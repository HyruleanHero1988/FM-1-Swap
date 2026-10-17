import FM1Kit
import Foundation
import Observation

/// A firmware package saved on the phone, ready to flash offline.
struct StoredFirmware: Codable, Identifiable, Hashable {
    enum Source: String, Codable {
        case catalogue
        case official
        case imported
    }

    /// What's known about a package before it's saved, beyond what the package itself says.
    struct Details {
        var name: String
        var version: String
        var author: String?
        var source: Source
        var catalogueID: String?
        var packageURL: URL?
        var build: String?
    }

    var id: String
    var name: String
    var version: String
    var author: String?
    var product: String
    var sha256: String
    var size: Int
    var fileName: String
    var source: Source
    var catalogueID: String?
    /// Where it was downloaded from, to recognise packages that have no published checksum.
    var packageURL: URL?
    /// Which build, for a release that comes in several (e.g. "1core").
    var build: String?
    var savedAt: Date
}

/// The on-phone firmware cache: downloads, the official firmware, and imports.
///
/// M-VAVE's V15 comes with the app (it's Apache-2.0, from M-VAVE's open-source repository), and
/// is saved here on first launch, so the official firmware is always to hand for recovery.
@MainActor
@Observable
final class FirmwareLibrary {
    enum Failure: LocalizedError {
        case download(String)
        case noPackage
        case hashMismatch
        case productMismatch(expected: String, found: String)
        case notOfficial
        case officialNotFound

        var errorDescription: String? {
            switch self {
            case .download(let reason):
                return "Download failed: \(reason)"
            case .noPackage:
                return "No downloadable file is known for this firmware. Get it from the project's own links."
            case .hashMismatch:
                return """
                    The downloaded file doesn't match its published size or checksum, so it was not saved. \
                    Refresh the list and try again.
                    """
            case .productMismatch(let expected, let found):
                return "The file reports \(found), but the catalogue says \(expected). Not saved."
            case .notOfficial:
                return """
                    That file isn't a recognised official FM-1 firmware \
                    (only an unmodified V15 FM-1.fwsc is accepted).
                    """
            case .officialNotFound:
                return """
                    Couldn't find FM-1.fwsc on M-VAVE's downloads page. \
                    Download it in Safari and import it from Files.
                    """
            }
        }
    }

    private(set) var items: [StoredFirmware] = []
    private(set) var downloading: Set<String> = []
    /// Each saved package's loader hash, worked out when first needed.
    @ObservationIgnored private var loaderHashes: [String: String?] = [:]

    private let directory: URL
    private let indexURL: URL

    /// The copy of V15 that comes with the app.
    static let bundledOfficialURL = Bundle.main.url(forResource: "FM-1_015", withExtension: "fwsc")

    init(
        directory: URL = AppDirectories.support.appending(path: "Firmware", directoryHint: .isDirectory),
        bundledOfficial: URL? = bundledOfficialURL
    ) {
        self.directory = directory
        indexURL = directory.appending(path: "library.json")
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        load()
        if official == nil, let bundledOfficial {
            try? saveBundledOfficial(from: bundledOfficial)
        }
    }

    /// The saved V15: the firmware other firmwares install from, and the one to go back to.
    ///
    /// Only V15 counts, as M-VAVE's other official firmware (FM1 Open) doesn't do either job.
    var official: StoredFirmware? {
        items.first { $0.source == .official && $0.sha256 == OfficialFirmware.v15.sha256 }
    }

    var totalBytes: Int {
        items.reduce(0) { $0 + $1.size }
    }

    var sortedItems: [StoredFirmware] {
        items.sorted { lhs, rhs in
            if (lhs.source == .official) != (rhs.source == .official) { return lhs.source == .official }
            return lhs.name.localizedCaseInsensitiveCompare(rhs.name) == .orderedAscending
        }
    }

    /// The saved copy of exactly this release: the same checksum when one is published,
    /// otherwise the same download address.
    func stored(for entry: CatalogueEntry) -> StoredFirmware? {
        if let sha256 = entry.sha256 {
            return items.first { $0.sha256 == sha256 }
        }
        guard let packageURL = entry.packageURL else { return nil }
        return items.first { $0.catalogueID == entry.id && $0.packageURL == packageURL }
    }

    /// A saved copy of an older release of this catalogue firmware.
    func outdated(for entry: CatalogueEntry) -> StoredFirmware? {
        guard stored(for: entry) == nil else { return nil }
        return items.first { item in
            item.catalogueID == entry.id
                && (entry.version.map { ReleaseVersion.isNewer($0, than: item.version) } ?? true)
        }
    }

