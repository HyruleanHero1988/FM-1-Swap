import UIKit

/// What to call the device the app is running on, in the app's text.
enum ThisDevice {
    /// "phone" or "iPad".
    @MainActor
    static var name: String {
        UIDevice.current.userInterfaceIdiom == .pad ? "iPad" : "phone"
    }
}

/// The system clipboard.
enum Pasteboard {
    @MainActor
    static func copy(_ url: URL) {
        UIPasteboard.general.url = url
    }
}
