package com.fmswitch.fm1kit

internal data class ContainerEntry(
    val type: Int,
    val name: String,
    val offset: Int,
    val size: Int,
    val storedSize: Int,
    val encipheredLength: Int,
    val checksum: Int,
) {
    val isEnciphered: Boolean get() = encipheredLength != 0
    val range: IntRange get() = offset until (offset + size)

    fun stored(within: Int): IntRange {
        val end = (offset + storedSize).coerceAtMost(within)
        val start = offset.coerceAtLeast(0).coerceAtMost(within)
        return start until end.coerceAtLeast(start)
    }

    companion object {
        fun from(fields: ByteArray): ContainerEntry = ContainerEntry(
            type = readLittleEndian(fields, 0, 2).toInt(),
            name = asciiText(fields, 64, fields.size),
            offset = readLittleEndian(fields, 8, 4).toInt(),
            size = readLittleEndian(fields, 12, 4).toInt(),
            storedSize = readLittleEndian(fields, 16, 4).toInt(),
            encipheredLength = readLittleEndian(fields, 24, 4).toInt(),
            checksum = readLittleEndian(fields, 4, 2).toInt(),
        )
    }
}

internal class FirmwareContainer(image: ByteArray) {
    val flash: ContainerEntry
    val loader: ContainerEntry
    val userData: ContainerEntry?

    val applicationRange: IntRange
        get() = if (flash.size > BOOT_AREA_LENGTH) {
            (flash.offset + BOOT_AREA_LENGTH) until flash.range.last + 1
        } else {
            flash.range
        }

    init {
        if (image.size < HEADER_LENGTH) throw FwscFailure.DamagedHeader
        val header = decipher(image, 0, HEADER_LENGTH)
        val count = readLittleEndian(header, 8, 2).toInt()
        if (readLittleEndian(header, 0, 2).toInt() != crc16(header, 2, header.size)) {
            throw FwscFailure.DamagedHeader
        }
        if (readLittleEndian(header, 4, 4).toInt() != image.size) throw FwscFailure.DamagedHeader
        val chip = asciiText(header, 16, 32)
        if (chip != CHIP) throw FwscFailure.UnexpectedChip(chip)

        val tableEnd = HEADER_LENGTH + count * ENTRY_LENGTH
        if (count !in 1..MAXIMUM_ENTRIES || tableEnd > image.size) throw FwscFailure.DamagedHeader
        if (readLittleEndian(header, 2, 2).toInt() != crc16(image, HEADER_LENGTH, tableEnd)) {
            throw FwscFailure.DamagedHeader
        }

        val entries = ArrayList<ContainerEntry>()
        for (index in 0 until count) {
            val start = HEADER_LENGTH + index * ENTRY_LENGTH
            val entry = ContainerEntry.from(decipher(image, start, start + ENTRY_LENGTH))
            val stored = entry.stored(image.size)
            if (entry.offset < tableEnd ||
                entry.storedSize < entry.size ||
                entry.range.last + 1 > image.size ||
                (entry.isEnciphered && entry.encipheredLength != entry.storedSize) ||
                entries.any { it.stored(image.size).overlaps(stored) }
            ) {
                throw FwscFailure.DamagedHeader
            }
            if (!entry.isEnciphered && crc16(image, entry.range.first, entry.range.last + 1) != entry.checksum) {
                throw FwscFailure.DamagedPart(entry.name)
            }
            entries.add(entry)
        }

        val flashes = entries.filter { it.type == FLASH_TYPE }
        val loaders = entries.filter { it.type == LOADER_TYPE }
        val flash = flashes.singleOrNull()
        if (flash == null || flash.isEnciphered || flash.size !in FLASH_SIZES || flash.size % FLASH_SECTOR_SIZE != 0) {
            throw FwscFailure.MissingPart("firmware image")
        }
        val loader = loaders.singleOrNull()
        if (loader == null || loader.isEnciphered || loader.size !in LOADER_SIZES) {
            throw FwscFailure.MissingPart("update loader")
        }
        this.flash = flash
        this.loader = loader
        userData = entries.firstOrNull { it.type == USER_DATA_TYPE && it.name == USER_DATA_NAME }
    }

    companion object {
        const val HEADER_LENGTH = 64
        const val ENTRY_LENGTH = 80
        const val MAXIMUM_ENTRIES = 12
        const val CHIP = "AC791N"
        const val FLASH_TYPE = 0
        const val LOADER_TYPE = 100
        const val USER_DATA_TYPE = 50
        const val USER_DATA_NAME = "USR"
        val FLASH_SIZES = 16_384..1_044_480
        const val FLASH_SECTOR_SIZE = 4096
        val LOADER_SIZES = 1 until 32_768
        const val BOOT_AREA_LENGTH = 0x4000
    }
}

private fun IntRange.overlaps(other: IntRange): Boolean {
    if (isEmpty() || other.isEmpty()) return false
    return first <= other.last && other.first <= last
}
