package com.doomsdaywatch.alert

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/*
 * Home-screen scenes, cycling: the Doom sigil, then Thor, then Loki (~8 s each; tap to skip).
 * Thor and Loki are the user's pictures brought to life with effects drawn on top
 * (crackling lightning, glowing strands, mist, debris) and tilt-to-move depth.
 */

private const val SCENE_S = 8f
private const val FADE_S = 0.9f

private val BoltGlow = Color(0xFF378ADD)
private val BoltMid = Color(0xFF85B7EB)
private val BoltCore = Color(0xFFF4F9FF)
private val LokiGlow = Color(0xFFC0DD97)
private val LokiMist = Color(0xFF5DCAA5)

/**
 * Full-screen animated background. The home content sits on top of it.
 * @param skip     bump to jump to the next scene (taps on the art area)
 * @param sigilTop where the Doom sigil's square starts from the top of the screen
 * @param onIndex  told which scene is showing (for the dots)
 */
@Composable
fun DoomScene(
    modifier: Modifier = Modifier,
    colors: SigilColors,
    speed: Float,
    pulseKey: Any?,
    skip: Int,
    sigilTop: Dp,
    onIndex: (Int) -> Unit,
) {
    var time by remember { mutableFloatStateOf(0f) }
    var index by remember { mutableIntStateOf(0) }
    var prev by remember { mutableIntStateOf(0) }
    var switchedAt by remember { mutableFloatStateOf(-10f) }
    var nextSwitch by remember { mutableFloatStateOf(SCENE_S) }
    val tilt = rememberTilt()

    fun advance() {
        prev = index
        index = (index + 1) % 3
        switchedAt = time
        nextSwitch = time + SCENE_S
        onIndex(index)
    }

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) time += (now - last) / 1_000_000_000f
                last = now
            }
            if (time >= nextSwitch) advance()
        }
    }
    LaunchedEffect(skip) { if (skip > 0) advance() }

    val fade = ((time - switchedAt) / FADE_S).coerceIn(0f, 1f)
    Box(modifier.fillMaxSize().background(Color(0xFF07090A))) {
        if (fade < 1f && prev != index) SceneLayer(prev, 1f - fade, time, colors, speed, pulseKey, tilt.value, sigilTop)
        SceneLayer(index, fade, time, colors, speed, pulseKey, tilt.value, sigilTop)

        // Transition flavour: a lightning flash into Thor, a wash of green mist into Loki.
        if (fade < 1f) {
            Canvas(Modifier.fillMaxSize()) {
                val k = sin(fade * PI.toFloat())
                when (index) {
                    1 -> drawRect(BoltCore.copy(alpha = 0.55f * k))
                    2 -> drawRect(LokiMist.copy(alpha = 0.35f * k))
                }
            }
        }

        // Fades so the picture melts into the page and the text stays readable.
        Canvas(Modifier.fillMaxSize()) {
            val top = Brush.verticalGradient(
                0f to Color(0xB307090A), 0.1f to Color(0x0007090A),
                0.42f to Color(0x0007090A), 0.57f to Color(0xD907090A), 0.65f to Color(0xFF07090A),
            )
            drawRect(top)
        }
    }
}

/** The little scene dots. */
@Composable
fun SceneDots(index: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(3) { i ->
            Box(
                Modifier
                    .size(if (i == index) 18.dp else 6.dp, 6.dp)
                    .clip(CircleShape)
                    .background(if (i == index) Color(0xFFE1F5EE) else Color(0x66E1F5EE)),
            )
        }
    }
}

