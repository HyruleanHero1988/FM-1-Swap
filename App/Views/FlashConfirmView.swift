import SwiftUI

/// Shows exactly what will be flashed (including any official-first step) before starting.
struct FlashConfirmView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
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
        VStack(alignment: .leading, spacing: 16) {
            ScreenHeader(title: "FLASH?")
            ForEach(Array(steps.enumerated()), id: \.offset) { index, step in
                MonoText("\(index + 1). \(step.name) \(step.version)", size: 17)
            }
            if missingOfficial {
                MonoText("This needs the official firmware first, and none is saved. Add it under SAVED.", size: 14)
            }
            if model.device.identity == nil {
                MonoText("No FM-1 is connected yet. Connect it before starting.", size: 14)
            }
            NoteText("Takes a minute or two per step. Stay in the app until it finishes.")
            Spacer()
            HStack(spacing: 10) {
                Button("CANCEL") { dismiss() }
                    .buttonStyle(.pixel)
                Button("START") {
                    model.device.flash(steps)
                    dismiss()
                    onStart()
                }
                .buttonStyle(.pixelProminent)
                .disabled(missingOfficial || model.device.identity == nil)
            }
        }
        .padding(20)
        .overlay { CRTOverlay() }
    }
}
