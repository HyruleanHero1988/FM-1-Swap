package com.fmswitch.catalogue

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogueSearchTest {
    private val hyp = CatalogueEntry(
        id = "redtail-hyp1",
        name = "HYP-1",
        author = "RedtailWorks",
        what = "Hyper-saw synth",
        links = listOf(
            CatalogueLink("Project site", "https://rustypawz.github.io/fm1-HYP1/"),
            CatalogueLink("Source", "https://github.com/RustyPawz/fm1-HYP1"),
        ),
    )
    private val x0x = CatalogueEntry(
        id = "x0x",
        name = "X0X",
        author = "Charles Vestal",
        links = listOf(CatalogueLink("Project site", "https://github.com/charlesvestal/fm1-x0x")),
    )
    private val rene = CatalogueEntry(id = "rene", name = "Pocket", author = "René Bohne")
    private val v15 = CatalogueEntry(id = "mvave-official", name = "FM-1 V15", author = "M-VAVE")

    @Test
    fun matchesTheAuthorShownOnTheRow() {
        assertEquals(listOf(hyp), search("redtail"))
        assertEquals(listOf(x0x), search("vestal"))
        assertEquals(listOf(x0x), search("CHARLES VESTAL"))
    }

    @Test
    fun matchesTheGithubAccountBehindTheAuthor() {
        assertEquals(listOf(hyp), search("rustypawz"))
        assertEquals(listOf(x0x), search("charlesvestal"))
    }

    @Test
    fun ignoresAccentsSpacingAndPunctuation() {
        assertEquals(listOf(rene), search("rene"))
        assertEquals(listOf(rene), search("René"))
        assertEquals(listOf(v15), search("mvave"))
        assertEquals(listOf(hyp), search("hyp1"))
    }

    @Test
    fun everyWordMustMatchButCanComeFromDifferentFields() {
        assertEquals(listOf(hyp), search("rustypawz saw"))
        assertEquals(listOf(x0x), search("x0x charles"))
        assertTrue(search("x0x redtail").isEmpty())
    }

    @Test
    fun blankShowsEverything() {
        assertEquals(4, search("  ").size)
        assertFalse(CatalogueSearch.matches(v15, "felucca"))
    }

    @Test
    fun accountsComeFromGithubAndPagesLinks() {
        assertEquals(setOf("rustypawz"), CatalogueSearch.accounts(hyp))
    }

    private fun search(query: String) = listOf(hyp, x0x, rene, v15).filter { CatalogueSearch.matches(it, query) }
}
