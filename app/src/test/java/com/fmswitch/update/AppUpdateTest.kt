package com.fmswitch.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {
    @Test
    fun comparesVersionsNumerically() {
        assertTrue(isNewerVersion("0.10.0", "0.9.0"))
        assertTrue(isNewerVersion("v0.6.0", "0.5.0"))
        assertTrue(isNewerVersion("1", "0.9.9"))
        assertFalse(isNewerVersion("0.5.0", "0.5.0"))
        assertFalse(isNewerVersion("v0.5.0", "0.5.0"))
        assertFalse(isNewerVersion("0.5.0", "0.5.1"))
        assertFalse(isNewerVersion("0.9.0", "0.10.0"))
        assertEquals("0.6.0", displayVersion("v0.6.0"))
    }

    @Test
    fun aDebugInstallIsNotOfferedTheRelease() {
        val release = AppRelease(
            version = "v0.6.0",
            apkUrl = "https://github.com/HyruleanHero1988/FM-1-Swap/releases/download/v0.6.0/FM-1-Swap.apk",
            size = 15,
        )
        assertNull(offerUpdate("0.5.0-debug", release))
        assertEquals(release, offerUpdate("0.5.0", release))
    }

    @Test
    fun readsTheReleaseApkAndIgnoresTheDebugAsset() {
        val release = appRelease(RELEASE_JSON)
        assertEquals("v0.6.0", release?.version)
        assertEquals(
            "https://github.com/HyruleanHero1988/FM-1-Swap/releases/download/v0.6.0/FM-1-Swap.apk",
            release?.apkUrl,
        )
        assertEquals(15302077L, release?.size)
    }

    @Test
    fun skipsDraftsPrereleasesAndAReleaseWithoutTheApk() {
        assertNull(appRelease(RELEASE_JSON.replace("\"prerelease\": false", "\"prerelease\": true")))
        assertNull(appRelease(RELEASE_JSON.replace("\"draft\": false", "\"draft\": true")))
        assertNull(appRelease(RELEASE_JSON.replace("FM-1-Swap.apk", "FM-1-Swap-notes.txt")))
        assertNull(appRelease("not json"))
    }

    @Test
    fun rejectsAnUntrustedDownload() {
        assertTrue(trustedApkUrl("https://github.com/HyruleanHero1988/FM-1-Swap/releases/download/v0.6.0/FM-1-Swap.apk"))
        assertTrue(trustedApkUrl("https://objects.githubusercontent.com/github-production-release-asset-2e65be/FM-1-Swap.apk"))
        assertTrue(trustedUpdateUrl("https://api.github.com/repos/HyruleanHero1988/FM-1-Swap/releases/latest"))
        assertFalse(trustedApkUrl("https://api.github.com/repos/HyruleanHero1988/FM-1-Swap/releases/latest"))
        assertFalse(trustedApkUrl("http://github.com/HyruleanHero1988/FM-1-Swap/FM-1-Swap.apk"))
        assertFalse(trustedApkUrl("https://evil.example/FM-1-Swap.apk"))
        assertFalse(trustedApkUrl("https://github.com.evil.example/FM-1-Swap.apk"))
        assertFalse(trustedApkUrl("https://user:pass@github.com/HyruleanHero1988/FM-1-Swap.apk"))
        val sneaky = AppRelease("v0.9.0", "https://evil.example/FM-1-Swap.apk", 10)
        assertNull(offerUpdate("0.5.0", sneaky))
        val same = AppRelease(
            "v0.5.0",
            "https://github.com/HyruleanHero1988/FM-1-Swap/releases/download/v0.5.0/FM-1-Swap.apk",
            10,
        )
        assertNull(offerUpdate("0.5.0", same))
    }
}

private const val RELEASE_JSON = """
{
  "tag_name": "v0.6.0",
  "draft": false,
  "prerelease": false,
  "assets": [
    {
      "name": "FM-1-Swap-debug.apk",
      "browser_download_url": "https://github.com/HyruleanHero1988/FM-1-Swap/releases/download/android-debug/FM-1-Swap-debug.apk",
      "size": 1
    },
    {
      "name": "FM-1-Swap.apk",
      "browser_download_url": "https://github.com/HyruleanHero1988/FM-1-Swap/releases/download/v0.6.0/FM-1-Swap.apk",
      "size": 15302077
    }
  ]
}
"""
