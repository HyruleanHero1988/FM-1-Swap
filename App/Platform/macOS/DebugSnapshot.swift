#if DEBUG
    import AppKit

    /// Debug builds on the Mac accept `-snapshotAfter <seconds>`: the window then saves a picture of
    /// itself as snapshot.png in the app's temporary folder and the app quits, so screenshots can be
    /// taken without Screen Recording permission.
    @MainActor
    enum DebugSnapshot {
        static func scheduleIfAsked() {
            let delay = UserDefaults.standard.double(forKey: "snapshotAfter")
            guard delay > 0 else { return }
            Task {
                try? await Task.sleep(for: .seconds(delay))
                if let view = NSApplication.shared.windows.first(where: \.isVisible)?.contentView,
                    let bitmap = view.bitmapImageRepForCachingDisplay(in: view.bounds)
                {
                    view.cacheDisplay(in: view.bounds, to: bitmap)
                    let url = FileManager.default.temporaryDirectory.appending(path: "snapshot.png")
                    try? bitmap.representation(using: .png, properties: [:])?.write(to: url)
                    print("Snapshot: \(url.path)")
                }
                NSApplication.shared.terminate(nil)
            }
        }
    }
#endif
