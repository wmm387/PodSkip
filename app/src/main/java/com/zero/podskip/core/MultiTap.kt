package com.zero.podskip.core

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

enum class Tap { SINGLE, DOUBLE, TRIPLE }

/**
 * 单击 / 双击 / 三击识别。
 * 三击立即触发；双击、单击需等判定窗口结束确认没有后续按键。
 */
object MultiTap {
    private val handler = Handler(Looper.getMainLooper())
    private var count = 0
    private var lastAt = 0L
    private var pending: Runnable? = null

    fun feed(windowMs: Long, onResolved: (Tap) -> Unit) {
        val now = SystemClock.uptimeMillis()
        if (now - lastAt > windowMs) count = 0
        count++
        lastAt = now
        pending?.let { handler.removeCallbacks(it) }

        if (count >= 3) {
            count = 0
            pending = null
            onResolved(Tap.TRIPLE)
            return
        }

        pending = Runnable {
            val tap = if (count == 1) Tap.SINGLE else Tap.DOUBLE
            count = 0
            pending = null
            onResolved(tap)
        }.also { handler.postDelayed(it, windowMs) }
    }
}
