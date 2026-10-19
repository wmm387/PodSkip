package com.zero.podskip.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.SystemClock
import android.view.Display
import android.view.KeyEvent
import android.view.Surface
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import com.zero.podskip.Mode
import com.zero.podskip.Settings

class SwipeKeyService : AccessibilityService() {

    companion object {
        private const val TAG = "PodSkip"

        /**
         * 当前运行中的服务实例。设置页/引导页改完保活开关或授予通知权限后，
         * 直接调 [refreshForeground] 立即生效，不用等下一次窗口切换。
         */
        @Volatile
        var instance: SwipeKeyService? = null
            private set
    }

    @Volatile
    private var fgPkg: String = ""

    private val systemOverlays = setOf(
        "com.android.systemui",
        "com.android.permissioncontroller",
        "com.google.android.inputmethod.latin"
    )

    override fun onCreate() {
        super.onCreate()
        instance = this
        Settings.init(this)
        // 默认不常驻：等白名单 App 出现在前台时再挂前台通知
        if (Settings.enabled) goForeground(fgPkg.takeIf { it.isNotEmpty() })
        wearDetector = WearDetector(this, ::onHeadsetOff, ::onHeadsetOn).also { it.start() }
    }

    /** 设置改动后立即重算前台通知状态（开着就常驻，关掉才撤） */
    fun refreshForeground() {
        syncForeground(fgPkg.takeIf { it.isNotEmpty() })
    }

    /** 当前是否已挂前台通知 */
    private var fgActive = false

    /** 前台通知里正在显示的应用 */
    private var notifiedPkg: String? = null

    /**
     * 保活通知始终和「总开关」绑定：
     * 开关开着就常驻一条通知（前台服务），关掉才撤。
     * 之前做成「只在白名单应用前台时才挂」，结果是服务在后台被系统冻结，
     * 连窗口事件都收不到，永远等不到「白名单应用出现」那一刻，直接死锁。
     */
    private fun syncForeground(pkg: String?) {
        if (Settings.enabled) {
            if (!fgActive || notifiedPkg != pkg) goForeground(pkg)
        } else if (fgActive) {
            stopForegroundService()
        }
    }

