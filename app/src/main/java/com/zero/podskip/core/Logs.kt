package com.zero.podskip.core

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import androidx.core.content.FileProvider
import com.zero.podskip.BuildConfig
import com.zero.podskip.Settings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 轻量事件日志：同时写 logcat 和 filesDir/logs/podskip-yyyyMMdd.log。
 *
 * 存在的意义只有一个——别人手机上出问题（按键没反应之类），
 * 不用教他开 adb，直接在设置页点「导出诊断日志」，把文件发过来就行。
 *
 * 按天分文件：[KEEP_DAYS] 天前的自动删；导出只取最近 [EXPORT_WINDOW_MS] 的事件，
 * 免得一按导出就是一个几百 KB 的大杂烩，关键的那几行反而找不到。
 *
 * 除了业务事件，还有一个 3 秒一次的主线程心跳：如果进程被 MIUI 这类
 * 系统冻结，心跳会迟到，迟到时长会写成一行 `W/beat`，冻结这件事就有证据了。
 */
object Logs {

    private const val DIR = "logs"
    private const val NAME_PREFIX = "podskip-"
    private const val NAME_SUFFIX = ".log"

    /** 单个日志文件上限。超了就丢掉最旧的一半，继续滚动写入 */
    private const val MAX_BYTES = 512 * 1024

    /** 日志保留天数 */
    private const val KEEP_DAYS = 3

    /** 导出只取最近这么久的事件（这段时间一条都没有时退回今天的全部） */
    private const val EXPORT_WINDOW_MS = 3 * 60 * 60 * 1000L

    /** 导出窗口的小时数，界面提示文案用 */
    val exportWindowHours: Int get() = (EXPORT_WINDOW_MS / 3_600_000L).toInt()

    /** 导出正文上限，超了只留最新的部分 */
    private const val MAX_EXPORT_CHARS = 200_000

