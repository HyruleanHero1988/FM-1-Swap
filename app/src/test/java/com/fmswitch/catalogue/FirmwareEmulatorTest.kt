package com.fmswitch.catalogue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirmwareEmulatorTest {
    @Test
    fun hyp1UsesTheRustypawzPage() {
        val hyp = CatalogueEntry(
            id = "hyp1",
            name = "HYP-1",
            author = "RedtailWorks",
            links = listOf(
                CatalogueLink("Try it in the browser", "https://rustypawz.github.io/fm1-HYP1/emu/"),
            ),
        )
        val launch = FirmwareEmulator.launch(hyp)
        assertEquals("https://rustypawz.github.io/fm1-HYP1/emu/", launch.url)
        assertTrue(launch.dedicated)
        assertEquals(
            "https://rustypawz.github.io/fm1-HYP1/lof/emu/",
            FirmwareEmulator.launch(CatalogueEntry(id = "redtail-lof", name = "LOF-1")).url,
        )
    }

    @Test
    fun hubPlayLinkBeatsTheGenericChipEmulator() {
        val x0x = CatalogueEntry(
            id = "x0x",
            name = "X0X",
            links = listOf(
                CatalogueLink("Try it in the browser", "https://charlesvestal.github.io/fm1-x0x/emu/"),
            ),
        )
        assertEquals("https://charlesvestal.github.io/fm1-x0x/emu/", FirmwareEmulator.launch(x0x).url)
    }

    @Test
    fun sloopFallsBackToTheChipEmulator() {
        val launch = FirmwareEmulator.launch(CatalogueEntry(id = "sloop", name = "SLOOP"))
        assertEquals("${FirmwareEmulator.CHIP_EMU}?embed=1&fw=sloop", launch.url)
        assertFalse(launch.dedicated)
        assertTrue(launch.credit.contains("Eriksson"))
    }

    @Test
    fun officialStockUsesTheMvaveCard() {
        val launch = FirmwareEmulator.launch(
            CatalogueEntry(id = "mvave-official", name = "FM-1 V15", isOfficial = true),
        )
        assertEquals("${FirmwareEmulator.CHIP_EMU}?embed=1&fw=mvave", launch.url)
        assertTrue(launch.hint.contains("stock"))
    }
}
