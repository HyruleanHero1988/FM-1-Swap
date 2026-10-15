import Foundation

/// The firmware list on fm1-editor.com (`https://fm1-editor.com/firmware/`), read from its HTML.
///
/// The page has no feed, so each `<article class="firmware-entry">` is picked apart: its
/// anchor, title, author, description, latest version, update date, stars and links. It lists
/// firmwares the hub does not, such as Baud Girl's and M-VAVE's own.
struct EditorListing: Sendable {
    static let defaultURL = URL(string: "https://fm1-editor.com/firmware/")!

    struct Entry: Sendable, Hashable {
        /// The page anchor, e.g. "baud-girl".
        var anchor: String
        var name: String
        var author: String?
        var blurb: String?
        /// The page's "Latest …" text, e.g. "V15" or "FM-1_101".
        var latest: String?
        var updated: String?
        var stars: Int?
        /// Pinned to the top of the page (M-VAVE's and Baud Girl's).
        var pinned: Bool
        var links: [CatalogueLink]
    }

    var entries: [Entry]

    init(html: String, pageURL: URL = EditorListing.defaultURL) {
        entries = HTML.elements("article", in: HTML.withoutComments(html)).compactMap { article in
            Self.entry(from: article, pageURL: pageURL)
        }
    }

    private static func entry(from article: HTML.Element, pageURL: URL) -> Entry? {
        guard (article.attributes["class"] ?? "").contains("firmware-entry"),
              let anchor = article.attributes["id"],
              let title = HTML.elements("h2", in: article.inner).first.map({ HTML.text($0.inner) }),
              !title.isEmpty
        else {
            return nil
        }
        let paragraphs = HTML.elements("p", in: article.inner)
        let author = paragraphs.first { ($0.attributes["class"] ?? "").contains("synthwave-kicker") }.map { HTML.text($0.inner) }
        let blurbs = paragraphs
            .filter { ($0.attributes["class"] ?? "").contains("firmware-blurb") }
            .map { HTML.text($0.inner) }
            .filter { !$0.isEmpty }
        let latest = HTML.elements("span", in: article.inner)
            .map { HTML.text($0.inner) }
            .first { $0.hasPrefix("Latest ") }
            .map { String($0.dropFirst("Latest ".count)) }

        var links: [CatalogueLink] = []
        let linkList = HTML.elements("ul", in: article.inner).first { ($0.attributes["class"] ?? "").contains("firmware-links") }
        for anchorElement in HTML.elements("a", in: linkList?.inner ?? "") {
            guard let href = anchorElement.attributes["href"],
                  let url = URL(string: href, relativeTo: pageURL)?.absoluteURL,
                  url.scheme?.hasPrefix("http") == true
            else {
                continue
            }
            let label = HTML.text(anchorElement.inner)
                .replacingOccurrences(of: "↗", with: "")
                .trimmingCharacters(in: .whitespaces)
            let link = CatalogueLink(label: label.isEmpty ? (url.host() ?? "Link") : label, url: url)
            if !links.contains(where: { $0.comparable == link.comparable }) {
                links.append(link)
            }
        }

        return Entry(
            anchor: anchor,
            name: title,
            author: author.flatMap { $0.isEmpty ? nil : $0 },
            blurb: blurbs.isEmpty ? nil : blurbs.joined(separator: "\n\n"),
            latest: latest,
            updated: article.attributes["data-updated"],
            stars: article.attributes["data-stars"].flatMap(Int.init),
            pinned: article.attributes["data-pinned"] != nil,
            links: links
        )
    }
}

/// Just enough HTML reading for the firmware pages: elements by tag, attributes and text.
enum HTML {
    struct Element {
        var attributes: [String: String]
        var inner: String
    }

    /// Elements with this tag, outermost first. Nested elements of the same tag are not expected.
    static func elements(_ tag: String, in html: String) -> [Element] {
        guard let pattern = try? Regex<(Substring, Substring, Substring)>("(?si)<\(tag)((?:\\s[^>]*)?)>(.*?)</\(tag)\\s*>") else {
            return []
        }
        return html.matches(of: pattern).map { match in
            Element(attributes: attributes(String(match.output.1)), inner: String(match.output.2))
        }
    }

    static func attributes(_ text: String) -> [String: String] {
        var result: [String: String] = [:]
        let pattern = #/(?s)([A-Za-z_:][-A-Za-z0-9_:.]*)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>]+)))?/#
        for match in text.matches(of: pattern) {
            let value = match.output.2 ?? match.output.3 ?? match.output.4 ?? ""
            result[String(match.output.1).lowercased()] = decodeEntities(String(value))
        }
        return result
    }

    /// The page without `<!-- … -->` comments, which may quote markup or stale links.
    static func withoutComments(_ html: String) -> String {
        html.replacing(#/(?s)<!--.*?-->/#, with: "")
    }

    /// The visible text: tags dropped, entities decoded, whitespace collapsed.
    static func text(_ html: String) -> String {
        let withoutTags = html.replacing(#/<[^>]*>/#, with: " ")
        let collapsed = decodeEntities(withoutTags)
            .split(whereSeparator: { $0.isWhitespace })
            .joined(separator: " ")
        // Closing punctuation should not float after a dropped tag.
        return collapsed
            .replacingOccurrences(of: " ,", with: ",")
            .replacingOccurrences(of: " .", with: ".")
            .replacingOccurrences(of: " )", with: ")")
            .replacingOccurrences(of: "( ", with: "(")
    }

    static func decodeEntities(_ text: String) -> String {
        guard text.contains("&") else { return text }
        let named = ["amp": "&", "lt": "<", "gt": ">", "quot": "\"", "apos": "'", "nbsp": " ", "rsquo": "’", "lsquo": "‘", "ldquo": "“", "rdquo": "”", "mdash": "—", "ndash": "–", "hellip": "…", "minus": "−"]
        return text.replacing(#/&(#[xX][0-9A-Fa-f]+|#[0-9]+|[A-Za-z]+);/#) { match -> String in
            let body = String(match.output.1)
            if body.hasPrefix("#x") || body.hasPrefix("#X") {
                return UInt32(body.dropFirst(2), radix: 16).flatMap(Unicode.Scalar.init).map { String(Character($0)) } ?? String(match.output.0)
            }
            if body.hasPrefix("#") {
                return UInt32(body.dropFirst()).flatMap(Unicode.Scalar.init).map { String(Character($0)) } ?? String(match.output.0)
            }
            return named[body.lowercased()] ?? String(match.output.0)
        }
    }
}
