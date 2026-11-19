import AppKit
import Combine
import Foundation
import SwiftUI

enum Tab: String, CaseIterable, Identifiable {
    case play = "Play", instances = "Instances", worlds = "Worlds", settings = "Settings", advanced = "Advanced"
    var id: String { rawValue }
    var symbol: String {
        switch self {
        case .play: return "play.fill"
        case .instances: return "square.stack.3d.up.fill"
        case .worlds: return "globe.europe.africa.fill"
        case .settings: return "slider.horizontal.3"
        case .advanced: return "waveform.path.ecg"
        }
    }
}

enum AdvancedTab: String, CaseIterable, Identifiable {
    case benchmark = "Benchmark", logs = "Logs"
    var id: String { rawValue }
}

@MainActor
final class AppState: ObservableObject {
    @Published var settings: Settings { didSet { if settings != oldValue { settings.save() } } }
    @Published var tab: Tab = .play
    @Published var advancedTab: AdvancedTab = .benchmark
    /// The guided setup covers the window until everything is installed and the person has seen the final page.
    @Published var wizardOpen = false
    @Published var worlds: [WorldInfo] = []
    @Published var benchRuns: [BenchRun] = []
    @Published var benchStatus: String?
    @Published var benchProgress: Double = 0
    @Published var benchDetail = ""   // e.g. "Run 2 of 2 · Vulkan · Far (256) · distance 1 of 3"
    @Published var benchStage = ""    // starting game / warming up / measuring
    @Published var benchBusy = false
    @Published private(set) var benchCountdown: Int?
    @Published private(set) var benchResultNames: [String] = []
    @Published var message: String?

    @Published private(set) var player: VerifiedMinecraftPlayer?
    @Published private(set) var signingIn = false

    private let signIn = MicrosoftSignIn()
    private let accountStore: AccountStore = KeychainAccountStore()
    /// The saved accounts, for the account picker, and which one is in use.
    @Published private(set) var accounts: [StoredAccount] = []
    @Published private(set) var activeAccountId: String?
    private var sessionExpiry: Task<Void, Never>?
    /// Bumped by sign-out and sign-in, so a renewal that finishes late cannot undo what the user just did.
    private var sessionGeneration = 0
    /// The quiet renewal after opening already signed in; Play waits for it when the game needs the sign-in token.
    private var renewal: Task<Void, Never>?

    let install = InstallManager()
    let updates = UpdateManager()
    let runner = GameRunner()
    let jars = JarLibrary()
    let instances = InstanceStore()
    let versions = VersionCatalog()
    private let telemetry = Telemetry()
    private var telemetryCancellables: Set<AnyCancellable> = []
    /// The jar the setup wizard imported last, so its final page can show the result.
    @Published var wizardJarID: String?

    init() {
        settings = Settings.load()
        instances.syncLegacyJars(jars)
        wizardOpen = !(install.baseReady && GameRunner.stageConfig(settings.stageDir) != nil)
        refreshWorlds()
        benchRuns = BenchmarkStore.runs()
        if let demo = ProcessInfo.processInfo.environment["SILICA_DEMO_PLAYER"] {
            if !demo.isEmpty { player = VerifiedMinecraftPlayer(restoringName: demo, expiresAt: .distantFuture, profileId: "demo-player") }
        } else {
            loadAccounts()
            activateActiveAccount()
        }
        if install.ready && stageReady { Task { [self] in await jars.recheckStale(stageDir: settings.stageDir, java: install.javaPath) } }
        telemetry.start(enabled: settings.shareAnonymousCount != false, playing: runner.isRunning, backend: activeSettings.backend.rawValue, gameVersion: telemetryGameVersion)
        runner.$isRunning.sink { [weak self] running in
            guard let self else { return }
            self.telemetry.update(enabled: self.settings.shareAnonymousCount != false, playing: running, backend: self.activeSettings.backend.rawValue, gameVersion: self.telemetryGameVersion)
        }.store(in: &telemetryCancellables)
        $settings.sink { [weak self] next in
            guard let self else { return }
            let inst = self.instances.instance(for: next.instanceID)
            let eff = self.instances.effectiveSettings(next, for: inst)
            let gv = inst.jarID != nil ? "custom" : inst.effectiveVersionID
            self.telemetry.update(enabled: next.shareAnonymousCount != false, playing: self.runner.isRunning, backend: eff.backend.rawValue, gameVersion: gv)
        }.store(in: &telemetryCancellables)
    }

