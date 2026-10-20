package com.fmswitch.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.fmswitch.emu.Fm1Panel
import com.fmswitch.emu.Fm1PanelSource
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val Room = Color(0xFF18191B)
private val BodyTop = Color(0xFF5E6166)
private val BodyBottom = Color(0xFF4A4D52)
private val Tray = Color(0xFF2B2C2F)
private val Silk = Color(0xFFC9C6BD)
private val ButtonTop = Color(0xFF4B4E54)
private val ButtonBottom = Color(0xFF3B3D42)
private val ButtonInk = Color(0xFF9C9A94)
private val KeyTop = Color(0xFF6A5557)
private val KeyBottom = Color(0xFF4F3F42)
private val KeyLine = Color(0xFF8F7C7E)
private val Amber = Color(0xFFF2A33A)
private val Green = Color(0xFF62D36E)

/** Panel units of drag per encoder detent. */
private const val DRAG_PER_CLICK = 14f

private sealed class Grab {
    data class Press(val id: Int) : Grab()
    data class Turn(val index: Int, var carry: Float) : Grab()
    data class Pot(val startValue: Int, val startY: Float) : Grab()
}

/**
 * The FM-1's front panel in Compose, drawn after fm1-emu's (crates/fm1-live/src/panel.rs):
 * the live screen, LEDs from the firmware, multi-touch keys and buttons, knobs turned by
 * dragging up (clockwise) or down.
 */
