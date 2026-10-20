package com.zero.podskip

import android.content.Context
import android.content.SharedPreferences

enum class Mode { AUTO, COUNT, PASSTHROUGH }

object Settings {
    private const val PREF = "podskip"
    private lateinit var sp: SharedPreferences

    /**
     * 全新安装时名单为空，由引导页第 5 步让用户自己勾选。
     * 不预置任何包名：不同渠道/版本的短剧 App 包名不一致，硬塞反而会写入无效项。
     */
    val DEFAULT_WHITELIST = emptySet<String>()

    /** 引导页候选：常见的短剧类应用 */
    val DRAMA_CANDIDATES = listOf(
        "com.dragon.read",   // 番茄小说
        "com.phoenix.read",  // 红果短剧
        "com.kylin.read"     // 红果漫剧
    )

    fun init(ctx: Context) {
        if (!::sp.isInitialized) {
            sp = ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            migrate()
        }
    }

    /**
     * 一次性配置迁移。
     * v1（早期版本）默认窗口 350ms，被实测证明余量不足；按键测试页也曾算出
     * 300ms 这类装不下真实双击的值。统一抬到 400ms，只做一次。
     */
    private fun migrate() {
        if (sp.getInt("config_version", 0) >= 2) return
        if (sp.getLong("tap_window", 400L) < 400L) {
            sp.edit().putLong("tap_window", 400L).apply()
        }
        sp.edit().putInt("config_version", 2).apply()
    }

    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(value) {
            sp.edit().putBoolean("enabled", value).apply()
        }

    var mode: Mode
        get() = runCatching { Mode.valueOf(sp.getString("mode", Mode.AUTO.name)!!) }
            .getOrDefault(Mode.AUTO)
        set(value) {
            sp.edit().putString("mode", value.name).apply()
        }

    /** 上滑起点，屏幕高度比例，默认 0.78 */
    var swipeFromY: Float
        get() = sp.getFloat("swipe_from_y", 0.78f)
        set(value) {
            sp.edit().putFloat("swipe_from_y", value).apply()
        }

    /** 上滑终点，屏幕高度比例，默认 0.22 */
    var swipeToY: Float
        get() = sp.getFloat("swipe_to_y", 0.22f)
        set(value) {
            sp.edit().putFloat("swipe_to_y", value).apply()
        }

    var swipeDurationMs: Long
        get() = sp.getLong("swipe_duration", 260L)
        set(value) {
            sp.edit().putLong("swipe_duration", value).apply()
        }

    /** 多击判定窗口，毫秒 */
    var tapWindowMs: Long
        get() = sp.getLong("tap_window", 400L)
        set(value) {
            sp.edit().putLong("tap_window", value).apply()
        }

    /** 单击是否接管（关闭后双击/三击失效） */
    var singleTapTakeover: Boolean
        get() = sp.getBoolean("single_tap_takeover", true)
        set(value) {
            sp.edit().putBoolean("single_tap_takeover", value).apply()
        }

    /**
     * 长按防误触：吞掉长按产生的重复/松开事件，系统凑不出长按序列，
     * 就不会把耳机键的长按当成语音助手唤起。默认开。
     */
    var swallowLongPress: Boolean
        get() = sp.getBoolean("swallow_long_press", true)
        set(value) {
            sp.edit().putBoolean("swallow_long_press", value).apply()
        }

    // ---------- 佩戴检测 ----------

    /** 耳机摘下（音频输出断开）时自动暂停。默认开 */
    var wearAutoPause: Boolean
        get() = sp.getBoolean("wear_auto_pause", true)
        set(value) {
            sp.edit().putBoolean("wear_auto_pause", value).apply()
        }

    /** 重新戴上（音频输出回来）时自动恢复播放。默认开，依赖 wearAutoPause */
    var wearAutoResume: Boolean
        get() = sp.getBoolean("wear_auto_resume", true)
        set(value) {
            sp.edit().putBoolean("wear_auto_resume", value).apply()
        }

    /**
     * 自动恢复时限：超过这个时长就不再自动播放，默认 5 分钟。
     * 防止晚上摘下耳机、第二天早上戴上时突然外放。
     */
    var wearResumeWindowMs: Long
        get() = sp.getLong("wear_resume_window_ms", 5 * 60_000L)
        set(value) {
            sp.edit().putLong("wear_resume_window_ms", value).apply()
        }

    /** 设备移除后等多久确认（避开 A2DP/SCO 之间的路由抖动） */
    val wearDebounceMs: Long get() = 500L

    /**
     * 横屏时单击耳机 = 向屏幕中央双击。
     * 播放器横屏后普遍是「单击出控制栏、双击才暂停」，不这样处理按一下只会亮一下进度条。
     */
    var landscapeDoubleTap: Boolean
        get() = sp.getBoolean("landscape_double_tap", true)
        set(value) {
            sp.edit().putBoolean("landscape_double_tap", value).apply()
        }

    /**
     * 蓝牙耳机按键接管。
     * 蓝牙按键走 AVRCP，由系统直接派给「正在放音频的应用」，无障碍看不到。
     * 开启后本应用在白名单 App 前台时会挂一条静音音轨 + PLAYING 的 MediaSession，
     * 让系统把媒体键判给本应用，再翻译成点击/滑动。
     * 代价：接管期间通知栏的媒体卡片显示本应用。
     */
    var btKeys: Boolean
        get() = sp.getBoolean("bt_keys", true)
        set(value) {
            sp.edit().putBoolean("bt_keys", value).apply()
        }

    /**
     * 引导是否已经走完过一次。
     * 只有从未走过引导（或服务没开过）才自动跳引导；走过一次之后
     * 就算系统把无障碍解绑了，也只停在主页提示，不再硬跳。
     */
    var onboardingDone: Boolean
        get() = sp.getBoolean("onboarding_done", false)
        set(value) {
            sp.edit().putBoolean("onboarding_done", value).apply()
        }

    var whitelist: Set<String>
        get() = sp.getStringSet("whitelist", DEFAULT_WHITELIST) ?: DEFAULT_WHITELIST
        set(value) {
            sp.edit().putStringSet("whitelist", value).apply()
        }

    fun addToWhitelist(pkg: String) {
        whitelist = whitelist + pkg
    }

    fun removeFromWhitelist(pkg: String) {
        whitelist = whitelist - pkg
    }

    /** 手势参数页的「恢复默认」：只重置滑动几何与判定窗口 */
    fun resetSwipe() {
        swipeFromY = 0.78f
        swipeToY = 0.22f
        swipeDurationMs = 260L
        tapWindowMs = 400L
    }

    /** 高级设置页的「恢复默认」：只重置开关与佩戴检测，不动滑动参数 */
    fun resetAdvanced() {
        singleTapTakeover = true
        landscapeDoubleTap = true
        swallowLongPress = true
        mode = Mode.AUTO
        wearAutoPause = true
        wearAutoResume = true
        wearResumeWindowMs = 5 * 60_000L
    }
}
