package com.fmswitch.fm1kit

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorBackupCodecTest {
    @Test
    fun crc32MatchesTheEditorVector() {
        assertEquals(0xcbf43926L, editorCrc32("123456789".toByteArray(Charsets.US_ASCII)))
        assertEquals(0L, editorCrc32(ByteArray(0)))
    }

    @Test
    fun sevenBitGroupsRoundTrip() {
        val data = byteArrayOf(0x00, 0x7f, 0x80.toByte(), 0xff.toByte(), 0x01, 0x02, 0x03, 0x04)
        val packed = pack7(data)
        assertTrue(packed.all { it.toUInt8() < 0x80 })
        assertArrayEquals(data, unpack7(packed, data.size))
    }

    @Test
    fun archiveJsonRoundTripsTheEditorShape() {
        val bytes = byteArrayOf(1, 2, 3, 4, 0x80.toByte())
        val archive = EditorArchive(
            EditorBackupKind.FELUCCA,
            "Felucca 1.6.1",
            "2026-10-21T00:00:00Z",
            listOf(
                EditorObject(0, bytes, editorCrc32(bytes)),
                EditorObject(1, byteArrayOf(9), editorCrc32(byteArrayOf(9))),
            ),
        )
        val text = archive.toJson()
        assertTrue(text.contains("\"format\":\"felucca-backup\""))
        assertTrue(text.contains("\"created\":"))
        assertTrue(text.contains("\"size\":"))
        val again = EditorArchive.parse(text)
        assertEquals(EditorBackupKind.FELUCCA, again.kind)
        assertEquals("Felucca 1.6.1", again.firmware)
        assertArrayEquals(bytes, again.objects[0].bytes)
        val sloop = archive.copy(kind = EditorBackupKind.SLOOP)
        val sloopText = sloop.toJson()
        assertTrue(sloopText.contains("\"format\":\"sloop-backup\""))
        assertTrue(sloopText.contains("\"date\":"))
        assertTrue(sloopText.contains("\"len\":"))
        assertEquals(EditorBackupKind.SLOOP, EditorArchive.parse(sloopText).kind)
    }

    @Test
    fun sniffSeparatesTheTwoTrailers() {
        val felucca = byteArrayOf(0x55, 1, 9, 0x4d, 1, 64, 1, 0x42, 1, 3)
        assertEquals(EditorBackupKind.FELUCCA, EditorLibrarian.sniffEditorBackup(felucca))
        assertEquals(EditorBackupKind.SLOOP, EditorLibrarian.sniffEditorBackup(byteArrayOf(4, 11)))
        assertNull(EditorLibrarian.sniffEditorBackup(byteArrayOf(4, 5)))
        assertNull(EditorLibrarian.sniffEditorBackup(ByteArray(0)))
    }

    @Test
    fun sloopRestoreOrderWritesBanksBeforeTheLiveProjectAndSamplesLast() {
        val order = EditorLibrarian.restoreOrder(EditorBackupKind.SLOOP, setOf(0, 1, 2, 6, 8, 10, 11, 32, 35, 40))
        assertEquals(listOf(6, 8, 10, 11, 2, 0, 1, 32, 35, 40), order)
    }

    @Test
    fun onlyCommunityFirmwareIsProbed() {
        assertTrue(FM1Identity.parse("FM-1_916")!!.mightHaveEditorBackup)
        assertTrue(FM1Identity.parse("FM-1_900")!!.mightHaveEditorBackup)
        assertTrue(FM1Identity.parse("FM-1_909")!!.mightHaveEditorBackup)
        assertFalse(FM1Identity.parse("FM-1_102")!!.mightHaveEditorBackup)
        assertFalse(FM1Identity.parse("FM-1_015")!!.mightHaveEditorBackup)
        assertFalse(FM1Identity.parse("ota-FM-1_009")!!.mightHaveEditorBackup)
        assertFalse(FM1Identity.parse("FM-1_8000400")!!.mightHaveEditorBackup)
    }
}

class EditorLibrarianTest {
    private val fast = EditorLibrarian(infoTimeoutMs = 40, listTimeoutMs = 40, readTimeoutMs = 40, writeTimeoutMs = 40)

