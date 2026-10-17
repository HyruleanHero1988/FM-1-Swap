/// M-VAVE's open-source FM-1 firmware (FM1 Open, github.com/m-vave/fm1_open) and builds of it.
enum OpenFirmware {
    /// Whether a device identity is FM1 Open's or a community build's, as M-VAVE's updater
    /// recognises them: `FM1_OFFICIAL_OPEN`, `FM1_COMMUNITY_<n>`, or `FM-1_8` and six digits
    /// (V12 is `FM-1_8000012`).
    static func isOpen(_ identity: String) -> Bool {
        identity.wholeMatch(of: /FM1_OFFICIAL_OPEN|FM1_COMMUNITY_\d+|FM-1_8\d{6}/) != nil
    }
}
