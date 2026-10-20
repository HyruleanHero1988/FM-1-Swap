package com.fmswitch.fm1kit

sealed class UpdateEvent {
    data class Connected(val identity: FM1Identity) : UpdateEvent()
    data class Staging(val requests: Int) : UpdateEvent()
    data object WaitingForLoader : UpdateEvent()
    data class LoaderFound(val identity: FM1Identity) : UpdateEvent()
    data class Writing(val fraction: Double, val requests: Int) : UpdateEvent()
    data class Checking(val fraction: Double, val requests: Int) : UpdateEvent()
    data object Rebooting : UpdateEvent()
    data class Finished(val identity: FM1Identity) : UpdateEvent()
}

enum class InstallKind {
    UPDATE,
    SWITCHING_FIRMWARE,
    REINSTALL,
    SAME_IDENTITY,
}

data class InstallCheck(
    val device: FM1Identity,
    val capability: FM1Capability?,
    val kind: InstallKind,
) {
    companion object {
        fun of(
            device: FM1Identity,
            capability: FM1Capability?,
            package_: FwscPackage,
            buildID: FM1BuildID?,
        ): InstallCheck {
            val kind = when {
                capability != null -> {
                    val isSameBuild = buildID == capability.buildID
                    val isSameLine = package_.identity.isOpenFirmware && capability.family == FM1Capability.FM1_FAMILY
                    when {
                        isSameBuild -> InstallKind.REINSTALL
                        isSameLine -> InstallKind.UPDATE
                        else -> InstallKind.SWITCHING_FIRMWARE
                    }
                }
                device == package_.identity -> {
                    if (device == FM1Identity.OFFICIAL_V15) InstallKind.REINSTALL else InstallKind.SAME_IDENTITY
                }
                else -> InstallKind.SWITCHING_FIRMWARE
            }
            return InstallCheck(device, capability, kind)
        }
    }
}

data class InstallOptions(
    val confirmsSwitchingFirmware: Boolean = false,
    val confirmsSameIdentity: Boolean = false,
    val buildID: FM1BuildID? = null,
)

data class UpdaterTiming(
    val afterUpgradeCommandMs: Long = 0,
    val stagingIdleLimitMs: Long = 8_000,
    val loaderBootMs: Long = 3_000,
    val loaderSearchMs: Long = 30_000,
    val writeIdleLimitMs: Long = 60_000,
    val firmwareBootMs: Long = 3_000,
    val returnSearchMs: Long = 40_000,
    val responseDelayMs: Long = 0,
    val finishSettleMs: Long = 600,
    val scanIntervalMs: Long = 1_000,
    val handshakeWaitMs: Long = 1_000,
    val commandReplyWaitMs: Long = 1_500,
    val voiceReplyWaitMs: Long = 10_000,
    val handshakeAttempts: Int = 2,
) {
    companion object {
        val Standard = UpdaterTiming()
        val Fast = UpdaterTiming(
            stagingIdleLimitMs = 2_000,
            loaderBootMs = 50,
            loaderSearchMs = 5_000,
            writeIdleLimitMs = 5_000,
            firmwareBootMs = 50,
            returnSearchMs = 5_000,
            finishSettleMs = 20,
            scanIntervalMs = 20,
            handshakeWaitMs = 400,
            commandReplyWaitMs = 400,
            voiceReplyWaitMs = 1_000,
        )
    }
}

sealed class FM1UpdateError(message: String) : Exception(message) {
    enum class Phase { STAGING, WRITING }

