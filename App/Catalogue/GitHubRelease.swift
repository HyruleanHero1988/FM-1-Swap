import Foundation

/// A release as GitHub's REST API lists it, with only the fields the app reads.
struct GitHubRelease: Decodable {
    struct Asset: Decodable {
        var name: String
        var size: Int
        var digest: String?
        var downloadURL: URL

        enum CodingKeys: String, CodingKey {
            case name, size, digest
            case downloadURL = "browser_download_url"
        }
    }

    var tagName: String
    var pageURL: URL?
    var draft: Bool
    var prerelease: Bool
    var publishedAt: String?
    var assets: [Asset]

    enum CodingKeys: String, CodingKey {
        case draft, prerelease, assets
        case tagName = "tag_name"
        case pageURL = "html_url"
        case publishedAt = "published_at"
    }
}