    private fun stopForegroundService() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Throwable) {
        }
        fgActive = false
        notifiedPkg = null
    }

    private fun goForeground(pkg: String?) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            // v1 用了 IMPORTANCE_MIN，小米会把它折叠进「静默通知」甚至不显示，
            // 换成 v2 + IMPORTANCE_LOW：仍然不响不亮，但能稳定出现在通知栏。
            val chId = "podskip_running_v2"
            if (nm.getNotificationChannel(chId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(chId, "按键监听", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "看剧时显示，用来防止系统回收按键监听服务"
                        setShowBadge(false)
                        enableVibration(false)
                        setSound(null, null)
                        lockscreenVisibility = Notification.VISIBILITY_SECRET
                    }
                )
            }
            nm.deleteNotificationChannel("podskip_running")
            val pi = PendingIntent.getActivity(
                this,
                0,
                Intent(this, com.zero.podskip.MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val title = if (pkg != null) "正在接管：${labelOf(pkg)}" else "耳机切剧待机中"
            val n = Notification.Builder(this, chId)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText("双击下一个 · 三击上一个 · 单击暂停")
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
            startForeground(1001, n)
            fgActive = true
            notifiedPkg = pkg
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "startForeground failed: ${e.message}")
        }
    }

    private fun labelOf(pkg: String): String = try {
        val ai = packageManager.getApplicationInfo(pkg, 0)
        packageManager.getApplicationLabel(ai).toString()
    } catch (_: Throwable) {
        pkg
    }

    override fun onServiceConnected() {
        instance = this
        android.util.Log.d(TAG, "service connected")
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
        if (Settings.enabled) goForeground(fgPkg.takeIf { it.isNotEmpty() })
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        wearDetector?.stop()
        wearDetector = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        android.util.Log.d(TAG, "evt=${event?.eventType} pkg=${event?.packageName} fg=$fgPkg")
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg in systemOverlays) return
        if (pkg != fgPkg) {
            fgPkg = pkg
            AppUsageTracker.record(this, pkg)
            syncForeground(pkg)
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        android.util.Log.d(
            TAG,
            "key=${event.keyCode} act=${event.action} rep=${event.repeatCount} fg=$fgPkg " +
                "enabled=${Settings.enabled} wl=${Settings.whitelist.size}"
        )
        val hookKey = event.keyCode == KeyEvent.KEYCODE_HEADSETHOOK ||
            event.keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE

        // 长按防误触：凡是我们吞下的按下，把它的重复事件和松开也一起吞掉。
        // 系统凑不出完整的「长按」序列，就不会当成语音助手。
        if (Settings.swallowLongPress && hookKey && swallowChain) {
            if (event.action == KeyEvent.ACTION_UP || event.repeatCount > 0) {
                if (event.action == KeyEvent.ACTION_UP) swallowChain = false
                return true
            }
        }

        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return false

        // 无条件记录，引导页要用它做「按一下试试」的检测
        KeyProbe.record(event.keyCode)

        if (!Settings.enabled) {
            swallowChain = false
            return false
        }
        if (fgPkg !in Settings.whitelist) {
            android.util.Log.d(TAG, "blocked: fg=$fgPkg not in whitelist")
            swallowChain = false
            return false
        }

        // 用户亲手按了耳机键，说明他自己在控制播放，撤销「摘下自动暂停」的标记
        clearAutoPause()

        return when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                if (Settings.mode == Mode.COUNT) {
                    false
                } else {
                    swipe(up = true)
                    true
                }
            }

            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                if (Settings.mode == Mode.COUNT) {
                    false
                } else {
                    swipe(up = false)
                    true
                }
            }

            KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                if (Settings.mode == Mode.PASSTHROUGH || !Settings.singleTapTakeover) {
                    false
                } else {
                    MultiTap.feed(Settings.tapWindowMs) { tap ->
                        android.util.Log.d(TAG, "resolved tap=$tap")
                        when (tap) {
                            Tap.SINGLE -> singleTapGesture()
                            Tap.DOUBLE -> swipe(up = true)
                            Tap.TRIPLE -> swipe(up = false)
                        }
                    }
                    swallowChain = true
                    true
                }
            }

            else -> false
        }
    }

    /**
     * 上一次按下是否被我们吞掉了。
     * 只有吞过按下才会去吞对应的松开——透传模式下按下已经交给系统，
     * 松开也必须交给它，否则系统会收到残缺的按键序列。
     */
    private var swallowChain = false

    // ---------- 佩戴检测 ----------

    private var wearDetector: WearDetector? = null

    /** 当前的暂停是「摘下耳机」自动造成的，才允许自动恢复 */
    private var autoPausedByEar = false
    private var autoPausedAt = 0L

    private fun onHeadsetOff() {
        if (!Settings.wearAutoPause) return
        if (!canAct()) return
        // 本来就没在播（已经暂停/播完），再点一下反而会播起来
        if (!isMusicActive()) return
        autoPausedByEar = true
        autoPausedAt = SystemClock.uptimeMillis()
        singleTapGesture()
    }

    private fun onHeadsetOn() {
        if (!Settings.wearAutoPause || !Settings.wearAutoResume) return
        if (!autoPausedByEar) return
        val away = SystemClock.uptimeMillis() - autoPausedAt
        autoPausedByEar = false
        // 摘太久（默认超过 5 分钟）就不恢复了，免得隔夜戴上突然外放
        if (away > Settings.wearResumeWindowMs) return
        if (!canAct()) return
        // 耳机自己已经恢复播放了（部分耳机摘下/戴上有自己的播放控制），别再补一刀
        if (isMusicActive()) return
        singleTapGesture()
    }

    /** 系统当前是否有活跃的媒体流，用来判断「现在到底在不在播」 */
    private fun isMusicActive(): Boolean = try {
        getSystemService(AudioManager::class.java)?.isMusicActive == true
    } catch (_: Throwable) {
        false
    }

    /** 只在「总开关开 + 白名单应用在前台」时才真正动手 */
    private fun canAct(): Boolean =
        Settings.enabled && fgPkg.isNotEmpty() && fgPkg in Settings.whitelist

    /** 用户自己碰了耳机，就别再替他自动恢复了 */
    private fun clearAutoPause() {
        autoPausedByEar = false
    }

    /** 当前前台应用包名（白名单判断就靠它） */
    fun currentPackage(): String = fgPkg

    private fun realBounds() =
        getSystemService(WindowManager::class.java).currentWindowMetrics.bounds

    /** 屏幕当前旋转角度，仅用于日志与判断 */
    private fun rotationOf(): Int = try {
        getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
    } catch (_: Throwable) {
        Surface.ROTATION_0
    }

    /**
     * 是否处于横屏。
     * 主判据是屏幕物理旋转角（播放器进全屏会把 display 转到 90/270），
     * 再叠加 Configuration.orientation 兜底「应用声明了 landscape 但 display 未转」的机型差异。
     */
    private fun isLandscape(): Boolean {
        val rot = rotationOf()
        val byRotation = rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270
        val byConfig = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return byRotation || byConfig
    }

    private fun swipe(up: Boolean) {
        val b = realBounds()
        val x = b.exactCenterX()
        val h = b.height().toFloat()
        val fromY = h * if (up) Settings.swipeFromY else (1f - Settings.swipeFromY)
        val toY = h * if (up) Settings.swipeToY else (1f - Settings.swipeToY)

        val path = Path().apply {
            moveTo(x, fromY)
            lineTo(x, toY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(path, 0L, Settings.swipeDurationMs)
            )
            .build()
        android.util.Log.d(TAG, "dispatch ok=" + dispatchGesture(gesture, null, null))
    }

    /** 一次「点画面」：竖屏单击，横屏（播放器要双击才暂停）改成双击 */
    private fun singleTapGesture() {
        if (Settings.landscapeDoubleTap && isLandscape()) doubleTapCenter() else tapCenter()
    }

    private fun tapCenter() {
        val b = realBounds()
        val path = Path().apply { moveTo(b.exactCenterX(), b.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
            .build()
        android.util.Log.d(TAG, "dispatch ok=" + dispatchGesture(gesture, null, null))
    }

    /**
     * 在屏幕中央注入一次「双击」。
     * 两个 stroke 放在同一个手势里，间隔 120ms（远小于系统 300ms 双击阈值），
     * 播放器的 GestureDetector 会正常识别成双击 → 暂停/播放。
     */
    private fun doubleTapCenter() {
        val b = realBounds()
        val x = b.exactCenterX()
        val y = b.exactCenterY()
        val first = Path().apply { moveTo(x, y) }
        val second = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(first, 0L, 50L))
            .addStroke(GestureDescription.StrokeDescription(second, 120L, 50L))
            .build()
        android.util.Log.d(TAG, "dispatch ok=" + dispatchGesture(gesture, null, null))
    }

    override fun onInterrupt() {}
}
