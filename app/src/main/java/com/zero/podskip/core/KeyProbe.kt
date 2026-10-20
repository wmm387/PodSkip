package com.zero.podskip.core

import android.os.SystemClock
import android.view.KeyEvent

/**
 * 记录最近按键序列，供引导页和「按键测试」页使用。
 * Activity 与服务同进程，直接读静态字段即可。
 */
object KeyProbe {

    data class Entry(val code: Int, val at: Long, val gapMs: Long)

    private const val MAX = 16
    private val buf = ArrayDeque<Entry>()

    @Volatile
    var lastKeyCode: Int = 0
        private set

    @Volatile
    var lastAt: Long = 0L
        private set

    /** 与上一次按键的间隔，首次按键为 -1 */
    @Volatile
    var lastGapMs: Long = -1L
        private set

    @Synchronized
    fun record(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        val gap = if (lastAt == 0L) -1L else now - lastAt
        lastKeyCode = keyCode
        lastAt = now
        lastGapMs = gap
        buf.addLast(Entry(keyCode, now, gap))
        while (buf.size > MAX) buf.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<Entry> = buf.toList()

    @Synchronized
    fun clear() {
        buf.clear()
        lastKeyCode = 0
        lastAt = 0L
        lastGapMs = -1L
    }

    fun nameOf(code: Int): String = when (code) {
        KeyEvent.KEYCODE_MEDIA_NEXT -> "下一首 MEDIA_NEXT"
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "上一首 MEDIA_PREVIOUS"
        KeyEvent.KEYCODE_HEADSETHOOK -> "线控中键 HEADSETHOOK"
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "播放/暂停 PLAY_PAUSE"
        KeyEvent.KEYCODE_MEDIA_PLAY -> "播放 MEDIA_PLAY"
        KeyEvent.KEYCODE_MEDIA_PAUSE -> "暂停 MEDIA_PAUSE"
        else -> "keyCode = $code"
    }

    fun shortName(code: Int): String = when (code) {
        KeyEvent.KEYCODE_MEDIA_NEXT -> "NEXT"
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "PREV"
        KeyEvent.KEYCODE_HEADSETHOOK -> "HOOK"
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "PLAY"
        KeyEvent.KEYCODE_MEDIA_PLAY -> "PLAY"
        KeyEvent.KEYCODE_MEDIA_PAUSE -> "PAUSE"
        else -> "$code"
    }

    /** 是否是「需要计数」的单键型信号 */
    fun isSingleButton(code: Int): Boolean =
        code == KeyEvent.KEYCODE_HEADSETHOOK || code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE

    /** 是否是耳机自带的多功能键（A 型）信号 */
    fun isDedicatedKey(code: Int): Boolean =
        code == KeyEvent.KEYCODE_MEDIA_NEXT || code == KeyEvent.KEYCODE_MEDIA_PREVIOUS ||
            code == KeyEvent.KEYCODE_MEDIA_PLAY || code == KeyEvent.KEYCODE_MEDIA_PAUSE

    /**
     * 从按键序列推断合适的多击判定窗口。
     * 取同一串连按内部的最大间隔，乘 1.35 留余量，对齐到 50ms。
     * 下限锁死 400ms：单次采样出来的间隔往往偏快，用它反推会把窗口压得装不下
     * 用户真实的双击，导致双击被拆成两次单击。上限 600ms，避免单击暂停太迟钝。
     * 数据不足返回 -1。
     */
    fun suggestWindowMs(entries: List<Entry> = snapshot()): Long {
        val gaps = entries.filter { isSingleButton(it.code) && it.gapMs in 1..700 }
            .map { it.gapMs }
        if (gaps.isEmpty()) return -1L
        val raw = gaps.max() * 1.35
        val rounded = (raw / 50.0).toInt() * 50L
        return rounded.coerceIn(400L, 600L)
    }
}
