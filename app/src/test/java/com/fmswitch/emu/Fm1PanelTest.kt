package com.fmswitch.emu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Fm1PanelTest {
    @Test
    fun theMatrixHas14ButtonsAnd27Keys() {
        assertEquals((0..13).toList(), Fm1Panel.buttons.map { it.id }.sorted())
        assertEquals((14..40).toList(), Fm1Panel.keys.map { it.id }.sorted())
        assertEquals(16, Fm1Panel.keys.count { !it.black })
    }

    @Test
    fun hitsFindKnobsButtonsAndKeys() {
        assertEquals(Fm1Panel.Hit.Pot, Fm1Panel.hit(110f, 135f))
        assertEquals(Fm1Panel.Hit.Encoder(0), Fm1Panel.hit(250f, 135f))
        assertEquals(Fm1Panel.Hit.Press(12), Fm1Panel.hit(906f + 4 * 58 + 25, 232f + 76 + 30))
        // the first white key is F3 (id 14); the black key over its right edge is F#3 (id 15)
        assertEquals(Fm1Panel.Hit.Press(14), Fm1Panel.hit(70f, 760f))
        assertEquals(Fm1Panel.Hit.Press(15), Fm1Panel.hit(130f, 620f))
        assertNull(Fm1Panel.hit(640f, 300f))
    }

    @Test
    fun aFingerThatMissedCanSlideOntoAKey() {
        val gap = Fm1Panel.hit(61f + 62f, 760f)
        assertNull(gap)
        assertEquals(14, Fm1Panel.slideKey(null, Fm1Panel.hit(70f, 760f)))
        assertNull(Fm1Panel.slideKey(null, gap))
        assertNull(Fm1Panel.slideKey(null, Fm1Panel.Hit.Press(8)))
        assertNull(Fm1Panel.slideKey(null, Fm1Panel.Hit.Pot))
        assertEquals(16, Fm1Panel.slideKey(14, Fm1Panel.Hit.Press(16)))
        assertNull(Fm1Panel.slideKey(14, Fm1Panel.Hit.Press(14)))
        assertNull(Fm1Panel.slideKey(8, Fm1Panel.Hit.Press(16)))
    }
}
