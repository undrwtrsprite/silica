import AppKit
import AuthenticationServices
import CryptoKit
import Foundation
import Security

struct VerifiedMinecraftPlayer {
    let name: String
    let expiresAt: Date
    /// Minecraft profile id (32 hex digits) and the current Minecraft access token. The game needs both to join online-mode servers.
    /// The token is only ever held in memory; a player restored from the Keychain has no token until the background renewal finishes.
    let profileId: String?
    let accessToken: String?
    /// Addresses of the player's active skin and cape picture, straight from their Minecraft profile (so the game needs no lookup for its own player).
    let skinURL: String?
    let capeURL: String?

    fileprivate init(name: String, expiresAt: Date, profileId: String? = nil, accessToken: String? = nil, skinURL: String? = nil, capeURL: String? = nil) {
        self.name = name
        self.expiresAt = expiresAt
        self.profileId = profileId
        self.accessToken = accessToken
        self.skinURL = skinURL
        self.capeURL = capeURL
    }

    /// Rebuilds a player that was verified on an earlier run and saved in the Keychain, so Silica can open already signed in.
    /// The sign-in is renewed and the purchase re-checked in the background right after.
    init(restoringName name: String, expiresAt: Date, profileId: String?) {
        self.init(name: name, expiresAt: expiresAt, profileId: profileId, accessToken: nil)
    }

    var isValid: Bool { expiresAt > Date() }
}

@MainActor
final class MicrosoftSignIn: NSObject, ASWebAuthenticationPresentationContextProviding {
    nonisolated static let clientID = "c40801b4-6fb2-43f2-a04a-8c231aec90fa"
    static let scheme = "msauth.dev.betavk.launcher"
    static let redirect = "\(scheme)://auth"
    nonisolated static let scope = "XboxLive.signin offline_access"
    nonisolated static let authority = "https://login.microsoftonline.com/consumers/oauth2/v2.0"

    private var browser: ASWebAuthenticationSession?
    private var window: NSWindow?

    func signIn() async throws -> SignedInSession {
        let verifier = try Self.randomToken()
        let state = try Self.randomToken()
        let challenge = Self.base64URL(Data(SHA256.hash(data: Data(verifier.utf8))))
        var authorize = URLComponents(string: Self.authority + "/authorize")!
        authorize.queryItems = [
            .init(name: "client_id", value: Self.clientID),
            .init(name: "response_type", value: "code"),
            .init(name: "redirect_uri", value: Self.redirect),
            .init(name: "scope", value: Self.scope),
            .init(name: "state", value: state),
            .init(name: "code_challenge", value: challenge),
            .init(name: "code_challenge_method", value: "S256"),
            .init(name: "prompt", value: "select_account"),
        ]
        let callback = try await authenticate(at: authorize.url!)
        let code = try Self.authorizationCode(from: callback, state: state)

        var request = URLRequest(url: URL(string: Self.authority + "/token")!)
        request.httpMethod = "POST"
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        var form = URLComponents()
        form.queryItems = [
            .init(name: "client_id", value: Self.clientID),
            .init(name: "grant_type", value: "authorization_code"),
            .init(name: "code", value: code),
            .init(name: "redirect_uri", value: Self.redirect),
            .init(name: "code_verifier", value: verifier),
            .init(name: "scope", value: Self.scope),
        ]
        request.httpBody = Data(form.percentEncodedQuery!.replacingOccurrences(of: "+", with: "%2B").utf8)
        let services = MinecraftAccountService()
        let tokens = try await services.microsoftTokens(for: request)
        let player = try await services.verify(microsoftToken: tokens.access_token)
        return SignedInSession(player: player, refreshToken: tokens.refresh_token)
    }

    static func authorizationCode(from callback: URL, state: String) throws -> String {
        if let items = URLComponents(url: callback, resolvingAgainstBaseURL: false)?.queryItems,
           let error = items.first(where: { $0.name == "error" })?.value {
            let description = items.first(where: { $0.name == "error_description" })?.value?.replacingOccurrences(of: "+", with: " ")   // the callback encodes spaces as +
            throw AccountError.microsoftRefused(MicrosoftTokenError.summary(error: error, description: description))
        }
        guard let parts = URLComponents(url: callback, resolvingAgainstBaseURL: false) else {
            throw AccountError.microsoftRefused("the sign-in callback was not a valid address")
        }
        let items = parts.queryItems ?? []
        var problems: [String] = []
        if parts.scheme != scheme { problems.append("scheme is \(parts.scheme ?? "none")") }
        if parts.host != "auth" { problems.append("host is \(parts.host ?? "none")") }
        if !parts.path.isEmpty && parts.path != "/" { problems.append("path is \(parts.path)") }   // Microsoft hands back auth/ with a trailing slash
        if parts.fragment != nil { problems.append("has a #fragment") }
        let states = items.filter { $0.name == "state" }.map(\.value)
        if states != [state] { problems.append(states.isEmpty ? "no state returned" : "state did not match") }
        let codes = items.filter { $0.name == "code" }
        if codes.count != 1 { problems.append("\(codes.count) code values") }
        guard problems.isEmpty, let code = codes.first?.value, !code.isEmpty else {
            let names = items.map(\.name).joined(separator: ",")
            throw AccountError.microsoftRefused("unexpected sign-in callback (" + (problems.isEmpty ? "empty code" : problems.joined(separator: "; ")) + "; parameters: \(names.isEmpty ? "none" : names))")
        }
        return code
    }

