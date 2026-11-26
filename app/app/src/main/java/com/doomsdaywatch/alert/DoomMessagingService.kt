package com.doomsdaywatch.alert

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/** Receives the watcher's high-priority data messages, even when the app is closed. */
class DoomMessagingService : FirebaseMessagingService() {

    override fun onMessageReceived(msg: RemoteMessage) {
        val d = msg.data
        val kind = d["kind"] ?: "info"
        val title = d["title"] ?: msg.notification?.title ?: "Doomsday tickets update"
        val body = d["body"] ?: msg.notification?.body ?: ""
        val url = d["url"]?.takeIf { it.startsWith("https://") } ?: BuildConfig.DISTRICT_URL
        Log.i(DoomApp.TAG, "Push received: $kind / $title")
        deliver(this, d["id"], kind, title, body, url, d["series"])
        Guard.start(this)
    }

    companion object {
        /** Shows an alert once, whether it came by push or by the backup check. */
        fun deliver(ctx: android.content.Context, id: String?, kind: String, title: String, body: String, url: String, series: String? = null) {
            if (!AlertStore.claim(ctx, id)) {
                Log.i(DoomApp.TAG, "Alert $id already shown, skipping duplicate")
                return
            }
            AlertStore.save(ctx, LastAlert(kind, title, body, url, System.currentTimeMillis()))
            if (AlertStore.isSeriesAcked(ctx, series)) {
                Log.i(DoomApp.TAG, "Reminder for series $series already seen, staying quiet")
                return
            }
            if (!series.isNullOrEmpty()) AlertStore.setCurrentSeries(ctx, series)
            if (kind == "alarm") Alarm.fire(ctx, title, body, url) else Alarm.info(ctx, title, body, url)
        }
    }

    override fun onNewToken(token: String) {
        // Topic messaging doesn't need the token, but re-subscribe so a reset token keeps alerts.
        (application as? DoomApp)?.subscribe()
    }
}
