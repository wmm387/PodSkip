package com.zero.podskip.ui

import android.os.Bundle
import android.view.View
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.zero.podskip.Mode
import com.zero.podskip.R
import com.zero.podskip.Settings
import com.zero.podskip.core.SwipeKeyService

/**
 * 高级设置：所有开关类选项集中在这里。
 * 手势参数页只留滑动几何与时序（滑块），避免两类配置混在一页。
 */
class AdvancedSettingsActivity : AppCompatActivity() {

    private lateinit var switchSingle: SwitchCompat
    private lateinit var switchLandscape: SwitchCompat
    private lateinit var switchLongPress: SwitchCompat
    private lateinit var switchBt: SwitchCompat
    private lateinit var switchWearPause: SwitchCompat
    private lateinit var switchWearResume: SwitchCompat
    private lateinit var radioMode: RadioGroup
    private lateinit var radioWindow: RadioGroup
    private lateinit var wearHeader: View
    private lateinit var wearBody: View
    private lateinit var wearToggle: TextView
    private lateinit var switchAdSkip: SwitchCompat
    private lateinit var adLast: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(this)
        setContentView(R.layout.activity_advanced)

        switchSingle = findViewById(R.id.switch_single)
        switchLandscape = findViewById(R.id.switch_landscape)
        switchLongPress = findViewById(R.id.switch_long_press)
        switchBt = findViewById(R.id.switch_bt)
        switchWearPause = findViewById(R.id.switch_wear_pause)
        switchWearResume = findViewById(R.id.switch_wear_resume)
        radioMode = findViewById(R.id.radio_mode)
        radioWindow = findViewById(R.id.radio_resume_window)
        wearHeader = findViewById(R.id.wear_header)
        wearBody = findViewById(R.id.wear_body)
        wearToggle = findViewById(R.id.wear_toggle)
        switchAdSkip = findViewById(R.id.switch_ad_skip)
        adLast = findViewById(R.id.tv_ad_last)

        // 佩戴检测是低频功能，默认收起，点标题展开
        wearHeader.setOnClickListener { setWearExpanded(wearBody.visibility != View.VISIBLE) }

        switchSingle.setOnCheckedChangeListener { _, checked ->
            Settings.singleTapTakeover = checked
        }
        switchLandscape.setOnCheckedChangeListener { _, checked ->
            Settings.landscapeDoubleTap = checked
        }
        switchLongPress.setOnCheckedChangeListener { _, checked ->
            Settings.swallowLongPress = checked
        }
        switchBt.setOnCheckedChangeListener { _, checked ->
            Settings.btKeys = checked
            SwipeKeyService.instance?.refreshForeground()
        }
        switchWearPause.setOnCheckedChangeListener { _, checked ->
            Settings.wearAutoPause = checked
            renderWearDependency()
        }
        switchWearResume.setOnCheckedChangeListener { _, checked ->
            Settings.wearAutoResume = checked
        }
        switchAdSkip.setOnCheckedChangeListener { _, checked ->
            Settings.adSkip = checked
            SwipeKeyService.instance?.onAdSkipChanged()
            renderAdLast()
        }

        radioMode.setOnCheckedChangeListener { _, id ->
            Settings.mode = when (id) {
                R.id.rb_count -> Mode.COUNT
                R.id.rb_pass -> Mode.PASSTHROUGH
                else -> Mode.AUTO
            }
        }
        radioWindow.setOnCheckedChangeListener { _, id ->
            Settings.wearResumeWindowMs = when (id) {
                R.id.rb_1min -> 60_000L
                R.id.rb_15min -> 15 * 60_000L
                else -> 5 * 60_000L
            }
        }

        findViewById<TextView>(R.id.btn_reset_advanced).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("恢复默认参数？")
                .setMessage("按键模式、单击接管、横屏适配、长按防误触、佩戴检测都会恢复成默认值，当前的调整会丢失。")
                .setNegativeButton("取消", null)
                .setPositiveButton("恢复") { _, _ ->
                    Settings.resetAdvanced()
                    loadValues()
                }
                .show()
        }

        loadValues()
    }

    override fun onResume() {
        super.onResume()
        // 检测是常驻服务在做，回到这一页时把「最近一次结论」刷新一下
        renderAdLast()
    }

    private fun loadValues() {
        switchSingle.isChecked = Settings.singleTapTakeover
        switchLandscape.isChecked = Settings.landscapeDoubleTap
        switchLongPress.isChecked = Settings.swallowLongPress
        switchBt.isChecked = Settings.btKeys
        switchWearPause.isChecked = Settings.wearAutoPause
        switchWearResume.isChecked = Settings.wearAutoResume
        switchAdSkip.isChecked = Settings.adSkip
        radioMode.check(
            when (Settings.mode) {
                Mode.COUNT -> R.id.rb_count
                Mode.PASSTHROUGH -> R.id.rb_pass
                Mode.AUTO -> R.id.rb_auto
            }
        )
        radioWindow.check(
            when {
                Settings.wearResumeWindowMs <= 60_000L -> R.id.rb_1min
                Settings.wearResumeWindowMs >= 15 * 60_000L -> R.id.rb_15min
                else -> R.id.rb_5min
            }
        )
        renderWearDependency()
        renderAdLast()
    }

    /**
     * 显示服务最近一次检测的结论。
     * 不在应用里做「立即检测」——点这个按钮时前台是本应用，检测的永远是设置页，
     * 看不到短剧那边认没认出来；改成回显真实检测记录。
     */
    private fun renderAdLast() {
        val last = SwipeKeyService.instance?.lastAdResult().orEmpty()
        adLast.text = when {
            !Settings.adSkip -> "自动跳过已关闭"
            last.isEmpty() -> "最近一次检测：还没有记录（去短剧 App 里播一集）"
            else -> "最近一次检测：$last"
        }
    }

    /** 折叠/展开佩戴检测；收起时不影响已保存的开关值 */
    private fun setWearExpanded(expanded: Boolean) {
        wearBody.visibility = if (expanded) View.VISIBLE else View.GONE
        wearToggle.text = if (expanded) "收起" else "展开"
    }

    /** 自动恢复依赖自动暂停，没开暂停时整组置灰，避免配出无效组合 */
    private fun renderWearDependency() {
        val on = Settings.wearAutoPause
        switchWearResume.isEnabled = on
        switchWearResume.alpha = if (on) 1f else 0.4f
        radioWindow.isEnabled = on
        radioWindow.alpha = if (on) 1f else 0.4f
        for (i in 0 until radioWindow.childCount) {
            radioWindow.getChildAt(i).isEnabled = on
        }
    }
}
