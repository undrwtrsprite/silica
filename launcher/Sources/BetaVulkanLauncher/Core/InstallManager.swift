import Foundation

/// First-run setup: an arm64 JDK 21 (Adoptium), Mojang's b1.7.3 client jar and sound assets. Nothing of Mojang's is bundled with the
/// app; every file is downloaded from Mojang's own servers and verified against the SHA-1 Mojang publishes.
@MainActor
final class InstallManager: ObservableObject {
    enum State: Equatable {
        case pending, running(Double, String), done(String), failed(String)
        var isDone: Bool { if case .done = self { return true } else { return false } }
        var isRunning: Bool { if case .running = self { return true } else { return false } }
    }

    @Published var jdk: State = .pending
    @Published var client: State = .pending
    @Published var assets: State = .pending
    @Published var prepared: State = .pending
    /// Folder of the staged game build (its prep/ holds our jar preparation tool); set by the app before setting up.
    var stageDir = ""
    @Published var busy = false

    /// Where the Minecraft jar comes from: Mojang's servers, or a copy you already have.
    enum ClientSource: Equatable { case mojang, local(URL) }
    @Published var clientSource: ClientSource = .mojang
    /// The official b1.7.3 client's SHA-1 (what Mojang's own manifest publishes).
    static let clientSHA1 = "43db9b498cb67058d2e12d394e6507722e71bb45"

    /// A b1.7.3 client the Mojang launcher or an older install already left on this Mac.
    static func detectExistingClient() -> URL? {
        let home = FileManager.default.homeDirectoryForCurrentUser
        let candidates = [
            "Library/Application Support/minecraft/versions/b1.7.3/b1.7.3.jar",
            "Library/Application Support/minecraft/bin/minecraft.jar",
            ".minecraft/versions/b1.7.3/b1.7.3.jar",
        ].map { home.appendingPathComponent($0) }
        return candidates.first { FileManager.default.fileExists(atPath: $0.path) && ((try? Hashing.sha1(of: $0)) == clientSHA1) }
    }

    struct StepInfo: Identifiable {
        let id: String, title: String, detail: String, symbol: String
        let state: State
    }

    /// The four things setup does, in order, with plain-language descriptions.
    var steps: [StepInfo] {
        [
            StepInfo(id: "jdk", title: "Java for Apple Silicon", detail: "A native Java 21 runtime, kept inside Silica's folder.", symbol: "cup.and.saucer.fill", state: jdk),
            StepInfo(id: "client", title: "Minecraft Beta 1.7.3", detail: clientSource == .mojang ? "Downloaded from Mojang's servers and verified." : "Your own copy, checked against Mojang's checksum.", symbol: "shippingbox.fill", state: client),
            StepInfo(id: "prepare", title: "Patch the game", detail: "Silica's improvements are applied to your copy on this Mac. Nothing is shared.", symbol: "wand.and.stars", state: prepared),
            StepInfo(id: "assets", title: "Sounds and music", detail: "Downloaded from Mojang's servers and verified.", symbol: "speaker.wave.2.fill", state: assets),
        ]
    }

    /// 0 to 1 across all steps, weighted by how long each usually takes.
    var overall: Double {
        func v(_ s: State) -> Double {
            switch s { case .done: return 1; case .running(let p, _): return p; default: return 0 }
        }
        return 0.20 * v(jdk) + 0.10 * v(client) + 0.40 * v(prepared) + 0.30 * v(assets)
    }

    var failure: (title: String, message: String)? {
        for st in steps { if case .failed(let m) = st.state { return (st.title, m) } }
        return nil
    }

    var currentTitle: String {
        for st in steps { if case .running(_, let m) = st.state { return m } }
        return busy ? "Working…" : ""
    }

    var javaPath: URL? { Self.findJava() }
    var ready: Bool { jdk.isDone && client.isDone && prepared.isDone && assets.isDone }

    init() { refresh() }

    /// Re-check what is already on disk (cheap; no network).
    func refresh() {
        if let java = Self.findJava() {
            jdk = .done("Java at \(java.deletingLastPathComponent().deletingLastPathComponent().lastPathComponent)")
        } else { jdk = .pending }
        client = FileManager.default.fileExists(atPath: AppPaths.clientJar.path) ? .done("client.jar present") : .pending
        prepared = FileManager.default.fileExists(atPath: AppPaths.readableJar.path) && FileManager.default.fileExists(atPath: AppPaths.resources.appendingPathComponent("terrain.png").path)
            ? .done("readable jar present") : .pending
        let index = AppPaths.assets.appendingPathComponent("indexes/b1.7.json")
        assets = FileManager.default.fileExists(atPath: index.path) ? .done("asset index present") : .pending
    }

    static func findJava() -> URL? {
        guard let e = FileManager.default.enumerator(at: AppPaths.jdk, includingPropertiesForKeys: nil, options: [.skipsHiddenFiles]) else { return nil }
        for case let url as URL in e where url.lastPathComponent == "java" && url.path.contains("/Contents/Home/bin/") { return url }
        return nil
    }

