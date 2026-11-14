import Foundation

/// `BetaVulkan --selftest`: exercises the launcher's non-UI pipeline end to end and exits non-zero on the first failure.
@MainActor
enum SelfTest {
    static func run(state: AppState) async -> Int32 {
        func say(_ s: String) { print("[selftest] \(s)"); fflush(stdout) }
        say("support dir: \(AppPaths.support.path)")
        let fresh = InstallManager()
        fresh.jdk = .pending
        fresh.client = .pending
        fresh.prepared = .pending
        fresh.assets = .pending
        await fresh.installAll(player: nil)
        guard fresh.steps.allSatisfy({ $0.state == .pending }), !fresh.busy else {
            say("FAIL: signed-out installation was not blocked")
            return 1
        }
        say("signed-out installation blocked before downloads")
        say("checking files already installed…")
        say("jdk:    \(desc(state.install.jdk))")
        say("client: \(desc(state.install.client))")
        say("prepared: \(desc(state.install.prepared))")
        say("assets: \(desc(state.install.assets))")
        guard state.stageReady else { say("FAIL: no staged game at \(state.settings.stageDir)"); return 2 }
        guard state.install.ready, let java = state.install.javaPath else {
            say("PASS: no installed game to inspect")
            return 0
        }
        let arch = (try? InstallManager.javaArch(java)) ?? "?"
        say("java: \(java.path)  os.arch=\(arch)")
        guard arch == "aarch64" else { say("FAIL: JDK is not arm64"); return 1 }
        if let sha = try? Hashing.sha1(of: AppPaths.clientJar) { say("client.jar sha1 \(sha) (Mojang publishes 43db9b498cb67058d2e12d394e6507722e71bb45)") }

        let error = state.runner.launch(settings: state.settings, java: java, player: nil)
        guard error == GameRunner.signInRequired, !state.runner.isRunning, state.runner.lines.isEmpty else {
            say("FAIL: signed-out launch was not blocked")
            return 3
        }
        say("signed-out launch blocked before starting Java")
        say("PASS")
        return 0
    }

    private static func desc(_ s: InstallManager.State) -> String {
        switch s {
        case .pending: return "pending"
        case .running(let p, let m): return "\(m) \(Int(p * 100))%"
        case .done(let m): return "done (\(m))"
        case .failed(let m): return "FAILED: \(m)"
        }
    }
}
