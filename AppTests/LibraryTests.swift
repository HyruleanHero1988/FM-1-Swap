import FM1Kit
import Foundation
import Testing

@testable import FMSwitch

/// The on-phone library's built-in official firmware.
@MainActor
struct LibraryTests {
    static func emptyDirectory() -> URL {
        URL.temporaryDirectory.appending(path: UUID().uuidString, directoryHint: .isDirectory)
    }

    @Test func comesWithTheOfficialFirmwareAndKeepsItWhenCleared() throws {
        let library = FirmwareLibrary(directory: Self.emptyDirectory())
        let official = try #require(library.official)
        #expect(official.sha256 == OfficialFirmware.v15.sha256)
        #expect(official.product == "FM-1_015")
        #expect(official.author == "M-VAVE")

        library.clear()
        #expect(library.official == official)
    }

    @Test func savesTheBuiltInCopyOnlyOnce() throws {
        let directory = Self.emptyDirectory()
        _ = FirmwareLibrary(directory: directory)
        let reopened = FirmwareLibrary(directory: directory)
        #expect(reopened.items.count == 1)
    }

    @Test func picksThePackagesThatCanFinishALoadersUpdate() throws {
        let library = FirmwareLibrary(directory: Self.emptyDirectory())
        let official = try #require(library.official)
        let stockLoader = try #require(FM1Identity(text: "ota-FM-1_015"))
        let otherLoader = try #require(FM1Identity(text: "ota-FM-1_900"))
        #expect(library.packages(thatCanFinish: stockLoader) == [official])
        #expect(library.packages(thatCanFinish: otherLoader).isEmpty)
    }

    @Test func picksThePackageAnArmedOpenFirmwaresLoaderIsWaitingFor() throws {
        let library = FirmwareLibrary(directory: Self.emptyDirectory())
        let official = try #require(library.official)
        // Open firmware armed with V15 starts a loader named after the start of V15's build ID.
        let armedForV15 = try #require(FM1Identity(text: "ota-FM1C_db1642b2b6fa5c2c"))
        let armedForOther = try #require(FM1Identity(text: "ota-FM1C_0123456789abcdef"))
        #expect(library.packages(thatCanFinish: armedForV15) == [official])
        #expect(library.packages(thatCanFinish: armedForOther).isEmpty)
    }

    @Test func recognisesOpenFirmwareIdentities() {
        #expect(OpenFirmware.isOpen("FM-1_8000012"))
        #expect(OpenFirmware.isOpen("FM1_OFFICIAL_OPEN"))
        #expect(OpenFirmware.isOpen("FM1_COMMUNITY_3"))
        #expect(!OpenFirmware.isOpen("FM-1_015"))
        #expect(!OpenFirmware.isOpen("FM-1_916"))
        #expect(!OpenFirmware.isOpen("ota-FM-1_8000012"))
    }
}
