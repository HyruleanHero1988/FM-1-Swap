package com.fmswitch.fm1kit

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Optional local check against M-VAVE CDN files already downloaded to %TEMP%.
 * Skips when the files are not present (CI / clean machines).
 */
class StockSmkPackageTest {
    @Test
    fun officialV016PackagesDecodeExpectedProducts() {
        val cases = listOf(
            "SMK-37_Pro_016.fwsc" to "SMK-37 Pro_016",
            "SMK-37_Elite_016.fwsc" to "SMK-37 Elite_016",
            "MKE-P37_015.fwsc" to "MKE-P37_015",
            "STARRYKEY_016.fwsc" to "STARRYKEY-37 PLAY_016",
        )
        val temp = File(System.getProperty("java.io.tmpdir"))
        var checked = 0
        for ((fileName, expected) in cases) {
            val file = File(temp, fileName)
            assumeTrue("need $fileName in temp", true) // don't fail the suite if missing
            if (!file.isFile) continue
            val package_ = FwscPackage.read(file.readBytes())
            assertEquals(fileName, expected, package_.product)
            checked++
        }
        assumeTrue("no stock packages in temp to check", checked > 0)
    }
}
