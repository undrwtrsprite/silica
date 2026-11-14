import Foundation

/// What the jar checker found out about a jar (decoded from the JSON JarCheck prints).
struct JarReport: Codable, Equatable {
    var verdict: String                // green, yellow or red
    var reasons: [String]
    var notes: [String]
    var identityOk: Bool
    var classesChanged: Int
    var classesAdded: Int
    var methodsChanged: Int
    var patchedClasses: Int
    var keyMissing: [String]
    var removedClasses: [String]
    var patchFailures: [String]
    var overlap: [String]
    var brokenLinks: [String]
    var changedClassNames: [String]
    var addedClassNames: [String]

    var level: Level { Level(rawValue: verdict) ?? .red }
    enum Level: String { case green, yellow, red }

    /// One line a person can read.
    var headline: String {
        switch level {
        case .green: return methodsChanged == 0 && classesAdded == 0 ? "The original game" : "Works with Silica"
        case .yellow: return "Works, with overlaps"
        case .red: return "Silica can't run this one properly"
        }
    }
}

/// A jar you added: a modded Beta 1.7.3 client. Silica keeps your original untouched, makes a remapped copy of it, and runs its own patches on top at load time.
struct CustomJar: Codable, Identifiable, Equatable {
    var id: String                     // first 12 hex digits of its SHA-1
    var name: String
    var sha1: String
    var added: Date
    var report: JarReport?
    var patchVersion: String?          // which build of Silica's patches the report was made with
    var failure: String?               // why it could not be prepared or checked at all
}

/// Everything about extra jars: importing (copy, remap, check), re-checking after Silica's patches change, and handing the game the right files.
@MainActor
final class JarLibrary: ObservableObject {
    @Published private(set) var jars: [CustomJar] = []
    @Published private(set) var busy = false
    @Published private(set) var status = ""
    /// Set when the last import was refused or failed; shown to the person.
    @Published var problem: String?

    /// The check on the original game (against itself): proves every patch applies to the jar Silica makes from Mojang's. Not saved; it is quick to redo.
    @Published private(set) var originalReport: JarReport?
    @Published private(set) var originalChecking = false

    init() { load() }

    func checkOriginal(stageDir: String, java: URL?) async {
        guard let java, !originalChecking, FileManager.default.fileExists(atPath: AppPaths.readableJar.path) else { return }
        originalChecking = true
        defer { originalChecking = false }
        let jp = java.path, stage = URL(fileURLWithPath: stageDir), readable = AppPaths.readableJar
        originalReport = try? await Task.detached { try Self.runCheck(java: jp, stage: stage, candidate: readable) }.value
    }

    func jar(_ id: String?) -> CustomJar? { id.flatMap { id in jars.first { $0.id == id } } }

    // MARK: What the game runs

    struct Selection {
        let readable: URL, resources: URL, gameDir: String?
    }

    /// The files to launch with; nil means the official jar. Falls back to official when the chosen jar is gone.
    func selection(for id: String?) -> Selection? {
        guard let id, jar(id) != nil else { return nil }
        let dir = AppPaths.jars.appendingPathComponent(id, isDirectory: true)
        guard FileManager.default.fileExists(atPath: dir.appendingPathComponent("readable.jar").path) else { return nil }
        return Selection(readable: dir.appendingPathComponent("readable.jar"), resources: dir.appendingPathComponent("resources", isDirectory: true),
                         gameDir: dir.appendingPathComponent("game", isDirectory: true).path)
    }

    // MARK: Storage

    private func load() {
        guard let data = try? Data(contentsOf: AppPaths.jarIndex), let list = try? JSONDecoder.iso.decode([CustomJar].self, from: data) else { return }
        jars = list.filter { FileManager.default.fileExists(atPath: AppPaths.jars.appendingPathComponent($0.id).path) }
    }

