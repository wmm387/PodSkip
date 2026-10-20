package com.zero.podskip.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.zero.podskip.R
import com.zero.podskip.Settings
import com.zero.podskip.core.KeyProbe
import com.zero.podskip.core.SwipeKeyService

/**
 * 耳机按键测试：换耳机后在这里按两下，自动算出合适的多击判定窗口。
 */
class KeyTestActivity : AppCompatActivity() {

    private lateinit var big: TextView
    private lateinit var sub: TextView
    private lateinit var advice: TextView
    private lateinit var history: TextView
    private lateinit var tip: TextView
    private lateinit var btnApply: Button

    private val handler = Handler(Looper.getMainLooper())
    private var suggested = -1L

    private val poller = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 250)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Settings.init(this)
        setContentView(R.layout.activity_keytest)

        big = findViewById(R.id.probe_big)
        sub = findViewById(R.id.probe_sub)
        advice = findViewById(R.id.probe_advice)
        history = findViewById(R.id.probe_history)
        tip = findViewById(R.id.probe_tip)
        btnApply = findViewById(R.id.btn_apply)

        tip.text = "A 型（耳机自带切歌键）：发 MEDIA_NEXT / MEDIA_PREVIOUS，" +
            "双击直接生效、零延迟，不用调窗口。\n" +
            "B 型（只有一个键）：所有按法都是 HEADSETHOOK，需要自己数按键次数，" +
            "单击和双击会等一个判定窗口才执行。窗口太小双击会变成两次单击，" +
            "太大单击暂停会变慢。"

        btnApply.setOnClickListener {
            if (suggested > 0) {
                Settings.tapWindowMs = suggested
                Toast.makeText(this, "判定窗口已设为 ${suggested}ms", Toast.LENGTH_SHORT).show()
                render()
            }
        }

        findViewById<Button>(R.id.btn_clear).setOnClickListener {
            KeyProbe.clear()
            suggested = -1L
            render()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(poller)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poller)
    }

    private fun render() {
        if (!isServiceEnabled()) {
            big.text = "无障碍服务没开启"
            big.setTextColor(getColor(R.color.danger))
            sub.text = "先回主页面按「开启引导」把服务打开"
            advice.text = ""
            btnApply.visibility = View.GONE
            history.text = "（服务没开，收不到按键）"
            return
        }

        val entries = KeyProbe.snapshot()
        if (entries.isEmpty()) {
            big.text = "等待按键…"
            big.setTextColor(getColor(R.color.on_surface))
            sub.text = "按一下耳机上的按键"
            advice.text = "听歌时按一下、连按两下，各试一次，这里会自动算出建议值。"
            btnApply.visibility = View.GONE
            history.text = "（还没有记录）"
            return
        }

        val last = entries.last()
        big.text = KeyProbe.nameOf(last.code)
        big.setTextColor(getColor(R.color.success))

        sub.text = if (last.gapMs > 0) {
            "距离上一次按键 ${last.gapMs}ms"
        } else {
            "第一次按键"
        }

        val dedicated = entries.filter { KeyProbe.isDedicatedKey(it.code) }
        val single = entries.filter { KeyProbe.isSingleButton(it.code) }
        suggested = if (dedicated.isEmpty()) KeyProbe.suggestWindowMs(entries) else -1L
        val current = Settings.tapWindowMs

        advice.text = when {
            dedicated.isNotEmpty() -> {
                "收到 ${KeyProbe.shortName(dedicated.last().code)}：属于直接发媒体键的类型" +
                    "（有线 A 型耳机或蓝牙耳机），按下立即生效，不做击数判定，不用调窗口。"
            }

            suggested > 0 -> {
                val gaps = single.filter { it.gapMs in 1..700 }.map { it.gapMs }
                val maxGap = gaps.maxOrNull() ?: 0
                buildString {
                    append("这是 B 型耳机：所有按法都只发同一个键，靠数按键次数区分。\n")
                    append("你连按时最大间隔 ${maxGap}ms，建议判定窗口 ${suggested}ms（当前 ${current}ms）。")
                    if (current < 400L) {
                        append("\n当前 ${current}ms 偏小，双击很可能被拆成两次单击，")
                        append("表现为「按两下只是暂停了两次」。")
                    }
                }
            }

            single.isNotEmpty() -> {
                "这是 B 型耳机。还没测到连按——快速按两下（平时切歌的动作）试试。"
            }

            else -> "识别到未知按键，可以点「清零重测」再试。"
        }

        if (suggested > 0 && suggested != current) {
            btnApply.visibility = View.VISIBLE
            btnApply.text = "采用建议值 ${suggested}ms（当前 ${current}ms）"
        } else {
            btnApply.visibility = View.GONE
        }

        history.text = entries.takeLast(8).joinToString("\n") { e ->
            val gap = if (e.gapMs > 0) "  +${e.gapMs}ms" else "  —"
            "${KeyProbe.shortName(e.code)}$gap"
        }
    }

    private fun isServiceEnabled(): Boolean {
        val am = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.name == SwipeKeyService::class.java.name }
    }
}
