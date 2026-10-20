package com.zero.podskip.core

import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.zero.podskip.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 广告自动跳过（实验功能）。
 *
 * 参考 https://github.com/clarithromycine/ad-auto 的思路：
 * 界面变化 → 读当前应用的节点树 → [AdRules] 匹配 → 点「跳过」或上滑划走。
 *
 * 三条硬边界，改这段代码前先确认没破坏它们：
 * 1. **只在白名单应用里跑**。别的界面上一堆「关闭 / 跳过」按钮，误点一下就是别的后果。
 * 2. **只在总开关和本功能都打开时跑**。关掉后连界面事件都不收（见 SwipeKeyService.applyA11yConfig）。
 * 3. **正剧优先**。宁可漏跳一条广告，也不能把正在看的剧划走。
 *
 * 工作节奏：界面事件驱动，content-changed 事件 400ms 节流；每次动作后至少冷却 1.5 秒
 * （视频广告的按钮会残留），划完再补扫一次确认广告是否真的走了；同一条广告最多划 10 次
 * 或 30 秒，还没划走就停手。
 *
 * 为什么要留到 10 次：有些广告（尤其穿山甲系）划一次不生效，要等 3–6 秒落地动画放完
 * 才认，期间只能反复试。1.5 秒间隔刚好卡在「不连划到多跳集」和「不漏掉慢生效的广告」
 * 之间，实测这套节奏可用，别随手收紧。
 */