    private var telemetryGameVersion: String {
        activeInstance.jarID != nil ? "custom" : activeInstance.effectiveVersionID
    }

    var activeInstance: GameInstance { instances.instance(for: settings.instanceID) }
    var activeSettings: Settings { instances.effectiveSettings(settings, for: activeInstance) }
    var activeGameDir: String { instances.gameDir(for: activeInstance, defaultDir: settings.gameDir, jars: jars) }

    func selectInstance(_ id: String) {
        settings.instanceID = id
        settings.jarID = instances.instance(for: id).jarID
        refreshWorlds()
    }

    var stageReady: Bool { GameRunner.stageConfig(settings.stageDir) != nil }
    var canPlay: Bool { player?.isValid == true && install.baseReady && stageReady && !runner.isRunning && !benchBusy }

    /// Signs in with Microsoft and saves the account; it becomes the active one. Used both for the first account and for "Add account".
    func signInWithMicrosoft() {
        guard !signingIn, !runner.isRunning, !benchBusy else { return }
        signingIn = true
        Task { [self] in
            defer { signingIn = false }
            do {
                let session = try await signIn.signIn()
                sessionGeneration += 1
                var saved = accountStore.load()
                saved.upsert(session.player, refreshToken: session.refreshToken)
                accountStore.save(saved)
                loadAccounts()
                adopt(session.player)
            } catch is CancellationError {
                // Closing the browser leaves things as they were.
            } catch let error as AccountError {
                message = error.localizedDescription
            } catch {
                message = "Could not reach the sign-in services. Check your connection and try again."
            }
        }
    }

    /// Makes another saved account the active one.
    func switchAccount(to id: String) {
        guard !signingIn, !runner.isRunning, !benchBusy, id != activeAccountId else { return }
        var saved = accountStore.load()
        guard saved.accounts.contains(where: { $0.profileId == id }) else { return }
        saved.active = id
        accountStore.save(saved)
        loadAccounts()
        sessionExpiry?.cancel()
        sessionGeneration += 1
        player = nil
        activateActiveAccount()
    }

    /// Signs out of the active account and forgets it. Other saved accounts stay in the list.
    func signOut() {
        guard !signingIn, !install.busy, !runner.isRunning, !benchBusy else { return }
        sessionExpiry?.cancel()
        sessionGeneration += 1
        var saved = accountStore.load()
        if let id = saved.active ?? activeAccountId { saved.remove(id) }
        accountStore.save(saved)
        loadAccounts()
        player = nil
    }

    private func loadAccounts() {
        let args = CommandLine.arguments
        guard !args.contains("--snapshots"), !args.contains("--selftest") else { return }
        let saved = accountStore.load()
        accounts = saved.accounts
        activeAccountId = saved.active
    }

    /// Opens signed in as the active account. If its Minecraft session is still valid the app is signed in at once and renews quietly
    /// in the background; otherwise it signs in again from the saved token, which takes a second.
    private func activateActiveAccount() {
        let args = CommandLine.arguments
        guard !args.contains("--snapshots"), !args.contains("--selftest") else { return }
        guard activeAccountId != nil else { return }
        let restorer = restorer()
        let generation = sessionGeneration
        if let instant = restorer.instantPlayer() {
            adopt(instant)
            renewal = Task { [self] in
                let outcome = await restorer.restore()
                guard generation == sessionGeneration else { return }
                await applyRenewal(outcome)
            }
            return
        }
        signingIn = true
        Task { [self] in
            defer { signingIn = false }
            let outcome = await restorer.restore()
            guard generation == sessionGeneration else { return }
            await applyRenewal(outcome)
        }
    }