@Composable
fun Fm1PanelView(engine: Fm1PanelSource, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val lcd = remember { Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888) }
    val lcdImage = remember(lcd) { lcd.asImageBitmap() }
    val down = remember { mutableStateMapOf<Int, Boolean>() }
    val clicks = remember { mutableStateMapOf<Int, Int>() }
    var pot by remember { mutableLongStateOf(Fm1Panel.POT_START.toLong()) }
    var tick by remember { mutableLongStateOf(0L) }
    var shownRevision by remember { mutableLongStateOf(-1L) }

    LaunchedEffect(engine) {
        while (true) {
            withFrameNanos { now ->
                val frame = engine.frame.get()
                if (frame != null && frame.revision.toLong() != shownRevision) {
                    lcd.setPixels(frame.lcd, 0, 240, 0, 0, 240, 240)
                    shownRevision = frame.revision.toLong()
                }
                tick = now
            }
        }
    }

    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = if (maxHeight.value.isFinite()) with(density) { maxHeight.toPx() } else Float.MAX_VALUE
        val scale = minOf(widthPx / Fm1Panel.W, heightPx / Fm1Panel.H)
        val panelSize = with(density) { DpSize((Fm1Panel.W * scale).toDp(), (Fm1Panel.H * scale).toDp()) }

        Canvas(
            Modifier
                .size(panelSize)
                .pointerInput(engine, scale) {
                    val grabs = HashMap<PointerId, Grab>()
                    fun release(grab: Grab?) {
                        if (grab is Grab.Press) {
                            engine.key(grab.id, false)
                            down.remove(grab.id)
                        }
                    }
                    fun press(id: Int) {
                        engine.key(id, true)
                        down[id] = true
                    }
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            for (change in event.changes) {
                                val x = change.position.x / scale
                                val y = change.position.y / scale
                                val grab = grabs[change.id]
                                when {
                                    change.pressed && grab == null && !change.previousPressed -> {
                                        when (val hit = Fm1Panel.hit(x, y)) {
                                            is Fm1Panel.Hit.Press -> { press(hit.id); grabs[change.id] = Grab.Press(hit.id) }
                                            is Fm1Panel.Hit.Encoder -> grabs[change.id] = Grab.Turn(hit.index, 0f)
                                            Fm1Panel.Hit.Pot -> grabs[change.id] = Grab.Pot(pot.toInt(), y)
                                            null -> Unit
                                        }
                                        change.consume()
                                    }
                                    change.pressed && grab != null -> {
                                        val dx = (change.position.x - change.previousPosition.x) / scale
                                        val dy = (change.position.y - change.previousPosition.y) / scale
                                        when (grab) {
                                            is Grab.Press -> {
                                                // slide along the keyboard: the finger plays the key it is on
                                                val hit = Fm1Panel.hit(x, y, slop = 0f) as? Fm1Panel.Hit.Press
                                                if (grab.id >= 14 && hit != null && hit.id >= 14 && hit.id != grab.id) {
                                                    release(grab)
                                                    press(hit.id)
                                                    grabs[change.id] = Grab.Press(hit.id)
                                                }
                                            }
                                            is Grab.Turn -> {
                                                grab.carry += dx - dy
                                                val n = (grab.carry / DRAG_PER_CLICK).toInt()
                                                if (n != 0) {
                                                    grab.carry -= n * DRAG_PER_CLICK
                                                    engine.turn(Fm1Panel.encoders[grab.index].encoder, n)
                                                    clicks[grab.index] = (clicks[grab.index] ?: 0) + n
                                                }
                                            }
                                            is Grab.Pot -> {
                                                val v = (grab.startValue + (grab.startY - y) * 4f).roundToInt()
                                                    .coerceIn(0, Fm1Panel.POT_ADC_MAX)
                                                if (v.toLong() != pot) {
                                                    pot = v.toLong()
                                                    engine.pot(v)
                                                }
                                            }
                                        }
                                        change.consume()
                                    }
                                    !change.pressed && grab != null -> {
                                        release(grab)
                                        grabs.remove(change.id)
                                        change.consume()
                                    }
                                }
                            }
                        }
                    }
                },
        ) {
            tick
            val leds = engine.frame.get()?.leds
            withTransform({ scale(scale, scale, pivot = Offset.Zero) }) {
                drawBody()
                drawImage(
                    lcdImage,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(240, 240),
                    dstOffset = IntOffset(Fm1Panel.LCD_X.toInt(), Fm1Panel.LCD_Y.toInt()),
                    dstSize = IntSize(Fm1Panel.LCD_SIZE.toInt(), Fm1Panel.LCD_SIZE.toInt()),
                    filterQuality = FilterQuality.None,
                )
                val label = TextStyle(color = Silk, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                fun caption(text: String, cx: Float, top: Float, style: TextStyle) {
                    val layout = measurer.measure(text, style.copy(fontSize = style.fontSize / density.fontScale / density.density))
                    drawText(layout, topLeft = Offset(cx - layout.size.width / 2f, top))
                }
                caption("MASTER", Fm1Panel.pot.cx, Fm1Panel.pot.cy - Fm1Panel.pot.r - 30f, label)
                knob(Fm1Panel.pot.cx, Fm1Panel.pot.cy, Fm1Panel.pot.r, (-135f + 270f * pot / 1023f) * (PI / 180).toFloat())
                Fm1Panel.encoders.forEachIndexed { i, k ->
                    caption(k.label, k.cx, k.cy - k.r - 30f, label)
                    knob(k.cx, k.cy, k.r, (clicks[i] ?: 0) * 18f * (PI / 180).toFloat())
                }
                for (b in Fm1Panel.buttons) {
                    val pressed = down[b.id] == true
                    val led = bright(leds?.getOrNull(b.id) ?: 0f)
                    val tint = if (b.id == 12) Green else Amber
                    val off = if (pressed) 2f else 0f
                    drawRoundRect(Color(0xFF1C1D20), Offset(b.x + 1, b.y + 3), Size(b.w, b.h), CornerRadius(9f))
                    drawRoundRect(
                        Brush.verticalGradient(
                            listOf(lerp(ButtonTop, tint, led * 0.85f), lerp(ButtonBottom, tint, led * 0.7f)),
                            startY = b.y + off, endY = b.y + off + b.h,
                        ),
                        Offset(b.x, b.y + off), Size(b.w, b.h), CornerRadius(9f),
                    )
                    val ink = if (led > 0.5f) Color(0xFF2A1A03) else ButtonInk
                    val style = TextStyle(color = ink, fontSize = if (b.w > 60) 20.sp else 15.sp, fontWeight = FontWeight.Bold)
                    val layout = measurer.measure(b.label, style.copy(fontSize = style.fontSize / density.fontScale / density.density))
                    drawText(layout, topLeft = Offset(b.x + (b.w - layout.size.width) / 2, b.y + off + (b.h - layout.size.height) / 2))
                }
                for (k in Fm1Panel.keys) {
                    val pressed = down[k.id] == true
                    val led = bright(leds?.getOrNull(k.id) ?: 0f)
                    val off = if (pressed) 3f else 0f
                    val r = CornerRadius(k.w / 2)
                    drawRoundRect(Color(0xFF1A1A1C), Offset(k.x + 1, k.y + 4), Size(k.w, k.h), r)
                    val heat = if (pressed) 0.55f else led * 0.6f
                    drawRoundRect(
                        Brush.verticalGradient(listOf(lerp(KeyTop, Amber, heat), lerp(KeyBottom, Amber, heat * 0.8f)), startY = k.y + off, endY = k.y + off + k.h),
                        Offset(k.x, k.y + off), Size(k.w, k.h), r,
                    )
                    val line = lerp(KeyLine, Color(0xFFFFE2B0), if (pressed) 1f else led)
                    drawRoundRect(line, Offset(k.x + k.w / 2 - 2.5f, k.y + 18 + off), Size(5f, k.h - 36), CornerRadius(2.5f))
                }
            }
        }
    }
}