class AdDetector(
    private val svc: SwipeKeyService,
    private val swipeUp: () -> Unit,
) {

    companion object {
        private const val TAG = "PodSkip"

        /** content-changed 事件一秒能来十几次，读节点树不便宜，统一节流 */
        private const val SCAN_GAP_MS = 400L

        /** 两次动作之间的最小间隔：广告被划走后按钮/文案会残留一会儿 */
        private const val ACTION_COOLDOWN_MS = 1500L

        /** 穿山甲类广告残留更久，冷却拉长，避免同一个广告被反复划 */
        private const val LONG_COOLDOWN_MS = 6000L

        /** 倒计时结束后多等一点，给「上滑继续观看」文案留渲染时间 */
        private const val COUNTDOWN_BUFFER_MS = 400L

        /** 读不到的倒计时（文案画在视频层）按这个值兜底重扫 */
        private const val UNKNOWN_COUNTDOWN_MS = 2000L

        /** 异常文案（比如卡在 99 秒）不让它把等待无限拉长 */
        private const val MAX_COUNTDOWN_S = 30

        /** 一次节点遍历的上限，防止某些界面挂上千个节点把主线程拖住 */
        private const val MAX_NODES = 500
        private const val MAX_DEPTH = 40

        /** 同一条广告最多连续划几次。慢生效的广告要等 3–6 秒，留足重试机会 */
        private const val MAX_SWIPE_STREAK = 10

        /** 一条广告的总尝试预算 */
        private const val CHAIN_BUDGET_MS = 30_000L

        /** 距上次上滑超过这个时长，视为换了一条广告，重新计数 */
        private const val CHAIN_RESET_MS = 8_000L

        /** 没命中时的日志节流，不然看剧全程都在刷 */
        private const val LOG_GAP_MS = 10_000L
    }

    private val handler = Handler(Looper.getMainLooper())

    private var lastScanAt = 0L
    private var lastLogAt = 0L
    private var nextAllowedAt = 0L

    /** 连续上滑计数与本次「划不走」的起始时刻 */
    private var swipeStreak = 0
    private var chainStartedAt = 0L
    private var lastSwipeAt = 0L

    /** 最近一次检测结论，给设置页显示，见 [lastResultText] */
    @Volatile
    private var lastResult = ""

    private var pending: Runnable? = null

    // ---------- 入口 ----------

    /** 无障碍事件入口，由 [SwipeKeyService.onAccessibilityEvent] 转发 */
    fun onEvent(event: AccessibilityEvent) {
        if (!active()) return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return

        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // 换了窗口 = 换了上下文，别把上一条广告的冷却/计数带过来
            swipeStreak = 0
            chainStartedAt = 0L
        } else if (SystemClock.uptimeMillis() - lastScanAt < SCAN_GAP_MS) {
            return
        }
        scan()
    }

    /**
     * 最近一次检测结论（带时间），给设置页显示。
     *
     * 原来这里是个「立即检测当前界面」按钮，但按钮点在自己页面里，检测的永远是设置页，
     * 看不到短剧那边到底认没认出来——改成记录最近一次真实检测，回到设置页就能看。
     */
    fun lastResultText(): String = lastResult

    /** 功能被关掉时撤销待执行的补扫 */
    fun cancelPending() {
        pending?.let { handler.removeCallbacks(it) }
        pending = null
    }

    // ---------- 主流程 ----------

    private fun active(): Boolean = Settings.enabled && Settings.adSkip

    private fun scan() {
        if (!active()) {
            note("自动跳过已关闭")
            return
        }
        lastScanAt = SystemClock.uptimeMillis()

        val pkg = svc.currentPackage()
        if (pkg.isEmpty() || pkg == svc.packageName) {
            note("当前没有接管任何应用")
            return
        }
        if (pkg !in Settings.whitelist) {
            note("${labelOf(pkg)} 不在白名单")
            return
        }

        val nodes = collect(pkg)
        if (nodes.isEmpty()) {
            note("没读到界面节点")
            return
        }
        val pageText = AdRules.pageTextOf(nodes)
        val (w, h) = screenSize()
        val action = AdRules.match(pkg, nodes, pageText, w, h)
        if (action == null) {
            val msg = "未发现广告（节点 ${nodes.size}，文字 ${pageText.length}）"
            note(msg)
            logThrottled("ad miss pkg=$pkg $msg")
            return
        }

        val now = SystemClock.uptimeMillis()

        // 倒计时/滑动锁：先等结束再划，锁期内划了也是白划
        val seconds = AdRules.remainingCountdownSeconds(pageText)
        if (!action.immediate && action.type == AdActionType.SWIPE_UP && seconds != null && seconds > 0) {
            val sec = seconds
            val wait = sec.coerceAtMost(MAX_COUNTDOWN_S) * 1000L + COUNTDOWN_BUFFER_MS
            schedule(wait)
            val msg = "广告倒计时 ${sec}s，结束后再划（${action.reason}）"
            note(msg)
            Logs.d(TAG, "ad wait $msg")
            return
        }

        if (now < nextAllowedAt) return

        if (action.type == AdActionType.SWIPE_UP && !canSwipeAgain(now)) {
            note("同一条广告已划 $swipeStreak 次仍未走，停手等界面变化")
            Logs.w(TAG, "ad give up: streak=$swipeStreak reason=${action.reason}")
            return
        }

        val ok = when (action.type) {
            AdActionType.CLICK -> click(action.node)
            AdActionType.SWIPE_UP -> {
                swipeUp()
                true
            }
        }

        // 冷却统一 1.5 秒（穿山甲 6 秒）：慢生效的广告要留重试机会，
        // 靠 MAX_SWIPE_STREAK 兜住「连划导致多跳集」的风险
        val cooldown = if (action.reason.contains("穿山甲")) LONG_COOLDOWN_MS else ACTION_COOLDOWN_MS
        nextAllowedAt = now + cooldown
        if (action.type == AdActionType.SWIPE_UP) {
            if (swipeStreak == 0) chainStartedAt = now
            swipeStreak++
            lastSwipeAt = now
        }

        val verb = if (action.type == AdActionType.CLICK) "点击" else "上滑"
        Logs.d(
            TAG,
            "ad hit pkg=$pkg act=$verb reason=${action.reason} ok=$ok " +
                "nodes=${nodes.size} streak=$swipeStreak"
        )
        note((if (ok) "已$verb · " else "执行失败 · ") + action.reason)

        // 划完补扫一次：广告可能还在屏上（落在入场动画上、或滑动锁刚解除）
        if (ok && action.type == AdActionType.SWIPE_UP) schedule(cooldown)
    }

    private fun note(msg: String) {
        lastResult = stamp() + " " + msg
    }

    private fun stamp(): String =
        SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    private fun canSwipeAgain(now: Long): Boolean {
        if (swipeStreak > 0 && now - lastSwipeAt > CHAIN_RESET_MS) {
            swipeStreak = 0
            chainStartedAt = 0L
        }
        if (swipeStreak >= MAX_SWIPE_STREAK) return false
        return chainStartedAt == 0L || now - chainStartedAt <= CHAIN_BUDGET_MS
    }

    private fun schedule(delay: Long) {
        pending?.let { handler.removeCallbacks(it) }
        val r = Runnable {
            pending = null
            scan()
        }
        pending = r
        handler.postDelayed(r, delay)
    }

    // ---------- 读节点 / 执行动作 ----------

    /**
     * 收集目标应用当前可见的节点。
     *
     * 走 [android.accessibilityservice.AccessibilityService.getWindows] 而不是只用
     * rootInActiveWindow：广告经常挂在一个独立的窗口里（浮层、SurfaceView 容器），
     * 只读活动窗口会漏。只收包名匹配的窗口，系统弹窗和输入法天然被排掉。
     */
    private fun collect(pkg: String): List<AccessibilityNodeInfo> {
        val roots = ArrayList<AccessibilityNodeInfo>()
        try {
            for (w in svc.windows) {
                val r = w.root ?: continue
                if (r.packageName?.toString() == pkg && roots.none { it == r }) roots.add(r)
            }
        } catch (_: Throwable) {
        }
        try {
            svc.rootInActiveWindow?.let { r ->
                if (r.packageName?.toString() == pkg && roots.none { it == r }) roots.add(r)
            }
        } catch (_: Throwable) {
        }

        val out = ArrayList<AccessibilityNodeInfo>()
        val queue = java.util.ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        for (r in roots) queue.add(r to 0)
        while (queue.isNotEmpty() && out.size < MAX_NODES) {
            val (n, depth) = queue.poll() ?: break
            if (n.isVisibleToUser) out.add(n)
            if (depth >= MAX_DEPTH) continue
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                queue.add(c to depth + 1)
            }
        }
        return out
    }

    /** 先试可点击祖先的 ACTION_CLICK；不行再按节点坐标补一次点击手势 */
    private fun click(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val target = AdRules.findClickable(node)
        if (target != null && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true

        val r = android.graphics.Rect()
        node.getBoundsInScreen(r)
        if (r.width() <= 0 || r.height() <= 0) return false
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L))
            .build()
        return svc.dispatchGesture(gesture, null, null)
    }

    private fun screenSize(): Pair<Int, Int> = try {
        val b = svc.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        b.width() to b.height()
    } catch (_: Throwable) {
        0 to 0
    }

    private fun labelOf(pkg: String): String = try {
        svc.packageManager.getApplicationLabel(svc.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Throwable) {
        pkg
    }

    private fun logThrottled(msg: String) {
        val now = SystemClock.uptimeMillis()
        if (now - lastLogAt < LOG_GAP_MS) return
        lastLogAt = now
        Logs.d(TAG, msg)
    }
}
