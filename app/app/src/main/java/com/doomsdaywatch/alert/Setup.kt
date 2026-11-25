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

/** Brands whose own battery managers kill background apps unless Autostart is allowed. */
private val AGGRESSIVE_OEMS = setOf("xiaomi", "redmi", "poco", "vivo", "iqoo", "oppo", "realme", "oneplus", "huawei", "honor", "tecno", "infinix")

private fun manufacturer() = Build.MANUFACTURER.lowercase()

fun needsAutostartStep(): Boolean = manufacturer() in AGGRESSIVE_OEMS

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
    if (Build.VERSION.SDK_INT >= 34) {
        steps += SetupStep(
            "fullscreen", "Allow full-screen alarm",
            "Lets the alarm take over your lock screen like an incoming call.",
            "Open setting and turn it on", Alarm.canFullScreen(ctx),
        )
    }
    if (needsAutostartStep()) {
        val brand = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        steps += SetupStep(
            "autostart", "Turn on Autostart ($brand)",
            "$brand phones kill apps you swipe away. Find Doomsday Alert and switch Autostart ON. Also open Recent apps, long-press Doomsday Alert and tap the lock icon so swiping never kills it.",
            "Open Autostart settings", p.getBoolean("autostartDone", false),
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
    val pkg = Uri.parse("package:${ctx.packageName}")
    fun cmp(p: String, c: String) = Intent().setComponent(ComponentName(p, c))
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
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg),
    )
    startFirst(ctx, tries)
}

private fun startFirst(ctx: Context, intents: List<Intent>) {
    for (i in intents) {
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
    var autostartOpened by remember { mutableIntStateOf(if (prefs.getBoolean("autostartOpened", false)) 1 else 0) }

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
                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
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
                        .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
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
                autostartOpened = 1
                openAutostart(ctx)
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
                    Spacer(Modifier.height(14.dp))
                    BigButton(s.button, Amber) { act(s) }
                    if (s.key == "autostart" && autostartOpened == 1) {
                        Spacer(Modifier.height(10.dp))
                        BigButton("I've turned Autostart ON", Green) {
                            prefs.edit().putBoolean("autostartDone", true).apply()
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