    /// A saved copy of this release in another build than the one picked.
    func otherBuild(for entry: CatalogueEntry) -> StoredFirmware? {
        guard stored(for: entry) == nil, outdated(for: entry) == nil else { return nil }
        return items.first { $0.catalogueID == entry.id }
    }

    func url(for item: StoredFirmware) -> URL {
        directory.appending(path: item.fileName)
    }

    func loadPackage(_ item: StoredFirmware) throws -> FwscPackage {
        try FwscPackage(data: Data(contentsOf: url(for: item)))
    }

    /// The saved firmwares whose package can finish the update `loader` is waiting to do.
    ///
    /// Each package brings its own update loader, and a loader reports `ota-` and the identity of
    /// the package that started it, so that package can finish it. There are two exceptions:
    /// - M-VAVE's loader always reports `ota-FM-1_015`, so any package that brings it will do.
    /// - The loader of armed open firmware reports `ota-FM1C_` and the start of the armed build
    ///   ID, so the package with that build ID is the one.
    func packages(thatCanFinish loader: FM1Identity) -> [StoredFirmware] {
        sortedItems.filter { item in
            if let armedBuildPrefix = loader.armedBuildPrefix {
                return Self.buildID(of: item).hasPrefix(armedBuildPrefix.lowercased())
            }
            return FM1Identity.loaderPrefix + item.product == loader.text
                || (loader.text == Self.stockLoaderIdentity
                    && loaderSHA256(of: item) == OfficialFirmware.stockLoaderSHA256)
        }
    }

    /// The build ID open firmware is armed with to install `item`, in lower-case hex: M-VAVE's
    /// published one for its own releases, otherwise the package's SHA-256, as M-VAVE's updater
    /// uses.
    private static func buildID(of item: StoredFirmware) -> String {
        OfficialFirmware.known.first { $0.sha256 == item.sha256 }?.buildID.hex.lowercased() ?? item.sha256
    }

    /// What M-VAVE's update loader reports, whichever package started it.
    static let stockLoaderIdentity = "ota-FM-1_015"

    private func loaderSHA256(of item: StoredFirmware) -> String? {
        if let known = loaderHashes[item.id] { return known }
        let hash = try? loadPackage(item).loaderSHA256
        loaderHashes[item.id] = hash
        return hash
    }

    // MARK: - Adding.

    /// Downloads a catalogue firmware, checks whatever is published about it (size, hash,
    /// identity), checks it reads as an FM-1 package, and saves it, replacing older releases.
    ///
    /// M-VAVE's own firmware is recognised by its hash and kept as the official firmware.
    @discardableResult
    func download(_ entry: CatalogueEntry) async throws -> StoredFirmware {
        guard let packageURL = entry.packageURL else { throw Failure.noPackage }
        downloading.insert(entry.id)
        defer { downloading.remove(entry.id) }

        let data = try await fetch(packageURL)
        if let size = entry.size, data.count != size { throw Failure.hashMismatch }
        if let sha256 = entry.sha256, FwscPackage.sha256Hex(data) != sha256 { throw Failure.hashMismatch }
        let package = try FwscPackage(data: data)
        if let product = entry.product, package.product != product {
            throw Failure.productMismatch(expected: product, found: package.product)
        }
        if OfficialFirmware.matching(package) == .v15 {
            return try saveOfficial(
                data, package: package, name: OfficialFirmware.v15.name, catalogueID: entry.id, from: packageURL)
        }
        guard !entry.isOfficial else { throw Failure.notOfficial }
        for older in items where older.catalogueID == entry.id {
            remove(older)
        }
        let details = StoredFirmware.Details(
            name: entry.name,
            version: entry.version ?? package.product,
            author: entry.author,
            source: .catalogue,
            catalogueID: entry.id,
            packageURL: packageURL,
            build: entry.build
        )
        return try save(data, package: package, details: details)
    }

    /// Finds FM-1.fwsc on M-VAVE's downloads page and saves it, if it is the known official release.
    @discardableResult
    func downloadOfficialFromMVAVE() async throws -> StoredFirmware {
        guard let found = await PackageDiscovery(fetch: Network.fetch).officialPackage() else {
            throw Failure.officialNotFound
        }
        return try await importOfficial(from: found.url)
    }

