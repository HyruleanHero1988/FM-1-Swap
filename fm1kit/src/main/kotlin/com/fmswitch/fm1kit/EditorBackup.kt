package com.fmswitch.fm1kit

import kotlinx.coroutines.ensureActive
import java.util.Base64
import kotlin.coroutines.coroutineContext

/**
 * Full musical backup used by Felucca's web editor and, on the same "FL" SysEx header, by SLOOP.
 * A device that answers is dumped the way that editor dumps it. Firmware that never answers is left alone.
 * Felucca: commands 65–67, file `felucca-backup`. SLOOP: commands 34–36, file `sloop-backup`.
 * Samples ride the shared slot commands 11–14. The FM6 voice bank is included only when the device lists it.
 */
enum class EditorBackupKind(val format: String, val title: String) {
    FELUCCA("felucca-backup", "Felucca"),
    SLOOP("sloop-backup", "SLOOP"),
}

data class EditorObject(
    val id: Int,
    val bytes: ByteArray,
    val crc: Long,
)

data class EditorArchive(
    val kind: EditorBackupKind,
    val firmware: String,
    val created: String,
    val objects: List<EditorObject>,
) {
    fun toJson(): String = buildString {
        append("{\"format\":\"").append(kind.format).append('"')
        append(",\"version\":1")
        append(",\"firmware\":").append(jsonString(firmware))
        append(if (kind == EditorBackupKind.FELUCCA) ",\"created\":" else ",\"date\":")
        append(jsonString(created))
        append(",\"objects\":[")
        objects.forEachIndexed { index, obj ->
            if (index > 0) append(',')
            append("{\"id\":").append(obj.id)
            append(if (kind == EditorBackupKind.FELUCCA) ",\"size\":" else ",\"len\":")
            append(obj.bytes.size)
            append(",\"crc\":").append(obj.crc and 0xffff_ffffL)
            append(",\"data\":").append(jsonString(Base64.getEncoder().encodeToString(obj.bytes)))
            append('}')
        }
        append("]}")
    }

    companion object {
        fun parse(text: String): EditorArchive {
            val format = jsonField(text, "format") ?: error("Not an editor backup.")
            val kind = EditorBackupKind.entries.firstOrNull { it.format == format }
                ?: error("Not an editor backup.")
            val firmware = jsonField(text, "firmware") ?: error("Not an editor backup.")
            val created = jsonField(text, "created") ?: jsonField(text, "date") ?: ""
            val objects = OBJECT.findAll(text).map { match ->
                val id = match.groupValues[1].toInt()
                val size = match.groupValues[2].toInt()
                val crc = match.groupValues[3].toLong() and 0xffff_ffffL
                val bytes = Base64.getDecoder().decode(match.groupValues[4])
                if (bytes.size != size || editorCrc32(bytes) != crc) error("Backup checksum mismatch.")
                EditorObject(id, bytes, crc)
            }.toList()
            if (objects.none { it.id == 0 } || objects.none { it.id == 1 }) error("Backup is missing the current music or settings.")
            return EditorArchive(kind, firmware, created, objects)
        }

        private val OBJECT = Regex(
            """\{"id":(\d+),"(?:size|len)":(\d+),"crc":(\d+),"data":"([A-Za-z0-9+/=]*)"\}""",
        )
    }
}

/** Community firmware that might speak Felucca's or SLOOP's editor, so a flash sheet should ask the device. */
val FM1Identity.mightHaveEditorBackup: Boolean
    get() = !isLoader && !supportsBaudGirlBackup && !isOpenFirmware && version != 15

