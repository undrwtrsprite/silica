import Foundation

/// A playable Golden Age Minecraft profile with isolated saves/mods and optional per-instance settings overrides.
struct GameInstance: Codable, Identifiable, Equatable {
    static let defaultID = "default"

    var id: String
    var name: String
    var versionID: String?
    var jarID: String?
    var backend: Backend?
    var fullscreen: Bool?
    var memoryMB: Int?
    var created: Date
    var lastPlayed: Date?

    var isDefault: Bool { id == Self.defaultID }
    var effectiveVersionID: String { versionID ?? "b1.7.3" }
}

@MainActor
final class InstanceStore: ObservableObject {
    @Published private(set) var instances: [GameInstance] = []

    init() {
        load()
    }

    func instance(for id: String?) -> GameInstance {
        if let id, let found = instances.first(where: { $0.id == id }) {
            return found
        }
        return instances.first(where: { $0.isDefault })
            ?? GameInstance(id: GameInstance.defaultID, name: "Beta 1.7.3", jarID: nil, created: Date())
    }

    func gameDir(for instance: GameInstance, defaultDir: String, jars: JarLibrary? = nil) -> String {
        if instance.isDefault { return defaultDir }
        // Preserve isolated folders for any migrated jar-based instances if their custom folder already holds saves.
        let custom = AppPaths.instances.appendingPathComponent(instance.id, isDirectory: true).appendingPathComponent("game", isDirectory: true)
        if let jarID = instance.jarID,
           let legacy = jars?.selection(for: jarID)?.gameDir,
           !FileManager.default.fileExists(atPath: custom.path),
           FileManager.default.fileExists(atPath: legacy) {
            return legacy
        }
        AppPaths.ensure(custom)
        return custom.path
    }

    func effectiveSettings(_ base: Settings, for instance: GameInstance) -> Settings {
        var out = base
        if let b = instance.backend { out.backend = b }
        if let f = instance.fullscreen { out.fullscreen = f }
        if let m = instance.memoryMB { out.memoryMB = m }
        out.jarID = instance.jarID
        return out
    }

    /// Imports any previously added custom jars as named instances on upgrade so existing saves stay reachable.
    func syncLegacyJars(_ jars: JarLibrary) {
        var changed = false
        for jar in jars.jars where !instances.contains(where: { $0.jarID == jar.id }) {
            instances.append(GameInstance(
                id: "jar-\(jar.id)",
                name: jar.name,
                jarID: jar.id,
                created: jar.added
            ))
            changed = true
        }
        if changed { save() }
    }

    @discardableResult
    func create(name: String, versionID: String? = nil, jarID: String? = nil, backend: Backend? = nil, fullscreen: Bool? = nil, memoryMB: Int? = nil) -> GameInstance {
        let clean = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let entry = GameInstance(
            id: UUID().uuidString.lowercased(),
            name: clean.isEmpty ? "Beta 1.7.3 Copy" : clean,
            versionID: versionID,
            jarID: jarID,
            backend: backend,
            fullscreen: fullscreen,
            memoryMB: memoryMB,
            created: Date()
        )
        let dir = AppPaths.instances.appendingPathComponent(entry.id, isDirectory: true).appendingPathComponent("game", isDirectory: true)
        AppPaths.ensure(dir)
        instances.append(entry)
        save()
        return entry
    }

    @discardableResult
    func duplicate(_ source: GameInstance, defaultDir: String, jars: JarLibrary) -> GameInstance {
        let copy = create(
            name: "\(source.name) Copy",
            versionID: source.versionID,
            jarID: source.jarID,
            backend: source.backend,
            fullscreen: source.fullscreen,
            memoryMB: source.memoryMB
        )
        let srcDir = URL(fileURLWithPath: gameDir(for: source, defaultDir: defaultDir, jars: jars), isDirectory: true)
        let dstDir = URL(fileURLWithPath: gameDir(for: copy, defaultDir: defaultDir, jars: jars), isDirectory: true)
        let fm = FileManager.default
        if let items = try? fm.contentsOfDirectory(at: srcDir, includingPropertiesForKeys: nil) {
            for item in items {
                try? fm.copyItem(at: item, to: dstDir.appendingPathComponent(item.lastPathComponent))
            }
        }
        return copy
    }

    func update(_ id: String, mutate: (inout GameInstance) -> Void) {
        guard let i = instances.firstIndex(where: { $0.id == id }) else { return }
        mutate(&instances[i])
        save()
    }

    func touchLastPlayed(_ id: String) {
        update(id) { $0.lastPlayed = Date() }
    }

    func delete(_ id: String, jars: JarLibrary) {
        guard let idx = instances.firstIndex(where: { $0.id == id }), !instances[idx].isDefault else { return }
        let removed = instances.remove(at: idx)
        let dir = AppPaths.instances.appendingPathComponent(id, isDirectory: true)
        try? FileManager.default.removeItem(at: dir)
        if let jarID = removed.jarID, !instances.contains(where: { $0.jarID == jarID }) {
            jars.remove(jarID)
        }
        save()
    }

    private func load() {
        var list: [GameInstance] = []
        if let data = try? Data(contentsOf: AppPaths.instanceIndex),
           let decoded = try? JSONDecoder.iso.decode([GameInstance].self, from: data) {
            list = decoded
        }
        if !list.contains(where: { $0.isDefault }) {
            list.insert(GameInstance(id: GameInstance.defaultID, name: "Beta 1.7.3", jarID: nil, created: Date()), at: 0)
        }
        instances = list
    }

    private func save() {
        AppPaths.ensure(AppPaths.instances)
        if let data = try? JSONEncoder.iso.encode(instances) {
            try? data.write(to: AppPaths.instanceIndex, options: .atomic)
        }
    }
}

private extension JSONDecoder {
    static var iso: JSONDecoder { let d = JSONDecoder(); d.dateDecodingStrategy = .iso8601; return d }
}
private extension JSONEncoder {
    static var iso: JSONEncoder { let e = JSONEncoder(); e.dateEncodingStrategy = .iso8601; return e }
}
