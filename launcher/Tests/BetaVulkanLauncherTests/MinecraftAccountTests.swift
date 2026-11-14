import Foundation
import XCTest
@testable import BetaVulkanLauncher

final class MinecraftAccountTests: XCTestCase {
    @MainActor
    func testCallbackMustMatchStateAndRedirect() throws {
        let redirect = MicrosoftSignIn.redirect
        XCTAssertEqual(try MicrosoftSignIn.authorizationCode(
            from: URL(string: redirect + "?state=expected&code=test")!, state: "expected"), "test")
        // Microsoft hands the callback back with a trailing slash after "auth"
        XCTAssertEqual(try MicrosoftSignIn.authorizationCode(
            from: URL(string: redirect + "/?code=test&state=expected")!, state: "expected"), "test")

        for callback in [
            redirect + "?state=wrong&code=test",
            redirect + "?state=expected&state=wrong&code=test",
            redirect + "?state=expected&code=test&code=another",
            redirect + "/elsewhere?state=expected&code=test",
        ] {
            XCTAssertThrowsError(try MicrosoftSignIn.authorizationCode(from: URL(string: callback)!, state: "expected"))
        }
    }

    @MainActor
    func testOwnershipAndProfileAreRequired() async throws {
        for (fixture, expected): (String, AccountError) in [
            ("no-purchase", .ownership),
            ("no-profile", .profile),
            ("unapproved", .minecraftAccess),
            ("unavailable", .verification),
        ] {
            do {
                _ = try await service().verify(microsoftToken: fixture)
                XCTFail("Unexpected verification for \(fixture)")
            } catch let error as AccountError {
                XCTAssertEqual(error, expected)
            }
        }
    }