class EditorLibrarian(
    private val infoTimeoutMs: Long = 1_200,
    private val listTimeoutMs: Long = 3_000,
    private val readTimeoutMs: Long = 1_500,
    private val writeTimeoutMs: Long = 4_000,
) {
    /** Which backup this device actually answers, or null when it speaks neither. */
    suspend fun detect(connection: MidiConnection): EditorBackupKind? = when (val found = survey(connection)) {
        null -> null
        else -> found.kind
    }

    /**
     * Reads every object the device lists. Null when the device has no such backup.
     * Throws once a backup has started and then fails, so a flash does not continue half-saved.
     */
    suspend fun capture(
        connection: MidiConnection,
        identityText: String,
        progress: (Int, Int) -> Unit = { _, _ -> },
    ): EditorArchive? {
        val found = survey(connection) ?: return null
        val objects = ArrayList<EditorObject>(found.meta.size)
        val total = found.meta.sumOf { it.length }.coerceAtLeast(1)
        var done = 0
        for (item in found.meta) {
            coroutineContext.ensureActive()
            val bytes = ByteArray(item.length)
            var offset = 0
            while (offset < item.length) {
                val size = minOf(CHUNK, item.length - offset)
                val part = readChunk(connection, found.kind, item.id, offset, size)
                part.copyInto(bytes, offset)
                offset += size
                done += size
                progress(done, total)
            }
            if (editorCrc32(bytes) != item.crc) {
                throw FM1UpdateError.BackupFailed("the FM-1 changed during the backup. Stop playback and try again")
            }
            objects += EditorObject(item.id, bytes, item.crc)
        }
        progress(total, total)
        val firmware = found.info?.version?.takeIf { it.isNotEmpty() } ?: identityText
        return EditorArchive(found.kind, firmware, isoNow(), objects)
    }

    suspend fun restore(
        connection: MidiConnection,
        archive: EditorArchive,
        progress: (Int, Int) -> Unit = { _, _ -> },
    ) {
        val info = readInfo(connection, required = true)
            ?: throw FM1UpdateError.BackupFailed("the FM-1 didn't answer")
        val live = survey(connection)
            ?: throw FM1UpdateError.BackupFailed(
                "this FM-1 isn't running a firmware that can take a ${archive.kind.title} backup. Run that firmware again and stop playback",
            )
        if (live.kind != archive.kind) {
            throw FM1UpdateError.BackupFailed(
                "this is a ${archive.kind.title} backup, and the FM-1 is running ${info.version}. Run that firmware again and stop playback",
            )
        }
        val objects = archive.objects.associateBy { it.id }.toMutableMap()
        if (archive.kind == EditorBackupKind.SLOOP) {
            val newest = listOf(0, 2, 3, 4, 5).maxOf { id -> projectFormat(objects[id]?.bytes) }
            if (newest > deviceProjectFormat(info.pcount)) {
                throw FM1UpdateError.BackupFailed("this backup is from ${archive.firmware}, which this FM-1 cannot open")
            }
            if (objects[10] == null && firmwareAtLeast(archive.firmware, 2, 5, 1)) {
                val midi = ByteArray(40)
                midi[32] = 1
                midi[33] = 2
                midi[34] = 3
                midi[35] = 10
                midi[36] = 2
                objects[10] = EditorObject(10, midi, editorCrc32(midi))
            }
        }
        val order = restoreOrder(archive.kind, objects.keys)
        val total = order.sumOf { objects[it]?.bytes?.size ?: 0 }.coerceAtLeast(1)
        var done = 0
        for (id in order) {
            coroutineContext.ensureActive()
            val obj = objects[id] ?: continue
            if (id >= 32) {
                restoreSample(connection, id - 32, obj.bytes)
            } else {
                val wrote = restoreObject(connection, archive.kind, obj)
                if (!wrote) {
                    done += obj.bytes.size
                    progress(done, total)
                    continue
                }
            }
            done += obj.bytes.size
            progress(done, total)
        }
        progress(total, total)
    }

    private suspend fun survey(connection: MidiConnection): Survey? {
        val info = readInfo(connection, required = false) ?: return null
        val hint = sniffEditorBackup(info.trailer)
        if (hint != EditorBackupKind.SLOOP) {
            val listed = listFelucca(connection)
            if (listed != null) return Survey(EditorBackupKind.FELUCCA, info, listed)
            if (hint == EditorBackupKind.FELUCCA) return null
        }
        if (hint != EditorBackupKind.FELUCCA) {
            val listed = listSloop(connection)
            if (listed != null) return Survey(EditorBackupKind.SLOOP, info, listed)
        }
        return null
    }

    private data class Survey(
        val kind: EditorBackupKind,
        val info: EditorInfo?,
        val meta: List<ObjectMeta>,
    )

    private data class EditorInfo(val version: String, val pcount: Int, val trailer: ByteArray)

    private data class ObjectMeta(val id: Int, val length: Int, val crc: Long)

    private suspend fun readInfo(connection: MidiConnection, required: Boolean): EditorInfo? {
        val body = request(connection, CMD_INFO, ByteArray(0), infoTimeoutMs, attempts = if (required) 2 else 1)
        if (body == null) {
            if (required) throw FM1UpdateError.BackupFailed("the FM-1 didn't answer")
            return null
        }
        return try {
            parseInfo(body)
        } catch (error: FM1UpdateError) {
            if (required) throw error
            null
        }
    }

    private suspend fun listFelucca(connection: MidiConnection): List<ObjectMeta>? {
        val body = request(connection, CMD_FELUCCA_LIST, ByteArray(0), listTimeoutMs, attempts = 1) ?: return null
        return parseFeluccaList(body)
    }

    private suspend fun listSloop(connection: MidiConnection): List<ObjectMeta>? {
        val body = request(connection, CMD_SLOOP_LIST, ByteArray(0), listTimeoutMs, attempts = 1) ?: return null
        return parseSloopList(body)
    }

    private suspend fun readChunk(
        connection: MidiConnection,
        kind: EditorBackupKind,
        id: Int,
        offset: Int,
        size: Int,
    ): ByteArray {
        val cmd = if (kind == EditorBackupKind.FELUCCA) CMD_FELUCCA_GET else CMD_SLOOP_GET
        val args = ByteArray(1 + 5 + 2)
        args[0] = id.toByte()
        u35(offset).copyInto(args, 1)
        args[6] = (size and 0x7f).toByte()
        args[7] = ((size shr 7) and 0x7f).toByte()
        val body = request(connection, cmd, args, readTimeoutMs, attempts = 3)
            ?: throw FM1UpdateError.BackupFailed("the FM-1 didn't answer a backup read")
        if (body.size < 9) throw FM1UpdateError.BackupFailed("a backup read came back short")
        val rc = body[1].toUInt8()
        if (rc != 0) throw FM1UpdateError.BackupFailed(rcMessage(rc))
        if (body[0].toUInt8() != id || u35(body, 2) != offset || (body[7].toUInt8() or (body[8].toUInt8() shl 7)) != size) {
            throw FM1UpdateError.BackupFailed("a backup read came back for the wrong range")
        }
        return unpack7(body.copyOfRange(9, body.size), size)
    }

    /** @return false when this firmware does not keep that object and nothing was written. */
    private suspend fun restoreObject(connection: MidiConnection, kind: EditorBackupKind, obj: EditorObject): Boolean {
        val cmd = if (kind == EditorBackupKind.FELUCCA) CMD_FELUCCA_PUT else CMD_SLOOP_PUT
        val begin = ByteArray(2 + 10)
        begin[0] = 0
        begin[1] = obj.id.toByte()
        u35(obj.bytes.size).copyInto(begin, 2)
        u35(obj.crc).copyInto(begin, 7)
        val opened = put(connection, cmd, begin)
        val skip = opened == 1 && kind == EditorBackupKind.FELUCCA && obj.id == 9 ||
            (opened == 1 || opened == 5) && kind == EditorBackupKind.SLOOP && obj.id in 8..11
        if (skip) return false
        if (opened != 0) throw FM1UpdateError.BackupFailed(rcMessage(opened))
        try {
            var offset = 0
            while (offset < obj.bytes.size) {
                val chunk = obj.bytes.copyOfRange(offset, minOf(offset + CHUNK, obj.bytes.size))
                val args = ByteArray(2 + 5 + pack7(chunk).size)
                args[0] = 1
                args[1] = obj.id.toByte()
                u35(offset).copyInto(args, 2)
                pack7(chunk).copyInto(args, 7)
                val rc = put(connection, cmd, args)
                if (rc != 0) throw FM1UpdateError.BackupFailed(rcMessage(rc))
                offset += chunk.size
            }
            val rc = put(connection, cmd, byteArrayOf(2, obj.id.toByte()), writeTimeoutMs)
            if (rc == 3) throw FM1UpdateError.BackupFailed("stop playback on the FM-1 first")
            if (rc != 0) throw FM1UpdateError.BackupFailed(rcMessage(rc))
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            runCatching { put(connection, cmd, byteArrayOf(3, obj.id.toByte())) }
            throw error
        }
        return true
    }

    private suspend fun put(connection: MidiConnection, cmd: Int, args: ByteArray, timeoutMs: Long = readTimeoutMs): Int {
        val body = request(connection, cmd, args, timeoutMs, attempts = 1)
            ?: throw FM1UpdateError.BackupFailed("the FM-1 didn't answer a backup write")
        if (body.size < 3) throw FM1UpdateError.BackupFailed("a backup write came back short")
        return body[2].toUInt8()
    }

    private suspend fun restoreSample(connection: MidiConnection, slot: Int, bytes: ByteArray) {
        if (bytes.isEmpty()) {
            sample(connection, CMD_SMP_ERASE, byteArrayOf(slot.toByte()), writeTimeoutMs, slot)
            return
        }
        if (bytes.size < DATA_OFF) throw FM1UpdateError.BackupFailed("a sample backup is too short")
        sample(connection, CMD_SMP_BEGIN, byteArrayOf(slot.toByte()), readTimeoutMs, slot)
        var offset = DATA_OFF
        while (offset < bytes.size) {
            val chunk = bytes.copyOfRange(offset, minOf(offset + CHUNK, bytes.size))
            val packed = pack7(chunk)
            val args = ByteArray(4 + packed.size)
            args[0] = slot.toByte()
            args[1] = (offset and 0x7f).toByte()
            args[2] = ((offset shr 7) and 0x7f).toByte()
            args[3] = ((offset shr 14) and 0x7f).toByte()
            packed.copyInto(args, 4)
            sample(connection, CMD_SMP_WRITE, args, readTimeoutMs, slot)
            offset += chunk.size
        }
        val header = pack7(bytes.copyOfRange(0, HEADER_LEN))
        val end = ByteArray(1 + header.size)
        end[0] = slot.toByte()
        header.copyInto(end, 1)
        sample(connection, CMD_SMP_END, end, writeTimeoutMs, slot)
    }

    private suspend fun sample(connection: MidiConnection, cmd: Int, args: ByteArray, timeoutMs: Long, slot: Int) {
        val body = request(connection, cmd, args, timeoutMs, attempts = 1)
            ?: throw FM1UpdateError.BackupFailed("the FM-1 didn't answer a sample write")
        if (body.isEmpty() || body[0].toUInt8() != slot) {
            throw FM1UpdateError.BackupFailed("a sample write came back for the wrong slot")
        }
        val rc = body.last().toUInt8()
        if (rc != 0) throw FM1UpdateError.BackupFailed(rcMessage(rc))
    }

    private suspend fun request(
        connection: MidiConnection,
        cmd: Int,
        args: ByteArray,
        timeoutMs: Long,
        attempts: Int,
    ): ByteArray? {
        repeat(attempts) {
            coroutineContext.ensureActive()
            if (connection.isLost) throw FM1UpdateError.BackupFailed("the FM-1 disconnected during the backup")
            connection.drain()
            try {
                connection.send(frame(cmd, args))
            } catch (_: Exception) {
                throw FM1UpdateError.BackupFailed("the FM-1 disconnected during the backup")
            }
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            while (System.nanoTime() < deadline) {
                coroutineContext.ensureActive()
                val remaining = ((deadline - System.nanoTime()) / 1_000_000L).coerceAtLeast(1)
                val sysex = connection.receive(remaining) ?: continue
                val body = unframe(sysex, cmd) ?: continue
                return body
            }
        }
        return null
    }

    companion object {
        const val CHUNK = 256
        const val DATA_OFF = 512
        const val HEADER_LEN = 32 + 16 * 28
        private const val CMD_INFO = 1
        private const val CMD_SMP_BEGIN = 11
        private const val CMD_SMP_WRITE = 12
        private const val CMD_SMP_END = 13
        private const val CMD_SMP_ERASE = 14
        private const val CMD_SLOOP_LIST = 34
        private const val CMD_SLOOP_GET = 35
        private const val CMD_SLOOP_PUT = 36
        private const val CMD_FELUCCA_LIST = 65
        private const val CMD_FELUCCA_GET = 66
        private const val CMD_FELUCCA_PUT = 67
        private val SLOOP_PUT = intArrayOf(6, 7, 8, 9, 10, 11, 2, 3, 4, 5, 0, 1)

        internal fun frame(cmd: Int, args: ByteArray): ByteArray {
            val out = ByteArray(6 + args.size)
            out[0] = 0xF0.toByte()
            out[1] = 0x7D
            out[2] = 0x46
            out[3] = 0x4C
            out[4] = cmd.toByte()
            args.copyInto(out, 5)
            out[out.lastIndex] = 0xF7.toByte()
            return out
        }

        internal fun unframe(sysex: ByteArray, cmd: Int): ByteArray? {
            if (sysex.size < 6 || sysex[0].toUInt8() != 0xF0 || sysex.last().toUInt8() != 0xF7) return null
            if (sysex[1].toUInt8() != 0x7D || sysex[2].toUInt8() != 0x46 || sysex[3].toUInt8() != 0x4C) return null
            if (sysex[4].toUInt8() != cmd) return null
            val body = sysex.copyOfRange(5, sysex.size - 1)
            if (body.any { it.toUInt8() > 0x7f }) return null
            return body
        }

        private fun parseInfo(body: ByteArray): EditorInfo {
            val cursor = Cursor(body)
            val version = cursor.string()
            val engines = cursor.byte()
            val pcount = cursor.byte()
            cursor.byte()
            cursor.byte()
            cursor.byte()
            repeat(engines) { cursor.string() }
            return EditorInfo(version, pcount, body.copyOfRange(cursor.index, body.size))
        }

        internal fun sniffEditorBackup(trailer: ByteArray): EditorBackupKind? {
            var index = 0
            while (index + 2 < trailer.size) {
                if (trailer[index].toUInt8() == 0x42 && trailer[index + 1].toUInt8() == 1) {
                    val caps = trailer[index + 2].toUInt8()
                    if (caps in 1..3) return EditorBackupKind.FELUCCA
                }
                index += 1
            }
            if (trailer.size == 2 && trailer[1].toUInt8() in 6..40) return EditorBackupKind.SLOOP
            return null
        }

        private fun parseFeluccaList(body: ByteArray): List<ObjectMeta>? {
            if (body.size < 3 || body[0].toUInt8() != 1) return null
            val count = body[2].toUInt8()
            if (body.size != 3 + count * 11) return null
            val rc = body[1].toUInt8()
            if (rc != 0) throw FM1UpdateError.BackupFailed(rcMessage(rc))
            val meta = readObjects(body, 3, count)
            if (!plausible(meta)) return null
            return meta
        }

        private fun parseSloopList(body: ByteArray): List<ObjectMeta>? {
            if (body.size < 2) return null
            val count = body[1].toUInt8()
            if (count < 2 || body.size != 2 + count * 11) return null
            val rc = body[0].toUInt8()
            if (rc != 0) throw FM1UpdateError.BackupFailed(rcMessage(rc))
            val meta = readObjects(body, 2, count)
            if (!plausible(meta)) return null
            return meta
        }

        private fun readObjects(body: ByteArray, start: Int, count: Int): List<ObjectMeta> {
            val meta = ArrayList<ObjectMeta>(count)
            for (index in 0 until count) {
                val at = start + index * 11
                val id = body[at].toUInt8()
                val length = u35(body, at + 1)
                val crc = u35Long(body, at + 6)
                if (length > if (id >= 32) 81_920 else 16_384) {
                    throw FM1UpdateError.BackupFailed("a backup object is larger than this app will read")
                }
                if (length == 0 && crc != 0L) throw FM1UpdateError.BackupFailed("an empty backup object had a checksum")
                meta += ObjectMeta(id, length, crc)
            }
            return meta
        }

        private fun plausible(meta: List<ObjectMeta>): Boolean {
            if (meta.map { it.id }.distinct().size != meta.size) return false
            val live = meta.firstOrNull { it.id == 0 } ?: return false
            val settings = meta.firstOrNull { it.id == 1 } ?: return false
            return live.length > 0 && settings.length > 0
        }

        internal fun restoreOrder(kind: EditorBackupKind, ids: Set<Int>): List<Int> = when (kind) {
            EditorBackupKind.FELUCCA ->
                ids.filter { it != 0 && it != 1 }.sortedBy { if (it >= 32) 100 + it else it } + listOf(1, 0)
            EditorBackupKind.SLOOP -> {
                val known = SLOOP_PUT.toSet()
                listOf(6, 7, 8, 9, 10, 11).filter { it in ids } +
                    ids.filter { it < 32 && it !in known }.sorted() +
                    listOf(2, 3, 4, 5, 0, 1).filter { it in ids } +
                    ids.filter { it >= 32 }.sorted()
            }
        }

        internal fun projectFormat(bytes: ByteArray?): Int {
            if (bytes == null || bytes.size < 4) return 0
            if (bytes[1].toUInt8() == 0x4E && bytes[2].toUInt8() == 0x55 && bytes[3].toUInt8() == 0x46) {
                return bytes[0].toUInt8() - 0x30
            }
            return 0
        }

        internal fun deviceProjectFormat(pcount: Int): Int = when {
            pcount >= 71 -> 6
            pcount >= 61 -> 5
            else -> 4
        }

        internal fun firmwareAtLeast(version: String, major: Int, minor: Int, patch: Int): Boolean {
            val match = Regex("""(\d+)\.(\d+)(?:\.(\d+))?""").find(version) ?: return false
            val got = intArrayOf(match.groupValues[1].toInt(), match.groupValues[2].toInt(), match.groupValues[3].toIntOrNull() ?: 0)
            val want = intArrayOf(major, minor, patch)
            for (index in 0..2) if (got[index] != want[index]) return got[index] > want[index]
            return true
        }

        private fun rcMessage(rc: Int): String = when (rc) {
            1 -> "the FM-1 refused that backup object"
            2 -> "the FM-1 rejected the backup"
            3 -> "stop playback on the FM-1 first"
            4 -> "the FM-1 couldn't write its flash"
            5 -> "start the backup again"
            else -> "the FM-1 answered backup status $rc"
        }

        private fun isoNow(): String {
            val now = java.time.Instant.now().toString()
            return now
        }
    }
}

