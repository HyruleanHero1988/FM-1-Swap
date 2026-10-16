import Foundation

/// One firmware in the merged list, from the firmware hub, fm1-editor.com, or both.
///
/// Hub entries always carry a package with a size and SHA-256. Entries found only on
/// fm1-editor.com get their package from wherever it can be discovered (a GitHub release,
/// the publisher's own release list, or a link on the project page), so the package, its
/// checksum and the identity it reports may be unknown until it is downloaded.
struct CatalogueEntry: Codable, Sendable, Identifiable, Hashable {
    /// Which lists the firmware appears on.
    struct Listing: OptionSet, Codable, Sendable, Hashable {
        let rawValue: Int

        static let hub = Self(rawValue: 1 << 0)
        static let editor = Self(rawValue: 1 << 1)
    }

    /// Where the package link came from.
    enum PackageOrigin: String, Codable, Sendable, Hashable {
        /// The firmware hub's mirror, with a size and SHA-256.
        case hub
        /// An asset on the project's GitHub release.
        case githubRelease
        /// The publisher's own release list (e.g. Baud Girl's), with a SHA-256.
        case publisherManifest
        /// A `.fwsc` link found on the project's web page; no published checksum.
        case projectPage
        /// M-VAVE's own download, checked against the known official hash.
        case mvave
    }

    /// One of several packages a release offers, e.g. Melodee's standard and "1core" builds.
    struct Build: Codable, Sendable, Hashable {
        /// What sets this build apart, from its file name: "1core", "bluetooth", or "standard".
        var name: String
        var url: URL
        var size: Int?
        var sha256: String?

        init(name: String, url: URL, size: Int? = nil, sha256: String? = nil) {
            self.name = name
            self.url = url
            self.size = size
            self.sha256 = sha256?.lowercased()
        }
    }

    var id: String
    var name: String
    var author: String?
    /// Categories such as "synth", "groove" or "drums".
    var kinds: [String]
    /// The description.
    var what: String?
    var version: String?
    /// The release date (yyyy-mm-dd).
    var date: String?
    var beta: Bool
    var packageURL: URL?
    var packageOrigin: PackageOrigin?
    /// The identity the device reports once installed, e.g. `FM-1_916`, when known in advance.
    var product: String?
    var size: Int?
    var sha256: String?
    /// Set to "mvave" when the firmware has to be installed from the official firmware.
    var loader: String?
    var links: [CatalogueLink]
    var repo: String?
    var listing: Listing
    /// M-VAVE's own firmware.
    var isOfficial: Bool
    /// The builds the release offers, when there's more than one; nil until known.
    var builds: [Build]?
    /// The build `packageURL` points at, when the release has several and it's known which.
    var build: String?
    /// The release's page, with its notes.
    var releaseURL: URL?
    /// The project's star count, as the firmware lists give it.
    var stars: Int?

    init(
        id: String,
        name: String,
        author: String? = nil,
        kinds: [String] = [],
        what: String? = nil,
        version: String? = nil,
        date: String? = nil,
        beta: Bool = false,
        packageURL: URL? = nil,
        packageOrigin: PackageOrigin? = nil,
        product: String? = nil,
        size: Int? = nil,
        sha256: String? = nil,
        loader: String? = nil,
        links: [CatalogueLink] = [],
        repo: String? = nil,
        listing: Listing,
        isOfficial: Bool = false,
        stars: Int? = nil
    ) {
        self.id = id
        self.name = name
        self.author = author
        self.kinds = kinds
        self.what = what
        self.version = version
        self.date = date
        self.beta = beta
        self.packageURL = packageURL
        self.packageOrigin = packageOrigin
        self.product = product
        self.size = size
        self.sha256 = sha256?.lowercased()
        self.loader = loader
        self.links = links
        self.repo = repo
        self.listing = listing
        self.isOfficial = isOfficial
        self.stars = stars
    }

    /// True when the official firmware has to be running before this one can be installed.
    var requiresOfficialFirmware: Bool {
        loader?.lowercased() == "mvave"
    }

    var isDownloadable: Bool { packageURL != nil }

    /// True when the publisher (or the hub) states the package's SHA-256.
    var hasPublishedChecksum: Bool { sha256 != nil }

    /// True when the release has several builds and none is chosen yet, so saving has to wait.
    var needsBuildChoice: Bool { (builds?.count ?? 0) > 1 && build == nil }

    /// The entry pointing at the named build, or unchanged if there's no such build.
    func choosing(build name: String) -> Self {
        guard let chosen = builds?.first(where: { $0.name == name }) else { return self }
        var entry = self
        // The identity the list gives belongs to its own build; another build may report another.
        if chosen.sha256 == nil || chosen.sha256 != sha256 { entry.product = nil }
        entry.packageURL = chosen.url
        entry.packageOrigin = .githubRelease
        entry.size = chosen.size
        entry.sha256 = chosen.sha256
        entry.build = name
        return entry
    }
}

