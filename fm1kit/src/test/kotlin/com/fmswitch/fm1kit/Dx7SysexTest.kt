package com.fmswitch.fm1kit

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class Dx7SysexTest {
    @Test
    fun readsTheFm1FactoryBank() {
        val text = javaClass.getResourceAsStream("/patches/fm1-bank1.b64")!!.bufferedReader().readText()
        val bank = Dx7Bank.parse(Base64.getMimeDecoder().decode(text))
        assertEquals(32, bank.voices.size)
        assertEquals("PIANO 1", bank.name(0))
        assertEquals("SYN PAD 2", bank.name(7))
        val again = Dx7Bank.parse(bank.bankMessage())
        assertEquals(bank.name(0), again.name(0))
        assertArrayEquals(bank.voices[3], again.voices[3])
    }

    @Test
    fun singleVoiceGoesToTheEditBuffer() {
        val bank = factoryBank()
        val message = bank.singleVoiceMessage(0)
        assertEquals(0xF0, message[0].toUInt8())
        assertEquals(0x43, message[1].toUInt8())
        assertEquals(0x00, message[2].toUInt8())
        assertEquals(0x00, message[3].toUInt8())
        assertEquals(0x01, message[4].toUInt8())
        assertEquals(0x1B, message[5].toUInt8())
        assertEquals(163, message.size)
        assertEquals(0xF7, message.last().toUInt8())
        val data = message.copyOfRange(6, 161)
        assertEquals(Dx7Bank.checksum(data), message[161].toUInt8())
        assertEquals(27, message[161].toUInt8())
        assertEquals(95, data[0].toUInt8())
        assertEquals(95, data[128].toUInt8())
    }

    @Test
    fun parameterMessagesCoverTheWholeVoice() {
        val messages = factoryBank().parameterMessages(0)
        assertEquals(155, messages.size)
        assertArrayEquals(
            byteArrayOf(0xF0.toByte(), 0x43, 0x10, 0x00, 0x00, messages[0][5], 0xF7.toByte()),
            messages[0],
        )
        assertEquals(0x01, messages[128][3].toUInt8())
        assertEquals(0x00, messages[128][4].toUInt8())
    }

    @Test
    fun v15HoldsAPatchUntilSaveAndReplacesABank() {
        val identity = FM1Identity.OFFICIAL_V15
        val bank = factoryBank()
        val voice = planVoiceSend(identity, bank, 0) as PatchPlan.Ready
        assertEquals(1, voice.messages.size)
        assertEquals("Sent. Hold SAVE on the FM-1 to store it.", voice.done)
        val whole = planBankSend(identity, bank) as PatchPlan.Ready
        assertEquals(4104, whole.messages.single().size)
        assertTrue(whole.done.contains("32"))
    }

    @Test
    fun fm1VaEditsTheSelectedPreset() {
        val identity = FM1Identity.parse("FM-1_102")!!
        val voice = planVoiceSend(identity, factoryBank(), 1) as PatchPlan.Ready
        assertEquals(155, voice.messages.size)
        assertEquals("Sent to the preset that's selected. Save it on the FM-1 to keep it.", voice.done)
        assertTrue(planBankSend(identity, factoryBank()) is PatchPlan.Ready)
    }

    @Test
    fun officialOpenTakesOnePatchOntoATrack() {
        val identity = FM1Identity.parse("FM-1_8000000")!!
        val voice = planVoiceSend(identity, factoryBank(), 0) as PatchPlan.Ready
        assertEquals("Sent to the DX7 track on channel 1.", voice.done)
        val bank = planBankSend(identity, factoryBank()) as PatchPlan.Refused
        assertTrue(bank.reason.contains("one patch"))
    }

    @Test
    fun otherFirmwaresAreRefused() {
        val identity = FM1Identity.parse("FM-1_911")!!
        assertTrue(planVoiceSend(identity, factoryBank(), 0) is PatchPlan.Refused)
        assertTrue(planBankSend(identity, factoryBank()) is PatchPlan.Refused)
        val loader = planVoiceSend(FM1Identity.parse("ota-FM-1_009")!!, factoryBank(), 0) as PatchPlan.Refused
        assertTrue(loader.reason.contains("update loader"))
    }

    private fun factoryBank(): Dx7Bank {
        val text = javaClass.getResourceAsStream("/patches/fm1-bank1.b64")!!.bufferedReader().readText()
        return Dx7Bank.parse(Base64.getMimeDecoder().decode(text))
    }
}
