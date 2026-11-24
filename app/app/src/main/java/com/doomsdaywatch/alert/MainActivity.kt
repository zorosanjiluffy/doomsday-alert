package com.doomsdaywatch.alert

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.OffsetDateTime

private val Black = Color(0xFF07090A)
private val Text1 = Color(0xFFE1F5EE)
private val Text2 = Color(0xFF9FE1CB)
private val Muted = Color(0xFF5F5E5A)
private val Green = Color(0xFF1D9E75)
private val GreenBright = Color(0xFF5DCAA5)
private val Amber = Color(0xFFEF9F27)

/** First show: Thu 17 Dec 2026, 6:00 AM IST. */
private val RELEASE: Instant = OffsetDateTime.parse("2026-12-17T06:00:00+05:30").toInstant()

data class WatcherStatus(val lastCheck: Instant?, val shows: Int, val tamilTheatres: Int, val dates: Int, val error: String?)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        (application as DoomApp).subscribe()
        setContent { DoomRoot() }
    }
}

/** Shows the mandatory setup until every permission is granted, then the home screen. */
@Composable
private fun DoomRoot() {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        // Re-check every time the person comes back from a system popup or settings page.
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                tick++
                delay(1500)
            }
        }
    }
    val steps = remember(tick) { requiredSteps(ctx) }
    if (steps.all { it.done }) HomeScreen() else SetupScreen(steps) { tick++ }
}

@Composable
private fun HomeScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var status by remember { mutableStateOf<WatcherStatus?>(null) }
    var refresh by remember { mutableIntStateOf(0) } // bumps when we return to the app
    var testCountdown by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            refresh++
            while (true) {
                now = System.currentTimeMillis()
                delay(1000)
            }
        }
    }
    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                fetchStatus()?.let { status = it }
                delay(30_000)
            }
        }
    }

    val last = remember(refresh, now / 5000) { AlertStore.last(ctx) }
    val recentAlarm = last != null && last.kind == "alarm" && now - last.at < 30 * 60_000
    val msLeft = RELEASE.toEpochMilli() - now
    val daysLeft = msLeft / 86_400_000f
    val speed = 1f + 2f * (1f - (daysLeft / 30f).coerceIn(0f, 1f))

    Column(
        Modifier
            .fillMaxSize()
            .background(Black)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        StatusPill(status, pushOn = DoomApp.pushConfigured && AlertStore.isSubscribed(ctx))
        Spacer(Modifier.height(8.dp))

        Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
            DoomSigil(
                Modifier.fillMaxSize(),
                colors = if (recentAlarm) RedSigil else GreenSigil,
                speed = speed,
                pulseKey = status?.lastCheck,
            )
            Column(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(Black.copy(alpha = 0.88f))
                    .border(1.dp, Color(0xFF0F6E56), RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(if (msLeft > 0) "Doom arrives in" else "Doom has arrived", color = GreenBright, fontSize = 12.sp)
                Text(
                    countdown(msLeft),
                    color = Text1,
                    fontSize = 24.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Text(
            "Avengers: Doomsday · Chennai",
            color = Text1,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(statusLine(status, now), color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center)

        if (last != null) {
            Spacer(Modifier.height(16.dp))
            LastAlertCard(last, now)
        }

        Spacer(Modifier.height(20.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Green)
                .clickable { openDistrict(ctx, last?.url ?: BuildConfig.DISTRICT_URL) }
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Open Doomsday on District  →", color = Black, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(20.dp))
        Text(
            if (testCountdown > 0) "Test alarm in $testCountdown… lock your phone now" else "Test the alarm (rings in 10 seconds)",
            color = if (testCountdown > 0) Amber else Text2,
            fontSize = 14.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, Color(0xFF0F6E56), RoundedCornerShape(12.dp))
                .clickable(enabled = testCountdown == 0) {
                    scope.launch {
                        for (i in 10 downTo 1) {
                            testCountdown = i
                            delay(1000)
                        }
                        testCountdown = 0
                        Alarm.fire(
                            ctx.applicationContext,
                            "🚨 TEST: Doomsday tickets just dropped!",
                            "This is a test. If it's buzzing, you're ready.\nTouch the screen to stop.",
                            BuildConfig.DISTRICT_URL,
                        )
                    }
                }
                .padding(horizontal = 18.dp, vertical = 12.dp),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "Not affiliated with Marvel or District. Fan-made alert for friends.",
            color = Muted,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun StatusPill(status: WatcherStatus?, pushOn: Boolean) {
    val ok = pushOn && status?.error == null
    Row(
        Modifier
            .border(1.dp, if (ok) Green else Amber, RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(if (ok) GreenBright else Amber))
        Spacer(Modifier.width(8.dp))
        Text(
            when {
                !DoomApp.pushConfigured -> "Push not set up in this build"
                !pushOn -> "Connecting to alerts…"
                else -> "Watching District · Chennai"
            },
            color = if (ok) GreenBright else Amber,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun LastAlertCard(a: LastAlert, now: Long) {
    val alarm = a.kind == "alarm"
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (alarm) Color(0xFF2A0B0B) else Color(0xFF0B1F19))
            .padding(14.dp),
    ) {
        Text("Last alert · ${ago(now - a.at)}", color = if (alarm) Color(0xFFF09595) else Text2, fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        Text(a.title, color = Text1, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        if (a.body.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(a.body, color = Text2, fontSize = 13.sp, maxLines = 6, lineHeight = 18.sp)
        }
    }
}

private suspend fun fetchStatus(): WatcherStatus? {
    if (BuildConfig.STATUS_URL.isEmpty()) return null
    return withContext(Dispatchers.IO) {
        try {
            val conn = URL(BuildConfig.STATUS_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            val j = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            WatcherStatus(
                lastCheck = j.optString("lastCheck").takeIf { it.isNotEmpty() && it != "null" }?.let { Instant.parse(it) },
                shows = j.optInt("shows"),
                tamilTheatres = j.optJSONObject("theatresByLanguage")?.optInt("Tamil") ?: 0,
                dates = j.optJSONArray("dates")?.length() ?: 0,
                error = j.optString("lastError").takeIf { it.isNotEmpty() && it != "null" },
            )
        } catch (_: Exception) {
            null
        }
    }
}

private fun statusLine(s: WatcherStatus?, now: Long): String {
    if (BuildConfig.STATUS_URL.isEmpty()) return "Alerts arrive the moment new theatres or dates open"
    if (s?.lastCheck == null) return "Contacting the watcher…"
    val base = "Last check ${ago(now - s.lastCheck.toEpochMilli())} · ${s.shows} shows · Tamil at ${s.tamilTheatres} theatres"
    return if (s.error != null) "$base\nWatcher is retrying: ${s.error.take(60)}" else base
}

private fun countdown(ms: Long): String {
    if (ms <= 0) return "00d 00h 00m"
    val s = ms / 1000
    return "%02dd %02dh %02dm %02ds".format(s / 86400, s % 86400 / 3600, s % 3600 / 60, s % 60)
}

private fun ago(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s ago"
        s < 3600 -> "${s / 60} min ago"
        s < 86400 -> "${s / 3600} h ago"
        else -> "${s / 86400} d ago"
    }
}
