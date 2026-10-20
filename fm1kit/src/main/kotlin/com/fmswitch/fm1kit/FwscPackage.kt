package com.fmswitch.fm1kit

sealed class FwscFailure(message: String) : Exception(message) {
    data object TooShort : FwscFailure("This file is too short to be an FM-1 firmware package.")
    data object TooLarge : FwscFailure("This file is too large to be an FM-1 firmware package.")
    data class UnreadableProduct(val text: String) :
        FwscFailure("This file doesn't carry an FM-1 product identity (it reads \"$text\").")
    data class UnexpectedModel(val model: String) :
        FwscFailure("This package is for a $model, not an FM-1.")
    data object DamagedHeader :
        FwscFailure("This package's header is damaged, so it can't be installed. Download it again.")
    data class UnexpectedChip(val chip: String) :
        FwscFailure("This package is for a $chip chip, not the FM-1's AC791N.")
    data class DamagedPart(val name: String) :
        FwscFailure("Part of this package ($name) is damaged, so it can't be installed. Download it again.")
    data class MissingPart(val part: String) :
        FwscFailure("This package has no usable $part, so it can't be installed.")
}

class FwscPackage private constructor(
    val fileBytes: ByteArray,
    val identity: FM1Identity,
    val logicalImage: ByteArray,
    val sha256: String,
    val loaderSHA256: String,
    internal val container: FirmwareContainer,
) {
    val product: String get() = identity.text
    val hasFactoryVoices: Boolean get() = factoryVoices() != null

    fun factoryVoices(): ByteArray? {
        if (OfficialFirmware.matching(this) != OfficialFirmware.V15) return null
        return voicesMatching(FACTORY_VOICES_SHA256)
    }

    private fun voicesMatching(expectedSha256: String): ByteArray? {
        val part = container.userData ?: return null
        if (!part.isEnciphered) return null
        if (part.size < FACTORY_VOICES_LENGTH) return null
        if (part.offset + part.storedSize > logicalImage.size) return null
        val plain = decipherUserData(logicalImage, part.offset until (part.offset + part.storedSize))
        if (crc16(plain, 0, part.size) != part.checksum) return null
        val voices = plain.copyOf(FACTORY_VOICES_LENGTH)
        return if (sha256Hex(voices) == expectedSha256) voices else null
    }

    companion object {
        const val BLOCK_COUNT = 20
        const val BLOCK_SIZE = 0x30
        const val IMAGE_BYTES_PER_BLOCK = 0x2F
        const val UNUSED_MARKER = 0x7D
        const val MAXIMUM_FILE_LENGTH = 2_097_152
        const val EXPECTED_MODEL = "FM-1"
        const val FACTORY_VOICES_LENGTH = 16_384
        const val VOICE_BANK_LENGTH = 4096
        const val FACTORY_VOICES_SHA256 = "ecad0b8620f9d784d46c8da1e3c5de02c40214649ed8dec218026431046aad25"
        const val USER_DATA_BLOCK_LENGTH = 32
        const val USER_DATA_KEY_SEED = 0x980F

        fun read(bytes: ByteArray): FwscPackage {
            if (bytes.size <= BLOCK_COUNT * BLOCK_SIZE) throw FwscFailure.TooShort
            if (bytes.size > MAXIMUM_FILE_LENGTH) throw FwscFailure.TooLarge
            val product = decodeProduct(bytes)
            val identity = FM1Identity.parse(product)
            if (identity == null || product.any { it.code !in 0x21..0x7E }) {
                throw FwscFailure.UnreadableProduct(product)
            }
            if (identity.model != EXPECTED_MODEL) throw FwscFailure.UnexpectedModel(identity.model)
            val image = stripMarkers(bytes)
            val container = FirmwareContainer(image)
            return FwscPackage(
                fileBytes = bytes,
                identity = identity,
                logicalImage = image,
                sha256 = sha256Hex(bytes),
                loaderSHA256 = sha256Hex(image.copyOfRange(container.loader.range.first, container.loader.range.last + 1)),
                container = container,
            )
        }

        fun decodeProduct(bytes: ByteArray): String {
            val chars = StringBuilder()
            for (block in 0 until BLOCK_COUNT) {
                val marker = bytes[block * BLOCK_SIZE + IMAGE_BYTES_PER_BLOCK].toUInt8()
                if (marker != UNUSED_MARKER) {
                    chars.append(((marker - block - 1) and 0xFF).toChar())
                }
            }
            return chars.toString()
        }

        fun stripMarkers(bytes: ByteArray): ByteArray {
            val image = ArrayList<Byte>(bytes.size - BLOCK_COUNT)
            for (block in 0 until BLOCK_COUNT) {
                val start = block * BLOCK_SIZE
                image.addAll(bytes.copyOfRange(start, start + IMAGE_BYTES_PER_BLOCK).toList())
            }
            image.addAll(bytes.copyOfRange(BLOCK_COUNT * BLOCK_SIZE, bytes.size).toList())
            return image.toByteArray()
        }

        fun decipherUserData(image: ByteArray, range: IntRange): ByteArray {
            val plain = ArrayList<Byte>(range.count())
            var start = range.first
            while (start <= range.last) {
                val end = minOf(start + USER_DATA_BLOCK_LENGTH, range.last + 1)
                val key = USER_DATA_KEY_SEED xor ((start shr 2) and 0xFFFF)
                plain.addAll(decipher(image, start, end, key).toList())
                start += USER_DATA_BLOCK_LENGTH
            }
            return plain.toByteArray()
        }
    }
}

data class OfficialFirmware(
    val name: String,
    val size: Int,
    val sha256: String,
    val buildID: FM1BuildID,
) {
    companion object {
        val V15 = OfficialFirmware(
            name = "FM-1 V15",
            size = 699_956,
            sha256 = "db1642b2b6fa5c2cccb11ffd13878068bb28601678d3644049f99dc40e7edb8a",
            buildID = FM1BuildID.knownHex("db1642b2b6fa5c2cccb11ffd13878068bb28601678d3644049f99dc40e7edb8a"),
        )
        val OPEN_V12 = OfficialFirmware(
            name = "FM1 Open V12",
            size = 475_700,
            sha256 = "2dd4f2d2f020851f8797752371704a9a541aa747f4e20419d629de9aa2c66f43",
            buildID = FM1BuildID.knownHex("426b7fa5cf1626bceaa05e8106b9a8b0eaad7f49d8b2c157303f15be347596aa"),
        )
        const val STOCK_LOADER_SHA256 = "ef1f2db89b011fa53e71df363f0122de0ea896618c53fb53e8da676322004541"
        val known = listOf(V15, OPEN_V12)

        fun matching(package_: FwscPackage): OfficialFirmware? =
            known.firstOrNull { it.size == package_.fileBytes.size && it.sha256 == package_.sha256 }
    }
}
