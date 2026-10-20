package com.fmswitch.catalogue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorListingTest {
    @Test
    fun readsBaudGirlFromFm1EditorHtml() {
        val listing = EditorListing.parse(EDITOR_HTML)
        val baud = listing.entries.single { it.anchor == "baud-girl" }
        assertEquals("Baud Girl (FM-1+VA)", baud.name)
        assertEquals("Baud Girl", baud.author)
        assertEquals("FM-1_101", baud.latest)
        assertEquals("2026-10-12", baud.updated)
        assertTrue(baud.pinned)
        assertTrue(baud.blurb!!.contains("Virtual Analog"))
        assertTrue(baud.links.any { it.url == "https://baudgirl.com/work/FM-1+VA" })
        assertTrue(baud.links.any { it.url == "https://baudgirl.com/work/FM-1+VA/manual" })
        val clipper = listing.entries.single { it.anchor == "clipper" }
        assertEquals("Clipper", clipper.name)
        assertEquals("isod89", clipper.author)
        assertEquals("1.0", clipper.latest)
        assertTrue(clipper.experimental)
        assertTrue(clipper.links.any { it.url.contains("isod89/clipper-fm1") })
    }

    @Test
    fun stockIsOfficialV15AndOpenIsNot() {
        val listing = EditorListing.parse(EDITOR_HTML)
        val stock = listing.entries.single { it.anchor == "stock" }
        val open = listing.entries.single { it.anchor == "official-open" }
        assertTrue(CatalogueBuilder.isOfficialV15(stock))
        assertFalse(CatalogueBuilder.isOfficialV15(open))
    }
}

class CatalogueBuilderTest {
    @Test
    fun addsBaudGirlWhenTheHubDoesNotCarryIt() {
        val editor = EditorListing.parse(EDITOR_HTML)
        val merged = CatalogueBuilder.merge(listOf(officialCatalogueEntry()), editor.entries)
        val baud = merged.single { it.id == "fm1-editor-baud-girl" }
        assertEquals("Baud Girl (FM-1+VA)", baud.name)
        assertEquals("Baud Girl", baud.author)
        assertEquals("FM-1_101", baud.version)
        assertNull(baud.packageURL)
        assertTrue(baud.links.any { it.label == "fm1-editor.com listing" })
        assertEquals(1, merged.count { it.isOfficial })
        assertTrue(merged.any { it.id == "fm1-editor-official-open" })
        val clipper = merged.single { it.id == "fm1-editor-clipper" }
        assertEquals("Clipper", clipper.name)
        assertEquals("1.0", clipper.version)
        assertTrue(clipper.beta)
        assertEquals("isod89", clipper.author)
    }

    @Test
    fun matchesHubAndEditorByNormalisedName() {
        val hub = CatalogueEntry(id = "felucca", name = "Felucca", packageURL = "https://example/felucca.fwsc")
        val listed = EditorListing.Entry(anchor = "felucca", name = "Felucca!")
        assertTrue(CatalogueBuilder.matches(hub, listed))
        assertEquals("baudgirlfm1va", CatalogueBuilder.normalisedName("Baud Girl (FM-1+VA)"))
    }
}

class PackageDiscoveryTest {
    @Test
    fun readsBaudGirlPublisherManifest() {
        val found = PackageDiscovery.manifestPackage(INSTALL_HTML, "https://baudgirl.com/work/FM-1+VA/install")
        assertNotNull(found)
        assertEquals("https://baudgirl.com/fm1/firmware/FM-1+VA-102.fwsc", found!!.url)
        assertEquals("FM-1_102", found.version)
        assertEquals("FM-1_102", found.product)
        assertEquals("2026-10-19", found.date)
        assertEquals("7b169a69b30570581d0ebc7b44851e3e08898d2956ade155363bcf18fac5c348", found.sha256)
        assertFalse(found.beta)
    }

    @Test
    fun findsInstallPageFromProjectPage() {
        val links = PackageDiscovery.installPageLinks(
            """<a href="/work/FM-1+VA/install">Install</a>""",
            "https://baudgirl.com/work/FM-1+VA",
        )
        assertEquals(listOf("https://baudgirl.com/work/FM-1+VA/install"), links)
    }

