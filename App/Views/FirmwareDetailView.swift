import FM1Kit
import SwiftUI

/// One catalogue firmware: description, details, links, and download / flash.
struct FirmwareDetailView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.phosphor) private var theme
    @Environment(\.dismiss) private var dismiss
    @State private var errorMessage: String?
    @State private var confirming: StoredFirmware?
    let listed: CatalogueEntry

    init(entry: CatalogueEntry) {
        listed = entry
    }

    /// The listed release, or a newer one found at the firmware's source.
    private var entry: CatalogueEntry { model.catalogue.entry(id: listed.id) ?? listed }
    private var stored: StoredFirmware? { model.library.stored(for: entry) }
    private var isDownloading: Bool { model.library.downloading.contains(entry.id) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                HStack {
                    Button("◂ BACK") { dismiss() }
                        .buttonStyle(.pixelCompact)
                    Spacer()
                    if entry.isOfficial { PixelTag(text: "OFFICIAL") }
                    if entry.beta { PixelTag(text: "BETA", inverted: false) }
                }

                VStack(alignment: .leading, spacing: 6) {
                    Text(entry.name.uppercased())
                        .font(.pixel(24, bold: true, relativeTo: .largeTitle))
                        .foregroundStyle(theme.bright)
                        .phosphorGlow()
                    MonoText([entry.version, entry.author.map { "by \($0)" }].compactMap { $0 }.joined(separator: "  ·  "), size: 15, dim: true)
                }

                sourceCheck

                if let what = entry.what {
                    MonoText(what, size: 15)
                }

                builds

                actions

                if let errorMessage {
                    MonoText(errorMessage, size: 14)
                }

                PixelPanel(title: "DETAILS") {
                    detailRow("REPORTS", entry.product ?? stored?.product ?? "Known once saved")
                    if let size = entry.size ?? stored?.size { detailRow("SIZE", formattedBytes(size)) }
                    if let date = entry.date { detailRow("RELEASED", date) }
                    if !entry.kinds.isEmpty { detailRow("KIND", entry.kinds.joined(separator: ", ")) }
                    detailRow("LISTED ON", listedOn)
                    detailRow("FILE FROM", packageSource)
                    detailRow("SHA-256", entry.sha256.map { String($0.prefix(16)) + "…" } ?? "Not published")
                    if entry.isDownloadable && !entry.hasPublishedChecksum {
                        MonoText("No checksum is published for this file, so it's only checked to be an FM-1 firmware package before saving.", size: 13, dim: true)
                    }
                    if entry.requiresOfficialFirmware {
                        MonoText("Installs from the official firmware: the official firmware is flashed first.", size: 13)
                    }
                }

                links
            }
            .padding(16)
        }
        .scrollIndicators(.hidden)
        .background(theme.background.ignoresSafeArea())
        .overlay { CRTOverlay() }
        .task { await model.catalogue.checkSource(of: listed.id) }
        .sheet(item: $confirming) { item in
            FlashConfirmView(target: item, requiresOfficial: entry.requiresOfficialFirmware) {
                dismiss()
            }
            .environment(model)
            .environment(\.phosphor, theme)
            .presentationDetents([.medium, .large])
            .presentationBackground(theme.background)
        }
    }

    private var listedOn: String {
        [entry.listing.contains(.hub) ? "Firmware hub" : nil, entry.listing.contains(.editor) ? "fm1-editor.com" : nil]
            .compactMap { $0 }
            .joined(separator: " + ")
    }

    private var packageSource: String {
        switch entry.packageOrigin {
        case .hub: "Firmware hub mirror"
        case .githubRelease: "GitHub release"
        case .publisherManifest: "The publisher's site"
        case .projectPage: "The project's page"
        case .mvave: "M-VAVE's downloads"
        case nil: "None found"
        }
    }

    /// What the firmware's own source says about newer releases than the lists show.
    @ViewBuilder
    private var sourceCheck: some View {
        if let source = ReleaseCheck.source(for: model.catalogue.listedEntry(id: listed.id) ?? listed) {
            let check = model.catalogue.sourceChecks[listed.id]
            switch check?.outcome {
            case nil:
                MonoText("Checking \(source.rawValue) for a newer release…", size: 13, dim: true)
            case let .newer(newer):
                VStack(alignment: .leading, spacing: 6) {
                    PixelTag(text: "NEWER THAN LISTED")
                    MonoText("\(newer.version ?? "A newer release") is out on \(source.rawValue); the firmware lists still show \(listedVersion). Saving gets the newer one.", size: 14)
                }
            case .current:
                MonoText("Latest on \(source.rawValue) (checked \(check?.checkedAt.formatted(date: .omitted, time: .shortened) ?? "now")).", size: 13, dim: true)
            case .failed:
                MonoText("Couldn't reach \(source.rawValue) to check for a newer release, so this is the listed one.", size: 13, dim: true)
            }
        }
    }

    private var listedVersion: String {
        model.catalogue.listedEntry(id: listed.id)?.version ?? "an older release"
    }

    @ViewBuilder
    private var actions: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let stored {
                Button("FLASH TO FM-1") { confirming = stored }
                    .buttonStyle(.pixelProminent)
                Button("REMOVE FROM PHONE") { model.library.remove(stored) }
                    .buttonStyle(.pixel)
            } else if entry.isDownloadable {
                Button(saveTitle) {
                    Task { await download() }
                }
                .buttonStyle(.pixelProminent)
                .disabled(isDownloading || entry.needsBuildChoice)
                if isDownloading {
                    PixelProgressBar(fraction: nil)
                }
                if let other = model.library.otherBuild(for: entry) {
                    MonoText("Replaces the saved \(other.build.map { "\($0) build" } ?? "copy").", size: 13, dim: true)
                } else {
                    MonoText("Save it first, then flash it any time, even offline.", size: 13, dim: true)
                }
            } else {
                MonoText("No downloadable file was found for this firmware. Get it from the links below; if you download a .fwsc, import it under SAVED.", size: 14)
            }
        }
    }

    private var saveTitle: String {
        if isDownloading { return "DOWNLOADING…" }
        if entry.needsBuildChoice { return "PICK A BUILD FIRST" }
        if model.library.outdated(for: entry) != nil { return "UPDATE SAVED COPY" }
        if let build = entry.build, model.library.otherBuild(for: entry) != nil { return "SAVE \(build.uppercased()) BUILD" }
        return "SAVE TO PHONE"
    }

    /// The release's builds, when it comes in several (e.g. standard and single-core).
    @ViewBuilder
    private var builds: some View {
        if let builds = entry.builds, builds.count > 1 {
            let listedBuild = model.catalogue.defaultBuild(of: model.catalogue.listedEntry(id: listed.id) ?? listed).build
            PixelPanel(title: "BUILD") {
                ForEach(builds, id: \.name) { build in
                    let isChosen = build.name == entry.build
                    Button {
                        model.catalogue.choose(build: build.name, for: listed.id)
                    } label: {
                        HStack {
                            Text(isChosen ? "◆" : "◇")
                            Text(build.name.uppercased())
                            Spacer()
                            if build.name == listedBuild {
                                Text("LISTED").font(.pixel(9, relativeTo: .caption2))
                            }
                        }
                        .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(isChosen ? .pixelProminent : .pixel)
                    .accessibilityAddTraits(isChosen ? .isSelected : [])
                }
                if let listedBuild {
                    MonoText("This release comes in \(builds.count) builds. The firmware lists give the \(listedBuild) build; pick another only if its release notes say it suits you.", size: 13, dim: true)
                } else {
                    MonoText("This release comes in \(builds.count) builds, and it isn't clear which one the firmware lists give. Check the release notes, then pick one.", size: 13)
                }
                if let releaseURL = entry.releaseURL {
                    Link(destination: releaseURL) {
                        Text("▸ RELEASE NOTES")
                            .font(.pixel(11, bold: true, relativeTo: .callout))
                            .foregroundStyle(theme.bright)
                    }
                }
            }
        }
    }

    /// One row per link: its label, then the whole address, which scrolls sideways when long.
    @ViewBuilder
    private var links: some View {
        if !entry.links.isEmpty {
            PixelPanel(title: "LINKS") {
                ForEach(Array(entry.links.enumerated()), id: \.offset) { index, link in
                    if index > 0 {
                        Rectangle().fill(theme.faint).frame(height: 1)
                    }
                    Link(destination: link.url) {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("▸ \(link.label.uppercased())")
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
                    .accessibilityLabel(link.label)
                    .accessibilityHint(link.url.absoluteString)
                    .contextMenu {
                        Button("Copy link") { UIPasteboard.general.url = link.url }
                    }
                }
            }
        }
    }

    private func detailRow(_ label: String, _ value: String) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(label)
                .font(.pixel(10, relativeTo: .caption))
                .foregroundStyle(theme.muted)
                .frame(width: 92, alignment: .leading)
            MonoText(value, size: 14)
        }
    }

    private func download() async {
        errorMessage = nil
        do {
            try await model.library.download(entry)
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

/// Shows exactly what will be flashed (including any official-first step) before starting.
struct FlashConfirmView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.phosphor) private var theme
    @Environment(\.dismiss) private var dismiss
    let target: StoredFirmware
    var requiresOfficial = false
    var onStart: () -> Void = {}

    var body: some View {
        let steps = model.device.plan(for: target, requiresOfficial: requiresOfficial, alwaysViaOfficial: model.settings.alwaysViaOfficial)
        let missingOfficial = (requiresOfficial || model.settings.alwaysViaOfficial) && target.source != .official && model.library.official == nil
        VStack(alignment: .leading, spacing: 16) {
            ScreenHeader(title: "FLASH?")
            ForEach(Array(steps.enumerated()), id: \.offset) { index, step in
                MonoText("\(index + 1). \(step.name) \(step.version)", size: 17)
            }
            if missingOfficial {
                MonoText("This needs the official firmware first, and none is saved. Add it under SAVED.", size: 14)
            }
            if model.device.identity == nil {
                MonoText("No FM-1 is connected yet. Connect it before starting.", size: 14)
            }
            MonoText("Takes a minute or two per step. Stay in the app until it finishes.", size: 13, dim: true)
            Spacer()
            HStack(spacing: 10) {
                Button("CANCEL") { dismiss() }
                    .buttonStyle(.pixel)
                Button("START") {
                    model.device.flash(steps)
                    dismiss()
                    onStart()
                }
                .buttonStyle(.pixelProminent)
                .disabled(missingOfficial || model.device.identity == nil)
            }
        }
        .padding(20)
        .overlay { CRTOverlay() }
    }
}
