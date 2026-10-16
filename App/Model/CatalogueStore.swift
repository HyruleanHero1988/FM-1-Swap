import Foundation
import Observation

/// The firmware list: the firmware hub and fm1-editor.com merged, loaded from the on-device
/// copy at launch and rebuilt on request.
@MainActor
@Observable
final class CatalogueStore {
    private(set) var catalogue: MergedCatalogue?
    private(set) var isRefreshing = false
    private(set) var errorMessage: String?
    /// What each firmware's own source said when its page was last opened, by catalogue ID.
    private(set) var sourceChecks: [String: SourceCheck] = [:]
    /// The build picked for each firmware whose releases come in several, by catalogue ID.
    private(set) var chosenBuilds: [String: String] {
        didSet { UserDefaults.standard.set(chosenBuilds, forKey: "chosenBuilds") }
    }

    private let cacheURL: URL

    init(directory: URL = AppDirectories.support) {
        cacheURL = directory.appending(path: "catalogue.json")
        chosenBuilds = UserDefaults.standard.dictionary(forKey: "chosenBuilds") as? [String: String] ?? [:]
        // The first builds cached the hub's feed alone.
        try? FileManager.default.removeItem(at: directory.appending(path: "catalog.json"))
        loadCached()
    }

    /// M-VAVE's firmware first, then by name.
    ///
    /// A newer release found at the firmware's source stands in for the listed one, pointing at the
    /// build picked for it.
    var entries: [CatalogueEntry] {
        (catalogue?.entries ?? []).map(newestKnown).sorted { lhs, rhs in
            if lhs.isOfficial != rhs.isOfficial { return lhs.isOfficial }
            return lhs.name.localizedCaseInsensitiveCompare(rhs.name) == .orderedAscending
        }
    }

    func entry(id: String) -> CatalogueEntry? {
        listedEntry(id: id).map(newestKnown)
    }

    /// The entry as the firmware lists have it, before any newer release found at its source.
    func listedEntry(id: String) -> CatalogueEntry? {
        catalogue?.entries.first { $0.id == id }
    }

    private func newestKnown(_ entry: CatalogueEntry) -> CatalogueEntry {
        let known = defaultBuild(of: entry)
        return chosenBuilds[entry.id].map { known.choosing(build: $0) } ?? known
    }

    /// The newest known release, pointing at the build the lists use (if known), before any pick.
    func defaultBuild(of entry: CatalogueEntry) -> CatalogueEntry {
        switch sourceChecks[entry.id]?.outcome {
        case .newer(let newer), .current(let newer): newer
        case .failed, nil: entry
        }
    }

    /// Picks one of a release's builds; later releases keep to it.
    func choose(build name: String, for id: String) {
        chosenBuilds[id] = name
    }

    /// Asks the firmware's own source (its GitHub releases, or its publisher's site) for a newer
    /// release than the lists show, since they're only rebuilt every so often.
    ///
    /// A result is reused for a few minutes, to stay well inside GitHub's limit on unsigned
    /// requests.
    func checkSource(of id: String) async {
        guard let listed = listedEntry(id: id), ReleaseCheck.source(for: listed) != nil else { return }
        if let previous = sourceChecks[id] {
            let isFresh = Date.now.timeIntervalSince(previous.checkedAt) < 10 * 60
            if previous.outcome == nil || (isFresh && previous.outcome != .failed) { return }
        }
        sourceChecks[id] = SourceCheck(checkedAt: .now, outcome: nil)
        let outcome: SourceCheck.Outcome
        do {
            switch try await ReleaseCheck(fetch: Network.fetch).check(listed) {
            case .newer(let newer): outcome = .newer(newer)
            case .upToDate(let current): outcome = .current(current)
            case .noSource: outcome = .current(listed)
            }
        } catch {
            outcome = .failed
        }
        // The list may have been refreshed meanwhile; only keep a result for the same listing.
        guard listedEntry(id: id) == listed else {
            sourceChecks[id] = nil
            return
        }
        sourceChecks[id] = SourceCheck(checkedAt: .now, outcome: outcome)
    }

    /// Catalogue firmwares known to report this identity once installed.
    func entries(matching product: String) -> [CatalogueEntry] {
        entries.filter { $0.product == product }
    }

    func refresh() async {
        guard !isRefreshing else { return }
        isRefreshing = true
        errorMessage = nil
        defer { isRefreshing = false }
        do {
            let built = try await CatalogueBuilder(fetch: Network.fetch).build(previous: catalogue?.entries ?? [])
            catalogue = built
            // The lists may have caught up; pages check their sources again when opened.
            sourceChecks = [:]
            if let data = try? JSONEncoder().encode(built) {
                try? data.write(to: cacheURL, options: .atomic)
            }
        } catch {
            errorMessage =
                catalogue == nil
                ? "Couldn't fetch the firmware list. Check your connection and try again."
                : "Couldn't refresh; showing the list saved on this phone."
        }
    }

    private func loadCached() {
        guard let data = try? Data(contentsOf: cacheURL) else { return }
        catalogue = try? JSONDecoder().decode(MergedCatalogue.self, from: data)
    }
}

/// The latest check of a firmware's own source for a newer release.
struct SourceCheck: Equatable {
    enum Outcome: Equatable {
        case newer(CatalogueEntry)
        /// The listed release is the newest, with its builds filled in if it has several.
        case current(CatalogueEntry)
        case failed
    }

    var checkedAt: Date
    /// Nil while the check is running.
    var outcome: Outcome?
}

/// Plain HTTP GETs that bypass the URL cache and treat non-2xx answers as failures.
enum Network {
    struct BadStatus: LocalizedError {
        var code: Int

        var errorDescription: String? {
            code == 404 ? "not found (refresh the list for the latest release)" : "HTTP \(code)"
        }
    }

    @Sendable
    static func fetch(_ url: URL) async throws -> Data {
        var request = URLRequest(url: url)
        request.cachePolicy = .reloadIgnoringLocalCacheData
        request.timeoutInterval = 30
        let (data, response) = try await URLSession.shared.data(for: request)
        if let http = response as? HTTPURLResponse, !(200..<300).contains(http.statusCode) {
            throw BadStatus(code: http.statusCode)
        }
        return data
    }
}

enum AppDirectories {
    /// Application Support, created on first use.
    ///
    /// Kept out of iCloud backups (it is a re-downloadable cache).
    static let support: URL = {
        let url = URL.applicationSupportDirectory.appending(path: "FMSwitch", directoryHint: .isDirectory)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        var mutableURL = url
        try? mutableURL.setResourceValues(values)
        return url
    }()
}
