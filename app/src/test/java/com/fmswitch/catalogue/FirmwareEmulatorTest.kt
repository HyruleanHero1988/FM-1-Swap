package com.fmswitch.catalogue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FirmwareEmulatorTest {
    @Test
    fun hyp1HasTheRustypawzEmulator() {
        val hyp = CatalogueEntry(
            id = "hyp1",
            name = "HYP-1",
            author = "RedtailWorks",
            links = listOf(
                CatalogueLink("Try it in the browser", "https://rustypawz.github.io/fm1-HYP1/emu/"),
            ),
        )
        assertEquals("https://rustypawz.github.io/fm1-HYP1/emu/", FirmwareEmulator.authorEmulator(hyp))
        assertEquals(
            "https://rustypawz.github.io/fm1-HYP1/lof/emu/",
            FirmwareEmulator.authorEmulator(CatalogueEntry(id = "redtail-lof", name = "LOF-1")),
        )
    }

    @Test
    fun hubPlayLinkIsTheAuthorsEmulator() {
        val x0x = CatalogueEntry(
            id = "x0x",
            name = "X0X",
            links = listOf(
                CatalogueLink("Try it in the browser", "https://charlesvestal.github.io/fm1-x0x/emu/"),
            ),
        )
        assertEquals("https://charlesvestal.github.io/fm1-x0x/emu/", FirmwareEmulator.authorEmulator(x0x))
    }

    @Test
    fun mostFirmwaresHaveNoAuthorEmulator() {
        assertNull(FirmwareEmulator.authorEmulator(CatalogueEntry(id = "sloop", name = "SLOOP")))
        assertNull(FirmwareEmulator.authorEmulator(CatalogueEntry(id = "fm1-editor-baud-girl", name = "Baud Girl (FM-1+VA)")))
    }

    @Test
    fun onlinePageUsesFm1EmuSlugs() {
        assertEquals("${FirmwareEmulator.CHIP_EMU}?fw=sloop", FirmwareEmulator.onlineUrl(CatalogueEntry(id = "sloop", name = "SLOOP")))
        assertEquals(
            "${FirmwareEmulator.CHIP_EMU}?fw=mvave",
            FirmwareEmulator.onlineUrl(CatalogueEntry(id = "mvave-official", name = "FM-1 V15", isOfficial = true)),
        )
    }
}
