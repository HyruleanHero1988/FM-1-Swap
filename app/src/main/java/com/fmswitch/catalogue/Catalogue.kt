package com.fmswitch.catalogue

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

@Serializable
data class CatalogueLink(val label: String, val url: String) {
    val comparable: String
        get() = url.lowercase()
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("www.")
            .trimEnd('/')
}

@Serializable
data class CatalogueEntry(
    val id: String,
    val name: String,
    val author: String? = null,
    val kinds: List<String> = emptyList(),
    val what: String? = null,
    val version: String? = null,
    val date: String? = null,
    val beta: Boolean = false,
    val packageURL: String? = null,
    val product: String? = null,
    val size: Int? = null,
    val sha256: String? = null,
    val loader: String? = null,
    val links: List<CatalogueLink> = emptyList(),
    val stars: Int? = null,
    val installs: Int? = null,
    val pinned: Boolean = false,
    val photoURL: String? = null,
    val photoAlt: String? = null,
    /** GitHub's short About line, when the project has one. */
    val about: String? = null,
    val isOfficial: Boolean = false,
) {
    val requiresOfficialFirmware: Boolean get() = loader?.lowercase() == "mvave"
    val isDownloadable: Boolean get() = packageURL != null
}

@Serializable
data class MergedCatalogue(
    val hubUpdated: String?,
    val entries: List<CatalogueEntry>,
    val problems: List<String> = emptyList(),
    val snapshotVersion: Int = 0,
    val bundled: Boolean = false,
) {
    companion object {
        const val BUNDLED_VERSION = 1
        const val ASSET_PATH = "catalogue/snapshot.json"
    }
}

object HubFeed {
    const val DEFAULT_URL = "https://thefirmwarehub.com/catalog.json"
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(text: String, feedUrl: String = DEFAULT_URL): Pair<String?, List<CatalogueEntry>> {
        val root = json.parseToJsonElement(text).jsonObject
        val updated = root["updated"]?.jsonPrimitive?.contentOrNull
        val firmwares = root["firmwares"]?.jsonArray ?: JsonArray(emptyList())
        return updated to firmwares.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            entry(obj, feedUrl)
        }
    }

    private fun entry(obj: JsonObject, feedUrl: String): CatalogueEntry? {
        val id = obj.str("id") ?: return null
        val name = obj.str("name") ?: return null
        val pkg = obj.str("pkg") ?: return null
        val kinds = when (val kind = obj["kind"]) {
            is JsonArray -> kind.mapNotNull { it.jsonPrimitive.contentOrNull }
            else -> listOfNotNull(obj.str("kind"))
        }
        val labelled = listOf(
            "Project site" to obj.str("site"),
            "Source code" to obj.str("source"),
            "Release" to obj.str("release"),
            "Editor" to obj.str("editor"),
            "Try it in the browser" to obj.str("play"),
        )
        val links = ArrayList<CatalogueLink>()
        for ((label, address) in labelled) {
            if (address.isNullOrBlank()) continue
            val link = CatalogueLink(label, address)
            if (links.none { it.comparable == link.comparable }) links.add(link)
        }
        return CatalogueEntry(
            id = id,
            name = name,
            author = obj.str("author"),
            kinds = kinds,
            what = obj.str("what"),
            version = obj.str("version"),
            date = obj.str("date"),
            beta = obj["beta"]?.jsonPrimitive?.booleanOrNull ?: false,
            packageURL = URI(feedUrl).resolve(pkg).toString(),
            product = obj.str("product"),
            size = obj["size"]?.jsonPrimitive?.intOrNull,
            sha256 = obj.str("sha256")?.lowercase(),
            loader = obj.str("loader"),
            links = links,
            stars = obj["stars"]?.jsonPrimitive?.intOrNull,
            installs = obj["installs"]?.jsonPrimitive?.intOrNull,
        )
    }

    private fun JsonObject.str(key: String): String? {
        val value = this[key] ?: return null
        val primitive = value as? kotlinx.serialization.json.JsonPrimitive ?: return null
        if (primitive is kotlinx.serialization.json.JsonNull) return null
        return primitive.contentOrNull
    }
}