    @MainActor
    func testRunnerRequiresVerifiedPlayerAndUsesProfileName() async throws {
        let runner = GameRunner()
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        try Data(#"{"mainClass":"Test","classpath":[],"jvmArgs":[]}"#.utf8)
            .write(to: folder.appendingPathComponent("launch.json"))

        var settings = Settings()
        settings.stageDir = folder.path
        settings.gameDir = folder.path
        let java = URL(fileURLWithPath: "/usr/bin/true")
        XCTAssertEqual(runner.launch(settings: settings, java: java, player: nil), GameRunner.signInRequired)
        XCTAssertFalse(runner.isRunning)
        XCTAssertTrue(runner.lines.isEmpty)

        let player = try await service().verify(microsoftToken: "owner")
        let exit: Int32 = await withCheckedContinuation { continuation in
            if let error = runner.launch(settings: settings, java: java, player: player,
                                         completion: { continuation.resume(returning: $0) }) {
                XCTFail(error)
                continuation.resume(returning: -1)
            }
        }
        XCTAssertEqual(exit, 0)
        XCTAssertTrue(runner.lines.contains { $0.contains("--username VerifiedPlayer") })
        XCTAssertFalse(runner.lines.contains { $0.contains("fixture-token") })
    }

    // MARK: saved accounts

    private func accounts(_ list: [StoredAccount], active: String?) -> StoredAccounts { StoredAccounts(active: active, accounts: list) }
    private func account(_ id: String, _ name: String, token: String, expires: TimeInterval = 3600) -> StoredAccount {
        StoredAccount(profileId: id, name: name, expiresAt: Date().addingTimeInterval(expires), refreshToken: token)
    }

    @MainActor
    func testRestoreSignsInAgainAndKeepsTheNewestToken() async {
        let store = MemoryAccountStore(accounts([account("a1", "Old", token: "owner-old")], active: "a1"))
        guard case .restored(let player) = await SessionRestorer(store: store, service: service()).restore() else {
            return XCTFail("Expected the session to be restored")
        }
        XCTAssertEqual(player.name, "VerifiedPlayer")
        XCTAssertEqual(store.saved.accounts.first?.refreshToken, "rotated-token")
        XCTAssertEqual(store.saved.accounts.first?.name, "VerifiedPlayer")
    }

    @MainActor
    func testNothingSavedMeansSignedOut() async {
        guard case .none = await SessionRestorer(store: MemoryAccountStore(StoredAccounts()), service: service()).restore() else {
            return XCTFail("Expected no session")
        }
    }

    @MainActor
    func testRevokedAccountIsRemovedAndTheOthersStay() async {
        let store = MemoryAccountStore(accounts([account("a1", "One", token: "revoked-1"), account("a2", "Two", token: "owner-2")], active: "a1"))
        guard case .rejected = await SessionRestorer(store: store, service: service()).restore() else {
            return XCTFail("Expected the account to be rejected")
        }
        XCTAssertEqual(store.saved.accounts.map(\.profileId), ["a2"])
        XCTAssertNil(store.saved.active)
    }

    @MainActor
    func testLostPurchaseRemovesThatAccount() async {
        let store = MemoryAccountStore(accounts([account("a1", "One", token: "no-purchase-1")], active: "a1"))
        guard case .rejected = await SessionRestorer(store: store, service: service()).restore() else {
            return XCTFail("Expected the account to be rejected")
        }
        XCTAssertTrue(store.saved.accounts.isEmpty)
    }

    @MainActor
    func testServiceProblemKeepsTheAccount() async {
        let store = MemoryAccountStore(accounts([account("a1", "One", token: "flaky-1")], active: "a1"))
        guard case .unavailable = await SessionRestorer(store: store, service: service()).restore() else {
            return XCTFail("Expected the service to be unavailable")
        }
        XCTAssertEqual(store.saved.accounts.first?.refreshToken, "flaky-1")
    }

    @MainActor
    func testRenewingAnotherAccountLeavesTheActiveOneAlone() async {
        let store = MemoryAccountStore(accounts([account("a1", "One", token: "owner-1"), account("a2", "Two", token: "owner-2")], active: "a1"))
        guard case .restored = await SessionRestorer(store: store, service: service()).restore(id: "a2") else {
            return XCTFail("Expected the session to be restored")
        }
        XCTAssertEqual(store.saved.active, "a1")
        XCTAssertEqual(store.saved.accounts.first { $0.profileId == "a2" }?.refreshToken, "rotated-token")
        XCTAssertEqual(store.saved.accounts.first { $0.profileId == "a1" }?.refreshToken, "owner-1")
    }

    func testSigningInAddsAccountsAndTheNewestBecomesActive() async throws {
        var saved = StoredAccounts()
        let first = try await service().verify(microsoftToken: "owner")
        saved.upsert(first, refreshToken: "t1")
        XCTAssertEqual(saved.accounts.count, 1)
        XCTAssertEqual(saved.active, first.profileId)
        // the same account again updates it instead of adding a second one
        saved.upsert(first, refreshToken: "t2")
        XCTAssertEqual(saved.accounts.count, 1)
        XCTAssertEqual(saved.accounts.first?.refreshToken, "t2")
        // an account that cannot be signed in again later (no refresh token) is not saved
        var empty = StoredAccounts()
        empty.upsert(first, refreshToken: nil)
        XCTAssertTrue(empty.accounts.isEmpty)
    }

    func testRemovingTheActiveAccountLeavesNobodyActive() {
        var saved = accounts([account("a1", "One", token: "t"), account("a2", "Two", token: "t")], active: "a1")
        saved.remove("a1")
        XCTAssertEqual(saved.accounts.map(\.profileId), ["a2"])
        XCTAssertNil(saved.active)
        saved.remove("a2")
        XCTAssertTrue(saved.accounts.isEmpty)
    }

    func testTheOldSingleAccountBecomesTheFirstSavedAccount() {
        let expires = Date().addingTimeInterval(100)
        let migrated = StoredAccounts.fromLegacy(token: "t", name: "Alex", profileId: "abc", expiresAt: expires)
        XCTAssertEqual(migrated.active, "abc")
        XCTAssertEqual(migrated.accounts, [StoredAccount(profileId: "abc", name: "Alex", expiresAt: expires, refreshToken: "t")])
        XCTAssertEqual(StoredAccounts.fromLegacy(token: "t", name: "Alex", profileId: nil, expiresAt: expires).active, "alex")
    }

    @MainActor
    func testSavedActiveAccountOpensSignedInWithoutTheNetwork() {
        let restorer = SessionRestorer(store: MemoryAccountStore(accounts([account("a1", "Alex", token: "t")], active: "a1")), service: service())
        XCTAssertEqual(restorer.instantPlayer()?.name, "Alex")
        XCTAssertEqual(restorer.instantPlayer()?.profileId, "a1")
        XCTAssertNil(restorer.instantPlayer()?.accessToken)   // the token comes with the background renewal
    }

    @MainActor
    func testExpiredOrInactiveAccountIsNotUsedInstantly() {
        let expired = accounts([account("a1", "Alex", token: "t", expires: -10)], active: "a1")
        XCTAssertNil(SessionRestorer(store: MemoryAccountStore(expired), service: service()).instantPlayer())
        let nobody = accounts([account("a1", "Alex", token: "t")], active: nil)
        XCTAssertNil(SessionRestorer(store: MemoryAccountStore(nobody), service: service()).instantPlayer())
    }

    @MainActor
    func testMicrosoftsOwnErrorReachesTheMessage() {
        let callback = URL(string: MicrosoftSignIn.redirect + "?error=invalid_request&error_description=AADSTS50011%3A+The+redirect+URI+does+not+match&state=s")!
        XCTAssertThrowsError(try MicrosoftSignIn.authorizationCode(from: callback, state: "s")) { error in
            XCTAssertEqual((error as? AccountError)?.errorDescription,
                           "Microsoft refused the sign-in: AADSTS50011: The redirect URI does not match")
        }
    }

    func testSignInCarriesTheProfileIdAndToken() async throws {
        let player = try await service().verify(microsoftToken: "owner")
        XCTAssertEqual(player.profileId, "0123456789abcdef0123456789abcdef")
        XCTAssertEqual(player.accessToken, "fixture-token-owner")
        XCTAssertEqual(player.skinURL, "http://textures.minecraft.net/texture/skin")   // the active skin, not the old one
        XCTAssertEqual(player.capeURL, "http://textures.minecraft.net/texture/cape")
    }

    func testSignInAsksForOfflineAccess() {
        XCTAssertTrue(MicrosoftSignIn.scope.split(separator: " ").contains("offline_access"))
    }

    private func service() -> MinecraftAccountService {
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [AccountFixtureProtocol.self]
        return MinecraftAccountService(session: URLSession(configuration: config))
    }
}

private final class AccountFixtureProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func stopLoading() {}

