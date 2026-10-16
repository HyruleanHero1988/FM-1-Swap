import FM1Kit
import Foundation
import Observation
import SwiftUI
import UIKit

/// Talks to the FM-1: identifies it, watches for it coming and going, and runs flashes,
/// including "official first, then the target" chains and resuming interrupted writes.
@MainActor
@Observable
final class DeviceController {
    enum FlashPhase: Equatable {
        case preparing
        case staging(requests: Int)
        case waitingForLoader
        case writing(fraction: Double)
        /// The loader reading back what it wrote; not every loader does this.
        case verifying(fraction: Double)
        case rebooting
        case succeeded(String)
        case failed(String, canResume: Bool)

        var label: String {
            switch self {
            case .preparing: "CONNECTING"
            case .staging: "CHECKING PACKAGE"
            case .waitingForLoader: "WAITING FOR LOADER"
            case .writing: "WRITING"
            case .verifying: "VERIFYING"
            case .rebooting: "REBOOTING"
            case .succeeded: "DONE"
            case .failed: "STOPPED"
            }
        }
    }

    struct FlashJob: Equatable {
        var steps: [StoredFirmware]
        var index = 0
        var phase = FlashPhase.preparing

        var current: StoredFirmware { steps[index] }

        var isRunning: Bool {
            switch phase {
            case .succeeded, .failed: false
            default: true
            }
        }

        /// How far the current phase has got, or `nil` while it can't be measured.
        ///
        /// Each phase counts from 0 on its own: whether a loader verifies after writing isn't known
        /// until it starts, so one bar across every phase would have to jump back. Some loaders
        /// don't read the whole image, so writing can end short of 1.
        var phaseFraction: Double? {
            switch phase {
            case .staging(let requests): min(Double(requests) / 75, 1)
            case .writing(let fraction), .verifying(let fraction): fraction
            case .succeeded: 1
            case .preparing, .waitingForLoader, .rebooting, .failed: nil
            }
        }
    }

    private(set) var identity: FM1Identity?
    private(set) var isScanning = false
    private(set) var lastScan: Date?
    private(set) var ports: [MIDIPortDescriptor] = []
    private(set) var job: FlashJob?
    private(set) var log: [String] = []
    private(set) var transportName = ""
    private(set) var transportError: String?

    private(set) var transport: any MIDITransport = UnavailableTransport()
    private var updater = FM1Updater(transport: UnavailableTransport())
    private var watchTask: Task<Void, Never>?
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid

    private let library: FirmwareLibrary
    private let history: FlashHistory

    init(library: FirmwareLibrary, history: FlashHistory) {
        self.library = library
        self.history = history
    }

    var isFlashing: Bool { job?.isRunning ?? false }

    var simulatedDevice: SimulatedFM1? { transport as? SimulatedFM1 }

    // MARK: - Transport.

    func configure(_ kind: TransportKind, settings: AppSettings) {
        guard !isFlashing else { return }
        watchTask?.cancel()
        transportError = nil
        switch kind {
        case .usbMIDI:
            do {
                transport = try CoreMIDITransport()
            } catch {
                transport = UnavailableTransport()
                transportError = error.localizedDescription
            }
        case .simulated:
            var options = SimulatedFM1.Options()
            options.startsInRecoveryMode = settings.simulatedRecoveryMode
            options.unplugDuringWriteAt = settings.simulatedCablePull ? 0.45 : nil
            options.writesBackwards = settings.simulatedReadsBackwards
            options.checksAfterWriting = settings.simulatedVerifies
            transport = SimulatedFM1(options: options)
        }
        transportName = kind.name
        updater = FM1Updater(transport: transport)
        identity = nil
        ports = []

        let changes = transport.changes()
        watchTask = Task { [weak self] in
            await self?.scan()
            for await _ in changes {
                // Let a burst of add/remove notifications settle before probing.
                try? await Task.sleep(for: .milliseconds(400))
                guard let self, !isFlashing else { continue }
                await scan()
            }
        }
    }

    // MARK: - Identifying.

    func scan() async {
        guard !isScanning, !isFlashing else { return }
        isScanning = true
        defer {
            isScanning = false
            lastScan = .now
        }
        ports = await transport.ports()
        identity = await updater.identify()
    }

    // MARK: - Flashing.

    /// The steps for installing `target`: the official firmware first when it is required
    /// (or always, if the user prefers) and the device is not already running it.
    func plan(for target: StoredFirmware, requiresOfficial: Bool, alwaysViaOfficial: Bool) -> [StoredFirmware] {
        guard target.source != .official,
            requiresOfficial || alwaysViaOfficial,
            let official = library.official,
            identity?.text != official.product
        else {
            return [target]
        }
        return [official, target]
    }

