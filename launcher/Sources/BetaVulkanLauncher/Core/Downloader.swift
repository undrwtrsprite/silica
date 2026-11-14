import Foundation

struct DownloadError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

/// Download with progress and checksum verification. A file that fails verification is deleted and reported, never used.
final class Downloader: NSObject, URLSessionDownloadDelegate {
    private var continuation: CheckedContinuation<URL, Error>?
    private var progress: (@Sendable (Double) -> Void)?
    private lazy var session = URLSession(configuration: .default, delegate: self, delegateQueue: nil)

    enum Checksum { case sha1(String), sha256(String), none }

    static func fetch(_ url: URL, to destination: URL, checksum: Checksum = .none, progress: (@Sendable (Double) -> Void)? = nil) async throws {
        let d = Downloader()
        let tmp = try await d.run(url, progress: progress)
        defer { try? FileManager.default.removeItem(at: tmp) }
        switch checksum {
        case .sha1(let want):
            let got = try Hashing.sha1(of: tmp)
            guard got.lowercased() == want.lowercased() else { throw DownloadError(message: "SHA-1 mismatch for \(url.lastPathComponent): expected \(want), got \(got)") }
        case .sha256(let want):
            let got = try Hashing.sha256(of: tmp)
            guard got.lowercased() == want.lowercased() else { throw DownloadError(message: "SHA-256 mismatch for \(url.lastPathComponent): expected \(want), got \(got)") }
        case .none: break
        }
        AppPaths.ensure(destination.deletingLastPathComponent())
        try? FileManager.default.removeItem(at: destination)
        try FileManager.default.moveItem(at: tmp, to: destination)
    }

    static func json(_ url: URL) async throws -> Any {
        let (data, response) = try await URLSession.shared.data(from: url)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw DownloadError(message: "HTTP \((response as? HTTPURLResponse)?.statusCode ?? -1) for \(url)") }
        return try JSONSerialization.jsonObject(with: data)
    }

    private func run(_ url: URL, progress: (@Sendable (Double) -> Void)?) async throws -> URL {
        self.progress = progress
        return try await withCheckedThrowingContinuation { cont in
            self.continuation = cont
            self.session.downloadTask(with: url).resume()
        }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64, totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        if totalBytesExpectedToWrite > 0 { progress?(Double(totalBytesWritten) / Double(totalBytesExpectedToWrite)) }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        if let http = downloadTask.response as? HTTPURLResponse, http.statusCode != 200 {
            continuation?.resume(throwing: DownloadError(message: "HTTP \(http.statusCode) for \(downloadTask.originalRequest?.url?.absoluteString ?? "?")"))
        } else {
            // the system deletes `location` when this returns: move it somewhere stable first
            let stable = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
            do {
                try FileManager.default.moveItem(at: location, to: stable)
                continuation?.resume(returning: stable)
            } catch {
                continuation?.resume(throwing: error)
            }
        }
        continuation = nil
        session.finishTasksAndInvalidate()
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if let error, let c = continuation {
            c.resume(throwing: error)
            continuation = nil
        }
    }
}
