import Foundation

/// Catalog of RetroMCP-supported Golden Age Minecraft versions (Alpha 1.0–Beta 1.7.3, plus experimental Infdev and Beta 1.8) and on-demand jar preparation.
@MainActor
final class VersionCatalog: ObservableObject {
    struct McVersion: Codable, Identifiable, Hashable {
        let id: String
        let type: String
        let releaseTime: String?
        let url: String?
        let resources: String?

        /// Supported experimental targets outside the core Alpha 1.0–Beta 1.7.3 line.
        static let experimentalIDs: Set<String> = [
            "b1.8.1",
            "b1.8",
            "inf-20100630-1835",
            "inf-20100630-1340",
        ]

        var isExperimental: Bool { Self.experimentalIDs.contains(id) }
        var isInfdev: Bool { id.hasPrefix("inf-") }
        var isAlpha: Bool { type == "old_alpha" && !isInfdev }
        var era: String {
            if isInfdev { return "Infdev" }
            return isAlpha ? "Alpha" : "Beta"
        }

        var displayName: String {
            if id == "inf-20100630-1835" { return "Infdev 2010-06-30 (18:35)" }
            if id == "inf-20100630-1340" { return "Infdev 2010-06-30 (13:40)" }
            if id.hasPrefix("a") { return "Alpha " + id.dropFirst() }
            if id.hasPrefix("b") { return "Beta " + id.dropFirst() }
            return id
        }

        var pickerLabel: String {
            isExperimental ? "\(displayName) (Experimental)" : displayName
        }
    }

    /// Bump whenever Canonicalizer/mcprep rules change so existing prepared jars rebuild cleanly from cached vanilla jars.
    static let prepFormatVersion = "0.3.0"

    @Published private(set) var versions: [McVersion] = VersionCatalog.fallbackVersions
    @Published private(set) var loading = false
    @Published var busyMessage: String? = nil

    private static let catalogURL = URL(string: "https://mcphackers.org/versionsV3/versions.json")!

    private static let fallbackVersions: [McVersion] = [
        McVersion(id: "b1.7.3", type: "old_beta", releaseTime: "2011-07-08T00:00:00+00:00",
                  url: "https://launchermeta.mojang.com/v1/packages/5d5a79df098d8b797169e505995817021094e9bb/b1.7.3.json",
                  resources: "https://mcphackers.org/versionsV2/b1.7.3.zip"),
        McVersion(id: "b1.5_01", type: "old_beta", releaseTime: "2011-04-20T00:00:00+00:00",
                  url: nil, resources: "https://mcphackers.org/versionsV2/b1.5_01.zip"),
        McVersion(id: "b1.2_02", type: "old_beta", releaseTime: "2011-01-21T00:00:00+00:00",
                  url: nil, resources: "https://mcphackers.org/versionsV2/b1.2_02.zip"),
        McVersion(id: "a1.2.6", type: "old_alpha", releaseTime: "2010-12-03T00:00:00+00:00",
                  url: nil, resources: "https://mcphackers.org/versionsV2/a1.2.6.zip"),
        McVersion(id: "a1.1.2_01", type: "old_alpha", releaseTime: "2010-09-23T00:00:00+00:00",
                  url: nil, resources: "https://mcphackers.org/versionsV2/a1.1.2_01.zip"),
        McVersion(id: "a1.0.4", type: "old_alpha", releaseTime: "2010-07-09T00:00:00+00:00",
                  url: nil, resources: "https://mcphackers.org/versionsV2/a1.0.4.zip"),
    ]

    init() {
        loadCached()
    }

    func version(for id: String) -> McVersion? {
        versions.first { $0.id == id }
    }

    func pickerVersions(includeExperimental: Bool) -> [McVersion] {
        includeExperimental ? versions : versions.filter { !$0.isExperimental }
    }

    func isPrepared(_ versionID: String) -> Bool {
        let readable = AppPaths.readableJar(for: versionID).path
        let terrain = AppPaths.resources(for: versionID).appendingPathComponent("terrain.png").path
        let stamp = (try? String(contentsOf: AppPaths.prepStamp(for: versionID), encoding: .utf8))?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return FileManager.default.fileExists(atPath: readable)
            && FileManager.default.fileExists(atPath: terrain)
            && stamp == Self.prepFormatVersion
    }