    private func authenticate(at url: URL) async throws -> URL {
        guard let window = NSApp.keyWindow ?? NSApp.mainWindow else { throw AccountError.browser }
        self.window = window
        defer { browser = nil; self.window = nil }
        return try await withCheckedThrowingContinuation { continuation in
            let session = ASWebAuthenticationSession(url: url, callbackURLScheme: Self.scheme) { callback, error in
                if let callback {
                    continuation.resume(returning: callback)
                } else if (error as? ASWebAuthenticationSessionError)?.code == .canceledLogin {
                    continuation.resume(throwing: CancellationError())
                } else {
                    continuation.resume(throwing: AccountError.browser)
                }
            }
            session.presentationContextProvider = self
            browser = session
            if !session.start() { continuation.resume(throwing: AccountError.browser) }
        }
    }

    func presentationAnchor(for session: ASWebAuthenticationSession) -> ASPresentationAnchor {
        window ?? ASPresentationAnchor()
    }

    private static func randomToken() throws -> String {
        var bytes = Data(count: 32)
        let status = bytes.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 32, $0.baseAddress!) }
        guard status == errSecSuccess else { throw AccountError.browser }
        return base64URL(bytes)
    }

    private static func base64URL(_ data: Data) -> String {
        data.base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }

}

/// Microsoft's own reason for refusing a sign-in or token request (an error code and text such as AADSTS70000: ...). Contains no secrets.
enum MicrosoftTokenError {
    static func summary(error: String, description: String?) -> String {
        let text = description ?? error
        let firstLine = text.split(whereSeparator: \.isNewline).first.map(String.init) ?? text
        return String(firstLine.prefix(240))
    }
}

struct MicrosoftTokens: Decodable {
    let access_token: String
    let refresh_token: String?
}

/// A verified player plus the refresh token that lets Silica sign in again without the browser.
struct SignedInSession {
    let player: VerifiedMinecraftPlayer
    let refreshToken: String?
}

struct MinecraftAccountService {
    private let session: URLSession

    init(session: URLSession = URLSession(configuration: .ephemeral)) {
        self.session = session
    }

    func verify(microsoftToken: String) async throws -> VerifiedMinecraftPlayer {
        let xbox: XboxToken = try await post("https://user.auth.xboxlive.com/user/authenticate", [
            "Properties": ["AuthMethod": "RPS", "SiteName": "user.auth.xboxlive.com", "RpsTicket": "d=\(microsoftToken)"],
            "RelyingParty": "http://auth.xboxlive.com", "TokenType": "JWT",
        ], failure: .xbox)
        let xsts: XboxToken = try await post("https://xsts.auth.xboxlive.com/xsts/authorize", [
            "Properties": ["SandboxId": "RETAIL", "UserTokens": [xbox.Token]],
            "RelyingParty": "rp://api.minecraftservices.com/", "TokenType": "JWT",
        ], failure: .xbox)
        guard let hash = xsts.DisplayClaims?.xui.first?.uhs, !hash.isEmpty else { throw AccountError.xbox }
        let minecraft: MinecraftToken = try await post(Self.base + "/authentication/login_with_xbox", [
            "identityToken": "XBL3.0 x=\(hash);\(xsts.Token)",
        ], failure: .minecraft)
        let expiresAt = Date().addingTimeInterval(minecraft.expires_in)
        guard !minecraft.access_token.isEmpty, minecraft.expires_in > 0 else { throw AccountError.minecraft }

        // the purchase check and the profile do not depend on each other: ask for both at once
        async let entitlementsReply: Entitlements = get("/entitlements/license?requestId=\(UUID().uuidString)", token: minecraft.access_token)
        async let profileReply: Profile = get("/minecraft/profile", token: minecraft.access_token)
        let entitlements = try await entitlementsReply
        guard entitlements.items.contains(where: { $0.name == "product_minecraft" }) else { throw AccountError.ownership }
        let profile = try await profileReply
        guard profile.name.range(of: "^[A-Za-z0-9_]{1,16}$", options: .regularExpression) != nil else {
            throw AccountError.profile
        }
        return VerifiedMinecraftPlayer(name: profile.name, expiresAt: expiresAt, profileId: profile.id, accessToken: minecraft.access_token,
                                       skinURL: profile.skins?.first { $0.state == "ACTIVE" }?.url, capeURL: profile.capes?.first { $0.state == "ACTIVE" }?.url)
    }


