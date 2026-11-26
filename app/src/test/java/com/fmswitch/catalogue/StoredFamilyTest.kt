package com.fmswitch.catalogue

import com.fmswitch.fm1kit.DeviceFamilyKind
import com.fmswitch.model.StoredFirmware
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoredFamilyTest {
    private fun item(product: String, catalogueID: String? = null) = StoredFirmware(
        id = product,
        name = product,
        version = "1",
        product = product,
        sha256 = "aa",
        size = 1,
        fileName = "$product.fwsc",
        source = "imported",
        catalogueID = catalogueID,
        savedAt = 0,
    )

    @Test
    fun productIdentityPicksFm1AndSmk() {
        assertEquals(DeviceFamilyKind.FM1, familyForStored(item("FM-1_015")))
        assertEquals(DeviceFamilyKind.FM1, familyForStored(item("FM-1_916")))
        assertEquals(DeviceFamilyKind.SMK37, familyForStored(item("SMK-37 Pro_016")))
        assertEquals(DeviceFamilyKind.SMK37, familyForStored(item("SMK-37 Elite_900")))
        assertEquals(DeviceFamilyKind.SMK37, familyForStored(item("MKE-P37_703")))
        assertEquals(DeviceFamilyKind.SMK37, familyForStored(item("STARRYKEY-37 PLAY_016")))
    }

    @Test
    fun cataloguePlatformsFillInWhenProductIsOpaque() {
        val smkOnly = CatalogueEntry(
            id = "x",
            name = "X",
            platforms = listOf(DevicePlatform.SMK37),
        )
        assertEquals(DeviceFamilyKind.SMK37, familyForStored(item("mystery"), smkOnly))
        assertTrue(item("mystery", "x").matchesFamily(DeviceFamilyKind.SMK37, smkOnly))
        assertFalse(item("FM-1_015").matchesFamily(DeviceFamilyKind.SMK37))
        assertTrue(item("FM-1_015").matchesFamily(DeviceFamilyKind.FM1))
        assertTrue(item("odd-name").matchesFamily(DeviceFamilyKind.FM1))
        assertTrue(item("odd-name").matchesFamily(DeviceFamilyKind.SMK37))
    }
}
