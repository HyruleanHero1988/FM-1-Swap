import Foundation
import Testing

@testable import FMSwitch

struct CatalogueTests {
    static let hubSample = """
        {"updated": "2026-10-14 20:00 UTC", "firmwares": [
          {"id": "zp12", "kind": "drums", "name": "zp12", "author": "zvenson", "what": "A 12-bit sampler.",
           "version": "2.4.2", "date": "2026-10-14", "pkg": "fw/zp12-2.4.2.fwsc", "product": "FM-1_974",
           "size": 610110, "sha256": "DD05", "beta": false, "loader": null, "stars": 21, "unknownField": 1,
           "repo": "zvenson/zp12", "site": "https://zp12.designburgapps.com"},
          {"id": "bubba", "kind": ["groove", "drums"], "name": "Bubba Machine", "version": "0.5.0-beta",
           "pkg": "fw/bubba-0.5.0-beta.fwsc", "product": "FM-1_003", "size": 1, "sha256": "ab", "beta": true,
           "loader": "mvave"}
        ]}
        """

    static let editorSample = """
        <!-- One <article class="firmware-entry"> per firmware. -->
        <section class="firmware-latest"><h2 id="firmware-latest-title">Latest</h2></section>
        <article class="firmware-entry synthwave-panel" id="stock" data-pinned data-updated="2026-07-30">
          <p class="synthwave-kicker">M-VAVE</p>
          <div><h2 class="firmware-title" id="firmware-stock">Stock firmware</h2></div>
          <p class="firmware-status"><span>Latest V15</span></p>
          <p class="firmware-blurb">The firmware every FM1 ships with.</p>
          <ul class="firmware-links"><li><a href="https://www.m-vave.com/download">M-VAVE downloads ↗</a></li></ul>
        </article>
        <article class="firmware-entry synthwave-panel" id="baud-girl" data-pinned data-updated="2026-10-12">
          <p class="synthwave-kicker">Baud Girl</p>
          <h2 class="firmware-title" id="firmware-fm1-va">Baud Girl (FM-1+VA)</h2>
          <p class="firmware-status"><span>Latest FM-1_101</span></p>
          <p class="firmware-blurb">Keeps M-VAVE&#39;s FM engine &amp; effects,
            and adds a Virtual Analog engine.</p>
          <ul class="firmware-links">
            <li><a class="hero-action" href="https://baudgirl.com/work/FM-1+VA" rel="noopener">FM-1+VA ↗</a></li>
          </ul>
        </article>
        <article class="firmware-entry synthwave-panel" id="zp12-sampler" data-stars="25">
          <p class="synthwave-kicker">zvenson</p>
          <h2 class="firmware-title">zp12</h2>
          <p class="firmware-blurb">Editor blurb.</p>
          <ul class="firmware-links"><li><a href="https://github.com/zvenson/zp12">GitHub ↗</a></li></ul>
        </article>
        """

    @Test func decodesTheHubFeedAndResolvesPackageURLs() throws {
        let feed = try HubFeed.decode(Data(Self.hubSample.utf8))
        #expect(feed.updated == "2026-10-14 20:00 UTC")
        let entries = feed.entries()
        #expect(entries.count == 2)
        #expect(entries[0].stars == 21)
        #expect(entries[1].stars == nil)
        #expect(entries[0].kinds == ["drums"])
        #expect(entries[0].sha256 == "dd05")
        #expect(entries[0].packageURL?.absoluteString == "https://thefirmwarehub.com/fw/zp12-2.4.2.fwsc")
        #expect(entries[0].packageOrigin == .hub)
        #expect(entries[0].links.map(\.label) == ["Project site"])
        #expect(entries[1].kinds == ["groove", "drums"])
        #expect(entries[1].requiresOfficialFirmware)
        #expect(!entries[0].requiresOfficialFirmware)
    }

