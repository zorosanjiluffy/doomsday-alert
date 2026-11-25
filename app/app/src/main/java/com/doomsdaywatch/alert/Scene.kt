package com.doomsdaywatch.alert

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/*
 * The home-screen scene: the god of mischief on his throne at the top, holding the glowing
 * timelines that wind down around the Doom sigil, while the thunderer's lightning strikes.
 * Everything is drawn in code: an original fan-made design, no studio artwork.
 *
 * Layout uses a 100 x 140 unit box (u = width / 100). The sigil sits in the bottom square
 * (centre 50,90, radius 46); the throne rests on its rim at the top.
 */

private val GoldDeep = Color(0xFF854F0B)
private val Gold = Color(0xFFEF9F27)
private val GoldLight = Color(0xFFFAC775)
private val ThroneFill = Color(0xFF0C1210)
private val Cape = Color(0xFF0B2A20)
private val CapeEdge = Color(0xFF1D9E75)
private val BoltGlow = Color(0xFF378ADD)
private val BoltMid = Color(0xFF85B7EB)
private val BoltCore = Color(0xFFF4F9FF)

private data class Bolt(val main: List<Offset>, val forks: List<List<Offset>>, val born: Float, val target: Offset)

@Composable
fun DoomScene(
    modifier: Modifier = Modifier,
    colors: SigilColors,
    speed: Float,
    pulseKey: Any?,
    center: @Composable BoxScope.() -> Unit,
) {
    var time by remember { mutableFloatStateOf(0f) }
    var bolts by remember { mutableStateOf(listOf<Bolt>()) }
    var nextBolt by remember { mutableFloatStateOf(1.2f) }
    var strikeRequest by remember { mutableStateOf(0) }
    val rnd = remember { Random(System.nanoTime()) }
    var widthPx by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) time += (now - last) / 1_000_000_000f
                last = now
            }
            val u = widthPx / 100f
            if (u > 0f && (time >= nextBolt || strikeRequest > 0)) {
                strikeRequest = 0
                bolts = (bolts.filter { time - it.born < 0.9f } + makeBolt(u, rnd, time)).takeLast(3)
                nextBolt = time + 2.5f + rnd.nextFloat() * 4f
            }
        }
    }
    LaunchedEffect(pulseKey) { if (pulseKey != null) strikeRequest++ }

    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(100f / 140f)
            .onSizeChanged { widthPx = it.width.toFloat() },
    ) {
        // Back layer: throne, figure, timelines.
        Canvas(Modifier.fillMaxSize()) {
            val u = size.width / 100f
            timelines(u, time * speed, colors)
            throne(u, time)
        }
        // The sigil in the bottom square, countdown plate on top of it.
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .align(Alignment.BottomCenter),
            contentAlignment = Alignment.Center,
        ) {
            DoomSigil(Modifier.fillMaxSize(), colors, speed, pulseKey)
            center()
        }
        // Front layer: lightning.
        Canvas(Modifier.fillMaxSize()) {
            val u = size.width / 100f
            bolts.forEach { drawBolt(it, time, u) }
        }
    }
}

// ---------------- throne + figure ----------------

private fun DrawScope.p(u: Float, x: Float, y: Float) = Offset(x * u, y * u)

