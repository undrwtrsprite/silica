import Foundation

/// Privacy-safe anonymous online counter heartbeat.
/// Generates one random local UUID and sends only `{ id, state }` ("launcher" | "playing") to silicalauncher.xyz/api/players.
/// Never sends any Microsoft ID, username, hardware identifier, or world data, and respects Settings.shareAnonymousCount.
@MainActor
final class Telemetry {
    private static let endpoint = URL(string: "https://silicalauncher.xyz/api/players")!
    private static let clientKey = "silica.anonClientId"

    private let anonID: String
    private var task: Task<Void, Never>?
    private var enabled = true
    private var playing = false

    init() {
        if let saved = UserDefaults.standard.string(forKey: Self.clientKey), saved.count >= 8 {
            anonID = saved
        } else {
            let fresh = UUID().uuidString.lowercased()
            UserDefaults.standard.set(fresh, forKey: Self.clientKey)
            anonID = fresh
        }
    }

    func start(enabled: Bool, playing: Bool) {
        self.enabled = enabled
        self.playing = playing
        let args = CommandLine.arguments
        if args.contains("--snapshots") || args.contains("--selftest") { return }
        guard task == nil else { return }
        task = Task { [weak self] in
            while !Task.isCancelled {
                if let self, self.enabled {
                    await self.ping(state: self.playing ? "playing" : "launcher")
                }
                try? await Task.sleep(for: .seconds(90))
            }
        }
    }

    func update(enabled: Bool, playing: Bool) {
        let changed = (self.enabled != enabled) || (self.playing != playing)
        self.enabled = enabled
        self.playing = playing
        let args = CommandLine.arguments
        if changed, enabled, !args.contains("--snapshots"), !args.contains("--selftest") {
            Task { [weak self] in
                await self?.ping(state: playing ? "playing" : "launcher")
            }
        }
    }

    private func ping(state: String) async {
        var req = URLRequest(url: Self.endpoint)
        req.httpMethod = "POST"
        req.timeoutInterval = 8
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.httpBody = try? JSONSerialization.data(withJSONObject: ["id": anonID, "state": state])
        _ = try? await URLSession.shared.data(for: req)
    }
}