    override func startLoading() {
        let url = request.url!
        let body = request.httpBodyStream.map { stream -> String in
            stream.open()
            defer { stream.close() }
            var bytes = [UInt8](repeating: 0, count: 4096)
            let count = stream.read(&bytes, maxLength: bytes.count)
            return String(decoding: bytes.prefix(max(0, count)), as: UTF8.self)
        } ?? String(decoding: request.httpBody ?? Data(), as: UTF8.self)
        let auth = request.value(forHTTPHeaderField: "Authorization") ?? ""
        let fixtures = ["owner", "no-purchase", "no-profile", "unapproved", "unavailable", "revoked", "flaky"]
        let fixture = fixtures.first { (body + auth).contains($0) } ?? "unknown"
        let reply: (Int, String)
        switch (url.host, url.path) {
        case ("login.microsoftonline.com", "/consumers/oauth2/v2.0/token"):
            switch fixture {
            case "revoked": reply = (400, #"{"error":"invalid_grant"}"#)
            case "flaky": reply = (503, "{}")
            default: reply = (200, #"{"access_token":"\#(fixture)-access","refresh_token":"rotated-token"}"#)
            }
        case ("user.auth.xboxlive.com", "/user/authenticate"):
            reply = (200, #"{"Token":"fixture-token-\#(fixture)"}"#)
        case ("xsts.auth.xboxlive.com", "/xsts/authorize"):
            reply = (200, #"{"Token":"fixture-token-\#(fixture)","DisplayClaims":{"xui":[{"uhs":"test"}]}}"#)
        case ("api.minecraftservices.com", "/authentication/login_with_xbox"):
            reply = fixture == "unapproved" ? (403, "{}") : (200, #"{"access_token":"fixture-token-\#(fixture)","expires_in":3600}"#)
        case ("api.minecraftservices.com", "/entitlements/license"):
            switch fixture {
            case "unavailable": reply = (503, "{}")
            case "no-purchase": reply = (200, #"{"items":[{"name":"game_minecraft"}]}"#)
            case "owner", "no-profile": reply = (200, #"{"items":[{"name":"product_minecraft"}]}"#)
            default: reply = (400, "{}")
            }
        case ("api.minecraftservices.com", "/minecraft/profile"):
            reply = fixture == "owner" ? (200, #"{"name":"VerifiedPlayer","id":"0123456789abcdef0123456789abcdef","skins":[{"state":"INACTIVE","url":"http://textures.minecraft.net/texture/old"},{"state":"ACTIVE","url":"http://textures.minecraft.net/texture/skin"}],"capes":[{"state":"ACTIVE","url":"http://textures.minecraft.net/texture/cape"}]}"#) : (404, "{}")
        default:
            reply = (404, "{}")
        }
        let response = HTTPURLResponse(url: url, statusCode: reply.0, httpVersion: nil, headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(reply.1.utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
}

private final class MemoryAccountStore: AccountStore {
    var saved: StoredAccounts
    init(_ saved: StoredAccounts) { self.saved = saved }
    func load() -> StoredAccounts { saved }
    func save(_ accounts: StoredAccounts) { saved = accounts }
}
