package com.fmswitch.catalogue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlVoidTagTest {
    @Test
    fun readsImgWithoutAClosingTag() {
        val img = Html.voids("img", """<img alt="SLOOP" src="/shot.webp" srcset="/shot-460.webp 460w">""").single()
        assertEquals("SLOOP", img.attributes["alt"])
        assertEquals("/shot.webp", img.attributes["src"])
        assertEquals("/shot-460.webp 460w", img.attributes["srcset"])
    }
}

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
        assertEquals(
            "https://fm1-editor.com/assets/firmware-sloop-460.webp",
            listing.entries.single { it.anchor == "sloop" }.photoURL,
        )
        assertEquals("SLOOP's tracks screen, with its four tracks playing", listing.entries.single { it.anchor == "sloop" }.photoAlt)
        assertNull(clipper.photoURL)
        val open = listing.entries.single { it.anchor == "official-open" }
        assertTrue(open.pinned)
        assertEquals(151, open.stars)
        assertTrue(open.links.any { it.url == "https://open.m-vave.com/fm1-workbench" })
        assertEquals(
            "https://fm1-editor.com/assets/firmware-fm1-va.webp",
            listing.entries.single { it.anchor == "baud-girl" }.photoURL,
        )
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
        val merged = CatalogueBuilder.merge(
            listOf(
                officialCatalogueEntry(),
                CatalogueEntry(id = "sloop", name = "SLOOP", author = "isod89", stars = 567, installs = 2803),
            ),
            editor.entries,
        )
        val baud = merged.single { it.id == "fm1-editor-baud-girl" }
        assertEquals("Baud Girl (FM-1+VA)", baud.name)
        assertEquals("Baud Girl", baud.author)
        assertEquals("FM-1_101", baud.version)
        assertNull(baud.packageURL)
        assertTrue(baud.links.any { it.label == "fm1-editor.com listing" })
        assertEquals(1, merged.count { it.isOfficial })
        val open = merged.single { it.id == "fm1-editor-official-open" }
        assertEquals("Official Open", open.name)
        assertEquals("M-VAVE", open.author)
        assertTrue(open.pinned)
        assertEquals(151, open.stars)
        assertFalse(open.isOfficial)
        assertNull(open.packageURL)
        val clipper = merged.single { it.id == "fm1-editor-clipper" }
        assertEquals("Clipper", clipper.name)
        assertEquals("1.0", clipper.version)
        assertTrue(clipper.beta)
        assertEquals("isod89", clipper.author)
        val sloop = merged.single { it.id == "sloop" }
        assertEquals("https://fm1-editor.com/assets/firmware-sloop-460.webp", sloop.photoURL)
        assertEquals(567, sloop.stars)
        assertTrue(merged.single { it.id == "fm1-editor-baud-girl" }.pinned)
    }

    @Test
    fun aboutLinePrefersTheGitHubShortDescription() {
        val sloop = CatalogueEntry(
            id = "sloop",
            name = "SLOOP",
            about = "A live groovebox firmware for the M-VAVE FM-1",
            what = "A live groovebox: tracks, layers, a step sequencer, song mode.",
        )
        assertEquals("A live groovebox firmware for the M-VAVE FM-1", CatalogueBlurb.line(sloop))
        val baud = CatalogueEntry(
            id = "baud",
            name = "Baud Girl",
            what = "Keeps M-VAVE's FM engine and effects, fixes its engine bugs, and adds a Virtual Analog engine.",
        )
        assertEquals(baud.what, CatalogueBlurb.line(baud))
        val open = CatalogueEntry(id = "open", name = "Official Open", what = "word ".repeat(40).trim())
        val line = CatalogueBlurb.line(open)!!
        assertTrue(line.endsWith("…"))
        assertTrue(line.length < open.what!!.length)
    }

    @Test
    fun aboutOverlayFillsOnlyBlankLines() {
        val entries = listOf(
            CatalogueEntry(id = "sloop", name = "SLOOP"),
            CatalogueEntry(id = "kept", name = "Kept", about = "Already there"),
        )
        val filled = AboutOverlay.apply(entries, """{"sloop":"A live groovebox firmware for the M-VAVE FM-1","kept":"nope"}""")
        assertEquals("A live groovebox firmware for the M-VAVE FM-1", filled[0].about)
        assertEquals("Already there", filled[1].about)
    }

    @Test
    fun readsAGitHubAboutLine() {
        assertEquals(
            "A live groovebox firmware for the M-VAVE FM-1",
            RepoAbout.description("""{"description":"A live groovebox firmware for the M-VAVE FM-1"}"""),
        )
        assertNull(RepoAbout.description("""{"description":"  "}"""))
        assertNull(RepoAbout.description("""{"description":null}"""))
    }

    @Test
    fun listsSloopNearTheTopByStars() {
        val ordered = CatalogueRank.ordered(
            listOf(
                CatalogueEntry(id = "obscure", name = "BEETLE", stars = 2),
                CatalogueEntry(id = "sloop", name = "SLOOP", stars = 567, installs = 2803),
                officialCatalogueEntry(),
                CatalogueEntry(id = "felucca", name = "Felucca", stars = 543, installs = 1124),
                CatalogueEntry(id = "fm1-editor-baud-girl", name = "Baud Girl (FM-1+VA)", pinned = true),
                CatalogueEntry(id = "fm1-editor-clipper", name = "Clipper", stars = 1, beta = true),
            ),
        )
        assertEquals(listOf("mvave-official", "sloop", "felucca", "fm1-editor-baud-girl", "obscure", "fm1-editor-clipper"), ordered.map { it.id })
    }

    @Test
    fun curationKeepsChosenFirmwaresOnTopInPersonalOrder() {
        val popular = CatalogueRank.ordered(
            listOf(
                CatalogueEntry(id = "obscure", name = "BEETLE", stars = 2),
                CatalogueEntry(id = "sloop", name = "SLOOP", stars = 567, installs = 2803),
                officialCatalogueEntry(),
                CatalogueEntry(id = "felucca", name = "Felucca", stars = 543, installs = 1124),
                CatalogueEntry(id = "fm1-editor-baud-girl", name = "Baud Girl (FM-1+VA)", pinned = true),
                CatalogueEntry(id = "fm1-editor-clipper", name = "Clipper", stars = 1, beta = true),
            ),
        )
        val ordered = CatalogueOrder.apply(popular, FirmwareSort.CURATION, listOf("fm1-editor-clipper", "obscure"))
        assertEquals(
            listOf("fm1-editor-clipper", "obscure", "mvave-official", "sloop", "felucca", "fm1-editor-baud-girl"),
            ordered.map { it.id },
        )
    }

    @Test
    fun nameAuthorAndNewestSortsIgnorePopularity() {
        val entries = listOf(
            CatalogueEntry(id = "sloop", name = "SLOOP", author = "Zed", date = "2020-01-01"),
            CatalogueEntry(id = "baud", name = "Baud Girl", author = "Ada", date = "2026-10-20"),
            CatalogueEntry(id = "anon", name = "Anon", date = "2024-05-01"),
        )
        assertEquals(listOf("anon", "baud", "sloop"), CatalogueOrder.apply(entries, FirmwareSort.NAME, emptyList()).map { it.id })
        assertEquals(listOf("baud", "sloop", "anon"), CatalogueOrder.apply(entries, FirmwareSort.AUTHOR, emptyList()).map { it.id })
        assertEquals(listOf("baud", "anon", "sloop"), CatalogueOrder.apply(entries, FirmwareSort.NEWEST, emptyList()).map { it.id })
    }

    @Test
    fun matchesHubAndEditorByNormalisedName() {
        val hub = CatalogueEntry(id = "felucca", name = "Felucca", packageURL = "https://example/felucca.fwsc")
        val listed = EditorListing.Entry(anchor = "felucca", name = "Felucca!")
        assertTrue(CatalogueBuilder.matches(hub, listed))
        assertEquals("baudgirlfm1va", CatalogueBuilder.normalisedName("Baud Girl (FM-1+VA)"))
    }

    @Test
    fun mergesRedtailPackWithoutDuplicatingHyp1() {
        val redtail = RedtailListing.parse(REDTAIL_HTML)
        val merged = CatalogueBuilder.merge(
            listOf(
                officialCatalogueEntry(),
                CatalogueEntry(
                    id = "hyp1",
                    name = "HYP-1",
                    author = "RedtailWorks",
                    packageURL = "https://thefirmwarehub.com/fw/hyp1-1.5.44.fwsc",
                    product = "FM-1_7010544",
                ),
            ),
            editor = emptyList(),
            redtail = redtail.entries,
        )
        assertEquals(1, merged.count { CatalogueBuilder.normalisedName(it.name) == "hyp1" })
        val hyp = merged.single { it.id == "hyp1" }
        assertEquals("https://rustypawz.github.io/fm1-HYP1/img/hyp-device.png", hyp.photoURL)
        assertTrue(hyp.links.any { it.url.endsWith("/install/") || it.url.endsWith("/install") })
        assertFalse(merged.any { it.id == "redtail-hyp" })
        val lof = merged.single { it.id == "redtail-lof" }
        assertEquals("LOF-1", lof.name)
        assertEquals("RedtailWorks", lof.author)
        assertTrue(lof.beta)
        assertTrue(lof.what!!.contains("lofi groovebox", ignoreCase = true))
        assertEquals("https://rustypawz.github.io/fm1-HYP1/lof/img/lof-device.png", lof.photoURL)
        assertEquals("redtail-eco", merged.single { it.name == "ECO-1" }.id)
        assertEquals("redtail-grav", merged.single { it.name == "GRAV-1" }.id)
        assertTrue(merged.none { it.name.contains("OPEN-1") })
        assertEquals("Hold OCT− and OCT+ for 5 s while it runs", RescueMode.summary("redtail-lof"))
        assertEquals(RescueMode.summary("hyp1"), RescueMode.summary("redtail-grav"))
    }
}

