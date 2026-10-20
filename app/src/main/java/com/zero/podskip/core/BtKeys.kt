package com.zero.podskip.core

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.SystemClock
import android.view.KeyEvent
import kotlin.concurrent.thread

/**
 * 蓝牙耳机按键通道。
 *
 * 蓝牙按键走 AVRCP，由系统直接派给「正在放音频的那个应用」，不产生 KeyEvent，
 * 所以无障碍服务看不到（按键测试页也空白）。只挂一个 PLAYING 的 MediaSession
 * 没用——实测系统仍然投给播放器自己的会话；这里再补一条**静音音轨**，
 * 让自己在 AudioService 眼里也是「正在放媒体」的应用，才可能被选中。
 * 按键从 [MediaSession.Callback.onMediaButtonEvent] 进来，再交给和有线耳机同一套判定。
 *
 * 代价：接管期间通知栏的媒体卡片会变成「耳机切剧」。
 */
class BtKeys(
    private val ctx: Context,
    private val onKeyDown: (Int) -> Boolean,
    /** 每 2 秒问一次「现在还该不该接管」：离开白名单、关开关、蓝牙断开都要能自动让出 */
    private val stillWanted: () -> Boolean
) {

    private var session: MediaSession? = null
    private var track: AudioTrack? = null
    private var pump: Thread? = null
    private var taken = false
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun ensureSession(): MediaSession {
        session?.let { return it }
        val s = MediaSession(ctx, "PodSkipBtKeys")
        s.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS)
        s.setCallback(object : MediaSession.Callback() {
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                val e: KeyEvent = mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
                    ?: return false
                if (e.action != KeyEvent.ACTION_DOWN || e.repeatCount > 0) return true
                Logs.d(TAG, "bt key=${e.keyCode}")
                return onKeyDown(e.keyCode)
            }
        })
        session = s
        return s
    }

    /** 静音音轨：只是为了让自己出现在 AudioService 的「正在播放」列表里，听不到任何声音 */
    private fun startSilent() {
        if (track != null) return
        try {
            val fmt = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(8000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val t = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(1280)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            t.play()
            track = t
            val buf = ByteArray(640) // 40ms @ 8kHz 16bit 单声道，全 0 即静音
            pump = thread(name = "podskip-silent", isDaemon = true) {
                try {
                    while (track === t) {
                        t.write(buf, 0, buf.size)
                    }
                } catch (_: Throwable) {
                }
            }
        } catch (e: Throwable) {
            Logs.w(TAG, "silent audio failed: ${e.message}")
        }
    }

    private fun stopSilent() {
        val t = track ?: return
        track = null
        try {
            t.pause()
            t.flush()
            t.stop()
            t.release()
        } catch (_: Throwable) {
        }
        pump = null
    }

    fun isTaken(): Boolean = taken

    fun setTakingOver(take: Boolean) {
        if (take) claim() else releaseNow()
    }

    private fun claim() {
        if (taken) {
            // 已经在接管，只补状态，不重复抢
            try {
                session?.setPlaybackState(playing())
            } catch (_: Throwable) {
            }
            return
        }
        try {
            val s = ensureSession()
            // 先出声，再声明播放：顺序反过来系统可能还认为播放器才是活跃的那个
            startSilent()
            if (!s.isActive) s.isActive = true
            s.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "耳机切剧")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "按键接管中")
                    .build()
            )
            s.setPlaybackState(paused())
            s.setPlaybackState(playing())
            taken = true
            tick = 0
            uiHandler.removeCallbacks(keeper)
            uiHandler.postDelayed(keeper, 1000L)
            Logs.d(TAG, "bt claim active=${s.isActive} silent=${track != null}")
        } catch (e: Throwable) {
            // 半路失败绝不能留下还在出声的静音音轨——它会一直把自己顶成
            // 系统认定的「媒体按键会话」，别的播放器就再也收不到播放键了
            Logs.w(TAG, "bt claim failed: ${e.message}")
            releaseNow()
        }
    }

    /**
     * 彻底让出。
     *
     * [taken] 只表示「逻辑上是否接管」，不能拿来当「有没有资源要清」的判据：
     * 接管中途抛异常时可能音轨已经开出来了而 taken 还没置 true，
     * 这种半成品状态下如果直接 return，静音音轨会一直播下去，
     * 系统就永远把媒体键派给本应用，音乐 App 只能暂停不能播放。
     * 所以这里无条件清理。
     */
    private fun releaseNow() {
        uiHandler.removeCallbacks(keeper)
        val had = taken || track != null || session != null
        taken = false
        stopSilent()
        val s = session
        session = null
        if (s != null) {
            try {
                s.isActive = false
                s.release()
            } catch (_: Throwable) {
            }
        }
        if (had) Logs.d(TAG, "bt release")
    }

    /**
     * 处理完一个键后再抢一次。
     * 注入滑动后短剧会切下一个视频，它的音轨重新 start，系统就把媒体键判给它了，
     * 所以这里把自己的静音音轨重启，重新变成「最近开始播放的应用」。
     */
    fun reclaim() {
        if (!taken) return
        for (delay in longArrayOf(600L, 1400L)) {
            uiHandler.postDelayed({
                if (!taken) return@postDelayed
                restartSilent()
            }, delay)
        }
    }

    /**
     * 定时把自己重新变成「最近一个开始播放的应用」——短剧一换集就会把媒体键拿走。
     *
     * 检查「还该不该接管」要勤（1 秒），否则切到音乐 App 后按键还会落空；
     * 但重建静音音轨不必那么频繁（每 3 个 tick 一次），省得一直在开开关关音轨。
     */
    private var tick = 0

    private val keeper = object : Runnable {
        override fun run() {
            if (!taken) return
            // 蓝牙耳机拔了 / 关了开关 / 离开白名单，都在这里自动让出
            if (!stillWanted()) {
                Logs.d(TAG, "bt auto release")
                releaseNow()
                return
            }
            tick++
            if (tick % 3 == 0) restartSilent()
            uiHandler.postDelayed(this, 1000L)
        }
    }

    private fun restartSilent() {
        try {
            stopSilent()
            startSilent()
            session?.setPlaybackState(playing())
        } catch (_: Throwable) {
        }
    }

    fun release() = releaseNow()

    private fun playing(): PlaybackState = PlaybackState.Builder()
        .setActions(
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or
                PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT or
                PlaybackState.ACTION_SKIP_TO_PREVIOUS
        )
        .setState(PlaybackState.STATE_PLAYING, 0L, 1f, SystemClock.elapsedRealtime())
        .build()

    private fun paused(): PlaybackState = PlaybackState.Builder()
        .setActions(PlaybackState.ACTION_PLAY_PAUSE)
        .setState(PlaybackState.STATE_PAUSED, 0L, 1f, SystemClock.elapsedRealtime())
        .build()

    companion object {
        private const val TAG = "PodSkip"
    }
}
