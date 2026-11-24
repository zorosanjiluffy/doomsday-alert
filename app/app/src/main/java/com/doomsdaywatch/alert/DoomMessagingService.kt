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

        AlertStore.save(this, LastAlert(kind, title, body, url, System.currentTimeMillis()))
        if (kind == "alarm") Alarm.fire(this, title, body, url) else Alarm.info(this, title, body, url)
    }

    override fun onNewToken(token: String) {
        // Topic messaging doesn't need the token, but re-subscribe so a reset token keeps alerts.
        (application as? DoomApp)?.subscribe()
    }
}