    @Test func readsEditorEntriesAndIgnoresMarkupInComments() {
        let listing = EditorListing(html: Self.editorSample)
        #expect(listing.entries.map(\.anchor) == ["stock", "baud-girl", "zp12-sampler"])
        #expect(listing.entries[2].stars == 25)
        let baudGirl = listing.entries[1]
        #expect(baudGirl.name == "Baud Girl (FM-1+VA)")
        #expect(baudGirl.author == "Baud Girl")
        #expect(baudGirl.latest == "FM-1_101")
        #expect(baudGirl.pinned)
        #expect(baudGirl.blurb == "Keeps M-VAVE's FM engine & effects, and adds a Virtual Analog engine.")
        #expect(
            baudGirl.links == [CatalogueLink(label: "FM-1+VA", url: URL(string: "https://baudgirl.com/work/FM-1+VA")!)])
    }

    @Test func mergesByRepositoryAndAddsEditorOnlyFirmwares() throws {
        let hub = try HubFeed.decode(Data(Self.hubSample.utf8)).entries()
        let editor = EditorListing(html: Self.editorSample).entries
        let merged = CatalogueBuilder.merge(hub: hub, editor: editor, editorURL: EditorListing.defaultURL)
        #expect(merged.count == 4)

        let zp12 = try #require(merged.first { $0.id == "zp12" })
        #expect(zp12.listing == [.hub, .editor])
        #expect(zp12.what == "A 12-bit sampler.")
        #expect(zp12.stars == 21, "The hub's count is kept over the editor's.")
        #expect(zp12.links.contains { $0.url.absoluteString == "https://fm1-editor.com/firmware/#zp12-sampler" })

        let officialEntry = merged.first(where: \.isOfficial)
        let official = try #require(officialEntry)
        #expect(official.id == "mvave-official")
        #expect(official.packageURL == nil)

        let baudGirl = try #require(merged.first { $0.id == "fm1-editor-baud-girl" })
        #expect(baudGirl.listing == .editor)
        #expect(baudGirl.version == "FM-1_101")
    }

    @Test func keepsM_VAVEsOpenFirmwareApartFromV15() throws {
        let html = """
            <article class="firmware-entry" id="stock" data-updated="2026-07-30">
              <h2 class="firmware-title">Stock firmware</h2>
              <p class="firmware-status"><span>Latest V15</span></p>
              <ul class="firmware-links"><li><a href="https://www.m-vave.com/download">Downloads</a></li></ul>
            </article>
            <article class="firmware-entry" id="official-open" data-updated="2026-10-14">
              <h2 class="firmware-title">Official Open</h2>
              <p class="firmware-status"><span>Latest V12</span></p>
              <ul class="firmware-links">
                <li><a href="https://open.m-vave.com/fm1-workbench">FM1 Workbench</a></li>
                <li><a href="https://www.m-vave.com/download">Downloads</a></li>
              </ul>
            </article>
            """
        let editor = EditorListing(html: html).entries
        let merged = CatalogueBuilder.merge(hub: [], editor: editor, editorURL: EditorListing.defaultURL)
        #expect(merged.map(\.id) == ["mvave-official", "fm1-editor-official-open"])
        #expect(merged.map(\.isOfficial) == [true, false])
    }

    @Test func picksTheCurrentReleaseFromAPublisherManifest() throws {
        let html = """
            <script type="application/json" id="fi-releases">[
              {"version":"FM-1_101","file":"/fm1/firmware/FM-1+VA-101.fwsc","sha256":"B43D","beta":false,
               "current":true,"date":"2026-10-12"},
              {"version":"FM-1_097","file":"/fm1/firmware/FM-1+VA-97.fwsc","sha256":"08ed","beta":false,"current":false}
            ]</script>
            """
        let found = try #require(
            PackageDiscovery.manifestPackage(
                in: html, pageURL: URL(string: "https://baudgirl.com/work/FM-1+VA/install")!))
        #expect(found.url.absoluteString == "https://baudgirl.com/fm1/firmware/FM-1+VA-101.fwsc")
        #expect(found.sha256 == "b43d")
        #expect(found.product == "FM-1_101")
        #expect(found.origin == .publisherManifest)
    }

    @Test func findsPageLinksButSkipsOfficialAndUnrelatedFiles() {
        let html = """
            <a href="firmware/fm1doom-0.5.fwsc">Doom</a> <a href="/firmware/sloopdx-3.5.fwsc">SLOOP</a>
            <p>Go back with M-VAVE's (FM-1.fwsc) file.</p> <input accept=".fwsc">
            """
        let page = URL(string: "https://dx7.designburgapps.com/doom/")!
        let links = PackageDiscovery.packageLinks(in: html, pageURL: page)
        #expect(
            links.map(\.absoluteString) == [
                "https://dx7.designburgapps.com/doom/firmware/fm1doom-0.5.fwsc",
                "https://dx7.designburgapps.com/firmware/sloopdx-3.5.fwsc",
            ])
        let words = PackageDiscovery.identifyingWords(name: "FM-1 Doom", links: [])
        #expect(words == ["doom"])
        #expect(links.filter { PackageDiscovery.isRelevant($0, words: words) }.count == 1)
    }

    @Test func prefersFullGitHubReleasesAndReadsAssetDigests() throws {
        let json = """
            [{"tag_name":"v0.12","draft":false,"prerelease":true,"published_at":"2026-10-14T10:00:00Z",
              "assets":[{"name":"pm-0.12.fwsc","size":10,"digest":"sha256:AA","browser_download_url":"https://example.com/pm-0.12.fwsc"}]},
             {"tag_name":"v0.11","draft":false,"prerelease":false,"published_at":"2026-10-10T10:00:00Z",
              "assets":[{"name":"FM-1.fwsc","size":1,"browser_download_url":"https://example.com/FM-1.fwsc"},
                        {"name":"pm-0.11.fwsc","size":9,"digest":"sha256:BB","browser_download_url":"https://example.com/pm-0.11.fwsc"}]}]
            """
        let releases = try JSONDecoder().decode([GitHubRelease].self, from: Data(json.utf8))
        let found = try #require(PackageDiscovery.pick(from: releases))
        #expect(found.version == "v0.11")
        #expect(found.sha256 == "bb")
        #expect(found.size == 9)
        #expect(!found.beta)
        #expect(found.date == "2026-10-10")

        let onlyPre = try #require(PackageDiscovery.pick(from: [releases[0]]))
        #expect(onlyPre.beta)
    }

    @Test func mapsGitHubPagesLinksToRepositories() {
        let links = [
            CatalogueLink(
                label: "", url: URL(string: "https://quixotic7.github.io/PurpleMonkeyFM1/webapp/installer/")!),
            CatalogueLink(label: "", url: URL(string: "https://github.com/Quixotic7/PurpleMonkeyFM1")!),
        ]
        let repos = PackageDiscovery.gitHubRepos(in: links)
        #expect(repos.count == 1)
        #expect(repos.first?.name == "PurpleMonkeyFM1")
    }

    @Test func showsTheSiteSourceAndManualFirstAndFoldsAwayTheRest() {
        func link(_ label: String) -> CatalogueLink {
            CatalogueLink(label: label, url: URL(literal: "https://example.com").appending(path: label))
        }
        let entry = CatalogueEntry(
            id: "melodee",
            name: "Melodee",
            links: [
                link("Release"), link("Manual"), link("Source"), link("Install"), link("Project site"),
                link("Source code"),
            ],
            listing: .hub
        )
        #expect(entry.mainLinks.map(\.label) == ["Project site", "Source", "Manual"])
        #expect(entry.moreLinks.map(\.label) == ["Release", "Install", "Source code"])
        #expect(entry.moreLinks.map(\.displayLabel) == ["Release", "Web installer", "Source code"])
    }

    @Test func foldsAwayEveryLinkWhenThereIsNoSiteSourceOrManual() {
        let entry = CatalogueEntry(
            id: "x",
            name: "X",
            links: [CatalogueLink(label: "Editor", url: URL(literal: "https://example.com/editor"))],
            listing: .editor
        )
        #expect(entry.mainLinks.isEmpty)
        #expect(entry.moreLinks.count == 1)
    }

    @MainActor @Test func dropsARepeatedIDFromAListSavedByAnOlderBuild() throws {
        let directory = URL.temporaryDirectory.appending(path: UUID().uuidString, directoryHint: .isDirectory)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let saved = MergedCatalogue(
            builtAt: .now,
            entries: [
                CatalogueEntry(
                    id: "mvave-official", name: "M-VAVE official firmware", listing: .editor, isOfficial: true),
                CatalogueEntry(id: "mvave-official", name: "Official Open", listing: .editor, isOfficial: true),
                CatalogueEntry(id: "amb-1", name: "AMB-1", listing: .hub),
            ],
            problems: []
        )
        try JSONEncoder().encode(saved).write(to: directory.appending(path: "catalogue.json"))

        let store = CatalogueStore(directory: directory)
        #expect(store.entries.map(\.id) == ["mvave-official", "amb-1"])
        #expect(store.entries.first?.name == "M-VAVE official firmware")
    }
}