private class Cursor(private val bytes: ByteArray) {
    var index: Int = 0
        private set

    fun byte(): Int {
        if (index >= bytes.size) throw FM1UpdateError.BackupFailed("the FM-1's editor reply was short")
        return bytes[index++].toUInt8()
    }

    fun string(): String {
        val out = StringBuilder()
        while (true) {
            val value = byte()
            if (value == 0) return out.toString()
            out.append(value.toChar())
        }
    }
}

internal fun editorCrc32(bytes: ByteArray): Long {
    var crc = 0xffff_ffffL
    for (value in bytes) {
        crc = crc xor value.toUInt8().toLong()
        repeat(8) {
            crc = if (crc and 1L == 0L) crc ushr 1 else (crc ushr 1) xor 0xedb88320L
        }
    }
    return crc.inv() and 0xffff_ffffL
}

internal fun u35(value: Int): ByteArray = u35(value.toLong() and 0xffff_ffffL)

internal fun u35(value: Long): ByteArray {
    val bits = value and 0xffff_ffffL
    return ByteArray(5) { index -> ((bits ushr (7 * index)) and 0x7f).toByte() }
}

internal fun u35(bytes: ByteArray, offset: Int): Int = u35Long(bytes, offset).toInt()

internal fun u35Long(bytes: ByteArray, offset: Int): Long {
    if (bytes.size < offset + 5) throw FM1UpdateError.BackupFailed("a backup number was cut short")
    var value = 0L
    for (index in 0 until 5) value = value or ((bytes[offset + index].toUInt8().toLong()) shl (7 * index))
    return value and 0xffff_ffffL
}

