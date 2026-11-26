package com.fmswitch.fm1kit

/**
 * Variable interleave blocks and flash layout detection (SMK stock V016 uses 36 blocks).
 * Layout rules are reimplemented from public installer behaviour; not copied from GPL sources.
 */
object FwscLayout {
    const val MINIMUM_IDENTITY_BLOCKS = FwscPackage.BLOCK_COUNT
    const val MAXIMUM_BLOCKS = 64
    const val SMK_FLASH_LENGTH = 0x9D000
    const val FM1_FLASH_LENGTH = 0x93000
    private const val SMK_BTIF = 0xC0000L
    private const val FM1_BTIF = 0xE9000L
    private const val SMK_USR_OFFSET = 0xF4000L
    private const val SMK_USR_LENGTH = 0xA000
    private const val FM1_USR_OFFSET = 0xEA000L

    fun identityBlockCount(raw: ByteArray): Int {
        fun marker(index: Int): Int? {
            val offset = index * FwscPackage.BLOCK_SIZE + FwscPackage.IMAGE_BYTES_PER_BLOCK
            if ((index + 1) * FwscPackage.BLOCK_SIZE > raw.size) return null
            return raw[offset].toUInt8()
        }
        var count = MINIMUM_IDENTITY_BLOCKS
        while (count < MAXIMUM_BLOCKS) {
            val m = marker(count) ?: break
            if (m == FwscPackage.UNUSED_MARKER) break
            if (!printableIdentityByte(m, count)) break
            count++
        }
        return if (count > MINIMUM_IDENTITY_BLOCKS && marker(count) == FwscPackage.UNUSED_MARKER) {
            count
        } else {
            MINIMUM_IDENTITY_BLOCKS
        }
    }

    fun interleaveBlockCount(raw: ByteArray): Int {
        var count = identityBlockCount(raw)
        while (count < MAXIMUM_BLOCKS) {
            val offset = count * FwscPackage.BLOCK_SIZE + FwscPackage.IMAGE_BYTES_PER_BLOCK
            if ((count + 1) * FwscPackage.BLOCK_SIZE > raw.size) break
            if (raw[offset].toUInt8() != FwscPackage.UNUSED_MARKER) break
            count++
        }
        return count
    }

    fun decodeProduct(raw: ByteArray): String {
        if (raw.size < MINIMUM_IDENTITY_BLOCKS * FwscPackage.BLOCK_SIZE) {
            throw FwscFailure.TooShort
        }
        val identityBlocks = identityBlockCount(raw)
        val layout = resolveLogicalImage(raw)
        val charBlocks = if (identityBlocks == MINIMUM_IDENTITY_BLOCKS) {
            MINIMUM_IDENTITY_BLOCKS
        } else {
            minOf(identityBlocks, layout.blocks)
        }
        val chars = StringBuilder()
        for (block in 0 until charBlocks) {
            val marker = raw[block * FwscPackage.BLOCK_SIZE + FwscPackage.IMAGE_BYTES_PER_BLOCK].toUInt8()
            if (marker != FwscPackage.UNUSED_MARKER) {
                chars.append(((marker - block - 1) and 0xFF).toChar())
            }
        }
        return chars.toString()
    }

    data class ResolvedImage(val blocks: Int, val image: ByteArray)

    fun resolveLogicalImage(raw: ByteArray): ResolvedImage {
        val detected = interleaveBlockCount(raw)
        val image = stripMarkers(raw, detected)
        if (detected != MINIMUM_IDENTITY_BLOCKS && packageDamaged(image)) {
            val fallback = stripMarkers(raw, MINIMUM_IDENTITY_BLOCKS)
            if (!packageDamaged(fallback)) {
                return ResolvedImage(MINIMUM_IDENTITY_BLOCKS, fallback)
            }
        }
        return ResolvedImage(detected, image)
    }

    fun stripMarkers(raw: ByteArray, blocks: Int): ByteArray {
        val image = ArrayList<Byte>(raw.size - blocks)
        for (block in 0 until blocks) {
            val start = block * FwscPackage.BLOCK_SIZE
            image.addAll(raw.copyOfRange(start, start + FwscPackage.IMAGE_BYTES_PER_BLOCK).toList())
        }
        image.addAll(raw.copyOfRange(blocks * FwscPackage.BLOCK_SIZE, raw.size).toList())
        return image.toByteArray()
    }

    enum class PackageBoard { FM1, SMK }

    fun packageBoard(image: ByteArray): PackageBoard? {
        val files = packageFiles(image)
        val flash = files.firstOrNull { it.type == 0 } ?: return null
        if (flash.offset + flash.size > image.size) return null
        val regions = regionTable(image, flash.offset, flash.size)
        val usrEntries = files.filter { it.type == 50 }
        val hasSmkLayout = flash.size == SMK_FLASH_LENGTH &&
            regions["BTIF"]?.first == SMK_BTIF &&
            regions["USR"]?.first == SMK_USR_OFFSET &&
            regions["USR"]?.second == SMK_USR_LENGTH.toLong() &&
            usrEntries.all { it.target == SMK_USR_OFFSET && it.length == SMK_USR_LENGTH.toInt() }
        if (hasSmkLayout) return PackageBoard.SMK
        val looksFm1 = flash.size == FM1_FLASH_LENGTH ||
            regions["BTIF"]?.first == FM1_BTIF ||
            regions["USR"]?.first == FM1_USR_OFFSET ||
            usrEntries.any { it.target == FM1_USR_OFFSET }
        return if (looksFm1) PackageBoard.FM1 else null
    }

