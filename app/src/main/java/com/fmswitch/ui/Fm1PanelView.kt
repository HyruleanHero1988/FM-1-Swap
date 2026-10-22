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
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.fmswitch.emu.Afterglow
import com.fmswitch.emu.Fm1Panel
import com.fmswitch.emu.Fm1PanelSource
import com.fmswitch.theme.LocalPhosphor
import com.fmswitch.theme.PixelFont
import com.fmswitch.theme.mix
import kotlin.math.PI
import kotlin.math.abs
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
private const val CLICK_DEGREES = 18f
private const val DEG = (PI / 180).toFloat()
private const val MATRIX_IDS = 41

private fun potAngle(value: Int): Float = (-135f + 270f * value / Fm1Panel.POT_ADC_MAX) * DEG

private sealed class Grab {
    data class Press(val id: Int) : Grab()
    data class Turn(val index: Int, var carry: Float) : Grab()
    data class Pot(val startValue: Int, val startY: Float) : Grab()
}

/**
 * The FM-1's front panel in Compose, drawn after fm1-emu's (crates/fm1-live/src/panel.rs):
 * the live screen, LEDs from the firmware, multi-touch keys and buttons, knobs turned by
 * dragging up (clockwise) or down.
 *
 * With [phosphor] set, everything except the screen is traced as glowing vector lines in
 * that colour. The screen is the firmware's own pixels in both styles and nothing is drawn
 * over it.
 */
