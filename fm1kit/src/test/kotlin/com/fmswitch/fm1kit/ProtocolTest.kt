package com.fmswitch.fm1kit

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SevenBitTest {
    @Test
    fun packsLeastSignificantBitFirst() {
        assertArrayEquals(byteArrayOf(0x7F, 0x01), SevenBit.pack(byteArrayOf(0xFF.toByte())))
        assertArrayEquals(
            byteArrayOf(0x00, 0x32, 0x41, 0x01),
            SevenBit.pack(byteArrayOf(0x00, 0x59, 0x30)),
        )
    }

    @Test
    fun roundTripsEveryLength() {
        for (length in 0..40) {
            val bytes = ByteArray(length) { i -> (((i * 37) + 11) and 0xFF).toByte() }
            val unpacked = SevenBit.unpack(SevenBit.pack(bytes))
            assertArrayEquals(bytes, unpacked.copyOf(length))
            assertTrue(SevenBit.pack(bytes).all { it.toUInt8() < 0x80 })
        }
    }
}

class MessageTest {
    @Test
    fun handshakeQueryDecodesToIdentityCommand() {
        val body = SevenBit.unpack(FM1Message.HANDSHAKE_QUERY.copyOfRange(1, FM1Message.HANDSHAKE_QUERY.size - 1))
        assertEquals(0x00, body[0].toUInt8())
        assertEquals(0x59, body[1].toUInt8())
        assertEquals(FM1Message.IDENTITY_COMMAND, body[2].toUInt8())
    }

    @Test
    fun readRequestRoundTrips() {
        val request = FM1ReadRequest(flashType = 1, address = 0x00012340, length = 481)
        val parsed = FM1ReadRequest.parse(request.encoded())
        assertEquals(request, parsed)
    }

    @Test
    fun readRequestWithBadChecksumIsIgnored() {
        val body = SevenBit.unpack(
            FM1ReadRequest(address = 0x100, length = 16).encoded().let { it.copyOfRange(1, it.size - 1) },
        )
        body[14] = (body[14].toUInt8() xor 0x01).toByte()
        assertNull(FM1ReadRequest.parse(byteArrayOf(0xF0.toByte()) + SevenBit.pack(body) + byteArrayOf(0xF7.toByte())))
    }

    @Test
    fun responseDeclaresTheFullLength() {
        val request = FM1ReadRequest(address = 0x40, length = 481)
        val data = ByteArray(481) { it.toByte() }
        val parsed = FM1ReadRequest.parseResponse(request.response(data))!!
        assertEquals(0x40L, parsed.first.address)
        assertArrayEquals(data, parsed.second)
        val body = SevenBit.unpack(request.response(data).let { it.copyOfRange(1, it.size - 1) })
        assertEquals(481 + 8, readLittleEndian(body, 3, 3).toInt())
    }

    @Test
    fun identityRoundTripsAndRecognisesTheLoader() {
        val identity = FM1Identity.parse("ota-FM-1_009")!!
        assertTrue(identity.isLoader)
        assertEquals("FM-1", identity.hardwareModel)
        assertEquals(9, identity.version)
        assertEquals(identity, FM1Identity.fromHandshakeReply(identity.handshakeReply()))
        assertEquals(91035, FM1Identity.parse("FM-1_91035")?.version)
        assertNull(FM1Identity.parse("nonsense"))
        assertTrue(FM1Identity.parse("FM-1_8000012")!!.isOpenFirmware)
        assertTrue(OpenFirmware.isOpen("FM1_OFFICIAL_OPEN"))
    }
}

class PackageTest {
    @Test
    fun bundledV15ParsesAndMatchesOfficialHash() {
        val bytes = javaClass.classLoader!!.getResourceAsStream("FM-1_015.fwsc")!!.readBytes()
        val package_ = FwscPackage.read(bytes)
        assertEquals("FM-1_015", package_.product)
        assertEquals(OfficialFirmware.V15.sha256, package_.sha256)
        assertEquals(OfficialFirmware.V15, OfficialFirmware.matching(package_))
        assertEquals(OfficialFirmware.V15.size, bytes.size)
        assertNotNull(package_.factoryVoices())
        assertEquals(FwscPackage.FACTORY_VOICES_LENGTH, package_.factoryVoices()!!.size)
        assertEquals(FwscPackage.EXPECTED_MODEL, package_.identity.model)
        assertEquals(OfficialFirmware.STOCK_LOADER_SHA256, package_.loaderSHA256)
    }

    @Test
    fun rejectsTruncatedFiles() {
        try {
            FwscPackage.read(ByteArray(10))
            throw AssertionError("expected failure")
        } catch (_: FwscFailure.TooShort) {
        }
    }
}

class MidiProbeTest {
    @Test
    fun probesGenericUsbMidiAndJieLiNames() {
        assertTrue(FM1Updater.isProbeable(MidiPortDescriptor("1", "USB MIDI")))
        assertTrue(FM1Updater.isProbeable(MidiPortDescriptor("1", "USB-MIDI")))
        assertTrue(FM1Updater.isProbeable(MidiPortDescriptor("1", "JieLi AC791N")))
        assertTrue(FM1Updater.isProbeable(MidiPortDescriptor("1", "FM-1 Midi")))
        assertTrue(FM1Updater.isProbeable(MidiPortDescriptor("1", "clipper-fm1")))
    }

    @Test
    fun prefersFm1NameAndFallsBackToUnnamedPorts() {
        val mixed = listOf(
            MidiPortDescriptor("generic", "USB-MIDI"),
            MidiPortDescriptor("fm1", "FM-1 Midi"),
        )
        assertEquals("fm1", FM1Updater.portsToProbe(mixed).first().id)
        val unnamed = listOf(MidiPortDescriptor("x", "MIDI 2"))
        assertEquals(listOf("x"), FM1Updater.portsToProbe(unnamed).map { it.id })
    }
}