internal fun pack7(bytes: ByteArray): ByteArray {
    val out = ArrayList<Byte>(bytes.size + bytes.size / 7 + 1)
    var offset = 0
    while (offset < bytes.size) {
        val count = minOf(7, bytes.size - offset)
        var mask = 0
        val low = ByteArray(count)
        for (index in 0 until count) {
            val value = bytes[offset + index].toUInt8()
            mask = mask or ((value ushr 7) shl index)
            low[index] = (value and 0x7f).toByte()
        }
        out += mask.toByte()
        low.forEach { out += it }
        offset += count
    }
    return out.toByteArray()
}

internal fun unpack7(packed: ByteArray, size: Int): ByteArray {
    val out = ByteArray(size)
    var source = 0
    var dest = 0
    while (dest < size) {
        if (source >= packed.size) throw FM1UpdateError.BackupFailed("a backup chunk was short")
        val count = minOf(7, size - dest)
        val mask = packed[source++].toUInt8()
        if (mask ushr count != 0) throw FM1UpdateError.BackupFailed("a backup chunk was damaged")
        for (index in 0 until count) {
            if (source >= packed.size) throw FM1UpdateError.BackupFailed("a backup chunk was short")
            val low = packed[source++].toUInt8()
            if (low > 127) throw FM1UpdateError.BackupFailed("a backup chunk was damaged")
            out[dest++] = (low or ((mask ushr index and 1) shl 7)).toByte()
        }
    }
    if (source != packed.size) throw FM1UpdateError.BackupFailed("a backup chunk had trailing bytes")
    return out
}

private fun jsonString(value: String): String = buildString {
    append('"')
    for (char in value) {
        when (char) {
            '\\', '"' -> append('\\').append(char)
            else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
        }
    }
    append('"')
}

private fun jsonField(text: String, name: String): String? {
    val match = Regex(""""$name"\s*:\s*"((?:\\.|[^"\\])*)"""").find(text) ?: return null
    return match.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\")
}