    @Test
    fun picksClipperFwscFromInstallerPage() {
        val found = PackageDiscovery.packageLinks(
            """href="../../firmware/clipper-1.0-experimental.fwsc" and also (FM-1.fwsc)""",
            "https://isod89.github.io/clipper-fm1/webapp/installer/",
        )
        assertEquals(
            listOf("https://isod89.github.io/clipper-fm1/firmware/clipper-1.0-experimental.fwsc"),
            found,
        )
        val words = PackageDiscovery.identifyingWords(
            "Clipper",
            listOf(CatalogueLink("Source", "https://github.com/isod89/clipper-fm1")),
        )
        assertTrue(words.contains("clipper"))
        assertTrue(PackageDiscovery.isRelevant(found.single(), words))
        val repos = PackageDiscovery.gitHubRepos(
            listOf(
                CatalogueLink("Install", "https://isod89.github.io/clipper-fm1/webapp/installer/"),
                CatalogueLink("Source", "https://github.com/isod89/clipper-fm1"),
                CatalogueLink("Release", "https://github.com/isod89/clipper-fm1/releases/tag/v1.0-experimental"),
            ),
        )
        assertEquals(listOf("isod89" to "clipper-fm1"), repos)
    }
}

private const val EDITOR_HTML = """
<article class="firmware-entry synthwave-panel" id="official-open" data-updated="2026-10-14">
  <p class="synthwave-kicker">M-VAVE</p>
  <h2>Official Open</h2>
  <p class="firmware-status"><span>Latest V12</span></p>
  <p class="firmware-blurb">M-VAVE's own open-source firmware.</p>
  <ul class="firmware-links">
    <li><a href="https://github.com/m-vave/fm1_open">Source</a></li>
    <li><a href="https://www.m-vave.com/download">FM1 Workbench</a></li>
  </ul>
</article>
<article class="firmware-entry synthwave-panel" id="stock" data-updated="2026-07-30">
  <p class="synthwave-kicker">M-VAVE</p>
  <h2>Stock firmware</h2>
  <p class="firmware-status"><span>Latest V15</span></p>
  <p class="firmware-blurb">The firmware every FM1 ships with.</p>
  <ul class="firmware-links">
    <li><a href="https://www.m-vave.com/download">M-VAVE downloads</a></li>
  </ul>
</article>
<article class="firmware-entry synthwave-panel" id="baud-girl" data-pinned data-updated="2026-10-12">
  <p class="synthwave-kicker">Baud Girl</p>
  <h2>Baud Girl (FM-1+VA)</h2>
  <p class="firmware-status"><span>Latest FM-1_101</span></p>
  <p class="firmware-blurb">
    Keeps M-VAVE's FM engine and effects, fixes its engine bugs, and adds a Virtual
    Analog engine, an 8-Bit engine, a Bitcrush effect, a 64-step sequencer, and a Note
    Engine for arpeggios, auto-arpeggios, and auto-chords.
  </p>
  <ul class="firmware-links">
    <li><a href="https://baudgirl.com/work/FM-1+VA">FM-1+VA</a></li>
    <li><a href="https://baudgirl.com/work/FM-1+VA/manual">Manual</a></li>
  </ul>
</article>
<article class="firmware-entry synthwave-panel" id="clipper" data-updated="2026-10-19" data-stars="1">
  <p class="synthwave-kicker">isod89</p>
  <h2>Clipper</h2>
  <p class="firmware-status"><span>Experimental · Latest 1.0</span></p>
  <p class="firmware-blurb">A synthesizer from SLOOP, with 23 engines and 407 sounds.</p>
  <ul class="firmware-links">
    <li><a href="https://isod89.github.io/clipper-fm1/webapp/installer/">Install</a></li>
    <li><a href="https://github.com/isod89/clipper-fm1/releases/tag/v1.0-experimental">Release</a></li>
    <li><a href="https://github.com/isod89/clipper-fm1">Source</a></li>
  </ul>
</article>
"""

private const val INSTALL_HTML = """
<html><head>
<script type="application/json" id="fi-releases">
[{"version":"FM-1_102","device":"fm1","date":"2026-10-19","file":"/fm1/firmware/FM-1+VA-102.fwsc","sha256":"7b169a69b30570581d0ebc7b44851e3e08898d2956ade155363bcf18fac5c348","beta":false,"current":true,"n":102},{"version":"FM-1_101","file":"/fm1/firmware/FM-1+VA-101.fwsc","sha256":"b43d6fcdf5618b83e3294b84d0b177f490956b903024452d021cd7667a61f9b4","beta":false,"current":false}]
</script>
</head><body></body></html>
"""
