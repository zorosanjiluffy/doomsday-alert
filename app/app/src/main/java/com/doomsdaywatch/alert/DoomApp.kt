package com.doomsdaywatch.alert

import android.app.Application
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging

class DoomApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Alarm.createChannels(this)
        initFirebase()
    }

    private fun initFirebase() {
        if (!pushConfigured) {
            Log.w(TAG, "No Firebase config baked into this build; push alerts are off")
            return
        }
        if (FirebaseApp.getApps(this).isEmpty()) {
            val options = FirebaseOptions.Builder()
                .setApiKey(BuildConfig.FB_API_KEY)
                .setApplicationId(BuildConfig.FB_APP_ID)
                .setProjectId(BuildConfig.FB_PROJECT_ID)
                .setGcmSenderId(BuildConfig.FB_SENDER_ID)
                .build()
            FirebaseApp.initializeApp(this, options)
        }
        subscribe()
    }

    fun subscribe() {
        if (!pushConfigured) return
        FirebaseMessaging.getInstance().subscribeToTopic(BuildConfig.FCM_TOPIC)
            .addOnCompleteListener { task ->
                AlertStore.setSubscribed(this, task.isSuccessful)
                Log.i(TAG, "Topic ${BuildConfig.FCM_TOPIC} subscribe: ${task.isSuccessful} ${task.exception?.message ?: ""}")
            }
    }

    companion object {
        const val TAG = "DoomApp"
        val pushConfigured: Boolean get() = BuildConfig.FB_APP_ID.isNotEmpty()
    }
}