@Composable
private fun BoxScope.SceneLayer(
    which: Int,
    alpha: Float,
    time: Float,
    colors: SigilColors,
    speed: Float,
    pulseKey: Any?,
    tilt: Offset,
    sigilTop: Dp,
) {
    val maxShift = with(LocalDensity.current) { 14.dp.toPx() }
    // Tilt, plus a slow drift so it breathes even when the phone is still.
    val drift = Offset(sin(time * 0.25f) * 0.25f, sin(time * 0.19f + 1f) * 0.2f)
    val t = Offset((tilt.x + drift.x).coerceIn(-1f, 1f), (tilt.y + drift.y).coerceIn(-1f, 1f))
    when (which) {
        0 -> Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .align(Alignment.TopCenter)
                .padding(top = sigilTop)
                .graphicsLayer {
                    this.alpha = alpha
                    translationX = t.x * maxShift * 0.5f
                    translationY = t.y * maxShift * 0.5f
                },
        ) { DoomSigil(Modifier.fillMaxSize(), colors, speed, pulseKey) }

        1 -> PictureScene(R.drawable.scene_thor, 736f / 1308f, alpha, t, maxShift) { m, w, h, u ->
            thorEffects(time, m, w, h, u)
        }

        else -> PictureScene(R.drawable.scene_loki, 900f / 1592f, alpha, t, maxShift) { m, w, h, u ->
            lokiEffects(time, m, w, h, u)
        }
    }
}

/**
 * A picture with an effects layer in the same image coordinates. The picture moves with tilt;
 * the effects move further, which reads as depth.
 */
@Composable
private fun PictureScene(
    @DrawableRes res: Int,
    aspect: Float,
    alpha: Float,
    tilt: Offset,
    maxShift: Float,
    effects: DrawScope.(ImgMap, Float, Float, Float) -> Unit,
) {
    // The picture fills the top of the screen (the art area) and melts into black below.
    Box(Modifier.fillMaxWidth().fillMaxHeight(0.64f).graphicsLayer { this.alpha = alpha }) {
        Image(
            painter = painterResource(res),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.1f
                    scaleY = 1.1f
                    translationX = tilt.x * maxShift
                    translationY = tilt.y * maxShift
                },
        )
        Canvas(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.1f
                    scaleY = 1.1f
                    translationX = tilt.x * maxShift * 1.6f
                    translationY = tilt.y * maxShift * 1.6f
                },
        ) {
            effects(ImgMap(size.width, size.height, aspect), size.width, size.height, size.width / 100f)
        }
    }
}

/** Maps picture fractions (0..1) to canvas pixels, matching ContentScale.Crop's centring. */
private class ImgMap(w: Float, h: Float, aspect: Float) {
    private val drawnH = max(w / aspect, h)
    private val drawnW = drawnH * aspect
    private val ox = (w - drawnW) / 2f
    private val oy = (h - drawnH) / 2f
    operator fun invoke(fx: Float, fy: Float) = Offset(ox + fx * drawnW, oy + fy * drawnH)
}

// ---------------- Thor ----------------

