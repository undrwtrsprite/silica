import Foundation

struct LaunchConfig: Decodable {
    let mainClass: String
    let classpath: [String]
    let jvmArgs: [String]
    var prepMain: String? = nil
}

/// Thread-safe line buffer: the pipe handlers append from background queues; the main actor drains it on a timer so the UI
/// never does per-line work.
final class LineBuffer: @unchecked Sendable {
    private let lock = NSLock()
    private var pending: [String] = []
    private var partial = ""

    func append(_ data: Data) {
        let text = partial + String(decoding: data, as: UTF8.self)
        var parts = text.components(separatedBy: "\n")
        partial = parts.removeLast()
        lock.lock(); pending.append(contentsOf: parts); lock.unlock()
    }

    func drain() -> [String] {
        lock.lock(); defer { lock.unlock() }
        let p = pending
        pending.removeAll(keepingCapacity: true)
        return p
    }
}

@MainActor
final class GameRunner: ObservableObject {
    @Published private(set) var isRunning = false
    @Published private(set) var lines: [String] = []
    @Published private(set) var hints: [CrashHint] = []
    @Published private(set) var lastExit: Int32?

    private var process: Process?
    private let buffer = LineBuffer()
    private var timer: Timer?
    private let maxLines = 5000

    static let signInRequired = "Sign in with a Microsoft account that owns Minecraft: Java Edition before launching the game."

    static func stageConfig(_ stageDir: String) -> LaunchConfig? {
        let url = URL(fileURLWithPath: stageDir).appendingPathComponent("launch.json")
        guard let data = try? Data(contentsOf: url) else { return nil }
        return try? JSONDecoder().decode(LaunchConfig.self, from: data)
    }

    func clearLog() { lines.removeAll(); hints.removeAll() }

    /// Builds and starts the game. `extraJVM` let the benchmark runner reuse this.
    func launch(settings: Settings, java: URL, player: VerifiedMinecraftPlayer?, extraJVM: [String] = [], jar: JarLibrary.Selection? = nil, gameDirOverride: String? = nil, completion: (@MainActor (Int32) -> Void)? = nil) -> String? {
        guard let player, player.isValid else { return Self.signInRequired }
        guard !isRunning else { return "The game is already running." }
        guard let cfg = Self.stageConfig(settings.stageDir) else { return "No staged game build in \(settings.stageDir). Run `gradle :hooks:stageGame` first, or pick the folder in Settings." }
        let stage = URL(fileURLWithPath: settings.stageDir)
        // our hook classes first, then your prepared jar and its resources; the agent patches the game's classes as they load
        let cp = (cfg.classpath.map { stage.appendingPathComponent($0).path } + [(jar?.readable ?? AppPaths.readableJar).path, (jar?.resources ?? AppPaths.resources).path]).joined(separator: ":")
        var jvm = cfg.jvmArgs.map { $0.replacingOccurrences(of: "${stage}", with: stage.path) }
        jvm += settings.gcProfile.flags(memoryMB: settings.memoryMB)
        jvm += ["-Dbetavk.backend=\(settings.backend.rawValue)", "-Dbetavk.assets=\(AppPaths.assets.path)",
                "-Dbetavk.meshworkers=\(settings.meshWorkers)", "-Dbetavk.genworkers=\(settings.genWorkers)"]
        if settings.backend == .vulkan && !settings.vsync { jvm.append("-Dbetavk.uncapped=true") }
        if settings.showSkins == false { jvm.append("-Dbetavk.skins=off") }
        jvm += extraJVM
        // Each instance keeps its own game directory so worlds and configs never mix.
        let gameDir = gameDirOverride ?? jar?.gameDir ?? settings.gameDir
        var args = ["--username", player.name, "--gamedir", gameDir,
                    "--width", String(settings.width), "--height", String(settings.height)]
        if settings.fullscreen { args.append("--fullscreen") }
        if settings.vsync { args.append("--vsync") }

        AppPaths.ensure(URL(fileURLWithPath: gameDir))
        let p = Process()
        p.executableURL = java
        p.arguments = jvm + ["-cp", cp, cfg.mainClass] + args
        p.currentDirectoryURL = URL(fileURLWithPath: gameDir)
        // the sign-in goes to the game through its environment, not its command line, which other programs can read; it is only used to join online-mode servers
        if let token = player.accessToken, let profileId = player.profileId {
            var environment = ProcessInfo.processInfo.environment
            environment["BETAVK_ACCESS_TOKEN"] = token
            environment["BETAVK_PROFILE_ID"] = profileId
            p.environment = environment
        }
        // your own skin and cape come from your profile, so the game does not need to look them up
        if player.skinURL != nil || player.capeURL != nil {
            var environment = p.environment ?? ProcessInfo.processInfo.environment
            if let skin = player.skinURL { environment["BETAVK_SKIN_URL"] = skin }
            if let cape = player.capeURL { environment["BETAVK_CAPE_URL"] = cape }
            p.environment = environment
        }
        let pipe = Pipe()
        p.standardOutput = pipe
        p.standardError = pipe
        let buf = buffer
        pipe.fileHandleForReading.readabilityHandler = { h in
            let d = h.availableData
            if d.isEmpty { h.readabilityHandler = nil } else { buf.append(d) }
        }
        p.terminationHandler = { [weak self] proc in
            Task { @MainActor in self?.finished(proc.terminationStatus, completion) }
        }
        lines.removeAll()
        hints.removeAll()
        lastExit = nil
        lines.append("$ \(java.path) \((jvm + ["-cp", "…", cfg.mainClass] + args).joined(separator: " "))")
        do { try p.run() } catch { return "Could not start Java: \(error.localizedDescription)" }
        process = p
        isRunning = true
        timer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in Task { @MainActor in self?.flush() } }
        return nil
    }

    func stop() { process?.terminate() }

    private func flush() {
        let new = buffer.drain()
        guard !new.isEmpty else { return }
        lines.append(contentsOf: new)
        if lines.count > maxLines { lines.removeFirst(lines.count - maxLines) }
    }

    private func finished(_ status: Int32, _ completion: (@MainActor (Int32) -> Void)?) {
        timer?.invalidate()
        timer = nil
        flush()
        isRunning = false
        lastExit = status
        process = nil
        hints = CrashHints.analyse(lines: lines, exitCode: status)
        completion?(status)
    }
}
