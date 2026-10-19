package com.zero.podskip

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.zero.podskip.core.SwipeKeyService
import com.zero.podskip.ui.AppListActivity
import com.zero.podskip.ui.GestureSettingsActivity
import com.zero.podskip.ui.OnboardingActivity

class MainActivity : AppCompatActivity() {

    private lateinit var toggle: SwitchCompat
    private lateinit var status: TextView
    private var suppressToggle = false

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            keepAliveHintView?.text = keepAliveText(Settings.keepAliveAlways)
            if (granted) SwipeKeyService.instance?.refreshForeground()
        }

    private var keepAliveHintView: TextView? = null

    /** 停在主页时也能看到「现在正在接管哪个应用」，1.5 秒刷一次 */
    private val uiHandler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refreshStatus()
            uiHandler.postDelayed(this, 1500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(this)
        setContentView(R.layout.activity_main)

        toggle = findViewById(R.id.toggle)
        status = findViewById(R.id.status)
        findViewById<TextView>(R.id.version).text =
            "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

        toggle.isChecked = Settings.enabled
        toggle.setOnCheckedChangeListener { _, checked ->
            if (suppressToggle) return@setOnCheckedChangeListener

            // 权限没开就打开引导，而不是只把开关点亮（点了也没用）
            if (checked && !isServiceEnabled()) {
                suppressToggle = true
                toggle.isChecked = false
                suppressToggle = false
                startActivity(
                    Intent(this, OnboardingActivity::class.java)
                        .putExtra(OnboardingActivity.EXTRA_START_STEP, 1)
                )
                return@setOnCheckedChangeListener
            }

            Settings.enabled = checked
            refreshStatus()
        }

        val switchKeepAlive = findViewById<SwitchCompat>(R.id.switch_keepalive)
        val keepAliveHint = findViewById<TextView>(R.id.keepalive_hint)
        keepAliveHintView = keepAliveHint
        switchKeepAlive.isChecked = Settings.keepAliveAlways
        switchKeepAlive.setOnCheckedChangeListener { _, checked ->
            Settings.keepAliveAlways = checked
            keepAliveHint.text = keepAliveText(checked)
            // 没有通知权限时保活是无效的，顺手把权限要了
            if (checked && !hasNotifPermission()) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            // 立即生效，不用等下一次切窗口
            SwipeKeyService.instance?.refreshForeground()
        }
        keepAliveHint.text = keepAliveText(Settings.keepAliveAlways)

        findViewById<Button>(R.id.btn_gesture).setOnClickListener {
            startActivity(Intent(this, GestureSettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btn_apps).setOnClickListener {
            startActivity(Intent(this, AppListActivity::class.java))
        }

        // 无障碍还没开：主页这两个按钮对用户没用，直接进引导，省掉「找不到开启入口」
        if (savedInstanceState == null && !isServiceEnabled()) {
            startActivity(
                Intent(this, OnboardingActivity::class.java)
                    .putExtra(OnboardingActivity.EXTRA_START_STEP, 1)
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // 引导页完成后（Settings.enabled 被置 true）或服务被系统回收后，开关要跟着真实状态走
        val shouldBeOn = Settings.enabled && isServiceEnabled()
        if (toggle.isChecked != shouldBeOn) {
            suppressToggle = true
            toggle.isChecked = shouldBeOn
            suppressToggle = false
        }
        refreshStatus()
        keepAliveHintView?.text = keepAliveText(Settings.keepAliveAlways)
        uiHandler.removeCallbacks(ticker)
        uiHandler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(ticker)
    }

    private fun keepAliveText(always: Boolean): String {
        val base = if (always) {
            "任何时候都保持后台运行，通知常驻，耗电略高"
        } else {
            "只在看剧时接管，退出白名单应用 1 分钟后自动撤掉通知"
        }
        return if (hasNotifPermission()) base
        else "$base\n⚠ 通知权限未开，保活不会生效——到「设置 → 重新查看开启引导」第 2 步允许通知。"
    }

    private fun hasNotifPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun refreshStatus() {
        val on = isServiceEnabled()
        val pkg = SwipeKeyService.instance?.currentPackage().orEmpty()
        val watching = Settings.enabled && on && pkg.isNotEmpty() &&
            pkg != packageName && pkg in Settings.whitelist
        status.text = when {
            !on -> getString(R.string.status_off)
            !Settings.enabled -> "服务已开启，但总开关是关的"
            watching -> "正在接管：${labelOf(pkg)}"
            else -> getString(R.string.status_on)
        }
        status.setTextColor(
            getColor(if (watching) R.color.success else R.color.on_surface_variant)
        )
    }

    private fun labelOf(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        pkg
    }

    private fun isServiceEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.name == SwipeKeyService::class.java.name }
    }
}
