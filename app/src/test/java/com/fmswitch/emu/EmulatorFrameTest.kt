package com.fmswitch.emu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmulatorFrameTest {
    @Test
    fun sidewaysPhoneLeavesTheControlsASideMargin() {
        val fit = EmulatorFrame.fit(800f, 400f)
        val panelWidth = 400f * Fm1Panel.W / Fm1Panel.H
        assertEquals(panelWidth, fit.panelWidth, 0.01f)
        assertEquals(400f, fit.panelHeight, 0.01f)
        assertEquals((800f - panelWidth) / 2f, fit.side, 0.01f)
        assertEquals(0f, fit.bar, 0.01f)
        assertTrue(fit.side > 0f)
    }

    @Test
    fun tallSpaceLeavesATopBarInstead() {
        val fit = EmulatorFrame.fit(400f, 800f)
        assertEquals(400f, fit.panelWidth, 0.01f)
        assertEquals(0f, fit.side, 0.01f)
        assertTrue(fit.bar > 0f)
        assertEquals(Fm1Panel.W / Fm1Panel.H, fit.panelWidth / fit.panelHeight, 0.001f)
    }

    @Test
    fun controlsNeverCrossThePanel() {
        val spaces = listOf(
            800f to 400f,
            400f to 800f,
            1280f to 820f,
            2000f to 900f,
            900f to 2000f,
            100f to 100f,
            0f to 10f,
            10f to 0f,
        )
        for ((width, height) in spaces) {
            val fit = EmulatorFrame.fit(width, height)
            val panel = EmulatorFrame.panelRect(width, height, fit)
            val chrome = EmulatorFrame.chromeRect(width, height, fit)
            assertFalse(
                "controls cross the panel at ${width}x$height",
                chrome.intersects(panel),
            )
            assertTrue(chrome.right <= width + 0.01f)
            assertTrue(chrome.bottom <= height.coerceAtLeast(0f) + 0.01f)
        }
    }
}