    data object DeviceNotFound : FM1UpdateError("No FM-1 found. Check the cable, and close any other app using it.")
    data class MultipleDevices(val count: Int) :
        FM1UpdateError("$count FM-1s answered. Connect only the one to update, and try again.")
    data class SwitchNotConfirmed(val device: String, val packageProduct: String) : FM1UpdateError(
        "Installing $packageProduct switches the FM-1 from $device to a different firmware, which can erase its projects and samples. Back them up, then confirm the switch.",
    )
    data class AlreadyInstalled(val identity: String) :
        FM1UpdateError("The FM-1 is already running $identity, so it isn't reinstalled. Choose another firmware.")
    data class SameIdentityNotConfirmed(val identity: String) : FM1UpdateError(
        "The FM-1 already reports $identity, the same identity as this package. Confirm you mean to reinstall it, or to install another release of the same firmware.",
    )
    data object CapabilityUnavailable : FM1UpdateError(
        "The FM-1's open firmware didn't say whether it's ready to update, so the update didn't start. Switch it off and on again, and try again.",
    )
    data object DeviceBusy : FM1UpdateError(
        "The FM-1's open firmware is busy with another update, so this one didn't start. Close any other updater, switch the FM-1 off and on again, and try again.",
    )
    data class ArmRefused(val code: Int?) : FM1UpdateError(
        if (code == null) {
            "The FM-1's open firmware didn't answer when asked to prepare for the update, so the update didn't start. Switch it off and on again, and try again."
        } else {
            "The FM-1's open firmware refused this package (code $code), so the update didn't start."
        },
    )
    data class BuildMismatch(val expected: FM1BuildID, val reported: FM1BuildID) : FM1UpdateError(
        "The update finished, but the FM-1 reports build ${reported.hex.take(16)} instead of ${expected.hex.take(16)}.",
    )
    data object FactoryVoicesUnavailable :
        FM1UpdateError("This package isn't M-VAVE's official V15, so it can't restore the factory voices.")
    data class VoiceRestoreNeedsV15(val device: String) :
        FM1UpdateError("Restoring the factory voices needs the FM-1 running V15 (FM-1_015), but it reports $device.")
    data class VoiceRestoreRefused(val code: Int, val bytesWritten: Int) : FM1UpdateError(
        "The FM-1 refused to restore the factory voices (code $code) after $bytesWritten of 16384 bytes. Some voices may be wrong or silent until a restore finishes: keep the FM-1 on V15 and try again.",
    )
    data class VoiceRestoreStalled(val bytesWritten: Int) : FM1UpdateError(
        "The FM-1 stopped answering while restoring the factory voices, after $bytesWritten of 16384 bytes. Some voices may be wrong or silent until a restore finishes: reconnect the FM-1, keep it on V15, and run the restore again.",
    )
    data class ModelMismatch(val device: String, val packageProduct: String) :
        FM1UpdateError("The device is $device but the package is for $packageProduct.")
    data class Stalled(val phase: Phase, val requests: Int) : FM1UpdateError(phaseMessage(phase, requests, lost = false))
    data class Disconnected(val phase: Phase, val requests: Int) : FM1UpdateError(phaseMessage(phase, requests, lost = true))
    data object LoaderNotFound :
        FM1UpdateError("The update loader didn't appear. Reconnect the FM-1 and resume the update.")
    data object DeviceDidNotReturn :
        FM1UpdateError("The FM-1 didn't come back after writing. Switch it off and on again.")
    data class IdentityMismatch(val expected: String, val reported: String) :
        FM1UpdateError("The update finished, but the FM-1 reports $reported instead of $expected.")
    data class BadRequest(val address: Long, val length: Int) : FM1UpdateError(
        "The FM-1 asked for data outside the package ($length bytes at 0x${address.toString(16)}), so the update was stopped without answering. Check the package is complete.",
    )
    data class BackupUnavailable(val identity: String) : FM1UpdateError(
        "The FM-1 reports $identity, which has no FM-1+VA preset and pattern dump. Use that firmware's own editor to back up first, or confirm the switch.",
    )
    data class BackupFailed(val detail: String) : FM1UpdateError(
        "Couldn't back up the presets and patterns before writing ($detail). The flash was not started.",
    )
    data class BackupRestoreMismatch(val what: String) : FM1UpdateError(
        "Restoring the backup stopped because the FM-1's copy of $what didn't match what was sent. Reconnect and try again.",
    )
    data class UnexpectedFinish(val phase: Phase) : FM1UpdateError(
        if (phase == Phase.STAGING) {
            "The FM-1 asked to finish writing while it was still checking the package, so the update was stopped. Switch it off and on again. If it starts in its update loader, resume the update; otherwise install again."
        } else {
            "The update loader asked to finish checking the package while it was writing, so the update was stopped. Keep the FM-1 connected and resume the update; it stays in update mode until one finishes."
        },
    )

    companion object {
        private fun phaseMessage(phase: Phase, requests: Int, lost: Boolean): String = when (phase) {
            Phase.STAGING ->
                "The FM-1 stopped after $requests requests while checking the package, before restarting into its update loader. It should still start normally. If it doesn't, and its firmware supports USB recovery mode (holding OCT− while switching it on), start it that way and install again; otherwise recovering it needs a computer."
            Phase.WRITING -> if (lost) {
                "The update loader disconnected after $requests requests. Reconnect the FM-1 and resume the update; it stays in update mode until one finishes."
            } else {
                "The update loader stopped after $requests requests. Keep the FM-1 connected and resume the update; it stays in update mode until one finishes."
            }
        }
    }
}

data class FoundDevice(
    val connection: MidiConnection,
    val identity: FM1Identity,
)
