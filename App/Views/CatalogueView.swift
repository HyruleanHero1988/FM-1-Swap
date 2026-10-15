import FM1Kit
import SwiftUI

/// The firmware list from the hub, with a refresh button and per-firmware status.
struct CatalogueView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.phosphor) private var theme
    @State private var search = ""
    @State private var selected: CatalogueEntry?

    private var visibleEntries: [CatalogueEntry] {
        matchingEntries.filter { model.settings.showLinksOnly || isFlashable($0) }
    }

    /// Entries passing the beta and search filters, before links-only ones are hidden.
    private var matchingEntries: [CatalogueEntry] {
        model.catalogue.entries.filter { entry in
            (model.settings.showBeta || !entry.beta)
                && (search.isEmpty
                    || entry.name.localizedCaseInsensitiveContains(search)
                    || (entry.what ?? "").localizedCaseInsensitiveContains(search)
                    || (entry.author ?? "").localizedCaseInsensitiveContains(search)
                    || entry.kinds.contains { $0.localizedCaseInsensitiveContains(search) })
        }
    }

    /// Downloadable, or already saved on the phone (a saved copy can be flashed even if its
    /// download has since disappeared).
    private func isFlashable(_ entry: CatalogueEntry) -> Bool {
        entry.isDownloadable || model.library.items.contains { $0.catalogueID == entry.id }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            ScreenHeader(title: "FIRMWARE") {
                Button(model.catalogue.isRefreshing ? "LOADING" : "REFRESH") {
                    Task { await model.catalogue.refresh() }
                }
                .buttonStyle(.pixelCompact)
                .disabled(model.catalogue.isRefreshing)
            }

            HStack(spacing: 8) {
                Text(">").font(.mono(17)).foregroundStyle(theme.bright)
                TextField("", text: $search, prompt: Text("SEARCH").foregroundStyle(theme.muted))
                    .font(.mono(15))
                    .foregroundStyle(theme.ink)
                    .tint(theme.bright)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 9)
            .background(PixelBox().fill(theme.surface))
            .overlay(PixelBox().strokeBorder(theme.dim, lineWidth: 2))

            statusLine

            ScrollView {
                LazyVStack(spacing: 8) {
                    ForEach(visibleEntries) { entry in
                        Button {
                            selected = entry
                        } label: {
                            CatalogueRow(entry: entry)
                        }
                        .buttonStyle(.plain)
                    }
                    linksOnlyFooter
                }
                .padding(.bottom, 16)
            }
            .scrollIndicators(.hidden)
            .refreshable { await model.catalogue.refresh() }
        }
        .padding(.horizontal, 16)
        .padding(.top, 16)
        .sheet(item: $selected) { entry in
            FirmwareDetailView(entry: entry)
                .environment(model)
                .environment(\.phosphor, theme)
                .presentationBackground(theme.background)
        }
        .task {
            if model.catalogue.catalogue == nil {
                await model.catalogue.refresh()
            }
            #if DEBUG
            // `-openFirmware <id>` opens a firmware's page at launch, for screenshots.
            if let id = UserDefaults.standard.string(forKey: "openFirmware") {
                selected = model.catalogue.entry(id: id)
            }
            #endif
        }
    }

    /// Says how many links-only firmwares are hidden, with a switch to show or hide them.
    @ViewBuilder
    private var linksOnlyFooter: some View {
        let linksOnly = matchingEntries.filter { !isFlashable($0) }.count
        if linksOnly > 0 {
            VStack(alignment: .leading, spacing: 8) {
                MonoText(
                    model.settings.showLinksOnly
                        ? "Showing \(linksOnly) links-only firmware\(linksOnly == 1 ? "" : "s") (no file to download)."
                        : "\(linksOnly) more firmware\(linksOnly == 1 ? " has" : "s have") no file to download, only links to \(linksOnly == 1 ? "its" : "their") sites.",
                    size: 13,
                    dim: true
                )
                Button(model.settings.showLinksOnly ? "HIDE LINKS-ONLY" : "SHOW LINKS-ONLY (\(linksOnly))") {
                    model.settings.showLinksOnly.toggle()
                }
                .buttonStyle(.pixelCompact)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 8)
        }
    }

    @ViewBuilder
    private var statusLine: some View {
        let count = visibleEntries.count
        VStack(alignment: .leading, spacing: 2) {
            if let catalogue = model.catalogue.catalogue {
                MonoText("\(count) FIRMWARES · CHECKED \(catalogue.builtAt.formatted(date: .abbreviated, time: .shortened).uppercased())", size: 12, dim: true)
                ForEach(catalogue.problems, id: \.self) { problem in
                    MonoText(problem, size: 12)
                }
            }
            if let error = model.catalogue.errorMessage {
                MonoText(error, size: 12)
            } else if model.catalogue.catalogue == nil && !model.catalogue.isRefreshing {
                MonoText("No list saved yet. Tap REFRESH while online.", size: 12)
            }
        }
    }
}

private struct CatalogueRow: View {
    @Environment(AppModel.self) private var model
    @Environment(\.phosphor) private var theme
    var entry: CatalogueEntry

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                Text(entry.name.uppercased())
                    .font(.pixel(14, bold: true, relativeTo: .headline))
                    .foregroundStyle(theme.bright)
                    .phosphorGlow(0.6)
                Spacer()
                Text(entry.version ?? "")
                    .font(.mono(13))
                    .foregroundStyle(theme.muted)
                    .lineLimit(1)
            }
            if let what = entry.what {
                Text(what)
                    .font(.mono(13))
                    .lineSpacing(3)
                    .foregroundStyle(theme.ink)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
            }
            HStack(spacing: 6) {
                if entry.isOfficial { PixelTag(text: "OFFICIAL") }
                if model.library.stored(for: entry) != nil || model.library.otherBuild(for: entry) != nil {
                    PixelTag(text: "SAVED")
                } else if model.library.outdated(for: entry) != nil {
                    PixelTag(text: "UPDATE")
                }
                if !entry.isDownloadable { PixelTag(text: "LINKS ONLY", inverted: false) }
                if entry.beta { PixelTag(text: "BETA", inverted: false) }
                if entry.requiresOfficialFirmware { PixelTag(text: "VIA OFFICIAL", inverted: false) }
                if model.device.identity?.text == entry.product { PixelTag(text: "ID MATCH", inverted: false) }
                Spacer()
                if let author = entry.author {
                    Text(author.uppercased())
                        .font(.pixel(8, relativeTo: .caption2))
                        .foregroundStyle(theme.muted)
                        .lineLimit(1)
                }
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(PixelBox().fill(theme.surface))
        .overlay(PixelBox().strokeBorder(theme.faint, lineWidth: 2))
        .contentShape(Rectangle())
    }
}
