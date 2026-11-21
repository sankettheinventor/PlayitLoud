package dev.wave.player.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.wave.player.playback.AudioFx
import dev.wave.player.playback.Visual
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// ---------------------------------------------------------------- small helpers

@Composable
fun NeuIconButton(icon: ImageVector, label: String, onClick: () -> Unit, size: Int = 44, active: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, spring(dampingRatio = 0.5f), label = "btn")
    Box(
        Modifier.size(size.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .neu(CircleShape, 5.dp, pressed = pressed)
            .clickable(interaction, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = if (active) Neu.Amber else Neu.Text, modifier = Modifier.size((size * 0.44f).dp)) }
}

fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> "—"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / 1048576.0)
    else -> String.format("%.2f GB", bytes / 1073741824.0)
}

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

private fun DrawScope.shadowCircle(center: Offset, radius: Float, fill: Color, blur: Float, dx: Float, dy: Float, shadow: Color) {
    drawIntoCanvas { c ->
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = fill.toArgb() }
        p.setShadowLayer(blur, dx, dy, shadow.toArgb())
        c.nativeCanvas.drawCircle(center.x, center.y, radius, p)
    }
}

// ---------------------------------------------------------------- live audio frames

/** Smoothed spectrum that updates every frame, in sync with what you hear. */
class LiveSpectrum {
    val bands = FloatArray(AudioFx.BANDS)
    var level by mutableFloatStateOf(0f)
    var bass by mutableFloatStateOf(0f)
    var tick by mutableFloatStateOf(0f)
}

@Composable
fun rememberLiveSpectrum(active: Boolean): LiveSpectrum {
    val live = remember { LiveSpectrum() }
    val playing by rememberUpdatedState(active)
    LaunchedEffect(Unit) {
        val vis = Visual()
        var last = 0L
        while (true) {
            withFrameNanos { t ->
                val dt = if (last == 0L) 0.016f else ((t - last) / 1e9f).coerceIn(0f, 0.1f)
                last = t
                val ok = playing && AudioFx.snapshot(vis)
                val k = min(1f, dt * 14f)
                for (i in live.bands.indices) {
                    val target = if (ok) vis.bands[i] else 0f
                    // fast rise, slower fall: feels punchy
                    val rate = if (target > live.bands[i]) k else k * 0.45f
                    live.bands[i] += (target - live.bands[i]) * rate
                }
                live.level += ((if (ok) min(1f, vis.rms * 3.2f) else 0f) - live.level) * k
                live.bass += ((if (ok) vis.bass else 0f) - live.bass) * k
                live.tick += dt
            }
        }
    }
    return live
}

// ---------------------------------------------------------------- vinyl

/**
 * Spinning record. It speeds up and slows down like a real turntable, and when
 * [live] is given, soft organic rings around it breathe with the music's spectrum.
 */
