package com.fmswitch.fm1kit

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    private fun v15(): FwscPackage {
        val bytes = javaClass.classLoader!!.getResourceAsStream("FM-1_015.fwsc")!!.readBytes()
        return FwscPackage.read(bytes)
    }

    private fun device(options: SimulatedOptions = SimulatedOptions()): SimulatedFM1 =
        SimulatedFM1(
            options.copy(
                requestIntervalMs = 0,
                rebootDurationMs = 40,
            ),
        )

    @Test
    fun identifiesSimulatedDevice() = runBlocking {
        val fm1 = device()
        val updater = FM1Updater(fm1, UpdaterTiming.Fast)
        assertEquals("FM-1_900", updater.identify()?.text)
        fm1.shutdown()
    }

    @Test
    fun installsV15OnSimulatedDevice() = runBlocking {
        val fm1 = device()
        val package_ = v15()
        fm1.prepare(package_)
        val updater = FM1Updater(fm1, UpdaterTiming.Fast)
        val events = ArrayList<UpdateEvent>()
        val identity = updater.install(
            package_,
            InstallOptions(confirmsSwitchingFirmware = true),
        ) { events.add(it) }
        assertEquals(FM1Identity.OFFICIAL_V15, identity)
        assertEquals("FM-1_015", updater.identify()?.text)
        assertTrue(events.any { it is UpdateEvent.Staging })
        assertTrue(events.any { it is UpdateEvent.Writing })
        assertTrue(events.any { it is UpdateEvent.Finished })
        fm1.shutdown()
    }

    @Test
    fun resumesAfterCablePull() = runBlocking {
        val fm1 = device(SimulatedOptions(unplugDuringWriteAt = 0.45))
        val package_ = v15()
        fm1.prepare(package_)
        val updater = FM1Updater(fm1, UpdaterTiming.Fast)
        try {
            updater.install(package_, InstallOptions(confirmsSwitchingFirmware = true))
            throw AssertionError("install should have been interrupted")
        } catch (error: FM1UpdateError) {
            assertTrue(error is FM1UpdateError.Disconnected || error is FM1UpdateError.LoaderNotFound || error is FM1UpdateError.Stalled)
        }
        val identity = updater.resume(package_)
        assertEquals(FM1Identity.OFFICIAL_V15, identity)
        fm1.shutdown()
    }

    @Test
    fun refusesV15OverV15WithoutSwitch() = runBlocking {
        val fm1 = device(SimulatedOptions(installedIdentity = FM1Identity.OFFICIAL_V15))
        val package_ = v15()
        fm1.prepare(package_)
        val updater = FM1Updater(fm1, UpdaterTiming.Fast)
        try {
            updater.install(package_, InstallOptions(confirmsSwitchingFirmware = true))
            throw AssertionError("expected already installed")
        } catch (_: FM1UpdateError.AlreadyInstalled) {
        }
        fm1.shutdown()
    }

    @Test
    fun fomniSwitchesWithTheOrdinaryUpdate() = runBlocking {
        assertFalse(FM1Identity.parse("FM-1_8000400")!!.isOpenFirmware)
        val fm1 = device(SimulatedOptions(installedIdentity = FM1Identity.parse("FM-1_8000400")!!))
        val package_ = v15()
        fm1.prepare(package_)
        val updater = FM1Updater(fm1, UpdaterTiming.Fast)
        val check = updater.check(package_)
        assertEquals(InstallKind.SWITCHING_FIRMWARE, check.kind)
        assertEquals(null, check.capability)
        val identity = updater.install(package_, InstallOptions(confirmsSwitchingFirmware = true))
        assertEquals(FM1Identity.OFFICIAL_V15, identity)
        fm1.shutdown()
    }

    @Test
    fun officialOpenArmsBeforeTheUpdate() = runBlocking {
        val fm1 = device(SimulatedOptions(installedIdentity = FM1Identity.parse("FM-1_8000014")!!))
        val package_ = v15()
        fm1.prepare(package_)
        val updater = FM1Updater(fm1, UpdaterTiming.Fast)
        val check = updater.check(package_)
        assertEquals(InstallKind.SWITCHING_FIRMWARE, check.kind)
        assertTrue(check.capability != null)
        val identity = updater.install(package_, InstallOptions(confirmsSwitchingFirmware = true))
        assertEquals(FM1Identity.OFFICIAL_V15, identity)
        fm1.shutdown()
    }

    @Test
    fun aLookalikeNumberWithoutTheOpenReplyStillSwitches() = runBlocking {
        val fm1 = device(
            SimulatedOptions(
                installedIdentity = FM1Identity.parse("FM-1_8000013")!!,
                answersOpenProtocol = false,
            ),
        )
        val package_ = v15()
        fm1.prepare(package_)
        val updater = FM1Updater(fm1, UpdaterTiming.Fast)
        val check = updater.check(package_)
        assertEquals(null, check.capability)
        val identity = updater.install(package_, InstallOptions(confirmsSwitchingFirmware = true))
        assertEquals(FM1Identity.OFFICIAL_V15, identity)
        fm1.shutdown()
    }

    @Test
    fun checkReportsSwitchingFirmware() = runBlocking {
        val fm1 = device()
        val updater = FM1Updater(fm1, UpdaterTiming.Fast)
        val check = updater.check(v15())
        assertEquals(InstallKind.SWITCHING_FIRMWARE, check.kind)
        assertEquals("FM-1_900", check.device.text)
        fm1.shutdown()
    }
}
