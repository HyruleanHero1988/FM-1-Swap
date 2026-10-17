import FM1Kit
import SwiftUI

/// Asks before restoring V15's factory voices, then shows the restore through to its end.
struct VoiceRestoreView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.phosphor) private var theme
    @State private var understood = false

    private var device: DeviceController { model.device }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            ScreenHeader(title: "FACTORY VOICES")
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    switch device.voiceRestore {
                    case .idle:
                        warning
                    case .writing(let fraction):
                        progress(fraction)
                    case .succeeded:
                        MonoText("Done: the FM-1's four voice banks now hold V15's 128 factory voices.", size: 15)
                    case .failed(let reason):
                        MonoText(reason, size: 15)
                    }
                }
            }
            .scrollIndicators(.hidden)
            buttons
        }
        .padding(20)
        .overlay { CRTOverlay() }
        .interactiveDismissDisabled(device.voiceRestore.isRunning)
        .onDisappear { device.dismissVoiceRestore() }
    }

    @ViewBuilder private var warning: some View {
        MonoText(
            """
            This erases the FM-1's four voice banks and writes V15's 128 factory voices in their place. \
            Every voice in them is replaced for good, including any you made, and V15 can't read them \
            back first.
            """,
            size: 14
        )
        NoteText(
            """
            Worth doing after installing V15 over another firmware, which can leave old voices behind, \
            some of them wrong or silent. Keep the cable connected until it finishes; if it's \
            interrupted, some voices may be wrong or silent until you run it again.
            """
        )
        if model.device.identity != .officialV15 {
            MonoText("The FM-1 needs to be running V15 (FM-1_015) for this.", size: 14)
        }
        PixelToggle("REPLACE EVERY VOICE", isOn: $understood)
    }

    private func progress(_ fraction: Double) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                MonoText("WRITING VOICES", size: 14)
                BlinkingCursor()
                Spacer()
                Text("\(Int(fraction * 100))%")
                    .font(.lcd(30))
                    .foregroundStyle(theme.bright)
            }
            PixelProgressBar(fraction: fraction)
            NoteText("Keep the cable connected. It takes about a second.")
        }
    }

    @ViewBuilder private var buttons: some View {
        HStack(spacing: 10) {
            switch device.voiceRestore {
            case .idle:
                Button("CANCEL") { dismiss() }
                    .buttonStyle(.pixel)
                Button("RESTORE") { Task { await device.restoreFactoryVoices() } }
                    .buttonStyle(.pixelProminent)
                    .disabled(!understood || device.identity != .officialV15 || device.isWriting)
            case .writing:
                EmptyView()
            case .succeeded:
                Button("CLOSE") { dismiss() }
                    .buttonStyle(.pixelProminent)
            case .failed:
                Button("CLOSE") { dismiss() }
                    .buttonStyle(.pixel)
                Button("RUN AGAIN") { Task { await device.restoreFactoryVoices() } }
                    .buttonStyle(.pixelProminent)
                    .disabled(device.isWriting)
            }
        }
    }
}

extension View {
    /// Presents ``VoiceRestoreView`` while `isPresented` is set.
    func voiceRestoreSheet(isPresented: Binding<Bool>, model: AppModel, theme: PhosphorTheme) -> some View {
        sheet(isPresented: isPresented) {
            VoiceRestoreView()
                .environment(model)
                .environment(\.phosphor, theme)
                .pixelSheetSize()
                .presentationBackground(theme.background)
        }
    }
}
