package dev.wave.player.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Dark neumorphic palette with an ember accent. */
object Neu {
    val Bg = Color(0xFF1C1D20)
    val Raised = Color(0xFF212226)
    val Pressed = Color(0xFF17181A)
    val LightShadow = Color(0xFF2D2E33)
    val DarkShadow = Color(0xFF0A0A0B)
    val Text = Color(0xFFECE8E2)
    val Muted = Color(0xFF8A8890)
    val Faint = Color(0xFF3A3B40)
    val Ember = Color(0xFFFF4E1B)
    val Amber = Color(0xFFFFB129)
    val EmberDeep = Color(0xFFC9300A)

    /** Kept for older screens. */
    val Accent = Amber

    val EmberBrush = Brush.horizontalGradient(listOf(Ember, Amber))
    val EmberVertical = Brush.verticalGradient(listOf(Amber, Ember))
}

private fun outlinePath(shape: Shape, scope: androidx.compose.ui.graphics.drawscope.DrawScope): Path =
    Path().apply { addOutline(shape.createOutline(scope.size, scope.layoutDirection, scope)) }

/** Soft raised surface: light shadow up-left, dark shadow down-right (Android 9+). */
fun Modifier.neu(
    shape: Shape = RoundedCornerShape(24.dp),
    elevation: Dp = 8.dp,
    pressed: Boolean = false,
): Modifier = if (pressed) neuInset(shape) else this
    .drawBehind {
        val e = elevation.toPx()
        val path = outlinePath(shape, this).asAndroidPath()
        drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Neu.Raised.toArgb() }
            paint.setShadowLayer(e * 1.6f, -e * 0.6f, -e * 0.6f, Neu.LightShadow.toArgb())
            canvas.nativeCanvas.drawPath(path, paint)
            paint.setShadowLayer(e * 1.6f, e * 0.8f, e * 0.8f, Neu.DarkShadow.toArgb())
            canvas.nativeCanvas.drawPath(path, paint)
        }
    }
    .background(Neu.Raised, shape)

/** Pressed-in surface: dark inner shadow top-left, faint light inner edge bottom-right. */
fun Modifier.neuInset(shape: Shape = RoundedCornerShape(24.dp), depth: Dp = 6.dp): Modifier = this
    .background(Neu.Pressed, shape)
    .drawBehind {
        val d = depth.toPx()
        val path = outlinePath(shape, this)
        clipPath(path) {
            drawIntoCanvas { canvas ->
                val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = d * 2
                    color = Neu.Pressed.toArgb()
                }
                p.setShadowLayer(d, d * 0.7f, d * 0.7f, Neu.DarkShadow.toArgb())
                canvas.nativeCanvas.drawPath(path.asAndroidPath(), p)
                p.setShadowLayer(d, -d * 0.5f, -d * 0.5f, Neu.LightShadow.copy(alpha = 0.7f).toArgb())
                canvas.nativeCanvas.drawPath(path.asAndroidPath(), p)
            }
        }
    }

/** Coloured glow behind a shape (used for the ember buttons). */
fun Modifier.glow(color: Color, radius: Dp, shape: Shape, alpha: Float = 0.55f): Modifier = drawBehind {
    val path = outlinePath(shape, this).asAndroidPath()
    drawIntoCanvas { canvas ->
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = color.toArgb() }
        p.setShadowLayer(radius.toPx(), 0f, radius.toPx() * 0.25f, color.copy(alpha = alpha).toArgb())
        canvas.nativeCanvas.drawPath(path, p)
    }
}
