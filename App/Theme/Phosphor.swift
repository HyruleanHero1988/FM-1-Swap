import SwiftUI

/// The look: a dark handheld-console screen with an accent colour the user picks.
///
/// Pixel capitals for headings, a pixel monospace for reading, and optional CRT touches (glow,
/// scanlines, flicker) kept subtle.
struct PhosphorTheme: Equatable {
    var base: Color
    var glow: Double
    var scanlines: Bool
    var flicker: Bool

    /// The accent: headings, buttons, tags, progress.
    var bright: Color { base }
    /// The accent, quieter: borders and rules.
    var dim: Color { base.opacity(0.5) }
    /// The accent, barely there: disabled states and empty segments.
    var faint: Color { base.opacity(0.2) }

    /// Body text: a warm off-white, nudged towards the accent.
    var ink: Color { Color(rgb: 0xECE8DF).mixed(with: base, amount: 0.08) }
    /// Secondary text.
    var muted: Color { Color(rgb: 0x8D918B).mixed(with: base, amount: 0.1) }

    /// Nearly black, tinted slightly towards the accent.
    var background: Color { Color(rgb: 0x0C0E0D).mixed(with: base, amount: 0.03) }
    /// Panels and rows, one step up from the background.
    var surface: Color { Color(rgb: 0x151917).mixed(with: base, amount: 0.05) }
    /// The hard offset shadow under buttons and panels.
    var shadow: Color { Color.black.opacity(0.55) }

    static let standard = Self(base: PhosphorPreset.green.colour, glow: 0.25, scanlines: false, flicker: false)
}

enum PhosphorPreset: String, CaseIterable, Identifiable {
    case green
    case amber
    case ice
    case rose
    case paper

    var id: String { rawValue }

    var name: String {
        switch self {
        case .green: "GREEN"
        case .amber: "AMBER"
        case .ice: "ICE"
        case .rose: "ROSE"
        case .paper: "PAPER"
        }
    }

    var hex: String {
        switch self {
        case .green: "#7BE39A"
        case .amber: "#FFB547"
        case .ice: "#8FD3EE"
        case .rose: "#FF8DB5"
        case .paper: "#E4E0D3"
        }
    }

    var colour: Color { Color(hex: hex) ?? .green }
}

private struct PhosphorThemeKey: EnvironmentKey {
    static let defaultValue = PhosphorTheme.standard
}

extension EnvironmentValues {
    var phosphor: PhosphorTheme {
        get { self[PhosphorThemeKey.self] }
        set { self[PhosphorThemeKey.self] = newValue }
    }
}

// MARK: - Fonts.

extension Font {
    /// Chunky pixel capitals for headings, buttons and labels.
    static func pixel(_ size: CGFloat, bold: Bool = false, relativeTo style: TextStyle = .body) -> Font {
        .custom(bold ? "Silkscreen-Bold" : "Silkscreen-Regular", size: size, relativeTo: style)
    }

    /// Pixel monospace for everything that is read rather than scanned.
    static func mono(_ size: CGFloat, relativeTo style: TextStyle = .body) -> Font {
        .custom("DepartureMono-Regular", size: size, relativeTo: style)
    }

    /// Tall LCD digits for big readouts such as the device identity and percentages.
    static func lcd(_ size: CGFloat, relativeTo style: TextStyle = .largeTitle) -> Font {
        .custom("VT323-Regular", size: size, relativeTo: style)
    }
}

// MARK: - Colour helpers.

extension Color {
    init(rgb value: UInt32) {
        self.init(
            red: Double((value >> 16) & 0xFF) / 255,
            green: Double((value >> 8) & 0xFF) / 255,
            blue: Double(value & 0xFF) / 255
        )
    }

    init?(hex: String) {
        let digits = hex.trimmingCharacters(in: CharacterSet(charactersIn: "# ")).uppercased()
        guard digits.count == 6, let value = UInt32(digits, radix: 16) else { return nil }
        self.init(rgb: value)
    }

    var rgbComponents: (red: Double, green: Double, blue: Double) {
        let resolved = resolve(in: EnvironmentValues())
        return (Double(resolved.red), Double(resolved.green), Double(resolved.blue))
    }

    var hexString: String {
        let rgb = rgbComponents
        let clamp = { (value: Double) in Int((min(max(value, 0), 1) * 255).rounded()) }
        return String(format: "#%02X%02X%02X", clamp(rgb.red), clamp(rgb.green), clamp(rgb.blue))
    }

    /// This colour moved `amount` (0...1) of the way towards `other`.
    func mixed(with other: Color, amount: Double) -> Color {
        let from = rgbComponents
        let to = other.rgbComponents
        let blend = { (start: Double, end: Double) in start + (end - start) * amount }
        return Color(red: blend(from.red, to.red), green: blend(from.green, to.green), blue: blend(from.blue, to.blue))
    }
}

// MARK: - Glow.

private struct PhosphorGlow: ViewModifier {
    @Environment(\.phosphor) private var theme
    var strength: Double

    func body(content: Content) -> some View {
        let opacity = 0.7 * theme.glow * strength
        if opacity > 0.01 {
            content.shadow(color: theme.base.opacity(opacity), radius: 4)
        } else {
            content
        }
    }
}

extension View {
    /// A soft bloom around accent text, scaled by the GLOW setting (off at zero).
    func phosphorGlow(_ strength: Double = 1) -> some View {
        modifier(PhosphorGlow(strength: strength))
    }
}