private fun DrawScope.thorEffects(t: Float, p: ImgMap, w: Float, h: Float, u: Float) {
    val fist = p(0.51f, 0.545f)
    val sky = p(0.53f, -0.05f)
    val surge = 0.5f + 0.5f * sin(t * 9f) * sin(t * 2.3f)

    // The air glows with each surge.
    drawRect(BoltMid.copy(alpha = 0.05f + 0.08f * surge))

    // The bolt re-forms ~12 times a second, so it crackles like live current.
    val seed = (t * 12f).toInt()
    val rnd = Random(seed)
    val main = mutableListOf(sky)
    displace(sky, fist, 7, 0.18f, rnd, main)
    drawBoltPath(main, u, 1.2f, 0.75f + 0.25f * surge)
    repeat(3) {
        val from = main[rnd.nextInt(main.size / 5, main.size - 3)]
        val end = from + Offset((rnd.nextFloat() - 0.5f) * 28f * u, rnd.nextFloat() * 10f * u)
        val f = mutableListOf(from)
        displace(from, end, 4, 0.45f, rnd, f)
        drawBoltPath(f, u, 0.5f, 0.6f)
    }

    // Arcs leaping off the fist.
    repeat(4) { i ->
        val r2 = Random(seed * 7 + i)
        val ang = r2.nextFloat() * 2f * PI.toFloat()
        val end = fist + Offset(cos(ang), sin(ang)) * ((6f + r2.nextFloat() * 9f) * u)
        val arc = mutableListOf(fist)
        displace(fist, end, 3, 0.5f, r2, arc)
        drawBoltPath(arc, u, 0.35f, 0.7f)
    }

    // Fist glow.
    val glowR = (9f + 4f * surge) * u
    drawCircle(
        Brush.radialGradient(listOf(BoltCore.copy(alpha = 0.95f), BoltMid.copy(alpha = 0.45f), Color.Transparent), fist, glowR),
        glowR, fist,
    )

    // Rock shards tumbling up through the storm.
    for (i in 0 until 14) {
        val sp = 0.03f + (i % 5) * 0.008f
        val ph = (t * sp + i * 0.137f) % 1f
        val x = w * ((i * 0.618f) % 1f) + sin(t * 0.6f + i) * 6f * u
        val y = h * (0.92f - ph * 0.75f)
        val sz = (0.8f + (i % 3) * 0.5f) * u
        val rot = t * (0.6f + (i % 4) * 0.3f) + i
        val a = sin(ph * PI.toFloat()) * 0.9f
        val shard = Path().apply {
            for (v in 0 until 3) {
                val ang = rot + v * 2.1f + (i % 2) * 0.4f
                val r = sz * (if (v == 0) 1.6f else 1f)
                val pt = Offset(x + cos(ang) * r, y + sin(ang) * r)
                if (v == 0) moveTo(pt.x, pt.y) else lineTo(pt.x, pt.y)
            }
            close()
        }
        drawPath(shard, Color(0xFF06101E).copy(alpha = a))
        drawPath(shard, BoltMid.copy(alpha = a * 0.55f), style = Stroke(0.2f * u))
    }
}

