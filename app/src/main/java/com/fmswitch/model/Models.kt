package com.fmswitch.model

import android.content.Context
import android.content.SharedPreferences
import com.fmswitch.catalogue.CatalogueBuilder
import com.fmswitch.catalogue.CatalogueEntry
import com.fmswitch.catalogue.CatalogueRank
import com.fmswitch.catalogue.EditorListing
import com.fmswitch.catalogue.HubFeed
import com.fmswitch.catalogue.MergedCatalogue
import com.fmswitch.catalogue.PackageDiscovery
import com.fmswitch.catalogue.RedtailListing
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

@Serializable
data class FlashRecord(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val version: String,
    val product: String,
    val date: Long,
)

class FirmwareLibrary(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val directory = File(context.filesDir, "firmware").apply { mkdirs() }
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

    suspend fun download(entry: CatalogueEntry): StoredFirmware = withContext(Dispatchers.IO) {
        val url = entry.packageURL ?: error("No downloadable file is known for this firmware.")
        downloading = downloading + entry.id
        try {
            val bytes = PackageDiscovery.firmwareBytes(fetchBytes(url))
            val package_ = FwscPackage.read(bytes)
            if (entry.sha256 != null && (bytes.size != (entry.size ?: bytes.size) || package_.sha256 != entry.sha256)) {
                error("The downloaded file doesn't match its published size or checksum, so it was not saved.")
            }
            if (entry.product != null && package_.product != entry.product) {
                error("The file reports ${package_.product}, but the catalogue says ${entry.product}. Not saved.")
            }
            save(
                bytes,
                package_,
                name = entry.name,
                version = entry.version ?: package_.product,
                author = entry.author,
                source = if (entry.isOfficial) "official" else "catalogue",
                catalogueID = entry.id,
                packageURL = url,
            )
        } finally {
            downloading = downloading - entry.id
        }
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
        val bytes = context.assets.open("firmware/FM-1_015.fwsc").use { it.readBytes() }
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

    val entries: List<CatalogueEntry>
        get() {
            val listed = catalogue?.entries.orEmpty()
            val withOfficial = if (listed.any { it.isOfficial }) listed else listOf(officialCatalogueEntry()) + listed
            return CatalogueRank.ordered(withOfficial)
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
            val built = MergedCatalogue(
                hubUpdated = hub?.first,
                entries = resolved,
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
