import Foundation
import SwiftUI

enum Backend: String, Codable, CaseIterable, Identifiable {
    case vulkan, gl
    var id: String { rawValue }
    var title: String { self == .vulkan ? "Vulkan (MoltenVK)" : "OpenGL 2.1" }
}

enum GCProfile: String, Codable, CaseIterable, Identifiable {
    case balanced, lowLatency, throughput
    var id: String { rawValue }
    var title: String {
        switch self {
        case .balanced: return "Balanced (G1)"
        case .lowLatency: return "Low latency (generational ZGC)"
        case .throughput: return "Throughput (Parallel)"
        }
    }
    /// JVM flags for the profile. Measured on an M5 (normal render distance, 2 runs each): G1 with a dynamically sized heap was best
    /// (about 202-220 FPS, 4 ms max pause); a fixed pre-touched 2 GB heap cost 3-5% and 1.3 GB RSS; ParallelGC and generational ZGC were slower.
    func flags(memoryMB: Int) -> [String] {
        let heap = ["-Xms\(memoryMB)m", "-Xmx\(memoryMB)m", "-XX:+AlwaysPreTouch"]
        switch self {
        case .balanced: return ["-Xmx\(memoryMB)m", "-XX:+UseG1GC", "-XX:MaxHeapFreeRatio=40", "-XX:MinHeapFreeRatio=15"]   // the free ratios keep G1 from holding a heap 4x the live data after start-up (same FPS, about 500 MB less)
        case .lowLatency: return heap + ["-XX:+UseZGC", "-XX:+ZGenerational"]
        case .throughput: return heap + ["-XX:+UseParallelGC"]
        }
    }
}

/// A few ready-made combinations of the performance settings, so nobody has to know what a garbage collector is.
enum Preset: String, CaseIterable, Identifiable {
    case recommended, smooth, lowMemory
    var id: String { rawValue }
    var title: String { self == .recommended ? "Recommended" : (self == .smooth ? "Smooth" : "Low memory") }
    var detail: String {
        switch self {
        case .recommended: return "Fastest frame rates. Uses up to 3 GB of memory."
        case .smooth: return "Synchronises with the display: steady frames, less heat and power."
        case .lowMemory: return "Uses up to 2 GB of memory, for Macs with 8 GB."
        }
    }
}

struct Settings: Codable, Equatable {
    var backend: Backend = .vulkan
    var gcProfile: GCProfile = .balanced
    var memoryMB = 3072
    var meshWorkers = 1
    var genWorkers = 2
    var vsync = false
    /// nil counts as on, so settings files from before this option still load
    var showSkins: Bool? = nil
    var fullscreen = false
    var width = 1280
    var height = 720
    var stageDir = AppPaths.bundledStage?.path ?? ("~/Documents/beta-vulkan/hooks/build/stage" as NSString).expandingTildeInPath
    var gameDir = AppPaths.gameDir.path

    /// Which preset the current performance values match, if any.
    var preset: Preset? {
        Preset.allCases.first { var c = self; c.apply($0); return c.memoryMB == memoryMB && c.vsync == vsync && c.meshWorkers == meshWorkers && c.genWorkers == genWorkers && c.gcProfile == gcProfile }
    }

    mutating func apply(_ p: Preset) {
        gcProfile = .balanced
        meshWorkers = 1
        genWorkers = 2
        switch p {
        case .recommended: memoryMB = 3072; vsync = false
        case .smooth: memoryMB = 3072; vsync = true
        case .lowMemory: memoryMB = 2048; vsync = false; genWorkers = 1
        }
    }

    static func load() -> Settings {
        guard let data = try? Data(contentsOf: AppPaths.settingsFile),
              var s = try? JSONDecoder().decode(Settings.self, from: data) else { return Settings() }
        if s.stageDir.hasSuffix("game/build/stage") { s.stageDir = Settings().stageDir }   // the old in-tree build folder
        // inside the app bundle the shipped build wins; a folder that no longer has a build falls back to it as well
        if let bundled = AppPaths.bundledStage, !FileManager.default.fileExists(atPath: URL(fileURLWithPath: s.stageDir).appendingPathComponent("launch.json").path) || s.stageDir.contains("/hooks/build/stage") {
            s.stageDir = bundled.path
        }
        return s
    }

    func save() {
        AppPaths.ensure(AppPaths.support)
        if let data = try? JSONEncoder().encode(self) { try? data.write(to: AppPaths.settingsFile, options: .atomic) }
    }
}
