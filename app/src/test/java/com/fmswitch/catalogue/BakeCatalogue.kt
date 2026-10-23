package com.fmswitch.catalogue

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * Rebuilds [MergedCatalogue.ASSET_PATH] from the live hub, fm1-editor.com, and
 * RedtailWorks pack. Not run in CI. From the repo root:
 *
 *     BAKE_CATALOGUE=true ./gradlew :app:testDebugUnitTest --tests com.fmswitch.catalogue.BakeCatalogue
 */
class BakeCatalogue {
    @Test
    fun writeBundledSnapshot() = runBlocking {
        Assume.assumeTrue("set BAKE_CATALOGUE=true to rebuild the shipped list", System.getenv("BAKE_CATALOGUE") == "true")
        val hubText = readOrFetch("/tmp/fm-cat/catalog.json", HubFeed.DEFAULT_URL)
        val editorHtml = readOrFetch("/tmp/fm-cat/editor.html", EditorListing.DEFAULT_URL)
        val redtailHtml = readOrFetch("/tmp/fm-cat/redtail.html", RedtailListing.DEFAULT_URL)
        val hub = HubFeed.decode(hubText)
        val editor = EditorListing.parse(editorHtml)
        val redtail = RedtailListing.parse(redtailHtml)
        val hubEntries = hub.second.filterNot { it.isOfficial || it.id == "mvave-official" }
        val merged = CatalogueBuilder.merge(
            listOf(officialCatalogueEntry()) + hubEntries,
            editor.entries,
            redtail = redtail.entries,
        )
        val resolved = PackageDiscovery.discoverMissing(merged, emptyList())
        val photographed = HubShots.fill(RepoShots.fill(resolved, emptyList()))
        val snapshot = MergedCatalogue(
            hubUpdated = hub.first,
            entries = CatalogueRank.ordered(photographed),
            problems = emptyList(),
            snapshotVersion = MergedCatalogue.BUNDLED_VERSION,
            bundled = true,
        )
        val json = Json { prettyPrint = true; encodeDefaults = false; ignoreUnknownKeys = true }
        val out = File(moduleRoot(), "src/main/assets/${MergedCatalogue.ASSET_PATH}")
        out.parentFile?.mkdirs()
        out.writeText(json.encodeToString(snapshot) + "\n")
        println("Wrote ${out.canonicalPath} (${snapshot.entries.size} firmwares, ${snapshot.entries.count { it.packageURL != null }} with packages)")
    }

    /** Fills photos on the snapshot already in the repo, without rebuilding the list. */
    @Test
    fun fillExistingSnapshot() = runBlocking {
        Assume.assumeTrue("set FILL_PHOTOS=true to scan GitHub READMEs", System.getenv("FILL_PHOTOS") == "true")
        val json = Json { prettyPrint = true; encodeDefaults = false; ignoreUnknownKeys = true }
        val file = File(moduleRoot(), "src/main/assets/${MergedCatalogue.ASSET_PATH}")
        val current = json.decodeFromString<MergedCatalogue>(file.readText())
        val photographed = HubShots.fill(RepoShots.fill(current.entries, emptyList()))
        val snapshot = current.copy(entries = photographed)
        file.writeText(json.encodeToString(snapshot) + "\n")
        val gained = photographed.count { it.photoURL != null } - current.entries.count { it.photoURL != null }
        println("Photos now ${photographed.count { it.photoURL != null }} of ${photographed.size} (+$gained)")
    }

    private fun readOrFetch(path: String, url: String): String {
        val file = File(path)
        if (file.exists() && file.length() > 0) return file.readText()
        return String(fetchBytes(url), Charsets.UTF_8)
    }

    private fun moduleRoot(): File {
        val cwd = File(".").canonicalFile
        if (File(cwd, "src/main/assets").isDirectory) return cwd
        return File(cwd, "app")
    }
}
