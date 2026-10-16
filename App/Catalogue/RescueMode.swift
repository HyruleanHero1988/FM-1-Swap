/// How a firmware starts the FM-1 in USB recovery mode, so another firmware can be installed even
/// when this one won't run.
///
/// This is a check each firmware makes as it starts, not something built into the FM-1, so it
/// differs from one firmware to the next. There's no feed for it: ``known`` comes from each
/// project's README, checked on 2026-10-16, apart from M-VAVE's and Felucca's, which were tried
/// on an FM-1.
enum RescueMode: Equatable, Sendable {
    /// Hold OCT− alone while switching on, from the given release where that's stated.
    case octaveDownAtPowerOn(since: String?)
    /// Hold OCT− and OCT+ together for 5 seconds while the firmware is running.
    case bothOctaveKeysHeld
    /// Holding OCT− while switching on starts the firmware as usual.
    case none(tried: String)

    /// What's known, by catalogue ID.
    ///
    /// Firmwares not listed may or may not have a recovery mode.
    static let known: [String: Self] = [
        "choralroot": .octaveDownAtPowerOn(since: "0.16"),
        "dinghy": .octaveDownAtPowerOn(since: nil),
        "etch": .octaveDownAtPowerOn(since: nil),
        "felucca": .none(tried: "1.6.1"),
        "fimba": .bothOctaveKeysHeld,
        "floyd": .octaveDownAtPowerOn(since: nil),
        "fumi": .bothOctaveKeysHeld,
        "ghoulbox": .octaveDownAtPowerOn(since: nil),
        "jangada": .octaveDownAtPowerOn(since: nil),
        "melodee": .octaveDownAtPowerOn(since: "1.3.5"),
        "mvave-official": .none(tried: "V15"),
        "sloop": .octaveDownAtPowerOn(since: nil),
        "sloopbanks": .octaveDownAtPowerOn(since: nil),
        "sloopdx": .octaveDownAtPowerOn(since: nil),
        "winampfm": .octaveDownAtPowerOn(since: nil),
        "zp12": .octaveDownAtPowerOn(since: nil),
    ]

    /// True when the mode comes from the project's own description rather than a try on an FM-1.
    var isFromReadme: Bool {
        if case .none = self { return false }
        return true
    }

    /// How to start the recovery mode, in a few words.
    var summary: String {
        switch self {
        case .octaveDownAtPowerOn(let since):
            "Hold OCT− while switching on" + (since.map { " (from \($0))" } ?? "")
        case .bothOctaveKeysHeld:
            "Hold OCT− and OCT+ for 5 s while it runs"
        case .none(let tried):
            "None: OCT− at switch-on starts it as usual (tried on \(tried))"
        }
    }
}

extension CatalogueEntry {
    /// How this firmware starts USB recovery mode, or `nil` when that isn't known.
    var rescueMode: RescueMode? { RescueMode.known[id] }
}
