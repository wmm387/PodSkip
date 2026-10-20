package com.zero.podskip.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.zero.podskip.R
import com.zero.podskip.Settings
import com.zero.podskip.core.Logs

/**
 * 手势参数：只放滑动几何与时序（滑块）。
 * 开关类选项（单击接管、横屏适配、长按防误触、按键模式、佩戴检测）都在 AdvancedSettingsActivity。
 */
class GestureSettingsActivity : AppCompatActivity() {

    companion object {
        private const val ISSUES_URL = "https://github.com/wmm387/PodSkip/issues/new/choose"
    }

    private lateinit var seekFrom: SeekBar
    private lateinit var seekTo: SeekBar
    private lateinit var seekDuration: SeekBar
    private lateinit var seekWindow: SeekBar

    private lateinit var labelFrom: TextView
    private lateinit var labelTo: TextView
    private lateinit var labelDuration: TextView
    private lateinit var labelWindow: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(this)
        setContentView(R.layout.activity_gesture)

        seekFrom = findViewById(R.id.seek_from)
        seekTo = findViewById(R.id.seek_to)
        seekDuration = findViewById(R.id.seek_duration)
        seekWindow = findViewById(R.id.seek_window)
        labelFrom = findViewById(R.id.label_from)
        labelTo = findViewById(R.id.label_to)
        labelDuration = findViewById(R.id.label_duration)
        labelWindow = findViewById(R.id.label_window)

        seekFrom.max = 30
        seekTo.max = 30
        seekDuration.max = 350
        seekWindow.max = 400

        bind()
        loadValues()
    }

    private fun bind() {
        seekFrom.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                Settings.swipeFromY = 0.60f + p / 100f
                renderLabels()
            }
        })
        seekTo.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                Settings.swipeToY = 0.10f + p / 100f
                renderLabels()
            }
        })
        seekDuration.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                Settings.swipeDurationMs = 150L + p
                renderLabels()
            }
        })
        seekWindow.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                Settings.tapWindowMs = 200L + p
                renderLabels()
            }
        })

        findViewById<Button>(R.id.btn_key_test).setOnClickListener {
            startActivity(Intent(this, KeyTestActivity::class.java))
        }
        findViewById<Button>(R.id.btn_advanced).setOnClickListener {
            startActivity(Intent(this, AdvancedSettingsActivity::class.java))
        }
        findViewById<TextView>(R.id.btn_reset).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("恢复默认参数？")
                .setMessage("上滑起点、上滑终点、滑动时长、多击判定窗口都会恢复成默认值，当前的调整会丢失。")
                .setNegativeButton("取消", null)
                .setPositiveButton("恢复") { _, _ ->
                    Settings.resetSwipe()
                    loadValues()
                }
                .show()
        }
        findViewById<Button>(R.id.btn_onboarding_again).setOnClickListener {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
        findViewById<Button>(R.id.btn_export_log).setOnClickListener { exportLog() }
        findViewById<Button>(R.id.btn_feedback).setOnClickListener { openIssues() }
    }

    /**
     * 导出前先说清楚范围。
     * 这个提示不是客套——用户踩了坑再导出，日志里只有最近 3 小时，
     * 而他上午的操作根本不在里面，导出来看着像「什么都没发生」。
     */
    private fun exportLog() {
        AlertDialog.Builder(this)
            .setTitle("导出诊断日志")
            .setMessage(
                "只会导出最近 ${Logs.exportWindowHours} 小时内的日志。\n\n" +
                    "如果你的操作已经超过 ${Logs.exportWindowHours} 小时，请重新操作一遍再导出，" +
                    "否则日志里看不到那次问题。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("导出") { _, _ -> doExportLog() }
            .show()
    }

    /** 导出诊断日志并调起分享，用户自己选微信/邮件发出来 */
    private fun doExportLog() {
        try {
            val file = Logs.export(this)
            Logs.share(this, file)
        } catch (e: Throwable) {
            Toast.makeText(this, "导出失败：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun openIssues() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ISSUES_URL)))
        } catch (_: Throwable) {
            Toast.makeText(this, ISSUES_URL, Toast.LENGTH_LONG).show()
        }
    }

    private fun loadValues() {
        seekFrom.progress = ((Settings.swipeFromY - 0.60f) * 100).toInt().coerceIn(0, 30)
        seekTo.progress = ((Settings.swipeToY - 0.10f) * 100).toInt().coerceIn(0, 30)
        seekDuration.progress = (Settings.swipeDurationMs - 150L).toInt().coerceIn(0, 350)
        seekWindow.progress = (Settings.tapWindowMs - 200L).toInt().coerceIn(0, 400)
        renderLabels()
    }

    private fun renderLabels() {
        labelFrom.text = "上滑起点：屏幕高度 ${(Settings.swipeFromY * 100).toInt()}%"
        labelTo.text = "上滑终点：屏幕高度 ${(Settings.swipeToY * 100).toInt()}%"
        labelDuration.text = "滑动时长：${Settings.swipeDurationMs} ms"
        labelWindow.text = "多击判定窗口：${Settings.tapWindowMs} ms"
    }

    private abstract class SimpleSeekListener : SeekBar.OnSeekBarChangeListener {
        override fun onStartTrackingTouch(sb: SeekBar?) {}
        override fun onStopTrackingTouch(sb: SeekBar?) {}
    }
}
