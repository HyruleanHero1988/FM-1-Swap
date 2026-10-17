@_spi(Testing) import FM1Kit
import Foundation
import Testing

@testable import FMSwitch

/// End-to-end flashes through the app's own controller, library and history, against the
/// simulated FM-1 at the real device's timings.
@MainActor
@Suite(.serialized)
struct FlashingTests {
    struct Setup {
        let controller: DeviceController
        let library: FirmwareLibrary
        let history: FlashHistory
    }

    static func setUp(cablePull: Bool = false, configure: (AppSettings) -> Void = { _ in }) async throws -> Setup {
        let directory = URL.temporaryDirectory.appending(path: UUID().uuidString, directoryHint: .isDirectory)
        let defaults = try #require(UserDefaults(suiteName: UUID().uuidString))
        let settings = AppSettings(defaults: defaults)
        settings.simulatedCablePull = cablePull
        configure(settings)
        let library = FirmwareLibrary(directory: directory)
        let history = FlashHistory(defaults: defaults)
        let controller = DeviceController(library: library, history: history)
        controller.configure(.simulated, settings: settings)
        try await waitUntil(seconds: 10) { controller.identity != nil }
        return Setup(controller: controller, library: library, history: history)
    }

    static func save(_ product: String, in library: FirmwareLibrary) throws -> StoredFirmware {
        let url = URL.temporaryDirectory.appending(path: "\(UUID().uuidString).fwsc")
        try FwscPackage.sampleFile(product: product).write(to: url)
        return try library.importFile(at: url)
    }

    static func waitUntil(seconds: Int, _ condition: () -> Bool) async throws {
        let deadline = ContinuousClock.now + .seconds(seconds)
        while !condition() {
            guard ContinuousClock.now < deadline else {
                Issue.record("Timed out after \(seconds) s.")
                return
            }
            try await Task.sleep(for: .milliseconds(100))
        }
    }

    @Test func flashesAFirmwareAndRecordsIt() async throws {
        let setup = try await Self.setUp()
        #expect(setup.controller.identity?.text == "FM-1_900")
        let item = try Self.save("FM-1_916", in: setup.library)

        setup.controller.flash([item], confirmsSwitchingFirmware: true)
        try await Self.waitUntil(seconds: 90) { setup.controller.job?.isRunning == false }

        #expect(setup.controller.job?.phase == .succeeded("FM-1_916"))
        #expect(setup.controller.identity?.text == "FM-1_916")
        #expect(setup.controller.log.last == "✓ Finished.")
        #expect(setup.history.last?.product == "FM-1_916")
    }

    @Test func flashesThroughALoaderThatReadsBackwardsAndVerifies() async throws {
        let setup = try await Self.setUp { settings in
            settings.simulatedReadsBackwards = true
            settings.simulatedVerifies = true
        }
        let item = try Self.save("FM-1_916", in: setup.library)

        setup.controller.flash([item], confirmsSwitchingFirmware: true)
        try await Self.waitUntil(seconds: 90) { setup.controller.job?.isRunning == false }

        #expect(setup.controller.job?.phase == .succeeded("FM-1_916"))
        let log = setup.controller.log
        let verifying = try #require(log.firstIndex { $0.contains("reading it back") })
        let rebooting = try #require(log.firstIndex { $0.contains("Rebooting") })
        #expect(verifying < rebooting)
        #expect(log.count { $0.contains("reading it back") } == 1)
    }

    @Test func resumesAfterTheCableIsPulled() async throws {
        let setup = try await Self.setUp(cablePull: true)
        let item = try Self.save("FM-1_916", in: setup.library)

        setup.controller.flash([item], confirmsSwitchingFirmware: true)
        try await Self.waitUntil(seconds: 90) { setup.controller.job?.isRunning == false }
        guard case .failed(_, let canResume) = setup.controller.job?.phase else {
            Issue.record(
                "Expected the pulled cable to stop the flash, got \(String(describing: setup.controller.job?.phase)).")
            return
        }
        #expect(canResume)

        // The simulated loader comes back after its restart; then resume finishes the write.
        try await Task.sleep(for: .seconds(2))
        setup.controller.resume()
        try await Self.waitUntil(seconds: 90) { setup.controller.job?.isRunning == false }
        #expect(setup.controller.job?.phase == .succeeded("FM-1_916"))
    }

    @Test func refusesToReinstallTheRunningFirmware() async throws {
        let setup = try await Self.setUp()
        let item = try Self.save("FM-1_900", in: setup.library)

        setup.controller.flash([item], confirmsSwitchingFirmware: true)
        try await Self.waitUntil(seconds: 90) { setup.controller.job?.isRunning == false }
        guard case .failed(let message, let canResume) = setup.controller.job?.phase else {
            Issue.record("Expected the device to refuse its own firmware.")
            return
        }
        #expect(!canResume)
        #expect(message.contains("already running FM-1_900"))
        #expect(setup.controller.identity?.text == "FM-1_900")
    }

    @Test func refusesToSwitchFirmwareUnconfirmed() async throws {
        let setup = try await Self.setUp()
        let item = try Self.save("FM-1_916", in: setup.library)
        #expect(try await setup.controller.check(item).kind == .switchingFirmware)

        setup.controller.flash([item])
        try await Self.waitUntil(seconds: 90) { setup.controller.job?.isRunning == false }
        guard case .failed(let message, let canResume) = setup.controller.job?.phase else {
            Issue.record("Expected the unconfirmed switch to be refused.")
            return
        }
        #expect(!canResume)
        #expect(message.contains("confirm the switch"))
        #expect(setup.controller.identity?.text == "FM-1_900")
    }

    @Test func restoresTheFactoryVoicesOnceV15IsInstalled() async throws {
        let setup = try await Self.setUp()
        let official = try #require(setup.library.official)

        setup.controller.flash([official], confirmsSwitchingFirmware: true)
        try await Self.waitUntil(seconds: 120) { setup.controller.job?.isRunning == false }
        #expect(setup.controller.job?.phase == .succeeded("FM-1_015"))

        await setup.controller.restoreFactoryVoices()
        #expect(setup.controller.voiceRestore == .succeeded)
        // The hash M-VAVE's updater pins for V15's factory voices.
        let voices = try #require(setup.controller.simulatedDevice?.currentVoices)
        #expect(
            FwscPackage.sha256Hex(Data(voices)) == "ecad0b8620f9d784d46c8da1e3c5de02c40214649ed8dec218026431046aad25")
    }

    @Test func refusesToRestoreVoicesOnAnotherFirmware() async throws {
        let setup = try await Self.setUp()
        await setup.controller.restoreFactoryVoices()
        guard case .failed(let message) = setup.controller.voiceRestore else {
            Issue.record("Expected the restore to be refused on FM-1_900.")
            return
        }
        #expect(message.contains("needs the FM-1 running V15"))
    }
}