    @Test
    fun feluccaDumpRoundTripsAndIgnoresTheUiStateCommand() = runBlocking {
        val device = MemoryEditorDevice.felucca()
        val archive = fast.capture(device, "FM-1_916")!!
        device.close()
        assertEquals(EditorBackupKind.FELUCCA, archive.kind)
        assertEquals("Felucca 1.6.1", archive.firmware)
        assertEquals(listOf(0, 1, 2, 8, 9, 32), archive.objects.map { it.id })
        assertEquals(0, archive.objects.first { it.id == 8 }.bytes.size)
        assertArrayEquals(device.original(0), archive.objects.first { it.id == 0 }.bytes)
        val text = archive.toJson()
        val other = MemoryEditorDevice.felucca(clear = true)
        fast.restore(other, EditorArchive.parse(text))
        val again = fast.capture(other, "FM-1_916")!!
        other.close()
        assertArrayEquals(archive.objects.first { it.id == 0 }.bytes, again.objects.first { it.id == 0 }.bytes)
        assertArrayEquals(archive.objects.first { it.id == 32 }.bytes, again.objects.first { it.id == 32 }.bytes)
        assertEquals(0, other.uiStateReads)
    }

    @Test
    fun sloopDumpKeepsTheFm6BankAndTheSamples() = runBlocking {
        val device = MemoryEditorDevice.sloop()
        assertEquals(EditorBackupKind.SLOOP, fast.detect(device))
        val archive = fast.capture(device, "FM-1_900")!!
        device.close()
        assertEquals("SLOOP 2.7.1", archive.firmware)
        assertTrue(archive.objects.any { it.id == 8 && it.bytes.size == 27 * 128 })
        assertTrue(archive.objects.any { it.id == 32 && it.bytes.isNotEmpty() })
        val wiped = MemoryEditorDevice.sloop(clear = true)
        fast.restore(wiped, archive)
        val again = fast.capture(wiped, "FM-1_900")!!
        wiped.close()
        assertArrayEquals(
            archive.objects.first { it.id == 8 }.bytes,
            again.objects.first { it.id == 8 }.bytes,
        )
        assertArrayEquals(
            archive.objects.first { it.id == 0 }.bytes,
            again.objects.first { it.id == 0 }.bytes,
        )
    }

    @Test
    fun aSloopBackupNewerThanTheDeviceIsRefusedBeforeWriting() = runBlocking {
        val device = MemoryEditorDevice.sloop(pcount = 50)
        val archive = EditorArchive(
            EditorBackupKind.SLOOP,
            "SLOOP 2.7.1",
            "2026-10-21T00:00:00Z",
            listOf(
                EditorObject(0, funProject(6), editorCrc32(funProject(6))),
                EditorObject(1, byteArrayOf(1, 2, 3, 4), editorCrc32(byteArrayOf(1, 2, 3, 4))),
            ),
        )
        try {
            fast.restore(device, archive)
            throw AssertionError("a newer project should be refused")
        } catch (error: FM1UpdateError.BackupFailed) {
            assertTrue(error.message!!.contains("cannot open"))
        }
        assertEquals(0, device.puts)
        device.close()
    }

    @Test
    fun aDeviceThatDoesNotSpeakTheEditorHasNoBackup() = runBlocking {
        val device = MemoryEditorDevice.silent()
        assertNull(fast.detect(device))
        assertNull(fast.capture(device, "FM-1_015"))
        device.close()
    }

    @Test
    fun feluccaUiStateIsNotASloopBackup() = runBlocking {
        val device = MemoryEditorDevice.feluccaUiOnly()
        assertNull(fast.detect(device))
        device.close()
        assertTrue(device.sawList34)
    }

    @Test
    fun aChangedDeviceFailsTheChecksumAndDoesNotPretendSuccess() = runBlocking {
        val device = MemoryEditorDevice.felucca(mutateDuringRead = true)
        try {
            fast.capture(device, "FM-1_916")
            throw AssertionError("checksum should fail")
        } catch (error: FM1UpdateError.BackupFailed) {
            assertTrue(error.message!!.contains("changed"))
        }
        device.close()
    }
}

private fun unpackPacked(packed: ByteArray): ByteArray {
    val size = packed.size / 8 * 7 + if (packed.size % 8 == 0) 0 else packed.size % 8 - 1
    return unpack7(packed, size)
}

private fun funProject(format: Int): ByteArray {
    val bytes = ByteArray(16)
    bytes[0] = (0x30 + format).toByte()
    bytes[1] = 0x4E
    bytes[2] = 0x55
    bytes[3] = 0x46
    return bytes
}

