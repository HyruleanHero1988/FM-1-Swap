import SwiftUI

enum AppTab: String, CaseIterable, Identifiable {
    case device
    case firmware
    case library
    case settings

    var id: String { rawValue }

    var title: String {
        switch self {
        case .device: "DEVICE"
        case .firmware: "FIRMWARE"
        case .library: "SAVED"
        case .settings: "SETUP"
        }
    }

    /// A tiny glyph drawn from box characters, in keeping with the pixel look.
    var glyph: String {
        switch self {
        case .device: "▣"
        case .firmware: "≡"
        case .library: "▼"
        case .settings: "◆"
        }
    }
}

struct RootView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.phosphor) private var theme
    @Environment(\.scenePhase) private var scenePhase
    @State private var tab = Self.initialTab

    /// Debug builds accept `-initialTab firmware` (etc.) as a launch argument, for screenshots.
    private static var initialTab: AppTab {
        #if DEBUG
            UserDefaults.standard.string(forKey: "initialTab").flatMap(AppTab.init(rawValue:)) ?? .device
        #else
            .device
        #endif
    }

    var body: some View {
        GeometryReader { geometry in
            let isWide = geometry.size.width >= WideLayout.minimumWidth
            VStack(spacing: 0) {
                StatusLine()
                if isWide {
                    HStack(spacing: 0) {
                        PixelSidebar(selection: $tab)
                        screen
                    }
                } else {
                    screen
                    PixelTabBar(selection: $tab)
                }
            }
            .environment(\.isWideLayout, isWide)
        }
        .background(theme.background.ignoresSafeArea())
        .overlay {
            // An overlay rather than a cover, so it appears even while a sheet is closing.
            if model.device.job != nil {
                FlashView()
                    .transition(.opacity)
            }
        }
        .animation(.easeOut(duration: 0.2), value: model.device.job != nil)
        .overlay {
            if model.settings.needsSafetyNotice {
                SafetyNoticeView()
                    .transition(.opacity)
            }
        }
        .animation(.easeOut(duration: 0.2), value: model.settings.needsSafetyNotice)
        .overlay { CRTOverlay() }
        .onChange(of: scenePhase) { _, phase in
            model.device.scenePhaseChanged(to: phase)
        }
    }
}

extension RootView {
    private var screen: some View {
        Group {
            switch tab {
            case .device: DeviceView(tab: $tab)
            case .firmware: CatalogueView()
            case .library: LibraryView()
            case .settings: SettingsView()
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/// The sections down the left of the wide layout, drawn like the tab bar's buttons.
private struct PixelSidebar: View {
    @Environment(\.phosphor) private var theme
    @Binding var selection: AppTab

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(AppTab.allCases) { tab in
                let selected = tab == selection
                Button {
                    selection = tab
                } label: {
                    HStack(spacing: 10) {
                        Text(tab.glyph).font(.mono(16))
                        Text(tab.title).font(.pixel(11, bold: true, relativeTo: .callout))
                        Spacer(minLength: 0)
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 10)
                    .foregroundStyle(selected ? theme.background : theme.muted)
                    .background(PixelBox(step: 2).fill(selected ? theme.bright : .clear))
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(tab.title.capitalized)
                .accessibilityAddTraits(selected ? .isSelected : [])
            }
            Spacer()
        }
        .padding(12)
        .frame(width: 180)
        .background(theme.surface.ignoresSafeArea(edges: [.bottom, .leading]))
        .overlay(alignment: .trailing) { Rectangle().fill(theme.dim).frame(width: 2) }
    }
}

/// The top line, like the device's own status bar: app name, connection and device identity.
private struct StatusLine: View {
    @Environment(AppModel.self) private var model
    @Environment(\.phosphor) private var theme

    var body: some View {
        HStack(spacing: 8) {
            Text("FM-SWITCH")
                .font(.pixel(12, bold: true, relativeTo: .caption))
            Spacer()
            Text(model.device.transportName)
                .font(.pixel(9, relativeTo: .caption2))
                .foregroundStyle(theme.muted)
            Text(model.device.identity?.text ?? "NO DEVICE")
                .font(.pixel(10, bold: true, relativeTo: .caption))
                .padding(.horizontal, 5)
                .padding(.vertical, 2)
                .foregroundStyle(model.device.identity == nil ? theme.bright : theme.background)
                .background(model.device.identity == nil ? Color.clear : theme.bright)
                .overlay(Rectangle().strokeBorder(theme.bright, lineWidth: 1))
        }
        .foregroundStyle(theme.bright)
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(theme.surface.ignoresSafeArea(edges: .top))
        .overlay(alignment: .bottom) { Rectangle().fill(theme.dim).frame(height: 2) }
        .accessibilityElement(children: .combine)
    }
}

private struct PixelTabBar: View {
    @Environment(\.phosphor) private var theme
    @Binding var selection: AppTab

    var body: some View {
        HStack(spacing: 6) {
            ForEach(AppTab.allCases) { tab in
                let selected = tab == selection
                Button {
                    selection = tab
                } label: {
                    VStack(spacing: 3) {
                        Text(tab.glyph).font(.mono(16))
                        Text(tab.title).font(.pixel(9, bold: true, relativeTo: .caption2))
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 6)
                    .foregroundStyle(selected ? theme.background : theme.muted)
                    .background(PixelBox(step: 2).fill(selected ? theme.bright : .clear))
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(tab.title.capitalized)
                .accessibilityAddTraits(selected ? .isSelected : [])
            }
        }
        .padding(.horizontal, 12)
        .padding(.top, 8)
        .padding(.bottom, 4)
        .background(theme.surface.ignoresSafeArea(edges: .bottom))
        .overlay(alignment: .top) { Rectangle().fill(theme.dim).frame(height: 2) }
    }
}
