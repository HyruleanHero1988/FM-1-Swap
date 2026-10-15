import SwiftUI

/// Look, connection and flashing preferences, plus credits.
struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.phosphor) private var theme

    var body: some View {
        @Bindable var settings = model.settings
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                ScreenHeader(title: "SETUP")

                PixelPanel(title: "SAFETY + RECOVERY") {
                    MonoText(SafetyNotice.text, size: 14)
                    MonoText(SafetyNotice.recovery, size: 14)
                }

                PixelPanel(title: "COLOUR + SCREEN") {
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 92), spacing: 8)], spacing: 8) {
                        ForEach(PhosphorPreset.allCases) { preset in
                            let selected = settings.phosphorHex.uppercased() == preset.hex
                            Button {
                                settings.phosphorHex = preset.hex
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
                    }
                    ColorPicker(selection: Binding(
                        get: { Color(hex: settings.phosphorHex) ?? PhosphorPreset.green.colour },
                        set: { settings.phosphorHex = $0.hexString }
                    ), supportsOpacity: false) {
                        MonoText("CUSTOM COLOUR  \(settings.phosphorHex)", size: 14)
                    }
                    labelledSlider("GLOW", value: $settings.glow)
                    Button("RESET LOOK") { settings.resetLook() }
                        .buttonStyle(.pixelCompact)
                    pixelToggle("CRT SCANLINES", isOn: $settings.scanlines)
                    pixelToggle("CRT FLICKER", isOn: $settings.flicker)
                }

                PixelPanel(title: "CONNECTION") {
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
                    MonoText("The iOS Simulator can't reach USB devices plugged into the Mac, so use the simulated FM-1 here, or run on a phone or as \"My Mac (Designed for iPad)\".", size: 13, dim: true)
                    #endif
                    if settings.transportKind == .simulated {
                        pixelToggle("START IN USB RECOVERY MODE", isOn: $settings.simulatedRecoveryMode)
                        pixelToggle("PULL THE CABLE AT 45%", isOn: $settings.simulatedCablePull)
                        Button("RESET SIMULATED FM-1") { model.applyConnectionSettings() }
                            .buttonStyle(.pixel)
                            .disabled(model.device.isFlashing)
                    }
                }

                PixelPanel(title: "FLASHING") {
                    pixelToggle("ALWAYS VIA OFFICIAL FIRMWARE", isOn: $settings.alwaysViaOfficial)
                    MonoText("Off: the official firmware is only flashed first when a firmware needs it.", size: 13, dim: true)
                    pixelToggle("SHOW BETA FIRMWARES", isOn: $settings.showBeta)
                    pixelToggle("SHOW LINKS-ONLY FIRMWARES", isOn: $settings.showLinksOnly)
                    MonoText("Links-only firmwares have no file this app can download; they link to their own sites.", size: 13, dim: true)
                }

                PixelPanel(title: "CREDITS") {
                    MonoText("Firmware lists: thefirmwarehub.com (mirrors each project's GitHub releases) and fm1-editor.com/firmware. Packages come from the hub, GitHub releases, publishers' own sites, and M-VAVE.", size: 13, dim: true)
                    MonoText("Update protocol: written from the AL-255/FM-1-RE reverse-engineering notes.", size: 13, dim: true)
                    MonoText("Fonts: Departure Mono, Silkscreen and VT323 (SIL Open Font Licence).", size: 13, dim: true)
                }
            }
            .padding(16)
        }
        .scrollIndicators(.hidden)
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
