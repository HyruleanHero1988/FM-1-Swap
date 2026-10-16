import Foundation

extension URL {
    /// A fixed web address written into the source.
    ///
    /// A malformed address is a programming error, so it stops the app the first time it's used
    /// rather than failing quietly later.
    init(literal: StaticString) {
        guard let url = URL(string: "\(literal)") else {
            preconditionFailure("Not a valid URL: \(literal)")
        }
        self = url
    }
}