struct ReleaseCheckTests {
    @Test func describesEachKnownRecoveryModeAndLeavesTheRestUnknown() {
        let sloop = CatalogueEntry(id: "sloop", name: "SLOOP", listing: .hub)
        #expect(sloop.rescueMode == .octaveDownAtPowerOn(since: nil))
        #expect(RescueMode.known["melodee"]?.summary == "Hold OCT− while switching on (from 1.3.5)")
        #expect(RescueMode.known["fumi"] == .bothOctaveKeysHeld)
        #expect(RescueMode.known["felucca"] == .none(tried: "1.6.1"))
        #expect(RescueMode.known["mvave-official"]?.isFromReadme == false)
        #expect(RescueMode.known["zp12"]?.isFromReadme == true)
        #expect(CatalogueEntry(id: "x0x", name: "X0X", listing: .hub).rescueMode == nil)
    }

    @Test func ordersVersionsNumericallyWithPreReleasesFirst() {
        #expect(ReleaseVersion.isNewer("v1.3.10", than: "1.3.9"))
        #expect(ReleaseVersion.isNewer("1.3.7", than: "1.3.6"))
        #expect(!ReleaseVersion.isNewer("v1.3.6", than: "1.3.6"))
        #expect(!ReleaseVersion.isNewer("1.3.5", than: "1.3.6"))
        #expect(ReleaseVersion.isNewer("1.4.0", than: "1.4.0-rc.4"))
        #expect(!ReleaseVersion.isNewer("1.4.0-rc.4", than: "1.4.0"))
        #expect(ReleaseVersion.isNewer("1.4.0-rc.10", than: "1.4.0-rc.9"))
        #expect(ReleaseVersion.isNewer("FM-1_101", than: "FM-1_97"))
        #expect(!ReleaseVersion.isNewer("0.12", than: nil))
        #expect(!ReleaseVersion.isNewer("drum-v0.16.0", than: "0.16.0"))
        #expect(!ReleaseVersion.isNewer("fumi-1.0.7", than: "1.0.7"))
        #expect(ReleaseVersion.isNewer("ghoulbox-v2.2", than: "2.1"))
        #expect(!ReleaseVersion.isNewer("ETCH-V1.38-OTA-RESTORE-FIX", than: "1.38"))
    }