    fun boardRefusal(deviceModel: String, image: ByteArray): FM1UpdateError? {
        val board = packageBoard(image) ?: return null
        val deviceIsSmk = DeviceFamily.isSmkFamily(deviceModel)
        val deviceIsFm1 = DeviceFamily.isFm1Model(deviceModel)
        return when {
            deviceIsSmk && board == PackageBoard.FM1 -> FM1UpdateError.WrongPackageBoard(
                "This package is laid out for the FM-1. On an SMK-37 it can erase factory calibration. Use an SMK-37 package.",
            )
            deviceIsFm1 && board == PackageBoard.SMK -> FM1UpdateError.WrongPackageBoard(
                "This package is laid out for the SMK-37, not the FM-1.",
            )
            else -> null
        }
    }

    private fun printableIdentityByte(marker: Int, block: Int): Boolean {
        val c = (marker - block - 1) and 0xFF
        return c in 0x20 until 0x7F
    }

    private data class FileEntry(
        val type: Int,
        val offset: Int,
        val size: Int,
        val target: Long,
        val length: Int,
    )

    private fun packageFiles(image: ByteArray): List<FileEntry> {
        if (image.size < 0x40) return emptyList()
        val header = ufwDecipher(image.copyOfRange(0, 0x40))
        val count = readLittleEndian(header, 8, 2).toInt()
        if (count !in 1..11 || image.size < 0x40 + 0x50 * count) return emptyList()
        return (0 until count).mapNotNull { index ->
            val start = 0x40 + index * 0x50
            val end = start + 0x50
            if (end > image.size) return@mapNotNull null
            val entry = ufwDecipher(image.copyOfRange(start, end))
            FileEntry(
                type = readLittleEndian(entry, 0, 2).toInt(),
                offset = readLittleEndian(entry, 8, 4).toInt(),
                size = readLittleEndian(entry, 12, 4).toInt(),
                target = readLittleEndian(entry, 0x1C, 4),
                length = readLittleEndian(entry, 0x20, 4).toInt(),
            )
        }
    }

    private fun regionTable(image: ByteArray, flashOffset: Int, flashSize: Int): Map<String, Pair<Long, Long>> {
        val out = LinkedHashMap<String, Pair<Long, Long>>()
        if (flashSize < 0x4120) return out
        val slice = image.copyOfRange(flashOffset, flashOffset + flashSize)
        if (slice.size < 0x4120) return out
        val decoded = sfcDecipher(slice.copyOfRange(0x4060, 0x4120), 0x60)
        for (row in 0 until 0xC0 step 0x20) {
            val name = buildString {
                for (i in 16 until 32) {
                    val b = decoded[row + i].toUInt8()
                    if (b <= 0x20 || b >= 0x7F) break
                    append(b.toChar())
                }
            }
            if (name.isEmpty()) continue
            val address = readLittleEndian(decoded, row + 4, 4)
            val size = readLittleEndian(decoded, row + 8, 4)
            out[name] = address to size
        }
        return out
    }

    private fun ufwDecipher(bytes: ByteArray): ByteArray {
        val out = bytes.copyOf()
        var key = 0xFFFF
        for (i in out.indices) {
            out[i] = (out[i].toUInt8() xor (key and 0xFF)).toByte()
            key = ((key shl 1) xor if (key and 0x8000 != 0) 0x1021 else 0) and 0xFFFF
        }
        return out
    }

    private fun sfcDecipher(bytes: ByteArray, base: Int, key: Int = 0x980F): ByteArray {
        val out = bytes.copyOf()
        var index = 0
        while (index < out.size) {
            var k = (key xor (base + index ushr 2)) and 0xFFFF
            val end = minOf(index + 32, out.size)
            for (j in index until end) {
                out[j] = (out[j].toUInt8() xor (k and 0xFF)).toByte()
                k = ((k shl 1) xor if (k and 0x8000 != 0) 0x1021 else 0) and 0xFFFF
            }
            index += 32
        }
        return out
    }

    private fun packageDamaged(image: ByteArray): Boolean {
        if (image.size < 0x40) return true
        val header = ufwDecipher(image.copyOfRange(0, 0x40))
        if (crc16(header, 2, header.size) != readLittleEndian(header, 0, 2).toInt()) return true
        val count = readLittleEndian(header, 8, 2).toInt()
        if (count !in 1..8 || image.size < 0x40 + 0x50 * count) return true
        for (index in 0 until count) {
            val start = 0x40 + index * 0x50
            val entry = ufwDecipher(image.copyOfRange(start, start + 0x50))
            val type = readLittleEndian(entry, 0, 2).toInt()
            val crc = readLittleEndian(entry, 4, 2).toInt()
            val offset = readLittleEndian(entry, 8, 4).toInt()
            val size = readLittleEndian(entry, 12, 4).toInt()
            if (offset + size > image.size) return true
            if (type == 0 || type == 100) {
                if (crc16(image, offset, size) != crc) return true
            }
        }
        return false
    }
}