class RedtailListingTest {
    @Test
    fun readsTheFourFm1CardsAndSkipsOpen1() {
        val listing = RedtailListing.parse(REDTAIL_HTML)
        assertEquals(listOf("hyp", "lof", "eco", "grav"), listing.entries.map { it.slug })
        val hyp = listing.entries.single { it.slug == "hyp" }
        assertEquals("HYP-1", hyp.name)
        assertFalse(hyp.experimental)
        assertEquals("Hypnotic techno and acid", hyp.tagline)
        assertEquals("https://rustypawz.github.io/fm1-HYP1/img/hyp-device.png", hyp.photoURL)
        assertEquals(
            listOf("Install", "About HYP-1", "Try in browser", "RedtailWorks pack", "Source"),
            hyp.links.map { it.label },
        )
        assertEquals("https://rustypawz.github.io/fm1-HYP1/install/", hyp.links.first().url)
        val lof = listing.entries.single { it.slug == "lof" }
        assertTrue(lof.experimental)
        assertEquals("A lofi groovebox", lof.tagline)
        assertTrue(lof.blurb!!.contains("tired jazz band"))
        assertEquals("https://rustypawz.github.io/fm1-HYP1/lof/install/", lof.links.first().url)
        assertTrue(listing.entries.none { it.name.contains("OPEN-1") })
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

    @Test
    fun unwrapsOfficialOpenFwscEmbeddedInTheWorkbench() {
        val html = """
            <script>FM1_PACKAGE = {"schema":1,"product":"FM-1_8000014","profile":"dx7","release_version":"V14","sha256":"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855","bytes":4,"data":"AQIDBA=="};</script>
        """.trimIndent()
        val found = PackageDiscovery.workbenchPackage(html, "https://github.com/m-vave/fm1_open/releases/download/V14/FM1-Open-V14-Workbench.html")
        assertNotNull(found)
        assertEquals("https://github.com/m-vave/fm1_open/releases/download/V14/FM1-Open-V14-Workbench.html", found!!.url)
        assertEquals("V14", found.version)
        assertEquals("FM-1_8000014", found.product)
        assertEquals(4, found.size)
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", found.sha256)
        assertEquals(byteArrayOf(1, 2, 3, 4).toList(), PackageDiscovery.firmwareBytes(html.toByteArray()).toList())
        val plain = byteArrayOf(0x46, 0x4d, 0x2d, 0x31)
        assertEquals(plain.toList(), PackageDiscovery.firmwareBytes(plain).toList())
        val truncated = """FM1_PACKAGE = {"schema":1,"product":"FM-1_8000014","release_version":"V14","sha256":"aa","bytes":492084,"data":"AAAA"""
        val head = PackageDiscovery.workbenchPackage(truncated, "https://example.com/FM1-Open-V14-Workbench.html")
        assertNotNull(head)
        assertEquals("V14", head!!.version)
        assertEquals("FM-1_8000014", head.product)
        assertEquals(492084, head.size)
    }

    @Test
    fun doesNotAttachOfficialOpenWorkbenchToStockV15() {
        val workbench =
            "https://github.com/m-vave/fm1_open/releases/download/V14/FM1-Open-V14-Workbench.html"
        val v15Words = PackageDiscovery.identifyingWords(
            "FM-1 V15",
            listOf(
                CatalogueLink("M-VAVE downloads", "https://www.m-vave.com/download"),
                CatalogueLink("fm1_open", "https://github.com/m-vave/fm1_open"),
            ),
        )
        val openWords = PackageDiscovery.identifyingWords(
            "Official Open",
            listOf(CatalogueLink("Source", "https://github.com/m-vave/fm1_open")),
        )
        assertFalse(PackageDiscovery.isRelevant(workbench, v15Words))
        assertTrue(PackageDiscovery.isRelevant(workbench, openWords))
        val found = DiscoveredPackage(url = workbench, version = "V14", product = "FM-1_8000014")
        val applied = PackageDiscovery.apply(found, officialCatalogueEntry())
        assertNull(applied.packageURL)
        assertEquals("V15", applied.version)
        assertEquals("FM-1_015", applied.product)
    }

    @Test
    fun readsRedtailInstallerMetaAndIgnoresSiblingPackFiles() {
        val found = PackageDiscovery.installerMetaPackage(
            """<script>const meta = {"version": "0.0.77", "product": "FM-1_7000077", "pkg": "../firmware/lof1-0.0.77.fwsc"};</script>""",
            "https://rustypawz.github.io/fm1-HYP1/lof/install/",
        )
        assertNotNull(found)
        assertEquals("https://rustypawz.github.io/fm1-HYP1/lof/firmware/lof1-0.0.77.fwsc", found!!.url)
        assertEquals("0.0.77", found.version)
        assertEquals("FM-1_7000077", found.product)
        assertTrue(found.beta)
        val words = PackageDiscovery.identifyingWords(
            "LOF-1",
            listOf(
                CatalogueLink("Install", "https://rustypawz.github.io/fm1-HYP1/lof/install/"),
                CatalogueLink("Source", "https://github.com/RustyPawz/fm1-HYP1"),
            ),
        )
        assertTrue(words.contains("lof"))
        assertFalse(words.contains("hyp1"))
        assertTrue(PackageDiscovery.isRelevant(found.url, words))
        assertFalse(
            PackageDiscovery.isRelevant(
                "https://rustypawz.github.io/fm1-HYP1/firmware/hyp1-1.5.44.fwsc",
                words,
            ),
        )
    }
}

private const val EDITOR_HTML = """
<article class="firmware-entry synthwave-panel" id="official-open" data-pinned data-updated="2026-10-14">
  <p class="synthwave-kicker">M-VAVE</p>
  <h2>Official Open</h2>
  <p class="firmware-status">
    <span>Latest V12</span>
    <span class="firmware-stars" title="151 stars on GitHub">151</span>
  </p>
  <p class="firmware-blurb">M-VAVE's own open-source firmware.</p>
  <ul class="firmware-links">
    <li><a href="https://open.m-vave.com/fm1-workbench">FM1 Workbench</a></li>
    <li><a href="https://github.com/m-vave/fm1_open">Source</a></li>
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
<article class="firmware-entry synthwave-panel" id="sloop" data-updated="2026-10-16" data-stars="556">
  <figure class="firmware-photo crt-inset dither-well">
    <img
      alt="SLOOP's tracks screen, with its four tracks playing"
      src="/assets/firmware-sloop-BW1MIVma.webp"
      srcset="/assets/firmware-sloop-460.webp 460w, /assets/firmware-sloop-BW1MIVma.webp 972w"
      width="972"
    />
  </figure>
  <p class="synthwave-kicker">isod89</p>
  <h2>SLOOP</h2>
  <p class="firmware-status"><span>Latest 2.7.1</span></p>
  <p class="firmware-blurb">A live groovebox.</p>
  <ul class="firmware-links">
    <li><a href="https://github.com/isod89/sloop-fm1">Source</a></li>
  </ul>
</article>
<article class="firmware-entry synthwave-panel" id="baud-girl" data-pinned data-updated="2026-10-12">
  <figure class="firmware-photo">
    <img alt="Baud Girl's FM1 render, with her name on the screen" src="/assets/firmware-fm1-va.webp" />
  </figure>
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
  <figure class="firmware-photo firmware-photo-missing dither-well" aria-hidden="true"><span>Clipper</span></figure>
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

private const val REDTAIL_HTML = """
<a class="open1" href="open1/"><b>OPEN-1</b> <em>Ideas phase</em></a>
<div class="fws">
<article class="fw"><a href="hyp/"><img src="img/hyp-device.png" alt="the FM-1 running HYP-1"></a>
<h3><a href="hyp/">HYP-1</a><span class="status">Stable</span></h3>
<p class="what">Hypnotic techno and acid</p>
<p>A breathing pad, a deep drone, a generative 303 line and a bed of grain over drums you sequence.</p>
<div class="go"><a href="hyp/">About HYP-1</a><a href="emu/">Try in browser</a><a href="install/">Install</a></div>
</article>
<article class="fw"><a href="lof/"><img src="lof/img/lof-device.png" alt="the FM-1 running LOF-1"></a>
<h3><a href="lof/">LOF-1</a><span class="status">Experimental</span></h3>
<p class="what">A lofi groovebox</p>
<p>You play the lead and a tired jazz band follows: comp, bass and drums move with your chords.</p>
<div class="go"><a href="lof/">About LOF-1</a><a href="lof/emu/">Try in browser</a><a href="lof/install/">Install</a></div>
</article>
<article class="fw"><a href="eco/"><img src="eco/img/eco-device.png" alt="the FM-1 running ECO-1"></a>
<h3><a href="eco/">ECO-1</a><span class="status">Experimental</span></h3>
<p class="what">A plant plays it</p>
<p>Three leaves fire notes as light and water charge them.</p>
<div class="go"><a href="eco/">About ECO-1</a><a href="eco/emu/">Try in browser</a><a href="eco/install/">Install</a></div>
</article>
<article class="fw"><a href="grav/"><img src="grav/img/grav-device.png" alt="the FM-1 running GRAV-1"></a>
<h3><a href="grav/">GRAV-1</a><span class="status">Experimental</span></h3>
<p class="what">A star and its planets</p>
<p>Slow, dark, spacey ambient: a star of coloured noise and up to six planets.</p>
<div class="go"><a href="grav/">About GRAV-1</a><a href="grav/emu/">Try in browser</a><a href="grav/install/">Install</a></div>
</article>
</div>
"""

private const val INSTALL_HTML = """
<html><head>
<script type="application/json" id="fi-releases">
[{"version":"FM-1_102","device":"fm1","date":"2026-10-19","file":"/fm1/firmware/FM-1+VA-102.fwsc","sha256":"7b169a69b30570581d0ebc7b44851e3e08898d2956ade155363bcf18fac5c348","beta":false,"current":true,"n":102},{"version":"FM-1_101","file":"/fm1/firmware/FM-1+VA-101.fwsc","sha256":"b43d6fcdf5618b83e3294b84d0b177f490956b903024452d021cd7667a61f9b4","beta":false,"current":false}]
</script>
</head><body></body></html>
"""