@Composable
fun Vinyl(isPlaying: Boolean, seed: String, modifier: Modifier = Modifier, live: LiveSpectrum? = null) {
    var angle by remember { mutableFloatStateOf(0f) }
    val playing by rememberUpdatedState(isPlaying)
    LaunchedEffect(Unit) {
        var last = 0L
        var speed = 0f
        while (true) {
            withFrameNanos { t ->
                val dt = if (last == 0L) 0f else ((t - last) / 1e9f).coerceIn(0f, 0.1f)
                last = t
                speed += ((if (playing) 1f else 0f) - speed) * min(1f, dt * 1.8f)
                angle = (angle + speed * 66f * dt) % 360f
            }
        }
    }
    val hue = ((seed.hashCode() % 360) + 360) % 360
    val label = Color.hsv(hue.toFloat(), 0.45f, 0.30f)
    val labelLight = Color.hsv(hue.toFloat(), 0.35f, 0.55f)

    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2f * (if (live != null) 0.78f else 0.96f)

        if (live != null) {
            val t = live.tick
            // glow that swells with the bass
            drawCircle(
                Brush.radialGradient(
                    0f to Neu.Ember.copy(alpha = 0.10f + 0.30f * live.bass),
                    1f to Color.Transparent,
                    center = c, radius = r * 1.32f,
                ),
                radius = r * 1.32f, center = c,
            )
            val n = 120
            for (ring in 0 until 4) {
                val path = Path()
                for (j in 0..n) {
                    val th = j.toFloat() / n * 2f * PI.toFloat()
                    val pos = abs((j.toFloat() / n) * 2f - 1f) // mirror spectrum around the circle
                    val band = (pos * (AudioFx.BANDS - 1)).toInt()
                    val amp = live.bands[band]
                    val wobble = sin(th * (3 + ring) + t * (0.7f + ring * 0.35f) + ring) * 0.5f +
                        sin(th * (5 - ring) - t * 0.9f) * 0.5f
                    val rad = r * (1.035f + ring * 0.03f) + r * 0.14f * amp * (0.65f + 0.35f * wobble) + r * 0.012f * wobble
                    val p = Offset(c.x + cos(th) * rad, c.y + sin(th) * rad)
                    if (j == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                path.close()
                drawPath(
                    path,
                    color = (if (ring == 0) Neu.Amber else Neu.Text).copy(alpha = 0.34f - ring * 0.07f + live.level * 0.15f),
                    style = Stroke(width = (1.6f - ring * 0.25f).dp.toPx()),
                )
            }
        }

        shadowCircle(c, r, Color(0xFF0E0E10), r * 0.10f, r * 0.04f, r * 0.06f, Color.Black.copy(alpha = 0.8f))

        rotate(angle, c) {
            for (i in 0 until 30) {
                val gr = r * (0.40f + i * 0.019f)
                drawCircle(Color.White.copy(alpha = if (i % 5 == 0) 0.06f else 0.022f), gr, c, style = Stroke(1f))
            }
            // light sheen across the grooves
            drawCircle(
                Brush.sweepGradient(
                    0.00f to Color.Transparent, 0.10f to Color.White.copy(alpha = 0.07f), 0.20f to Color.Transparent,
                    0.50f to Color.Transparent, 0.60f to Color.White.copy(alpha = 0.05f), 0.70f to Color.Transparent,
                    1.00f to Color.Transparent, center = c,
                ),
                r, c,
            )
            // label + eclipse ring
            drawCircle(
                Brush.radialGradient(
                    0.00f to Color.Black, 0.72f to Color.Black, 0.84f to Neu.Amber.copy(alpha = 0.9f),
                    0.90f to Color.White.copy(alpha = 0.35f), 1.0f to Color.Transparent, center = c, radius = r * 0.40f,
                ),
                r * 0.40f, c,
            )
            drawCircle(Brush.radialGradient(listOf(labelLight, label), center = c, radius = r * 0.30f), r * 0.30f, c)
            drawCircle(Color.White.copy(alpha = 0.55f), r * 0.018f, Offset(c.x + r * 0.2f, c.y))
            drawCircle(Neu.Bg, r * 0.035f, c)
        }
    }
}

// ---------------------------------------------------------------- waveform seek bar

/** Bars fill in as you listen; played part glows ember. Tap or drag to seek. */
@Composable
fun WaveformSeek(levels: List<Float>, progress: Float, onSeek: (Float) -> Unit, modifier: Modifier = Modifier) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val seek by rememberUpdatedState(onSeek)
    val shown = drag ?: progress
    Canvas(
        modifier.height(56.dp)
            .pointerInput(Unit) { detectTapGestures { seek((it.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { drag = (it.x / size.width).coerceIn(0f, 1f) },
                    onDragEnd = { drag?.let(seek); drag = null },
                    onDragCancel = { drag = null },
                ) { change, _ ->
                    change.consume()
                    drag = (change.position.x / size.width).coerceIn(0f, 1f)
                }
            },
    ) {
        val n = levels.size.coerceAtLeast(1)
        val gap = 2.dp.toPx()
        val w = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
        val brush = Brush.horizontalGradient(listOf(Neu.Ember, Neu.Amber), 0f, size.width)
        for (i in 0 until n) {
            val lv = levels.getOrElse(i) { 0f }
            val h = if (lv <= 0.01f) 3.dp.toPx() else (lv * size.height).coerceAtLeast(3.dp.toPx())
            val played = (i + 0.5f) / n <= shown
            drawRoundRect(
                brush = if (played) brush else SolidColor(Neu.Faint),
                topLeft = Offset(i * (w + gap), (size.height - h) / 2),
                size = Size(w, h),
                cornerRadius = CornerRadius(w / 2),
            )
        }
        val x = shown * size.width
        drawLine(Neu.Text.copy(alpha = 0.8f), Offset(x, 4.dp.toPx()), Offset(x, size.height - 4.dp.toPx()), 2.dp.toPx(), StrokeCap.Round)
    }
}

// ---------------------------------------------------------------- ember play button

@Composable
fun EmberPlayButton(isPlaying: Boolean, onClick: () -> Unit, size: Dp = 84.dp, progress: Float? = null) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium), label = "play")
    val ring = if (progress != null) size * 0.22f else 0.dp
    Box(Modifier.size(size + ring), contentAlignment = Alignment.Center) {
        if (progress != null) {
            Canvas(Modifier.fillMaxSize()) {
                val sw = 3.dp.toPx()
                val inset = sw
                drawArc(Neu.Faint, 0f, 360f, false, Offset(inset, inset), Size(this.size.width - inset * 2, this.size.height - inset * 2), style = Stroke(sw))
                drawArc(
                    Brush.sweepGradient(listOf(Neu.Ember, Neu.Amber, Neu.Ember)), -90f, 360f * progress.coerceIn(0f, 1f), false,
                    Offset(inset, inset), Size(this.size.width - inset * 2, this.size.height - inset * 2), style = Stroke(sw, cap = StrokeCap.Round),
                )
            }
        }
        Box(
            Modifier.size(size)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .glow(Neu.Ember, size * 0.22f, CircleShape)
                .background(Brush.radialGradient(listOf(Color(0xFFFF7A3D), Neu.Ember, Neu.EmberDeep)), CircleShape)
                .clickable(interaction, indication = null, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(isPlaying, label = "icon") { playing ->
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (playing) "Pause" else "Play",
                    tint = Color.White,
                    modifier = Modifier.size(size * 0.42f),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- knob

/**
 * Big rotary knob with a glowing arc. Drag up/right to raise, down/left to lower.
 * Bipolar knobs light the arc from the top (0) towards the value.
 */
@Composable
fun Knob(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    bipolar: Boolean = false,
    glow: Float = 0f,
) {
    val v by rememberUpdatedState(value)
    val cb by rememberUpdatedState(onChange)
    val span = range.endInclusive - range.start
    Box(
        modifier.pointerInput(Unit) {
            detectDragGestures { change, d ->
                change.consume()
                cb((v + (d.x - d.y) / 500f * span).coerceIn(range.start, range.endInclusive))
            }
        },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val r = size.minDimension / 2f
            val track = r * 0.86f
            val sw = r * 0.07f
            val tl = Offset(c.x - track, c.y - track)
            val sz = Size(track * 2, track * 2)
            val frac = ((value - range.start) / span).coerceIn(0f, 1f)

            // dashed scale
            for (i in 0..24) {
                val a = Math.toRadians((135.0 + i * 270.0 / 24)).toFloat()
                val r1 = r * 0.97f
                val r2 = r * (if (i % 6 == 0) 0.91f else 0.94f)
                drawLine(Neu.Faint, Offset(c.x + cos(a) * r1, c.y + sin(a) * r1), Offset(c.x + cos(a) * r2, c.y + sin(a) * r2), 1.5f)
            }
            drawArc(Color(0xFF0C0C0E), 135f, 270f, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))

            val start = if (bipolar) 270f else 135f
            val sweep = if (bipolar) (frac - 0.5f) * 270f else frac * 270f
            if (abs(sweep) > 0.5f) {
                val glowAlpha = 0.18f + 0.25f * glow
                drawArc(Neu.Ember.copy(alpha = glowAlpha * 0.5f), start, sweep, false, tl, sz, style = Stroke(sw * 3.2f, cap = StrokeCap.Round))
                drawArc(Neu.Ember.copy(alpha = glowAlpha), start, sweep, false, tl, sz, style = Stroke(sw * 2f, cap = StrokeCap.Round))
                drawArc(Brush.sweepGradient(listOf(Neu.Amber, Neu.Ember, Neu.Amber), c), start, sweep, false, tl, sz, style = Stroke(sw, cap = StrokeCap.Round))
            }

            // knob body
            val body = r * 0.62f
            shadowCircle(c, body, Neu.Raised, body * 0.22f, body * 0.10f, body * 0.14f, Neu.DarkShadow)
            drawCircle(Brush.linearGradient(listOf(Color(0xFF2E2F34), Color(0xFF17181B)), Offset(c.x - body, c.y - body), Offset(c.x + body, c.y + body)), body, c)
            drawCircle(Color(0xFF0E0E10), body, c, style = Stroke(r * 0.02f))
            val cap = body * 0.72f
            drawCircle(Brush.linearGradient(listOf(Color(0xFF26272B), Color(0xFF1B1C1F)), Offset(c.x - cap, c.y - cap), Offset(c.x + cap, c.y + cap)), cap, c)

            // indicator
            val a = Math.toRadians((135.0 + frac * 270.0)).toFloat()
            val ir = body * 0.78f
            val ip = Offset(c.x + cos(a) * ir, c.y + sin(a) * ir)
            drawCircle(Neu.Amber.copy(alpha = 0.35f), r * 0.06f, ip)
            drawCircle(Neu.Amber, r * 0.03f, ip)
        }
    }
}

// ---------------------------------------------------------------- vertical slider

@Composable
fun VSlider(
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    top: String,
    bottom: String,
    label: String,
    valueText: String,
    modifier: Modifier = Modifier,
) {
    val cb by rememberUpdatedState(onChange)
    val span = range.endInclusive - range.start
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(top, color = Neu.Muted, fontSize = 11.sp)
        Box(
            Modifier.padding(vertical = 8.dp).width(64.dp).height(190.dp)
                .pointerInput(Unit) {
                    fun set(y: Float) {
                        val pad = 18.dp.toPx()
                        val f = 1f - ((y - pad) / (size.height - pad * 2)).coerceIn(0f, 1f)
                        cb(range.start + f * span)
                    }
                    detectTapGestures { set(it.y) }
                }
                .pointerInput(Unit) {
                    fun set(y: Float) {
                        val pad = 18.dp.toPx()
                        val f = 1f - ((y - pad) / (size.height - pad * 2)).coerceIn(0f, 1f)
                        cb(range.start + f * span)
                    }
                    detectVerticalDragGestures(onDragStart = { set(it.y) }) { change, _ -> change.consume(); set(change.position.y) }
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val pad = 18.dp.toPx()
                val cx = size.width / 2
                val gw = 8.dp.toPx()
                val frac = ((value - range.start) / span).coerceIn(0f, 1f)
                val ty = pad + (1f - frac) * (size.height - pad * 2)

                for (i in 0..10) {
                    val y = pad + i * (size.height - pad * 2) / 10
                    val l = if (i % 5 == 0) 10.dp.toPx() else 6.dp.toPx()
                    drawLine(Neu.Faint, Offset(cx - gw - 6.dp.toPx() - l, y), Offset(cx - gw - 6.dp.toPx(), y), 1.5f)
                    drawLine(Neu.Faint, Offset(cx + gw + 6.dp.toPx(), y), Offset(cx + gw + 6.dp.toPx() + l, y), 1.5f)
                }
                drawRoundRect(Color(0xFF0C0C0E), Offset(cx - gw / 2, pad), Size(gw, size.height - pad * 2), CornerRadius(gw / 2))
                val fillH = size.height - pad - ty
                if (fillH > 1f) {
                    drawRoundRect(Neu.Ember.copy(alpha = 0.25f), Offset(cx - gw, ty), Size(gw * 2, fillH), CornerRadius(gw))
                    drawRoundRect(Neu.EmberVertical, Offset(cx - gw / 4, ty), Size(gw / 2, fillH), CornerRadius(gw / 4))
                }
                val tw = 36.dp.toPx(); val th = 22.dp.toPx()
                drawIntoCanvas { cv ->
                    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Color(0xFF2A2B30).toArgb() }
                    p.setShadowLayer(8.dp.toPx(), 0f, 4.dp.toPx(), Neu.DarkShadow.toArgb())
                    cv.nativeCanvas.drawRoundRect(cx - tw / 2, ty - th / 2, cx + tw / 2, ty + th / 2, 6.dp.toPx(), 6.dp.toPx(), p)
                }
                drawRoundRect(
                    Brush.verticalGradient(listOf(Color(0xFF3A3B41), Color(0xFF232428)), ty - th / 2, ty + th / 2),
                    Offset(cx - tw / 2, ty - th / 2), Size(tw, th), CornerRadius(6.dp.toPx()),
                )
                for (k in -1..1) {
                    val y = ty + k * 4.dp.toPx()
                    drawLine(Color.Black.copy(alpha = 0.5f), Offset(cx - tw * 0.3f, y), Offset(cx + tw * 0.3f, y), 1.5f)
                }
            }
        }
        Text(bottom, color = Neu.Muted, fontSize = 11.sp)
        Text(label, color = Neu.Text, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 10.dp), letterSpacing = 1.sp)
        Text(valueText, color = Neu.Amber, fontSize = 12.sp)
    }
}

// ---------------------------------------------------------------- spectrum bars

@Composable
fun SpectrumBars(live: LiveSpectrum, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        @Suppress("UNUSED_VARIABLE") val tick = live.tick // redraw every frame
        val n = AudioFx.BANDS
        val gap = 3.dp.toPx()
        val w = (size.width - gap * (n - 1)) / n
        val brush = Brush.verticalGradient(listOf(Neu.Amber, Neu.Ember), 0f, size.height)
        for (i in 0 until n) {
            val h = (live.bands[i] * size.height).coerceAtLeast(2.dp.toPx())
            drawRoundRect(brush, Offset(i * (w + gap), size.height - h), Size(w, h), CornerRadius(w / 3))
        }
    }
}
