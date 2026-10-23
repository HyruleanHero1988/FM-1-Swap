package com.fmswitch.catalogue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FirmwareKindTest {
    private val zp12 = CatalogueEntry(
        id = "zp12",
        name = "zp12",
        kinds = listOf("drums"),
        vintage = true,
        links = listOf(CatalogueLink("Try it in the browser", "https://thefirmwarehub.com/play/zp12/")),
    )
    private val felucca = CatalogueEntry(
        id = "felucca",
        name = "Felucca",
        kinds = listOf("groove", "dev"),
        builtFromSource = false,
    )
    private val signed = CatalogueEntry(
        id = "melodee",
        name = "Melodee",
        kinds = listOf("synth"),
        builtFromSource = true,
    )
    private val v15 = officialCatalogueEntry()

    @Test
    fun hubGroupsMatchTheSameWayTheSiteDoes() {
        assertTrue(FirmwareKind.ALL.matches(v15))
        assertTrue(FirmwareKind.DRUMS.matches(zp12))
        assertTrue(FirmwareKind.VINTAGE.matches(zp12))
        assertTrue(FirmwareKind.PLAY.matches(zp12))
        assertTrue(FirmwareKind.GROOVE.matches(felucca))
        assertTrue(FirmwareKind.DEV.matches(felucca))
        assertTrue(FirmwareKind.SRC.matches(signed))
        assertFalse(FirmwareKind.SYNTH.matches(zp12))
        assertFalse(FirmwareKind.SRC.matches(felucca))
        assertFalse(FirmwareKind.GROOVE.matches(v15))
    }

    @Test
    fun hubFeedKeepsVintagePlayAndSignedBuilds() {
        val (_, entries) = HubFeed.decode(HUB_JSON)
        val zp = entries.single { it.id == "zp12" }
        assertEquals(listOf("drums"), zp.kinds)
        assertTrue(zp.vintage)
        assertEquals("https://thefirmwarehub.com/play/zp12/", FirmwareEmulator.playLink(zp))
        val melodee = entries.single { it.id == "melodee" }
        assertTrue(melodee.builtFromSource)
        assertEquals(listOf("synth"), melodee.kinds)
        val hortator = entries.single { it.id == "hortator" }
        assertFalse(hortator.vintage)
        assertFalse(hortator.builtFromSource)
    }

    @Test
    fun hubScreenshotUrlsFollowTheCardId() {
        assertEquals(
            listOf("https://thefirmwarehub.com/img/sloop.png"),
            HubShots.urlFor("sloop"),
        )
        assertEquals(
            listOf(
                "https://thefirmwarehub.com/img/fm1-editor-clipper.png",
                "https://thefirmwarehub.com/img/clipper.png",
            ),
            HubShots.urlFor("fm1-editor-clipper"),
        )
        assertTrue(HubShots.isHubShot("https://thefirmwarehub.com/img/felucca.png?v=1"))
        assertTrue(RepoShots.isScannedShot("https://thefirmwarehub.com/img/sloop.png"))
        assertFalse(HubShots.isHubShot("https://example.com/img/sloop.png"))
    }

    @Test
    fun searchFindsHubKindWords() {
        assertTrue(CatalogueSearch.matches(felucca, "groovebox"))
        assertTrue(CatalogueSearch.matches(zp12, "vintage sampler"))
        assertFalse(CatalogueSearch.matches(felucca, "drums"))
    }
}

private const val HUB_JSON = """
{
  "updated": "2026-10-23 14:02 UTC",
  "firmwares": [
    {
      "id": "zp12",
      "kind": "drums",
      "name": "zp12",
      "author": "zvenson",
      "what": "A 12-bit sampler",
      "play": "https://thefirmwarehub.com/play/zp12/",
      "vintage": true,
      "pkg": "fw/zp12-2.4.4.fwsc",
      "sha256": "0d9bc8c314a7d1df36bcffea6b79b9fa523f44b618b596ae12a34bf8552ca441",
      "beta": false
    },
    {
      "id": "melodee",
      "kind": "synth",
      "name": "Melodee",
      "pkg": "fw/melodee.fwsc",
      "sha256": "a18f40d6f8c8087b25f115ab11286f85c7f04383de836227c463c6ce4745ce20",
      "provenance": {
        "sha256": "a18f40d6f8c8087b25f115ab11286f85c7f04383de836227c463c6ce4745ce20",
        "commit": "398ada1e09d864c12f3b778a43e45c7c2a8031b8",
        "verified": true
      }
    },
    {
      "id": "hortator",
      "kind": "drums",
      "name": "Hortator",
      "pkg": "fw/hortator.fwsc",
      "sha256": "d05aaf518369d8342467016f831107221e372d4fec4b3375b69086f7eb65c509",
      "vintage": false,
      "play": null
    }
  ]
}
"""
