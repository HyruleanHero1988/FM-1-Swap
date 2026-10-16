import SwiftUI

/// Look, connection and flashing preferences, plus credits.
struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.phosphor) private var theme

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                ScreenHeader(title: "SETUP")

                PixelPanel(title: "SAFETY + RECOVERY") {
                    MonoText(SafetyNotice.text, size: 14)
                    MonoText(SafetyNotice.recovery, size: 14)
                }

                PixelPanel(title: "IF IT WON'T START AT ALL") { romRecovery }

                PixelPanel(title: "COLOUR + SCREEN") { lookSettings }
                PixelPanel(title: "CONNECTION") { connectionSettings }
                PixelPanel(title: "FLASHING") { flashingSettings }
                PixelPanel(title: "CREDITS") { credits }
            }
            .padding(16)
        }
        .scrollIndicators(.hidden)
    }

    // MARK: - Panels.

    @ViewBuilder private var romRecovery: some View {
        MonoText(SafetyNotice.romRecovery, size: 14)
        ForEach(SafetyNotice.romRecoveryLinks, id: \.url) { link in
            Link(destination: link.url) {
                Text("▸ \(link.label.uppercased())")
                    .font(.pixel(11, bold: true, relativeTo: .callout))
                    .foregroundStyle(theme.bright)
            }
        }
    }

    @ViewBuilder private var lookSettings: some View {
        @Bindable var settings = model.settings
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 92), spacing: 8)], spacing: 8) {
            ForEach(PhosphorPreset.allCases) { preset in
                presetButton(preset)
            }
        }
        ColorPicker(
            selection: Binding(
                get: { Color(hex: settings.phosphorHex) ?? PhosphorPreset.green.colour },
                set: { settings.phosphorHex = $0.hexString }
            ),
            supportsOpacity: false
        ) {
            MonoText("CUSTOM COLOUR  \(settings.phosphorHex)", size: 14)
        }
        labelledSlider("GLOW", value: $settings.glow)
        Button("RESET LOOK") { settings.resetLook() }
            .buttonStyle(.pixelCompact)
        pixelToggle("CRT SCANLINES", isOn: $settings.scanlines)
        pixelToggle("CRT FLICKER", isOn: $settings.flicker)
    }

    @ViewBuilder private var connectionSettings: some View {
        @Bindable var settings = model.settings
        HStack(spacing: 8) {
            ForEach(TransportKind.allCases) { kind in
                Button(kind.name) {
                    settings.transportKind = kind
                    model.applyConnectionSettings()
                }
                .buttonStyle(PixelButtonStyle(prominent: settings.transportKind == kind, compact: true))
            }
        }
        #if targetEnvironment(simulator)
            NoteText(
                """
                The iOS Simulator can't reach USB devices plugged into the Mac, so use the simulated FM-1 \
                here, or run on a phone or as "My Mac (Designed for iPad)".
                """
            )
        #endif
        if settings.transportKind == .simulated {
            pixelToggle("START IN USB RECOVERY MODE", isOn: $settings.simulatedRecoveryMode)
            pixelToggle("PULL THE CABLE AT 45%", isOn: $settings.simulatedCablePull)
            pixelToggle("READ BACKWARDS, LIKE V15", isOn: $settings.simulatedReadsBackwards)
            pixelToggle("VERIFY AFTER WRITING, LIKE FELUCCA", isOn: $settings.simulatedVerifies)
            Button("RESET SIMULATED FM-1") { model.applyConnectionSettings() }
                .buttonStyle(.pixel)
                .disabled(model.device.isFlashing)
        }
    }

    @ViewBuilder private var flashingSettings: some View {
        @Bindable var settings = model.settings
        pixelToggle("ALWAYS VIA OFFICIAL FIRMWARE", isOn: $settings.alwaysViaOfficial)
        NoteText("Off: the official firmware is only flashed first when a firmware needs it.")
        pixelToggle("SHOW BETA FIRMWARES", isOn: $settings.showBeta)
        pixelToggle("SHOW LINKS-ONLY FIRMWARES", isOn: $settings.showLinksOnly)
        NoteText("Links-only firmwares have no file this app can download; they link to their own sites.")
    }

    @ViewBuilder private var credits: some View {
        NoteText(
            """
            Firmware lists: thefirmwarehub.com (mirrors each project's GitHub releases) and \
            fm1-editor.com/firmware. Packages come from the hub, GitHub releases, publishers' own sites, \
            and M-VAVE.
            """
        )
        NoteText("Update protocol: written from the AL-255/FM-1-RE reverse-engineering notes.")
        NoteText("Fonts: Departure Mono, Silkscreen and VT323 (SIL Open Font Licence).")
    }

    // MARK: - Controls.

    private func presetButton(_ preset: PhosphorPreset) -> some View {
        let selected = model.settings.phosphorHex.uppercased() == preset.hex
        return Button {
            model.settings.phosphorHex = preset.hex
        } label: {
            VStack(spacing: 4) {
                Rectangle().fill(preset.colour).frame(height: 18)
                Text(preset.name).font(.pixel(8, bold: true, relativeTo: .caption2))
            }
            .padding(6)
            .foregroundStyle(theme.bright)
            .overlay(PixelBox(step: 2).strokeBorder(selected ? theme.bright : theme.faint, lineWidth: 2))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(preset.name.capitalized)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private func pixelToggle(_ title: String, isOn: Binding<Bool>) -> some View {
        Button {
            isOn.wrappedValue.toggle()
        } label: {
            HStack {
                MonoText(title, size: 14)
                Spacer()
                Text(isOn.wrappedValue ? "[■]" : "[ ]")
                    .font(.mono(17))
                    .foregroundStyle(theme.bright)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title.capitalized)
        .accessibilityValue(isOn.wrappedValue ? "On" : "Off")
    }

    private func labelledSlider(_ title: String, value: Binding<Double>) -> some View {
        HStack {
            MonoText(title, size: 14)
            Slider(value: value, in: 0...1)
                .tint(theme.bright)
        }
    }
}
