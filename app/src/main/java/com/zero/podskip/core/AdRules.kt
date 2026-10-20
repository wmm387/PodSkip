package com.zero.podskip.core

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

enum class AdActionType { CLICK, SWIPE_UP }

/**
 * 一次「跳过广告」的决策结果。
 *
 * @param node 命中的节点。点击类是可点击目标，上滑类只用来打日志。
 * @param reason 命中的规则名，写进日志，出问题一眼能看出是哪条规则误判。
 * @param immediate 不走「等倒计时」流程。视频层渲染的广告读不到倒计时，
 *        也没有可点的播放按钮，只能直接划走。
 */
data class AdAction(
    val type: AdActionType,
    val node: AccessibilityNodeInfo? = null,
    val reason: String = "",
    val immediate: Boolean = false,
)

/**
 * 广告识别规则（实验功能）。
 *
 * 参考 https://github.com/clarithromycine/ad-auto 的规则表，按 PodSkip 的场景重写：
 * 只保留「读到文字 → 点跳过 / 上滑划走」这一类确定性高的规则，去掉点屏幕暂停、
 * 自定义关键词等实验性更强的分支。
 *
 * 匹配顺序（顺序本身就是安全策略，改动前先想清楚误伤代价）：
 * 1. 明确的「跳过广告」按钮 —— 语义无歧义，直接点
 * 2. 「上滑继续观看」提示 —— 明确的方向指令，直接划
 * 3. 正剧播放保护 —— 出现播放器控件就什么都不做，这是防误伤的核心
 * 4. 已知广告的形态特征（渲染视图 / 商城卡片 / 右上角角标 / 立即领取）
 * 5. 需要广告上下文的点击规则（跳过、关闭、知道了、继续观看）
 */
object AdRules {

    private val WS = Regex("\\s+")

    /** 「上滑继续观看」类提示。带明确方向，正常播放不会出现 */
    private val SWIPE_UP_KEYWORDS = listOf(
        "上滑继续观看", "上滑继续", "上滑继续看", "上滑看短剧", "上滑解锁", "上滑看下一集",
        "向上滑动继续观看",
    )

    /**
     * 正剧播放器的特征控件文字。命中任意一个就判定「现在播的是正剧」，
     * 除了上面两条硬信号，一律不点不划——误划一下就是跳过一整条视频，代价太高。
     */
    private val PLAYBACK_CONTROL_KEYWORDS = listOf(
        "倍速", "选集", "热评", "分享", "评论", "展开", "暂停", "下一集",
        "全集", "已完结", "作者声明", "跟播", "点赞", "收藏", "弹幕",
    )

    private val EPISODE_REGEX = Regex("第\\d+集")

    private val AD_CONTEXT_KEYWORDS = listOf("广告", "advertisement", "赞助")

    /** 广告倒计时文案（“3秒后可继续”“5s后继续观看”），本身就是广告的强信号 */
    private val COUNTDOWN_KEYWORDS = listOf(
        "秒后可继续", "s后可继续", "S后可继续",
        "秒后继续", "s后继续",
        "后可继续", "后继续观看", "后继续播放", "后可观看",
        "倒计时", "countdown",
    )

    /** 横屏全屏广告的「滑动锁」：锁期内怎么划都没用，只能当倒计时等 */
    private val SWIPE_LOCK_KEYWORDS = listOf("后可继续上滑", "后可上滑", "秒后可上滑", "s后可上滑")

    private val COUNTDOWN_REGEX = Regex("\\d+\\s*(?:秒|s|S)\\s*(?:后|后可继续|后继续|后可观看|后观看|后播放)?")

    private val COUNTDOWN_NUMBER_REGEX = Regex(
        "(?:广告|倒计时)\\s*(\\d+)(?:\\s*(?:秒|s|S))?|(\\d+)\\s*(?:秒|s|S)"
    )

    /** 「即将进入广告」前置提示：这时正剧还在播，不能动手 */
    private val UPCOMING_AD_KEYWORDS = listOf(
        "进入广告", "播放广告", "广告即将", "即将播放广告", "即将进入广告",
        "后进入广告", "后播放广告", "开始播放广告", "即将开始播放", "即将开始广告",
    )

    /** 直播样式广告的具名渲染视图：整屏画在视频层，无障碍树里一个字都没有 */
    const val AD_RENDER_VIEW_ID = "ttlive_player_render_view"
    const val REASON_LIVE_AD = "直播广告(渲染视图)"
    const val REASON_SHOP_CARD_AD = "商城卡片广告"

    /** 商城卡片的固定 banner；红果图文广告卡住时整棵树只剩它和「领取」 */
    private const val SHOP_CARD_BANNER = "好物省心选"
    private const val MAX_SHOP_CARD_TEXT_NODES = 6

