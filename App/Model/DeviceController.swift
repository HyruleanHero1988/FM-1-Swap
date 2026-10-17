import FM1Kit
import Foundation
import Observation
import SwiftUI

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

    /// Restoring V15's factory voices, which M-VAVE's updater offers after installing V15.
    enum VoiceRestore: Equatable {
        case idle
        case writing(fraction: Double)
        case succeeded
        case failed(String)

        var isRunning: Bool {
            if case .writing = self { true } else { false }
        }
    }

    struct FlashJob: Equatable {
        var steps: [StoredFirmware]
        /// Whether the person confirmed that these steps switch the FM-1 to a different firmware,
        /// which can erase its projects and samples.
        var confirmsSwitchingFirmware = false
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
    private(set) var voiceRestore = VoiceRestore.idle
    private(set) var log: [String] = []
    private(set) var transportName = ""
    private(set) var transportError: String?

    private(set) var transport: any MIDITransport = UnavailableTransport()
    private var updater = FM1Updater(transport: UnavailableTransport())
    private var watchTask: Task<Void, Never>?
    private let wakeKeeper = WakeKeeper()

    private let library: FirmwareLibrary
    private let history: FlashHistory

    init(library: FirmwareLibrary, history: FlashHistory) {
        self.library = library
        self.history = history
    }

    var isFlashing: Bool { job?.isRunning ?? false }

    /// Whether anything is being written to the FM-1, a flash or a voice restore, so nothing else
    /// may talk to it.
    var isWriting: Bool { isFlashing || voiceRestore.isRunning }

    var simulatedDevice: SimulatedFM1? { transport as? SimulatedFM1 }

    // MARK: - Transport.

    func configure(_ kind: TransportKind, settings: AppSettings) {
        guard !isWriting else { return }
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
                guard let self, !isWriting else { continue }
                await scan()
            }
        }
    }

    // MARK: - Identifying.

    func scan() async {
        guard !isScanning, !isWriting else { return }
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

    /// What installing `item` on the connected FM-1 would do, sending only the handshake (and,
    /// for M-VAVE's open firmware, its capability query).
    func check(_ item: StoredFirmware) async throws -> InstallCheck {
        let package = try library.loadPackage(item)
        return try await updater.check(package)
    }

    /// Starts installing `steps` in order.
    ///
    /// - Parameters:
    ///   - steps: The firmwares to install, in order.
    ///   - confirmsSwitchingFirmware: Set once the person has confirmed a switch to a different
    ///     firmware; FM1Kit refuses one otherwise.
    func flash(_ steps: [StoredFirmware], confirmsSwitchingFirmware: Bool = false) {
        guard !steps.isEmpty, !isWriting else { return }
        log = []
        job = FlashJob(steps: steps, confirmsSwitchingFirmware: confirmsSwitchingFirmware)
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
        let options = InstallOptions(confirmsSwitchingFirmware: job?.confirmsSwitchingFirmware ?? false)
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
                            : try await updater.install(package, options: options, events: report))
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

    // MARK: - Factory voices.

    /// Erases the FM-1's four voice banks and writes V15's 128 factory voices from the official
    /// firmware, if the FM-1 is running V15.
    ///
    /// Every voice in the banks is replaced, including the person's own, and V15 can't read
    /// them back first, so ask before calling this. If it's interrupted, running it again fixes it.
    func restoreFactoryVoices() async {
        guard !isWriting else { return }
        voiceRestore = .writing(fraction: 0)
        setKeepAwake(true)
        defer { setKeepAwake(false) }
        do {
            guard let official = library.official else { throw FM1UpdateError.factoryVoicesUnavailable }
            let package = try library.loadPackage(official)
            let (fractions, continuation) = AsyncStream<Double>.makeStream()
            let consumer = Task { @MainActor [weak self] in
                for await fraction in fractions {
                    self?.voiceRestore = .writing(fraction: fraction)
                }
            }
            let result: Result<Void, any Error>
            do {
                try await updater.restoreFactoryVoices(from: package) { continuation.yield($0) }
                result = .success(())
            } catch {
                result = .failure(error)
            }
            continuation.finish()
            await consumer.value
            try result.get()
            voiceRestore = .succeeded
        } catch {
            voiceRestore = .failed(error.localizedDescription)
        }
    }

    func dismissVoiceRestore() {
        guard !voiceRestore.isRunning else { return }
        voiceRestore = .idle
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

    /// Keeps the device awake for the whole flash or restore, so the person only has to stay in
    /// the app.
    private func setKeepAwake(_ on: Bool) {
        wakeKeeper.isHolding = on
    }

    /// Passes on scene changes, so a write that's under way can ask for time to finish.
    func scenePhaseChanged(to phase: ScenePhase) {
        wakeKeeper.scenePhaseChanged(to: phase, isWriting: isWriting)
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
