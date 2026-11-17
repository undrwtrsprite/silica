import AppKit
import CryptoKit
import Foundation

/// A dotted version such as 0.1.2 (a leading "v" is ignored). Compared number by number, so 0.10 is newer than 0.9.
struct AppVersion: Comparable, Equatable, CustomStringConvertible {
    let parts: [Int]
    init?(_ text: String) {
        var t = text.trimmingCharacters(in: .whitespaces)
        if t.hasPrefix("v") || t.hasPrefix("V") { t.removeFirst() }
        let pieces = t.split(separator: ".", omittingEmptySubsequences: false).map { Int($0) }
        guard !pieces.isEmpty, !pieces.contains(nil) else { return nil }
        parts = pieces.compactMap { $0 }
    }
    var description: String { parts.map(String.init).joined(separator: ".") }
    static func < (a: AppVersion, b: AppVersion) -> Bool {
        let n = max(a.parts.count, b.parts.count)
        for i in 0..<n {
            let x = i < a.parts.count ? a.parts[i] : 0, y = i < b.parts.count ? b.parts[i] : 0
            if x != y { return x < y }
        }
        return false
    }
    static func == (a: AppVersion, b: AppVersion) -> Bool { !(a < b) && !(b < a) }
}

/// Metadata for the newest published GitHub release (used to display release tagline & notes in Settings even when up to date).
struct LatestRelease: Equatable {
    let version: AppVersion
    let tagline: String
    let notes: String
    let pageURL: URL
}

/// A newer release that can be installed: the disk image and the signature file that goes with it.
struct UpdateInfo: Equatable {
    let version: AppVersion
    let tagline: String
    let notes: String
    let dmgURL: URL
    let signatureURL: URL
    let pageURL: URL
}

enum UpdateConfig {
    /// Where releases are published. Only the latest published release (never a draft or pre-release) is looked at.
    /// Convention: all published GitHub releases must carry a release tagline (`name`) and concise changelog notes (`body`).
    static let latestReleaseURL = URL(string: "https://api.github.com/repos/undrwtrsprite/silica/releases/latest")!
    /// Updates must carry an Ed25519 signature made with the matching private key, which is kept off this repository.
    /// Whoever controls the GitHub repository cannot push a build the app will accept without that key.
    static let publicKeyBase64 = "xFyno2eTnMcb7+mRwkwLq2eKZtgdX5/nVpVnN1LOlBA="
}

