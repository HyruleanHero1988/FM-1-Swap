import SwiftUI

/// A rectangle with one-step pixel corners, like a sprite box drawn on an 8-bit screen.
struct PixelBox: InsettableShape {
    var step: CGFloat = 3
    var inset: CGFloat = 0

    func path(in rect: CGRect) -> Path {
        let box = rect.insetBy(dx: inset, dy: inset)
        let notch = min(step, box.width / 2, box.height / 2)
        var path = Path()
        path.move(to: CGPoint(x: box.minX + notch, y: box.minY))
        path.addLine(to: CGPoint(x: box.maxX - notch, y: box.minY))
        path.addLine(to: CGPoint(x: box.maxX - notch, y: box.minY + notch))
        path.addLine(to: CGPoint(x: box.maxX, y: box.minY + notch))
        path.addLine(to: CGPoint(x: box.maxX, y: box.maxY - notch))
        path.addLine(to: CGPoint(x: box.maxX - notch, y: box.maxY - notch))
        path.addLine(to: CGPoint(x: box.maxX - notch, y: box.maxY))
        path.addLine(to: CGPoint(x: box.minX + notch, y: box.maxY))
        path.addLine(to: CGPoint(x: box.minX + notch, y: box.maxY - notch))
        path.addLine(to: CGPoint(x: box.minX, y: box.maxY - notch))
        path.addLine(to: CGPoint(x: box.minX, y: box.minY + notch))
        path.addLine(to: CGPoint(x: box.minX + notch, y: box.minY + notch))
        path.closeSubpath()
        return path
    }

    func inset(by amount: CGFloat) -> Self {
        Self(step: step, inset: inset + amount)
    }
}

/// A raised box with a title tab on its top edge, like a window on an old handheld.
struct PixelPanel<Content: View>: View {
    @Environment(\.phosphor) private var theme
    var title: String?
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            content
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 14)
        .padding(.top, title == nil ? 14 : 22)
        .padding(.bottom, 14)
        .background(PixelBox().fill(theme.shadow).offset(x: 4, y: 4))
        .background(PixelBox().fill(theme.surface))
        .overlay(PixelBox().strokeBorder(theme.dim, lineWidth: 2))
        .overlay(alignment: .topLeading) {
            if let title {
                Text(title)
                    .font(.pixel(10, bold: true, relativeTo: .caption))
                    .foregroundStyle(theme.background)
                    .padding(.horizontal, 6)
                    .padding(.vertical, 2)
                    .background(theme.bright)
                    .offset(x: 12, y: -8)
                    .accessibilityAddTraits(.isHeader)
            }
        }
    }
}

/// A chunky button with a hard block shadow; it drops into the shadow while pressed.
struct PixelButtonStyle: ButtonStyle {
    @Environment(\.phosphor) private var theme
    @Environment(\.isEnabled) private var isEnabled
    var prominent = false
    var compact = false

    func makeBody(configuration: Configuration) -> some View {
        let pressed = configuration.isPressed
        let edge = isEnabled ? theme.bright : theme.faint
        let depth: CGFloat = compact ? 2 : 3
        configuration.label
            .font(.pixel(compact ? 10 : 13, bold: true, relativeTo: .callout))
            .textCase(.uppercase)
            .lineLimit(1)
            .minimumScaleFactor(0.7)
            .padding(.horizontal, compact ? 8 : 14)
            .padding(.vertical, compact ? 6 : 11)
            .frame(maxWidth: compact ? nil : .infinity)
            .foregroundStyle(prominent ? theme.background : edge)
            .background(PixelBox(step: 2).fill(prominent ? edge : theme.surface))
            .overlay(PixelBox(step: 2).strokeBorder(edge, lineWidth: 2))
            .offset(x: pressed ? depth : 0, y: pressed ? depth : 0)
            .background(PixelBox(step: 2).fill(theme.shadow).offset(x: depth, y: depth).opacity(isEnabled ? 1 : 0))
            .contentShape(Rectangle())
    }
}

extension ButtonStyle where Self == PixelButtonStyle {
    static var pixel: PixelButtonStyle { PixelButtonStyle() }
    static var pixelProminent: PixelButtonStyle { PixelButtonStyle(prominent: true) }
    static var pixelCompact: PixelButtonStyle { PixelButtonStyle(compact: true) }
}

/// A segmented bar, one block per step, like the device's level meters.
struct PixelProgressBar: View {
    @Environment(\.phosphor) private var theme
    var fraction: Double?
    var segments = 20

    var body: some View {
        TimelineView(.animation(minimumInterval: 0.12, paused: fraction != nil)) { timeline in
            let lit = litSegments(at: timeline.date)
            HStack(spacing: 3) {
                ForEach(0..<segments, id: \.self) { index in
                    Rectangle()
                        .fill(lit.contains(index) ? theme.bright : theme.faint)
                }
            }
            .frame(height: 16)
            .padding(4)
            .overlay(PixelBox(step: 2).strokeBorder(theme.dim, lineWidth: 2))
        }
        .accessibilityElement()
        .accessibilityLabel("Progress")
        .accessibilityValue(fraction.map { "\(Int($0 * 100)) percent" } ?? "Working")
    }

