package com.doomsdaywatch.alert

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

/** One required setup step. The home screen stays locked until every step is done. */
class SetupStep(
    val key: String,
    val title: String,
    val why: String,
    val button: String,
    val done: Boolean,
)

private const val PREFS = "doom_setup"

/**
 * Brand-specific extra steps. Many brands add their own battery manager and an app killer on top
 * of Android; these guides say where each brand hides the switches. Stock-Android phones
 * (Pixel, Motorola, Nothing, Nokia…) return null and need nothing extra.
 */
class BrandGuide(
    val name: String,
    val autostart: Boolean,
    val power: String,
    val lock: String,
    val powerScreens: (String) -> List<Intent>,
)

private fun manufacturer(): String {
    val override = try {
        @SuppressLint("PrivateApi")
        val sp = Class.forName("android.os.SystemProperties")
        sp.getMethod("get", String::class.java).invoke(null, "debug.doom.brand") as String
    } catch (_: Exception) {
        ""
    }
    return override.ifBlank { Build.MANUFACTURER }.lowercase()
}

private fun cmp(p: String, c: String) = Intent().setComponent(ComponentName(p, c))
private fun appInfo(pkg: String) = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))

fun brandGuide(): BrandGuide? {
    val m = manufacturer()
    val brand = m.replaceFirstChar { it.uppercase() }
    return when {
        m in setOf("vivo", "iqoo") -> BrandGuide(
            brand, true,
            "Settings → Battery → Background power consumption management → Doomsday Alert → Allow (High background power consumption).",
            "Open Recent apps, pull the Doomsday Alert card down (or long-press it) and tap Lock.",
        ) { pkg ->
            listOf(
                cmp("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"),
                cmp("com.iqoo.powersaving", "com.iqoo.powersaving.PowerSavingManagerActivity"),
                appInfo(pkg),
            )
        }
        m in setOf("xiaomi", "redmi", "poco") -> BrandGuide(
            brand, true,
            "Battery saver → No restrictions for Doomsday Alert.",
            "Open Recent apps, long-press the Doomsday Alert card and tap the lock icon.",
        ) { pkg ->
            listOf(
                cmp("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                    .putExtra("package_name", pkg).putExtra("package_label", "Doomsday Alert"),
                appInfo(pkg),
            )
        }
        m in setOf("oppo", "realme", "oneplus") -> BrandGuide(
            brand, true,
            "App info → Battery usage → turn ON Allow background activity (and Allow auto launch if shown).",
            "Open Recent apps, tap the ⋮ on the Doomsday Alert card (or long-press it) and tap Lock.",
        ) { pkg -> listOf(appInfo(pkg)) }
        m in setOf("huawei", "honor") -> BrandGuide(
            brand, true,
            "Settings → Battery → App launch → Doomsday Alert → Manage manually, and turn all three switches ON.",
            "Open Recent apps, pull the Doomsday Alert card down and tap the lock.",
        ) { pkg ->
            listOf(
                cmp("com.huawei.systemmanager", "com.huawei.systemmanager.power.ui.HwPowerManagerActivity"),
                appInfo(pkg),
            )
        }
        m == "samsung" -> BrandGuide(
            brand, false,
            "App info → Battery → Unrestricted. Also check Settings → Battery → Background usage limits: Doomsday Alert must NOT be in Sleeping apps or Deep sleeping apps.",
            "Open Recent apps, tap the Doomsday Alert icon above its card and tap Keep open.",
        ) { pkg -> listOf(appInfo(pkg)) }
        m in setOf("tecno", "infinix", "itel") -> BrandGuide(
            brand, true,
            "Phone Master → Power saving / App management → Doomsday Alert → allow background running.",
            "Open Recent apps, pull the Doomsday Alert card down and tap the lock.",
        ) { pkg -> listOf(cmp("com.transsion.phonemaster", "com.cyin.himgr.powermanager.views.activity.PowerManagerActivity"), appInfo(pkg)) }
        else -> null
    }
}

fun needsAutostartStep(): Boolean = brandGuide()?.autostart == true

fun requiredSteps(ctx: Context): List<SetupStep> {
    val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val notifOk = (Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
        androidx.core.app.NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    val batteryOk = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    val steps = mutableListOf(
        SetupStep(
            "notif", "Allow notifications",
            "Without this the alarm can't reach you at all.",
            "Allow notifications", notifOk,
        ),
        SetupStep(
            "battery", "Let it run in the background",
            "So Android never puts the app to sleep and alerts arrive the second tickets open.",
            "Allow background running", batteryOk,
        ),
    )
    val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
    val dataOk = cm.restrictBackgroundStatus != android.net.ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
    if (!dataOk || p.getBoolean("dataWasBlocked", false)) {
        if (!dataOk) p.edit().putBoolean("dataWasBlocked", true).apply()
        steps += SetupStep(
            "data", "Allow background data",
            "Data Saver is blocking this app's internet in the background, so the backup check can't reach the watcher. Turn on Unrestricted data for Doomsday Alert.",
            "Open data setting", dataOk,
        )
    }
    val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
    val ch = nm.getNotificationChannel(Alarm.CH_ALARM)
    val channelOk = ch == null || ch.importance >= android.app.NotificationManager.IMPORTANCE_HIGH
    if (!channelOk) {
        steps += SetupStep(
            "channel", "Turn the alarm sound back on",
            "The \"Tickets opened (alarm)\" notifications are turned off or silenced, so the alarm can't ring. Set them to Allow with sound and pop-up.",
            "Open alarm notification setting", false,
        )
    }
    if (Build.VERSION.SDK_INT >= 34) {
        steps += SetupStep(
            "fullscreen", "Allow full-screen alarm",
            "Lets the alarm take over your lock screen like an incoming call.",
            "Open setting and turn it on", Alarm.canFullScreen(ctx),
        )
    }
    val guide = brandGuide()
    if (guide != null) {
        if (guide.autostart) {
            steps += SetupStep(
                "autostart", "Turn on Autostart (${guide.name})",
                "${guide.name} blocks apps from starting unless you open them. Find Doomsday Alert and switch Autostart ON, so a ticket alert can wake the app even when it's closed.",
                "Open Autostart settings", p.getBoolean("autostartDone", false),
            )
        }
        steps += SetupStep(
            "power", "Allow high background power (${guide.name})",
            "${guide.name} has its own battery manager that freezes apps and cuts their internet. ${guide.power}",
            "Open battery settings", p.getBoolean("powerDone", false),
        )
        steps += SetupStep(
            "lock", "Lock it in Recent apps",
            "On ${guide.name}, Clear all and swiping away force-stop apps. A locked app is never killed. ${guide.lock}",
            "I've locked it", p.getBoolean("lockDone", false),
        )
    }
    return steps
}

fun allSetupDone(ctx: Context) = requiredSteps(ctx).all { it.done }

@SuppressLint("BatteryLife")
private fun openBattery(ctx: Context) {
    val pkg = Uri.parse("package:${ctx.packageName}")
    // Shows Android's own "Let app always run in background?" popup.
    val tries = listOf(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkg),
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg),
    )
    startFirst(ctx, tries)
}

