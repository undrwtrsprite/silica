import Foundation

enum ProcessRunner {
    /// Runs a tool to completion on the calling thread (call from a background task). Throws on a non-zero exit status.
    @discardableResult
    static func run(_ launchPath: String, _ args: [String], captureStderr: Bool = false, cwd: String? = nil) throws -> String {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: launchPath)
        p.arguments = args
        if let cwd { p.currentDirectoryURL = URL(fileURLWithPath: cwd) }
        let out = Pipe(), err = Pipe()
        p.standardOutput = out
        p.standardError = err
        try p.run()
        let outData = out.fileHandleForReading.readDataToEndOfFile()
        let errData = err.fileHandleForReading.readDataToEndOfFile()
        p.waitUntilExit()
        let text = String(decoding: captureStderr ? errData + outData : outData, as: UTF8.self)
        guard p.terminationStatus == 0 else {
            throw DownloadError(message: "\((launchPath as NSString).lastPathComponent) failed (\(p.terminationStatus)): \(String(decoding: errData, as: UTF8.self).prefix(300))")
        }
        return text
    }
}