    /// Signs in again from a stored refresh token. Microsoft refusing the token is `AccountError.microsoft`; a network or server
    /// problem is a plain URLError, so the caller can tell "sign in again" from "try later".
    func refresh(refreshToken: String) async throws -> SignedInSession {
        guard let url = URL(string: MicrosoftSignIn.authority + "/token") else { throw AccountError.microsoft }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 30
        request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
        var form = URLComponents()
        form.queryItems = [
            .init(name: "client_id", value: MicrosoftSignIn.clientID),
            .init(name: "grant_type", value: "refresh_token"),
            .init(name: "refresh_token", value: refreshToken),
            .init(name: "scope", value: MicrosoftSignIn.scope),
        ]
        guard let encoded = form.percentEncodedQuery else { throw AccountError.microsoft }
        request.httpBody = Data(encoded.replacingOccurrences(of: "+", with: "%2B").utf8)
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw URLError(.badServerResponse) }
        if http.statusCode == 400 || http.statusCode == 401 { throw AccountError.microsoft }
        guard (200..<300).contains(http.statusCode) else { throw URLError(.badServerResponse) }
        guard let tokens = try? JSONDecoder().decode(MicrosoftTokens.self, from: data) else { throw AccountError.microsoft }
        let player = try await verify(microsoftToken: tokens.access_token)
        return SignedInSession(player: player, refreshToken: tokens.refresh_token ?? refreshToken)
    }

    private static let base = "https://api.minecraftservices.com"

    private func post<T: Decodable>(_ url: String, _ body: [String: Any], failure: AccountError) async throws -> T {
        var request = URLRequest(url: URL(string: url)!)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        return try await send(request, failure: failure)
    }

    private func get<T: Decodable>(_ path: String, token: String) async throws -> T {
        var request = URLRequest(url: URL(string: Self.base + path)!)
        request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        return try await send(request, failure: path == "/minecraft/profile" ? .profile : .verification)
    }


    /// The authorization-code exchange. A refusal carries Microsoft's explanation so the sign-in screen can show what is wrong.
    func microsoftTokens(for request: URLRequest) async throws -> MicrosoftTokens {
        var request = request
        request.timeoutInterval = 30
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw AccountError.microsoft }
        guard (200..<300).contains(http.statusCode) else {
            struct Refusal: Decodable { let error: String?; let error_description: String? }
            if let refusal = try? JSONDecoder().decode(Refusal.self, from: data), let error = refusal.error {
                throw AccountError.microsoftRefused(MicrosoftTokenError.summary(error: error, description: refusal.error_description))
            }
            throw AccountError.microsoftRefused("the token request returned HTTP \(http.statusCode)")
        }
        guard let tokens = try? JSONDecoder().decode(MicrosoftTokens.self, from: data) else {
            throw AccountError.microsoftRefused("the token reply had no access token")
        }
        return tokens
    }

    fileprivate func send<T: Decodable>(_ request: URLRequest, failure: AccountError) async throws -> T {
        var request = request
        request.timeoutInterval = 30
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let (data, response) = try await session.data(for: request)
        guard let http = response as? HTTPURLResponse else { throw failure }
        if failure == .minecraft && http.statusCode == 403 { throw AccountError.minecraftAccess }
        guard (200..<300).contains(http.statusCode), let value = try? JSONDecoder().decode(T.self, from: data) else {
            throw failure
        }
        return value
    }

    private struct XboxToken: Decodable {
        struct Claims: Decodable {
            struct User: Decodable { let uhs: String }
            let xui: [User]
        }
        let Token: String
        let DisplayClaims: Claims?
    }

    private struct MinecraftToken: Decodable {
        let access_token: String
        let expires_in: Double
    }

    private struct Entitlements: Decodable {
        struct Item: Decodable { let name: String }
        let items: [Item]
    }

    private struct Profile: Decodable {
        struct Cosmetic: Decodable { let state: String?; let url: String? }
        let name: String
        let id: String?
        let skins: [Cosmetic]?
        let capes: [Cosmetic]?
    }
}

enum AccountError: LocalizedError, Equatable {
    case browser, microsoft, xbox, minecraft, minecraftAccess, verification, ownership, profile
    case microsoftRefused(String)

    var errorDescription: String? {
        switch self {
        case .browser: return "Could not open Microsoft sign-in. Try again."
        case .microsoft: return "Microsoft sign-in could not be completed."
        case .microsoftRefused(let detail): return "Microsoft refused the sign-in: \(detail)"
        case .xbox: return "Xbox sign-in failed. Check that your Microsoft account has an Xbox profile."
        case .minecraft: return "Minecraft Services could not sign you in."
        case .minecraftAccess: return "Minecraft Services refused Silica’s sign-in request. Silica needs Minecraft Services approval before it can verify ownership."
        case .verification: return "Could not check Minecraft ownership. Try again."
        case .ownership: return "This account has no verified Minecraft: Java Edition purchase. Sign in with the account that owns Java Edition."
        case .profile: return "Could not load your Java Edition profile. If you just bought Minecraft, set up your profile in the official launcher first."
        }
    }
}