    func loadCached() {
        guard let data = try? Data(contentsOf: AppPaths.versionCatalogCache),
              let decoded = Self.parseCatalog(data, includeExperimental: true), !decoded.isEmpty else { return }
        versions = decoded
    }

    func refresh() async {
        guard !loading else { return }
        loading = true
        defer { loading = false }
        do {
            let (data, _) = try await URLSession.shared.data(from: Self.catalogURL)
            if let parsed = Self.parseCatalog(data, includeExperimental: true), !parsed.isEmpty {
                versions = parsed
                AppPaths.ensure(AppPaths.versionCatalogCache.deletingLastPathComponent())
                try? data.write(to: AppPaths.versionCatalogCache, options: .atomic)
            }
        } catch {
            // Keep cached/fallback list when offline.
        }
    }

    nonisolated static func parseCatalog(_ data: Data, includeExperimental: Bool = false) -> [McVersion]? {
        guard let raw = try? JSONDecoder().decode([McVersion].self, from: data) else { return nil }
        var seen = Set<String>()
        var result: [(version: McVersion, key: VersionKey)] = []
        for entry in raw {
            guard (entry.type == "old_alpha" || entry.type == "old_beta"),
                  entry.resources?.isEmpty == false,
                  entry.url?.isEmpty == false,
                  let key = VersionKey.parse(id: entry.id, type: entry.type, includeExperimental: includeExperimental),
                  seen.insert(entry.id).inserted else { continue }
            result.append((entry, key))
        }
        result.sort { lhs, rhs in
            if lhs.key != rhs.key { return lhs.key > rhs.key }
            return lhs.version.id > rhs.version.id
        }
        return result.map(\.version)
    }

    struct VersionKey: Comparable, Equatable {
        let era: Int          // -1 = Infdev (experimental), 0 = Alpha, 1 = Beta
        let major: Int
        let minor: Int
        let patch: Int
        let hotfix: Int
        let cleanBuild: Int   // 1 = clean release (no '-' suffix), 0 = test/timestamped build
        let suffix: String

        static func parse(id: String, type: String, includeExperimental: Bool = false) -> VersionKey? {
            if includeExperimental && McVersion.experimentalIDs.contains(id) && id.hasPrefix("inf-") {
                let parts = id.dropFirst(4).split(separator: "-")
                let day = parts.first.flatMap { Int($0) } ?? 0
                let hhmm = parts.count > 1 ? (Int(parts[1]) ?? 0) : 0
                return VersionKey(era: -1, major: day, minor: hhmm, patch: 0, hotfix: 0, cleanBuild: 1, suffix: "")
            }
            let era: Int
            let rest: Substring
            if id.hasPrefix("b"), type == "old_beta" {
                era = 1
                rest = id.dropFirst()
            } else if id.hasPrefix("a"), type == "old_alpha" {
                era = 0
                rest = id.dropFirst()
            } else {
                return nil
            }
            let hyphenParts = rest.split(separator: "-", maxSplits: 1, omittingEmptySubsequences: false)
            let versionPart = hyphenParts[0]
            let suffix = hyphenParts.count > 1 ? String(hyphenParts[1]) : ""
            let underscoreParts = versionPart.split(separator: "_", maxSplits: 1, omittingEmptySubsequences: false)
            let nums = underscoreParts[0].split(separator: ".")
            guard nums.count >= 2, nums.count <= 3,
                  let major = Int(nums[0]),
                  let minor = Int(nums[1]) else { return nil }
            let patch = nums.count == 3 ? (Int(nums[2]) ?? -1) : 0
            guard patch >= 0 else { return nil }
            if era == 1 && (major != 1 || minor > 7) {
                guard includeExperimental && McVersion.experimentalIDs.contains(id) else { return nil }
            }
            if era == 0 && major != 1 { return nil }
            let hotfix = underscoreParts.count > 1 ? (Int(underscoreParts[1]) ?? 0) : 0
            return VersionKey(
                era: era,
                major: major,
                minor: minor,
                patch: patch,
                hotfix: hotfix,
                cleanBuild: suffix.isEmpty ? 1 : 0,
                suffix: suffix
            )
        }

        static func < (lhs: VersionKey, rhs: VersionKey) -> Bool {
            let l = (lhs.era, lhs.major, lhs.minor, lhs.patch, lhs.hotfix, lhs.cleanBuild)
            let r = (rhs.era, rhs.major, rhs.minor, rhs.patch, rhs.hotfix, rhs.cleanBuild)
            if l != r { return l < r }
            return lhs.suffix < rhs.suffix
        }
    }