    private func applyRenewal(_ outcome: SessionRestore) async {
        switch outcome {
        case .restored(let renewed): adopt(renewed)
        case .rejected:
            // Microsoft or the purchase check said no: the saved sign-in is gone
            player = nil
            message = "That Microsoft sign-in has ended. Sign in again to keep playing."
        case .none, .unavailable: break   // offline: the saved session keeps working until it expires
        }
        loadAccounts()
    }

    private func restorer() -> SessionRestorer {
        SessionRestorer(store: accountStore, service: MinecraftAccountService())
    }

    private func adopt(_ verified: VerifiedMinecraftPlayer) {
        player = verified
        sessionExpiry?.cancel()
        sessionExpiry = Task { [weak self] in
            // renew a couple of minutes before the Minecraft token runs out, so playing is never interrupted
            do { try await Task.sleep(for: .seconds(max(0, verified.expiresAt.timeIntervalSinceNow - 120))) }
            catch { return }
            await self?.renewSession(until: verified.expiresAt)
        }
    }

    private func renewSession(until expiry: Date) async {
        let generation = sessionGeneration
        let outcome = await restorer().restore()
        guard generation == sessionGeneration else { return }
        if case .restored(let renewed) = outcome {
            adopt(renewed)
            return
        }
        // renewal failed: keep the current session until it really ends, then show signed out
        try? await Task.sleep(for: .seconds(max(0, expiry.timeIntervalSinceNow)))
        if player?.expiresAt == expiry { player = nil }
    }

    func refreshWorlds() {
        let dir = activeGameDir
        Task.detached {
            let list = Worlds.list(gameDir: dir)
            await MainActor.run { self.worlds = list }
        }
    }

    func play() {
        guard let java = install.javaPath else { message = "Install Java first (Play → Set up)."; return }
        guard !benchBusy else { return }
        let instance = activeInstance
        let launchSettings = activeSettings
        let dir = activeGameDir
        Task { [self] in
            // opened already signed in: the game still needs the sign-in token for online-mode servers, which the quiet renewal brings (a second at most)
            if player?.accessToken == nil { await renewal?.value }
            let chosen = jars.selection(for: instance.jarID)
            if instance.jarID != nil && chosen == nil {
                message = "The custom jar for \"\(instance.name)\" could not be found."
                return
            }
            if chosen == nil {
                do {
                    try await versions.prepareIfNeeded(instance.effectiveVersionID, java: java, stageDir: launchSettings.stageDir)
                    install.refresh()
                } catch {
                    versions.busyMessage = nil
                    message = error.localizedDescription
                    return
                }
            }
            if let err = runner.launch(settings: launchSettings, java: java, player: player, versionID: instance.effectiveVersionID, jar: chosen, gameDirOverride: dir) {
                message = err
            } else {
                instances.touchLastPlayed(instance.id)
            }
        }
    }

    /// Creates a new isolated instance (either any official Alpha/Beta version or backed by a custom jar URL).
    func createInstance(name: String, versionID: String = "b1.7.3", customJarURL: URL? = nil, select: Bool = true) {
        Task { [self] in
            var jarID: String? = nil
            if let customJarURL {
                guard let added = await jars.importJar(customJarURL, stageDir: settings.stageDir, java: install.javaPath, official: install) else { return }
                jarID = added.id
                wizardJarID = added.id
            }
            let created = instances.create(name: name, versionID: customJarURL == nil ? versionID : nil, jarID: jarID)
            if select { selectInstance(created.id) }
        }
    }

    /// Adds a jar from setup wizard and attaches it as an instance cleanly.
    func addJar(_ url: URL, select: Bool = true) {
        let name = url.deletingPathExtension().lastPathComponent
        createInstance(name: name, customJarURL: url, select: select)
    }

