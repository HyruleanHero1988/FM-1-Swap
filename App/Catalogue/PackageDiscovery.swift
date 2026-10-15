import FM1Kit
import Foundation

/// A package found for a firmware that the hub doesn't mirror.
struct DiscoveredPackage: Sendable, Hashable {
    var url: URL
    var origin: CatalogueEntry.PackageOrigin
    var version: String?
    var date: String?
    var beta: Bool
    var product: String?
    var size: Int?
    var sha256: String?
    /// Every build the release offers, when there's more than one.
    var builds: [CatalogueEntry.Build]? = nil
    var releaseURL: URL? = nil
}

/// Finds downloadable `.fwsc` packages for a firmware from its links. In order:
/// M-VAVE's download page (official firmware only), the newest GitHub release with a `.fwsc`
/// asset, a release list the publisher embeds in their page (as Baud Girl's install page does),
/// and finally `.fwsc` links on the project pages themselves. A page link is only taken when
/// its file name shares a word with the firmware's name or repository, since pages such as
/// shared installers can link other firmwares too.
struct PackageDiscovery: Sendable {
    typealias Fetch = @Sendable (URL) async throws -> Data

    static let mvaveDownloadsURL = URL(string: "https://www.m-vave.com/download")!

    let fetch: Fetch

    init(fetch: @escaping Fetch) {
        self.fetch = fetch
    }

    func discover(name: String, links: [CatalogueLink]) async -> DiscoveredPackage? {
        if links.contains(where: { $0.url.host()?.contains("m-vave.com") == true }) {
            return await officialPackage()
        }
        for repo in Self.gitHubRepos(in: links) {
            if let found = await latestRelease(owner: repo.owner, repo: repo.name) {
                return found
            }
        }
        let words = Self.identifyingWords(name: name, links: links)
        var pages = links.map(\.url).filter { $0.host() != "github.com" && $0.pathExtension.lowercased() != "zip" }
        var visited: Set<String> = []
        while let page = pages.first, visited.count < 6 {
            pages.removeFirst()
            guard visited.insert(page.absoluteString).inserted,
                  let data = try? await fetch(page),
                  let pageHTML = String(data: data, encoding: .utf8)
            else {
                continue
            }
            let html = HTML.withoutComments(pageHTML)
            if let found = Self.manifestPackage(in: html, pageURL: page) {
                return found
            }
            let relevant = Self.packageLinks(in: html, pageURL: page).filter { Self.isRelevant($0, words: words) }
            if let found = await firstWorkingPackage(in: relevant) {
                return found
            }
            // Publishers often keep the files on an install page one link away.
            pages += Self.installPageLinks(in: html, pageURL: page).filter { !visited.contains($0.absoluteString) }
        }
        return nil
    }

    /// M-VAVE's own FM-1 firmware, linked from their downloads page and checked by hash on download.
    func officialPackage() async -> DiscoveredPackage? {
        guard let data = try? await fetch(Self.mvaveDownloadsURL),
              let html = String(data: data, encoding: .utf8),
              let url = Self.packageLinks(in: html, pageURL: Self.mvaveDownloadsURL, includingOfficial: true)
                  .first(where: { $0.lastPathComponent.caseInsensitiveCompare("FM-1.fwsc") == .orderedSame })
        else {
            return nil
        }
        let official = OfficialFirmware.v15
        return DiscoveredPackage(url: url, origin: .mvave, version: "V15", date: nil, beta: false, product: nil, size: official.size, sha256: official.sha256)
    }

    // MARK: - GitHub.

    struct GitHubRelease: Decodable {
        struct Asset: Decodable {
            var name: String
            var size: Int
            var digest: String?
            var browser_download_url: URL
        }

        var tag_name: String
        var html_url: URL?
        var draft: Bool
        var prerelease: Bool
        var published_at: String?
        var assets: [Asset]
    }

    func latestRelease(owner: String, repo: String, track: ReleaseTrack = .preferFull) async -> DiscoveredPackage? {
        guard let releases = try? await gitHubReleases(owner: owner, repo: repo) else { return nil }
        return Self.pick(from: releases, track: track)
    }

    func gitHubReleases(owner: String, repo: String) async throws -> [GitHubRelease] {
        let url = URL(string: "https://api.github.com/repos/\(owner)/\(repo)/releases?per_page=10")!
        return try JSONDecoder().decode([GitHubRelease].self, from: try await fetch(url))
    }

    /// Which GitHub releases count.
    enum ReleaseTrack: Sendable {
        /// The newest full release, or the newest pre-release if there are no full ones.
        case preferFull
        /// Full releases only.
        case fullOnly
        /// Pre-releases only.
        case preReleaseOnly
    }

