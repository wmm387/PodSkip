package com.zero.podskip.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.zero.podskip.R
import com.zero.podskip.Settings
import com.zero.podskip.core.KeyProbe
import com.zero.podskip.core.SwipeKeyService

class OnboardingActivity : AppCompatActivity() {

    private val totalSteps = 5
    private var step = 1

    private lateinit var title: TextView
    private lateinit var step1: View
    private lateinit var step2: View
    private lateinit var step3: View
    private lateinit var step4: View
    private lateinit var step5: View
    private lateinit var a11yStatus: TextView
    private lateinit var notifyStatus: TextView
    private lateinit var btnNotify: Button
    private lateinit var keepAliveHint: TextView
    private lateinit var keyStatus: TextView
    private lateinit var gestureStatus: TextView
    private lateinit var appsStatus: TextView
    private lateinit var candidates: LinearLayout
    private lateinit var noCandidate: TextView
    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button

    /** 本轮是否已经弹过系统权限框；弹过仍被拒就改指向系统设置 */
    private var notifyAsked = false

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            notifyAsked = true
            updateNotifyStatus()
            if (granted) {
                // 服务若正在运行，立刻让它挂上前台通知，不用等下一次窗口切换
                SwipeKeyService.instance?.refreshForeground()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_onboarding)
        Settings.init(this)

        step = intent.getIntExtra(EXTRA_START_STEP, 1).coerceIn(1, totalSteps)

        title = findViewById(R.id.step_title)
        step1 = findViewById(R.id.step1)
        step2 = findViewById(R.id.step2)
        step3 = findViewById(R.id.step3)
        step4 = findViewById(R.id.step4)
        step5 = findViewById(R.id.step5)
        a11yStatus = findViewById(R.id.a11y_status)
        notifyStatus = findViewById(R.id.notify_status)
        btnNotify = findViewById(R.id.btn_notify)
        keepAliveHint = findViewById(R.id.keepalive_hint)
        keyStatus = findViewById(R.id.key_status)
        gestureStatus = findViewById(R.id.gesture_status)
        appsStatus = findViewById(R.id.apps_status)
        candidates = findViewById(R.id.candidates_container)
        noCandidate = findViewById(R.id.no_candidate)
        btnPrev = findViewById(R.id.btn_prev)
        btnNext = findViewById(R.id.btn_next)