object RescueMode {
    fun summary(id: String): String? = when (id) {
        "choralroot" -> "Hold OCT− while switching on (from 0.16)"
        "dinghy", "etch", "floyd", "ghoulbox", "jangada", "sloop", "sloopbanks", "sloopdx", "winampfm", "zp12",
        "fm1-editor-clipper",
        ->
            "Hold OCT− while switching on"
        "melodee" -> "Hold OCT− while switching on (from 1.3.5)"
        "fimba", "fumi", "hyp1", "redtail-hyp", "redtail-lof", "redtail-eco", "redtail-grav" ->
            "Hold OCT− and OCT+ for 5 s while it runs"
        "felucca" -> "None: OCT− at switch-on starts it as usual (tried on 1.6.1)"
        "mvave-official" -> "None: OCT− at switch-on starts it as usual (tried on V15)"
        else -> null
    }
}

object SafetyNotice {
    const val VERSION = 2
    const val TEXT =
        "Not affiliated with M-Vave. Though this utility makes every attempt to correctly reset and safely flash your device, flashing custom firmware is at your own risk."
    const val RECOVERY =
        "If your FM-1 won't start, some firmwares can start it in USB recovery mode if you hold down the \"OCT−\" key while switching it on. Then connect it and flash a firmware from the SAVED screen. Not every firmware does this, and if yours doesn't, recovery needs a computer: SETUP says more."
    const val ROM_RECOVERY =
        "If the FM-1 won't start at all, not even in USB recovery mode, FM-SWITCH can't help. If a computer sees a USB device with UBOOT in its name (such as WL80UBOOT), the FM-1's chip is in its own download mode, and tools built on jl-uboot-tool can put the official firmware back. Some firmwares (Melodee, FuMi-1 and Hortator among them) publish one with instructions. If it never gets that far, recovery needs an FM-1 Transporter. FM-SWITCH hasn't tried any of these."
    val ROM_LINKS = listOf(
        CatalogueLink("jl-uboot-tool", "https://github.com/kagaimiq/jl-uboot-tool"),
        CatalogueLink("FM-1 Transporter", "https://github.com/kurogedelic/FM-1-transporter"),
    )
}

fun fetchBytes(url: String, maxBytes: Int? = null): ByteArray {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 20_000
        readTimeout = 60_000
        instanceFollowRedirects = true
        requestMethod = "GET"
        setRequestProperty("User-Agent", "FM-SWITCH-Android/0.1")
        if (url.contains("api.github.com")) {
            setRequestProperty("Accept", "application/vnd.github+json")
            val token = System.getenv("GITHUB_TOKEN") ?: System.getenv("GH_TOKEN")
            if (!token.isNullOrBlank()) {
                setRequestProperty("Authorization", "Bearer $token")
            }
        }
    }
    try {
        connection.inputStream.use { input ->
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code")
            if (maxBytes == null) return input.readBytes()
            val out = java.io.ByteArrayOutputStream(maxBytes.coerceAtMost(64 * 1024))
            val buf = ByteArray(8192)
            var total = 0
            while (total < maxBytes) {
                val n = input.read(buf, 0, minOf(buf.size, maxBytes - total))
                if (n <= 0) break
                out.write(buf, 0, n)
                total += n
            }
            return out.toByteArray()
        }
    } finally {
        connection.disconnect()
    }
}

fun officialCatalogueEntry(): CatalogueEntry = CatalogueEntry(
    id = "mvave-official",
    name = "FM-1 V15",
    author = "M-VAVE",
    kinds = listOf("official"),
    what = "M-VAVE's official FM-1 firmware. Bundled with the app (Apache-2.0, unmodified from fm1_open) so you can always go back.",
    version = "V15",
    product = "FM-1_015",
    size = 699_956,
    sha256 = "db1642b2b6fa5c2cccb11ffd13878068bb28601678d3644049f99dc40e7edb8a",
    isOfficial = true,
    links = listOf(
        CatalogueLink("M-VAVE downloads", "https://www.m-vave.com/download"),
        CatalogueLink("fm1_open", "https://github.com/m-vave/fm1_open"),
    ),
)
