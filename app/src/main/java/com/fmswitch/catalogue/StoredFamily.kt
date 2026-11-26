package com.fmswitch.catalogue

import com.fmswitch.fm1kit.DeviceFamily
import com.fmswitch.fm1kit.DeviceFamilyKind
import com.fmswitch.fm1kit.FM1Identity
import com.fmswitch.model.StoredFirmware

/**
 * Which keyboard family a saved package belongs to, from its product identity
 * (e.g. `FM-1_015`, `SMK-37 Pro_016`) and, when that is unclear, the catalogue row's platforms.
 */
fun familyForStored(
    item: StoredFirmware,
    catalogueEntry: CatalogueEntry? = null,
): DeviceFamilyKind {
    FM1Identity.parse(item.product)?.let { return DeviceFamily.deviceFamilyFor(it) }
    val platforms = catalogueEntry?.platforms ?: return DeviceFamilyKind.UNKNOWN
    val listed = platforms.ifEmpty { listOf(DevicePlatform.FM1) }
    val smk = listed.any { it.equals(DevicePlatform.SMK37, ignoreCase = true) }
    val fm1 = listed.any { it.equals(DevicePlatform.FM1, ignoreCase = true) }
    return when {
        smk && !fm1 -> DeviceFamilyKind.SMK37
        fm1 && !smk -> DeviceFamilyKind.FM1
        else -> DeviceFamilyKind.UNKNOWN
    }
}

/** UNKNOWN packages stay visible in either list so an odd import is not hidden. */
fun StoredFirmware.matchesFamily(
    family: DeviceFamilyKind,
    catalogueEntry: CatalogueEntry? = null,
): Boolean {
    if (family == DeviceFamilyKind.UNKNOWN) return true
    val item = familyForStored(this, catalogueEntry)
    return item == DeviceFamilyKind.UNKNOWN || item == family
}