private fun DrawScope.drawBoltPath(pts: List<Offset>, u: Float, scale: Float, a: Float) {
    val path = Path().apply { pts.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
    drawPath(path, BoltGlow.copy(alpha = 0.35f * a), style = Stroke(5f * u * scale, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, BoltMid.copy(alpha = 0.8f * a), style = Stroke(1.8f * u * scale, cap = StrokeCap.Round, join = StrokeJoin.Round))
    drawPath(path, BoltCore.copy(alpha = a), style = Stroke(0.7f * u * scale, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

// ---------------- Loki ----------------

private fun bez(p0: Offset, p1: Offset, p2: Offset, s: Float): Offset {
    val m = 1 - s
    return p0 * (m * m) + p1 * (2 * m * s) + p2 * (s * s)
}

private fun DrawScope.lokiEffects(t: Float, p: ImgMap, w: Float, h: Float, u: Float) {
    // Drifting green mist.
    for (i in 0 until 7) {
        val cx = w * (((i * 0.31f) + t * 0.012f * (1 + i % 3)) % 1.2f - 0.1f)
        val cy = h * (0.2f + (i * 0.17f) % 0.75f) + sin(t * 0.3f + i) * 8f * u
        val r = (22f + (i % 3) * 10f) * u
        drawCircle(Brush.radialGradient(listOf(LokiMist.copy(alpha = 0.16f), Color.Transparent), Offset(cx, cy), r), r, Offset(cx, cy))
    }

    val lh = p(0.32f, 0.505f)
    val rh = p(0.735f, 0.51f)
    val head = p(0.52f, 0.39f)
    // The timeline strands: from each hand out to the edges, and from the head up.
    val strands = buildList {
        for (k in 0 until 4) {
            add(Triple(lh, p(0.10f - k * 0.02f, 0.40f - k * 0.03f), p(-0.02f, 0.27f + k * 0.04f)))
            add(Triple(lh, p(0.16f - k * 0.02f, 0.66f + k * 0.03f), p(-0.02f, 0.78f + k * 0.04f)))
            add(Triple(rh, p(0.90f + k * 0.02f, 0.40f - k * 0.03f), p(1.02f, 0.30f + k * 0.04f)))
            add(Triple(rh, p(0.86f + k * 0.02f, 0.66f + k * 0.03f), p(1.02f, 0.76f + k * 0.04f)))
        }
        for (k in 0 until 6) add(Triple(head, p(0.40f + k * 0.04f, 0.20f), p(0.30f + k * 0.09f, -0.02f)))
    }
    strands.forEachIndexed { i, (a, c, b) ->
        // Streaks of energy racing along each strand.
        for (j in 0 until 2) {
            val s = ((t * (0.22f + (i % 5) * 0.04f) + j * 0.5f + i * 0.21f) % 1f)
            val k = sin(s * PI.toFloat())
            var prevPt = bez(a, c, b, max(0f, s - 0.07f))
            for (q in 1..6) {
                val sq = max(0f, s - 0.07f + q * (0.07f / 6f))
                val pt = bez(a, c, b, sq)
                val f = q / 6f
                drawLine(LokiGlow.copy(alpha = 0.25f * k * f), prevPt, pt, 1.1f * u, StrokeCap.Round)
                drawLine(Color(0xFFEAF3DE).copy(alpha = 0.9f * k * f), prevPt, pt, 0.28f * u, StrokeCap.Round)
                prevPt = pt
            }
        }
    }

    // Glowing hands and a shimmer on the horns.
    val pulse = 0.6f + 0.4f * sin(t * 2.4f)
    for (hand in listOf(lh, rh)) {
        val r = (6f + 3f * pulse) * u
        drawCircle(Brush.radialGradient(listOf(LokiGlow.copy(alpha = 0.7f), Color.Transparent), hand, r), r, hand)
    }
    val shimmer = (t * 0.5f) % 1f
    if (shimmer < 0.25f) {
        val hp = p(0.52f, 0.38f)
        val r = 7f * u
        val a = 0.5f * sin(shimmer * 4f * PI.toFloat())
        drawCircle(Brush.radialGradient(listOf(Color(0xFFFAC775).copy(alpha = a), Color.Transparent), hp, r), r, hp)
    }

    // Embers rising.
    for (i in 0 until 30) {
        val ph = (t * (0.04f + (i % 5) * 0.01f) + i * 0.19f) % 1f
        val x = w * ((i * 0.377f) % 1f) + sin(t + i) * 4f * u
        val y = h * (1f - ph)
        drawCircle(LokiGlow.copy(alpha = sin(ph * PI.toFloat()) * 0.7f), (0.35f + (i % 3) * 0.2f) * u, Offset(x, y))
    }
}

// ---------------- shared ----------------

private fun displace(a: Offset, b: Offset, depth: Int, rough: Float, rnd: Random, out: MutableList<Offset>) {
    if (depth == 0) {
        out += b
        return
    }
    val mid = (a + b) / 2f
    val d = b - a
    val len = d.getDistance()
    val n = Offset(-d.y, d.x) / max(len, 0.001f)
    val m = mid + n * ((rnd.nextFloat() - 0.5f) * len * rough)
    displace(a, m, depth - 1, rough, rnd, out)
    displace(m, b, depth - 1, rough, rnd, out)
}

/** Phone tilt from the gravity sensor, smoothed, in -1..1 on each axis. */
@Composable
private fun rememberTilt(): State<Offset> {
    val ctx = LocalContext.current
    val state = remember { mutableStateOf(Offset.Zero) }
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var sx = 0f
        var sy = 0f
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                // Phone held upright reads y ≈ 9.8; leaning changes x and z.
                val tx = (-e.values[0] / 4f).coerceIn(-1f, 1f)
                val ty = ((e.values[2] - 3f) / 4f).coerceIn(-1f, 1f)
                sx += (tx - sx) * 0.12f
                sy += (ty - sy) * 0.12f
                state.value = Offset(sx, sy)
            }

            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm.unregisterListener(listener) }
    }
    return state
}
