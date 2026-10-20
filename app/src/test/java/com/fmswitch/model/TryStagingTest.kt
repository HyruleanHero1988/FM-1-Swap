package com.fmswitch.model

import com.fmswitch.catalogue.CatalogueEntry
import com.fmswitch.fm1kit.FwscPackage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class TryStagingTest {
    @get:Rule val temp = TemporaryFolder()

    private val bytes = File("../fm1kit/src/test/resources/FM-1_015.fwsc").readBytes()
    private val package_ = FwscPackage.read(bytes)
    private val entry = CatalogueEntry(
        id = "hub-sample",
        name = "Sample",
        author = "Someone",
        version = "1.0",
        packageURL = "https://example.invalid/sample.fwsc",
        product = package_.product,
        size = bytes.size,
        sha256 = package_.sha256,
    )
    private var fetches = 0
    private val files by lazy { temp.newFolder("files") }
    private val cache by lazy { temp.newFolder("cache") }

    private fun library() = FirmwareLibrary(files, cache, { error("no bundled V15 in this test") }) {
        fetches++
        bytes
    }

    @Test
    fun keepingPutsTheTriedPackageInSavedAndNeverFetchesItAgain() = runTest {
        val library = library()
        val staged = library.stageForPlay(entry, keep = true)
        assertTrue(staged.savedNow)
        val saved = library.stored(entry)!!
        assertEquals("catalogue", saved.source)
        assertEquals(entry.id, saved.catalogueID)
        assertEquals(library.file(saved), staged.file)
        assertFalse(library.stageForPlay(entry, keep = true).savedNow)
        assertFalse(library().stageForPlay(entry, keep = false).savedNow)
        assertEquals(1, fetches)
    }

    @Test
    fun withoutKeepingTheTryCacheIsReusedAndSavedStaysEmpty() = runTest {
        val library = library()
        val first = library.stageForPlay(entry, keep = false)
        assertFalse(first.savedNow)
        assertNull(library.stored(entry))
        assertEquals(first.file, library.stageForPlay(entry, keep = false).file)
        assertEquals(1, fetches)
    }

    @Test
    fun savingAfterAnUnkeptTryUsesTheCachedCopy() = runTest {
        val library = library()
        library.stageForPlay(entry, keep = false)
        assertTrue(library.stageForPlay(entry, keep = true).savedNow)
        assertNotNull(library.stored(entry))
        library.download(entry)
        assertEquals(1, fetches)
    }

    @Test
    fun savingWhileATryPlaysLeavesItsFileInPlace() = runTest {
        val library = library()
        val tried = library.stageForPlay(entry, keep = false)
        library.download(entry)
        assertTrue(tried.file.isFile)
        assertEquals(1, fetches)
    }
}
