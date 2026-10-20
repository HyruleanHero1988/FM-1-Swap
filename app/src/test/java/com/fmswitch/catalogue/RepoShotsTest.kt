package com.fmswitch.catalogue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RepoShotsTest {
    private val readmeUrl = "https://raw.githubusercontent.com/isod89/sloop-fm1/main/README.md"

    @Test
    fun sloopUsesTheFirstImageUnderScreenshotsNotTheLogo() {
        val shot = RepoShots.pick(SLOOP_README, readmeUrl)
        assertEquals(
            "https://raw.githubusercontent.com/isod89/sloop-fm1/main/assets/screens/sloop-2.6-screens.png",
            shot?.url,
        )
        assertEquals("SLOOP 2.6 screens on the FM-1", shot?.alt)
        assertTrue(shot!!.fromSection)
    }

    @Test
    fun withoutAScreenshotsHeadingTheFirstRealPictureWins() {
        val markdown = """
            <img src="assets/logo/mark.png" alt="logo">
            ![the panel](docs/panel.png)
        """.trimIndent()
        val shot = RepoShots.pick(markdown, readmeUrl)
        assertEquals(
            "https://raw.githubusercontent.com/isod89/sloop-fm1/main/docs/panel.png",
            shot?.url,
        )
        assertFalse(shot!!.fromSection)
        assertNull(RepoShots.pick(markdown, readmeUrl, sectionOnly = true))
    }

    @Test
    fun aGithubBlobLinkBecomesTheRawFile() {
        val markdown = "![screen](https://github.com/isod89/sloop-fm1/blob/main/assets/screens/one.png)"
        assertEquals(
            "https://raw.githubusercontent.com/isod89/sloop-fm1/main/assets/screens/one.png",
            RepoShots.pick(markdown, readmeUrl)?.url,
        )
    }

    @Test
    fun aScreenshotsImageReplacesAnotherPhotoAndASavedScanSticks() {
        val entry = CatalogueEntry(
            id = "sloop",
            name = "SLOOP",
            photoURL = "https://fm1-editor.com/assets/firmware-sloop-460.webp",
            photoAlt = "listing",
        )
        val shot = RepoShots.Shot(
            "https://raw.githubusercontent.com/isod89/sloop-fm1/main/assets/screens/sloop-2.6-screens.png",
            "SLOOP 2.6 screens on the FM-1",
            fromSection = true,
        )
        val chosen = RepoShots.choose(entry, previous = null, shot = shot)
        assertEquals(shot.url, chosen.photoURL)
        val again = RepoShots.choose(
            entry,
            previous = chosen,
            shot = RepoShots.Shot("https://example.com/other.png", null, fromSection = true),
        )
        assertEquals(shot.url, again.photoURL)
        assertEquals(shot.alt, again.photoAlt)
    }

    @Test
    fun overlayFillsAMissingShotAndLeavesOneTheListingAlreadyHas() {
        val text = """{"sloop":{"photoURL":"https://example.com/s.png","photoAlt":"grid"}}"""
        val filled = PhotoOverlay.apply(
            listOf(
                CatalogueEntry(id = "sloop", name = "SLOOP"),
                CatalogueEntry(id = "hyp1", name = "HYP-1", photoURL = "https://keep.example/a.png"),
            ),
            text,
        )
        assertEquals("https://example.com/s.png", filled[0].photoURL)
        assertEquals("grid", filled[0].photoAlt)
        assertEquals("https://keep.example/a.png", filled[1].photoURL)
    }

    @Test
    fun anOrdinaryPictureDoesNotReplaceAPhotoTheListingAlreadyHas() {
        val entry = CatalogueEntry(id = "hyp1", name = "HYP-1", photoURL = "https://rustypawz.github.io/x.png")
        val shot = RepoShots.Shot("https://raw.githubusercontent.com/a/b/main/x.png", null, fromSection = false)
        assertEquals(entry.photoURL, RepoShots.choose(entry, null, shot).photoURL)
    }
}

private const val SLOOP_README = """
<p align="center"><img src="assets/logo/sloop-logo.png" alt="SLOOP" width="420"></p>

## Screenshots

<p align="center"><img src="assets/screens/sloop-2.6-screens.png" alt="SLOOP 2.6 screens on the FM-1" width="760"></p>
<p align="center"><img src="assets/screens/editor-sound.png" alt="SLOOP web editor" width="760"></p>

## What it is
"""
