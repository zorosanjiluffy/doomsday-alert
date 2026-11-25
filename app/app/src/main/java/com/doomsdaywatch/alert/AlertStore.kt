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

    /**
     * Returns true the first time an alert id is seen, false for repeats. The same alert can
     * arrive by push and by the backup check; only the first one rings.
     */
    @Synchronized
    fun claim(ctx: Context, id: String?): Boolean {
        if (id.isNullOrEmpty()) return true
        val p = prefs(ctx)
        val seen = p.getString("seenIds", "")!!.split(',').filter { it.isNotEmpty() }
        if (id in seen) return false
        p.edit().putString("seenIds", (seen.takeLast(30) + id).joinToString(",")).commit()
        return true
    }

    /** Marks an id as seen without ringing (used for the alert that was already current at install). */
    fun markSeen(ctx: Context, id: String) { claim(ctx, id) }

    fun hasBaseline(ctx: Context) = prefs(ctx).getBoolean("pollBaseline", false)
    fun setBaseline(ctx: Context) = prefs(ctx).edit().putBoolean("pollBaseline", true).apply()

    fun setSubscribed(ctx: Context, ok: Boolean) = prefs(ctx).edit().putBoolean("subscribed", ok).apply()
    fun isSubscribed(ctx: Context) = prefs(ctx).getBoolean("subscribed", false)
}
