import FM1Kit
import Foundation
@testable import FMSwitch
import Testing

/// End-to-end flashes through the app's own controller, library and history, against the
/// simulated FM-1 at the real device's timings.
@MainActor
@Suite(.serialized)
struct FlashingTests {
    /// A `.fwsc` file whose header markers spell `product`, built the way the format is documented.
    static func packageFile(product: String, size: Int = 20_000) -> Data {
        let blockCount = 20
        let imageBytesPerBlock = 0x2F
        let image = (0..<size).map { UInt8(truncatingIfNeeded: $0 &* 13 &+ 5) }
        let productBytes = Array(product.utf8)
        var file: [UInt8] = []
        for block in 0..<blockCount {
            let start = block * imageBytesPerBlock
            file += image[start..<(start + imageBytesPerBlock)]
            file.append(block < productBytes.count ? UInt8(truncatingIfNeeded: Int(productBytes[block]) + block + 1) : 0x7D)
        }
        file += image[(blockCount * imageBytesPerBlock)...]
        return Data(file)
    }

    struct Setup {
        let controller: DeviceController
        let library: FirmwareLibrary
        let history: FlashHistory
        let settings: AppSettings
    }

    static func setUp(cablePull: Bool = false) async throws -> Setup {
        let directory = URL.temporaryDirectory.appending(path: UUID().uuidString, directoryHint: .isDirectory)
        let defaults = try #require(UserDefaults(suiteName: UUID().uuidString))
        let settings = AppSettings(defaults: defaults)
        settings.simulatedCablePull = cablePull
        let library = FirmwareLibrary(directory: directory)
        let history = FlashHistory(defaults: defaults)
        let controller = DeviceController(library: library, history: history)
        controller.configure(.simulated, settings: settings)
        try await waitUntil(seconds: 10) { controller.identity != nil }
        return Setup(controller: controller, library: library, history: history, settings: settings)
    }

    static func save(_ product: String, in library: FirmwareLibrary) throws -> StoredFirmware {
        let url = URL.temporaryDirectory.appending(path: "\(UUID().uuidString).fwsc")
        try packageFile(product: product).write(to: url)
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

        setup.controller.flash([item])
        try await Self.waitUntil(seconds: 90) { setup.controller.job?.isRunning == false }

        #expect(setup.controller.job?.phase == .succeeded("FM-1_916"))
        #expect(setup.controller.identity?.text == "FM-1_916")
        #expect(setup.controller.log.last == "✓ Finished.")
    }

    @Test func resumesAfterTheCableIsPulled() async throws {
        let setup = try await Self.setUp(cablePull: true)
        let item = try Self.save("FM-1_916", in: setup.library)

        setup.controller.flash([item])
        try await Self.waitUntil(seconds: 90) { setup.controller.job?.isRunning == false }
        guard case let .failed(_, canResume) = setup.controller.job?.phase else {
            Issue.record("Expected the pulled cable to stop the flash, got \(String(describing: setup.controller.job?.phase)).")
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

        setup.controller.flash([item])
        try await Self.waitUntil(seconds: 90) { setup.controller.job?.isRunning == false }
        guard case let .failed(message, canResume) = setup.controller.job?.phase else {
            Issue.record("Expected the device to refuse its own firmware.")
            return
        }
        #expect(!canResume)
        #expect(message.contains("checking the package"))
        #expect(setup.controller.identity?.text == "FM-1_900")
    }
}
