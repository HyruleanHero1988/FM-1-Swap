package com.fmswitch.fm1kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceNotFoundTest {
    @Test
    fun defaultCopyStillNamesTheFm1() {
        assertEquals(
            "No FM-1 found. Check the cable, and close any other app using it.",
            FM1UpdateError.DeviceNotFound().message,
        )
        assertEquals(
            "2 FM-1s answered. Connect only the one to update, and try again.",
            FM1UpdateError.MultipleDevices(2).message,
        )
    }

    @Test
    fun smkLabelNamesTheKeyboard() {
        assertEquals(
            "No SMK-37 found. Check the cable, and close any other app using it.",
            FM1UpdateError.DeviceNotFound("SMK-37").message,
        )
        val multi = FM1UpdateError.MultipleDevices(2, "SMK-37").message!!
        assertTrue(multi, multi.startsWith("2 SMK-37 keyboards answered"))
    }
}
