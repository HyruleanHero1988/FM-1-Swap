package com.fmswitch.theme

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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

class PixelBox(private val step: Dp = 3.dp) : Shape {
    override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val notch = with(density) { step.toPx() }.coerceAtMost(min(size.width, size.height) / 2f)
        val path = Path().apply {
            moveTo(notch, 0f)
            lineTo(size.width - notch, 0f)
            lineTo(size.width - notch, notch)
            lineTo(size.width, notch)
            lineTo(size.width, size.height - notch)
            lineTo(size.width - notch, size.height - notch)
            lineTo(size.width - notch, size.height)
            lineTo(notch, size.height)
            lineTo(notch, size.height - notch)
            lineTo(0f, size.height - notch)
            lineTo(0f, notch)
            lineTo(notch, notch)
            close()
        }
        return Outline.Generic(path)
    }
}

@Composable
fun PixelPanel(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val theme = LocalPhosphor.current
    Box(modifier.padding(bottom = 4.dp, end = 4.dp)) {
        Box(
            Modifier
                .matchParentSize()
                .offset(4.dp, 4.dp)
                .background(theme.shadow, PixelBox()),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .background(theme.surface, PixelBox())
                .border(2.dp, theme.dim, PixelBox())
                .padding(horizontal = 14.dp)
                .padding(top = if (title == null) 14.dp else 22.dp, bottom = 14.dp),
        ) {
            content()
        }
        if (title != null) {
            BasicText(
                text = title,
                style = pixelText(10.sp, bold = true, color = theme.background),
                modifier = Modifier
                    .offset(12.dp, (-8).dp)
                    .background(theme.bright)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
fun PixelButton(
    label: String,
    modifier: Modifier = Modifier,
    prominent: Boolean = false,
    compact: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val theme = LocalPhosphor.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val edge = if (enabled) theme.bright else theme.faint
    val depth = if (compact) 2.dp else 3.dp
    Box(
        modifier
            .padding(bottom = depth, end = depth)
            .clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        if (enabled) {
            Box(
                Modifier
                    .matchParentSize()
                    .offset(depth, depth)
                    .background(theme.shadow, PixelBox(2.dp)),
            )
        }
        BasicText(
            text = label.uppercase(),
            style = pixelText(
                if (compact) 10.sp else 13.sp,
                bold = true,
                color = if (prominent) theme.background else edge,
            ),
            maxLines = 1,
            modifier = Modifier
                .offset(if (pressed) depth else 0.dp, if (pressed) depth else 0.dp)
                .then(if (!compact) Modifier.fillMaxWidth() else Modifier)
                .background(if (prominent) edge else theme.surface, PixelBox(2.dp))
                .border(2.dp, edge, PixelBox(2.dp))
                .padding(horizontal = if (compact) 8.dp else 14.dp, vertical = if (compact) 6.dp else 11.dp),
        )
    }
}

@Composable
fun PixelProgressBar(fraction: Float?, segments: Int = 20, modifier: Modifier = Modifier) {
    val theme = LocalPhosphor.current
    val transition = rememberInfiniteTransition(label = "progress")
    val tick by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(120, easing = LinearEasing), RepeatMode.Restart),
        label = "tick",
    )
    val lit: IntRange = if (fraction != null) {
        0 until (fraction.coerceIn(0f, 1f) * segments).toInt().coerceAtMost(segments)
    } else {
        val span = (segments - 3) * 2
        val step = ((System.currentTimeMillis() / 120) % span.coerceAtLeast(1)).toInt()
        val start = if (step < segments - 3) step else span - step
        start until (start + 3)
    }
    Row(
        modifier
            .height(24.dp)
            .border(2.dp, theme.dim, PixelBox(2.dp))
            .padding(4.dp),
    ) {
        repeat(segments) { index ->
            Box(
                Modifier
                    .weight(1f)
                    .height(16.dp)
                    .background(if (index in lit) theme.bright else theme.faint),
            )
            if (index < segments - 1) Spacer(Modifier.width(3.dp))
        }
    }
    @Suppress("UNUSED_EXPRESSION")
    tick
}

@Composable
fun PixelTag(text: String, inverted: Boolean = true) {
    val theme = LocalPhosphor.current
    BasicText(
        text = text.uppercase(),
        style = pixelText(9.sp, bold = true, color = if (inverted) theme.background else theme.muted),
        modifier = Modifier
            .background(if (inverted) theme.bright else Color.Transparent)
            .then(if (inverted) Modifier else Modifier.border(1.dp, theme.muted))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

@Composable
fun BlinkingCursor() {
    val theme = LocalPhosphor.current
    val transition = rememberInfiniteTransition(label = "cursor")
    val on by transition.animateFloat(
        0f,
        1f,
        infiniteRepeatable(tween(500, easing = LinearEasing), RepeatMode.Reverse),
        label = "blink",
    )
    Box(
        Modifier
            .size(9.dp, 16.dp)
            .background(theme.bright.copy(alpha = if (on > 0.5f) 1f else 0f)),
    )
}

@Composable
fun MonoText(text: String, size: Int = 15, dim: Boolean = false, modifier: Modifier = Modifier) {
    val theme = LocalPhosphor.current
    BasicText(
        text = text,
        style = monoText(size.sp, if (dim) theme.muted else theme.ink),
        modifier = modifier,
    )
}

@Composable
fun NoteText(text: String, modifier: Modifier = Modifier) = MonoText(text, size = 13, dim = true, modifier = modifier)

@Composable
fun ScreenHeader(title: String, trailing: @Composable RowScope.() -> Unit = {}) {
    val theme = LocalPhosphor.current
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(title, style = pixelText(20.sp, bold = true, color = theme.bright, glow = true))
            Spacer(Modifier.weight(1f))
            trailing()
        }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .drawBehind {
                    val dash = 4.dp.toPx()
                    var x = 0f
                    while (x < size.width) {
                        drawRect(theme.dim, topLeft = androidx.compose.ui.geometry.Offset(x, 0f), size = androidx.compose.ui.geometry.Size(dash, size.height))
                        x += dash * 2
                    }
                },
        )
    }
}

@Composable
fun PixelToggle(title: String, isOn: Boolean, onToggle: (Boolean) -> Unit) {
    val theme = LocalPhosphor.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onToggle(!isOn) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MonoText(title, size = 14)
        Spacer(Modifier.weight(1f))
        BasicText(if (isOn) "[■]" else "[ ]", style = monoText(17.sp, theme.bright))
    }
}

@Composable
fun CRTOverlay() {
    val theme = LocalPhosphor.current
    if (!theme.scanlines && !theme.flicker) return
    val transition = rememberInfiniteTransition(label = "crt")
    val t by transition.animateFloat(
        0f,
        1f,
        infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart),
        label = "band",
    )
    Canvas(Modifier.fillMaxSize()) {
        if (theme.scanlines) {
            var y = 0f
            while (y < size.height) {
                drawRect(Color.Black.copy(alpha = 0.12f), topLeft = androidx.compose.ui.geometry.Offset(0f, 0f), size = androidx.compose.ui.geometry.Size(size.width, 1f))
                y += 3f
            }
        }
        if (theme.flicker) {
            val bandHeight = size.height * 0.14f
            val position = t * (size.height + bandHeight) - bandHeight
            drawRect(Color.Black.copy(alpha = 0.01f + 0.01f * sin(t * 53f) * sin(t * 7.3f)))
            translate(top = position) {
                drawRect(
                    brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(Color.Transparent, theme.base.copy(alpha = 0.025f), Color.Transparent),
                    ),
                    size = androidx.compose.ui.geometry.Size(size.width, bandHeight),
                )
            }
        }
    }
}
