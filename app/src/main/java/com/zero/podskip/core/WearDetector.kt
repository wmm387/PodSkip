package com.zero.podskip.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.zero.podskip.Settings

/**
 * 佩戴检测（近似实现）。
 *
 * Android 没有公开的「入耳状态」接口，能拿到的只有音频输出设备的去留：
 * 耳机被摘到断开、线控被拔出、蓝牙掉线时，这个设备会从输出列表消失。
 * 因此这里的「摘下」= 耳机类输出设备全部消失；「戴上」= 它又回来了。
 *
 * 两条信号叠加：
 * 1. [AudioManager.registerAudioDeviceCallback] —— 设备增删；
 * 2. [AudioManager.ACTION_AUDIO_BECOMING_NOISY] —— 拔出/断开瞬间的系统广播，比回调更快。
 *
 * 去抖：设备移除后等 [Settings.wearDebounceMs] 再确认，避开 A2DP ↔ SCO 之间
 * 切换路由时的瞬时抖动（否则通话/切歌时会误暂停）。
 */
class WearDetector(
    context: Context,
    private val onHeadsetOff: () -> Unit,
    private val onHeadsetOn: () -> Unit
) {
    private val ctx = context.applicationContext
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var started = false

    /** 当前是否接着耳机（含蓝牙、有线、USB） */
    private var present = false

    /** 已经因为「没了」通知过上层，等它回来时要发「回来了」 */
    private var offNotified = false

    private val confirmOff = Runnable {
        // 只有在「之前确实接着耳机」时才通知：没戴耳机时收到的
        // BECOMING_NOISY（比如手动切扬声器）不算摘下，否则会凭空暂停。
        if (!hasHeadset() && present) {
            present = false
            offNotified = true
            onHeadsetOff()
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            if (!hasHeadset()) return
            handler.removeCallbacks(confirmOff)
            if (!present) {
                present = true
                if (offNotified) {
                    offNotified = false
                    onHeadsetOn()
                }
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            if (!hasHeadset()) scheduleOffCheck()
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
            if (!hasHeadset()) scheduleOffCheck()
        }
    }

    private fun scheduleOffCheck() {
        handler.removeCallbacks(confirmOff)
        handler.postDelayed(confirmOff, Settings.wearDebounceMs)
    }

    fun start() {
        if (started) return
        started = true
        present = hasHeadset()
        offNotified = false
        runCatching {
            am.registerAudioDeviceCallback(deviceCallback, handler)
            ContextCompat.registerReceiver(
                ctx,
                noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
    }

    fun stop() {
        if (!started) return
        started = false
        handler.removeCallbacks(confirmOff)
        runCatching { am.unregisterAudioDeviceCallback(deviceCallback) }
        runCatching { ctx.unregisterReceiver(noisyReceiver) }
    }

    fun hasHeadset(): Boolean =
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.isHeadsetLike() }

    private fun AudioDeviceInfo.isHeadsetLike(): Boolean = when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_HEARING_AID -> true

        AudioDeviceInfo.TYPE_BLE_HEADSET -> Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        else -> false
    }
}
