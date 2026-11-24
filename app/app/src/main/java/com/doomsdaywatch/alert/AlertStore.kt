package com.doomsdaywatch.alert

import android.content.Context

data class LastAlert(val kind: String, val title: String, val body: String, val url: String, val at: Long)

/** Tiny persistence for the last alert received and whether push is connected. */
object AlertStore {
    private const val PREFS = "doom"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun save(ctx: Context, a: LastAlert) {
        prefs(ctx).edit()
            .putString("kind", a.kind).putString("title", a.title).putString("body", a.body)
            .putString("url", a.url).putLong("at", a.at)
            .apply()
    }

    fun last(ctx: Context): LastAlert? {
        val p = prefs(ctx)
        val at = p.getLong("at", 0L)
        if (at == 0L) return null
        return LastAlert(
            p.getString("kind", "info")!!, p.getString("title", "")!!,
            p.getString("body", "")!!, p.getString("url", BuildConfig.DISTRICT_URL)!!, at,
        )
    }

    fun setSubscribed(ctx: Context, ok: Boolean) = prefs(ctx).edit().putBoolean("subscribed", ok).apply()
    fun isSubscribed(ctx: Context) = prefs(ctx).getBoolean("subscribed", false)
}
