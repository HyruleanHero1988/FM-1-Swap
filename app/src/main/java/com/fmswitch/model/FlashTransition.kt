package com.fmswitch.model

import com.fmswitch.catalogue.CatalogueEntry

/** Name and version, the way a flash step should be read. */
fun StoredFirmware.installLabel(): String = "$name $version".trim()

/**
 * What the connected FM-1 is running, in the same kind of words as a package name.
 * A shared ID is left as the ID: several firmwares can report it, and a guessed name would be the wrong one.
 */
fun deviceFirmwareLabel(
    identityText: String?,
    loader: Boolean,
    saved: List<StoredFirmware>,
    catalogue: List<CatalogueEntry>,
    lastInstall: FlashRecord?,
): String {
    if (loader) return "Update loader"
    val text = identityText?.takeIf { it.isNotBlank() } ?: return "Unknown"
    lastInstall?.let { return it.installLabel() }
    val savedMatches = saved.filter { it.product == text }.map { it.installLabel() }.distinct()
    val catalogueMatches = catalogue.filter { it.product == text }.map { it.installLabel() }.distinct()
    val named = (savedMatches + catalogueMatches).distinct()
    if (named.size == 1) return named[0]
    return text
}

/** The editor told us the name of the firmware this flash is replacing. */
fun withDeviceFirmwareName(fromLabels: List<String>, deviceName: String?): List<String> {
    val name = deviceName?.trim().orEmpty()
    if (name.isEmpty() || fromLabels.isEmpty()) return fromLabels
    return fromLabels.toMutableList().also { it[0] = name }
}

fun CatalogueEntry.installLabel(): String = listOf(name, version).filter { !it.isNullOrBlank() }.joinToString(" ")

fun FlashRecord.installLabel(): String = "$name $version".trim()

/** One label per step: the device for the first, then each previous package. */
fun flashFromLabels(deviceLabel: String, steps: List<StoredFirmware>): List<String> =
    steps.mapIndexed { index, _ ->
        if (index == 0) deviceLabel else steps[index - 1].installLabel()
    }

/** "Going from {what is on the device} to {what this step installs}". */
fun flashArrow(from: String, step: StoredFirmware): String = "$from → ${step.installLabel()}"