enum UpdateParsing {
    /// Parses the newest published GitHub release's version, tagline (`name`), and notes (`body`). Returns nil for drafts/pre-releases.
    static func latestRelease(from data: Data) -> LatestRelease? {
        guard let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              root["draft"] as? Bool != true, root["prerelease"] as? Bool != true,
              let tag = root["tag_name"] as? String, let version = AppVersion(tag),
              let page = (root["html_url"] as? String).flatMap(URL.init(string:)) else { return nil }
        let rawName = (root["name"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let tagline = rawName.isEmpty ? "Silica \(version)" : rawName
        let notes = (root["body"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        return LatestRelease(version: version, tagline: tagline, notes: notes, pageURL: page)
    }

    /// Reads GitHub's "latest release" reply. Returns nil when it is not newer than `current`, is a draft or pre-release, or lacks a disk image and signature.
    static func newerRelease(from data: Data, than current: AppVersion) -> UpdateInfo? {
        guard let latest = latestRelease(from: data), latest.version > current,
              let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let assets = root["assets"] as? [[String: Any]] else { return nil }
        func asset(_ suffix: String) -> URL? {
            assets.first { ($0["name"] as? String)?.hasSuffix(suffix) == true }
                .flatMap { $0["browser_download_url"] as? String }.flatMap(URL.init(string:))
        }
        guard let dmg = asset(".dmg"), let sig = asset(".dmg.sig"),
              dmg.host == "github.com", sig.host == "github.com" else { return nil }
        return UpdateInfo(version: latest.version, tagline: latest.tagline, notes: latest.notes, dmgURL: dmg, signatureURL: sig, pageURL: latest.pageURL)
    }
}

enum UpdateVerifier {
    /// True when `signatureBase64` (the text of the .sig file) is a valid Ed25519 signature of the file's bytes under `publicKeyBase64`.
    static func isValid(file: URL, signatureBase64: String, publicKeyBase64: String = UpdateConfig.publicKeyBase64) -> Bool {
        guard let keyData = Data(base64Encoded: publicKeyBase64),
              let key = try? Curve25519.Signing.PublicKey(rawRepresentation: keyData),
              let sig = Data(base64Encoded: signatureBase64.trimmingCharacters(in: .whitespacesAndNewlines)),
              let bytes = try? Data(contentsOf: file, options: .mappedIfSafe) else { return false }
        return key.isValidSignature(sig, for: bytes)
    }
}

enum UpdateStatus: Equatable {
    case idle
    case checking
    case upToDate
    case available(UpdateInfo)
    case downloading(Double)
    case verifying
    case installing
    case failed(String)
}

/// Looks for a newer release, and installs it in place: download, check the signature, swap the app, restart.
@MainActor
final class UpdateManager: ObservableObject {
    @Published private(set) var status: UpdateStatus = .idle
    @Published private(set) var latestRelease: LatestRelease?
    /// Hidden for this run after the person dismisses the banner.
    @Published var bannerDismissed = false
    /// Set when installing an update failed, so the banner can say why. A failed check only shows in Settings.
    @Published private(set) var installError: String?
    func clearInstallError() { installError = nil; if case .failed = status { status = .idle } }

    static var currentVersion: AppVersion {
        AppVersion(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "") ?? AppVersion("0")!
    }
    static var currentVersionText: String { Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "dev" }

    var available: UpdateInfo? { if case .available(let u) = status { return u }; return nil }
    var isBusy: Bool {
        switch status { case .checking, .downloading, .verifying, .installing: return true; default: return false }
    }

    /// Asks GitHub for the latest release. `quiet` hides "up to date" and errors, for the automatic check at start-up.
    func check(quiet: Bool) async {
        guard !isBusy else { return }
        status = .checking
        var request = URLRequest(url: UpdateConfig.latestReleaseURL, timeoutInterval: 15)
        request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        request.setValue("Silica/\(Self.currentVersionText)", forHTTPHeaderField: "User-Agent")   // the version only: nothing about the person or the Mac
        do {
            let (data, response) = try await URLSession.shared.data(for: request)
            guard (response as? HTTPURLResponse)?.statusCode == 200 else {
                status = quiet ? .idle : .failed("GitHub did not answer. Try again later.")
                return
            }
            if let latest = UpdateParsing.latestRelease(from: data) {
                latestRelease = latest
            }
            if let info = UpdateParsing.newerRelease(from: data, than: Self.currentVersion) {
                status = .available(info)
            } else {
                status = quiet ? .idle : .upToDate
            }
        } catch {
            status = quiet ? .idle : .failed("Could not reach GitHub. Check your connection.")
        }
    }

    /// Downloads the new version, checks its signature, and replaces this app. The app quits and the new one opens.
    func install(_ info: UpdateInfo) async {
        guard !isBusy else { return }
        let app = Bundle.main.bundleURL
        installError = nil
        if let problem = Self.cannotReplace(app) {
            installError = problem
            status = .failed(problem)
            return
        }
        let work = FileManager.default.temporaryDirectory.appendingPathComponent("silica-update-\(UUID().uuidString)", isDirectory: true)
        do {
            try FileManager.default.createDirectory(at: work, withIntermediateDirectories: true)
            let dmg = work.appendingPathComponent("Silica.dmg")
            status = .downloading(0)
            try await Downloader.fetch(info.dmgURL, to: dmg) { p in Task { @MainActor [weak self] in self?.status = .downloading(p) } }
            let sigText = String(decoding: try await URLSession.shared.data(from: info.signatureURL).0, as: UTF8.self)

            status = .verifying
            guard await Task.detached(priority: .userInitiated, operation: { UpdateVerifier.isValid(file: dmg, signatureBase64: sigText) }).value else {
                throw DownloadError(message: "The download's signature did not match, so it was not installed.")
            }

            status = .installing
            let newApp = try await Task.detached(priority: .userInitiated) { try Self.extractApp(from: dmg, into: work, expecting: info.version) }.value
            try Self.handOver(newApp: newApp, replacing: app, workDir: work)
            NSApplication.shared.terminate(nil)
        } catch {
            try? FileManager.default.removeItem(at: work)
            let message = (error as? DownloadError)?.message ?? "The update could not be installed: \(error.localizedDescription)"
            installError = message
            status = .failed(message)
        }
    }

    /// Why this copy of the app cannot be replaced in place, or nil if it can.
    nonisolated static func cannotReplace(_ app: URL) -> String? {
        let path = app.path
        if path.contains("/AppTranslocation/") || path.hasPrefix("/Volumes/") {
            return "Move Silica into the Applications folder first, then update."
        }
        if !FileManager.default.isWritableFile(atPath: app.deletingLastPathComponent().path) {
            return "Silica's folder is not writable, so it cannot update itself. Download the new version from the website instead."
        }
        if app.pathExtension != "app" { return "Updates only work in the installed app, not a development build." }
        return nil
    }

    /// Mounts the disk image, copies Silica.app out, and checks it is the right app at the right version before anything is replaced.
    nonisolated static func extractApp(from dmg: URL, into work: URL, expecting version: AppVersion, bundleIdentifier: String = Bundle.main.bundleIdentifier ?? "dev.betavk.launcher") throws -> URL {
        let mount = work.appendingPathComponent("mount", isDirectory: true)
        try FileManager.default.createDirectory(at: mount, withIntermediateDirectories: true)
        try ProcessRunner.run("/usr/bin/hdiutil", ["attach", "-nobrowse", "-readonly", "-noverify", "-mountpoint", mount.path, dmg.path])
        defer { _ = try? ProcessRunner.run("/usr/bin/hdiutil", ["detach", mount.path, "-force"]) }
        let source = mount.appendingPathComponent("Silica.app")
        guard FileManager.default.fileExists(atPath: source.path) else { throw DownloadError(message: "The update did not contain Silica.app.") }
        let staged = work.appendingPathComponent("new", isDirectory: true)
        try FileManager.default.createDirectory(at: staged, withIntermediateDirectories: true)
        let copy = staged.appendingPathComponent("Silica.app")
        try ProcessRunner.run("/usr/bin/ditto", [source.path, copy.path])

        let plist = copy.appendingPathComponent("Contents/Info.plist")
        guard let info = NSDictionary(contentsOf: plist),
              info["CFBundleIdentifier"] as? String == bundleIdentifier,
              let text = info["CFBundleShortVersionString"] as? String, AppVersion(text) == version else {
            throw DownloadError(message: "The update is not the version it claims to be, so it was not installed.")
        }
        try ProcessRunner.run("/usr/bin/codesign", ["--verify", "--deep", "--strict", copy.path])
        return copy
    }

    /// Starts a small script that waits for this app to quit, swaps the folders (keeping the old one until the new one is in place), and opens the new app.
    nonisolated static func handOver(newApp: URL, replacing app: URL, workDir: URL, waitingFor pid: Int32 = ProcessInfo.processInfo.processIdentifier, reopen: Bool = true) throws {
        let script = workDir.appendingPathComponent("apply.sh")
        let body = """
        #!/bin/sh
        PID="$1"; NEW="$2"; OLD="$3"; WORK="$4"
        while kill -0 "$PID" 2>/dev/null; do sleep 0.3; done
        BACKUP="$OLD.previous"
        rm -rf "$BACKUP"
        if mv "$OLD" "$BACKUP"; then
          if mv "$NEW" "$OLD"; then
            xattr -dr com.apple.quarantine "$OLD" 2>/dev/null
            rm -rf "$BACKUP"
          else
            mv "$BACKUP" "$OLD"
          fi
        fi
        [ "$5" = "no" ] || open "$OLD"
        rm -rf "$WORK"
        """
        try body.write(to: script, atomically: true, encoding: .utf8)
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/bin/sh")
        p.arguments = ["-c", "nohup /bin/sh \"$0\" \"$@\" >/dev/null 2>&1 &", script.path, String(pid), newApp.path, app.path, workDir.path, reopen ? "yes" : "no"]
        try p.run()
        p.waitUntilExit()
    }
}
