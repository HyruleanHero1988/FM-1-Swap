import FM1Kit
import SwiftUI

/// Shows exactly what will be flashed (including any official-first step) before starting, and
/// asks for confirmation when that switches the FM-1 to a different firmware, or installs the
/// identity its custom firmware already reports.
struct FlashConfirmView: View {
    /// What the check before flashing found.
    private enum Checked: Equatable {
        case checking
        case found(InstallKind)
        /// The FM-1 is in its update loader, so the flash finishes that update; there's nothing
        /// to check.
        case finishingUpdate
        case failed(String)
    }

    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var checked = Checked.checking
    @State private var backedUp = false
    let target: StoredFirmware
    var requiresOfficial = false
    var onStart: () -> Void = {}

    var body: some View {
        let steps = model.device.plan(
            for: target,
            requiresOfficial: requiresOfficial,
            alwaysViaOfficial: model.settings.alwaysViaOfficial
        )
        let missingOfficial =
            (requiresOfficial || model.settings.alwaysViaOfficial) && target.source != .official
            && model.library.official == nil
        let switching = isSwitching(steps)
        let sameIdentity = !switching && checked == .found(.sameIdentity)
        VStack(alignment: .leading, spacing: 16) {
            ScreenHeader(title: switching ? "SWITCH FIRMWARE?" : sameIdentity ? "REINSTALL OR UPDATE?" : "FLASH?")
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    ForEach(Array(steps.enumerated()), id: \.offset) { index, step in
                        MonoText("\(index + 1). \(step.name) \(step.version)", size: 17)
                    }
                    if missingOfficial {
                        MonoText(
                            "This needs the official firmware first, and none is saved. Add it under SAVED.", size: 14)
                    }
                    deviceNotes(firstStep: steps.first)
                    checkNotes(switching: switching)
                    NoteText("Takes a minute or two per step. Stay in the app until it finishes.")
                }
            }
            .scrollIndicators(.hidden)
            buttons(steps, switching: switching, sameIdentity: sameIdentity, missingOfficial: missingOfficial)
        }
        .padding(20)
        .overlay { CRTOverlay() }
        .task(id: "\(steps.first?.id ?? "")|\(model.device.identity?.text ?? "")") { await check(steps.first) }
    }

    /// CANCEL, and the button that starts: SWITCH, INSTALL for the same identity, or START.
    private func buttons(
        _ steps: [StoredFirmware], switching: Bool, sameIdentity: Bool, missingOfficial: Bool
    ) -> some View {
        HStack(spacing: 10) {
            Button("CANCEL") { dismiss() }
                .buttonStyle(.pixel)
            // Starting from the REINSTALL OR UPDATE? question is the confirmation it asks for.
            Button(switching ? "SWITCH" : sameIdentity ? "INSTALL" : "START") {
                model.device.flash(
                    steps,
                    confirmsSwitchingFirmware: switching && backedUp,
                    confirmsSameIdentity: sameIdentity
                )
                dismiss()
                onStart()
            }
            .buttonStyle(.pixelProminent)
            .disabled(missingOfficial || !canStart(steps, switching: switching))
        }
    }

    /// Whether these steps move the FM-1 to a different firmware: always for a chain through the
    /// official firmware, otherwise as the check found.
    private func isSwitching(_ steps: [StoredFirmware]) -> Bool {
        steps.count > 1 || checked == .found(.switchingFirmware)
    }

    private func canStart(_ steps: [StoredFirmware], switching: Bool) -> Bool {
        guard model.device.identity != nil else { return false }
        switch checked {
        case .checking, .failed:
            return false
        case .finishingUpdate:
            return true
        case .found(let kind):
            return !(kind == .reinstall && steps.count == 1) && (!switching || backedUp)
        }
    }

    private func check(_ first: StoredFirmware?) async {
        guard let first, let identity = model.device.identity else {
            checked = .checking
            return
        }
        guard !identity.isLoader else {
            checked = .finishingUpdate
            return
        }
        checked = .checking
        do {
            checked = .found(try await model.device.check(first).kind)
        } catch {
            checked = .failed(error.localizedDescription)
        }
    }

    /// What the FM-1 reports now, and whether the first step can finish an update it's waiting in.
    @ViewBuilder private func deviceNotes(firstStep: StoredFirmware?) -> some View {
        if let identity = model.device.identity {
            MonoText("THE FM-1 NOW REPORTS \(identity.text)", size: 13, dim: true)
            if identity.isLoader, let first = firstStep,
                !model.library.packages(thatCanFinish: identity).contains(first)
            {
                MonoText(
                    """
                    The FM-1 is in an update another package started, and \(first.name) can't finish it. \
                    Flash the firmware you were installing instead.
                    """,
                    size: 14
                )
            }
        } else {
            MonoText("No FM-1 is connected yet. Connect it before starting.", size: 14)
        }
    }

    /// What the check found: a reinstall, a switch or the same identity to confirm, or why it
    /// couldn't check.
    @ViewBuilder private func checkNotes(switching: Bool) -> some View {
        switch checked {
        case .checking where model.device.identity != nil:
            MonoText("CHECKING THE FM-1…", size: 13, dim: true)
        case .failed(let reason):
            MonoText(reason, size: 14)
        case .found(.reinstall) where !switching:
            MonoText("The FM-1 is already running this firmware, so there's nothing to install.", size: 14)
        case .found(.sameIdentity) where !switching:
            if let identity = model.device.identity {
                MonoText(
                    """
                    The FM-1 already reports \(identity.text), the same as this package. Installing it \
                    reinstalls that firmware, or updates it to this release.
                    """,
                    size: 14
                )
            }
        default:
            EmptyView()
        }
        if switching {
            MonoText(
                """
                This switches the FM-1 to a different firmware. That can erase its projects, sounds and \
                samples, so back up anything you want to keep first.
                """,
                size: 14
            )
            PixelToggle("I'VE BACKED UP WHAT I NEED", isOn: $backedUp)
        } else {
            NoteText("Back up the FM-1's projects, sounds and samples before changing its firmware.")
        }
    }
}