    private func save() {
        AppPaths.ensure(AppPaths.jars)
        if let data = try? JSONEncoder.iso.encode(jars) { try? data.write(to: AppPaths.jarIndex, options: .atomic) }
    }

    func remove(_ id: String) {
        try? FileManager.default.removeItem(at: AppPaths.jars.appendingPathComponent(id, isDirectory: true))
        jars.removeAll { $0.id == id }
        save()
    }

    // MARK: Import

    /// Adds a jar and checks it. Needs the official setup done first (the check compares against your clean jar and the name table). Returns the new entry, or nil with `problem` set.
    @discardableResult
    func importJar(_ source: URL, stageDir: String, java: URL?, official: InstallManager) async -> CustomJar? {
        guard !busy else { return nil }
        busy = true
        problem = nil
        defer { busy = false; status = "" }
        guard let java else { problem = "Install Java first (the setup does it)."; return nil }
        guard official.client.isDone, official.prepared.isDone, FileManager.default.fileExists(atPath: AppPaths.mappings.path) else {
            problem = "Finish the normal setup first: Silica checks a jar against your clean copy of Beta 1.7.3."; return nil
        }
        do {
            status = "Reading \(source.lastPathComponent)…"
            let sha = try await Task.detached { try Hashing.sha1(of: source) }.value
            if sha == InstallManager.clientSHA1 { problem = "That is the original Beta 1.7.3 client, which is already installed. Pick it from the jar list."; return nil }
            let id = String(sha.prefix(12))
            if let existing = jar(id) { problem = "\"\(existing.name)\" is already in your list."; return nil }
            let dir = AppPaths.jars.appendingPathComponent(id, isDirectory: true)
            AppPaths.ensure(dir)
            let name = source.deletingPathExtension().lastPathComponent
            do {
                try await Task.detached { try Self.requireJar(source) }.value
                status = "Copying your jar…"
                let original = dir.appendingPathComponent("original.jar")
                try? FileManager.default.removeItem(at: original)
                try FileManager.default.copyItem(at: source, to: original)
                var entry = CustomJar(id: id, name: name, sha1: sha, added: Date(), report: nil, patchVersion: nil, failure: nil)
                jars.append(entry); save()
                entry = await prepareAndCheck(entry, stageDir: stageDir, java: java)
                if let i = jars.firstIndex(where: { $0.id == id }) { jars[i] = entry }
                save()
                return entry
            } catch {
                try? FileManager.default.removeItem(at: dir)
                problem = error.localizedDescription
                return nil
            }
        } catch {
            problem = error.localizedDescription
            return nil
        }
    }

    /// Re-checks every jar whose report was made with another build of the patches, so an update to Silica cannot quietly break a jar that worked yesterday.
    func recheckStale(stageDir: String, java: URL?) async {
        guard let java, !busy else { return }
        let current = Self.patchVersion(stageDir)
        let stale = jars.filter { $0.patchVersion != current || $0.report == nil }
        guard !stale.isEmpty else { return }
        busy = true
        defer { busy = false; status = "" }
        for var entry in stale {
            status = "Re-checking \(entry.name)…"
            entry = await prepareAndCheck(entry, stageDir: stageDir, java: java, reprepare: !FileManager.default.fileExists(atPath: AppPaths.jars.appendingPathComponent(entry.id).appendingPathComponent("readable.jar").path))
            if let i = jars.firstIndex(where: { $0.id == entry.id }) { jars[i] = entry }
        }
        save()
    }

    /// Runs the checks again on one jar (the Re-check button).
    func recheck(_ id: String, stageDir: String, java: URL?) async {
        guard let java, !busy, let entry = jar(id) else { return }
        busy = true
        defer { busy = false; status = "" }
        let updated = await prepareAndCheck(entry, stageDir: stageDir, java: java)
        if let i = jars.firstIndex(where: { $0.id == id }) { jars[i] = updated }
        save()
    }