    func installAll(player: VerifiedMinecraftPlayer?) async {
        guard player?.isValid == true, !busy else { return }
        busy = true
        defer { busy = false }
        if !jdk.isDone { await installJDK() }
        guard jdk.isDone else { return }
        if !client.isDone { await installClient() }
        guard client.isDone else { return }
        if !prepared.isDone { await prepareJar() }
        guard prepared.isDone else { return }
        if !assets.isDone { await installAssets() }
    }

    // MARK: JDK

    private func installJDK() async {
        jdk = .running(0, "Looking up the latest Temurin 21 for Apple Silicon…")
        do {
            let list = try await Downloader.json(URL(string: "https://api.adoptium.net/v3/assets/latest/21/hotspot?architecture=aarch64&image_type=jdk&os=mac&vendor=eclipse")!)
            guard let first = (list as? [[String: Any]])?.first,
                  let pkg = (first["binary"] as? [String: Any])?["package"] as? [String: Any],
                  let link = (pkg["link"] as? String).flatMap(URL.init(string:)),
                  let sum = pkg["checksum"] as? String else { throw DownloadError(message: "Unexpected Adoptium API response") }
            let tmp = FileManager.default.temporaryDirectory.appendingPathComponent("jdk21-\(UUID().uuidString).tar.gz")
            try await Downloader.fetch(link, to: tmp, checksum: .sha256(sum)) { [weak self] p in
                Task { @MainActor in self?.jdk = .running(p, "Downloading JDK 21 (arm64)") }
            }
            jdk = .running(1, "Unpacking…")
            AppPaths.ensure(AppPaths.jdk)
            try await Task.detached { try ProcessRunner.run("/usr/bin/tar", ["-xzf", tmp.path, "-C", AppPaths.jdk.path]) }.value
            try? FileManager.default.removeItem(at: tmp)
            guard let java = Self.findJava() else { throw DownloadError(message: "java not found after unpacking") }
            let arch = try await Task.detached { try Self.javaArch(java) }.value
            guard arch == "aarch64" else { throw DownloadError(message: "The JDK reports os.arch=\(arch), not aarch64") }
            jdk = .done("Native arm64 JDK 21 (os.arch=aarch64)")
        } catch { jdk = .failed(error.localizedDescription) }
    }

    nonisolated static func javaArch(_ java: URL) throws -> String {
        let out = try ProcessRunner.run(java.path, ["-XshowSettings:properties", "-version"], captureStderr: true)
        for line in out.split(separator: "\n") where line.contains("os.arch") {
            return line.split(separator: "=").last.map { $0.trimmingCharacters(in: .whitespaces) } ?? "?"
        }
        return "?"
    }

    // MARK: Mojang client + assets

    private func versionJSON() async throws -> [String: Any] {
        let manifest = try await Downloader.json(URL(string: "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")!)
        guard let v = ((manifest as? [String: Any])?["versions"] as? [[String: Any]])?.first(where: { $0["id"] as? String == "b1.7.3" }),
              let url = (v["url"] as? String).flatMap(URL.init(string:)) else { throw DownloadError(message: "b1.7.3 not found in Mojang's version manifest") }
        guard let json = try await Downloader.json(url) as? [String: Any] else { throw DownloadError(message: "Bad version JSON") }
        return json
    }

    private func installClient() async {
        if case .local(let url) = clientSource {
            client = .running(0.3, "Checking your copy…")
            do {
                let sum = try await Task.detached { try Hashing.sha1(of: url) }.value
                guard sum == Self.clientSHA1 else {
                    throw DownloadError(message: "That file is not the original Beta 1.7.3 client (its checksum does not match Mojang's). Choose the b1.7.3 jar from the Minecraft launcher, or let Silica download it.")
                }
                AppPaths.ensure(AppPaths.clientJar.deletingLastPathComponent())
                try? FileManager.default.removeItem(at: AppPaths.clientJar)
                try FileManager.default.copyItem(at: url, to: AppPaths.clientJar)
                client = .done("Your copy of b1.7.3 verified")
            } catch { client = .failed(error.localizedDescription) }
            return
        }
        client = .running(0, "Reading Mojang's version manifest…")
        do {
            let json = try await versionJSON()
            guard let c = (json["downloads"] as? [String: Any])?["client"] as? [String: Any],
                  let url = (c["url"] as? String).flatMap(URL.init(string:)), let sha1 = c["sha1"] as? String else { throw DownloadError(message: "No client download in version JSON") }
            try await Downloader.fetch(url, to: AppPaths.clientJar, checksum: .sha1(sha1)) { [weak self] p in
                Task { @MainActor in self?.client = .running(p, "Downloading client.jar") }
            }
            client = .done("client.jar verified (SHA-1 \(sha1.prefix(8))…)")
        } catch { client = .failed(error.localizedDescription) }
    }