    static let melodeeReleases = """
        [{"tag_name":"v1.3.7","draft":false,"prerelease":false,"published_at":"2026-10-16T01:00:00Z",
          "assets":[{"name":"melodee-1.3.7-1core.fwsc","size":5,"digest":"sha256:DD","browser_download_url":"https://example.com/melodee-1.3.7-1core.fwsc"},
                    {"name":"melodee-1.3.7.fwsc","size":6,"digest":"sha256:CC","browser_download_url":"https://example.com/melodee-1.3.7.fwsc"}]},
         {"tag_name":"v1.4.0-rc.4","draft":false,"prerelease":true,"published_at":"2026-10-14T20:20:27Z",
          "assets":[{"name":"melodee-1.4.0-rc.4.fwsc","size":7,"browser_download_url":"https://example.com/melodee-1.4.0-rc.4.fwsc"}]},
         {"tag_name":"v1.3.6","draft":false,"prerelease":false,"published_at":"2026-10-15T06:06:53Z",
          "assets":[{"name":"melodee-1.3.6-1core.fwsc","size":5,"digest":"sha256:EE","browser_download_url":"https://example.com/melodee-1.3.6-1core.fwsc"},
                    {"name":"melodee-1.3.6.fwsc","size":6,"digest":"sha256:FF","browser_download_url":"https://example.com/melodee-1.3.6.fwsc"}]}]
        """

