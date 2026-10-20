package com.fmswitch.fm1kit

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProbeProblemTest {
    private val port = MidiPortDescriptor("1", "FM-1")

    private class Silent(override val port: MidiPortDescriptor) : MidiConnection {
        override val isLost = false
        override fun send(sysex: ByteArray) = Unit
        override suspend fun receive(timeoutMs: Long): ByteArray? = null
        override fun drain() = Unit
        override fun close() = Unit
    }

    private fun transport(open: (MidiPortDescriptor) -> MidiConnection) = object : MidiTransport {
        override suspend fun ports() = listOf(port)
        override suspend fun open(port: MidiPortDescriptor) = open(port)
        override fun changes(onChange: () -> Unit) = AutoCloseable { }
    }

    @Test
    fun saysWhenThePortWontOpen() = runBlocking {
        val updater = FM1Updater(
            transport { throw MidiTransportError.Unavailable("FM-1 refused its output port 0") },
            UpdaterTiming.Fast,
        )
        assertNull(updater.find())
        val problem = updater.lastProbeProblem!!
        assertTrue(problem, problem.startsWith("FM-1: couldn't open it"))
        assertTrue(problem, problem.contains("refused its output port 0"))
    }

    @Test
    fun saysWhenNothingAnswersTheHandshake() = runBlocking {
        val updater = FM1Updater(transport { Silent(it) }, UpdaterTiming.Fast)
        assertNull(updater.find())
        assertEquals("FM-1: opened, but nothing answered the FM-1 handshake.", updater.lastProbeProblem)
    }

    @Test
    fun clearsOnceTheFm1Answers() = runBlocking {
        val fm1 = SimulatedFM1(SimulatedOptions(requestIntervalMs = 0))
        val updater = FM1Updater(fm1, UpdaterTiming.Fast)
        assertNotNull(updater.find())
        assertNull(updater.lastProbeProblem)
        fm1.shutdown()
    }
}
