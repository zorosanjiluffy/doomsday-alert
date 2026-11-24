package com.doomsdaywatch.alert

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Palette for the sigil. Green while watching; red right after an alarm. */
data class SigilColors(val deep: Color, val mid: Color, val bright: Color, val glow: Color)

val GreenSigil = SigilColors(Color(0xFF0F6E56), Color(0xFF1D9E75), Color(0xFF5DCAA5), Color(0xFF9FE1CB))
val RedSigil = SigilColors(Color(0xFF791F1F), Color(0xFFA32D2D), Color(0xFFE24B4A), Color(0xFFF7C1C1))

/**
 * An original "Doom sigil": three counter-rotating rings of procedurally drawn runes, a turning
 * five-point star, a breathing core, and sparks of green energy drifting upward.
 *
 * @param speed      1 = calm; it's raised as release day approaches
 * @param pulseKey   changing this fires a shockwave ring (each successful watcher check)
 */
@Composable
fun DoomSigil(modifier: Modifier, colors: SigilColors, speed: Float, pulseKey: Any?) {
    var time by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(speed) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) time += (now - last) / 1_000_000_000f * speed
                last = now
            }
        }
    }
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(pulseKey) {
        if (pulseKey == null) return@LaunchedEffect
        pulse.snapTo(0f)
        pulse.animateTo(1f, tween(1600, easing = LinearEasing))
    }

    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = min(size.width, size.height) / 2 * 0.92f
        val t = time

        sparks(c, r, t, colors)

        // Outer ring: fine ticks.
        rotate(t * 6f, c) {
            drawCircle(colors.deep, r, c, style = Stroke(2f))
            for (i in 0 until 72) {
                val a = i / 72f * 2 * PI.toFloat()
                val len = if (i % 6 == 0) r * 0.07f else r * 0.03f
                drawLine(
                    colors.mid,
                    c + polar(a, r),
                    c + polar(a, r - len),
                    strokeWidth = if (i % 6 == 0) 3f else 1.5f,
                )
            }
        }

        // Rune ring (counter-rotating).
        val runeR = r * 0.80f
        rotate(-t * 10f, c) {
            drawCircle(colors.mid, runeR + r * 0.075f, c, style = Stroke(3f))
            drawCircle(colors.mid, runeR - r * 0.075f, c, style = Stroke(1.5f))
            val n = 18
            for (i in 0 until n) {
                val deg = i * 360f / n
                withTransform({ rotate(deg, c) }) {
                    rune(Offset(c.x, c.y - runeR), r * 0.11f, i * 7919 + 13, colors.bright)
                }
            }
        }

        // Inner rune ring, faster, smaller.
        val innerR = r * 0.58f
        rotate(t * 16f, c) {
            drawCircle(colors.deep, innerR, c, style = Stroke(2f))
            for (i in 0 until 12) {
                withTransform({ rotate(i * 30f, c) }) {
                    rune(Offset(c.x, c.y - innerR - r * 0.055f), r * 0.07f, i * 104729 + 7, colors.mid)
                }
            }
        }

        // Five-point star.
        rotate(-t * 22f, c) {
            val starR = r * 0.48f
            val p = Path()
            for (i in 0..5) {
                val a = (i * 2 * 2 * PI / 5 - PI / 2).toFloat()
                val pt = c + polar(a, starR)
                if (i == 0) p.moveTo(pt.x, pt.y) else p.lineTo(pt.x, pt.y)
            }
            drawPath(p, colors.bright, style = Stroke(4f, join = StrokeJoin.Round))
            for (i in 0 until 5) {
                val a = (i * 2 * PI / 5 - PI / 2).toFloat()
                drawCircle(colors.glow, 6f, c + polar(a, starR))
            }
        }

        // Breathing core.
        val breath = 0.5f + 0.5f * sin(t * 2.2f)
        drawCircle(colors.deep.copy(alpha = 0.35f), r * (0.24f + 0.03f * breath), c)
        drawCircle(colors.mid, r * (0.24f + 0.03f * breath), c, style = Stroke(3f))

        // Shockwave on each watcher check.
        val pv = pulse.value
        if (pv < 1f) {
            drawCircle(
                colors.glow.copy(alpha = (1f - pv) * 0.8f),
                r * (0.25f + pv * 0.85f),
                c,
                style = Stroke(6f * (1f - pv) + 1f),
            )
        }
    }
}

private fun polar(a: Float, d: Float) = Offset(cos(a) * d, sin(a) * d)

/** Draws one procedurally generated rune: a stem plus branches chosen from the seed's bits. */
private fun DrawScope.rune(center: Offset, h: Float, seed: Int, color: Color) {
    val top = Offset(center.x, center.y - h / 2)
    val bottom = Offset(center.x, center.y + h / 2)
    val w = h * 0.45f
    val s = Stroke(3f, cap = StrokeCap.Round)
    drawLine(color, top, bottom, strokeWidth = s.width, cap = StrokeCap.Round)
    val bits = (seed * 2654435761L).toInt() ushr 3
    val ys = floatArrayOf(-0.5f, -0.2f, 0.1f)
    for (k in 0 until 3) {
        val b = (bits ushr (k * 3)) and 7
        val y0 = center.y + ys[k] * h
        when (b) {
            0, 1 -> drawLine(color, Offset(center.x, y0), Offset(center.x + w, y0 + h * 0.25f), s.width, StrokeCap.Round)
            2, 3 -> drawLine(color, Offset(center.x, y0), Offset(center.x - w, y0 + h * 0.25f), s.width, StrokeCap.Round)
            4 -> {
                drawLine(color, Offset(center.x, y0), Offset(center.x + w, y0 + h * 0.2f), s.width, StrokeCap.Round)
                drawLine(color, Offset(center.x, y0), Offset(center.x - w, y0 + h * 0.2f), s.width, StrokeCap.Round)
            }
            5 -> drawCircle(color, h * 0.08f, Offset(center.x + w * 0.6f, y0 + h * 0.1f))
            else -> Unit
        }
    }
}

/** Green energy motes drifting up through the sigil. */
private fun DrawScope.sparks(c: Offset, r: Float, t: Float, colors: SigilColors) {
    for (i in 0 until 46) {
        val sp = 0.05f + (i * 37 % 23) / 23f * 0.09f
        val phase = (t * sp + i * 0.618f) % 1f
        val x = c.x + sin(phase * 5f + i) * r * (0.2f + (i % 7) / 7f * 0.9f)
        val y = c.y + r * 1.1f - phase * r * 2.3f
        val a = sin(phase * PI.toFloat())
        drawCircle(
            (if (i % 3 == 0) colors.glow else colors.mid).copy(alpha = max(0f, a) * 0.8f),
            2f + (i % 4),
            Offset(x, y),
        )
    }
}
