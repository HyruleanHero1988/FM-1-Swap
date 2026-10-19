import FM1Kit
import SwiftUI

/// The firmware list from the hub, with a refresh button and per-firmware status.
struct CatalogueView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.phosphor) private var theme
    @Environment(\.isWideLayout) private var isWide
    @State private var search = ""
    @State private var selected: CatalogueEntry?
    @FocusState private var isSearchFocused: Bool

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
        Group {
            if isWide {
                ListAndDetail(placeholder: "Pick a firmware from the list.", hasSelection: selected != nil) {
                    listColumn
                } detail: {
                    if let selected {
                        FirmwareDetailView(entry: selected, isInPane: true)
                            .id(selected.id)
                    }
                }
            } else {
                listColumn
            }
        }
        // In the wide layout the selected firmware shows beside the list instead.
        .sheet(item: isWide ? .constant(nil) : $selected) { entry in
            FirmwareDetailView(entry: entry)
                .environment(model)
                .environment(\.phosphor, theme)
                .pixelSheetSize(isTall: true)
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

    private var listColumn: some View {
        VStack(alignment: .leading, spacing: 12) {
            ScreenHeader(title: "FIRMWARE") {
                Button(model.catalogue.isRefreshing ? "LOADING" : "REFRESH") {
                    Task { await model.catalogue.refresh() }
                }
                .buttonStyle(.pixelCompact)
                .disabled(model.catalogue.isRefreshing)
            }

            searchField
            statusLine
            firmwareList
        }
        .padding(.horizontal, 16)
        .padding(.top, 16)
    }

    private var searchField: some View {
        HStack(spacing: 8) {
            Text(">").font(.mono(17)).foregroundStyle(theme.bright)
            TextField("", text: $search, prompt: Text("SEARCH").foregroundStyle(theme.muted))
                .font(.mono(15))
                .foregroundStyle(theme.ink)
                .tint(theme.bright)
                .plainTextEntry()
                .focused($isSearchFocused)
                // The box shows focus instead of the system's rounded ring.
                .focusEffectDisabled()
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 9)
        .background(PixelBox().fill(theme.surface))
        .overlay(PixelBox().strokeBorder(isSearchFocused ? theme.bright : theme.dim, lineWidth: 2))
    }

    private var firmwareList: some View {
        ScrollView {
            LazyVStack(spacing: 8) {
                ForEach(visibleEntries) { entry in
                    Button {
                        selected = entry
                    } label: {
                        CatalogueRow(entry: entry, isSelected: isWide && selected?.id == entry.id)
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

    /// Says how many links-only firmwares are hidden, with a switch to show or hide them.
    @ViewBuilder
    private var linksOnlyFooter: some View {
        let linksOnly = matchingEntries.filter { !isFlashable($0) }.count
        if linksOnly > 0 {
            VStack(alignment: .leading, spacing: 8) {
                NoteText(linksOnlyMessage(count: linksOnly))
                Button(model.settings.showLinksOnly ? "HIDE LINKS-ONLY" : "SHOW LINKS-ONLY (\(linksOnly))") {
                    model.settings.showLinksOnly.toggle()
                }
                .buttonStyle(.pixelCompact)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 8)
        }
    }

    private func linksOnlyMessage(count: Int) -> String {
        if model.settings.showLinksOnly {
            return "Showing \(count) links-only firmware\(count == 1 ? "" : "s") (no file to download)."
        }
        return count == 1
            ? "1 more firmware has no file to download, only links to its site."
            : "\(count) more firmwares have no file to download, only links to their sites."
    }

    @ViewBuilder
    private var statusLine: some View {
        let count = visibleEntries.count
        VStack(alignment: .leading, spacing: 2) {
            if let catalogue = model.catalogue.catalogue {
                let checked = catalogue.builtAt.formatted(date: .abbreviated, time: .shortened).uppercased()
                MonoText("\(count) FIRMWARES · CHECKED \(checked)", size: 12, dim: true)
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
    /// Whether the row's firmware is showing beside the list.
    var isSelected = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            title
            if let what = entry.what {
                Text(what)
                    .font(.mono(13))
                    .lineSpacing(3)
                    .foregroundStyle(theme.ink)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
            }
            tags
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(PixelBox().fill(theme.surface))
        .overlay(PixelBox().strokeBorder(isSelected ? theme.bright : theme.faint, lineWidth: 2))
        .contentShape(Rectangle())
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }

    private var title: some View {
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
    }

    private var tags: some View {
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
}
