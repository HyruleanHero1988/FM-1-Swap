package com.fmswitch.model

import android.content.Context
import android.content.SharedPreferences
import com.fmswitch.catalogue.AboutOverlay
import com.fmswitch.catalogue.CatalogueBuilder
import com.fmswitch.catalogue.CatalogueEntry
import com.fmswitch.catalogue.CatalogueRank
import com.fmswitch.catalogue.FirmwareSort
import com.fmswitch.catalogue.EditorListing
import com.fmswitch.catalogue.HubFeed
import com.fmswitch.catalogue.MergedCatalogue
import com.fmswitch.catalogue.PackageDiscovery
import com.fmswitch.catalogue.PhotoOverlay
import com.fmswitch.catalogue.RedtailListing
import com.fmswitch.catalogue.RepoAbout
import com.fmswitch.catalogue.RepoShots
import com.fmswitch.catalogue.fetchBytes
import com.fmswitch.catalogue.officialCatalogueEntry
import com.fmswitch.fm1kit.FwscFailure
import com.fmswitch.fm1kit.FwscPackage
import com.fmswitch.fm1kit.OfficialFirmware
import com.fmswitch.theme.PhosphorPreset
import com.fmswitch.theme.PhosphorTheme
import com.fmswitch.theme.invoke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import androidx.compose.ui.graphics.Color

enum class TransportKind(val id: String, val label: String) {
    USB_MIDI("usbMIDI", "USB MIDI"),
    SIMULATED("simulated", "SIMULATED FM-1"),
}

class AppSettings(private val prefs: SharedPreferences) {
    var phosphorHex: String
        get() = prefs.getString("phosphorHex", PhosphorPreset.Green.hex) ?: PhosphorPreset.Green.hex
        set(value) { prefs.edit().putString("phosphorHex", value).apply() }
    var glow: Float
        get() = prefs.getFloat("glow", 0.25f)
        set(value) { prefs.edit().putFloat("glow", value).apply() }
    var scanlines: Boolean
        get() = prefs.getBoolean("scanlines", false)
        set(value) { prefs.edit().putBoolean("scanlines", value).apply() }
    var flicker: Boolean
        get() = prefs.getBoolean("flicker", false)
        set(value) { prefs.edit().putBoolean("flicker", value).apply() }
    var phosphorPanel: Boolean
        get() = prefs.getBoolean("phosphorPanel", true)
        set(value) { prefs.edit().putBoolean("phosphorPanel", value).apply() }
    var transportKind: TransportKind
        get() = TransportKind.entries.firstOrNull { it.id == prefs.getString("transport", TransportKind.USB_MIDI.id) }
            ?: TransportKind.USB_MIDI
        set(value) { prefs.edit().putString("transport", value.id).apply() }
    var showBeta: Boolean
        get() = prefs.getBoolean("showBeta", true)
        set(value) { prefs.edit().putBoolean("showBeta", value).apply() }
    var showLinksOnly: Boolean
        get() = prefs.getBoolean("showLinksOnly", false)
        set(value) { prefs.edit().putBoolean("showLinksOnly", value).apply() }
    var autoSaveTried: Boolean
        get() = prefs.getBoolean("autoSaveTried", true)
        set(value) { prefs.edit().putBoolean("autoSaveTried", value).apply() }
    /** Off, TRY runs at the real FM-1 clock and hides the 100 / 160 / 240 buttons. */
    var chooseEmulatorClock: Boolean
        get() = prefs.getBoolean("chooseEmulatorClock", false)
        set(value) { prefs.edit().putBoolean("chooseEmulatorClock", value).apply() }
    /** Off, TRY stops when the app leaves the screen. Leaving the TRY screen always stops it. */
    var backgroundEmulator: Boolean
        get() = prefs.getBoolean("backgroundEmulator", false)
        set(value) { prefs.edit().putBoolean("backgroundEmulator", value).apply() }
    /** Catalogue ids, first in this list is the top of MY CURATION. */
    var curation: List<String>
        get() = prefs.getString("curation", null).orEmpty().lineSequence().filter { it.isNotBlank() }.toList()
        set(value) { prefs.edit().putString("curation", value.joinToString("\n")).apply() }
    var curatedOnly: Boolean
        get() = prefs.getBoolean("curatedOnly", false)
        set(value) { prefs.edit().putBoolean("curatedOnly", value).apply() }
    var firmwareSort: FirmwareSort
        get() = FirmwareSort.entries.firstOrNull { it.id == prefs.getString("firmwareSort", FirmwareSort.CURATION.id) }
            ?: FirmwareSort.CURATION
        set(value) { prefs.edit().putString("firmwareSort", value.id).apply() }
    var alwaysViaOfficial: Boolean
        get() = prefs.getBoolean("alwaysViaOfficial", false)
        set(value) { prefs.edit().putBoolean("alwaysViaOfficial", value).apply() }
    var simulatedRecoveryMode: Boolean
        get() = prefs.getBoolean("simulatedRescueMode", false)
        set(value) { prefs.edit().putBoolean("simulatedRescueMode", value).apply() }
    var simulatedCablePull: Boolean
        get() = prefs.getBoolean("simulatedCablePull", false)
        set(value) { prefs.edit().putBoolean("simulatedCablePull", value).apply() }
    var simulatedReadsBackwards: Boolean
        get() = prefs.getBoolean("simulatedReadsBackwards", false)
        set(value) { prefs.edit().putBoolean("simulatedReadsBackwards", value).apply() }
    var simulatedVerifies: Boolean
        get() = prefs.getBoolean("simulatedVerifies", false)
        set(value) { prefs.edit().putBoolean("simulatedVerifies", value).apply() }
    var acknowledgedNoticeVersion: Int
        get() = prefs.getInt("acknowledgedNoticeVersion", 0)
        set(value) { prefs.edit().putInt("acknowledgedNoticeVersion", value).apply() }