/// A labelled link shown on a firmware's page.
struct CatalogueLink: Codable, Sendable, Hashable {
    var label: String
    var url: URL

    init(label: String, url: URL) {
        self.label = label
        self.url = url
    }

    /// The address without scheme or trailing slash, for spotting the same link twice.
    var comparable: String {
        var text = url.absoluteString.lowercased()
        for prefix in ["https://", "http://", "www."] where text.hasPrefix(prefix) {
            text.removeFirst(prefix.count)
        }
        while text.hasSuffix("/") { text.removeLast() }
        return text
    }
}

// MARK: - The firmware hub feed.

/// The firmware hub's catalogue (`https://thefirmwarehub.com/catalog.json`), rebuilt from each
/// project's latest GitHub release and mirrored on the hub with SHA-256 hashes.
struct HubFeed: Decodable, Sendable {
    static let defaultURL = URL(literal: "https://thefirmwarehub.com/catalog.json")

    /// When the hub last rebuilt the catalogue, e.g. "2026-10-14 20:00 UTC".
    var updated: String?
    var firmwares: [Firmware]

    static func decode(_ data: Data) throws -> Self {
        try JSONDecoder().decode(Self.self, from: data)
    }

    /// The feed's entries, with package paths resolved against the feed's address.
    func entries(relativeTo feedURL: URL = Self.defaultURL) -> [CatalogueEntry] {
        firmwares.map { $0.entry(relativeTo: feedURL) }
    }

    struct Firmware: Decodable, Sendable {
        var id: String
        var name: String
        var author: String?
        var kinds: [String]
        var what: String?
        var version: String
        var date: String?
        var beta: Bool
        var pkg: String
        var product: String
        var size: Int
        var sha256: String
        var loader: String?
        var site: String?
        var source: String?
        var release: String?
        var editor: String?
        var play: String?
        var repo: String?
        var stars: Int?

        enum CodingKeys: String, CodingKey {
            case id, name, author, what, version, date, beta, pkg, product, size, sha256, loader, site, source, release,
                editor, play, repo, stars
            case kinds = "kind"
        }

        init(from decoder: any Decoder) throws {
            let container = try decoder.container(keyedBy: CodingKeys.self)
            id = try container.decode(String.self, forKey: .id)
            name = try container.decode(String.self, forKey: .name)
            author = try container.decodeIfPresent(String.self, forKey: .author)
            if let list = try? container.decode([String].self, forKey: .kinds) {
                kinds = list
            } else {
                kinds = (try? container.decode(String.self, forKey: .kinds)).map { [$0] } ?? []
            }
            what = try container.decodeIfPresent(String.self, forKey: .what)
            version = try container.decode(String.self, forKey: .version)
            date = try container.decodeIfPresent(String.self, forKey: .date)
            beta = try container.decodeIfPresent(Bool.self, forKey: .beta) ?? false
            pkg = try container.decode(String.self, forKey: .pkg)
            product = try container.decode(String.self, forKey: .product)
            size = try container.decode(Int.self, forKey: .size)
            sha256 = try container.decode(String.self, forKey: .sha256)
            loader = try container.decodeIfPresent(String.self, forKey: .loader)
            site = try container.decodeIfPresent(String.self, forKey: .site)
            source = try container.decodeIfPresent(String.self, forKey: .source)
            release = try container.decodeIfPresent(String.self, forKey: .release)
            editor = try container.decodeIfPresent(String.self, forKey: .editor)
            play = try container.decodeIfPresent(String.self, forKey: .play)
            repo = try container.decodeIfPresent(String.self, forKey: .repo)
            stars = try container.decodeIfPresent(Int.self, forKey: .stars)
        }

        func entry(relativeTo feedURL: URL) -> CatalogueEntry {
            let labelled: [(String, String?)] = [
                ("Project site", site),
                ("Source code", source),
                ("Release", release),
                ("Editor", editor),
                ("Try it in the browser", play),
            ]
            var links: [CatalogueLink] = []
            for (label, address) in labelled {
                guard let address, let url = URL(string: address), url.scheme != nil else { continue }
                let link = CatalogueLink(label: label, url: url)
                if !links.contains(where: { $0.comparable == link.comparable }) {
                    links.append(link)
                }
            }
            return CatalogueEntry(
                id: id,
                name: name,
                author: author,
                kinds: kinds,
                what: what,
                version: version,
                date: date,
                beta: beta,
                packageURL: URL(string: pkg, relativeTo: feedURL)?.absoluteURL,
                packageOrigin: .hub,
                product: product,
                size: size,
                sha256: sha256,
                loader: loader,
                links: links,
                repo: repo,
                listing: .hub,
                stars: stars
            )
        }
    }
}