    static func melodee(version: String, beta: Bool = false, sha256: String = "AA") -> CatalogueEntry {
        CatalogueEntry(
            id: "melodee",
            name: "Melodee",
            version: version,
            beta: beta,
            packageURL: URL(string: "https://thefirmwarehub.com/fw/melodee-\(version).fwsc"),
            packageOrigin: .hub,
            product: "FM-1_91036",
            size: 1,
            sha256: sha256,
            repo: "keremimo/melodee",
            listing: .hub
        )
    }

    static let check = ReleaseCheck { url in
        #expect(url.absoluteString == "https://api.github.com/repos/keremimo/melodee/releases?per_page=10")
        return Data(melodeeReleases.utf8)
    }

    @Test func namesBuildsByWhatSetsThemApart() {
        #expect(
            PackageDiscovery.buildNames(for: ["melodee-1.3.6-1core.fwsc", "melodee-1.3.6.fwsc"]) == [
                "1core", "standard",
            ])
        #expect(
            PackageDiscovery.buildNames(for: ["bubba-diag-0.6.1-beta.fwsc", "bubba-machine-0.6.1-beta.fwsc"]) == [
                "diag", "machine",
            ])
        #expect(
            PackageDiscovery.buildNames(for: ["hortator-0.16.0-bluetooth.fwsc", "hortator-0.16.0.fwsc"]) == [
                "bluetooth", "standard",
            ])
        #expect(PackageDiscovery.buildNames(for: ["felucca-2.4.fwsc", "sloop-2.4.fwsc"]) == ["felucca", "sloop"])
        #expect(
            PackageDiscovery.buildNames(for: ["choralroot-0.22-beta1-1core.fwsc", "choralroot-0.22-beta1.fwsc"]) == [
                "1core", "standard",
            ])
    }

    @Test func asksForABuildWhenTheListedOneIsUnknown() async throws {
        guard case .newer(let newer) = try await Self.check.check(Self.melodee(version: "1.3.6")) else {
            Issue.record("Expected a newer release.")
            return
        }
        #expect(newer.version == "v1.3.7")
        #expect(newer.packageOrigin == .githubRelease)
        #expect(newer.product == nil)
        #expect(newer.date == "2026-10-16")
        #expect(newer.builds?.map(\.name) == ["1core", "standard"])
        #expect(newer.needsBuildChoice)

        let standard = newer.choosing(build: "standard")
        #expect(!standard.needsBuildChoice)
        #expect(standard.packageURL?.lastPathComponent == "melodee-1.3.7.fwsc")
        #expect(standard.sha256 == "cc")
    }

    @Test func keepsTheBuildTheListChose() async throws {
        guard case .newer(let singleCore) = try await Self.check.check(Self.melodee(version: "1.3.6", sha256: "ee"))
        else {
            Issue.record("Expected a newer release.")
            return
        }
        #expect(singleCore.build == "1core")
        #expect(singleCore.packageURL?.lastPathComponent == "melodee-1.3.7-1core.fwsc")
        #expect(singleCore.sha256 == "dd")
    }

    @Test func staysOnTheListedTrackAndFillsInTheListedBuilds() async throws {
        guard case .upToDate(let listed) = try await Self.check.check(Self.melodee(version: "1.3.7", sha256: "dd"))
        else {
            Issue.record("Expected up to date.")
            return
        }
        #expect(listed.build == "1core")
        #expect(listed.builds?.count == 2)
        #expect(listed.packageURL?.host() == "thefirmwarehub.com")

        guard case .newer(let beta) = try await Self.check.check(Self.melodee(version: "1.4.0-rc.3", beta: true)) else {
            Issue.record("Expected a newer pre-release.")
            return
        }
        #expect(beta.version == "v1.4.0-rc.4")
        #expect(beta.beta)
        #expect(beta.builds == nil)
    }
}