        findViewById<Button>(R.id.btn_open_a11y).setOnClickListener {
            startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        btnNotify.setOnClickListener { onNotifyButtonClick() }
        keepAliveHint.text = keepAliveText()

        findViewById<Button>(R.id.btn_key_test).setOnClickListener {
            startActivity(Intent(this, KeyTestActivity::class.java))
        }
        findViewById<Button>(R.id.btn_gesture).setOnClickListener {
            startActivity(Intent(this, GestureSettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btn_apps).setOnClickListener {
            startActivity(Intent(this, AppListActivity::class.java))
        }

        btnPrev.setOnClickListener { if (step > 1) go(step - 1) }

        btnNext.setOnClickListener {
            if (step < totalSteps) go(step + 1) else completeAndEnable()
        }

        render()
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun go(target: Int) {
        step = target
        render()
    }

    private fun render() {
        title.text = "第 $step / $totalSteps 步"

        step1.visibility = if (step == 1) View.VISIBLE else View.GONE
        step2.visibility = if (step == 2) View.VISIBLE else View.GONE
        step3.visibility = if (step == 3) View.VISIBLE else View.GONE
        step4.visibility = if (step == 4) View.VISIBLE else View.GONE
        step5.visibility = if (step == 5) View.VISIBLE else View.GONE

        // 无障碍没开时翻页没有意义，先把第 1 步的服务打开；按钮藏起来避免误点
        val ready = isServiceEnabled()
        btnPrev.visibility = if (ready) View.VISIBLE else View.GONE
        btnNext.visibility = if (ready) View.VISIBLE else View.GONE
        if (ready) {
            btnPrev.isEnabled = step > 1
            btnNext.text = if (step == totalSteps) "完成并开启" else "下一步"
        }

        if (step == 1) updateA11yStatus()
        if (step == 2) updateNotifyStatus()
        if (step == 3) updateKeyStatus()
        if (step == 4) updateGestureStatus()
        if (step == 5) renderCandidates()
    }

    // ---------- 第 1 步：无障碍状态 ----------

    private fun updateA11yStatus() {
        val on = isServiceEnabled()
        a11yStatus.text = if (on) "服务已开启 ✓" else "服务未开启，点下面的按钮去打开"
        a11yStatus.setTextColor(getColor(if (on) R.color.success else R.color.danger))
    }

    // ---------- 第 2 步：通知权限与保活 ----------

    private fun hasNotifPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun updateNotifyStatus() {
        if (hasNotifPermission()) {
            notifyStatus.text =
                "通知已允许 ✓\n看剧时会出现一条静默通知，系统就不会轻易回收服务。"
            notifyStatus.setTextColor(getColor(R.color.success))
            btnNotify.text = "查看通知设置"
        } else {
            notifyStatus.text =
                "通知未允许\n服务仍能工作，但系统更容易在后台把它回收，通知栏也看不到运行状态。"
            notifyStatus.setTextColor(getColor(R.color.warning))
            btnNotify.text = if (notifyAsked) "去系统设置打开通知" else "允许通知"
        }
        keepAliveHint.text = keepAliveText()
    }

    private fun keepAliveText(): String = if (hasNotifPermission()) {
        "通知已允许：接管开启后通知栏会常驻运行状态。"
    } else {
        "⚠ 通知未允许：通知栏不会显示运行状态，服务更容易被系统冻结，按键会时灵时不灵。"
    }

    private fun onNotifyButtonClick() {
        when {
            hasNotifPermission() -> openNotificationSettings()
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> openNotificationSettings()
            !notifyAsked -> {
                notifyAsked = true
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            else -> openNotificationSettings()
        }
    }

    private fun openNotificationSettings() {
        try {
            startActivity(
                Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, packageName)
            )
        } catch (e: Exception) {
            startActivity(
                Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
            )
        }
    }

    // ---------- 第 3 步：按键测试 ----------

    private fun updateKeyStatus() {
        if (KeyProbe.lastAt == 0L) {
            keyStatus.text = "还没测过。进去按一下耳机键即可。"
            keyStatus.setTextColor(getColor(R.color.on_surface_variant))
            return
        }
        val name = KeyProbe.nameOf(KeyProbe.lastKeyCode)
        val tail = if (KeyProbe.isDedicatedKey(KeyProbe.lastKeyCode)) {
            "属于直接发切歌键的类型，零延迟。"
        } else {
            "属于单键类型，靠数按键次数区分，会有判定延迟。"
        }
        keyStatus.text = "最近一次按键：$name\n$tail"
        keyStatus.setTextColor(getColor(R.color.success))
    }

    // ---------- 第 4 步：手势参数 ----------

    private fun updateGestureStatus() {
        val from = (Settings.swipeFromY * 100).toInt()
        val to = (Settings.swipeToY * 100).toInt()
        gestureStatus.text =
            "当前：判定窗口 ${Settings.tapWindowMs}ms · 上滑 $from%→$to% · ${Settings.swipeDurationMs}ms"
    }

    // ---------- 第 5 步：候选应用 ----------

    private fun renderCandidates() {
        candidates.removeAllViews()
        val installed = Settings.DRAMA_CANDIDATES.filter { isInstalled(it) }
        noCandidate.visibility = if (installed.isEmpty()) View.VISIBLE else View.GONE

        installed.forEach { pkg ->
            val row = layoutInflater.inflate(R.layout.item_app, candidates, false)
            val icon = row.findViewById<ImageView>(R.id.item_icon)
            val label = row.findViewById<TextView>(R.id.item_text)
            try {
                val ai = packageManager.getApplicationInfo(pkg, 0)
                label.text = packageManager.getApplicationLabel(ai).toString()
                icon.setImageDrawable(packageManager.getApplicationIcon(ai))
            } catch (e: Exception) {
                label.text = pkg
                icon.setImageResource(android.R.drawable.sym_def_app_icon)
            }
            row.findViewById<TextView>(R.id.item_pkg).text = pkg

            val btn = row.findViewById<Button>(R.id.item_btn)
            val added = pkg in Settings.whitelist
            btn.text = if (added) "已加入" else "加入"
            btn.setOnClickListener {
                if (pkg in Settings.whitelist) {
                    Settings.removeFromWhitelist(pkg)
                } else {
                    Settings.addToWhitelist(pkg)
                }
                renderCandidates()
            }
            candidates.addView(row)
        }

        val wl = Settings.whitelist
        appsStatus.text = if (wl.isEmpty()) {
            "还没选任何应用。完成后需要在白名单里至少加一个，否则按键不会被接管。"
        } else {
            "已加入 ${wl.size} 个：" + wl.map { labelOf(it) }.joinToString("、")
        }
        appsStatus.setTextColor(
            getColor(if (wl.isEmpty()) R.color.warning else R.color.success)
        )
    }

    private fun labelOf(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    private fun isInstalled(pkg: String): Boolean = try {
        packageManager.getApplicationInfo(pkg, 0)
        true
    } catch (e: Exception) {
        false
    }

    // ---------- 完成 ----------

    private fun completeAndEnable() {
        if (!isServiceEnabled()) {
            Toast.makeText(this, "无障碍服务还没开启，先在第 1 步打开", Toast.LENGTH_LONG).show()
            go(1)
            return
        }
        Settings.enabled = true
        // 记一笔：引导走完过，以后不再自动跳引导
        Settings.onboardingDone = true
        // 开着通知权限的话，立刻让服务按需挂上前台通知
        SwipeKeyService.instance?.refreshForeground()
        Toast.makeText(this, "按键接管已开启", Toast.LENGTH_SHORT).show()
        setResult(RESULT_OK)
        finish()
    }

    private fun isServiceEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.name == SwipeKeyService::class.java.name }
    }

    companion object {
        const val EXTRA_START_STEP = "start_step"
    }
}
