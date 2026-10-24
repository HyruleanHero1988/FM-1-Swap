package com.fmswitch.update

import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A published release APK. [version] is the release tag, such as v0.6.2. */
data class AppRelease(
    val version: String,
    val apkUrl: String,
    val size: Long,
)

const val RELEASES_LATEST = "https://api.github.com/repos/HyruleanHero1988/FM-1-Swap/releases/latest"
const val RELEASE_ASSET = "FM-1-Swap.apk"

/** One look at the release, then about half a day before the next. */
const val CHECK_INTERVAL_MS = 12L * 60 * 60 * 1000

const val MAX_APK_BYTES = 32 * 1024 * 1024

const val PREF_OFFER_VERSION = "updateOfferVersion"
const val PREF_OFFER_URL = "updateOfferUrl"
const val PREF_OFFER_SIZE = "updateOfferSize"
const val PREF_DISMISSED_VERSION = "updateDismissedVersion"
const val PREF_CHECKED_AT = "updateCheckedAt"
const val PREF_INSTALL_STATUS = "updateInstallStatus"

const val INSTALL_ABORTED = "aborted"
const val INSTALL_FAILED = "failed"

const val UPDATE_DOWNLOAD_FAILED = "Couldn't download the update."
const val UPDATE_SIZE_MISMATCH = "The download did not match the published file."
const val UPDATE_INSTALL_FAILED = "Android did not install the update."
const val UPDATE_PERMISSION_COPY = "Allow FM-1 Swap to install its update. Android asks this once."

sealed class AppUpdatePhase {
    data object Idle : AppUpdatePhase()
    data object Downloading : AppUpdatePhase()
    data object NeedsPermission : AppUpdatePhase()
    data object Installing : AppUpdatePhase()
    data class Failed(val message: String) : AppUpdatePhase()
}

private const val REDIRECT_LIMIT = 5

/** Numeric pieces of a version, ignoring a leading v and anything after a hyphen. */
fun versionParts(version: String): List<Int> {
    val bare = version.trim().removePrefix("v").removePrefix("V").substringBefore("-")
    if (bare.isEmpty()) return listOf(0)
    return bare.split(".").map { piece ->
        piece.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
    }
}

/** True when [candidate] is a higher version than [current]. 0.10.0 is higher than 0.9.0. */
fun isNewerVersion(candidate: String, current: String): Boolean {
    val left = versionParts(candidate)
    val right = versionParts(current)
    val count = maxOf(left.size, right.size)
    for (index in 0 until count) {
        val a = left.getOrElse(index) { 0 }
        val b = right.getOrElse(index) { 0 }
        if (a != b) return a > b
    }
    return false
}

fun displayVersion(version: String): String =
    version.trim().removePrefix("v").removePrefix("V")

/**
 * An APK URL we will hand to the installer. Release downloads live on github.com
 * and then on a githubusercontent host.
 */
fun trustedApkUrl(url: String): Boolean = trustedHost(url) { host ->
    host == "github.com" || host.endsWith(".githubusercontent.com")
}

/** Release JSON and the APK download, including the API host that publishes the tag. */
fun trustedUpdateUrl(url: String): Boolean = trustedHost(url) { host ->
    host == "api.github.com" || host == "github.com" || host.endsWith(".githubusercontent.com")
}

private fun trustedHost(url: String, allow: (String) -> Boolean): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    if (uri.scheme != "https") return false
    if (!uri.userInfo.isNullOrEmpty()) return false
    val host = uri.host?.lowercase() ?: return false
    return allow(host)
}

/** The release asset named FM-1-Swap.apk, or null when this payload is not one we install. */
fun appRelease(json: String): AppRelease? = runCatching { parseRelease(json) }.getOrNull()

private fun parseRelease(json: String): AppRelease? {
    val root = Json.parseToJsonElement(json) as? JsonObject ?: return null
    if (root.bool("draft") || root.bool("prerelease")) return null
    val tag = root.text("tag_name")?.takeIf { it.isNotBlank() } ?: return null
    val assets = root["assets"] as? JsonArray ?: return null
    val apk = assets.mapNotNull { it as? JsonObject }
        .firstOrNull { it.text("name") == RELEASE_ASSET }
        ?: return null
    val url = apk.text("browser_download_url")?.takeIf { it.isNotBlank() } ?: return null
    val size = apk["size"]?.jsonPrimitive?.longOrNull ?: 0L
    return AppRelease(tag, url, size)
}

/**
 * The release to offer, or null when this build should stay quiet.
 * A debug install is a different package, so the release APK cannot replace it.
 */
fun offerUpdate(currentVersion: String, release: AppRelease?): AppRelease? {
    if (release == null) return null
    if (currentVersion.endsWith("-debug")) return null
    if (!trustedApkUrl(release.apkUrl)) return null
    if (!isNewerVersion(release.version, currentVersion)) return null
    return release
}

/**
 * GET [url], following redirects only while each hop stays on a trusted host.
 * The body is capped so a wrong response cannot grow without limit.
 */
fun fetchUpdateBytes(url: String, maxBytes: Int): ByteArray {
    var current = url
    repeat(REDIRECT_LIMIT) {
        if (!trustedUpdateUrl(current)) throw UpdateDownloadException(UPDATE_DOWNLOAD_FAILED)
        val connection = (URL(current).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = false
            requestMethod = "GET"
            setRequestProperty("User-Agent", "FM-1-Swap/0.6.2")
            if (current.contains("api.github.com")) {
                setRequestProperty("Accept", "application/vnd.github+json")
            }
        }
        try {
            val code = try {
                connection.responseCode
            } catch (_: FileNotFoundException) {
                404
            }
            if (code in 300..399) {
                val location = connection.getHeaderField("Location")
                    ?: throw UpdateDownloadException(UPDATE_DOWNLOAD_FAILED)
                current = URL(URL(current), location).toExternalForm()
                return@repeat
            }
            if (code !in 200..299) throw UpdateDownloadException(UPDATE_DOWNLOAD_FAILED)
            return readCapped(connection, maxBytes)
        } finally {
            connection.disconnect()
        }
    }
    throw UpdateDownloadException(UPDATE_DOWNLOAD_FAILED)
}

private fun readCapped(connection: HttpURLConnection, maxBytes: Int): ByteArray {
    val advertised = connection.contentLengthLong
    if (advertised > maxBytes) throw UpdateDownloadException(UPDATE_DOWNLOAD_FAILED)
    connection.inputStream.use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) throw UpdateDownloadException(UPDATE_DOWNLOAD_FAILED)
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}

class UpdateDownloadException(message: String) : Exception(message)

private fun JsonObject.bool(key: String): Boolean =
    this[key]?.jsonPrimitive?.booleanOrNull == true

private fun JsonObject.text(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull
