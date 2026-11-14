import CryptoKit
import XCTest
@testable import BetaVulkanLauncher

final class UpdaterTests: XCTestCase {
    func testVersionsCompareNumberByNumber() {
        XCTAssertTrue(AppVersion("0.9")! < AppVersion("0.10")!)
        XCTAssertTrue(AppVersion("v0.1.1")! < AppVersion("0.1.2")!)
        XCTAssertTrue(AppVersion("1.0")! == AppVersion("1.0.0")!)
        XCTAssertFalse(AppVersion("0.2.0")! < AppVersion("0.1.9")!)
        XCTAssertNil(AppVersion("0.1.beta"))
        XCTAssertNil(AppVersion(""))
    }

    private func release(tag: String = "v0.2.0", draft: Bool = false, prerelease: Bool = false, dmg: String? = "https://github.com/undrwtrsprite/silica/releases/download/v0.2.0/Silica-0.2.0.dmg", sig: String? = "https://github.com/undrwtrsprite/silica/releases/download/v0.2.0/Silica-0.2.0.dmg.sig") -> Data {
        var assets: [[String: Any]] = []
        if let dmg { assets.append(["name": "Silica-0.2.0.dmg", "browser_download_url": dmg]) }
        if let sig { assets.append(["name": "Silica-0.2.0.dmg.sig", "browser_download_url": sig]) }
        let json: [String: Any] = ["tag_name": tag, "draft": draft, "prerelease": prerelease, "body": "Notes", "html_url": "https://github.com/undrwtrsprite/silica/releases/tag/\(tag)", "assets": assets]
        return try! JSONSerialization.data(withJSONObject: json)
    }

    func testNewerReleaseIsOffered() {
        let info = UpdateParsing.newerRelease(from: release(), than: AppVersion("0.1.1")!)
        XCTAssertEqual(info?.version, AppVersion("0.2.0"))
        XCTAssertEqual(info?.dmgURL.lastPathComponent, "Silica-0.2.0.dmg")
        XCTAssertEqual(info?.signatureURL.lastPathComponent, "Silica-0.2.0.dmg.sig")
    }

    func testSameOlderDraftPrereleaseAndUnsignedReleasesAreNotOffered() {
        let now = AppVersion("0.2.0")!
        XCTAssertNil(UpdateParsing.newerRelease(from: release(), than: now))
        XCTAssertNil(UpdateParsing.newerRelease(from: release(tag: "v0.1.0"), than: now))
        XCTAssertNil(UpdateParsing.newerRelease(from: release(draft: true), than: AppVersion("0.1.0")!))
        XCTAssertNil(UpdateParsing.newerRelease(from: release(prerelease: true), than: AppVersion("0.1.0")!))
        XCTAssertNil(UpdateParsing.newerRelease(from: release(sig: nil), than: AppVersion("0.1.0")!), "no signature file: never offered")
        XCTAssertNil(UpdateParsing.newerRelease(from: release(dmg: nil), than: AppVersion("0.1.0")!))
        XCTAssertNil(UpdateParsing.newerRelease(from: Data("not json".utf8), than: now))
    }

    func testDownloadsMustComeFromGitHub() {
        XCTAssertNil(UpdateParsing.newerRelease(from: release(dmg: "https://evil.example/Silica-0.2.0.dmg"), than: AppVersion("0.1.0")!))
        XCTAssertNil(UpdateParsing.newerRelease(from: release(sig: "https://evil.example/Silica-0.2.0.dmg.sig"), than: AppVersion("0.1.0")!))
    }

    func testSignatureAcceptsTheRightFileAndRejectsChangesAndOtherKeys() throws {
        let key = Curve25519.Signing.PrivateKey()
        let publicKey = key.publicKey.rawRepresentation.base64EncodedString()
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("updater-test-\(UUID().uuidString).dmg")
        defer { try? FileManager.default.removeItem(at: file) }
        let bytes = Data((0..<4096).map { UInt8($0 % 251) })
        try bytes.write(to: file)
        let signature = try key.signature(for: bytes).base64EncodedString()

        XCTAssertTrue(UpdateVerifier.isValid(file: file, signatureBase64: signature + "\n", publicKeyBase64: publicKey))

        var changed = bytes; changed[100] ^= 1
        try changed.write(to: file)
        XCTAssertFalse(UpdateVerifier.isValid(file: file, signatureBase64: signature, publicKeyBase64: publicKey), "a changed file must fail")

        try bytes.write(to: file)
        let other = Curve25519.Signing.PrivateKey().publicKey.rawRepresentation.base64EncodedString()
        XCTAssertFalse(UpdateVerifier.isValid(file: file, signatureBase64: signature, publicKeyBase64: other), "a signature by another key must fail")
        XCTAssertFalse(UpdateVerifier.isValid(file: file, signatureBase64: "garbage", publicKeyBase64: publicKey))
    }

