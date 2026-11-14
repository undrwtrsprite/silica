import Foundation

/// The subset of the game's benchmark JSON the launcher shows.
struct BenchResult: Codable, Identifiable, Equatable {
    var id: String { "\(label)-\(distance)" }
    var label = ""
    var distance = 0
    var avgFPS = 0.0, lowFPS = 0.0, p99ms = 0.0, maxMs = 0.0, rebuildMs = 0.0, gcPauses = 0, rssMB = 0
    var renderer = "", java = "", arch = ""

    static let distanceNames = [0: "Far (256)", 1: "Normal (128)", 2: "Short (64)", 3: "Tiny (32)"]
    var distanceName: String { Self.distanceNames[distance] ?? "\(distance)" }

    init?(json url: URL, label: String) {
        guard let data = try? Data(contentsOf: url), let o = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else { return nil }
        func d(_ k: String) -> Double { (o[k] as? NSNumber)?.doubleValue ?? 0 }
        self.label = label
        distance = Int(d("render_distance_setting"))
        avgFPS = d("avg_fps"); lowFPS = d("one_percent_low_fps"); p99ms = d("p99_ms"); maxMs = d("max_ms")
        rebuildMs = d("rebuild_avg_ms"); gcPauses = Int(d("gc_pause_events")); rssMB = Int(d("rss_mb"))
        renderer = o["gl_renderer"] as? String ?? ""; java = o["java"] as? String ?? ""; arch = o["os_arch"] as? String ?? ""
    }
}

struct BenchRun: Identifiable, Equatable {
    var id: String { name }
    let name: String
    let date: Date
    let results: [BenchResult]
}

enum BenchmarkStore {
    static func runs() -> [BenchRun] {
        let dirs = (try? FileManager.default.contentsOfDirectory(at: AppPaths.benchmarks, includingPropertiesForKeys: [.creationDateKey], options: [.skipsHiddenFiles])) ?? []
        return dirs.compactMap { dir -> BenchRun? in
            let files = ((try? FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: nil)) ?? []).filter { $0.pathExtension == "json" }.sorted { $0.lastPathComponent < $1.lastPathComponent }
            let results = files.compactMap { BenchResult(json: $0, label: dir.lastPathComponent) }
            guard !results.isEmpty else { return nil }
            let date = (try? dir.resourceValues(forKeys: [.creationDateKey]))?.creationDate ?? .distantPast
            return BenchRun(name: dir.lastPathComponent, date: date, results: results)
        }.sorted { $0.date > $1.date }
    }

    static func newRunDirectory(label: String) -> URL {
        let stamp = ISO8601DateFormatter().string(from: Date()).replacingOccurrences(of: ":", with: "-")
        let dir = AppPaths.benchmarks.appendingPathComponent("\(stamp)-\(label)")
        AppPaths.ensure(dir)
        return dir
    }
}
