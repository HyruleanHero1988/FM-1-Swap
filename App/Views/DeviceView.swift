import FM1Kit
import SwiftUI

/// The Phase 0 screen: is an FM-1 there, what does it say it runs, and what might that be.
struct DeviceView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.phosphor) private var theme
    @Binding var tab: AppTab
    @State private var restoringVoices = false

    private var device: DeviceController { model.device }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                ScreenHeader(title: "DEVICE") {
                    Button(device.isScanning ? "SCANNING" : "SCAN") {
                        Task { await device.scan() }
                    }
                    .buttonStyle(.pixelCompact)
                    .disabled(device.isScanning)
                }

                AdaptiveColumns {
                    identityPanel

                    if let identity = device.identity {
                        if identity.isLoader {
                            loaderPanel(identity)
                        } else {
                            installedPanel(identity)
                        }
                    } else {
                        hintPanel
                    }
                } trailing: {
                    if device.identity == .officialV15 { voicesPanel }
                    portsPanel
                }
            }
            .padding(16)
        }
        .scrollIndicators(.hidden)
        .voiceRestoreSheet(isPresented: $restoringVoices, model: model, theme: theme)
    }

    private var voicesPanel: some View {
        PixelPanel(title: "FACTORY VOICES") {
            MonoText(
                """
                After V15 goes on over another firmware, some voice banks can still hold the old \
                firmware's voices. Restoring writes V15's 128 factory voices over all four banks.
                """,
                size: 14
            )
            Button("RESTORE FACTORY VOICES") { restoringVoices = true }
                .buttonStyle(.pixel)
                .disabled(device.isWriting)
        }
    }

    private var identityPanel: some View {
        PixelPanel(title: "REPORTS") {
            HStack(alignment: .firstTextBaseline) {
                Text(device.identity?.text ?? (device.isScanning ? "LISTENING" : "----"))
                    .font(.lcd(60))
                    .foregroundStyle(theme.bright)
                    .phosphorGlow()
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                if device.isScanning || device.identity == nil {
                    BlinkingCursor()
                }
            }
            if let identity = device.identity {
                MonoText(identity.isLoader ? "UPDATE LOADER IS RUNNING" : "FIRMWARE IS RUNNING", size: 14, dim: true)
            }
            if let error = device.transportError {
                MonoText(error, size: 13)
            }
        }
    }

    private func installedPanel(_ identity: FM1Identity) -> some View {
        let matches = model.catalogue.entries(matching: identity.text)
        let official = model.library.official.flatMap { $0.product == identity.text ? $0 : nil }
        let last = model.history.lastInstall(matching: identity.text)
        return PixelPanel(title: "PROBABLY") {
            if let last {
                MonoText("LAST FLASHED FROM THIS \(ThisDevice.name.uppercased()):", size: 13, dim: true)
                MonoText(
                    "\(last.name) \(last.version)  ·  \(last.date.formatted(date: .abbreviated, time: .shortened))",
                    size: 15)
            }
            if let official {
                MonoText("Matches your official firmware: \(official.name)", size: 14)
            }
            if !matches.isEmpty {
                NoteText(
                    matches.count == 1 ? "CATALOGUE MATCH:" : "\(matches.count) CATALOGUE FIRMWARES REPORT THIS ID:")
                ForEach(matches) { entry in
                    MonoText("· \(entry.name) \(entry.version ?? "")", size: 15)
                }
            }
            if OpenFirmware.isOpen(identity.text) {
                MonoText("M-VAVE's open-source firmware (FM1 Open), or a build of it.", size: 14)
            }
            if last == nil && official == nil && matches.isEmpty && !OpenFirmware.isOpen(identity.text) {
                MonoText(
                    "No saved or catalogue firmware reports this ID. It may be official firmware or an older release.",
                    size: 14,
                    dim: true
                )
            }
            if matches.count > 1 && last == nil {
                NoteText("Several firmwares share this ID, so the device can't say which one it is.")
            }
        }
    }

    private func loaderPanel(_ loader: FM1Identity) -> some View {
        let finishers = model.library.packages(thatCanFinish: loader)
        return PixelPanel(title: "UPDATE MODE") {
            MonoText(
                """
                The FM-1 is waiting in its update loader: an install was interrupted, or it was switched on \
                holding OCT− (USB recovery mode, which some firmwares offer).
                """,
                size: 14
            )
            if finishers.isEmpty {
                MonoText(
                    """
                    None of your saved firmwares brought this loader. Flash the firmware you were \
                    installing (save it first if it isn't), as only its own package can finish.
                    """,
                    size: 14,
                    dim: true
                )
            } else {
                NoteText(finishers.count == 1 ? "THIS ONE CAN FINISH IT:" : "ANY OF THESE CAN FINISH IT:")
                ForEach(finishers) { item in
                    MonoText("· \(item.name) \(item.version)", size: 15)
                }
            }
            Button("CHOOSE FIRMWARE") { tab = .library }
                .buttonStyle(.pixelProminent)
        }
    }

    private var hintPanel: some View {
        PixelPanel(title: "CONNECT") {
            MonoText("1. Switch the FM-1 on.", size: 15)
            MonoText("2. Plug it into this \(ThisDevice.name)'s USB port with a data cable.", size: 15)
            MonoText("3. Close any other MIDI app using it.", size: 15)
            if model.settings.transportKind == .simulated {
                MonoText("Using the simulated FM-1 (change in SETUP).", size: 13, dim: true)
            }
        }
    }

    private var portsPanel: some View {
        PixelPanel(title: "MIDI PORTS") {
            if device.ports.isEmpty {
                MonoText("None found.", size: 14, dim: true)
            }
            ForEach(device.ports) { port in
                HStack {
                    MonoText(port.name, size: 14)
                    Spacer()
                    PixelTag(
                        text: FM1Updater.isProbeable(port) ? "PROBED" : "IGNORED",
                        inverted: FM1Updater.isProbeable(port))
                }
            }
            if let lastScan = device.lastScan {
                MonoText("Last scan \(lastScan.formatted(date: .omitted, time: .standard))", size: 16, dim: true)
            }
        }
    }
}
