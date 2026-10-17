import AppKit

/// What to call the device the app is running on, in the app's text.
enum ThisDevice {
    @MainActor
    static var name: String { "Mac" }
}

/// The system clipboard.
enum Pasteboard {
    @MainActor
    static func copy(_ url: URL) {
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(url.absoluteString, forType: .URL)
        NSPasteboard.general.setString(url.absoluteString, forType: .string)
    }
}