    /// Turns the downloaded client jar into the readable jar our patches apply to (the same steps as scripts/mcprep.sh): RetroMCP-Java (open source)
    /// fetches the b1.7.3 name table, `mcprep` remaps and widens the jar, and the jar's resources are unpacked next to it. All of it stays on this machine.
    private func prepareJar() async {
        guard let java = javaPath else { prepared = .failed("Install the JDK first"); return }
        let prep = URL(fileURLWithPath: stageDir).appendingPathComponent("prep")
        guard FileManager.default.fileExists(atPath: prep.appendingPathComponent("mcprep.jar").path) else {
            prepared = .failed("No game build in \(stageDir): run `gradle :hooks:stageGame` first"); return
        }
        prepared = .running(0.05, "Fetching the name table (RetroMCP-Java)…")
        do {
            let rm = AppPaths.retroMCP
            AppPaths.ensure(rm)
            let cli = rm.appendingPathComponent("RetroMCP-Java-CLI.jar")
            if !FileManager.default.fileExists(atPath: cli.path) {
                try await Downloader.fetch(URL(string: "https://github.com/MCPHackers/RetroMCP-Java/releases/latest/download/RetroMCP-Java-CLI.jar")!, to: cli, checksum: .none) { [weak self] p in
                    Task { @MainActor in self?.prepared = .running(0.05 + 0.15 * p, "Downloading RetroMCP-Java") }
                }
            }
            let tiny = rm.appendingPathComponent("conf/mappings.tiny")
            let client = AppPaths.clientJar, readable = AppPaths.readableJar, resources = AppPaths.resources
            let jp = java.path, rmPath = rm.path, preps = prep.path
            if !FileManager.default.fileExists(atPath: tiny.path) {
                prepared = .running(0.25, "Setting up the b1.7.3 name table…")
                try await Task.detached { try ProcessRunner.run(jp, ["-jar", cli.path, "setup", "b1.7.3"], cwd: rmPath) }.value
            }
            prepared = .running(0.6, "Remapping your jar…")
            AppPaths.ensure(readable.deletingLastPathComponent())
            try await Task.detached {
                try ProcessRunner.run(jp, ["-cp", "\(preps)/*", "dev.betavk.mcprep.Main", client.path, tiny.path, readable.path])
            }.value
            prepared = .running(0.85, "Unpacking textures…")
            try? FileManager.default.removeItem(at: resources)
            AppPaths.ensure(resources)
            try await Task.detached { try ProcessRunner.run("/usr/bin/unzip", ["-q", "-o", client.path, "-x", "*.class", "META-INF/*", "-d", resources.path]) }.value
            prepared = .done("readable jar ready")
        } catch { prepared = .failed(error.localizedDescription) }
    }

    private func installAssets() async {
        assets = .running(0, "Reading the asset index…")
        do {
            let json = try await versionJSON()
            guard let idx = json["assetIndex"] as? [String: Any], let url = (idx["url"] as? String).flatMap(URL.init(string:)), let sha1 = idx["sha1"] as? String
            else { throw DownloadError(message: "No assetIndex in version JSON") }
            let indexFile = AppPaths.assets.appendingPathComponent("indexes/b1.7.json")   // the game reads this exact name
            try await Downloader.fetch(url, to: indexFile, checksum: .sha1(sha1))
            let data = try Data(contentsOf: indexFile)
            guard let objects = (try JSONSerialization.jsonObject(with: data) as? [String: Any])?["objects"] as? [String: [String: Any]] else { throw DownloadError(message: "Bad asset index") }
            let unique = Dictionary(objects.values.compactMap { o -> (String, Int)? in
                guard let h = o["hash"] as? String, let s = o["size"] as? Int else { return nil }
                return (h, s)
            }, uniquingKeysWith: { a, _ in a })
            let total = unique.count
            let counter = Counter()
            try await withThrowingTaskGroup(of: Void.self) { group in
                var iterator = unique.makeIterator()
                var inFlight = 0
                func next() -> (String, Int)? { iterator.next() }
                while let (hash, size) = next() {
                    let dest = AppPaths.assets.appendingPathComponent("objects/\(hash.prefix(2))/\(hash)")
                    if let attrs = try? FileManager.default.attributesOfItem(atPath: dest.path), (attrs[.size] as? Int) == size {
                        await counter.bump()
                        continue
                    }
                    if inFlight >= 8 { try await group.next(); inFlight -= 1 }
                    group.addTask {
                        let src = URL(string: "https://resources.download.minecraft.net/\(hash.prefix(2))/\(hash)")!
                        try await Downloader.fetch(src, to: dest, checksum: .sha1(hash))
                        await counter.bump()
                    }
                    inFlight += 1
                    let done = await counter.value
                    assets = .running(Double(done) / Double(total), "Downloading sounds \(done)/\(total)")
                }
                try await group.waitForAll()
            }
            assets = .done("\(total) sound files verified")
        } catch { assets = .failed(error.localizedDescription) }
    }
}

actor Counter {
    private(set) var value = 0
    func bump() { value += 1 }
}
