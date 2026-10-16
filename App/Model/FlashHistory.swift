import Foundation
import Observation

/// What this phone last installed.
///
/// The device's identity alone is ambiguous (many firmwares report `FM-1_900`), so this fills the
/// gap when the identities agree.
struct FlashRecord: Codable, Hashable, Identifiable {
    var id = UUID()
    var name: String
    var version: String
    var product: String
    var date: Date
}

@MainActor
@Observable
final class FlashHistory {
    private(set) var records: [FlashRecord] = []
    private let defaults: UserDefaults
    private static let key = "flashHistory"
    private static let limit = 30

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        if let data = defaults.data(forKey: Self.key),
            let decoded = try? JSONDecoder().decode([FlashRecord].self, from: data)
        {
            records = decoded
        }
    }

    var last: FlashRecord? { records.first }

    /// The last install from this phone, if the device still reports the identity it left with.
    func lastInstall(matching product: String) -> FlashRecord? {
        guard let last, last.product == product else { return nil }
        return last
    }

    func record(_ item: StoredFirmware) {
        records.insert(FlashRecord(name: item.name, version: item.version, product: item.product, date: .now), at: 0)
        records = Array(records.prefix(Self.limit))
        if let data = try? JSONEncoder().encode(records) {
            defaults.set(data, forKey: Self.key)
        }
    }
}
