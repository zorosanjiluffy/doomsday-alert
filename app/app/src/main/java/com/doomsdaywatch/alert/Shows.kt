package com.doomsdaywatch.alert

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One show from the watcher's /shows list. */
data class ShowItem(
    val lang: String,
    val date: String,
    val cinema: String,
    val time: String,
    val screen: String,
    val status: String,
    val url: String,
    val isNew: Boolean,
)

data class ShowsData(val shows: List<ShowItem>, val newToday: Int)

private val ShowsUrl: String get() = BuildConfig.STATUS_URL.replace(Regex("/status$"), "/shows")

private suspend fun fetchShows(): ShowsData? {
    if (BuildConfig.STATUS_URL.isEmpty()) return null
    return withContext(Dispatchers.IO) {
        try {
            val conn = URL(ShowsUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            val j = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val arr = j.getJSONArray("shows")
            val list = (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ShowItem(
                    o.optString("lang"), o.optString("date"), o.optString("cinema"), o.optString("time"),
                    o.optString("screen"), o.optString("status"), o.optString("url"), o.optBoolean("isNew"),
                )
            }
            ShowsData(list, j.optInt("newToday"))
        } catch (_: Exception) {
            null
        }
    }
}

private val Card = Color(0xFF0E1513)
private val Line = Color(0xFF22302B)
private val Text1 = Color(0xFFE1F5EE)
private val Text2 = Color(0xFF9FE1CB)
private val Muted = Color(0xFF6F7D78)
private val Green = Color(0xFF1D9E75)
private val GreenBright = Color(0xFF5DCAA5)
private val Amber = Color(0xFFEF9F27)
private val Red = Color(0xFFE24B4A)

private fun prettyDate(iso: String): String = try {
    LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH))
} catch (_: Exception) {
    iso
}

/** Seat status colour: green available, amber filling fast, red almost full / sold out. */
private fun statusColor(s: String): Color = when {
    s.contains("sold", true) || s.contains("almost", true) -> Red
    s.contains("fast", true) -> Amber
    else -> GreenBright
}

/**
 * Every show the watcher sees, by language and date. Shows released today (IST) carry a NEW badge
 * and a banner at the top until midnight; the next day, with nothing new, no banner shows.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShowsSection() {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var data by remember { mutableStateOf<ShowsData?>(null) }
    var lang by remember { mutableStateOf("Tamil") }
    var date by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var onlyNew by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                fetchShows()?.let { data = it }
                delay(90_000)
            }
        }
    }

    val d = data
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Card)
            .border(1.dp, Line, RoundedCornerShape(16.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Shows", color = Text1, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            // Language dropdown.
            Box {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .border(1.dp, Green, RoundedCornerShape(10.dp))
                        .clickable { menuOpen = true }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(lang, color = GreenBright, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(6.dp))
                    Text("▾", color = GreenBright, fontSize = 14.sp)
                }
                val langs = d?.shows?.map { it.lang }?.distinct()?.sortedByDescending { it == "Tamil" } ?: listOf("Tamil", "English")
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    langs.forEach { l ->
                        val n = d?.shows?.count { it.lang == l } ?: 0
                        DropdownMenuItem(
                            text = { Text("$l  ($n shows)") },
                            onClick = {
                                lang = l
                                date = null
                                menuOpen = false
                            },
                        )
                    }
                }
            }
        }

        if (d == null) {
            Spacer(Modifier.height(10.dp))
            Text("Loading shows…", color = Muted, fontSize = 13.sp)
            return@Column
        }

        // New-today banner (only on a day something new opened).
        if (d.newToday > 0) {
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF2A1C06))
                    .border(1.dp, Amber, RoundedCornerShape(12.dp))
                    .clickable {
                        onlyNew = !onlyNew
                        // Jump to a language that actually has new shows.
                        if (onlyNew && d.shows.none { it.isNew && it.lang == lang }) {
                            d.shows.filter { it.isNew }.groupBy { it.lang }.maxByOrNull { it.value.size }?.key?.let { lang = it }
                            date = null
                        }
                    }
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("🆕", fontSize = 18.sp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "${d.newToday} new show${if (d.newToday > 1) "s" else ""} released today",
                        color = Amber, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    )
                    val split = d.shows.filter { it.isNew }.groupBy { it.lang }.entries
                        .sortedByDescending { it.value.size }.joinToString(" · ") { "${it.value.size} ${it.key}" }
                    Text(
                        if (onlyNew) "$split · showing only new · tap to show all" else "$split · tap to see them",
                        color = Text2, fontSize = 12.sp,
                    )
                }
            }
        }

        val forLang = d.shows.filter { it.lang == lang && (!onlyNew || it.isNew) }
        val dates = forLang.map { it.date }.distinct().sorted()
        val selDate = date?.takeIf { it in dates } ?: dates.firstOrNull()

        if (dates.isEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                if (onlyNew) "No new $lang shows today." else "No $lang shows listed yet. You'll get an alarm the moment they open.",
                color = Muted, fontSize = 13.sp,
            )
            return@Column
        }

        // Date chips.
        Spacer(Modifier.height(12.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            dates.forEach { dt ->
                val sel = dt == selDate
                val hasNew = forLang.any { it.date == dt && it.isNew }
                Text(
                    prettyDate(dt) + if (hasNew) "  🆕" else "",
                    color = if (sel) Color(0xFF07090A) else Text2,
                    fontSize = 13.sp,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (sel) GreenBright else Color.Transparent)
                        .border(1.dp, if (sel) GreenBright else Line, RoundedCornerShape(20.dp))
                        .clickable { date = dt }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        // Theatres and their show times for that date.
        val byCinema = forLang.filter { it.date == selDate }.groupBy { it.cinema }
        byCinema.entries.sortedByDescending { e -> e.value.any { it.isNew } }.forEach { (cinema, shows) ->
            Spacer(Modifier.height(12.dp))
            val newHere = shows.any { it.isNew }
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .border(1.dp, if (newHere) Amber else Line, RoundedCornerShape(12.dp))
                    .clickable { openDistrict(ctx, shows.first().url) }
                    .padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(cinema, color = Text1, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    if (newHere) {
                        Text(
                            "NEW",
                            color = Color(0xFF07090A), fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Amber).padding(horizontal = 6.dp, vertical = 2.dp),
                        )
                    }
                }
                val screens = shows.map { it.screen }.filter { it.isNotBlank() }.distinct()
                if (screens.isNotEmpty()) {
                    Text(screens.joinToString(" · "), color = Muted, fontSize = 11.sp)
                }
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    shows.forEach { s ->
                        val c = statusColor(s.status)
                        Text(
                            s.time + if (s.isNew) " 🆕" else "",
                            color = c, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, if (s.isNew) Amber else c.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                .clickable { openDistrict(ctx, s.url) }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Legend(GreenBright, "Available")
            Legend(Amber, "Filling fast")
            Legend(Red, "Almost full")
        }
        Spacer(Modifier.height(4.dp))
        Text("Tap a theatre or time to book on District.", color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun Legend(c: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(8.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(c))
        Spacer(Modifier.width(4.dp))
        Text(label, color = Muted, fontSize = 11.sp)
    }
}
