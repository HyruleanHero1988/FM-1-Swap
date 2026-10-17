import SwiftUI

/// The risk and recovery notice: shown full screen on first run, and kept in SETUP.
enum SafetyNotice {
    /// Bump when the wording changes materially, so everyone sees it again once.
    static let version = 2

    static let text = """
        Not affiliated with M-Vave. Though this utility makes every attempt to correctly reset and \
        safely flash your device, flashing custom firmware is at your own risk.
        """

    static let recovery = """
        If your FM-1 won't start, some firmwares can start it in USB recovery mode if you hold down \
        the "OCT−" key while switching it on. Then connect it and flash a firmware from the SAVED \
        screen. Not every firmware does this, and if yours doesn't, recovery needs a computer: SETUP \
        says more.
        """

    /// What's left when the FM-1 won't start at all.
    ///
    /// The tools are other people's, and haven't been tried with FM-SWITCH.
    static let romRecovery = """
        If the FM-1 won't start at all, not even in USB recovery mode, FM-SWITCH can't help. If a computer \
        sees a USB device with UBOOT in its name (such as WL80UBOOT), the FM-1's chip is in its own \
        download mode, and tools built on jl-uboot-tool can put the official firmware back. Some \
        firmwares (Melodee, FuMi-1 and Hortator among them) publish one with instructions. If it never \
        gets that far, recovery needs an FM-1 Transporter. FM-SWITCH hasn't tried any of these.
        """

    /// Where to find the computer recovery tools.
    static let romRecoveryLinks = [
        CatalogueLink(label: "jl-uboot-tool", url: URL(literal: "https://github.com/kagaimiq/jl-uboot-tool")),
        CatalogueLink(
            label: "FM-1 Transporter",
            url: URL(literal: "https://github.com/kurogedelic/FM-1-transporter")
        ),
    ]
}

/// The first-run notice.
///
/// It has to be acknowledged before the app can be used; OK unlocks after a few seconds, so it
/// isn't tapped away unread.
struct SafetyNoticeView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.phosphor) private var theme
    @State private var secondsLeft = 4

    var body: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 22) {
                    Text("FM-SWITCH")
                        .font(.pixel(28, bold: true, relativeTo: .largeTitle))
                        .foregroundStyle(theme.bright)
                        .phosphorGlow()
                        .padding(.top, 40)
                    PixelPanel(title: "BEFORE YOU START") {
                        MonoText(SafetyNotice.text, size: 16)
                        MonoText(SafetyNotice.recovery, size: 16)
                    }
                    MonoText("This notice is also on the SETUP screen.", size: 13, dim: true)
                }
                .padding(20)
            }
            .scrollIndicators(.hidden)

            Button(secondsLeft > 0 ? "OK (\(secondsLeft))" : "OK") {
                model.settings.acknowledgedNoticeVersion = SafetyNotice.version
            }
            .buttonStyle(.pixelProminent)
            .disabled(secondsLeft > 0)
            .padding(20)
        }
        .frame(maxWidth: WideLayout.readableWidth)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(theme.background.ignoresSafeArea())
        .accessibilityAddTraits(.isModal)
        .task {
            while secondsLeft > 0 {
                try? await Task.sleep(for: .seconds(1))
                secondsLeft -= 1
            }
        }
    }
}
