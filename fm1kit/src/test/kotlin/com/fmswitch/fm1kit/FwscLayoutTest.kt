package com.fmswitch.fm1kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FwscLayoutTest {
    @Test
    fun fm1BundledV15IsFm1Layout() {
        val bytes = javaClass.classLoader!!.getResourceAsStream("FM-1_015.fwsc")!!.readBytes()
        val image = FwscLayout.resolveLogicalImage(bytes).image
        assertEquals(FwscLayout.PackageBoard.FM1, FwscLayout.packageBoard(image))
        assertEquals("FM-1_015", FwscLayout.decodeProduct(bytes))
    }

    @Test
    fun smkIdentityIsRecognised() {
        assertTrue(DeviceFamily.isSmkFamily("SMK-37 Pro"))
        assertTrue(DeviceFamily.isSmkFamily("SMK-37 Elite"))
        assertTrue(DeviceFamily.sameKeyboard("SMK-37 Pro", "STARRYKEY-37 PLAY"))
        assertTrue(DeviceFamily.isOtherSmkFirmware(FM1Identity.parse("SMK-37_931")!!))
    }

    @Test
    fun boardRefusalBlocksFm1PackageOnSmk() {
        val bytes = javaClass.classLoader!!.getResourceAsStream("FM-1_015.fwsc")!!.readBytes()
        val image = FwscLayout.resolveLogicalImage(bytes).image
        val error = FwscLayout.boardRefusal("SMK-37 Pro", image)
        assertNotNull(error)
        assertTrue(error is FM1UpdateError.WrongPackageBoard)
    }

    @Test
    fun bundledFm1AllowedOnFm1() {
        val bytes = javaClass.classLoader!!.getResourceAsStream("FM-1_015.fwsc")!!.readBytes()
        val image = FwscLayout.resolveLogicalImage(bytes).image
        assertNull(FwscLayout.boardRefusal("FM-1", image))
    }

    @Test
    fun sloopProUsesSmkLayout() {
        val bytes = javaClass.classLoader!!.getResourceAsStream("sloop-pro-2.8.4.fwsc")!!.readBytes()
        val resolved = FwscLayout.resolveLogicalImage(bytes)
        assertTrue(resolved.blocks >= FwscPackage.BLOCK_COUNT)
        assertEquals(FwscLayout.PackageBoard.SMK, FwscLayout.packageBoard(resolved.image))
        val package_ = FwscPackage.read(bytes)
        assertEquals("SMK-37 Pro", package_.identity.model)
        assertEquals("SMK-37 Pro_900", package_.product)
    }
}