    /** 心跳间隔 / 迟到多久算一次卡顿 */
    private const val BEAT_MS = 3_000L
    private const val STALL_MS = 1_500L

    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "podskip-log").apply { isDaemon = true }
    }

    private val fileStamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val dayStamp = SimpleDateFormat("yyyyMMdd", Locale.US)
    private val readStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** 上一次清理发生在哪一天，用来避免每写一行都扫一遍目录 */
    @Volatile
    private var lastCleanDay = ""

    @Volatile
    private var app: Context? = null

    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        exec.execute {
            try {
                prune(File(ctx.filesDir, DIR), System.currentTimeMillis())
            } catch (_: Throwable) {
            }
        }
    }

    fun d(tag: String, msg: String) = log("D", tag, msg)
    fun w(tag: String, msg: String) = log("W", tag, msg)
    fun e(tag: String, msg: String) = log("E", tag, msg)

    private fun log(level: String, tag: String, msg: String) {
        when (level) {
            "D" -> android.util.Log.d(tag, msg)
            "W" -> android.util.Log.w(tag, msg)
            else -> android.util.Log.e(tag, msg)
        }
        append("$level/$tag: $msg")
    }

    private fun append(line: String) {
        val ctx = app ?: return
        val at = System.currentTimeMillis()
        val text = "${stamp(at)} $line\n"
        exec.execute {
            try {
                val dir = File(ctx.filesDir, DIR).apply { mkdirs() }
                val f = fileFor(dir, at)
                // 写满就丢掉最旧的一半，继续滚动，不会因为满了一直不写
                if (f.length() > MAX_BYTES) trimHead(f)
                f.appendText(text)
                if (dayOf(at) != lastCleanDay) {
                    lastCleanDay = dayOf(at)
                    prune(dir, at)
                }
            } catch (_: Throwable) {
                // 日志写不进去不能影响主流程
            }
        }
    }

    private fun now(fmt: SimpleDateFormat): String = synchronized(fmt) { fmt.format(Date()) }

    private fun stamp(at: Long): String = synchronized(fileStamp) { fileStamp.format(Date(at)) }

    private fun dayOf(at: Long): String = synchronized(dayStamp) { dayStamp.format(Date(at)) }

    private fun fileFor(dir: File, at: Long): File = File(dir, "$NAME_PREFIX${dayOf(at)}$NAME_SUFFIX")

    /**
     * 文件写满后砍掉最旧的一半，留最新的继续写。
     * 从换行之后开始切，保证留下的都是完整行（UTF-8 多字节字符的 0x0A 不会出现在中间）。
     */
    private fun trimHead(f: File) {
        try {
            val bytes = f.readBytes()
            val keep = MAX_BYTES / 2
            if (bytes.size <= keep) return
            var start = bytes.size - keep
            while (start < bytes.size && bytes[start] != '\n'.code.toByte()) start++
            if (start < bytes.size) start++
            f.writeBytes(bytes.copyOfRange(start, bytes.size))
        } catch (_: Throwable) {
        }
    }

    /** 按文件名日期升序，等于按时间顺序 */
    private fun logFiles(dir: File): List<File> =
        dir.listFiles { f -> f.name.startsWith(NAME_PREFIX) && f.name.endsWith(NAME_SUFFIX) }
            ?.sortedBy { it.name }
            ?: emptyList()

    /**
     * 删过期日志 + 收拾导出文件。
     * 顺带清掉旧版本留下的 podskip.log / podskip.log.1（当时按大小轮转，没有日期）。
     */
    private fun prune(dir: File, at: Long) {
        try {
            val keepFrom = dayOf(at - KEEP_DAYS * 86_400_000L)
            dir.listFiles()?.forEach { f ->
                val n = f.name
                when {
                    n == "podskip.log" || n == "podskip.log.1" -> f.delete()
                    n.startsWith(NAME_PREFIX) && n.endsWith(NAME_SUFFIX) -> {
                        val day = n.removePrefix(NAME_PREFIX).removeSuffix(NAME_SUFFIX)
                        if (day.length == 8 && day < keepFrom) f.delete()
                    }
                }
            }
            // 导出文件只留最近的 5 份
            dir.listFiles { f -> f.name.startsWith("podskip-log-") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(5)
                ?.forEach { it.delete() }
        } catch (_: Throwable) {
        }
    }

    // ---------- 主线程心跳：用来抓「进程被冻结」 ----------

    private val handler = Handler(Looper.getMainLooper())
    private var beating = false
    private var nextAt = 0L

    private val beat = object : Runnable {
        override fun run() {
            if (!beating) return
            val t = SystemClock.uptimeMillis()
            val late = t - nextAt
            if (late > STALL_MS) {
                append("W/beat: 主线程 ${late}ms 没跑起来（进程被系统冻结，或主线程被卡住）")
            }
            nextAt = t + BEAT_MS
            handler.postDelayed(this, BEAT_MS)
        }
    }

    fun startBeat() {
        if (beating) return
        beating = true
        nextAt = SystemClock.uptimeMillis() + BEAT_MS
        handler.postDelayed(beat, BEAT_MS)
    }

    fun stopBeat() {
        beating = false
        handler.removeCallbacks(beat)
    }

    // ---------- 导出 ----------

    /** 把日志和环境信息合成一个 txt，返回文件 */
    fun export(ctx: Context): File {
        init(ctx)
        Settings.init(ctx)
        drain()
        val dir = File(ctx.filesDir, DIR).apply { mkdirs() }
        val at = System.currentTimeMillis()
        val name = "podskip-log-" + now(SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)) + ".txt"
        val out = File(dir, name)
        out.writeText(
            header(ctx) +
                "\n---------- 最近 ${EXPORT_WINDOW_MS / 3_600_000} 小时事件 ----------\n" +
                readRecent(dir, at - EXPORT_WINDOW_MS, at)
        )
        prune(dir, at)
        return out
    }

    /** 系统分享这个文件（微信 / 邮件 / 任意文件管理器） */
    fun share(ctx: Context, file: File) {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "耳机切剧诊断日志")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(
            Intent.createChooser(send, "发送诊断日志").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** 等写入队列落盘，避免导出的文件比界面上看到的少最后几行 */
    private fun drain() {
        try {
            exec.submit {}.get(2, TimeUnit.SECONDS)
        } catch (_: Throwable) {
        }
    }

    /**
     * 取指定时间点之后的事件行。
     *
     * 行首是定宽的 `MM-dd HH:mm:ss.SSS`，直接按字符串比就行，不用解析成日期——
     * 一天几万行的时候按行 new 一个 SimpleDateFormat 会明显卡住导出。
     */
    private fun readRecent(dir: File, since: Long, at: Long): String {
        val cutoff = stamp(since)
        val sb = StringBuilder()
        for (f in logFiles(dir)) {
            try {
                f.forEachLine { line ->
                    if (line.length > 18 && line >= cutoff) sb.append(line).append('\n')
                }
            } catch (_: Throwable) {
            }
        }
        // 窗口内一条都没有（刚出问题就导出、或者刚过零点），退回今天全天的
        if (sb.isEmpty()) {
            val today = fileFor(dir, at)
            if (today.exists()) {
                try {
                    today.forEachLine { sb.append(it).append('\n') }
                } catch (_: Throwable) {
                }
            }
        }
        if (sb.length > MAX_EXPORT_CHARS) {
            return "（内容过长，只保留最后 ${MAX_EXPORT_CHARS / 1000}KB）\n" +
                sb.substring(sb.length - MAX_EXPORT_CHARS)
        }
        return sb.toString()
    }

    private fun header(ctx: Context): String {
        val sb = StringBuilder()
        val svc = SwipeKeyService.instance
        sb.appendLine("耳机切剧 诊断日志")
        sb.appendLine("导出时间：${now(readStamp)}")
        sb.appendLine("应用版本：${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        sb.appendLine("设备：${Build.MANUFACTURER} ${Build.MODEL} / ${Build.DEVICE}")
        sb.appendLine("系统：Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("ROM：${Build.DISPLAY}")
        sb.appendLine("无障碍服务：${if (a11yOn(ctx)) "已开启" else "未开启"}")
        sb.appendLine("后台运行豁免：${if (batteryExempt(ctx)) "已允许" else "未允许"}")
        sb.appendLine("通知权限：${if (notifAllowed(ctx)) "已允许" else "未允许"}")
        sb.appendLine("通知栏保活：${if (svc?.isForegroundActive() == true) "已挂" else "未挂"}")
        sb.appendLine("总开关：${if (Settings.enabled) "开" else "关"}")
        sb.appendLine("当前接管：${svc?.currentPackage()?.ifEmpty { "无" } ?: "无"}")
        sb.appendLine("白名单：${Settings.whitelist.joinToString(", ").ifEmpty { "空" }}")
        sb.appendLine(
            "按键模式：${Settings.mode} / 单击接管：${onOff(Settings.singleTapTakeover)}" +
                " / 横屏双击：${onOff(Settings.landscapeDoubleTap)}" +
                " / 长按防误触：${onOff(Settings.swallowLongPress)}"
        )
        sb.appendLine(
            "滑动：${Settings.swipeFromY} → ${Settings.swipeToY} / ${Settings.swipeDurationMs}ms" +
                " / 多击窗口：${Settings.tapWindowMs}ms"
        )
        sb.appendLine(
            "佩戴检测：暂停 ${onOff(Settings.wearAutoPause)} / 恢复 ${onOff(Settings.wearAutoResume)}" +
                " / 时限 ${Settings.wearResumeWindowMs}ms"
        )
        return sb.toString()
    }

    private fun onOff(v: Boolean) = if (v) "开" else "关"

    private fun a11yOn(ctx: Context): Boolean = try {
        val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.name == SwipeKeyService::class.java.name }
    } catch (_: Throwable) {
        false
    }

    private fun batteryExempt(ctx: Context): Boolean = try {
        ctx.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(ctx.packageName) == true
    } catch (_: Throwable) {
        false
    }

    private fun notifAllowed(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}