private fun DrawScope.throne(u: Float, t: Float) {
    // Throne back with Asgardian spires.
    val back = Path().apply {
        val pts = listOf(
            30f to 44f, 30f to 16f, 33f to 9f, 36f to 15f, 40f to 6f, 44f to 13f, 50f to 0.5f,
            56f to 13f, 60f to 6f, 64f to 15f, 67f to 9f, 70f to 16f, 70f to 44f,
        )
        pts.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x * u, y * u) else lineTo(x * u, y * u) }
        close()
    }
    drawPath(back, ThroneFill, style = Fill)
    drawPath(back, GoldDeep, style = Stroke(0.5f * u, join = StrokeJoin.Round))
    // Inner inlay lines.
    for (x in listOf(40f, 50f, 60f)) drawLine(GoldDeep.copy(alpha = 0.6f), p(u, x, 16f), p(u, x, 40f), 0.3f * u)

    // Armrests.
    for (side in listOf(-1f, 1f)) {
        val x0 = 50f + side * 20f
        val arm = Path().apply {
            moveTo((x0 - 4f) * u, 34f * u); lineTo((x0 + 4f) * u, 34f * u)
            lineTo((x0 + 3f) * u, 44f * u); lineTo((x0 - 3f) * u, 44f * u); close()
        }
        drawPath(arm, ThroneFill)
        drawPath(arm, GoldDeep, style = Stroke(0.4f * u))
    }

    // Cape flaring out behind the arms.
    val capeBack = Path().apply {
        moveTo(45f * u, 23f * u)
        cubicTo(38f * u, 26f * u, 31f * u, 34f * u, 27f * u, 45f * u)
        lineTo(73f * u, 45f * u)
        cubicTo(69f * u, 34f * u, 62f * u, 26f * u, 55f * u, 23f * u)
        close()
    }
    drawPath(capeBack, Color(0xFF07170F))
    drawPath(capeBack, CapeEdge.copy(alpha = 0.35f), style = Stroke(0.3f * u))

    // Cape / seated body.
    val body = Path().apply {
        moveTo(44.5f * u, 22.5f * u)
        quadraticTo(50f * u, 20.5f * u, 55.5f * u, 22.5f * u)
        lineTo(61f * u, 44f * u)
        quadraticTo(50f * u, 46f * u, 39f * u, 44f * u)
        close()
    }
    drawPath(body, Cape)
    drawPath(body, CapeEdge.copy(alpha = 0.8f), style = Stroke(0.45f * u, join = StrokeJoin.Round))
    // Collar.
    drawLine(Gold, p(u, 46f, 23f), p(u, 54f, 23f), 0.6f * u, StrokeCap.Round)

    // Arms reaching out to hold the timelines.
    val sway = sin(t * 0.8f) * 0.6f
    val lh = p(u, 33f, 27f + sway)
    val rh = p(u, 67f, 27f - sway)
    drawLine(Cape, p(u, 45f, 24f), lh, 2.2f * u, StrokeCap.Round)
    drawLine(Cape, p(u, 55f, 24f), rh, 2.2f * u, StrokeCap.Round)
    drawLine(CapeEdge.copy(alpha = 0.5f), p(u, 45f, 24f), lh, 0.35f * u, StrokeCap.Round)
    drawLine(CapeEdge.copy(alpha = 0.5f), p(u, 55f, 24f), rh, 0.35f * u, StrokeCap.Round)
    val glow = 0.6f + 0.4f * sin(t * 3f)
    for (h in listOf(lh, rh)) {
        drawCircle(GoldLight.copy(alpha = 0.25f * glow), 3.2f * u, h)
        drawCircle(GoldLight, 1.1f * u, h)
    }

    // Head and the great curved horns.
    val helm = Path().apply {
        moveTo(46.6f * u, 16.2f * u)
        quadraticTo(50f * u, 13.4f * u, 53.4f * u, 16.2f * u)
        lineTo(53.2f * u, 19.6f * u)
        lineTo(50f * u, 22.6f * u)
        lineTo(46.8f * u, 19.6f * u)
        close()
    }
    drawPath(helm, Color(0xFF101614))
    drawPath(helm, GoldDeep, style = Stroke(0.3f * u, join = StrokeJoin.Round))
    for (side in listOf(-1f, 1f)) {
        val horn = Path().apply {
            moveTo((50f + side * 1.5f) * u, 15.6f * u)
            cubicTo((50f + side * 4f) * u, 10f * u, (50f + side * 7.5f) * u, 5f * u, (50f + side * 12.5f) * u, 1.5f * u)
            cubicTo((50f + side * 8.5f) * u, 6.5f * u, (50f + side * 5.5f) * u, 11f * u, (50f + side * 3.4f) * u, 16.4f * u)
            close()
        }
        drawPath(horn, Gold)
        drawPath(horn, GoldDeep, style = Stroke(0.3f * u))
    }
    // Crown band and eyes.
    drawLine(Gold, p(u, 47f, 16.4f), p(u, 53f, 16.4f), 0.8f * u, StrokeCap.Round)
    val blink = if ((t % 5f) > 4.85f) 0.1f else 1f
    drawCircle(Color(0xFF9FE1CB).copy(alpha = blink), 0.45f * u, p(u, 48.7f, 18.4f))
    drawCircle(Color(0xFF9FE1CB).copy(alpha = blink), 0.45f * u, p(u, 51.3f, 18.4f))
}

// ---------------- timelines ----------------

private fun bez(p0: Offset, p1: Offset, p2: Offset, p3: Offset, s: Float): Offset {
    val m = 1 - s
    return p0 * (m * m * m) + p1 * (3 * m * m * s) + p2 * (3 * m * s * s) + p3 * (s * s * s)
}