    /** 仅对红果开启「立即领取 → 上滑」这条启发式，其它 App 没有样本验证过 */
    private const val RED_FRUIT_PKG = "com.phoenix.read"

    data class ClickRule(
        val name: String,
        val texts: List<String>,
        /** 需要页面出现「广告」字样或倒计时，避免把普通弹窗的「关闭」当成广告 */
        val requireAdContext: Boolean = true,
        /** 需要出现广告倒计时但当前还没走完 */
        val requireCountdownDone: Boolean = false,
    )

    /** 语义明确的跳过按钮，不需要任何上下文 */
    private val HARD_SKIP_TEXTS = listOf("跳过广告", "跳过此广告", "跳過廣告", "Skip Ad", "SkipAd")

    private val CLICK_RULES = listOf(
        ClickRule("跳过", listOf("跳过", "跳過", "skip", "Skip")),
        ClickRule("关闭", listOf("关闭广告", "关闭", "×", "✕")),
        ClickRule("知道了", listOf("知道了", "确定")),
        // 倒计时结束后出现的按钮。必须等倒计时真的结束，否则点了只是重新锁一次
        ClickRule("继续观看", listOf("继续观看", "继续播放", "立即观看", "立即播放"), requireCountdownDone = true),
    )

    fun pageTextOf(nodes: List<AccessibilityNodeInfo>): String = buildString {
        for (n in nodes) {
            n.text?.toString()?.let { append(it).append(' ') }
            n.contentDescription?.toString()?.let { append(it).append(' ') }
        }
    }

    fun hasCountdown(pageText: String): Boolean {
        val compact = pageText.replace(WS, "")
        if (COUNTDOWN_KEYWORDS.any { pageText.contains(it, true) || compact.contains(it, true) }) return true
        return pageText.contains(COUNTDOWN_REGEX) || compact.contains(COUNTDOWN_REGEX)
    }

    fun hasAdContext(pageText: String): Boolean {
        val compact = pageText.replace(WS, "")
        return AD_CONTEXT_KEYWORDS.any { pageText.contains(it, true) || compact.contains(it, true) } ||
            hasCountdown(pageText)
    }

    fun hasUpcomingAd(pageText: String): Boolean {
        val compact = pageText.replace(WS, "")
        return UPCOMING_AD_KEYWORDS.any { pageText.contains(it) || compact.contains(it) }
    }

    fun hasPlaybackControls(pageText: String): Boolean {
        val compact = pageText.replace(WS, "")
        return PLAYBACK_CONTROL_KEYWORDS.any { pageText.contains(it) || compact.contains(it) } ||
            compact.contains(EPISODE_REGEX)
    }

    /**
     * 倒计时剩余秒数（取最小值，即最接近结束的那个）。解析不到返回 null——
     * 像「5s后可继续上滑观看」这种被画在视频层上的锁文案读不到数字，只能靠时间戳兜底。
     */
    fun remainingCountdownSeconds(pageText: String): Int? {
        val compact = pageText.replace(WS, "")
        var min: Int? = null
        for (text in arrayOf(pageText, compact)) {
            for (m in COUNTDOWN_NUMBER_REGEX.findAll(text)) {
                val v = m.groupValues[1].ifEmpty { m.groupValues[2] }.toIntOrNull() ?: continue
                if (v > 0 && (min == null || v < min)) min = v
            }
        }
        return min
    }

