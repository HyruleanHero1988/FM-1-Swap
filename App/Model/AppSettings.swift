import Foundation
import Observation
import SwiftUI

enum TransportKind: String, CaseIterable, Identifiable {
    /// A real FM-1 over USB through CoreMIDI.
    case usbMIDI
    /// The software FM-1, for the Simulator and demos.
    case simulated

    var id: String { rawValue }

    var name: String {
        switch self {
        case .usbMIDI: "USB MIDI"
        case .simulated: "SIMULATED FM-1"
        }
    }
}

/// User preferences, persisted in `UserDefaults`.
@MainActor
@Observable
final class AppSettings {
    private let defaults: UserDefaults

    var phosphorHex: String { didSet { defaults.set(phosphorHex, forKey: Keys.phosphorHex) } }
    var glow: Double { didSet { defaults.set(glow, forKey: Keys.glow) } }
    var scanlines: Bool { didSet { defaults.set(scanlines, forKey: Keys.scanlines) } }
    var flicker: Bool { didSet { defaults.set(flicker, forKey: Keys.flicker) } }
    var transportKind: TransportKind { didSet { defaults.set(transportKind.rawValue, forKey: Keys.transport) } }
    var showBeta: Bool { didSet { defaults.set(showBeta, forKey: Keys.showBeta) } }
    /// List firmwares with no downloadable file (their links are still useful), off by default
    /// so the list only offers what can actually be flashed.
    var showLinksOnly: Bool { didSet { defaults.set(showLinksOnly, forKey: Keys.showLinksOnly) } }
    /// Install the official firmware first on every swap, not only when a firmware requires it.
    var alwaysViaOfficial: Bool { didSet { defaults.set(alwaysViaOfficial, forKey: Keys.alwaysViaOfficial) } }
    var simulatedRecoveryMode: Bool { didSet { defaults.set(simulatedRecoveryMode, forKey: Keys.simulatedRecovery) } }
    var simulatedCablePull: Bool { didSet { defaults.set(simulatedCablePull, forKey: Keys.simulatedCablePull) } }
    /// The `SafetyNotice.version` the user last acknowledged (0 for never).
    var acknowledgedNoticeVersion: Int {
        didSet { defaults.set(acknowledgedNoticeVersion, forKey: Keys.acknowledgedNotice) }
    }

    var needsSafetyNotice: Bool { acknowledgedNoticeVersion < SafetyNotice.version }

    /// Bumped when the look's defaults change, so earlier saved look settings are dropped once.
    private static let lookVersion = 2

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        if defaults.integer(forKey: Keys.lookVersion) < Self.lookVersion {
            for key in [Keys.phosphorHex, Keys.glow, Keys.scanlines, Keys.flicker] {
                defaults.removeObject(forKey: key)
            }
            defaults.set(Self.lookVersion, forKey: Keys.lookVersion)
        }
        let standard = PhosphorTheme.standard
        phosphorHex = defaults.string(forKey: Keys.phosphorHex) ?? PhosphorPreset.green.hex
        glow = defaults.object(forKey: Keys.glow) as? Double ?? standard.glow
        scanlines = defaults.object(forKey: Keys.scanlines) as? Bool ?? standard.scanlines
        flicker = defaults.object(forKey: Keys.flicker) as? Bool ?? standard.flicker
        #if targetEnvironment(simulator)
            let defaultTransport = TransportKind.simulated
        #else
            let defaultTransport = TransportKind.usbMIDI
        #endif
        transportKind =
            defaults.string(forKey: Keys.transport).flatMap(TransportKind.init(rawValue:)) ?? defaultTransport
        showBeta = defaults.object(forKey: Keys.showBeta) as? Bool ?? true
        showLinksOnly = defaults.object(forKey: Keys.showLinksOnly) as? Bool ?? false
        alwaysViaOfficial = defaults.object(forKey: Keys.alwaysViaOfficial) as? Bool ?? false
        simulatedRecoveryMode = defaults.object(forKey: Keys.simulatedRecovery) as? Bool ?? false
        simulatedCablePull = defaults.object(forKey: Keys.simulatedCablePull) as? Bool ?? false
        acknowledgedNoticeVersion = defaults.integer(forKey: Keys.acknowledgedNotice)
    }

    /// Puts the colour and screen effects back to the defaults.
    func resetLook() {
        let standard = PhosphorTheme.standard
        phosphorHex = PhosphorPreset.green.hex
        glow = standard.glow
        scanlines = standard.scanlines
        flicker = standard.flicker
    }

    var theme: PhosphorTheme {
        PhosphorTheme(
            base: Color(hex: phosphorHex) ?? PhosphorPreset.green.colour,
            glow: glow,
            scanlines: scanlines,
            flicker: flicker
        )
    }

    private enum Keys {
        static let lookVersion = "lookVersion"
        static let phosphorHex = "phosphorHex"
        static let glow = "glow"
        static let scanlines = "scanlines"
        static let flicker = "flicker"
        static let transport = "transport"
        static let showBeta = "showBeta"
        static let showLinksOnly = "showLinksOnly"
        static let alwaysViaOfficial = "alwaysViaOfficial"
        static let simulatedRecovery = "simulatedRescueMode"  // The original key, so saved settings carry over.
        static let simulatedCablePull = "simulatedCablePull"
        static let acknowledgedNotice = "acknowledgedNoticeVersion"
    }
}
