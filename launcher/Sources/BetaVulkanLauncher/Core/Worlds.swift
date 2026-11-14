import Foundation

struct WorldInfo: Identifiable, Equatable {
    var id: String { name }
    let name: String
    let url: URL
    let sizeBytes: Int64
    let modified: Date?
}

/// Backup, import and export of world folders. Archives are made with `ditto`, so resource forks and permissions survive.
enum Worlds {
    static func list(gameDir: String) -> [WorldInfo] {
        let saves = URL(fileURLWithPath: gameDir).appendingPathComponent("saves")
        let dirs = (try? FileManager.default.contentsOfDirectory(at: saves, includingPropertiesForKeys: [.isDirectoryKey], options: [.skipsHiddenFiles])) ?? []
        return dirs.compactMap { url in
            guard FileManager.default.fileExists(atPath: url.appendingPathComponent("level.dat").path) else { return nil }
            let modified = (try? FileManager.default.attributesOfItem(atPath: url.appendingPathComponent("level.dat").path))?[.modificationDate] as? Date
            return WorldInfo(name: url.lastPathComponent, url: url, sizeBytes: folderSize(url), modified: modified)
        }.sorted { ($0.modified ?? .distantPast) > ($1.modified ?? .distantPast) }
    }

    static func folderSize(_ url: URL) -> Int64 {
        guard let e = FileManager.default.enumerator(at: url, includingPropertiesForKeys: [.fileSizeKey], options: [.skipsHiddenFiles]) else { return 0 }
        var total: Int64 = 0
        for case let f as URL in e { total += Int64((try? f.resourceValues(forKeys: [.fileSizeKey]))?.fileSize ?? 0) }
        return total
    }

    static func backup(_ world: WorldInfo) throws -> URL {
        AppPaths.ensure(AppPaths.backups)
        let stamp = ISO8601DateFormatter().string(from: Date()).replacingOccurrences(of: ":", with: "-")
        let dest = AppPaths.backups.appendingPathComponent("\(world.name)-\(stamp).zip")
        try ProcessRunner.run("/usr/bin/ditto", ["-c", "-k", "--sequesterRsrc", "--keepParent", world.url.path, dest.path])
        return dest
    }

    static func export(_ world: WorldInfo, to destination: URL) throws {
        try ProcessRunner.run("/usr/bin/ditto", ["-c", "-k", "--sequesterRsrc", "--keepParent", world.url.path, destination.path])
    }

    /// Imports a world from a .zip, or from an existing saves folder entry. A world that would overwrite an existing one is renamed.
    static func importWorld(from source: URL, gameDir: String) throws -> String {
        let saves = URL(fileURLWithPath: gameDir).appendingPathComponent("saves")
        AppPaths.ensure(saves)
        let staging = FileManager.default.temporaryDirectory.appendingPathComponent("import-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: staging, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: staging) }
        if source.pathExtension.lowercased() == "zip" {
            try ProcessRunner.run("/usr/bin/ditto", ["-x", "-k", source.path, staging.path])
        } else {
            try FileManager.default.copyItem(at: source, to: staging.appendingPathComponent(source.lastPathComponent))
        }
        let candidates = try FileManager.default.contentsOfDirectory(at: staging, includingPropertiesForKeys: nil).filter { $0.lastPathComponent != "__MACOSX" }
        guard let world = candidates.first(where: { FileManager.default.fileExists(atPath: $0.appendingPathComponent("level.dat").path) }) else {
            throw DownloadError(message: "No level.dat found: that does not look like a Minecraft world.")
        }
        var name = world.lastPathComponent
        var n = 2
        while FileManager.default.fileExists(atPath: saves.appendingPathComponent(name).path) { name = "\(world.lastPathComponent) \(n)"; n += 1 }
        try FileManager.default.moveItem(at: world, to: saves.appendingPathComponent(name))
        return name
    }

    static func trash(_ world: WorldInfo) throws {
        try FileManager.default.trashItem(at: world.url, resultingItemURL: nil)
    }
}
