package com.fmswitch.model

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.fmswitch.theme.PhosphorPreset
import com.fmswitch.theme.PhosphorTheme
import com.fmswitch.theme.mix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceTest {
    @Test
    fun unknownIdStaysOnTheShippedDarkLook() {
        assertEquals(Appearance.DARK, Appearance.fromId(null))
        assertEquals(Appearance.DARK, Appearance.fromId(""))
        assertEquals(Appearance.DARK, Appearance.fromId("crt"))
        assertEquals(Appearance.LIGHT, Appearance.fromId("light"))
        assertEquals(Appearance.SYSTEM, Appearance.fromId("System"))
    }

    @Test
    fun resolvesAgainstThePhoneWhenAsked() {
        assertFalse(Appearance.LIGHT.resolvesDark(systemDark = true))
        assertFalse(Appearance.LIGHT.resolvesDark(systemDark = false))
        assertTrue(Appearance.DARK.resolvesDark(systemDark = false))
        assertTrue(Appearance.DARK.resolvesDark(systemDark = true))
        assertTrue(Appearance.SYSTEM.resolvesDark(systemDark = true))
        assertFalse(Appearance.SYSTEM.resolvesDark(systemDark = false))
    }

    @Test
    fun lightThemeKeepsInkDarkerThanThePaper() {
        val dark = PhosphorTheme(PhosphorPreset.Green.colour, 0.25f, scanlines = false, flicker = false, light = false)
        val light = PhosphorTheme(PhosphorPreset.Green.colour, 0.25f, scanlines = false, flicker = false, light = true)
        assertTrue(dark.background.luminance() < dark.ink.luminance())
        assertTrue(light.ink.luminance() < light.background.luminance())
        assertTrue(light.bright.luminance() < PhosphorPreset.Green.colour.luminance())
        assertEquals(Color(0xFF0C0E0D).red, dark.background.red, 0.05f)
    }

    @Test
    fun lightPanelTraceStandsOutOnThePaperBackground() {
        val light = PhosphorTheme(PhosphorPreset.Green.colour, 0.25f, scanlines = false, flicker = false, light = true)
        val dark = PhosphorTheme(PhosphorPreset.Green.colour, 0.25f, scanlines = false, flicker = false, light = false)
        assertTrue(light.panelTrace.luminance() + 0.08f < light.background.luminance())
        assertTrue(light.panelCore.luminance() + 0.15f < light.background.luminance())
        assertEquals(dark.base, dark.panelTrace)
        assertEquals(dark.base.mix(Color.White, 0.45f).red, dark.panelCore.red, 0.02f)
    }
}
