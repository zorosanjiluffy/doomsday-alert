package com.doomsdaywatch.alert

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.sin

/**
 * The red "tickets just dropped" takeover. When the system launches it full-screen on the lock
 * screen (autoBuzz), it rings and vibrates until the person touches the screen. If they leave
 * without touching, the insistent notification is posted again so the phone keeps buzzing.
 */
class AlertActivity : ComponentActivity() {
    private val buzzer by lazy { Buzzer(this) }
    private var acknowledged by mutableStateOf(false)
    private var title by mutableStateOf("")
    private var body by mutableStateOf("")
    private var url = BuildConfig.DISTRICT_URL

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        load(intent)
        setContent {
            AlertScreen(
                title = title,
                body = body,
                buzzing = !acknowledged,
                onTouch = ::acknowledge,
                onOpen = {
                    acknowledge()
                    openDistrict(this, url)
                    finish()
                },
                onClose = {
                    acknowledge()
                    finish()
                },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        load(intent)
    }

    private fun load(i: Intent) {
        title = i.getStringExtra(EXTRA_TITLE) ?: "Doomsday tickets just dropped"
        body = i.getStringExtra(EXTRA_BODY) ?: ""
        url = i.getStringExtra(EXTRA_URL) ?: BuildConfig.DISTRICT_URL
        val autoBuzz = i.getBooleanExtra(EXTRA_AUTOBUZZ, false)
        if (autoBuzz && !acknowledged) {
            // Take over from the notification so sound/vibration don't double up.
            Alarm.stop(this)
            buzzer.stop()
            buzzer.start()
        } else {
            acknowledge()
        }
    }

    private fun acknowledge() {
        if (acknowledged) return
        acknowledged = true
        AlertStore.ackCurrentSeries(this)
        buzzer.stop()
        Alarm.stop(this)
    }

    override fun onStop() {
        super.onStop()
        if (!acknowledged && !isChangingConfigurations) {
            // Screen went off / they navigated away without touching it: keep the phone buzzing.
            buzzer.stop()
            Alarm.fire(this, title, body, url)
            finish()
        }
    }

    override fun onDestroy() {
        buzzer.stop()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_BODY = "body"
        private const val EXTRA_URL = "url"
        private const val EXTRA_AUTOBUZZ = "autoBuzz"

        fun intent(ctx: Context, title: String, body: String, url: String, autoBuzz: Boolean) =
            Intent(ctx, AlertActivity::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_BODY, body)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_AUTOBUZZ, autoBuzz)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}

/** Opens the Doomsday page: the District app if it handles the link, otherwise the browser. */
fun openDistrict(ctx: Context, url: String) {
    val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        ctx.startActivity(i)
    } catch (_: Exception) {
    }
}

private val Red = Color(0xFFE24B4A)
private val RedDeep = Color(0xFF1A0606)
private val RedText = Color(0xFFFCEBEB)
private val RedSoft = Color(0xFFF09595)

@Composable
private fun AlertScreen(
    title: String,
    body: String,
    buzzing: Boolean,
    onTouch: () -> Unit,
    onOpen: () -> Unit,
    onClose: () -> Unit,
) {
    val t = rememberInfiniteTransition(label = "alert")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "rings")
    val shake by t.animateFloat(0f, 1f, infiniteRepeatable(tween(260, easing = LinearEasing), RepeatMode.Restart), label = "shake")

    Box(
        Modifier
            .fillMaxSize()
            .background(RedDeep)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(pass = PointerEventPass.Initial)
                    onTouch()
                }
            },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 36.dp)
                .graphicsLayer {
                    if (buzzing) translationX = sin(shake * 2 * Math.PI.toFloat()) * 10f
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                if (buzzing) "Touch anywhere to stop the alarm" else "Alarm stopped",
                color = RedSoft,
                fontSize = 13.sp,
                modifier = Modifier
                    .border(1.dp, Red, RoundedCornerShape(50))
                    .padding(horizontal = 14.dp, vertical = 5.dp),
            )
            Spacer(Modifier.height(40.dp))
            Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val c = Offset(size.width / 2, size.height / 2)
                    for (k in 0 until 3) {
                        val p = (phase + k / 3f) % 1f
                        drawCircle(
                            Red.copy(alpha = (1f - p) * 0.9f),
                            radius = size.minDimension * (0.22f + p * 0.28f),
                            center = c,
                            style = Stroke(width = 5f),
                        )
                    }
                    drawCircle(Red, radius = size.minDimension * 0.2f, center = c)
                }
                Text("🎟", fontSize = 46.sp)
            }
            Spacer(Modifier.height(28.dp))
            Text(
                title,
                color = RedText,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                lineHeight = 32.sp,
            )
            Spacer(Modifier.height(14.dp))
            Text(body, color = RedSoft, fontSize = 15.sp, textAlign = TextAlign.Center, lineHeight = 22.sp)
            Spacer(Modifier.height(36.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Red)
                    .clickable(onClick = onOpen)
                    .padding(vertical = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Open in District  →", color = RedText, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "Close",
                color = RedSoft,
                fontSize = 15.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onClose)
                    .padding(horizontal = 24.dp, vertical = 10.dp),
            )
        }
    }
}
