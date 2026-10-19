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
 * 轻量事件日志：同时写 logcat 和 filesDir/logs/podskip.log。
 *
 * 存在的意义只有一个——别人手机上出问题（按键没反应之类），
 * 不用教他开 adb，直接在设置页点「导出诊断日志」，把文件发过来就行。
 *
 * 除了业务事件，还有一个 3 秒一次的主线程心跳：如果进程被 MIUI 这类
 * 系统冻结，心跳会迟到，迟到时长会写成一行 `W/beat`，冻结这件事就有证据了。
 */
object Logs {

    private const val DIR = "logs"
    private const val NAME = "podskip.log"
    private const val MAX_BYTES = 128 * 1024L

    /** 心跳间隔 / 迟到多久算一次卡顿 */
    private const val BEAT_MS = 3_000L
    private const val STALL_MS = 1_500L

    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "podskip-log").apply { isDaemon = true }
    }

    private val fileStamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val readStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    @Volatile
    private var app: Context? = null

    fun init(ctx: Context) {
        if (app == null) app = ctx.applicationContext
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
        val text = "${now(fileStamp)} $line\n"
        exec.execute {
            try {
                val dir = File(ctx.filesDir, DIR).apply { mkdirs() }
                val f = File(dir, NAME)
                if (f.length() > MAX_BYTES) {
                    val old = File(dir, "$NAME.1")
                    old.delete()
                    f.renameTo(old)
                }
                f.appendText(text)
            } catch (_: Throwable) {
                // 日志写不进去不能影响主流程
            }
        }
    }

    private fun now(fmt: SimpleDateFormat): String = synchronized(fmt) { fmt.format(Date()) }

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
        val name = "podskip-log-" + now(SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)) + ".txt"
        val out = File(dir, name)
        out.writeText(header(ctx) + "\n---------- 事件 ----------\n" + readAll(dir))
        cleanOld(dir)
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

    private fun readAll(dir: File): String {
        val old = File(dir, "$NAME.1").takeIf { it.exists() }?.readText().orEmpty()
        val cur = File(dir, NAME).takeIf { it.exists() }?.readText().orEmpty()
        return old + cur
    }

    /** 只留最近的 5 份导出文件，别把用户存储撑大 */
    private fun cleanOld(dir: File) {
        try {
            dir.listFiles { f -> f.name.startsWith("podskip-log-") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(5)
                ?.forEach { it.delete() }
        } catch (_: Throwable) {
        }
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