    func testTheBuiltInPublicKeyIsAValidKey() {
        let data = Data(base64Encoded: UpdateConfig.publicKeyBase64)
        XCTAssertEqual(data?.count, 32)
        XCTAssertNotNil(try? Curve25519.Signing.PublicKey(rawRepresentation: data!))
    }

    func testAppsInsideADiskImageOrDevBuildsCannotBeReplaced() {
        XCTAssertNotNil(UpdateManager.cannotReplace(URL(fileURLWithPath: "/Volumes/Silica/Silica.app")))
        XCTAssertNotNil(UpdateManager.cannotReplace(URL(fileURLWithPath: "/private/var/folders/x/AppTranslocation/ABC/d/Silica.app")))
        XCTAssertNotNil(UpdateManager.cannotReplace(URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("BetaVulkanLauncher")))
    }

    /// Runs the real mount, copy, check and swap steps on a signed disk image. Skipped unless SILICA_UPDATE_TEST_DMG
    /// (a signed Silica-x.y.z.dmg), SILICA_UPDATE_TEST_APP (an older Silica.app to replace) and SILICA_UPDATE_TEST_VERSION are set.
    func testSignedDiskImageReplacesAnOlderApp() throws {
        let env = ProcessInfo.processInfo.environment
        guard let dmgPath = env["SILICA_UPDATE_TEST_DMG"], let oldApp = env["SILICA_UPDATE_TEST_APP"], let versionText = env["SILICA_UPDATE_TEST_VERSION"] else {
            throw XCTSkip("set SILICA_UPDATE_TEST_DMG, SILICA_UPDATE_TEST_APP and SILICA_UPDATE_TEST_VERSION to run")
        }
        let dmg = URL(fileURLWithPath: dmgPath)
        let sig = try String(contentsOfFile: dmgPath + ".sig", encoding: .utf8)
        XCTAssertTrue(UpdateVerifier.isValid(file: dmg, signatureBase64: sig), "the image must verify against the built-in public key")

        let work = FileManager.default.temporaryDirectory.appendingPathComponent("updater-e2e-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: work, withIntermediateDirectories: true)
        // the install location is outside the work folder, which the swap script deletes when it is done
        let home = FileManager.default.temporaryDirectory.appendingPathComponent("updater-e2e-installed-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: home) }
        let installed = home.appendingPathComponent("Silica.app")
        try FileManager.default.createDirectory(at: home, withIntermediateDirectories: true)
        try ProcessRunner.run("/usr/bin/ditto", [oldApp, installed.path])
        let before = (NSDictionary(contentsOf: installed.appendingPathComponent("Contents/Info.plist"))?["CFBundleShortVersionString"] as? String) ?? ""
        XCTAssertNotEqual(before, versionText)

        let version = try XCTUnwrap(AppVersion(versionText))
        let fresh = try UpdateManager.extractApp(from: dmg, into: work, expecting: version, bundleIdentifier: "dev.betavk.launcher")
        let waiter = Process(); waiter.executableURL = URL(fileURLWithPath: "/usr/bin/true"); try waiter.run(); waiter.waitUntilExit()   // a process that has already exited
        try UpdateManager.handOver(newApp: fresh, replacing: installed, workDir: work, waitingFor: waiter.processIdentifier, reopen: false)

        let plist = installed.appendingPathComponent("Contents/Info.plist")
        for _ in 0..<50 {
            if (NSDictionary(contentsOf: plist)?["CFBundleShortVersionString"] as? String) == versionText { break }
            Thread.sleep(forTimeInterval: 0.1)
        }
        XCTAssertEqual(NSDictionary(contentsOf: plist)?["CFBundleShortVersionString"] as? String, versionText)
        XCTAssertFalse(FileManager.default.fileExists(atPath: installed.path + ".previous"), "the old copy is removed once the new one is in place")
        XCTAssertFalse(FileManager.default.fileExists(atPath: work.path), "the script cleans up its work folder")
    }
}
