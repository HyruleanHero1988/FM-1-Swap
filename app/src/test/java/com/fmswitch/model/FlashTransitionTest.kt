package com.fmswitch.model

import com.fmswitch.catalogue.CatalogueEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class FlashTransitionTest {
    @Test
    fun arrowGoesFromTheRunningFirmwareToThePackage() {
        val running = deviceFirmwareLabel(
            identityText = "FM-1_900",
            loader = false,
            saved = listOf(saved("sloop", "SLOOP", "2.7.1", "FM-1_900")),
            catalogue = emptyList(),
            lastInstall = null,
        )
        val rainbow = saved("rainbow", "Rainbow Mode", "1.1.5-rb", "FM-1_911")
        assertEquals("SLOOP 2.7.1 → Rainbow Mode 1.1.5-rb", flashArrow(running, rainbow))
    }

    @Test
    fun sharedIdStaysAsTheIdTheDeviceReported() {
        val running = deviceFirmwareLabel(
            identityText = "FM-1_900",
            loader = false,
            saved = listOf(saved("sloop", "SLOOP", "2.7.1", "FM-1_900")),
            catalogue = listOf(
                CatalogueEntry(id = "sloop", name = "SLOOP", version = "2.7.1", product = "FM-1_900"),
                CatalogueEntry(id = "ghoulbox", name = "GHOULBOX", version = "2.3.2", product = "FM-1_900"),
            ),
            lastInstall = null,
        )
        assertEquals("FM-1_900", running)
    }

    @Test
    fun editorNamesTheFirmwareThisFlashReplaces() {
        val labels = flashFromLabels("FM-1_900", listOf(saved("rainbow", "Rainbow Mode", "1.1.5-rb", "FM-1_911")))
        assertEquals(
            listOf("FELUCCA SLOOP 2.7.1"),
            withDeviceFirmwareName(labels, "FELUCCA SLOOP 2.7.1"),
        )
        assertEquals(labels, withDeviceFirmwareName(labels, "  "))
    }

    @Test
    fun lastFlashFromThisPhoneNamesTheDevice() {
        val running = deviceFirmwareLabel(
            identityText = "FM-1_911",
            loader = false,
            saved = listOf(
                saved("sloop", "SLOOP", "2.7.1", "FM-1_911"),
                saved("rainbow", "Rainbow Mode", "1.1.5-rb", "FM-1_911"),
            ),
            catalogue = emptyList(),
            lastInstall = FlashRecord(name = "Rainbow Mode", version = "1.1.5-rb", product = "FM-1_911", date = 1),
        )
        assertEquals("Rainbow Mode 1.1.5-rb", running)
    }

    @Test
    fun loaderAndMissingDeviceHaveTheirOwnLabels() {
        assertEquals(
            "Update loader",
            deviceFirmwareLabel("ota-FM-1_009", loader = true, saved = emptyList(), catalogue = emptyList(), lastInstall = null),
        )
        assertEquals(
            "Unknown",
            deviceFirmwareLabel(null, loader = false, saved = emptyList(), catalogue = emptyList(), lastInstall = null),
        )
    }

    @Test
    fun aChainNamesEachStepFromThePreviousPackage() {
        val first = saved("sloop", "SLOOP", "2.7.1", "FM-1_900")
        val second = saved("rainbow", "Rainbow Mode", "1.1.5-rb", "FM-1_911")
        assertEquals(
            listOf("FM-1_900", "SLOOP 2.7.1"),
            flashFromLabels("FM-1_900", listOf(first, second)),
        )
    }

    private fun saved(id: String, name: String, version: String, product: String) = StoredFirmware(
        id = id,
        name = name,
        version = version,
        product = product,
        sha256 = id,
        size = 1,
        fileName = "$id.fwsc",
        source = "saved",
        savedAt = 0,
    )
}
