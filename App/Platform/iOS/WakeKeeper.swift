import SwiftUI
import UIKit

/// Keeps the iPhone or iPad from sleeping while something is written to the FM-1.
///
/// It pauses auto-lock and, if the person leaves the app mid-write, asks iOS for the short grace
/// period apps get to finish work.
@MainActor
final class WakeKeeper {
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid

    var isHolding = false {
        didSet {
            guard isHolding != oldValue else { return }
            UIApplication.shared.isIdleTimerDisabled = isHolding
            if !isHolding { endBackgroundTask() }
        }
    }

    func scenePhaseChanged(to phase: ScenePhase, isWriting: Bool) {
        switch phase {
        case .background where isWriting:
            guard backgroundTask == .invalid else { return }
            backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "FM-1 write") { [weak self] in
                Task { @MainActor in self?.endBackgroundTask() }
            }
        case .active:
            endBackgroundTask()
        default:
            break
        }
    }

    private func endBackgroundTask() {
        guard backgroundTask != .invalid else { return }
        UIApplication.shared.endBackgroundTask(backgroundTask)
        backgroundTask = .invalid
    }
}