/**
 * Just enough of the editor SysEx to stand in for a Felucca or SLOOP device.
 * Command 34 on Felucca is UI state, a short reply that must not parse as a backup list.
 */
private class MemoryEditorDevice(
    private val kind: EditorBackupKind?,
    private val version: String,
    private val pcount: Int,
    private val mutateDuringRead: Boolean,
    private val uiOnly: Boolean = false,
) : MidiConnection {
    private val objects = linkedMapOf<Int, ByteArray>()
    private var pending: ByteArray? = null
    private var staging: ByteArray? = null
    private var stagingId = -1
    private var sampleSlot = ByteArray(0)
    private var sampleUsed = 0
    private var mutated = false
    var puts: Int = 0
    var uiStateReads: Int = 0
    var sawList34: Boolean = false
    override val port = MidiPortDescriptor("mem", "memory")
    override val isLost = false

    fun original(id: Int): ByteArray = objects.getValue(id).copyOf()

    override fun send(sysex: ByteArray) {
        val cmd = sysex.getOrNull(4)?.toUInt8() ?: return
        val args = if (sysex.size >= 6) sysex.copyOfRange(5, sysex.size - 1) else ByteArray(0)
        pending = when (cmd) {
            1 -> if (version.isEmpty()) null else infoFrame()
            65 -> if (kind == EditorBackupKind.FELUCCA && !uiOnly) listFrame(65, prefix = byteArrayOf(1, 0)) else null
            66 -> if (kind == EditorBackupKind.FELUCCA) getFrame(66, args) else null
            67 -> if (kind == EditorBackupKind.FELUCCA) putFrame(67, args) else null
            34 -> when {
                kind == EditorBackupKind.SLOOP && !uiOnly -> listFrame(34, prefix = byteArrayOf(0))
                uiOnly || kind == EditorBackupKind.FELUCCA -> {
                    uiStateReads += 1
                    sawList34 = true
                    frame(34, ByteArray(13))
                }
                else -> {
                    sawList34 = true
                    null
                }
            }
            35 -> if (kind == EditorBackupKind.SLOOP) getFrame(35, args) else null
            36 -> if (kind == EditorBackupKind.SLOOP) putFrame(36, args) else null
            11 -> sampleBegin(args)
            12 -> sampleWrite(args)
            13 -> sampleEnd(args)
            14 -> sampleErase(args)
            else -> null
        }
    }

    override suspend fun receive(timeoutMs: Long): ByteArray? = pending.also { pending = null }

    override fun drain() {
        pending = null
    }

    override fun close() = Unit

    private fun infoFrame(): ByteArray {
        val body = ArrayList<Byte>()
        version.encodeToByteArray().forEach { body += it }
        body += 0
        body += 1
        body += pcount.toByte()
        body += 4
        body += 64
        body += 40
        "FM6".encodeToByteArray().forEach { body += it }
        body += 0
        val trailer = when {
            uiOnly -> byteArrayOf(4, 3)
            kind == EditorBackupKind.SLOOP -> byteArrayOf(4, 11)
            else -> byteArrayOf(0x55, 1, 9, 0x4d, 1, 64, 1, 0x42, 1, 3)
        }
        trailer.forEach { body += it }
        return frame(1, body.toByteArray())
    }

    private fun listFrame(cmd: Int, prefix: ByteArray): ByteArray {
        val body = ArrayList<Byte>()
        prefix.forEach { body += it }
        body += objects.size.toByte()
        for ((id, bytes) in objects) {
            body += id.toByte()
            u35(bytes.size).forEach { body += it }
            u35(editorCrc32(bytes)).forEach { body += it }
        }
        return frame(cmd, body.toByteArray())
    }

    private fun getFrame(cmd: Int, args: ByteArray): ByteArray {
        val id = args[0].toUInt8()
        val offset = u35(args, 1)
        val size = args[6].toUInt8() or (args[7].toUInt8() shl 7)
        val stored = objects.getValue(id)
        if (mutateDuringRead && id == 0 && !mutated) {
            stored[0] = (stored[0].toUInt8() xor 1).toByte()
            mutated = true
        }
        val data = stored.copyOfRange(offset, offset + size)
        val body = ArrayList<Byte>()
        body += id.toByte()
        body += 0
        u35(offset).forEach { body += it }
        body += (size and 0x7f).toByte()
        body += ((size shr 7) and 0x7f).toByte()
        pack7(data).forEach { body += it }
        return frame(cmd, body.toByteArray())
    }

    private fun putFrame(cmd: Int, args: ByteArray): ByteArray {
        puts += 1
        val op = args[0].toUInt8()
        val id = args[1].toUInt8()
        val rc = when (op) {
            0 -> {
                val length = u35(args, 2)
                staging = ByteArray(length)
                stagingId = id
                0
            }
            1 -> {
                val offset = u35(args, 2)
                val chunk = unpackPacked(args.copyOfRange(7, args.size))
                chunk.copyInto(staging!!, offset)
                0
            }
            2 -> {
                objects[stagingId] = staging!!
                staging = null
                0
            }
            else -> 0
        }
        return frame(cmd, byteArrayOf(op.toByte(), id.toByte(), rc.toByte()))
    }

    private fun sampleBegin(args: ByteArray): ByteArray {
        sampleSlot = ByteArray(81_920)
        sampleUsed = EditorLibrarian.DATA_OFF
        return frame(11, byteArrayOf(args[0], 0))
    }

    private fun sampleWrite(args: ByteArray): ByteArray {
        val offset = args[1].toUInt8() or (args[2].toUInt8() shl 7) or (args[3].toUInt8() shl 14)
        val chunk = unpackPacked(args.copyOfRange(4, args.size))
        chunk.copyInto(sampleSlot, offset)
        sampleUsed = maxOf(sampleUsed, offset + chunk.size)
        return frame(12, byteArrayOf(args[0], args[1], args[2], args[3], 0))
    }

    private fun sampleEnd(args: ByteArray): ByteArray {
        val header = unpackPacked(args.copyOfRange(1, args.size))
        header.copyInto(sampleSlot, 0)
        objects[32 + args[0].toUInt8()] = sampleSlot.copyOf(maxOf(sampleUsed, EditorLibrarian.DATA_OFF))
        return frame(13, byteArrayOf(args[0], 0))
    }

    private fun sampleErase(args: ByteArray): ByteArray {
        objects[32 + args[0].toUInt8()] = ByteArray(0)
        return frame(14, byteArrayOf(args[0], 0))
    }

    private fun frame(cmd: Int, args: ByteArray): ByteArray = EditorLibrarian.frame(cmd, args)

    companion object {
        fun felucca(clear: Boolean = false, mutateDuringRead: Boolean = false): MemoryEditorDevice {
            val device = MemoryEditorDevice(EditorBackupKind.FELUCCA, "Felucca 1.6.1", 64, mutateDuringRead)
            if (!clear) {
                device.objects[0] = ByteArray(32) { it.toByte() }
                device.objects[1] = ByteArray(8) { 3 }
                device.objects[2] = ByteArray(0)
                device.objects[8] = ByteArray(0)
                device.objects[9] = ByteArray(4) { 7 }
                device.objects[32] = sample(4)
            } else {
                device.objects[0] = ByteArray(32)
                device.objects[1] = ByteArray(8)
            }
            return device
        }

        fun sloop(clear: Boolean = false, pcount: Int = 71): MemoryEditorDevice {
            val device = MemoryEditorDevice(EditorBackupKind.SLOOP, "SLOOP 2.7.1", pcount, mutateDuringRead = false)
            if (!clear) {
                device.objects[0] = funProject(6)
                device.objects[1] = ByteArray(12) { 1 }
                device.objects[2] = ByteArray(0)
                device.objects[6] = ByteArray(6) { 2 }
                device.objects[8] = ByteArray(27 * 128) { (it and 0xff).toByte() }
                device.objects[32] = sample(9)
                device.objects[35] = ByteArray(0)
            } else {
                device.objects[0] = funProject(6)
                device.objects[1] = ByteArray(12)
            }
            return device
        }

        fun feluccaUiOnly(): MemoryEditorDevice =
            MemoryEditorDevice(EditorBackupKind.FELUCCA, "Felucca 1.0", 40, mutateDuringRead = false, uiOnly = true)

        fun silent(): MemoryEditorDevice =
            MemoryEditorDevice(null, "", 0, mutateDuringRead = false)

        private fun sample(seed: Int): ByteArray {
            val bytes = ByteArray(520)
            bytes[0] = seed.toByte()
            for (index in EditorLibrarian.DATA_OFF until bytes.size) bytes[index] = (seed + index).toByte()
            return bytes
        }
    }
}
