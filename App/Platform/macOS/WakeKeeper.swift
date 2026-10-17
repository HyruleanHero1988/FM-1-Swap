import Foundation
import SwiftUI

/// Keeps the Mac from idle sleep while something is written to the FM-1.
///
/// A Mac app keeps running in the background anyway, so leaving it needs nothing more.
@MainActor
final class WakeKeeper {
    private var activity: (any NSObjectProtocol)?

    var isHolding = false {
        didSet {
            guard isHolding != oldValue else { return }
            if isHolding {
                activity = ProcessInfo.processInfo.beginActivity(
                    options: [.idleSystemSleepDisabled, .userInitiated],
                    reason: "Writing to the FM-1")
            } else if let activity {
                ProcessInfo.processInfo.endActivity(activity)
                self.activity = nil
            }
        }
    }

    func scenePhaseChanged(to _: ScenePhase, isWriting _: Bool) {}
}
