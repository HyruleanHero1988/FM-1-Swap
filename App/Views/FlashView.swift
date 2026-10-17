import FM1Kit
import SwiftUI

/// Full-screen progress for a flash.
///
/// Navigation is blocked until it ends.
struct FlashView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.phosphor) private var theme
    @State private var restoringVoices = false

    var body: some View {
        if let job = model.device.job {
            VStack(alignment: .leading, spacing: 18) {
                ScreenHeader(title: headerTitle(job)) {
                    if job.steps.count > 1 {
                        Text("STEP \(job.index + 1)/\(job.steps.count)")
                            .font(.pixel(10, bold: true, relativeTo: .caption))
                            .foregroundStyle(theme.dim)
                    }
                }
                firmwareTitle(job)
                progress(job)
                outcome(job)
                logPanel
                Spacer(minLength: 0)
            }
            .padding(20)
            .frame(maxWidth: WideLayout.readableWidth)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
            .background(theme.background.ignoresSafeArea())
            .accessibilityAddTraits(.isModal)
            .voiceRestoreSheet(isPresented: $restoringVoices, model: model, theme: theme)
        }
    }

    private func firmwareTitle(_ job: DeviceController.FlashJob) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(job.current.name.uppercased())
                .font(.pixel(22, bold: true, relativeTo: .title))
                .foregroundStyle(theme.bright)
                .phosphorGlow()
            MonoText("\(job.current.version) → \(job.current.product)", size: 15, dim: true)
        }
    }

    private func progress(_ job: DeviceController.FlashJob) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(job.phase.label)
                    .font(.pixel(13, bold: true, relativeTo: .headline))
                    .foregroundStyle(theme.bright)
                if job.isRunning { BlinkingCursor() }
                Spacer()
                if let fraction = job.phaseFraction {
                    Text("\(Int(fraction * 100))%")
                        .font(.lcd(30))
                        .foregroundStyle(theme.bright)
                }
            }
            PixelProgressBar(fraction: job.phaseFraction)
        }
    }

    private var logPanel: some View {
        PixelPanel(title: "LOG") {
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: 2) {
                        ForEach(Array(model.device.log.enumerated()), id: \.offset) { index, line in
                            MonoText(line, size: 12, dim: true)
                                .id(index)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .frame(maxHeight: 160)
                .onChange(of: model.device.log.count) { _, count in
                    proxy.scrollTo(count - 1, anchor: .bottom)
                }
            }
        }
    }

    @ViewBuilder
    private func outcome(_ job: DeviceController.FlashJob) -> some View {
        switch job.phase {
        case .succeeded(let reported):
            PixelPanel(title: "DONE") {
                MonoText("The FM-1 now reports \(reported).", size: 15)
                if reported == FM1Identity.officialV15.text {
                    NoteText(
                        """
                        V15 can keep the old firmware's voices in some banks, some of them wrong or \
                        silent. Restoring writes its 128 factory voices over all four banks.
                        """
                    )
                    Button("RESTORE FACTORY VOICES") { restoringVoices = true }
                        .buttonStyle(.pixel)
                }
                Button("CLOSE") { model.device.dismissFlash() }
                    .buttonStyle(.pixelProminent)
            }
        case .failed(let message, let canResume):
            PixelPanel(title: "STOPPED") {
                MonoText(message, size: 15)
                if canResume {
                    Button("RESUME") { model.device.resume() }
                        .buttonStyle(.pixelProminent)
                } else {
                    Button("TRY AGAIN") { model.device.resume() }
                        .buttonStyle(.pixel)
                }
                Button("CLOSE") { model.device.dismissFlash() }
                    .buttonStyle(.pixel)
            }
        default:
            PixelPanel(title: "KEEP THIS SCREEN OPEN") {
                MonoText(
                    keepOpenMessage,
                    size: 14
                )
                NoteText(
                    """
                    An install takes from about 35 seconds to a minute, depending on the firmware being \
                    replaced. If it is interrupted, the FM-1 waits in update mode and RESUME carries on.
                    """
                )
            }
        }
    }

    private func headerTitle(_ job: DeviceController.FlashJob) -> String {
        switch job.phase {
        case .succeeded: "FLASHED"
        case .failed: "INTERRUPTED"
        default: "FLASHING"
        }
    }

    private var keepOpenMessage: String {
        #if os(macOS)
            "Sleep is paused. Don't quit FM-SWITCH or unplug the FM-1 until this finishes."
        #else
            """
            Auto-lock is paused. Don't lock the \(ThisDevice.name), switch apps or unplug the FM-1 until \
            this finishes.
            """
        #endif
    }
}
