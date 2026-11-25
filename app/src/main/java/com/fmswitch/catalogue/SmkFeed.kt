package com.fmswitch.catalogue

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI

/**
 * SMK-37 catalogue from genkigenki's install site (same Pro / Elite / MKE packages the browser uses).
 */
object SmkFeed {
    const val DEFAULT_URL = "https://genkigenki.github.io/smk37/"
    const val ASSET_PATH = "catalogue/smk.json"

    private val json = Json { ignoreUnknownKeys = true }

    private val authors = mapOf(
        "makkuro37" to "genkigenki",
        "vector37" to "genkigenki",
        "speak37" to "genkigenki",
        "erik37" to "genkigenki",
        "snoop37" to "isod89 / genkigenki",
        "x0x37" to "Charles Vestal / genkigenki",
        "fomni37" to "Charles Vestal / genkigenki",
        "maku37" to "okumurari / genkigenki",
        "rainbow37" to "joshbartlettband-web / genkigenki",
        "ghoulbox" to "Jason Persinger / genkigenki",
        "choralroot" to "Quixotic7 / genkigenki",
        "orbit" to "dspaudio / genkigenki",
        "amb" to "Charles Vestal / genkigenki",
        "polly" to "Charles Vestal / genkigenki",
        "winamp" to "Hericdk / genkigenki",
        "dub" to "sbegueria / genkigenki",
        "ketch" to "risseraka / genkigenki",
        "melodee" to "keremimo / genkigenki",
        "fmel" to "Antonio Altea / genkigenki",
        "frodhes" to "Leonardo Mirabal / genkigenki",
        "zen" to "aceyprime / genkigenki",
        "obervave" to "zvenson / genkigenki",
        "repair" to "genkigenki",
    )

    private val kinds = mapOf(
        "snoop37" to listOf("groovebox"),
        "x0x37" to listOf("drums"),
        "fomni37" to listOf("chords"),
        "maku37" to listOf("ambient"),
        "rainbow37" to listOf("other"),
        "ghoulbox" to listOf("synth"),
        "choralroot" to listOf("chords"),
        "orbit" to listOf("groovebox"),
        "amb" to listOf("ambient"),
        "polly" to listOf("other"),
        "winamp" to listOf("groovebox"),
        "dub" to listOf("groovebox"),
        "ketch" to listOf("tracker"),
        "melodee" to listOf("synth"),
        "fmel" to listOf("synth"),
        "frodhes" to listOf("synth"),
        "zen" to listOf("ambient"),
        "obervave" to listOf("synth"),
        "makkuro37" to listOf("synth"),
        "vector37" to listOf("synth"),
        "speak37" to listOf("synth"),
        "erik37" to listOf("synth"),
        "repair" to listOf("other"),
    )

    fun parsePage(html: String, baseUrl: String = DEFAULT_URL): List<CatalogueEntry> {
        val raw = extractFwArray(html) ?: return emptyList()
        val root = json.parseToJsonElement(raw)
        val array = root as? JsonArray ?: return emptyList()
        val base = URI(baseUrl)
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            entry(obj, base)
        }
    }

    fun merge(bundled: List<CatalogueEntry>, fresh: List<CatalogueEntry>): List<CatalogueEntry> {
        if (fresh.isEmpty()) return bundled
        val byId = LinkedHashMap<String, CatalogueEntry>()
        for (entry in bundled) byId[entry.id] = entry
        for (entry in fresh) {
            val previous = byId[entry.id]
            byId[entry.id] = if (previous == null) {
                entry
            } else {
                entry.copy(
                    links = (entry.links + previous.links).distinctBy { it.comparable },
                    what = entry.what ?: previous.what,
                    allowSiblingSmkModel = entry.allowSiblingSmkModel || previous.allowSiblingSmkModel,
                    isOfficial = previous.isOfficial || entry.isOfficial,
                )
            }
        }
        return byId.values.toList()
    }

    private fun entry(obj: JsonObject, base: URI): CatalogueEntry? {
        val id = obj.str("id") ?: return null
        if (id.startsWith("fm1-")) return null
        val name = obj.str("name") ?: return null
        val variants = obj["v"] as? JsonObject ?: return null
        val models = LinkedHashMap<String, SmkModelPackage>()
        for (key in listOf("pro", "elite", "mke")) {
            val pack = variants[key] as? JsonObject ?: continue
            val pkg = pack.str("pkg") ?: continue
            val product = pack.str("product") ?: continue
            val url = if (pkg.startsWith("http")) pkg else base.resolve(pkg).toString()
            models[key] = SmkModelPackage(
                product = product,
                packageURL = url,
                sha256 = pack.str("sha256")?.lowercase(),
                size = pack["size"]?.jsonPrimitive?.intOrNull,
            )
        }
        if (models.isEmpty()) return null
        val pro = models["pro"]
        return CatalogueEntry(
            id = id,
            name = name,
            author = authors[id] ?: "community",
            kinds = kinds[id] ?: listOf("synth"),
            what = "SMK-37 build. Connect your keyboard: SAVE / FLASH picks the Pro, Elite, or MKE-P37 package automatically. Starrykey 37 Play uses the Pro package.",
            version = obj.str("version"),
            beta = name.contains("beta", ignoreCase = true) || (obj.str("version")?.contains("beta", ignoreCase = true) == true),
            packageURL = pro?.packageURL,
            product = pro?.product,
            size = pro?.size,
            sha256 = pro?.sha256,
            platforms = listOf(DevicePlatform.SMK37),
            allowSiblingSmkModel = true,
            smkModels = models,
            links = listOf(
                CatalogueLink("Install page", DEFAULT_URL),
                CatalogueLink("The Firmware Hub (SMK-37)", "https://thefirmwarehub.com/smk37/"),
            ),
        )
    }

    private fun extractFwArray(html: String): String? {
        val marker = "const FW = ["
        val start = html.indexOf(marker)
        if (start < 0) return null
        var i = start + "const FW = ".length
        var depth = 0
        val from = i
        while (i < html.length) {
            when (html[i]) {
                '[' -> depth++
                ']' -> {
                    depth--
                    if (depth == 0) return html.substring(from, i + 1)
                }
            }
            i++
        }
        return null
    }

    private fun JsonObject.str(key: String): String? {
        val value = this[key] ?: return null
        if (value is JsonNull) return null
        val primitive = value as? JsonPrimitive ?: return null
        return primitive.contentOrNull
    }
}
