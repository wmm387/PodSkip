package com.zero.podskip.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.zero.podskip.R
import com.zero.podskip.Settings
import com.zero.podskip.core.AppUsageTracker
import com.zero.podskip.core.SwipeKeyService

class AppListActivity : AppCompatActivity() {

    private lateinit var recentContainer: LinearLayout
    private lateinit var enabledContainer: LinearLayout
    private lateinit var recentEmpty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(this)
        setContentView(R.layout.activity_apps)

        recentContainer = findViewById(R.id.recent_container)
        enabledContainer = findViewById(R.id.enabled_container)
        recentEmpty = findViewById(R.id.recent_empty)

        val etPkg = findViewById<EditText>(R.id.et_pkg)
        findViewById<Button>(R.id.btn_manual_add).setOnClickListener {
            val p = etPkg.text.toString().trim()
            if (p.contains(".") && p.length > 3) {
                Settings.addToWhitelist(p)
                etPkg.setText("")
                refresh()
            }
        }

        findViewById<Button>(R.id.btn_clear_recent).setOnClickListener {
            AppUsageTracker.clear(this)
            refresh()
        }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val wl = Settings.whitelist

        recentContainer.removeAllViews()
        val recent = AppUsageTracker.list(this).filter { it !in wl }
        if (recent.isEmpty()) {
            recentEmpty.visibility = View.VISIBLE
            recentEmpty.text = if (!isServiceEnabled()) {
                "无障碍服务还没开，记录不到任何应用。先把服务打开，再来这里。"
            } else {
                "还没有记录。去短剧 App 待一会儿再切回来。"
            }
        } else {
            recentEmpty.visibility = View.GONE
            recent.forEach { pkg ->
                addRow(recentContainer, pkg, "加入") {
                    Settings.addToWhitelist(pkg)
                    refresh()
                }
            }
        }

        enabledContainer.removeAllViews()
        if (wl.isEmpty()) {
            val tv = TextView(this)
            tv.text = "列表为空，所有应用都不会被接管"
            tv.textSize = 13f
            tv.setTextColor(getColor(R.color.on_surface_variant))
            tv.setPadding(0, 16, 0, 16)
            enabledContainer.addView(tv)
        } else {
            wl.forEach { pkg ->
                addRow(enabledContainer, pkg, "移除") {
                    Settings.removeFromWhitelist(pkg)
                    refresh()
                }
            }
        }
    }

    private fun addRow(container: LinearLayout, pkg: String, action: String, onClick: () -> Unit) {
        val row = layoutInflater.inflate(R.layout.item_app, container, false)
        val icon = row.findViewById<ImageView>(R.id.item_icon)
        val label = row.findViewById<TextView>(R.id.item_text)

        // 应用可能已卸载，取不到就退回系统默认图标，不让列表崩
        try {
            val ai = packageManager.getApplicationInfo(pkg, 0)
            label.text = packageManager.getApplicationLabel(ai).toString()
            icon.setImageDrawable(packageManager.getApplicationIcon(ai))
        } catch (e: Exception) {
            label.text = pkg
            icon.setImageResource(android.R.drawable.sym_def_app_icon)
        }
        row.findViewById<TextView>(R.id.item_pkg).text = pkg

        row.findViewById<Button>(R.id.item_btn).apply {
            this.text = action
            setOnClickListener { onClick() }
        }
        container.addView(row)
    }

    private fun isServiceEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.name == SwipeKeyService::class.java.name }
    }
}