    /// Ensures the given Alpha or Beta version is downloaded, remapped, canonicalized, and ready to launch.
    func prepareIfNeeded(_ versionID: String, java: URL, stageDir: String) async throws {
        if isPrepared(versionID) { return }
        if versions.count <= Self.fallbackVersions.count {
            await refresh()
        }
        guard let ver = version(for: versionID) else {
            throw DownloadError(message: "Unknown Minecraft version \(versionID).")
        }
        let prepDir = URL(fileURLWithPath: stageDir).appendingPathComponent("prep")
        guard FileManager.default.fileExists(atPath: prepDir.appendingPathComponent("mcprep.jar").path) else {
            throw DownloadError(message: "No game build in \(stageDir): run `gradle :hooks:stageGame` first.")
        }

        let client = AppPaths.clientJar(for: versionID)
        let readable = AppPaths.readableJar(for: versionID)
        let resources = AppPaths.resources(for: versionID)
        let mappings = AppPaths.mappings(for: versionID)
        AppPaths.ensure(client.deletingLastPathComponent())

        if !FileManager.default.fileExists(atPath: client.path) {
            guard let metaStr = ver.url, let metaURL = URL(string: metaStr) else {
                throw DownloadError(message: "No download manifest for \(ver.displayName).")
            }
            busyMessage = "Fetching \(ver.displayName) manifest…"
            let (metaData, _) = try await URLSession.shared.data(from: metaURL)
            guard let json = try JSONSerialization.jsonObject(with: metaData) as? [String: Any],
                  let dl = (json["downloads"] as? [String: Any])?["client"] as? [String: Any],
                  let jarStr = dl["url"] as? String, let jarURL = URL(string: jarStr) else {
                throw DownloadError(message: "No client jar URL in \(ver.displayName) manifest.")
            }
            let sha1 = dl["sha1"] as? String
            busyMessage = "Downloading \(ver.displayName)…"
            try await Downloader.fetch(jarURL, to: client, checksum: sha1.map { .sha1($0) } ?? .none) { _ in }
        }

        if !FileManager.default.fileExists(atPath: mappings.path) {
            guard let resStr = ver.resources, let resURL = URL(string: resStr) else {
                throw DownloadError(message: "No mappings URL for \(ver.displayName).")
            }
            busyMessage = "Downloading \(ver.displayName) mappings…"
            let tmpZip = FileManager.default.temporaryDirectory.appendingPathComponent("silica-map-\(versionID)-\(UUID().uuidString).zip")
            defer { try? FileManager.default.removeItem(at: tmpZip) }
            try await Downloader.fetch(resURL, to: tmpZip, checksum: .none) { _ in }
            AppPaths.ensure(mappings.deletingLastPathComponent())
            let mapData = try await Task.detached {
                try Self.extractEntry(zip: tmpZip, entry: "mappings.tiny")
            }.value
            try mapData.write(to: mappings, options: .atomic)
        }

        busyMessage = "Preparing \(ver.displayName)…"
        let jp = java.path, preps = prepDir.path
        _ = try await Task.detached {
            try ProcessRunner.run(jp, ["-cp", "\(preps)/*", "dev.betavk.mcprep.Main", client.path, mappings.path, readable.path])
        }.value

        busyMessage = "Unpacking \(ver.displayName) textures…"
        try? FileManager.default.removeItem(at: resources)
        AppPaths.ensure(resources)
        _ = try await Task.detached {
            try ProcessRunner.run("/usr/bin/unzip", ["-q", "-o", client.path, "-x", "*.class", "META-INF/*", "-d", resources.path])
        }.value
        try? Self.prepFormatVersion.write(to: AppPaths.prepStamp(for: versionID), atomically: true, encoding: .utf8)
        busyMessage = nil
    }

    private nonisolated static func extractEntry(zip: URL, entry: String) throws -> Data {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/unzip")
        p.arguments = ["-p", zip.path, entry]
        let out = Pipe()
        p.standardOutput = out
        p.standardError = FileHandle.nullDevice
        try p.run()
        let data = out.fileHandleForReading.readDataToEndOfFile()
        p.waitUntilExit()
        guard p.terminationStatus == 0, !data.isEmpty else {
            throw DownloadError(message: "Could not extract \(entry) from \(zip.lastPathComponent).")
        }
        return data
    }
}