private fun bright(f: Float): Float = (f / 0.08f).coerceIn(0f, 1f)

private fun lerp(a: Color, b: Color, t: Float): Color = androidx.compose.ui.graphics.lerp(a, b, t.coerceIn(0f, 1f))

private fun DrawScope.drawBody() {
    drawRect(Room, size = Size(Fm1Panel.W, Fm1Panel.H))
    drawRoundRect(
        Brush.verticalGradient(listOf(BodyTop, BodyBottom), startY = 14f, endY = Fm1Panel.H - 14f),
        Offset(14f, 14f), Size(Fm1Panel.W - 28, Fm1Panel.H - 28), CornerRadius(30f),
    )
    drawRoundRect(Color(0xFF0D0D0F), Offset(352f, 24f), Size(516f, 516f), CornerRadius(22f))
    drawRoundRect(Tray, Offset(50f, 388f), Size(260f, 64f), CornerRadius(14f))
    drawRoundRect(Tray, Offset(896f, 222f), Size(360f, 160f), CornerRadius(16f))
    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xFF2F2F33), Color(0xFF27272A)), startY = 562f, endY = 798f),
        Offset(34f, 562f), Size(1212f, 236f), CornerRadius(26f),
    )
    drawRoundRect(Color(0xFF3C6B43), Offset(1134f, 302f), Size(112f, 72f), CornerRadius(10f), style = Stroke(2f))
}

private fun DrawScope.knob(cx: Float, cy: Float, r: Float, angle: Float) {
    drawCircle(Color(0xFF26272A), r + 2, Offset(cx + 2, cy + 4))
    drawCircle(
        Brush.verticalGradient(listOf(Color(0xFF50535A), Color(0xFF2E3034)), startY = cy - r, endY = cy + r),
        r, Offset(cx, cy),
    )
    drawCircle(
        Brush.linearGradient(listOf(Color(0xFF6D7178), Color(0xFF3A3D43)), start = Offset(cx - r, cy - r), end = Offset(cx + r, cy + r)),
        r * 0.72f, Offset(cx, cy),
    )
    val s = sin(angle)
    val c = cos(angle)
    drawLine(
        Color(0xFFEEEAE2),
        Offset(cx + s * r * 0.12f, cy - c * r * 0.12f),
        Offset(cx + s * r * 0.74f, cy - c * r * 0.74f),
        strokeWidth = 3.2f,
        cap = StrokeCap.Round,
    )
}
