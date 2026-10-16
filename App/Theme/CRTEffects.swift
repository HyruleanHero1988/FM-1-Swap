import SwiftUI

/// Optional CRT touches laid over the whole app: faint scanlines with a light vignette, and a
/// slow refresh band with a tiny flicker.
///
/// Both are off by default and kept subtle. Never takes touches; motion is dropped when Reduce
/// Motion is on.
struct CRTOverlay: View {
    @Environment(\.phosphor) private var theme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            if theme.scanlines {
                Scanlines()
                RadialGradient(
                    colors: [.clear, .black.opacity(0.08), .black.opacity(0.3)],
                    center: .center,
                    startRadius: 180,
                    endRadius: 700
                )
            }
            if theme.flicker && !reduceMotion {
                RefreshBandAndFlicker(base: theme.base)
            }
        }
        .ignoresSafeArea()
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

private struct Scanlines: View {
    var body: some View {
        Canvas { context, size in
            var path = Path()
            for row in stride(from: 0, to: size.height, by: 3) {
                path.addRect(CGRect(x: 0, y: row, width: size.width, height: 1))
            }
            context.fill(path, with: .color(.black.opacity(0.12)))
        }
    }
}

private struct RefreshBandAndFlicker: View {
    var base: Color

    var body: some View {
        TimelineView(.animation(minimumInterval: 1 / 20)) { timeline in
            let time = timeline.date.timeIntervalSinceReferenceDate
            GeometryReader { proxy in
                let height = proxy.size.height
                let bandHeight = height * 0.14
                let position = (time.truncatingRemainder(dividingBy: 9) / 9) * (height + bandHeight) - bandHeight
                ZStack(alignment: .top) {
                    // Brightness wobble of a percent or so.
                    Color.black.opacity(0.01 + 0.01 * sin(time * 53) * sin(time * 7.3))
                    LinearGradient(colors: [.clear, base.opacity(0.025), .clear], startPoint: .top, endPoint: .bottom)
                        .frame(height: bandHeight)
                        .offset(y: position)
                }
            }
        }
    }
}
