import Foundation

/// Asks a firmware's own source whether there is a newer release than the one listed.
///
/// The firmware hub and fm1-editor.com only rebuild their lists every so often, while some
/// projects release several times a day, so a listing can be a release or two behind. The
/// check goes to the project's GitHub releases (or, for firmwares found on their publisher's
/// site, that site again) and, if it finds a newer version on the same track, returns the
/// entry pointing at that release instead.
struct ReleaseCheck: Sendable {
    enum Outcome: Sendable, Hashable {
        /// The source has a newer release; this is the entry for it.
        case newer(CatalogueEntry)
        /// The listed release is the newest the source has. The entry is the listed one, with the
        /// release's builds filled in when it offers several.
        case upToDate(CatalogueEntry)
        /// There's nothing to check against (no GitHub repository or publisher release list).
        case noSource
    }

    enum Source: String, Sendable {
        case gitHub = "GitHub"
        case publisher = "the publisher's site"
    }

    let discovery: PackageDiscovery

    init(fetch: @escaping PackageDiscovery.Fetch) {
        discovery = PackageDiscovery(fetch: fetch)
    }

    /// Where `check` would look for this firmware, or nil when there's nowhere to look.
    static func source(for entry: CatalogueEntry) -> Source? {
        guard !entry.isOfficial, entry.packageURL != nil else { return nil }
        if !PackageDiscovery.gitHubRepos(in: links(of: entry)).isEmpty { return .gitHub }
        if entry.packageOrigin == .publisherManifest { return .publisher }
        return nil
    }

    /// Throws when the source can't be reached, so a failed check isn't mistaken for "up to date".
    func check(_ entry: CatalogueEntry) async throws -> Outcome {
        switch Self.source(for: entry) {
        case .gitHub:
            return try await checkGitHub(entry)
        case .publisher:
            guard let found = await discovery.discover(name: entry.name, links: entry.links),
                  found.url != entry.packageURL,
                  let version = Self.version(of: found),
                  ReleaseVersion.isNewer(version, than: entry.version)
            else {
                return .upToDate(entry)
            }
            var newer = CatalogueBuilder.apply(found, to: entry)
            newer.version = version
            newer.beta = found.beta
            return .newer(newer)
        case nil:
            return .noSource
        }
    }

    func checkGitHub(_ entry: CatalogueEntry) async throws -> Outcome {
        var lastError: (any Error)?
        for repo in PackageDiscovery.gitHubRepos(in: Self.links(of: entry)) {
            let releases: [PackageDiscovery.GitHubRelease]
            do {
                releases = try await discovery.gitHubReleases(owner: repo.owner, repo: repo.name)
            } catch {
                lastError = error
                continue
            }
            guard let newest = Self.newest(in: releases, for: entry) else { continue }
            let listed = Self.listedBuild(of: entry, in: releases)
            if let newestPackage = PackageDiscovery.package(from: newest, build: listed?.build),
               let version = Self.version(of: newestPackage),
               ReleaseVersion.isNewer(version, than: entry.version) {
                var newer = CatalogueBuilder.apply(newestPackage, to: entry)
                newer.version = version
                newer.beta = newest.prerelease
                // Keep the build the list (or the person) had; with no match, they choose.
                if let builds = newer.builds {
                    newer.build = builds.contains { $0.name == listed?.build } ? listed?.build : nil
                }
                return .newer(newer)
            }
            var current = entry
            if let listed, let release = listed.release {
                let builds = PackageDiscovery.builds(of: release)
                if builds.count > 1 {
                    current.builds = builds
                    current.build = listed.build
                }
                current.releaseURL = release.html_url ?? current.releaseURL
            }
            return .upToDate(current)
        }
        if let lastError { throw lastError }
        return .upToDate(entry)
    }

    /// The newest release on the listed track: a full release isn't replaced by a pre-release,
    /// while a beta moves on to whichever is higher, the next pre-release or the full release.
    static func newest(in releases: [PackageDiscovery.GitHubRelease], for entry: CatalogueEntry) -> PackageDiscovery.GitHubRelease? {
        let full = PackageDiscovery.newest(in: releases, track: .fullOnly)
        let preRelease = entry.beta ? PackageDiscovery.newest(in: releases, track: .preReleaseOnly) : nil
        guard let full, let preRelease else { return full ?? preRelease }
        return ReleaseVersion.isNewer(preRelease.tag_name, than: full.tag_name) ? preRelease : full
    }

    /// The release the list points at, and which of its builds, found by the listed file's
    /// checksum (the hub renames the files it mirrors) or address. With neither, the release
    /// with the listed version is found, but not the build.
    static func listedBuild(of entry: CatalogueEntry, in releases: [PackageDiscovery.GitHubRelease]) -> (release: PackageDiscovery.GitHubRelease?, build: String?)? {
        for release in releases {
            if let build = PackageDiscovery.builds(of: release).first(where: {
                ($0.sha256 != nil && $0.sha256 == entry.sha256) || $0.url == entry.packageURL
            }) {
                return (release, build.name)
            }
        }
        if let version = entry.version, let release = releases.first(where: {
            !ReleaseVersion.isNewer($0.tag_name, than: version) && !ReleaseVersion.isNewer(version, than: $0.tag_name)
        }) {
            return (release, nil)
        }
        return nil
    }

    /// The release's version; some tags carry none (e.g. "ETCH-Update"), but the file name usually does.
    static func version(of package: DiscoveredPackage) -> String? {
        [package.version, package.url.deletingPathExtension().lastPathComponent]
            .compactMap { $0 }
            .first { $0.contains(where: \.isNumber) }
    }

    /// The entry's links plus its repository, which the hub names without always linking it.
    static func links(of entry: CatalogueEntry) -> [CatalogueLink] {
        guard let repo = entry.repo, let url = URL(string: "https://github.com/\(repo)") else { return entry.links }
        return [CatalogueLink(label: "", url: url)] + entry.links
    }
}

/// Loose version ordering for release names such as "v1.3.10", "drum-v0.16.0", "1.4.0-rc.4"
/// or "FM-1_101".
enum ReleaseVersion {
    /// True when `candidate` is a later release than `listed`. Anything before the first digit is
    /// ignored, numbers compare as numbers, and a pre-release ("-rc.1", "-beta") comes before
    /// the release it leads up to. With no listed version there's nothing to compare, so false.
    static func isNewer(_ candidate: String, than listed: String?) -> Bool {
        guard let listed else { return false }
        let (candidateCore, candidatePre) = split(candidate)
        let (listedCore, listedPre) = split(listed)
        switch candidateCore.compare(listedCore, options: [.numeric, .caseInsensitive]) {
        case .orderedDescending: return true
        case .orderedAscending: return false
        case .orderedSame:
            switch (candidatePre, listedPre) {
            case (nil, .some): return true
            case let (.some(candidatePre), .some(listedPre)):
                return candidatePre.compare(listedPre, options: [.numeric, .caseInsensitive]) == .orderedDescending
            default: return false
            }
        }
    }

    /// The version from its first digit, and any pre-release part after a "-" and a letter.
    static func split(_ version: String) -> (core: String, preRelease: String?) {
        let text = version.firstIndex(where: \.isNumber).map { String(version[$0...]) } ?? version
        guard let match = text.firstMatch(of: #/-(?=[A-Za-z])/#) else { return (text, nil) }
        return (String(text[..<match.range.lowerBound]), String(text[match.range.upperBound...]))
    }
}
