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

/// The on-phone firmware cache: downloads, the user's official firmware, and imports.
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
                return "The downloaded file doesn't match its published size or checksum, so it was not saved. Refresh the list and try again."
            case .productMismatch(let expected, let found):
                return "The file reports \(found), but the catalogue says \(expected). Not saved."
            case .notOfficial:
                return "That file isn't a recognised official FM-1 firmware (only an unmodified V15 FM-1.fwsc is accepted)."
            case .officialNotFound:
                return "Couldn't find FM-1.fwsc on M-VAVE's downloads page. Download it in Safari and import it from Files."
            }
        }
    }

    private(set) var items: [StoredFirmware] = []
    private(set) var downloading: Set<String> = []

    private let directory: URL
    private let indexURL: URL

    init(directory: URL = AppDirectories.support.appending(path: "Firmware", directoryHint: .isDirectory)) {
        self.directory = directory
        indexURL = directory.appending(path: "library.json")
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        load()
    }

    var official: StoredFirmware? {
        items.first { $0.source == .official }
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
            item.catalogueID == entry.id && (entry.version.map { ReleaseVersion.isNewer($0, than: item.version) } ?? true)
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

    // MARK: - Adding.

    /// Downloads a catalogue firmware, checks whatever is published about it (size, hash,
    /// identity), checks it reads as an FM-1 package, and saves it, replacing older releases.
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
        if let official = OfficialFirmware.matching(package) {
            if let existing = items.first(where: { $0.sha256 == package.sha256 }) { return existing }
            return try save(data, package: package, name: official.name, version: "Official", author: "M-VAVE", source: .official, catalogueID: entry.id, packageURL: packageURL)
        }
        guard !entry.isOfficial else { throw Failure.notOfficial }
        for older in items where older.catalogueID == entry.id {
            remove(older)
        }
        return try save(data, package: package, name: entry.name, version: entry.version ?? package.product, author: entry.author, source: .catalogue, catalogueID: entry.id, packageURL: packageURL, build: entry.build)
    }

    /// Finds FM-1.fwsc on M-VAVE's downloads page and saves it, if it is the known official release.
    @discardableResult
    func downloadOfficialFromMVAVE() async throws -> StoredFirmware {
        guard let found = await PackageDiscovery(fetch: Network.fetch).officialPackage() else {
            throw Failure.officialNotFound
        }
        return try await importOfficial(from: found.url)
    }

    /// Imports a `.fwsc` the user picked in Files. Official firmware is recognised by its hash.
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
        guard OfficialFirmware.matching(package) != nil else { throw Failure.notOfficial }
        return try add(data, fallbackName: "Official")
    }

    private func add(_ data: Data, fallbackName: String) throws -> StoredFirmware {
        let package = try FwscPackage(data: data)
        if let official = OfficialFirmware.matching(package) {
            if let existing = items.first(where: { $0.sha256 == package.sha256 }) { return existing }
            return try save(data, package: package, name: official.name, version: "Official", author: "M-VAVE", source: .official, catalogueID: nil, packageURL: nil)
        }
        if let existing = items.first(where: { $0.sha256 == package.sha256 }) { return existing }
        return try save(data, package: package, name: fallbackName, version: package.product, author: nil, source: .imported, catalogueID: nil, packageURL: nil)
    }

    private func fetch(_ url: URL) async throws -> Data {
        do {
            return try await Network.fetch(url)
        } catch {
            throw Failure.download(error.localizedDescription)
        }
    }

    private func save(
        _ data: Data,
        package: FwscPackage,
        name: String,
        version: String,
        author: String?,
        source: StoredFirmware.Source,
        catalogueID: String?,
        packageURL: URL?,
        build: String? = nil
    ) throws -> StoredFirmware {
        let fileName = "\(package.sha256.prefix(16)).fwsc"
        try data.write(to: directory.appending(path: fileName), options: .atomic)
        let item = StoredFirmware(
            id: package.sha256,
            name: name,
            version: version,
            author: author,
            product: package.product,
            sha256: package.sha256,
            size: data.count,
            fileName: fileName,
            source: source,
            catalogueID: catalogueID,
            packageURL: packageURL,
            build: build,
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

    /// Empties the cache. The official firmware is kept unless `includingOfficial` is set.
    func clear(includingOfficial: Bool) {
        for item in items where includingOfficial || item.source != .official {
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
