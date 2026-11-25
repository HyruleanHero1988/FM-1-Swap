package com.fmswitch.fm1kit

/**
 * FM-1 vs SMK-37 keyboard families. Identity strings are not enough: an SMK running
 * ported FM-1 firmware still reports `FM-1_*`, so package layout checks matter too.
 */
object DeviceFamily {
    const val FM1_MODEL = "FM-1"

    private val smkPattern = Regex(
        "^(ota-)?(SMK-37([ +].*)?|MKE-P37|STARRYKEY-37.*)$",
        RegexOption.IGNORE_CASE,
    )

    /** ChanceTheMaker SMK ports (`SMK-37_9xx`) use a different updater; never flash from here. */
    private val otherSmkPattern = Regex("^(ota-)?SMK-37_9\\d\\d$", RegexOption.IGNORE_CASE)

    /** Felucca / SLOOP family loaders this app can resume. */
    private val ourLoaderPattern = Regex(
        "^ota-(FM-1|SMK-37 Pro|SMK-37 Elite|MKE-P37)_\\d+$",
        RegexOption.IGNORE_CASE,
    )

    fun isFm1Model(model: String): Boolean =
        model.equals(FM1_MODEL, ignoreCase = true)

    fun isSmkFamily(model: String): Boolean = smkPattern.matches(model.trim())

    fun isOtherSmkFirmware(identity: FM1Identity): Boolean =
        otherSmkPattern.matches(identity.text)

    fun sameKeyboard(a: String, b: String): Boolean {
        val ma = a.removePrefix("ota-").removePrefix("OTA-")
        val mb = b.removePrefix("ota-").removePrefix("OTA-")
        if (ma.equals(mb, ignoreCase = true)) return true
        return isSmkFamily(ma) && isSmkFamily(mb)
    }

    fun isOurLoader(identity: FM1Identity): Boolean =
        identity.isLoader && ourLoaderPattern.matches(identity.text)

    fun deviceFamilyFor(model: String): DeviceFamilyKind = when {
        isSmkFamily(model) -> DeviceFamilyKind.SMK37
        isFm1Model(model) -> DeviceFamilyKind.FM1
        else -> DeviceFamilyKind.UNKNOWN
    }

    fun deviceFamilyFor(identity: FM1Identity): DeviceFamilyKind =
        deviceFamilyFor(identity.hardwareModel)

    /**
     * Whether a package's declared model can run on the connected device.
     * [allowSiblingSmkModels] covers Starrykey / vague `SMK-37` names that take the Pro package.
     */
    fun modelsCompatible(
        deviceModel: String,
        packageModel: String,
        allowSiblingSmkModels: Boolean = false,
    ): Boolean {
        if (deviceModel.equals(packageModel, ignoreCase = true)) return true
        if (allowSiblingSmkModels && sameKeyboard(deviceModel, packageModel)) return true
        return false
    }

    fun pickBestPackageProduct(deviceText: String, products: List<String>): PackagePick? {
        val parsed = FM1Identity.parse(deviceText) ?: return null
        if (isOtherSmkFirmware(parsed)) return null
        val dm = deviceText.substringBeforeLast('_').removePrefix("ota-").lowercase()
        products.firstOrNull { it.substringBeforeLast('_').equals(dm, ignoreCase = true) }?.let {
            return PackagePick(it, anyModel = false)
        }
        val kin = products.filter { sameKeyboard(dm, it.substringBeforeLast('_')) }
        kin.firstOrNull { it.startsWith("SMK-37 Pro_", ignoreCase = true) }?.let {
            return PackagePick(it, anyModel = true)
        }
        if (kin.size == 1) return PackagePick(kin.single(), anyModel = true)
        return null
    }

    /**
     * Catalogue / installer variant key for an SMK identity: `elite`, `mke`, or `pro`
     * (Starrykey and unknown SMK names take the Pro package).
     */
    fun smkVariantKey(identity: FM1Identity?): String {
        val model = identity?.hardwareModel?.removePrefix("ota-")?.removePrefix("OTA-").orEmpty()
        return when {
            model.equals("SMK-37 Elite", ignoreCase = true) -> VARIANT_ELITE
            model.equals("MKE-P37", ignoreCase = true) -> VARIANT_MKE
            else -> VARIANT_PRO
        }
    }

    const val VARIANT_PRO = "pro"
    const val VARIANT_ELITE = "elite"
    const val VARIANT_MKE = "mke"
}

enum class DeviceFamilyKind { FM1, SMK37, UNKNOWN }

data class PackagePick(val product: String, val anyModel: Boolean)