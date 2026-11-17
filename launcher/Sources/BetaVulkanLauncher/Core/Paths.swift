import Foundation

/// Everything the launcher writes lives under ~/Library/Application Support/BetaVulkan. Mojang's client jar and assets are
/// downloaded there at first run and are never bundled with the app.
enum AppPaths {
    static let support: URL = {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        return base.appendingPathComponent("BetaVulkan", isDirectory: true)
    }()
    static var jdk: URL { support.appendingPathComponent("jdk", isDirectory: true) }
    static var clientJar: URL { support.appendingPathComponent("versions/b1.7.3/client.jar") }
    /// Your own jar, remapped to readable names by our prepare tool (derived from Mojang's jar, so it stays on this machine).
    static var readableJar: URL { support.appendingPathComponent("versions/b1.7.3/readable.jar") }
    static var resources: URL { support.appendingPathComponent("versions/b1.7.3/resources", isDirectory: true) }
    /// Extra jars you add (modded Beta 1.7.3 jars), each in its own folder: jars/<id>/{original.jar, readable.jar, resources/, game/}. The original client.jar is never touched.
    static var jars: URL { support.appendingPathComponent("jars", isDirectory: true) }
    static var jarIndex: URL { jars.appendingPathComponent("index.json") }
    /// Isolated game instances, each with its own saves, options, and optional custom jar.
    static var instances: URL { support.appendingPathComponent("instances", isDirectory: true) }
    static var instanceIndex: URL { instances.appendingPathComponent("index.json") }
    static var mappings: URL { retroMCP.appendingPathComponent("conf/mappings.tiny") }
    static var retroMCP: URL { support.appendingPathComponent("retromcp", isDirectory: true) }
    static var assets: URL { support.appendingPathComponent("assets", isDirectory: true) }
    static var gameDir: URL { support.appendingPathComponent("game", isDirectory: true) }
    static var backups: URL { support.appendingPathComponent("backups", isDirectory: true) }
    static var benchmarks: URL { support.appendingPathComponent("benchmarks", isDirectory: true) }
    static var settingsFile: URL { support.appendingPathComponent("settings.json") }

    /// The game build (our jars, the patch agent and the preparation tool: no Mojang code) shipped inside the app by build_app.sh.
    static var bundledStage: URL? {
        guard let r = Bundle.main.resourceURL?.appendingPathComponent("stage"), FileManager.default.fileExists(atPath: r.appendingPathComponent("launch.json").path) else { return nil }
        return r
    }

    static func ensure(_ url: URL) {
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    }
}
