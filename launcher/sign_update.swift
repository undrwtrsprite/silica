// Signs a release disk image for Silica's updater: swift sign_update.swift Silica-1.2.3.dmg  ->  Silica-1.2.3.dmg.sig
// The private key is a base64 Ed25519 key in ~/.config/silica/update-signing.key (override with SILICA_SIGNING_KEY).
// Keep it private and backed up: it is the only thing that lets a build update installed copies of Silica.
// The matching public key is compiled into the app (UpdateConfig.publicKeyBase64 in Core/Updater.swift).
import CryptoKit
import Foundation

guard CommandLine.arguments.count == 2 else { FileHandle.standardError.write(Data("usage: swift sign_update.swift <file.dmg>\n".utf8)); exit(2) }
let file = URL(fileURLWithPath: CommandLine.arguments[1])
let keyPath = ProcessInfo.processInfo.environment["SILICA_SIGNING_KEY"] ?? NSString(string: "~/.config/silica/update-signing.key").expandingTildeInPath
guard let text = try? String(contentsOfFile: keyPath, encoding: .utf8),
      let raw = Data(base64Encoded: text.trimmingCharacters(in: .whitespacesAndNewlines)),
      let key = try? Curve25519.Signing.PrivateKey(rawRepresentation: raw) else {
    FileHandle.standardError.write(Data("No usable signing key at \(keyPath)\n".utf8)); exit(1)
}
guard let bytes = try? Data(contentsOf: file, options: .mappedIfSafe) else { FileHandle.standardError.write(Data("cannot read \(file.path)\n".utf8)); exit(1) }
let signature = try key.signature(for: bytes).base64EncodedString()
try (signature + "\n").write(to: URL(fileURLWithPath: file.path + ".sig"), atomically: true, encoding: .utf8)
print("signed \(file.lastPathComponent) -> \(file.lastPathComponent).sig")