    private func litSegments(at date: Date) -> Range<Int> {
        guard let fraction else {
            // Indeterminate: a three-block runner bouncing along the bar.
            let step = Int(date.timeIntervalSinceReferenceDate / 0.12) % ((segments - 3) * 2)
            let start = step < segments - 3 ? step : (segments - 3) * 2 - step
            return start..<(start + 3)
        }
        return 0..<Int((min(max(fraction, 0), 1) * Double(segments)).rounded(.down))
    }
}

/// A small tag, e.g. SAVED or BETA: filled in the accent, or outlined in the muted colour.
struct PixelTag: View {
    @Environment(\.phosphor) private var theme
    var text: String
    var inverted = true

    var body: some View {
        Text(text)
            .font(.pixel(9, bold: true, relativeTo: .caption2))
            .textCase(.uppercase)
            .padding(.horizontal, 5)
            .padding(.vertical, 2)
            .foregroundStyle(inverted ? theme.background : theme.muted)
            .background(inverted ? theme.bright : .clear)
            .overlay(Rectangle().strokeBorder(theme.muted, lineWidth: inverted ? 0 : 1))
    }
}

/// A blinking block cursor.
struct BlinkingCursor: View {
    @Environment(\.phosphor) private var theme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        TimelineView(.periodic(from: .now, by: 0.5)) { timeline in
            let on = reduceMotion || Int(timeline.date.timeIntervalSinceReferenceDate * 2).isMultiple(of: 2)
            Rectangle()
                .fill(theme.bright)
                .frame(width: 9, height: 16)
                .opacity(on ? 1 : 0)
        }
        .accessibilityHidden(true)
    }
}

/// Body text in the pixel monospace: ink for the main text, muted for secondary text.
struct MonoText: View {
    @Environment(\.phosphor) private var theme
    var text: String
    var size: CGFloat = 15
    var dim = false

    init(_ text: String, size: CGFloat = 15, dim: Bool = false) {
        self.text = text
        self.size = size
        self.dim = dim
    }

    var body: some View {
        Text(text)
            .font(.mono(size))
            .lineSpacing(size * 0.2)
            .foregroundStyle(dim ? theme.muted : theme.ink)
    }
}

/// A dimmed footnote under a control or panel, in small monospaced text.
struct NoteText: View {
    var text: String

    init(_ text: String) {
        self.text = text
    }

    var body: some View {
        MonoText(text, size: 13, dim: true)
    }
}

/// The header line at the top of each screen, ruled off with a dashed pixel line.
struct ScreenHeader<Trailing: View>: View {
    @Environment(\.phosphor) private var theme
    var title: String
    @ViewBuilder var trailing: Trailing

    var body: some View {
        HStack(alignment: .center) {
            Text(title)
                .font(.pixel(20, bold: true, relativeTo: .title2))
                .foregroundStyle(theme.bright)
                .phosphorGlow()
                .accessibilityAddTraits(.isHeader)
            Spacer()
            trailing
        }
        .padding(.bottom, 10)
        .overlay(alignment: .bottom) {
            Rectangle()
                .stroke(theme.dim, style: StrokeStyle(lineWidth: 2, dash: [4, 4]))
                .frame(height: 1)
        }
    }
}

extension ScreenHeader where Trailing == EmptyView {
    init(title: String) {
        self.title = title
        trailing = EmptyView()
    }
}

/// An on/off switch drawn as a text check box, "[■]" or "[ ]".
struct PixelToggle: View {
    @Environment(\.phosphor) private var theme
    let title: String
    @Binding var isOn: Bool

    init(_ title: String, isOn: Binding<Bool>) {
        self.title = title
        _isOn = isOn
    }

    var body: some View {
        Button {
            isOn.toggle()
        } label: {
            HStack {
                MonoText(title, size: 14)
                Spacer()
                Text(isOn ? "[■]" : "[ ]")
                    .font(.mono(17))
                    .foregroundStyle(theme.bright)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(title.capitalized)
        .accessibilityValue(isOn ? "On" : "Off")
    }
}

extension View {
    /// Text entry for searches and links: no capitals or corrections, and a keyboard for links
    /// on iPhone and iPad when `forLinks` is set.
    func plainTextEntry(forLinks: Bool = false) -> some View {
        #if os(iOS)
            textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(forLinks ? .URL : .default)
        #else
            autocorrectionDisabled()
        #endif
    }
}

extension View {
    /// Sizes a sheet: half or full height on iPhone and iPad (full only when `isTall`), and a
    /// fixed size on the Mac, where a sheet otherwise shrinks to fit and cuts off scrolling text.
    func pixelSheetSize(isTall: Bool = false) -> some View {
        #if os(macOS)
            frame(minWidth: 520, idealWidth: 560, minHeight: isTall ? 640 : 480, idealHeight: isTall ? 720 : 560)
        #else
            presentationDetents(isTall ? [.large] : [.medium, .large])
        #endif
    }
}

/// Formats byte counts as "597 KB".
func formattedBytes(_ count: Int) -> String {
    let formatter = ByteCountFormatter()
    formatter.countStyle = .file
    formatter.allowsNonnumericFormatting = false
    return formatter.string(fromByteCount: Int64(count)).uppercased()
}
