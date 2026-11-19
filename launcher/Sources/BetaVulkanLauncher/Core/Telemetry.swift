import Foundation

/// Privacy-safe anonymous telemetry heartbeat.
/// Sends only a random local UUID plus anonymous state/runtime fields (`state`, `backend`, `version`, `chip`, `macos`)
/// to silicalauncher.xyz/api/players. Never sends Microsoft IDs, usernames, hardware serials, or world data.
@MainActor
final class Telemetry {
    private static let endpoint = URL(string: "https://silicalauncher.xyz/api/players")!
    private static let clientKey = "silica.anonClientId"

    private let anonID: String
    private let chipFamily: String
    private let macosMajor: String
    private var task: Task<Void, Never>?
    private var enabled = true
    private var playing = false
    private var backend = "vulkan"
    private var gameVersion = "b1.7.3"
    private var playingActivity: NSObjectProtocol?

    init() {
        if let saved = UserDefaults.standard.string(forKey: Self.clientKey), saved.count >= 8 {
            anonID = saved
        } else {
            let fresh = UUID().uuidString.lowercased()
            UserDefaults.standard.set(fresh, forKey: Self.clientKey)
            anonID = fresh
        }
        chipFamily = Self.detectChipFamily()
        macosMajor = "macOS \(ProcessInfo.processInfo.operatingSystemVersion.majorVersion)"
    }

    private static func detectChipFamily() -> String {
        var size = 0
        sysctlbyname("machdep.cpu.brand_string", nil, &size, nil, 0)
        guard size > 0 else { return "Apple Silicon" }
        var buf = [CChar](repeating: 0, count: size)
        sysctlbyname("machdep.cpu.brand_string", &buf, &size, nil, 0)
        let brand = String(cString: buf)
        for gen in ["M5", "M4", "M3", "M2", "M1"] where brand.contains(gen) { return gen }
        return "Apple Silicon"
    }

    func start(enabled: Bool, playing: Bool, backend: String, gameVersion: String = "b1.7.3") {
        self.enabled = enabled
        self.playing = playing
        self.backend = backend
        self.gameVersion = gameVersion
        syncAppNapAssertion()
        let args = CommandLine.arguments
        if args.contains("--snapshots") || args.contains("--selftest") { return }
        guard task == nil else { return }
        task = Task { [weak self] in
            while !Task.isCancelled {
                if let self, self.enabled {
                    await self.ping()
                }
                try? await Task.sleep(for: .seconds(90))
            }
        }
    }

    func update(enabled: Bool, playing: Bool, backend: String, gameVersion: String = "b1.7.3") {
        let changed = (self.enabled != enabled) || (self.playing != playing) || (self.backend != backend) || (self.gameVersion != gameVersion)
        self.enabled = enabled
        self.playing = playing
        self.backend = backend
        self.gameVersion = gameVersion
        syncAppNapAssertion()
        let args = CommandLine.arguments
        if changed, enabled, !args.contains("--snapshots"), !args.contains("--selftest") {
            Task { [weak self] in await self?.ping() }
        }
    }

    /// Prevents macOS App Nap from throttling the 90s heartbeat timer while Silica is occluded behind fullscreen Minecraft.
    private func syncAppNapAssertion() {
        let active = enabled && playing
        if active, playingActivity == nil {
            playingActivity = ProcessInfo.processInfo.beginActivity(
                options: .userInitiatedAllowingIdleSystemSleep,
                reason: "Sending active session telemetry while Minecraft is running"
            )
        } else if !active, let activity = playingActivity {
            ProcessInfo.processInfo.endActivity(activity)
            playingActivity = nil
        }
    }

    private func ping() async {
        var req = URLRequest(url: Self.endpoint)
        req.httpMethod = "POST"
        req.timeoutInterval = 8
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        let payload: [String: String] = [
            "id": anonID,
            "state": playing ? "playing" : "launcher",
            "backend": backend,
            "version": UpdateManager.currentVersionText,
            "gameVersion": gameVersion,
            "chip": chipFamily,
            "macos": macosMajor
        ]
        req.httpBody = try? JSONSerialization.data(withJSONObject: payload)
        _ = try? await URLSession.shared.data(for: req)
    }
}