private fun openAutostart(ctx: Context) {
    val tries = listOf(
        cmp("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        cmp("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        cmp("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity"),
        cmp("com.iqoo.secure", "com.iqoo.secure.safecenter.SafeCenterActivity"),
        cmp("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
        cmp("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
        cmp("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        cmp("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        cmp("com.oplus.safecenter", "com.oplus.safecenter.startupapp.view.StartupAppListActivity"),
        cmp("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
        cmp("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        cmp("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        cmp("com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity"),
        appInfo(ctx.packageName),
    )
    startFirst(ctx, tries)
}

private fun startFirst(ctx: Context, intents: List<Intent>) {
    for (i in intents) {
        try {
            // From the setup screen, open settings in the app's own task so Back returns here.
            if (ctx !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            return
        } catch (_: Exception) {
        }
    }
}

private val Black = Color(0xFF07090A)
private val Text1 = Color(0xFFE1F5EE)
private val Text2 = Color(0xFF9FE1CB)
private val Muted = Color(0xFF6F7D78)
private val Green = Color(0xFF1D9E75)
private val GreenBright = Color(0xFF5DCAA5)
private val Amber = Color(0xFFEF9F27)

/**
 * Mandatory first-run screen. No skip: the back button closes the app, and the home screen only
 * appears once every step is done. Each step opens Android's own permission popup or setting.
 */
@Composable
fun SetupScreen(steps: List<SetupStep>, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as? Activity
    val prefs = remember { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var opened by remember { mutableIntStateOf(0) } // bumps when a settings page is opened
    fun wasOpened(key: String) = opened >= 0 && prefs.getBoolean("${key}Opened", false)

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        prefs.edit().putInt("notifAsks", prefs.getInt("notifAsks", 0) + 1).apply()
        onChanged()
        if (!granted && activity != null && Build.VERSION.SDK_INT >= 33 &&
            !activity.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) &&
            prefs.getInt("notifAsks", 0) >= 2
        ) {
            // Android stops showing the popup after two "Don't allow"s: send them to the setting.
            ctx.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName),
            )
        }
    }

    fun act(step: SetupStep) {
        when (step.key) {
            "notif" -> if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                prefs.getInt("notifAsks", 0) < 2
            ) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                ctx.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName),
                )
            }
            "battery" -> openBattery(ctx)
            "fullscreen" -> if (Build.VERSION.SDK_INT >= 34) {
                startFirst(
                    ctx,
                    listOf(
                        Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${ctx.packageName}")),
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")),
                    ),
                )
            }
            "autostart" -> {
                prefs.edit().putBoolean("autostartOpened", true).apply()
                opened++
                openAutostart(ctx)
            }
            "data" -> startFirst(
                ctx,
                listOf(
                    Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS, Uri.parse("package:${ctx.packageName}")),
                    appInfo(ctx.packageName),
                ),
            )
            "channel" -> startFirst(
                ctx,
                listOf(
                    Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                        .putExtra(Settings.EXTRA_CHANNEL_ID, Alarm.CH_ALARM),
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName),
                ),
            )
            "power" -> {
                prefs.edit().putBoolean("powerOpened", true).apply()
                opened++
                startFirst(ctx, brandGuide()?.powerScreens?.invoke(ctx.packageName) ?: listOf(appInfo(ctx.packageName)))
            }
            "lock" -> {
                prefs.edit().putBoolean("lockDone", true).apply()
                onChanged()
            }
        }
    }

    // Pop the first permission dialog straight away on first launch.
    LaunchedEffect(Unit) {
        val first = steps.firstOrNull { !it.done }
        if (first?.key == "notif" && prefs.getInt("notifAsks", 0) == 0) act(first)
    }

    BackHandler { activity?.finish() }

    val current = steps.indexOfFirst { !it.done }
    val doneCount = steps.count { it.done }

    Column(
        Modifier
            .fillMaxSize()
            .background(Black)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 24.dp),
    ) {
        Text(
            if (current >= 0) "Step ${current + 1} of ${steps.size} · $doneCount done" else "All set",
            color = GreenBright,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(6.dp))
        Text("Arm the Doomsday alarm", color = Text1, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "These are required. Without them your phone can't ring when tickets open, so the app stays locked until they're done.",
            color = Text2, fontSize = 14.sp, lineHeight = 20.sp,
        )
        Spacer(Modifier.height(22.dp))

        steps.forEachIndexed { i, s ->
            val active = i == current
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .border(
                        if (active) 2.dp else 1.dp,
                        when {
                            s.done -> Color(0xFF0F6E56)
                            active -> Amber
                            else -> Color(0xFF2A2F2D)
                        },
                        RoundedCornerShape(16.dp),
                    )
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(if (s.done) Green else if (active) Amber else Color(0xFF2A2F2D)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(if (s.done) "✓" else "${i + 1}", color = Black, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        s.title,
                        color = if (s.done) GreenBright else Text1,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                if (!s.done && active) {
                    Spacer(Modifier.height(8.dp))
                    Text(s.why, color = Text2, fontSize = 14.sp, lineHeight = 20.sp)
                    if (s.key == "lock") {
                        Spacer(Modifier.height(12.dp))
                        LockPicture(brandGuide()?.name ?: "")
                    }
                    Spacer(Modifier.height(14.dp))
                    BigButton(s.button, if (s.key == "lock") Green else Amber) { act(s) }
                    val confirm = when (s.key) {
                        "autostart" -> "I've turned Autostart ON"
                        "power" -> "I've allowed it"
                        else -> null
                    }
                    if (confirm != null && wasOpened(s.key)) {
                        Spacer(Modifier.height(10.dp))
                        BigButton(confirm, Green) {
                            prefs.edit().putBoolean("${s.key}Done", true).apply()
                            onChanged()
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        Text(
            "Your choices stay on your phone. The app only receives ticket alerts.",
            color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** A small drawn picture of the Recent apps screen with the lock highlighted. */
@Composable
private fun LockPicture(brand: String) {
    val samsung = brand.equals("Samsung", ignoreCase = true)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF111816))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Phone with three recent-app cards; ours in the middle, with the lock.
        Box(
            Modifier
                .size(96.dp, 150.dp)
                .clip(RoundedCornerShape(14.dp))
                .border(2.dp, Color(0xFF2A2F2D), RoundedCornerShape(14.dp))
                .padding(8.dp),
        ) {
            Box(Modifier.align(Alignment.CenterStart).size(16.dp, 92.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF1E2522)))
            Box(Modifier.align(Alignment.CenterEnd).size(16.dp, 92.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF1E2522)))
            Column(
                Modifier
                    .align(Alignment.Center)
                    .size(52.dp, 110.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF0B2A20))
                    .border(1.5.dp, Amber, RoundedCornerShape(8.dp)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(6.dp))
                Box(Modifier.size(16.dp).clip(CircleShape).background(Green))
                Spacer(Modifier.height(4.dp))
                Text("Doomsday", color = Text1, fontSize = 8.sp)
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.padding(bottom = 8.dp).size(26.dp).clip(CircleShape).background(Amber),
                    contentAlignment = Alignment.Center,
                ) { Text("🔒", fontSize = 13.sp) }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text("1. Open Recent apps", color = Text1, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                if (samsung) "2. Tap the Doomsday Alert icon" else "2. Pull the card down or long-press it",
                color = Text1, fontSize = 13.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(if (samsung) "3. Tap Keep open" else "3. Tap the 🔒 lock", color = Amber, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("A small lock appears on the card when it's done.", color = Muted, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

@Composable
private fun BigButton(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(color)
            .clickable(onClick = onClick)
            .padding(vertical = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Black, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}
