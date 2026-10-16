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
                header
                sourceCheck

                if let what = entry.what {
                    MonoText(what, size: 15)
                }

                builds

                actions

                if let errorMessage {
                    MonoText(errorMessage, size: 14)
                }

                details

                FirmwareLinksPanel(entry: entry)
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

    private var header: some View {
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
                MonoText(byline, size: 15, dim: true)
            }
        }
    }

    /// The version and author, e.g. "1.3.7  ·  by Melodee".
    private var byline: String {
        [entry.version, entry.author.map { "by \($0)" }]
            .compactMap(\.self)
            .joined(separator: "  ·  ")
    }

    private var details: some View {
        PixelPanel(title: "DETAILS") {
            detailRow("REPORTS", entry.product ?? stored?.product ?? "Known once saved")
            if let size = entry.size ?? stored?.size { detailRow("SIZE", formattedBytes(size)) }
            if let date = entry.date { detailRow("RELEASED", date) }
            if !entry.kinds.isEmpty { detailRow("KIND", entry.kinds.joined(separator: ", ")) }
            detailRow("LISTED ON", listedOn)
            detailRow("FILE FROM", packageSource)
            detailRow("SHA-256", entry.sha256.map { String($0.prefix(16)) + "…" } ?? "Not published")
            if entry.isDownloadable && !entry.hasPublishedChecksum {
                NoteText(
                    """
                    No checksum is published for this file, so it's only checked to be an FM-1 firmware \
                    package before saving.
                    """
                )
            }
            if entry.requiresOfficialFirmware {
                MonoText("Installs from the official firmware: the official firmware is flashed first.", size: 13)
            }
        }
    }

    private var listedOn: String {
        [entry.listing.contains(.hub) ? "Firmware hub" : nil, entry.listing.contains(.editor) ? "fm1-editor.com" : nil]
            .compactMap(\.self)
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
                NoteText("Checking \(source.rawValue) for a newer release…")
            case .newer(let newer):
                VStack(alignment: .leading, spacing: 6) {
                    PixelTag(text: "NEWER THAN LISTED")
                    MonoText(
                        """
                        \(newer.version ?? "A newer release") is out on \(source.rawValue); the firmware lists \
                        still show \(listedVersion). Saving gets the newer one.
                        """,
                        size: 14
                    )
                }
            case .current:
                let checked = check?.checkedAt.formatted(date: .omitted, time: .shortened) ?? "now"
                NoteText("Latest on \(source.rawValue) (checked \(checked)).")
            case .failed:
                NoteText("Couldn't reach \(source.rawValue) to check for a newer release, so this is the listed one.")
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
                    NoteText("Replaces the saved \(other.build.map { "\($0) build" } ?? "copy").")
                } else {
                    NoteText("Save it first, then flash it any time, even offline.")
                }
            } else {
                MonoText(
                    """
                    No downloadable file was found for this firmware. Get it from the links below; if you \
                    download a .fwsc, import it under SAVED.
                    """,
                    size: 14
                )
            }
        }
    }

    private var saveTitle: String {
        if isDownloading { return "DOWNLOADING…" }
        if entry.needsBuildChoice { return "PICK A BUILD FIRST" }
        if model.library.outdated(for: entry) != nil { return "UPDATE SAVED COPY" }
        if let build = entry.build, model.library.otherBuild(for: entry) != nil {
            return "SAVE \(build.uppercased()) BUILD"
        }
        return "SAVE TO PHONE"
    }

    /// The release's builds, when it comes in several (e.g. standard and single-core).
    @ViewBuilder
    private var builds: some View {
        if let builds = entry.builds, builds.count > 1 {
            let listedBuild = model.catalogue.defaultBuild(of: model.catalogue.listedEntry(id: listed.id) ?? listed)
                .build
            PixelPanel(title: "BUILD") {
                ForEach(builds, id: \.name) { build in
                    buildButton(build.name, isListed: build.name == listedBuild)
                }
                if let listedBuild {
                    NoteText(
                        """
                        This release comes in \(builds.count) builds. The firmware lists give the \(listedBuild) \
                        build; pick another only if its release notes say it suits you.
                        """
                    )
                } else {
                    MonoText(
                        """
                        This release comes in \(builds.count) builds, and it isn't clear which one the firmware \
                        lists give. Check the release notes, then pick one.
                        """,
                        size: 13
                    )
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

    private func buildButton(_ name: String, isListed: Bool) -> some View {
        let isChosen = name == entry.build
        return Button {
            model.catalogue.choose(build: name, for: listed.id)
        } label: {
            HStack {
                Text(isChosen ? "◆" : "◇")
                Text(name.uppercased())
                Spacer()
                if isListed {
                    Text("LISTED").font(.pixel(9, relativeTo: .caption2))
                }
            }
            .frame(maxWidth: .infinity)
        }
        .buttonStyle(isChosen ? .pixelProminent : .pixel)
        .accessibilityAddTraits(isChosen ? .isSelected : [])
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