    /// The newest release with a `.fwsc` asset on the given track.
    static func newest(in releases: [GitHubRelease], track: ReleaseTrack = .preferFull) -> GitHubRelease? {
        let usable = releases.filter { !$0.draft && !builds(of: $0).isEmpty }
        return switch track {
        case .preferFull: usable.first(where: { !$0.prerelease }) ?? usable.first
        case .fullOnly: usable.first(where: { !$0.prerelease })
        case .preReleaseOnly: usable.first(where: { $0.prerelease })
        }
    }

    /// The newest release on the given track. When it offers several builds, the package points
    /// at the plainest-named one, but no build is marked as chosen: that's left to the person.
    static func pick(from releases: [GitHubRelease], track: ReleaseTrack = .preferFull) -> DiscoveredPackage? {
        newest(in: releases, track: track).flatMap { package(from: $0, build: nil) }
    }

    /// The release as a package, pointing at the named build (or the plainest one).
    static func package(from release: GitHubRelease, build name: String?) -> DiscoveredPackage? {
        let builds = builds(of: release)
        guard let plainest = builds.min(by: { $0.url.lastPathComponent.count < $1.url.lastPathComponent.count }) else { return nil }
        let chosen = builds.first { $0.name == name } ?? plainest
        return DiscoveredPackage(
            url: chosen.url,
            origin: .githubRelease,
            version: release.tag_name,
            date: release.published_at.map { String($0.prefix(10)) },
            beta: release.prerelease,
            product: nil,
            size: chosen.size,
            sha256: chosen.sha256,
            builds: builds.count > 1 ? builds : nil,
            releaseURL: release.html_url
        )
    }

    /// The release's `.fwsc` packages (M-VAVE's own excepted), named by what sets them apart.
    static func builds(of release: GitHubRelease) -> [CatalogueEntry.Build] {
        let assets = release.assets.filter { $0.name.lowercased().hasSuffix(".fwsc") && !isOfficialFileName($0.name) }
        let names = buildNames(for: assets.map(\.name))
        return zip(assets, names).map { asset, name in
            let digest = asset.digest.flatMap { $0.hasPrefix("sha256:") ? String($0.dropFirst("sha256:".count)) : nil }
            return CatalogueEntry.Build(name: name, url: asset.browser_download_url, size: asset.size, sha256: digest)
        }
    }

    /// Short names for a release's packages: what's left of each file name once the part they all
    /// share is taken off both ends, cut at "-", "_" or ".". So `melodee-1.3.6-1core.fwsc` and
    /// `melodee-1.3.6.fwsc` become "1core" and "standard", and `bubba-diag-0.6.1-beta.fwsc` and
    /// `bubba-machine-0.6.1-beta.fwsc` become "diag" and "machine".
    static func buildNames(for fileNames: [String]) -> [String] {
        let stems = fileNames.map { name in
            name.lowercased().hasSuffix(".fwsc") ? Array(name.dropLast(".fwsc".count)) : Array(name)
        }
        guard stems.count > 1 else { return stems.map { String($0) } }
        let isSeparator: (Character) -> Bool = { "-_. ".contains($0) }
        let shortest = stems.map(\.count).min() ?? 0

        // The shared start, cut back to a separator.
        var prefix = 0
        while prefix < shortest, stems.allSatisfy({ $0[prefix] == stems[0][prefix] }) { prefix += 1 }
        while prefix > 0, !(isSeparator(stems[0][prefix - 1]) || stems.allSatisfy({ $0.count == prefix || isSeparator($0[prefix]) })) {
            prefix -= 1
        }

        // The shared end, likewise, never overlapping the start.
        var suffix = 0
        while suffix < shortest - prefix, stems.allSatisfy({ $0[$0.count - 1 - suffix] == stems[0][stems[0].count - 1 - suffix] }) { suffix += 1 }
        while suffix > 0, !(isSeparator(stems[0][stems[0].count - suffix]) || stems.allSatisfy({ $0.count == suffix || isSeparator($0[$0.count - 1 - suffix]) })) {
            suffix -= 1
        }

        let names = stems.map { stem in
            let middle = String(stem[prefix..<(stem.count - suffix)]).trimmingCharacters(in: CharacterSet(charactersIn: "-_. "))
            return middle.isEmpty ? "standard" : middle
        }
        return Set(names).count == names.count ? names : stems.map { String($0) }
    }

    /// GitHub repositories named by `github.com/owner/repo` and `owner.github.io/repo` links.
    static func gitHubRepos(in links: [CatalogueLink]) -> [(owner: String, name: String)] {
        var repos: [(owner: String, name: String)] = []
        for link in links {
            guard let host = link.url.host()?.lowercased() else { continue }
            let parts = link.url.pathComponents.filter { $0 != "/" }
            var repo: (owner: String, name: String)?
            if host == "github.com", parts.count >= 2 {
                repo = (parts[0], parts[1].replacingOccurrences(of: ".git", with: ""))
            } else if host.hasSuffix(".github.io"), let first = parts.first {
                repo = (String(host.dropLast(".github.io".count)), first)
            }
            if let repo, !repos.contains(where: { $0.owner.lowercased() == repo.owner.lowercased() && $0.name.lowercased() == repo.name.lowercased() }) {
                repos.append(repo)
            }
        }
        return repos
    }

