package com.fmswitch.catalogue

import com.fmswitch.fm1kit.DeviceFamily
import com.fmswitch.fm1kit.DeviceFamilyKind
import com.fmswitch.fm1kit.FM1Identity

enum class DeviceUiMode(val id: String, val label: String) {
    AUTO("auto", "AUTO"),
    FM1("fm1", "FM-1"),
    SMK37("smk37", "SMK-37"),
    ;

    companion object {
        fun fromId(id: String?): DeviceUiMode = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

fun CatalogueEntry.targetsPlatform(platform: DeviceFamilyKind): Boolean {
    val platforms = this.platforms.ifEmpty { listOf(DevicePlatform.FM1) }
    return when (platform) {
        DeviceFamilyKind.FM1 -> platforms.any { it.equals(DevicePlatform.FM1, ignoreCase = true) }
        DeviceFamilyKind.SMK37 -> platforms.any { it.equals(DevicePlatform.SMK37, ignoreCase = true) }
        DeviceFamilyKind.UNKNOWN -> true
    }
}

object DevicePlatform {
    const val FM1 = "fm1"
    const val SMK37 = "smk37"

    fun effective(
        mode: DeviceUiMode,
        identity: FM1Identity?,
    ): DeviceFamilyKind = when (mode) {
        DeviceUiMode.FM1 -> DeviceFamilyKind.FM1
        DeviceUiMode.SMK37 -> DeviceFamilyKind.SMK37
        DeviceUiMode.AUTO -> identity?.let { DeviceFamily.deviceFamilyFor(it) } ?: DeviceFamilyKind.FM1
    }
}