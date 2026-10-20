package com.fmswitch.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.fmswitch.R

data class PhosphorTheme(
    val base: Color,
    val glow: Float,
    val scanlines: Boolean,
    val flicker: Boolean,
) {
    val bright: Color get() = base
    val dim: Color get() = base.copy(alpha = 0.5f)
    val faint: Color get() = base.copy(alpha = 0.2f)
    val ink: Color get() = Color(0xFFECE8DF).mix(base, 0.08f)
    val muted: Color get() = Color(0xFF8D918B).mix(base, 0.10f)
    val background: Color get() = Color(0xFF0C0E0D).mix(base, 0.03f)
    val surface: Color get() = Color(0xFF151917).mix(base, 0.05f)
    val shadow: Color get() = Color.Black.copy(alpha = 0.55f)

    companion object {
        val Standard = PhosphorTheme(PhosphorPreset.Green.colour, 0.25f, scanlines = false, flicker = false)
    }
}

enum class PhosphorPreset(val id: String, val label: String, val hex: String) {
    Green("green", "GREEN", "#7BE39A"),
    Amber("amber", "AMBER", "#FFB547"),
    Ice("ice", "ICE", "#8FD3EE"),
    Rose("rose", "ROSE", "#FF8DB5"),
    Paper("paper", "PAPER", "#E4E0D3");

    val colour: Color get() = Color(hex) ?: Color(0xFF7BE39A)

    companion object {
        fun fromHex(hex: String): PhosphorPreset? = entries.firstOrNull { it.hex.equals(hex, ignoreCase = true) }
    }
}

val LocalPhosphor = staticCompositionLocalOf { PhosphorTheme.Standard }

val PixelFont = FontFamily(
    Font(R.font.silkscreen_regular, FontWeight.Normal),
    Font(R.font.silkscreen_bold, FontWeight.Bold),
)
val MonoFont = FontFamily(Font(R.font.departuremono_regular, FontWeight.Normal))
val LcdFont = FontFamily(Font(R.font.vt323_regular, FontWeight.Normal))

operator fun Color.Companion.invoke(hex: String): Color? {
    val digits = hex.trim().removePrefix("#")
    if (digits.length != 6) return null
    val value = digits.toLongOrNull(16) ?: return null
    return Color(
        red = ((value shr 16) and 0xFF) / 255f,
        green = ((value shr 8) and 0xFF) / 255f,
        blue = (value and 0xFF) / 255f,
    )
}

fun Color.mix(other: Color, amount: Float): Color = Color(
    red = red + (other.red - red) * amount,
    green = green + (other.green - green) * amount,
    blue = blue + (other.blue - blue) * amount,
    alpha = 1f,
)

fun Color.toHex(): String {
    fun c(v: Float) = (v.coerceIn(0f, 1f) * 255).toInt()
    return "#%02X%02X%02X".format(c(red), c(green), c(blue))
}

@Composable
fun pixelText(size: TextUnit, bold: Boolean = false, color: Color, glow: Boolean = false): TextStyle {
    val theme = LocalPhosphor.current
    return TextStyle(
        fontFamily = PixelFont,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        fontSize = size,
        color = color,
        shadow = if (glow && theme.glow > 0.01f) {
            Shadow(color = theme.base.copy(alpha = 0.7f * theme.glow), offset = Offset.Zero, blurRadius = 8f)
        } else {
            null
        },
    )
}

@Composable
fun monoText(size: TextUnit, color: Color): TextStyle = TextStyle(
    fontFamily = MonoFont,
    fontSize = size,
    color = color,
    lineHeight = size * 1.35f,
)

@Composable
fun lcdText(size: TextUnit, color: Color): TextStyle {
    val theme = LocalPhosphor.current
    return TextStyle(
        fontFamily = LcdFont,
        fontSize = size,
        color = color,
        shadow = if (theme.glow > 0.01f) {
            Shadow(color = theme.base.copy(alpha = 0.7f * theme.glow), offset = Offset.Zero, blurRadius = 10f)
        } else {
            null
        },
    )
}

fun formattedBytes(count: Int): String = when {
    count >= 1_000_000 -> "%.1f MB".format(count / 1_000_000.0)
    count >= 1_000 -> "%d KB".format((count + 500) / 1_000)
    else -> "$count B"
}