    /// Fingerprint of the patch code: changes whenever the staged hooks or agent do.
    static func patchVersion(_ stageDir: String) -> String {
        let stage = URL(fileURLWithPath: stageDir)
        let parts = ["lib/hooks.jar", "agent/patch-agent.jar"].compactMap { try? Hashing.sha1(of: stage.appendingPathComponent($0)) }
        return parts.map { String($0.prefix(10)) }.joined(separator: "-")
    }

    private func prepareAndCheck(_ input: CustomJar, stageDir: String, java: URL, reprepare: Bool = true) async -> CustomJar {
        var entry = input
        let dir = AppPaths.jars.appendingPathComponent(entry.id, isDirectory: true)
        let jp = java.path, stage = URL(fileURLWithPath: stageDir)
        do {
            let original = dir.appendingPathComponent("original.jar"), readable = dir.appendingPathComponent("readable.jar"), resources = dir.appendingPathComponent("resources", isDirectory: true)
            if reprepare {
                status = "Remapping \(entry.name)…"
                let preps = stage.appendingPathComponent("prep").path
                try await Task.detached {
                    try ProcessRunner.run(jp, ["-cp", "\(preps)/*", "dev.betavk.mcprep.Main", original.path, AppPaths.mappings.path, readable.path])
                }.value
                try? FileManager.default.removeItem(at: resources)
                AppPaths.ensure(resources)
                // no "-x META-INF/*": many mod jars have no such folder and unzip then exits non-zero
                try await Task.detached {
                    try ProcessRunner.run("/usr/bin/unzip", ["-q", "-o", original.path, "-x", "*.class", "-d", resources.path])
                    try? FileManager.default.removeItem(at: resources.appendingPathComponent("META-INF"))
                }.value
            }
            status = "Checking \(entry.name) against Silica's patches…"
            let report = try await Task.detached { try Self.runCheck(java: jp, stage: stage, candidate: readable) }.value
            entry.report = report
            entry.failure = nil
        } catch {
            entry.report = nil
            entry.failure = error.localizedDescription
        }
        entry.patchVersion = Self.patchVersion(stageDir)
        return entry
    }

    nonisolated private static func runCheck(java: String, stage: URL, candidate: URL) throws -> JarReport {
        let libs = (try? FileManager.default.contentsOfDirectory(atPath: stage.appendingPathComponent("lib").path)) ?? []
        let libPaths = libs.filter { $0.hasSuffix(".jar") }.sorted().map { stage.appendingPathComponent("lib/\($0)").path }
        let cp = "\(stage.appendingPathComponent("prep").path)/*:\(stage.appendingPathComponent("lib/hooks.jar").path)"
        let out = try ProcessRunner.run(java, ["-cp", cp, "dev.betavk.mcprep.JarCheck", AppPaths.readableJar.path, candidate.path, "dev.betavk.hooks.PatchSet"] + libPaths)
        guard let data = out.split(separator: "\n").last.map({ Data($0.utf8) }) else { throw DownloadError(message: "The checker printed nothing") }
        return try JSONDecoder().decode(JarReport.self, from: data)
    }

    /// A jar is a zip; a quick look catches renamed files and folders before any work is done.
    nonisolated private static func requireJar(_ url: URL) throws {
        var isDir: ObjCBool = false
        guard FileManager.default.fileExists(atPath: url.path, isDirectory: &isDir), !isDir.boolValue else { throw DownloadError(message: "Choose a .jar file.") }
        guard (try? ProcessRunner.run("/usr/bin/unzip", ["-l", url.path])) != nil else { throw DownloadError(message: "That file is not a valid jar (it cannot be opened as a zip).") }
    }
}

private extension JSONDecoder {
    static var iso: JSONDecoder { let d = JSONDecoder(); d.dateDecodingStrategy = .iso8601; return d }
}
private extension JSONEncoder {
    static var iso: JSONEncoder { let e = JSONEncoder(); e.dateEncodingStrategy = .iso8601; return e }
}
