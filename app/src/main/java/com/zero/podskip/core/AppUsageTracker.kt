package com.zero.podskip.core

import android.content.Context
import android.content.SharedPreferences

/**
 * 记录最近出现在前台的第三方应用包名。
 * 用户打开一次短剧 App 再切回来，就能在应用列表里看到它 —— 不用连电脑查包名。
 */
object AppUsageTracker {
    private const val KEY = "recent_apps"
    private const val MAX = 8
    private const val PREF = "podskip_recent"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun record(ctx: Context, pkg: String) {
        if (pkg == ctx.packageName) return
        if (pkg.startsWith("com.android.") || pkg.startsWith("android")) return

        val list = list(ctx).toMutableList()
        list.removeAll { it == pkg }
        list.add(0, pkg)
        while (list.size > MAX) list.removeAt(list.size - 1)
        sp(ctx).edit().putString(KEY, list.joinToString("\n")).apply()
    }

    fun list(ctx: Context): List<String> =
        sp(ctx).getString(KEY, "")
            ?.split("\n")
            ?.filter { it.isNotBlank() }
            ?: emptyList()

    fun clear(ctx: Context) {
        sp(ctx).edit().remove(KEY).apply()
    }
}