private fun DrawScope.timelines(u: Float, t: Float, colors: SigilColors) {
    for (side in listOf(-1f, 1f)) {
        val hand = Offset((50f + side * 17f) * u, 27f * u)
        for (k in 0 until 5) {
            val w = sin(t * 0.7f + k * 1.3f + side) * 2.2f
            val p1 = Offset((50f + side * (30f + k * 3.5f) + w) * u, (12f - k * 2.5f) * u)
            val p2 = Offset((50f + side * (60f + k * 2.5f)) * u, (80f + k * 7f + w) * u)
            val p3 = Offset((50f + side * (5f + k * 4.5f)) * u, (139f - k * 1.2f) * u)
            val gold = (k + if (side > 0) 1 else 0) % 2 == 0
            val col = if (gold) Gold else colors.bright
            val path = Path().apply {
                moveTo(hand.x, hand.y)
                cubicTo(p1.x, p1.y, p2.x, p2.y, p3.x, p3.y)
            }
            drawPath(path, col.copy(alpha = 0.09f), style = Stroke(1.5f * u, cap = StrokeCap.Round))
            drawPath(path, col.copy(alpha = 0.6f), style = Stroke((0.22f + 0.05f * k) * u, cap = StrokeCap.Round))

            // A branching timeline splitting off.
            if (k % 2 == 1) {
                val s0 = 0.45f + 0.05f * k
                val b0 = bez(hand, p1, p2, p3, s0)
                val bEnd = Offset(b0.x + side * (10f + k * 2f) * u, b0.y + (16f + sin(t + k) * 3f) * u)
                val branch = Path().apply {
                    moveTo(b0.x, b0.y)
                    quadraticTo(b0.x + side * 9f * u, b0.y + 2f * u, bEnd.x, bEnd.y)
                }
                drawPath(branch, col.copy(alpha = 0.5f), style = Stroke(0.3f * u, cap = StrokeCap.Round))
                drawCircle(col.copy(alpha = 0.8f), 0.5f * u, bEnd)
            }

            // Sparks flowing down the timeline.
            for (j in 0 until 3) {
                val s = ((t * (0.06f + k * 0.01f) + j / 3f + k * 0.17f) % 1f)
                val pt = bez(hand, p1, p2, p3, s)
                val a = sin(s * PI.toFloat())
                drawCircle(GoldLight.copy(alpha = 0.9f * a), (0.55f + 0.25f * a) * u, pt)
            }
        }
    }
}

// ---------------- lightning ----------------

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

private fun makeBolt(u: Float, rnd: Random, t: Float): Bolt {
    // Strike the sigil's rim, or now and then the horns.
    val target = if (rnd.nextFloat() < 0.25f) {
        Offset((50f + (if (rnd.nextBoolean()) -12.5f else 12.5f)) * u, 1.5f * u)
    } else {
        val ang = (PI + rnd.nextFloat() * PI).toFloat() // upper half of the ring
        Offset((50f + cos(ang) * 44f) * u, (90f + sin(ang) * 44f) * u)
    }
    val start = Offset((if (target.x < 50f * u) rnd.nextFloat() * 25f else 75f + rnd.nextFloat() * 25f) * u, -4f * u)
    val main = mutableListOf(start)
    displace(start, target, 6, 0.32f, rnd, main)
    val forks = mutableListOf<List<Offset>>()
    repeat(1 + rnd.nextInt(3)) {
        val from = main[main.size / 4 + rnd.nextInt(main.size / 2)]
        val dir = (target - start)
        val end = from + Offset(dir.x * (0.15f + rnd.nextFloat() * 0.25f) + (rnd.nextFloat() - 0.5f) * 30f * u, dir.y * (0.2f + rnd.nextFloat() * 0.2f))
        val f = mutableListOf(from)
        displace(from, end, 4, 0.4f, rnd, f)
        forks += f
    }
    return Bolt(main, forks, t, target)
}

private fun DrawScope.drawBolt(b: Bolt, t: Float, u: Float) {
    val age = t - b.born
    if (age > 0.9f) return
    val fade = (1f - age / 0.9f)
    val flicker = 0.7f + 0.3f * sin(age * 70f)
    val a = fade * flicker
    if (age < 0.15f) drawRect(BoltMid.copy(alpha = 0.16f * (1f - age / 0.15f)))
    fun line(pts: List<Offset>, scale: Float) {
        val path = Path().apply { pts.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
        drawPath(path, BoltGlow.copy(alpha = 0.35f * a), style = Stroke(4.5f * u * scale, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, BoltMid.copy(alpha = 0.85f * a), style = Stroke(1.7f * u * scale, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, BoltCore.copy(alpha = a), style = Stroke(0.7f * u * scale, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, GoldLight.copy(alpha = 0.35f * a), style = Stroke(0.15f * u * scale))
    }
    line(b.main, 1f)
    b.forks.forEach { line(it, 0.6f) }
    // Impact burst.
    drawCircle(BoltCore.copy(alpha = 0.8f * a), 1.6f * u * (1f + age * 3f), b.target)
    drawCircle(BoltGlow.copy(alpha = 0.35f * a), 6f * u * (0.5f + age * 2f), b.target, style = Stroke(0.5f * u))
}