@Composable
fun Fm1PanelView(
    engine: Fm1PanelSource,
    modifier: Modifier = Modifier,
    phosphor: Color? = null,
    glow: Float = 0.25f,
) {
    val measurer = rememberTextMeasurer()
    val lcd = remember { Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888) }
    val lcdImage = remember(lcd) { lcd.asImageBitmap() }
    val down = remember { mutableStateMapOf<Int, Boolean>() }
    val clicks = remember { mutableStateMapOf<Int, Int>() }
    var pot by remember { mutableLongStateOf(Fm1Panel.POT_START.toLong()) }
    var tick by remember { mutableLongStateOf(0L) }
    var shownRevision by remember { mutableLongStateOf(-1L) }
    val heat = remember { FloatArray(MATRIX_IDS) }
    val sweep = remember { FloatArray(Fm1Panel.encoders.size + 1) }
    val beam = remember(phosphor, glow) { phosphor?.let { Beam(it, glow) } }
    val page = LocalPhosphor.current.background

    fun knobAngle(i: Int): Float =
        if (i == Fm1Panel.encoders.size) potAngle(pot.toInt()) else (clicks[i] ?: 0) * CLICK_DEGREES * DEG

    LaunchedEffect(engine) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                val frame = engine.frame.get()
                if (frame != null && frame.revision.toLong() != shownRevision) {
                    lcd.setPixels(frame.lcd, 0, 240, 0, 0, 240, 240)
                    shownRevision = frame.revision.toLong()
                }
                val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.25f)
                for (id in heat.indices) {
                    val target = maxOf(if (down[id] == true) 1f else 0f, bright(frame?.leds?.getOrNull(id) ?: 0f))
                    heat[id] = Afterglow.glow(heat[id], target, dt)
                }
                for (i in sweep.indices) {
                    sweep[i] = if (last == 0L) knobAngle(i) else Afterglow.follow(sweep[i], knobAngle(i), dt)
                }
                last = now
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
                    try {
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
                                    change.pressed && grab == null -> {
                                        // The finger came down off a key. Sliding onto one plays it.
                                        val id = Fm1Panel.slideKey(null, Fm1Panel.hit(x, y, slop = 0f))
                                        if (id != null) {
                                            press(id)
                                            grabs[change.id] = Grab.Press(id)
                                        }
                                        change.consume()
                                    }
                                    change.pressed && grab != null -> {
                                        val dx = (change.position.x - change.previousPosition.x) / scale
                                        val dy = (change.position.y - change.previousPosition.y) / scale
                                        when (grab) {
                                            is Grab.Press -> {
                                                val id = Fm1Panel.slideKey(grab.id, Fm1Panel.hit(x, y, slop = 0f))
                                                if (id != null) {
                                                    release(grab)
                                                    press(id)
                                                    grabs[change.id] = Grab.Press(id)
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
                    } finally {
                        // A new panel size (rotation) restarts this block. Release whatever is still held
                        // so a key does not stay down in the firmware after the finger's stream ends.
                        grabs.values.forEach(::release)
                        grabs.clear()
                    }
                },
        ) {
            tick
            val leds = engine.frame.get()?.leds
            fun DrawScope.drawLcd() = drawImage(
                lcdImage,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(240, 240),
                dstOffset = IntOffset(Fm1Panel.LCD_X.toInt(), Fm1Panel.LCD_Y.toInt()),
                dstSize = IntSize(Fm1Panel.LCD_SIZE.toInt(), Fm1Panel.LCD_SIZE.toInt()),
                filterQuality = FilterQuality.None,
            )
            if (beam != null) {
                withTransform({ scale(scale, scale, pivot = Offset.Zero) }) {
                    drawPhosphorPanel(beam, page, measurer, density.fontScale * density.density, heat, sweep, ::knobAngle)
                    drawLcd()
                }
                return@Canvas
            }
            withTransform({ scale(scale, scale, pivot = Offset.Zero) }) {
                drawBody()
                drawLcd()
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

/** Halo passes under each beam line: width in panel units to opacity. */
private val Halo = listOf(16f to 0.045f, 9f to 0.09f, 4.5f to 0.2f)
private const val CORE = 1.8f

private class Beam(val colour: Color, glow: Float) {
    val halo = 0.6f + glow.coerceIn(0f, 1f) * 1.6f
    val hot = colour.mix(Color.White, 0.45f)
}

/** One traced line: soft halo passes added under a hot core, as a scope's phosphor blooms. */
private inline fun DrawScope.beam(b: Beam, level: Float, stroke: DrawScope.(Color, Float) -> Unit) {
    val l = level.coerceIn(0f, 1f)
    for ((width, alpha) in Halo) stroke(b.colour.copy(alpha = (alpha * b.halo * l).coerceIn(0f, 1f)), width)
    stroke(b.hot.copy(alpha = l), CORE)
}

private fun DrawScope.beamRect(b: Beam, level: Float, x: Float, y: Float, w: Float, h: Float, r: Float) =
    beam(b, level) { c, width ->
        drawRoundRect(c, Offset(x, y), Size(w, h), CornerRadius(r), style = Stroke(width), blendMode = BlendMode.Plus)
    }

private fun DrawScope.beamLine(b: Beam, level: Float, from: Offset, to: Offset) =
    beam(b, level) { c, width ->
        drawLine(c, from, to, strokeWidth = width, cap = StrokeCap.Round, blendMode = BlendMode.Plus)
    }

private fun DrawScope.beamCircle(b: Beam, level: Float, cx: Float, cy: Float, r: Float) =
    beam(b, level) { c, width ->
        drawCircle(c, r, Offset(cx, cy), style = Stroke(width), blendMode = BlendMode.Plus)
    }

private fun DrawScope.phosphorFill(b: Beam, heat: Float, x: Float, y: Float, w: Float, h: Float, r: Float) {
    if (heat < 0.01f) return
    drawRoundRect(b.colour.copy(alpha = 0.3f * heat), Offset(x, y), Size(w, h), CornerRadius(r), blendMode = BlendMode.Plus)
}

private fun DrawScope.drawPhosphorPanel(
    b: Beam,
    field: Color,
    measurer: TextMeasurer,
    textScale: Float,
    heat: FloatArray,
    sweep: FloatArray,
    angle: (Int) -> Float,
) {
    fun label(text: String, cx: Float, cy: Float, size: Float, level: Float) {
        val style = TextStyle(
            fontFamily = PixelFont,
            fontSize = (size / textScale).sp,
            color = b.hot.copy(alpha = level.coerceIn(0f, 1f)),
            shadow = Shadow(b.colour.copy(alpha = (0.55f * b.halo * level).coerceIn(0f, 1f)), Offset.Zero, blurRadius = 9f),
        )
        val layout = measurer.measure(text, style)
        drawText(layout, topLeft = Offset(cx - layout.size.width / 2f, cy - layout.size.height / 2f))
    }

    fun knob(index: Int, k: Fm1Panel.Knob, ticks: Boolean) {
        val now = angle(index)
        val trail = sweep[index]
        beamCircle(b, 0.75f, k.cx, k.cy, k.r)
        beamCircle(b, 0.3f, k.cx, k.cy, k.r * 0.72f)
        if (ticks) {
            for (i in 0..10) {
                val a = (-135f + 27f * i) * DEG
                beamLine(
                    b, 0.4f,
                    Offset(k.cx + sin(a) * (k.r + 8f), k.cy - cos(a) * (k.r + 8f)),
                    Offset(k.cx + sin(a) * (k.r + 16f), k.cy - cos(a) * (k.r + 16f)),
                )
            }
        }
        val behind = now - trail
        if (abs(behind) > 0.01f) {
            val r = k.r * 0.8f
            drawArc(
                b.colour.copy(alpha = (0.22f * b.halo).coerceIn(0f, 1f)),
                startAngle = minOf(now, trail) / DEG - 90f,
                sweepAngle = abs(behind) / DEG,
                useCenter = true,
                topLeft = Offset(k.cx - r, k.cy - r),
                size = Size(r * 2, r * 2),
                blendMode = BlendMode.Plus,
            )
        }
        beamLine(
            b, 1f,
            Offset(k.cx + sin(now) * k.r * 0.12f, k.cy - cos(now) * k.r * 0.12f),
            Offset(k.cx + sin(now) * k.r * 0.86f, k.cy - cos(now) * k.r * 0.86f),
        )
        label(k.label, k.cx, k.cy - k.r - 24f, 18f, 0.8f)
    }

    drawRect(field, size = Size(Fm1Panel.W, Fm1Panel.H))
    beamRect(b, 0.55f, 14f, 14f, Fm1Panel.W - 28, Fm1Panel.H - 28, 30f)
    beamRect(b, 0.7f, 352f, 24f, 516f, 516f, 22f)
    beamRect(b, 0.35f, 50f, 388f, 260f, 64f, 14f)
    beamRect(b, 0.35f, 896f, 222f, 360f, 160f, 16f)
    beamRect(b, 0.35f, 34f, 562f, 1212f, 236f, 26f)
    beamRect(b, 0.3f, 1134f, 302f, 112f, 72f, 10f)

    knob(Fm1Panel.encoders.size, Fm1Panel.pot, ticks = true)
    Fm1Panel.encoders.forEachIndexed { i, k -> knob(i, k, ticks = false) }

    for (btn in Fm1Panel.buttons) {
        val h = heat[btn.id]
        phosphorFill(b, h, btn.x, btn.y, btn.w, btn.h, 9f)
        beamRect(b, 0.55f + 0.45f * h, btn.x, btn.y, btn.w, btn.h, 9f)
        label(btn.label, btn.x + btn.w / 2, btn.y + btn.h / 2, if (btn.w > 60) 18f else 13f, 0.7f + 0.3f * h)
    }
    for (key in Fm1Panel.keys) {
        val h = heat[key.id]
        val r = key.w / 2
        phosphorFill(b, h, key.x, key.y, key.w, key.h, r)
        beamRect(b, (if (key.black) 0.5f else 0.6f) + 0.4f * h, key.x, key.y, key.w, key.h, r)
        beamLine(b, 0.25f + 0.75f * h, Offset(key.x + r, key.y + 20f), Offset(key.x + r, key.y + key.h - 20f))
    }
}
