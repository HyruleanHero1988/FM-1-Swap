import Foundation

/// The merged firmware list, as saved on the phone.
struct MergedCatalogue: Codable, Sendable, Equatable {
    var builtAt: Date
    /// When the firmware hub last rebuilt its catalogue, as the hub writes it, e.g. "2026-10-14 20:00 UTC".
    var hubUpdated: String?
    var entries: [CatalogueEntry]
    /// Sources that couldn't be read on this refresh, in plain words.
    var problems: [String]
}

/// Builds the merged list: the firmware hub's catalogue, plus every firmware on fm1-editor.com
/// that the hub doesn't carry, with packages discovered for those where possible.
struct CatalogueBuilder: Sendable {
    typealias Fetch = PackageDiscovery.Fetch

    enum Failure: Error, LocalizedError {
        case nothingReachable

        var errorDescription: String? {
            "Couldn't reach the firmware hub or fm1-editor.com."
        }
    }

    var hubURL = HubFeed.defaultURL
    var editorURL = EditorListing.defaultURL
    let fetch: Fetch

    init(fetch: @escaping Fetch) {
        self.fetch = fetch
    }

    /// Fetches both lists and merges them. `previous` supplies discovered packages to fall back
    /// on when a project's site or GitHub can't be reached this time.
    func build(previous: [CatalogueEntry] = []) async throws -> MergedCatalogue {
        async let hubData = try? fetch(hubURL)
        async let editorData = try? fetch(editorURL)
        let hub = await hubData.flatMap { try? HubFeed.decode($0) }
        let editor = await editorData.flatMap { String(data: $0, encoding: .utf8) }
            .map { html in EditorListing(html: html, pageURL: editorURL) }

        var problems: [String] = []
        if hub == nil { problems.append("The firmware hub couldn't be read.") }
        if editor == nil || editor?.entries.isEmpty == true { problems.append("fm1-editor.com couldn't be read.") }
        guard hub != nil || editor?.entries.isEmpty == false else { throw Failure.nothingReachable }

        let merged = Self.merge(
            hub: hub?.entries(relativeTo: hubURL) ?? [], editor: editor?.entries ?? [], editorURL: editorURL)
        let discovery = PackageDiscovery(fetch: fetch)
        let previousByID = Dictionary(previous.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })

        let resolved = await withTaskGroup(of: (Int, CatalogueEntry).self) { group in
            for (index, entry) in merged.enumerated() where entry.packageURL == nil {
                group.addTask {
                    guard let found = await discovery.discover(name: entry.name, links: entry.links) else {
                        return (index, Self.fallBack(entry, to: previousByID[entry.id]))
                    }
                    return (index, Self.apply(found, to: entry))
                }
            }
            var entries = merged
            for await (index, entry) in group {
                entries[index] = entry
            }
            return entries
        }
        return MergedCatalogue(builtAt: .now, hubUpdated: hub?.updated, entries: resolved, problems: problems)
    }

    // MARK: - Merging.

    /// Hub entries, enriched with what fm1-editor.com adds, then the editor's extra firmwares.
    static func merge(hub: [CatalogueEntry], editor: [EditorListing.Entry], editorURL: URL) -> [CatalogueEntry] {
        var entries = hub
        for listed in editor {
            var components = URLComponents(url: editorURL, resolvingAgainstBaseURL: true)
            components?.fragment = listed.anchor
            let pageLink = CatalogueLink(label: "fm1-editor.com listing", url: components?.url ?? editorURL)
            if let index = entries.firstIndex(where: { matches($0, listed) }) {
                var entry = entries[index]
                entry.listing.insert(.editor)
                if entry.what == nil { entry.what = listed.blurb }
                if entry.author == nil { entry.author = listed.author }
                if entry.stars == nil { entry.stars = listed.stars }
                for link in listed.links + [pageLink]
                where !entry.links.contains(where: { $0.comparable == link.comparable }) {
                    entry.links.append(link)
                }
                entries[index] = entry
            } else {
                let isOfficial =
                    listed.anchor == "stock" || listed.links.contains { $0.url.host()?.contains("m-vave.com") == true }
                entries.append(
                    CatalogueEntry(
                        id: isOfficial ? "mvave-official" : "fm1-editor-\(listed.anchor)",
                        name: isOfficial ? "M-VAVE official firmware" : listed.name,
                        author: listed.author,
                        what: listed.blurb,
                        version: listed.latest,
                        date: listed.updated,
                        links: listed.links + [pageLink],
                        listing: .editor,
                        isOfficial: isOfficial,
                        stars: listed.stars
                    ))
            }
        }
        return entries
    }

    /// The same firmware: a shared GitHub repository, or the same name once punctuation is ignored.
    static func matches(_ entry: CatalogueEntry, _ listed: EditorListing.Entry) -> Bool {
        var entryLinks = entry.links
        if let repo = entry.repo, let url = URL(string: "https://github.com/\(repo)") {
            entryLinks.append(CatalogueLink(label: "", url: url))
        }
        let ours = Set(PackageDiscovery.gitHubRepos(in: entryLinks).map { "\($0.owner)/\($0.name)".lowercased() })
        let theirs = Set(PackageDiscovery.gitHubRepos(in: listed.links).map { "\($0.owner)/\($0.name)".lowercased() })
        if !ours.isDisjoint(with: theirs) { return true }
        return normalisedName(entry.name) == normalisedName(listed.name)
    }

    static func normalisedName(_ name: String) -> String {
        name.lowercased().filter { $0.isLetter || $0.isNumber }
    }

    static func apply(_ found: DiscoveredPackage, to entry: CatalogueEntry) -> CatalogueEntry {
        var entry = entry
        entry.packageURL = found.url
        entry.packageOrigin = found.origin
        entry.version = found.version ?? entry.version
        entry.date = found.date ?? entry.date
        entry.beta = entry.beta || found.beta
        entry.product = found.product
        entry.size = found.size
        entry.sha256 = found.sha256
        entry.builds = found.builds
        entry.build = nil
        entry.releaseURL = found.releaseURL ?? entry.releaseURL
        return entry
    }

    /// Keeps the package found on an earlier refresh when discovery fails this time.
    static func fallBack(_ entry: CatalogueEntry, to previous: CatalogueEntry?) -> CatalogueEntry {
        guard let previous, previous.packageURL != nil else { return entry }
        var entry = entry
        entry.packageURL = previous.packageURL
        entry.packageOrigin = previous.packageOrigin
        entry.version = previous.version
        entry.date = previous.date
        entry.beta = previous.beta
        entry.product = previous.product
        entry.size = previous.size
        entry.sha256 = previous.sha256
        return entry
    }
}