    func flash(_ steps: [StoredFirmware]) {
        guard !steps.isEmpty, !isFlashing else { return }
        log = []
        job = FlashJob(steps: steps)
        Task { await run(from: 0) }
    }

    /// Carries on from the step that stopped; the loader picks up a write where it was interrupted.
    func resume() {
        guard let index = job?.index, job?.isRunning == false else { return }
        job?.phase = .preparing
        Task { await run(from: index) }
    }

    func dismissFlash() {
        guard !isFlashing else { return }
        job = nil
        Task { await scan() }
    }

    private func run(from startIndex: Int) async {
        guard let steps = job?.steps else { return }
        setKeepAwake(true)
        defer { setKeepAwake(false) }

        for index in startIndex..<steps.count {
            job?.index = index
            job?.phase = .preparing
            let item = steps[index]
            appendLog("▸ \(item.name) \(item.version) (\(item.product))")
            do {
                let package = try library.loadPackage(item)
                simulatedDevice?.prepare(for: package)

                let (events, continuation) = AsyncStream<UpdateEvent>.makeStream()
                let consumer = Task { @MainActor [weak self] in
                    for await event in events {
                        self?.apply(event)
                    }
                }
                let isLoaderRunning = await updater.identify()?.isLoader ?? false
                let result: Result<FM1Identity, any Error>
                do {
                    let report: @Sendable (UpdateEvent) -> Void = { continuation.yield($0) }
                    result = .success(
                        isLoaderRunning
                            ? try await updater.resume(package, events: report)
                            : try await updater.install(package, events: report))
                } catch {
                    result = .failure(error)
                }
                continuation.finish()
                await consumer.value

                let installed = try result.get()
                identity = installed
                history.record(item)
            } catch {
                appendLog("✕ \(error.localizedDescription)")
                job?.phase = .failed(error.localizedDescription, canResume: Self.isResumable(error))
                return
            }
        }
        job?.phase = .succeeded(identity?.text ?? "")
        appendLog("✓ Finished.")
    }

    private func apply(_ event: UpdateEvent) {
        switch event {
        case .connected(let identity):
            appendLog("Connected: \(identity.text)")
        case .staging(let requests):
            job?.phase = .staging(requests: requests)
        case .waitingForLoader:
            job?.phase = .waitingForLoader
            appendLog("Package accepted; rebooting into the update loader.")
        case .loaderFound(let identity):
            appendLog("Loader: \(identity.text)")
        case .writing(let fraction, _):
            job?.phase = .writing(fraction: fraction)
        case .checking(let fraction, _):
            if case .verifying = job?.phase {} else { appendLog("Written; the loader is reading it back to check it.") }
            job?.phase = .verifying(fraction: fraction)
        case .rebooting:
            job?.phase = .rebooting
            appendLog("Rebooting into the new firmware.")
        case .finished(let identity):
            appendLog("Device reports \(identity.text).")
        }
    }

    private static func isResumable(_ error: any Error) -> Bool {
        switch error as? FM1UpdateError {
        case .disconnected(.writing, _), .stalled(.writing, _), .loaderNotFound:
            true
        default:
            false
        }
    }

    private func appendLog(_ line: String) {
        log.append(line)
    }

    // MARK: - Staying alive.

    /// Auto-lock is paused for the whole flash, so the user only has to stay in the app.
    private func setKeepAwake(_ on: Bool) {
        UIApplication.shared.isIdleTimerDisabled = on
        if !on { endBackgroundTask() }
    }

    /// If the user leaves mid-flash, ask iOS for the short grace period apps get to finish work.
    func scenePhaseChanged(to phase: ScenePhase) {
        switch phase {
        case .background where isFlashing:
            guard backgroundTask == .invalid else { return }
            backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "FM-1 flash") { [weak self] in
                Task { @MainActor in self?.endBackgroundTask() }
            }
        case .active:
            endBackgroundTask()
        default:
            break
        }
    }

    private func endBackgroundTask() {
        guard backgroundTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(backgroundTask)
        backgroundTask = .invalid
    }
}

/// Stands in when CoreMIDI could not start; it never finds anything.
final class UnavailableTransport: MIDITransport {
    func ports() async -> [MIDIPortDescriptor] { [] }

    func open(_: MIDIPortDescriptor) async throws -> any MIDIConnection {
        throw MIDITransportError.portGone
    }

    func changes() -> AsyncStream<Void> {
        AsyncStream { _ in }
    }
}