    val theme: PhosphorTheme
        get() = PhosphorTheme(
            base = Color(phosphorHex) ?: PhosphorPreset.Green.colour,
            glow = glow,
            scanlines = scanlines,
            flicker = flicker,
        )

    fun resetLook() {
        phosphorHex = PhosphorPreset.Green.hex
        glow = 0.25f
        scanlines = false
        flicker = false
        phosphorPanel = true
    }
}

@Serializable
data class StoredFirmware(
    val id: String,
    val name: String,
    val version: String,
    val author: String? = null,
    val product: String,
    val sha256: String,
    val size: Int,
    val fileName: String,
    val source: String,
    val catalogueID: String? = null,
    val packageURL: String? = null,
    val savedAt: Long,
)

/** A package ready to boot in TRY. It may live in the saved library or only in the try cache. */
data class StagedFirmware(val file: File, val sha256: String, val savedNow: Boolean = false)

@Serializable
data class FlashRecord(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val version: String,
    val product: String,
    val date: Long,
)

class FirmwareLibrary internal constructor(
    filesDir: File,
    private val cacheDir: File,
    private val bundledOfficial: () -> ByteArray,
    private val fetch: (String) -> ByteArray,
) {
    constructor(context: Context) : this(
        context.filesDir,
        context.cacheDir,
        { context.assets.open("firmware/FM-1_015.fwsc").use { it.readBytes() } },
        ::fetchBytes,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val directory = File(filesDir, "firmware").apply { mkdirs() }
    private val indexFile = File(directory, "library.json")
    var items: List<StoredFirmware> = emptyList()
        private set
    var downloading: Set<String> = emptySet()
        private set

    init {
        load()
        if (official == null) {
            runCatching { saveBundledOfficial() }
        }
    }

    val official: StoredFirmware?
        get() = items.firstOrNull { it.source == "official" && it.sha256 == OfficialFirmware.V15.sha256 }

    val sortedItems: List<StoredFirmware>
        get() = items.sortedWith(
            compareBy<StoredFirmware> { it.source != "official" }.thenBy { it.name.lowercase() },
        )

    fun stored(forEntry: CatalogueEntry): StoredFirmware? {
        val sha = forEntry.sha256
        if (sha != null) return items.firstOrNull { it.sha256 == sha }
        val url = forEntry.packageURL ?: return null
        return items.firstOrNull { it.catalogueID == forEntry.id && it.packageURL == url }
    }

    fun outdated(forEntry: CatalogueEntry): StoredFirmware? {
        if (stored(forEntry) != null) return null
        return items.firstOrNull { it.catalogueID == forEntry.id }
    }

    fun file(forItem: StoredFirmware): File = File(directory, forItem.fileName)

    fun loadPackage(item: StoredFirmware): FwscPackage = FwscPackage.read(file(item).readBytes())

    /**
     * Bytes TRY can boot. A package already in SAVED, or already fetched into the try cache, is
     * reused without touching the network. Otherwise it is downloaded once: into SAVED when
     * [keep] is set, else into the try cache only.
     */
    suspend fun stageForPlay(entry: CatalogueEntry, keep: Boolean = false): StagedFirmware = withContext(Dispatchers.IO) {
        stored(entry)?.let { return@withContext StagedFirmware(file(it), it.sha256) }
        if (entry.isOfficial) {
            official?.let { return@withContext StagedFirmware(file(it), it.sha256) }
        }
        val cached = entry.sha256?.let { cachedPackage(it) }
        if (cached != null && !keep) return@withContext StagedFirmware(tryCacheFile(cached.second.sha256), cached.second.sha256)
        val (bytes, package_) = cached ?: fetchChecked(entry)
        if (keep) {
            val item = saveFromCatalogue(entry, bytes, package_)
            StagedFirmware(file(item), item.sha256, savedNow = true)
        } else {
            StagedFirmware(writeTryCache(bytes, package_.sha256), package_.sha256)
        }
    }

    suspend fun download(entry: CatalogueEntry): StoredFirmware = withContext(Dispatchers.IO) {
        downloading = downloading + entry.id
        try {
            val cached = entry.sha256?.let { cachedPackage(it) }
            val (bytes, package_) = cached ?: fetchChecked(entry)
            saveFromCatalogue(entry, bytes, package_)
        } finally {
            downloading = downloading - entry.id
        }
    }

    private fun saveFromCatalogue(entry: CatalogueEntry, bytes: ByteArray, package_: FwscPackage): StoredFirmware {
        val url = entry.packageURL ?: error("No downloadable file is known for this firmware.")
        return save(
            bytes,
            package_,
            name = entry.name,
            version = entry.version ?: package_.product,
            author = entry.author,
            source = if (entry.isOfficial) "official" else "catalogue",
            catalogueID = entry.id,
            packageURL = url,
        )
    }

    private fun fetchChecked(entry: CatalogueEntry): Pair<ByteArray, FwscPackage> {
        val url = entry.packageURL ?: error("No downloadable file is known for this firmware.")
        val bytes = PackageDiscovery.firmwareBytes(fetch(url))
        val package_ = FwscPackage.read(bytes)
        if (entry.sha256 != null && (bytes.size != (entry.size ?: bytes.size) || package_.sha256 != entry.sha256)) {
            error("The downloaded file doesn't match its published size or checksum, so it was not saved.")
        }
        if (entry.product != null && package_.product != entry.product) {
            error("The file reports ${package_.product}, but the catalogue says ${entry.product}. Not saved.")
        }
        return bytes to package_
    }

    private fun tryCacheFile(sha256: String): File =
        File(File(cacheDir, "try-packages"), "$sha256.fwsc")

    /** A complete try-cache file whose checksum matches [sha256], or null. */
    private fun cachedPackage(sha256: String): Pair<ByteArray, FwscPackage>? {
        val file = tryCacheFile(sha256)
        if (!file.isFile) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        val package_ = runCatching { FwscPackage.read(bytes) }.getOrNull() ?: return null
        if (package_.sha256 != sha256) return null
        return bytes to package_
    }

    private fun writeTryCache(bytes: ByteArray, sha256: String): File {
        val dir = File(cacheDir, "try-packages").apply { mkdirs() }
        val out = File(dir, "$sha256.fwsc")
        val partial = File(dir, "$sha256.fwsc.part")
        partial.writeBytes(bytes)
        if (!partial.renameTo(out)) {
            out.writeBytes(bytes)
            partial.delete()
        }
        return out
    }

    fun importBytes(bytes: ByteArray, displayName: String = "Imported"): StoredFirmware {
        val package_ = FwscPackage.read(bytes)
        val official = OfficialFirmware.matching(package_)
        return save(
            bytes,
            package_,
            name = official?.name ?: displayName,
            version = official?.name?.substringAfter(" ") ?: package_.product,
            source = if (official == OfficialFirmware.V15) "official" else "imported",
        )
    }

    fun remove(item: StoredFirmware) {
        if (item.source == "official") return
        file(item).delete()
        items = items.filterNot { it.id == item.id }
        persist()
    }

    fun clear() {
        val keep = official
        directory.listFiles()?.forEach { file ->
            if (keep == null || file.name != keep.fileName) file.delete()
        }
        items = listOfNotNull(keep)
        persist()
        if (official == null) runCatching { saveBundledOfficial() }
    }

    private fun saveBundledOfficial() {
        val bytes = bundledOfficial()
        val package_ = FwscPackage.read(bytes)
        if (OfficialFirmware.matching(package_) != OfficialFirmware.V15) error("bundled V15 is not official")
        save(bytes, package_, "FM-1 V15", "V15", "M-VAVE", "official", "mvave-official", null)
    }

    private fun save(
        bytes: ByteArray,
        package_: FwscPackage,
        name: String,
        version: String,
        author: String? = null,
        source: String,
        catalogueID: String? = null,
        packageURL: String? = null,
    ): StoredFirmware {
        val fileName = "${package_.sha256.take(16)}.fwsc"
        File(directory, fileName).writeBytes(bytes)
        items = items.filterNot { it.sha256 == package_.sha256 }
        val item = StoredFirmware(
            id = package_.sha256,
            name = name,
            version = version,
            author = author,
            product = package_.product,
            sha256 = package_.sha256,
            size = bytes.size,
            fileName = fileName,
            source = source,
            catalogueID = catalogueID,
            packageURL = packageURL,
            savedAt = System.currentTimeMillis(),
        )
        items = items + item
        persist()
        return item
    }

    private fun load() {
        if (!indexFile.exists()) return
        items = runCatching { json.decodeFromString<List<StoredFirmware>>(indexFile.readText()) }.getOrDefault(emptyList())
            .filter { file(it).exists() }
    }

    private fun persist() {
        indexFile.writeText(json.encodeToString(items))
    }
}

class CatalogueStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val cacheFile = File(context.filesDir, "catalogue.json")
    var catalogue: MergedCatalogue? = null
        private set
    var isRefreshing: Boolean = false
        private set
    var errorMessage: String? = null
        private set

    init {
        loadCached()
    }

    private val photoOverlayText: String by lazy { assetText(PhotoOverlay.ASSET_PATH) }
    private val aboutOverlayText: String by lazy { assetText(AboutOverlay.ASSET_PATH) }

    private fun assetText(path: String): String = runCatching {
        context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }
    }.getOrDefault("")

    val entries: List<CatalogueEntry>
        get() {
            val listed = catalogue?.entries.orEmpty()
            val withOfficial = if (listed.any { it.isOfficial }) listed else listOf(officialCatalogueEntry()) + listed
            val described = AboutOverlay.apply(withOfficial, aboutOverlayText)
            return CatalogueRank.ordered(PhotoOverlay.apply(described, photoOverlayText))
        }

    fun entry(id: String): CatalogueEntry? = entries.firstOrNull { it.id == id }

    fun entriesMatching(product: String): List<CatalogueEntry> = entries.filter { it.product == product }

    suspend fun refresh(onUpdate: () -> Unit = {}) {
        if (isRefreshing) return
        isRefreshing = true
        errorMessage = null
        try {
            val previous = catalogue?.entries.orEmpty()
            val (hubText, editorHtml, redtailHtml) = coroutineScope {
                val hub = async(Dispatchers.IO) {
                    runCatching { String(fetchBytes(HubFeed.DEFAULT_URL), Charsets.UTF_8) }.getOrNull()
                }
                val editor = async(Dispatchers.IO) {
                    runCatching { String(fetchBytes(EditorListing.DEFAULT_URL), Charsets.UTF_8) }.getOrNull()
                }
                val redtail = async(Dispatchers.IO) {
                    runCatching { String(fetchBytes(RedtailListing.DEFAULT_URL), Charsets.UTF_8) }.getOrNull()
                }
                Triple(hub.await(), editor.await(), redtail.await())
            }
            val hub = hubText?.let { runCatching { HubFeed.decode(it) }.getOrNull() }
            val editor = editorHtml?.let { runCatching { EditorListing.parse(it) }.getOrNull() }
            val redtail = redtailHtml?.let { runCatching { RedtailListing.parse(it) }.getOrNull() }
            val problems = ArrayList<String>()
            if (hub == null) problems += "The firmware hub couldn't be read."
            if (editor == null || editor.entries.isEmpty()) problems += "fm1-editor.com couldn't be read."
            if (redtail == null || redtail.entries.isEmpty()) problems += "The RedtailWorks pack couldn't be read."
            if (hub == null && (editor == null || editor.entries.isEmpty())) {
                error("Couldn't reach the firmware hub or fm1-editor.com.")
            }
            val hubEntries = hub?.second.orEmpty().filterNot { it.isOfficial || it.id == "mvave-official" }
            val merged = CatalogueBuilder.merge(
                listOf(officialCatalogueEntry()) + hubEntries,
                editor?.entries.orEmpty(),
                redtail = redtail?.entries.orEmpty(),
            )
            catalogue = MergedCatalogue(hub?.first, merged, problems)
            if (problems.isNotEmpty()) errorMessage = problems.joinToString(" ")
            onUpdate()
            val resolved = PackageDiscovery.discoverMissing(merged, previous)
            val photographed = RepoShots.fill(resolved, previous)
            val described = RepoAbout.fill(AboutOverlay.apply(photographed, aboutOverlayText), previous)
            val built = MergedCatalogue(
                hubUpdated = hub?.first,
                entries = described,
                problems = problems,
                snapshotVersion = MergedCatalogue.BUNDLED_VERSION,
                bundled = false,
            )
            catalogue = built
            withContext(Dispatchers.IO) { cacheFile.writeText(json.encodeToString(built)) }
            if (problems.isNotEmpty()) errorMessage = problems.joinToString(" ")
        } catch (_: Throwable) {
            errorMessage = if (catalogue == null || catalogue?.bundled == true) {
                "Couldn't refresh the list. Showing the copy shipped with the app."
            } else {
                "Couldn't refresh; showing the list saved earlier."
            }
        } finally {
            isRefreshing = false
        }
    }

    private fun loadCached() {
        val bundled = loadBundled()
        val cached = readCache()
        catalogue = when {
            cached != null &&
                cached.entries.size > 1 &&
                cached.snapshotVersion >= (bundled?.snapshotVersion ?: 0) -> cached
            bundled != null -> bundled
            cached != null -> cached
            else -> null
        }
    }

    private fun readCache(): MergedCatalogue? {
        if (!cacheFile.exists()) return null
        val text = cacheFile.readText()
        runCatching { json.decodeFromString<MergedCatalogue>(text) }.getOrNull()?.let { return it }
        return runCatching {
            val (updated, hub) = HubFeed.decode(text)
            MergedCatalogue(updated, listOf(officialCatalogueEntry()) + hub, emptyList())
        }.getOrNull()
    }

    private fun loadBundled(): MergedCatalogue? = runCatching {
        val text = context.assets.open(MergedCatalogue.ASSET_PATH).bufferedReader(Charsets.UTF_8).use { it.readText() }
        json.decodeFromString<MergedCatalogue>(text)
    }.getOrNull()
}

class FlashHistory(private val prefs: SharedPreferences) {
    private val json = Json { ignoreUnknownKeys = true }
    var records: List<FlashRecord> = emptyList()
        private set

    init {
        records = prefs.getString("flashHistory", null)?.let {
            runCatching { json.decodeFromString<List<FlashRecord>>(it) }.getOrNull()
        }.orEmpty()
    }

    fun lastInstall(matching: String): FlashRecord? = records.firstOrNull()?.takeIf { it.product == matching }

    fun record(item: StoredFirmware) {
        records = (listOf(
            FlashRecord(name = item.name, version = item.version, product = item.product, date = System.currentTimeMillis()),
        ) + records).take(30)
        prefs.edit().putString("flashHistory", json.encodeToString(records)).apply()
    }
}

class LibraryException(message: String) : Exception(message)

fun Throwable.userMessage(): String = when (this) {
    is FwscFailure -> message ?: toString()
    else -> message ?: toString()
}
