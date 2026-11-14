import Foundation
import Security

/// One saved Microsoft account: who it is, when its Minecraft token runs out, and the refresh token that signs it in again.
struct StoredAccount: Codable, Equatable, Identifiable {
    let profileId: String
    var name: String
    var expiresAt: Date
    var refreshToken: String

    var id: String { profileId }
}

/// Every saved account and which one is in use. All of it lives in one Keychain item, so macOS asks for permission once.
struct StoredAccounts: Codable, Equatable {
    var active: String?
    var accounts: [StoredAccount] = []

    var activeAccount: StoredAccount? { accounts.first { $0.profileId == active } }

    /// Adds or updates the account of a freshly verified player and makes it the active one. Without a refresh token an existing account
    /// keeps its old one, and a new account is not saved (nothing could sign it in again).
    mutating func upsert(_ player: VerifiedMinecraftPlayer, refreshToken: String?) {
        let id = player.profileId ?? player.name.lowercased()
        if let index = accounts.firstIndex(where: { $0.profileId == id }) {
            accounts[index].name = player.name
            accounts[index].expiresAt = player.expiresAt
            if let refreshToken { accounts[index].refreshToken = refreshToken }
        } else if let refreshToken {
            accounts.append(StoredAccount(profileId: id, name: player.name, expiresAt: player.expiresAt, refreshToken: refreshToken))
        } else {
            return
        }
        active = id
    }

    /// Forgets an account. If it was the active one, nobody is active afterwards.
    mutating func remove(_ id: String) {
        accounts.removeAll { $0.profileId == id }
        if active == id { active = nil }
    }

    /// The single account the app used to keep (a refresh token and a cached player in two Keychain items) becomes the first saved account.
    static func fromLegacy(token: String, name: String, profileId: String?, expiresAt: Date) -> StoredAccounts {
        let id = profileId ?? name.lowercased()
        return StoredAccounts(active: id, accounts: [StoredAccount(profileId: id, name: name, expiresAt: expiresAt, refreshToken: token)])
    }
}

protocol AccountStore {
    func load() -> StoredAccounts
    func save(_ accounts: StoredAccounts)
}

/// The macOS Keychain, readable only on this Mac and only while it is unlocked.
struct KeychainAccountStore: AccountStore {
    private static let service = "dev.betavk.launcher.microsoft"

    private func query(_ account: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: Self.service, kSecAttrAccount as String: account]
    }

    func load() -> StoredAccounts {
        if let data = read("accounts"), let accounts = try? JSONDecoder().decode(StoredAccounts.self, from: data) { return accounts }
        return migrateLegacy()
    }

    func save(_ accounts: StoredAccounts) {
        if accounts.accounts.isEmpty {
            SecItemDelete(query("accounts") as CFDictionary)
            return
        }
        guard let data = try? JSONEncoder().encode(accounts) else { return }
        SecItemDelete(query("accounts") as CFDictionary)
        var item = query("accounts")
        item[kSecValueData as String] = data
        item[kSecAttrAccessible as String] = kSecAttrAccessibleWhenUnlockedThisDeviceOnly
        SecItemAdd(item as CFDictionary, nil)
    }

    /// Earlier versions kept one account in two items (a refresh token and the last player). Move it into the new item, once.
    private func migrateLegacy() -> StoredAccounts {
        struct LegacyPlayer: Decodable { let name: String; let expiresAt: Date; let profileId: String? }
        guard let tokenData = read("refresh-token"), let token = String(data: tokenData, encoding: .utf8),
              let playerData = read("player"), let player = try? JSONDecoder().decode(LegacyPlayer.self, from: playerData) else { return StoredAccounts() }
        let migrated = StoredAccounts.fromLegacy(token: token, name: player.name, profileId: player.profileId, expiresAt: player.expiresAt)
        save(migrated)
        SecItemDelete(query("refresh-token") as CFDictionary)
        SecItemDelete(query("player") as CFDictionary)
        return migrated
    }

    private func read(_ account: String) -> Data? {
        var request = query(account)
        request[kSecReturnData as String] = true
        request[kSecMatchLimit as String] = kSecMatchLimitOne
        var found: CFTypeRef?
        guard SecItemCopyMatching(request as CFDictionary, &found) == errSecSuccess, let data = found as? Data else { return nil }
        return data
    }
}

enum SessionRestore {
    case none                                   // that account is not saved
    case restored(VerifiedMinecraftPlayer)
    case rejected                               // Microsoft or the purchase check said no: the account was removed
    case unavailable                            // offline or a service problem: the account is kept for next time
}

/// Signs a saved account in again from its refresh token, and keeps the newest token Microsoft hands back.
/// Main-actor, so reading and writing the saved accounts never overlap.
@MainActor
struct SessionRestorer {
    let store: AccountStore
    let service: MinecraftAccountService

    /// The active account, if its Minecraft token has not expired: no network needed, so the app opens signed in.
    func instantPlayer(now: Date = Date()) -> VerifiedMinecraftPlayer? {
        guard let account = store.load().activeAccount, account.expiresAt > now.addingTimeInterval(60) else { return nil }
        return VerifiedMinecraftPlayer(restoringName: account.name, expiresAt: account.expiresAt, profileId: account.profileId)
    }

    /// Renews the account `id` (the active one when nil).
    func restore(id: String? = nil) async -> SessionRestore {
        let saved = store.load()
        guard let account = saved.accounts.first(where: { $0.profileId == (id ?? saved.active) }) else { return .none }
        do {
            let session = try await service.refresh(refreshToken: account.refreshToken)
            var latest = store.load()   // read again: the user may have added or removed accounts while the network call ran
            guard let index = latest.accounts.firstIndex(where: { $0.profileId == account.profileId }) else { return .none }
            latest.accounts[index].name = session.player.name
            latest.accounts[index].expiresAt = session.player.expiresAt
            if let newest = session.refreshToken { latest.accounts[index].refreshToken = newest }
            store.save(latest)
            return .restored(session.player)
        } catch let error as AccountError {
            switch error {
            case .microsoft, .ownership:
                var latest = store.load()
                latest.remove(account.profileId)
                store.save(latest)
                return .rejected
            default:
                return .unavailable
            }
        } catch {
            return .unavailable
        }
    }
}