    func chooseJarFile(select: Bool = true) {
        let panel = NSOpenPanel()
        panel.message = "Choose a modded Minecraft jar"
        panel.allowedContentTypes = [.init(filenameExtension: "jar")!]
        panel.canChooseDirectories = false
        if panel.runModal() == .OK, let url = panel.url { addJar(url, select: select) }
    }

    func setUp(source: InstallManager.ClientSource, thenAdd jar: URL? = nil) {
        guard let player, player.isValid, !install.busy else { return }
        install.stageDir = settings.stageDir
        install.clientSource = source
        if case .local = source {
            install.client = .pending
        }
        Task { [self] in
            await install.installAll(player: player)
            // a jar chosen in the setup is added once the original game is in place (the check compares against it)
            if let jar, install.ready { addJar(jar) }
        }
    }

    /// Settings > Run setup again: starts the guided setup over the current state (it skips what is already in place).
    func reopenSetup() { install.refresh(); wizardOpen = true }

    // MARK: Benchmarks

    private var benchCancelled = false
    private var benchTask: Task<Void, Never>?

    func stopBenchmark() {
        guard benchBusy else { return }
        benchCancelled = true
        if benchCountdown != nil {
            benchTask?.cancel()
        } else {
            benchStatus = "Stopping…"
            runner.stop()
        }
    }

    func startBenchmark(_ backends: [Backend]) {
        guard let java = install.javaPath, canPlay else { return }
        benchBusy = true
        benchCancelled = false
        benchStatus = nil
        benchProgress = 0
        benchCountdown = 5
        let launchSettings = settings
        benchTask = Task {
            defer {
                benchCountdown = nil
                benchBusy = false
                benchTask = nil
            }
            for seconds in (1...5).reversed() {
                benchCountdown = seconds
                do {
                    try await Task.sleep(for: .seconds(1))
                } catch {
                    benchStatus = "Benchmark cancelled before launch."
                    return
                }
            }
            guard !benchCancelled else {
                benchStatus = "Benchmark cancelled before launch."
                return
            }
            benchCountdown = nil

            var completed: [String] = []
            for (index, backend) in backends.enumerated() {
                guard let name = await runBenchmark(backend: backend, settings: launchSettings, java: java,
                                                    runIndex: index, runCount: backends.count) else { break }
                completed.append(name)
                benchProgress = Double(completed.count) / Double(backends.count)
            }
            benchRuns = BenchmarkStore.runs()
            if !completed.isEmpty { benchResultNames = completed }
            if completed.count == backends.count { benchStatus = "Benchmark complete." }
        }
    }

    private func runBenchmark(backend: Backend, settings: Settings, java: URL, runIndex: Int, runCount: Int) async -> String? {
        let label = backend == .vulkan ? "vulkan" : "opengl"
        let dir = BenchmarkStore.newRunDirectory(label: label)
        var launchSettings = settings
        launchSettings.backend = backend
        launchSettings.fullscreen = true
        for distance in [0, 1, 2] {
            guard player?.isValid == true else {
                try? FileManager.default.removeItem(at: dir)
                benchStatus = "Sign in again to run a benchmark."
                return nil
            }
            let out = dir.appendingPathComponent("d\(distance).json").path
            let jvm = ["-Dbetavk.bench=true", "-Dbetavk.nograb=true", "-Dbetavk.bench.distance=\(distance)", "-Dbetavk.bench.warmup=10",
                       "-Dbetavk.bench.seconds=20", "-Dbetavk.bench.out=\(out)", "-Dbetavk.bench.label=\(label)"]
            benchDetail = "\(backend == .vulkan ? "Vulkan" : "OpenGL") · \(BenchResult.distanceNames[distance] ?? "")"
            benchStage = "Starting the game"
            let ticker = startBenchTicker(label: label, distance: distance, runIndex: runIndex, runCount: runCount)
            let status: Int32 = await withCheckedContinuation { continuation in
                if let error = runner.launch(settings: launchSettings, java: java, player: player, extraJVM: jvm,
                                             completion: { continuation.resume(returning: $0) }) {
                    message = error
                    continuation.resume(returning: -1)
                }
            }
            ticker.cancel()
            if benchCancelled {
                try? FileManager.default.removeItem(at: dir)
                benchStatus = "Benchmark stopped. The partial run was discarded."
                return nil
            }
            if status != 0 {
                benchStatus = "Benchmark stopped (exit \(status)). See Logs."
                return nil
            }
        }
        return dir.lastPathComponent
    }

