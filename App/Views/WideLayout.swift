import SwiftUI

/// The layout for big screens: iPad in landscape (or a wide window), and the Mac.
///
/// The sections move to a sidebar, lists show the selected item beside them instead of in a
/// sheet, and long screens split into two columns.
enum WideLayout {
    /// The narrowest window that gets the wide layout.
    ///
    /// An 11-inch iPad in portrait (834 points) keeps the phone layout; in landscape, or a
    /// 13-inch in either, it goes wide.
    static let minimumWidth: CGFloat = 900

    /// The width of a list beside its selected item.
    static let listWidth: CGFloat = 380

    /// The widest a column of text gets, so lines stay readable.
    static let readableWidth: CGFloat = 720
}

extension EnvironmentValues {
    /// Whether the screen is using ``WideLayout``.
    @Entry var isWideLayout = false
}

/// Two columns side by side in the wide layout, or one after the other otherwise.
struct AdaptiveColumns<Leading: View, Trailing: View>: View {
    @Environment(\.isWideLayout) private var isWide
    private let spacing: CGFloat
    private let leading: Leading
    private let trailing: Trailing

    init(spacing: CGFloat = 22, @ViewBuilder leading: () -> Leading, @ViewBuilder trailing: () -> Trailing) {
        self.spacing = spacing
        self.leading = leading()
        self.trailing = trailing()
    }

    var body: some View {
        if isWide {
            HStack(alignment: .top, spacing: spacing) {
                VStack(alignment: .leading, spacing: spacing) { leading }
                    .frame(maxWidth: .infinity, alignment: .leading)
                VStack(alignment: .leading, spacing: spacing) { trailing }
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
        } else {
            VStack(alignment: .leading, spacing: spacing) {
                leading
                trailing
            }
        }
    }
}

/// A list on the left and its selected item on the right, with a hint while nothing is picked.
struct ListAndDetail<List: View, Detail: View>: View {
    @Environment(\.phosphor) private var theme
    private let placeholder: String
    private let hasSelection: Bool
    private let list: List
    private let detail: Detail

    /// - Parameters:
    ///   - placeholder: Shown in place of the detail while nothing is picked.
    ///   - hasSelection: Whether something is picked.
    ///   - list: The list, on the left.
    ///   - detail: The picked item, on the right.
    init(
        placeholder: String,
        hasSelection: Bool,
        @ViewBuilder list: () -> List,
        @ViewBuilder detail: () -> Detail
    ) {
        self.placeholder = placeholder
        self.hasSelection = hasSelection
        self.list = list()
        self.detail = detail()
    }

    var body: some View {
        HStack(spacing: 0) {
            list
                .frame(width: WideLayout.listWidth)
            Rectangle().fill(theme.dim).frame(width: 2)
            Group {
                if hasSelection {
                    detail
                        .frame(maxWidth: WideLayout.readableWidth, alignment: .leading)
                } else {
                    MonoText(placeholder, size: 14, dim: true)
                        .padding(24)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        }
    }
}