    /// Imports a `.fwsc` the user picked in Files.
    ///
    /// Official firmware is recognised by its hash.
    @discardableResult
    func importFile(at url: URL) throws -> StoredFirmware {
        let accessing = url.startAccessingSecurityScopedResource()
        defer { if accessing { url.stopAccessingSecurityScopedResource() } }
        let data = try Data(contentsOf: url)
        return try add(data, fallbackName: url.deletingPathExtension().lastPathComponent)
    }

    /// Downloads the official firmware from a link the user supplies; anything else is refused.
    @discardableResult
    func importOfficial(from remoteURL: URL) async throws -> StoredFirmware {
        let data = try await fetch(remoteURL)
        let package = try FwscPackage(data: data)
        guard OfficialFirmware.matching(package) == .v15 else { throw Failure.notOfficial }
        return try add(data, fallbackName: "Official")
    }

    private func add(_ data: Data, fallbackName: String) throws -> StoredFirmware {
        let package = try FwscPackage(data: data)
        let official = OfficialFirmware.matching(package)
        if official == .v15 {
            return try saveOfficial(
                data, package: package, name: OfficialFirmware.v15.name, catalogueID: nil, from: nil)
        }
        if let existing = existing(package) { return existing }
        // M-VAVE's other releases (FM1 Open) are named and credited, but aren't the official V15.
        let details = StoredFirmware.Details(
            name: official?.name ?? fallbackName,
            version: package.product,
            author: official == nil ? nil : "M-VAVE",
            source: .imported
        )
        return try save(data, package: package, details: details)
    }

    /// Saves the V15 that comes with the app, after checking it's the known release.
    private func saveBundledOfficial(from url: URL) throws {
        let data = try Data(contentsOf: url)
        let package = try FwscPackage(data: data)
        guard OfficialFirmware.matching(package) == .v15 else { throw Failure.notOfficial }
        try saveOfficial(data, package: package, name: OfficialFirmware.v15.name, catalogueID: nil, from: nil)
    }

    /// Saves M-VAVE's own firmware, or returns the copy already saved.
    @discardableResult
    private func saveOfficial(
        _ data: Data,
        package: FwscPackage,
        name: String,
        catalogueID: String?,
        from packageURL: URL?
    ) throws -> StoredFirmware {
        if let existing = existing(package) { return existing }
        let details = StoredFirmware.Details(
            name: name,
            version: "Official",
            author: "M-VAVE",
            source: .official,
            catalogueID: catalogueID,
            packageURL: packageURL
        )
        return try save(data, package: package, details: details)
    }

    /// The saved copy of exactly this package, if there is one.
    private func existing(_ package: FwscPackage) -> StoredFirmware? {
        items.first { $0.sha256 == package.sha256 }
    }

    private func fetch(_ url: URL) async throws -> Data {
        do {
            return try await Network.fetch(url)
        } catch {
            throw Failure.download(error.localizedDescription)
        }
    }

    private func save(_ data: Data, package: FwscPackage, details: StoredFirmware.Details) throws -> StoredFirmware {
        let fileName = "\(package.sha256.prefix(16)).fwsc"
        try data.write(to: directory.appending(path: fileName), options: .atomic)
        let item = StoredFirmware(
            id: package.sha256,
            name: details.name,
            version: details.version,
            author: details.author,
            product: package.product,
            sha256: package.sha256,
            size: data.count,
            fileName: fileName,
            source: details.source,
            catalogueID: details.catalogueID,
            packageURL: details.packageURL,
            build: details.build,
            savedAt: .now
        )
        items.removeAll { $0.id == item.id }
        items.append(item)
        persist()
        return item
    }

    // MARK: - Removing.

    func remove(_ item: StoredFirmware) {
        try? FileManager.default.removeItem(at: url(for: item))
        items.removeAll { $0.id == item.id }
        persist()
    }

    /// Empties the cache, except for the official firmware.
    func clear() {
        for item in items where item.source != .official {
            remove(item)
        }
    }

    // MARK: - Persistence.

    private func load() {
        guard let data = try? Data(contentsOf: indexURL),
            let decoded = try? JSONDecoder().decode([StoredFirmware].self, from: data)
        else {
            return
        }
        // Drop index entries whose files have gone.
        items = decoded.filter { FileManager.default.fileExists(atPath: url(for: $0).path) }
    }

    private func persist() {
        guard let data = try? JSONEncoder().encode(items) else { return }
        try? data.write(to: indexURL, options: .atomic)
    }
}