    // MARK: - Pages.

    struct ManifestRelease: Decodable {
        var version: String?
        var file: String
        var sha256: String?
        var date: String?
        var beta: Bool?
        var current: Bool?
    }

    /// A JSON release list embedded in the page, as `[{"version", "file", "sha256", "beta", "current"}]`.
    static func manifestPackage(in html: String, pageURL: URL) -> DiscoveredPackage? {
        for script in HTML.elements("script", in: html) where (script.attributes["type"] ?? "").contains("json") {
            guard let releases = try? JSONDecoder().decode([ManifestRelease].self, from: Data(script.inner.utf8)),
                  !releases.isEmpty
            else {
                continue
            }
            let fwsc = releases.filter { $0.file.lowercased().hasSuffix(".fwsc") }
            guard let chosen = fwsc.first(where: { $0.current == true }) ?? fwsc.first(where: { $0.beta != true }) ?? fwsc.first,
                  let url = URL(string: chosen.file, relativeTo: pageURL)?.absoluteURL
            else {
                continue
            }
            let product = chosen.version.flatMap { $0.wholeMatch(of: #/FM-1_\d+/#) != nil ? $0 : nil }
            return DiscoveredPackage(
                url: url,
                origin: .publisherManifest,
                version: chosen.version,
                date: chosen.date,
                beta: chosen.beta ?? false,
                product: product,
                size: nil,
                sha256: chosen.sha256?.lowercased()
            )
        }
        return nil
    }

    /// Absolute addresses of `.fwsc` files the page mentions, M-VAVE's `FM-1.fwsc` excluded.
    static func packageLinks(in html: String, pageURL: URL, includingOfficial: Bool = false) -> [URL] {
        var found: [URL] = []
        for match in html.matches(of: #/["'(=]([^"'()<>\s]*[A-Za-z0-9_\-.+%]\.fwsc)(?=["'()<>\s?#])/#) {
            let path = String(match.output.1)
            guard let url = URL(string: path, relativeTo: pageURL)?.absoluteURL,
                  url.scheme?.hasPrefix("http") == true,
                  includingOfficial || !isOfficialFileName(url.lastPathComponent),
                  !found.contains(url)
            else {
                continue
            }
            found.append(url)
        }
        return found
    }

    /// Same-site links whose path mentions "install", e.g. `/work/FM-1+VA/install`.
    static func installPageLinks(in html: String, pageURL: URL) -> [URL] {
        var found: [URL] = []
        for anchor in HTML.elements("a", in: html) {
            guard let href = anchor.attributes["href"],
                  let url = URL(string: href, relativeTo: pageURL)?.absoluteURL,
                  url.host() == pageURL.host(),
                  url.path().lowercased().contains("install"),
                  url.path() != pageURL.path(),
                  !found.contains(url)
            else {
                continue
            }
            found.append(url)
        }
        return Array(found.prefix(2))
    }

    /// Page links have no published checksum, and pages sometimes name files they don't host, so
    /// each candidate (newest first) is downloaded and must read as an FM-1 package to be listed.
    func firstWorkingPackage(in candidates: [URL]) async -> DiscoveredPackage? {
        let ordered = candidates.sorted { $0.lastPathComponent.localizedStandardCompare($1.lastPathComponent) == .orderedDescending }
        for url in ordered.prefix(3) {
            guard let data = try? await fetch(url), let package = try? FwscPackage(data: data) else { continue }
            return DiscoveredPackage(url: url, origin: .projectPage, version: nil, date: nil, beta: false, product: package.product, size: data.count, sha256: nil)
        }
        return nil
    }

    /// Distinctive words from the firmware's name and repository names, e.g. "doom" for "FM-1 Doom".
    static func identifyingWords(name: String, links: [CatalogueLink]) -> Set<String> {
        let common: Set<String> = ["fm", "fm1", "the", "and", "for", "firmware", "patch", "synth", "web", "app", "github", "io"]
        let sources = [name] + gitHubRepos(in: links).map(\.name)
        var words: Set<String> = []
        for source in sources {
            for word in source.lowercased().split(whereSeparator: { !$0.isLetter && !$0.isNumber }) {
                let word = String(word)
                if word.count >= 3, !common.contains(word), !word.allSatisfy(\.isNumber) {
                    words.insert(word)
                }
            }
        }
        return words
    }

    static func isRelevant(_ url: URL, words: Set<String>) -> Bool {
        let fileName = url.lastPathComponent.lowercased().filter { $0.isLetter || $0.isNumber }
        return words.contains { fileName.contains($0) }
    }

    static func isOfficialFileName(_ name: String) -> Bool {
        name.caseInsensitiveCompare("FM-1.fwsc") == .orderedSame
    }
}