    /**
     * 判断当前界面是不是广告，以及该做什么。
     *
     * @param screenW/screenH 屏幕尺寸（像素），给「右上角广告角标」这类位置特征用；
     *        为 0 时跳过位置判断。
     */
    fun match(
        pkg: String,
        nodes: List<AccessibilityNodeInfo>,
        pageText: String,
        screenW: Int,
        screenH: Int,
    ): AdAction? {
        val compact = pageText.replace(WS, "")

        // 1) 明确的跳过按钮：不需要上下文，先于正剧保护——正剧页里也不可能出现「跳过广告」
        for (node in nodes) {
            if (!node.matchesAny(HARD_SKIP_TEXTS)) continue
            // 找不到可点祖先就把节点本身交给调用方，由它按坐标补一次点击：
            // 不少广告的「跳过」是自绘控件，无障碍查不到 CLICKABLE
            return AdAction(AdActionType.CLICK, findClickable(node) ?: node, "跳过广告")
        }

        // 2) 「上滑继续观看」：广告自己给的指令，比任何推断都可靠
        if (SWIPE_UP_KEYWORDS.any { pageText.contains(it) || compact.contains(it) }) {
            val hint = nodes.firstOrNull { it.matchesAny(SWIPE_UP_KEYWORDS) }
            return AdAction(AdActionType.SWIPE_UP, hint, "上滑继续观看")
        }

        // 3) 正剧保护：有播放器控件就收手。后面的规则全是启发式，误伤代价太大
        if (hasPlaybackControls(pageText)) return null

        // 4) 直播样式广告：整屏画在视频层，页面里一个字读不到，只能认这个具名 id
        nodes.firstOrNull { it.viewIdResourceName?.endsWith("/$AD_RENDER_VIEW_ID") == true }?.let {
            return AdAction(AdActionType.SWIPE_UP, it, REASON_LIVE_AD, immediate = true)
        }

        // 5) 红果穿山甲视频广告：提示词画在 Surface 上读不到，但「立即领取」是原生按钮
        if (pkg == RED_FRUIT_PKG) {
            val cta = nodes.firstOrNull {
                (it.text?.toString().orEmpty().contains("立即领取") ||
                    it.contentDescription?.toString().orEmpty().contains("立即领取")) &&
                    findClickable(it) != null
            }
            if (cta != null) return AdAction(AdActionType.SWIPE_UP, cta, "穿山甲广告(立即领取)")
        }

        // 6) 红果图文/商城卡片广告：只剩 banner 和「领取」两个文字节点，上滑能划走
        if (isShopCardAd(nodes, pageText)) {
            val banner = nodes.firstOrNull {
                it.contentDescription?.toString()?.contains(SHOP_CARD_BANNER) == true
            }
            return AdAction(AdActionType.SWIPE_UP, banner, REASON_SHOP_CARD_AD, immediate = true)
        }

        // 7) 右上角「广告」角标：全屏视频广告的原生标记，有倒计时时留给倒计时流程
        if (!hasCountdown(pageText) && !hasUpcomingAd(pageText)) {
            findAdBadgeNode(nodes, screenW, screenH)?.let {
                return AdAction(AdActionType.SWIPE_UP, it, "广告角标")
            }
        }

        // 8) 需要广告上下文的点击规则
        val adContext = hasAdContext(pageText)
        val countdownDone = !hasActiveCountdown(pageText)
        for (rule in CLICK_RULES) {
            if (rule.requireAdContext && !adContext) continue
            if (rule.requireCountdownDone && !countdownDone) continue
            for (node in nodes) {
                if (!node.matchesAny(rule.texts)) continue
                val target = findClickable(node) ?: continue
                return AdAction(AdActionType.CLICK, target, rule.name)
            }
        }

        return null
    }

    /** 页面上有还没走完的倒计时（读到具体秒数） */
    private fun hasActiveCountdown(pageText: String): Boolean =
        (remainingCountdownSeconds(pageText) ?: 0) > 0

    /**
     * 图文/商城卡片广告：出现固定 banner，且页面文字节点极少。
     * 正剧、内容页、商城页的文字远多于这个数，所以「少」本身就是判据的一部分。
     */
    private fun isShopCardAd(nodes: List<AccessibilityNodeInfo>, pageText: String): Boolean {
        val textNodes = nodes.count {
            !it.text.isNullOrEmpty() || !it.contentDescription.isNullOrEmpty()
        }
        if (textNodes == 0 || textNodes > MAX_SHOP_CARD_TEXT_NODES) return false
        return pageText.replace(WS, "").contains(SHOP_CARD_BANNER)
    }

    /**
     * 屏幕右上角的「广告」角标：Pangle 全屏广告统一放这个位置，横竖屏通用。
     * 只认不可点的节点，可点的「广告」多半是信息流里的广告卡片，点了会跳转。
     */
    private fun findAdBadgeNode(
        nodes: List<AccessibilityNodeInfo>,
        screenW: Int,
        screenH: Int,
    ): AccessibilityNodeInfo? {
        if (screenW <= 0 || screenH <= 0) return null
        val topLimit = screenH * 0.28f
        val rightLimit = screenW * 0.60f
        for (n in nodes) {
            val t = n.text?.toString()?.trim().orEmpty()
            val d = n.contentDescription?.toString()?.trim().orEmpty()
            if (t != "广告" && d != "广告") continue
            if (n.isClickable) continue
            val r = Rect()
            n.getBoundsInScreen(r)
            if (r.isEmpty) continue
            val cx = (r.left + r.right) / 2f
            val cy = (r.top + r.bottom) / 2f
            if (cy > topLimit || cx < rightLimit) continue
            return n
        }
        return null
    }

    /** 自身或最近的祖先里第一个可点击节点（广告按钮常把文字包在不可点的 TextView 里） */
    fun findClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = node
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur.isClickable) return cur
            cur = cur.parent
            depth++
        }
        return null
    }

    private fun AccessibilityNodeInfo.matchesAny(texts: List<String>): Boolean {
        val t = text?.toString().orEmpty()
        val d = contentDescription?.toString().orEmpty()
        return texts.any { t.contains(it) || d.contains(it) }
    }
}
