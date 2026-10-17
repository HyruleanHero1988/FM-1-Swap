import SwiftUI

/// A firmware's links: the project site, source code and manual, with every other link folded away
/// under MORE LINKS.
struct FirmwareLinksPanel: View {
    @Environment(\.phosphor) private var theme
    @State private var showsMoreLinks = false
    let entry: CatalogueEntry

    var body: some View {
        if !entry.links.isEmpty {
            let more = entry.moreLinks
            PixelPanel(title: "LINKS") {
                linkRows(entry.mainLinks)
                if !more.isEmpty {
                    if !entry.mainLinks.isEmpty {
                        Rectangle().fill(theme.faint).frame(height: 1)
                    }
                    Button {
                        showsMoreLinks.toggle()
                    } label: {
                        Text("\(showsMoreLinks ? "▾" : "▸") MORE LINKS (\(more.count))")
                            .font(.pixel(11, bold: true, relativeTo: .callout))
                            .foregroundStyle(theme.dim)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("More links")
                    .accessibilityValue(showsMoreLinks ? "Shown" : "Hidden")
                    if showsMoreLinks {
                        linkRows(more)
                    }
                }
            }
        }
    }

    /// One row per link: its label, then the whole address, which scrolls sideways when long.
    private func linkRows(_ links: [CatalogueLink]) -> some View {
        ForEach(Array(links.enumerated()), id: \.offset) { index, link in
            if index > 0 {
                Rectangle().fill(theme.faint).frame(height: 1)
            }
            Link(destination: link.url) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("▸ \(link.displayLabel.uppercased())")
                        .font(.pixel(11, bold: true, relativeTo: .callout))
                        .foregroundStyle(theme.bright)
                    ScrollView(.horizontal) {
                        MonoText(link.url.absoluteString, size: 13, dim: true)
                            .fixedSize()
                    }
                    .scrollIndicators(.hidden)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .accessibilityLabel(link.displayLabel)
            .accessibilityHint(link.url.absoluteString)
            .contextMenu {
                Button("Copy link") { Pasteboard.copy(link.url) }
            }
        }
    }
}
