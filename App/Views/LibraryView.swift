import FM1Kit
import SwiftUI
import UniformTypeIdentifiers

/// Firmwares saved on the phone for offline flashing, the official firmware, and the cache.
struct LibraryView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.phosphor) private var theme
    @State private var importing = false
    @State private var officialLink = ""
    @State private var message: String?
    @State private var busy = false
    @State private var confirmingClear = false
    @State private var flashing: StoredFirmware?

    private var library: FirmwareLibrary { model.library }

    /// Saved catalogue firmwares that have a newer release in the list.
    private var updatable: [CatalogueEntry] {
        model.catalogue.entries.filter { library.outdated(for: $0) != nil }
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                ScreenHeader(title: "SAVED")
                officialPanel
                savedPanel
                storagePanel
                if let message {
                    MonoText(message, size: 14)
                }
            }
            .padding(16)
        }
        .scrollIndicators(.hidden)
        .fileImporter(isPresented: $importing, allowedContentTypes: [UTType(filenameExtension: "fwsc") ?? .data, .data]) { result in
            switch result {
            case .success(let url):
                do {
                    let item = try library.importFile(at: url)
                    message = item.source == .official ? "Official firmware verified and saved." : "Saved \(item.name) (\(item.product)). It isn't in the catalogue, so check where it came from."
                } catch {
                    message = error.localizedDescription
                }
            case .failure(let error):
                message = error.localizedDescription
            }
        }
        .sheet(item: $flashing) { item in
            FlashConfirmView(target: item, requiresOfficial: item.catalogueID.flatMap(model.catalogue.entry(id:))?.requiresOfficialFirmware ?? false)
                .environment(model)
                .environment(\.phosphor, theme)
                .presentationDetents([.medium, .large])
                .presentationBackground(theme.background)
        }
        .confirmationDialog("Clear saved firmwares?", isPresented: $confirmingClear, titleVisibility: .visible) {
            Button("Clear all except official", role: .destructive) { library.clear(includingOfficial: false) }
            Button("Clear everything", role: .destructive) { library.clear(includingOfficial: true) }
        } message: {
            Text("They can be downloaded again while online.")
        }
    }

    private var officialPanel: some View {
        PixelPanel(title: "OFFICIAL FIRMWARE") {
            if let official = library.official {
                HStack {
                    MonoText("✓ \(official.name)", size: 17)
                    Spacer()
                    PixelTag(text: official.product)
                }
                MonoText("Verified against the known checksum. Kept when you clear the cache.", size: 13, dim: true)
                Button("FLASH OFFICIAL") { flashing = official }
                    .buttonStyle(.pixel)
            } else {
                MonoText("Not saved. Download FM-1 V15 (FM-1.fwsc) straight from M-VAVE's site, or import a copy you already have. Either way it's checked against the known checksum.", size: 14)
                Button(busy ? "DOWNLOADING…" : "GET FROM M-VAVE") { Task { await fetchOfficialFromMVAVE() } }
                    .buttonStyle(.pixelProminent)
                    .disabled(busy)
                Button("IMPORT FROM FILES") { importing = true }
                    .buttonStyle(.pixel)
                HStack(spacing: 8) {
                    TextField("", text: $officialLink, prompt: Text("OR PASTE A DOWNLOAD LINK").foregroundStyle(theme.muted))
                        .font(.mono(14))
                        .foregroundStyle(theme.ink)
                        .tint(theme.bright)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .keyboardType(.URL)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 8)
                        .background(PixelBox(step: 2).fill(theme.background))
                        .overlay(PixelBox(step: 2).strokeBorder(theme.dim, lineWidth: 2))
                    Button(busy ? "…" : "GET") { Task { await fetchOfficial() } }
                        .buttonStyle(.pixelCompact)
                        .disabled(busy || URL(string: officialLink)?.scheme == nil)
                }
            }
        }
    }

    private var savedPanel: some View {
        PixelPanel(title: "ON THIS PHONE") {
            let items = library.sortedItems.filter { $0.source != .official }
            if items.isEmpty {
                MonoText("Nothing saved. Save firmwares from the FIRMWARE list to flash them offline.", size: 14, dim: true)
            }
            ForEach(items) { item in
                HStack(alignment: .center, spacing: 10) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(item.name.uppercased())
                            .font(.pixel(12, bold: true, relativeTo: .headline))
                            .foregroundStyle(theme.bright)
                            .phosphorGlow(0.7)
                        MonoText([item.version, item.build.map { "\($0) build" }, item.product, formattedBytes(item.size)].compactMap { $0 }.joined(separator: " · "), size: 17, dim: true)
                    }
                    Spacer()
                    Button("FLASH") { flashing = item }
                        .buttonStyle(.pixelCompact)
                    Button("✕") { library.remove(item) }
                        .buttonStyle(.pixelCompact)
                        .accessibilityLabel("Remove \(item.name)")
                }
                .padding(.vertical, 4)
            }
            Button("IMPORT .FWSC FROM FILES") { importing = true }
                .buttonStyle(.pixel)
        }
    }

    private var storagePanel: some View {
        PixelPanel(title: "STORAGE") {
            MonoText("\(library.items.count) FILES · \(formattedBytes(library.totalBytes))", size: 15)
            if !updatable.isEmpty {
                MonoText("\(updatable.count) saved firmware\(updatable.count == 1 ? " has" : "s have") a newer release.", size: 13)
                Button(busy ? "UPDATING…" : "DOWNLOAD UPDATES") { Task { await downloadUpdates() } }
                    .buttonStyle(.pixel)
                    .disabled(busy)
            }
            Button("CLEAR CACHE") { confirmingClear = true }
                .buttonStyle(.pixel)
                .disabled(library.items.isEmpty)
        }
    }

    private func fetchOfficial() async {
        guard let url = URL(string: officialLink) else { return }
        busy = true
        defer { busy = false }
        do {
            try await library.importOfficial(from: url)
            message = "Official firmware verified and saved."
            officialLink = ""
        } catch {
            message = error.localizedDescription
        }
    }

    private func fetchOfficialFromMVAVE() async {
        busy = true
        defer { busy = false }
        do {
            try await library.downloadOfficialFromMVAVE()
            message = "Official firmware downloaded from M-VAVE, verified and saved."
        } catch {
            message = error.localizedDescription
        }
    }

    private func downloadUpdates() async {
        busy = true
        defer { busy = false }
        var failures = 0
        for entry in updatable {
            do {
                try await library.download(entry)
            } catch {
                failures += 1
            }
        }
        message = failures == 0 ? "Saved firmwares are up to date." : "\(failures) update\(failures == 1 ? "" : "s") failed. Refresh the list and try again."
    }
}
