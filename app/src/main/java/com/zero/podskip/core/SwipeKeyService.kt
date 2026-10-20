package com.zero.podskip.core

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.SystemClock
import android.view.Display
import android.view.KeyEvent
import android.view.Surface
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
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

    /**
     * 当前接管的应用包名。
     * 只允许「用户自己安装的应用」和「桌面」写它，系统窗口一律不认，见 [trackForeground]。
     */
    @Volatile
    private var fgPkg: String = ""

    private val systemOverlays = setOf(
        "com.android.systemui",
        "com.android.permissioncontroller",
        "com.google.android.inputmethod.latin"
    )

    /** 包名 -> 是否用户自己安装的应用，避免每次按键都查一次 PackageManager */
    private val userAppCache = HashMap<String, Boolean>()

    /**
     * 输入法包名。键盘弹出时它也会发 TYPE_WINDOW_STATE_CHANGED，
     * 而且它多半是用户自己装的（第三方输入法没有 FLAG_SYSTEM），
     * 不排掉的话在短剧里点一下搜索框，fgPkg 就变成输入法，按键随即失效。
     */
    private val imePkgs: Set<String> by lazy {
        try {
            getSystemService(InputMethodManager::class.java)
                .enabledInputMethodList.map { it.packageName }.toSet()
        } catch (_: Throwable) {
            emptySet()
        }
    }

    /** 桌面包名，允许它更新 fgPkg（回到桌面就不再接管），但它本身不算用户应用 */
    private val launcherPkg: String by lazy {
        try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName.orEmpty()
        } catch (_: Throwable) {
            ""
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Settings.init(this)
        Logs.init(this)
        Logs.d(TAG, "service created")
        Logs.startBeat()
        btKeys = BtKeys(this, { code -> onBtKey(code) }, ::shouldTakeBt)
        // 总开关开着就常驻前台通知，纯保活用，不参与任何应用判断
        if (Settings.enabled) goForeground()
        wearDetector = WearDetector(this, ::onHeadsetOff, ::onHeadsetOn).also { it.start() }
    }

    /** 蓝牙耳机通道（静音音轨 + MediaSession），见 [BtKeys] */
    private var btKeys: BtKeys? = null

    /** 蓝牙按键进来后走和有线耳机一样的动作，但**不判双击**：蓝牙耳机自己会区分单击/双击 */
    private fun onBtKey(code: Int): Boolean {
        KeyProbe.record(code)
        if (!Settings.enabled) return false
        if (fgPkg.isEmpty()) trackForeground(liveWindowPkg())
        if (fgPkg !in Settings.whitelist) return false
        clearAutoPause()
        val handled = when (code) {
            KeyEvent.KEYCODE_MEDIA_NEXT ->
                if (Settings.mode == Mode.COUNT) false else {
                    swipe(up = true)
                    true
                }

            KeyEvent.KEYCODE_MEDIA_PREVIOUS ->
                if (Settings.mode == Mode.COUNT) false else {
                    swipe(up = false)
                    true
                }

            KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE ->
                if (Settings.mode == Mode.PASSTHROUGH || !Settings.singleTapTakeover) false else {
                    // 蓝牙单击只发一个键码，不用等双击窗口
                    singleTapGesture()
                    true
                }

            else -> false
        }
        if (handled) btKeys?.reclaim()
        return handled
    }

    /** 白名单应用在前台就接管；离开、关总开关、关掉该功能、蓝牙断开都自动让出 */
    private fun updateBtTakeover() {
        val take = shouldTakeBt()
        btRelease?.let { uiHandler.removeCallbacks(it) }
        btRelease = null
        if (take) {
            btKeys?.setTakingOver(true)
        } else if (btKeys?.isTaken() == true) {
            // 窗口事件会抖（系统弹窗、桌面、输入法），晚 2 秒再让出
            val r = Runnable {
                btRelease = null
                btKeys?.setTakingOver(false)
            }
            btRelease = r
            uiHandler.postDelayed(r, 2000)
        }
    }

    /** 接管媒体键的全部前提条件；[BtKeys] 每 2 秒会回来问一次 */
    private fun shouldTakeBt(): Boolean =
        Settings.btKeys && Settings.enabled &&
            fgPkg.isNotEmpty() && fgPkg in Settings.whitelist && hasBtAudio()

    /**
     * 当前有没有连着蓝牙音频设备（A2DP / BLE）。
     * 没蓝牙耳机时抢媒体键毫无意义，只会白挂一条静音音轨、占着通知栏媒体卡片。
     * 列表为空按「有」处理——查询异常时宁可不影响原有功能。
     */
    private fun hasBtAudio(): Boolean = try {
        val outs = getSystemService(AudioManager::class.java)
            .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        outs.isEmpty() || outs.any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
        }
    } catch (_: Throwable) {
        true
    }

    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var btRelease: Runnable? = null

    /** 设置改动后立即重算前台通知状态（开着就常驻，关掉才撤），并刷新通知文案 */
    fun refreshForeground() {
        syncForeground()
        updateNotification()
        updateBtTakeover()
    }

    /** 当前是否已挂前台通知 */
    private var fgActive = false

    fun isForegroundActive(): Boolean = fgActive

    /**
     * 保活通知只跟「总开关」绑定：开着就常驻，关掉才撤。
     * 通知内容不反映任何应用信息——之前按应用动态改标题，
     * 反而让通知变成一层额外的状态机，出问题更难查。
     */
    private fun syncForeground() {
        if (Settings.enabled) {
            if (!fgActive) goForeground()
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
    }

    private fun goForeground() {
        try {
            val text = statusText()
            startForeground(1001, buildNotification(text))
            notifyText = text
            fgActive = true
            Logs.d(TAG, "foreground on: $text")
        } catch (e: Throwable) {
            Logs.w(TAG, "startForeground failed: ${e.message}")
        }
    }

    /** 上一次写进通知的文案，一样就不再重复 notify */
    private var notifyText = ""

    private val labelCache = HashMap<String, String>()

    private fun labelOf(pkg: String): String = labelCache.getOrPut(pkg) {
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Throwable) {
            pkg
        }
    }

    /**
     * 通知栏文案：白名单应用在前台时显示「正在接管：xx」，
     * 其它时候回到手势提示。跟白名单联动，用户加了/删了应用立刻变。
     */
    private fun statusText(): String {
        val pkg = fgPkg
        return if (pkg.isNotEmpty() && pkg in Settings.whitelist && pkg != packageName) {
            "正在接管：${labelOf(pkg)}"
        } else {
            "双击下一个 · 三击上一个 · 单击暂停"
        }
    }

    /** 接管的应用变了就换一行通知，别的场景不动 */
    private fun updateNotification() {
        if (!fgActive) return
        val text = statusText()
        if (text == notifyText) return
        notifyText = text
        try {
            getSystemService(NotificationManager::class.java).notify(1001, buildNotification(text))
        } catch (_: Throwable) {
        }
    }

    private fun buildNotification(text: String): Notification {
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
        return Notification.Builder(this, chId)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("耳机切剧正在运行")
            .setContentText(text)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    override fun onServiceConnected() {
        instance = this
        Logs.d(TAG, "service connected")
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
        if (Settings.enabled) goForeground()
        // 重装 / 重开无障碍时，短剧可能已经在播放了，补一次前台应用
        android.os.Handler(mainLooper).postDelayed({
            if (fgPkg.isEmpty()) trackForeground(liveWindowPkg())
            updateBtTakeover()
        }, 900)
    }

    override fun onDestroy() {
        Logs.d(TAG, "service destroyed")
        Logs.stopBeat()
        btKeys?.release()
        btKeys = null
        if (instance === this) instance = null
        wearDetector?.stop()
        wearDetector = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        trackForeground(event.packageName?.toString().orEmpty())
    }

    /**
     * 更新「当前接管的应用」。
     *
     * 只接受用户自己安装的应用和桌面。MIUI 大量场景会弹自己的系统窗口
     * （耳机音效 com.miui.misound、状态栏插件 miui.systemui.plugin、
     * 全局搜索 com.android.quicksearchbox 等），它们同样发 TYPE_WINDOW_STATE_CHANGED。
     * 以前不加过滤跟着更新，前台应用就会被系统窗口顶掉，白名单判定随即失败，
     * 表现就是「按耳机没反应」；而之后没有新的窗口事件把它改回来，
     * 直到拉一下通知栏再收起、重新触发短剧 App 的窗口事件才恢复。
     */
    private fun trackForeground(pkg: String) {
        if (pkg.isEmpty() || pkg == packageName) return
        if (pkg in systemOverlays || pkg in imePkgs) return
        if (!isUserApp(pkg) && pkg != launcherPkg) return
        if (pkg == fgPkg) return
        fgPkg = pkg
        Logs.d(TAG, "fg -> $pkg")
        updateNotification()
        updateBtTakeover()
        if (pkg != launcherPkg) AppUsageTracker.record(this, pkg)
    }

    /** 是不是用户自己装的（非系统应用）。系统应用永远不可能是要接管的短剧 App */
    private fun isUserApp(pkg: String): Boolean = userAppCache.getOrPut(pkg) {
        try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            (info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0
        } catch (_: Throwable) {
            false
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        Logs.d(TAG, "key=${event.keyCode} a=${event.action} r=${event.repeatCount} fg=$fgPkg")
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

        // 服务刚连上、还没收到过窗口事件时补一次，否则「打开 App 直接按」会被挡掉
        if (fgPkg.isEmpty()) trackForeground(liveWindowPkg())

        if (fgPkg !in Settings.whitelist) {
            Logs.d(TAG, "skip pkg=$fgPkg")
            swallowChain = false
            return false
        }

        // 用户亲手按了耳机键，说明他自己在控制播放，撤销「摘下自动暂停」的标记
        clearAutoPause()

        val handled = perform(event.keyCode)
        if (handled) swallowChain = true
        return handled
    }

    /**
     * 按键 → 手势。有线耳机（无障碍按键）和蓝牙耳机（MediaSession）两条通道共用，
     * 保证同一副耳机换个连接方式行为不变。
     */
    private fun perform(code: Int): Boolean = when (code) {
        KeyEvent.KEYCODE_MEDIA_NEXT -> {
            if (Settings.mode == Mode.COUNT) false else {
                swipe(up = true)
                true
            }
        }

        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
            if (Settings.mode == Mode.COUNT) false else {
                swipe(up = false)
                true
            }
        }

        KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
            if (Settings.mode == Mode.PASSTHROUGH || !Settings.singleTapTakeover) {
                false
            } else {
                MultiTap.feed(Settings.tapWindowMs) { tap ->
                    Logs.d(TAG, "tap=$tap")
                    when (tap) {
                        Tap.SINGLE -> singleTapGesture()
                        Tap.DOUBLE -> swipe(up = true)
                        Tap.TRIPLE -> swipe(up = false)
                    }
                }
                true
            }
        }

        else -> false
    }

    /** 服务刚连上、还没收到窗口事件时的兜底：直接问窗口现在是谁 */
    private fun liveWindowPkg(): String = try {
        rootInActiveWindow?.packageName?.toString().orEmpty()
    } catch (_: Throwable) {
        ""
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

    /** 屏幕当前旋转角度，仅用于判断 */
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
        Logs.d(TAG, "dispatch=" + dispatchGesture(gesture, gestureCallback, null))
    }

    private val gestureCallback = object : GestureResultCallback() {
        override fun onCompleted(gestureDescription: GestureDescription?) {
            Logs.d(TAG, "gesture done")
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            Logs.d(TAG, "gesture CANCELLED")
        }
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
        Logs.d(TAG, "dispatch=" + dispatchGesture(gesture, gestureCallback, null))
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
        Logs.d(TAG, "dispatch=" + dispatchGesture(gesture, gestureCallback, null))
    }

    override fun onInterrupt() {}
}