    /// Drives the progress bar from the game's own log: starting -> "world ready" (warm-up, 10 s) -> "measuring" (20 s). Each run is three distances.
    private func startBenchTicker(label: String, distance: Int, runIndex: Int, runCount: Int) -> Task<Void, Never> {
        let began = Date()
        let names = ["Far (256)", "Normal (128)", "Short (64)"]
        let nice = label == "vulkan" ? "Vulkan" : (label == "opengl" ? "OpenGL" : label)
        return Task { @MainActor in
            var warmStart: Date?, measStart: Date?
            while !Task.isCancelled {
                let log = self.runner.lines
                let now = Date()
                if measStart == nil, log.contains(where: { $0.contains("[bench] measuring") }) { measStart = now }
                if warmStart == nil, log.contains(where: { $0.contains("[bench] world ready") }) { warmStart = now }
                let frac: Double, stage: String
                if let m = measStart { frac = 0.40 + 0.60 * min(1, now.timeIntervalSince(m) / 20); stage = "Measuring" }
                else if let w = warmStart { frac = 0.15 + 0.25 * min(1, now.timeIntervalSince(w) / 10); stage = "Warming up" }
                else { frac = 0.15 * (1 - exp(-now.timeIntervalSince(began) / 4)); stage = "Starting the game" }
                self.benchProgress = (Double(runIndex * 3 + distance) + frac) / Double(runCount * 3)
                self.benchDetail = "Run \(runIndex + 1) of \(runCount) · \(nice) · \(names[distance]) · distance \(distance + 1) of 3"
                self.benchStage = stage
                try? await Task.sleep(nanoseconds: 250_000_000)
            }
        }
    }

    // MARK: Worlds

    func backup(_ w: WorldInfo) {
        Task.detached {
            do { let url = try Worlds.backup(w); await MainActor.run { self.message = "Backed up to \(url.lastPathComponent)" } }
            catch { await MainActor.run { self.message = error.localizedDescription } }
        }
    }

    func export(_ w: WorldInfo) {
        let panel = NSSavePanel()
        panel.nameFieldStringValue = "\(w.name).zip"
        guard panel.runModal() == .OK, let url = panel.url else { return }
        Task.detached {
            do { try Worlds.export(w, to: url); await MainActor.run { self.message = "Exported \(w.name)" } }
            catch { await MainActor.run { self.message = error.localizedDescription } }
        }
    }

    func importWorld() {
        let panel = NSOpenPanel()
        panel.message = "Choose a world .zip, or a world folder"
        panel.canChooseDirectories = true
        panel.canChooseFiles = true
        guard panel.runModal() == .OK, let url = panel.url else { return }
        let dir = activeGameDir
        Task.detached {
            do { let name = try Worlds.importWorld(from: url, gameDir: dir); await MainActor.run { self.message = "Imported \(name)"; self.refreshWorlds() } }
            catch { await MainActor.run { self.message = error.localizedDescription } }
        }
    }

    func trash(_ w: WorldInfo) {
        Task.detached {
            do { try Worlds.trash(w); await MainActor.run { self.refreshWorlds() } }
            catch { await MainActor.run { self.message = error.localizedDescription } }
        }
    }

    func choose(_ keyPath: WritableKeyPath<Settings, String>, message text: String) {
        let panel = NSOpenPanel()
        panel.message = text
        panel.canChooseDirectories = true
        panel.canChooseFiles = false
        if panel.runModal() == .OK, let url = panel.url { settings[keyPath: keyPath] = url.path }
    }
}
