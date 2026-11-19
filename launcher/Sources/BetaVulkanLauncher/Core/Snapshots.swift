import AppKit
import SwiftUI

/// `BetaVulkan --snapshots <dir>`: renders every page in light and dark mode, the setup stages, and dithered scenes.
@MainActor
enum Snapshots {
    private static func write<V: View>(_ view: V, _ name: String, _ out: URL, width: CGFloat, height: CGFloat) {
        let r = ImageRenderer(content: view.frame(width: width, height: height))
        r.scale = 2
        guard let img = r.nsImage, let tiff = img.tiffRepresentation, let rep = NSBitmapImageRep(data: tiff),
              let png = rep.representation(using: .png, properties: [:]) else {
            print("[snapshots] render failed for \(name)")
            return
        }
        try? png.write(to: out.appendingPathComponent("\(name).png"))
        print("[snapshots] wrote \(name).png \(Int(img.size.width))x\(Int(img.size.height))")
    }

    static func run(into dir: String) {
        let out = URL(fileURLWithPath: dir)
        try? FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)
        let state = AppState()
        func root(_ scheme: ColorScheme) -> some View {
            RootView()
                .environmentObject(state)
                .environmentObject(state.install)
                .environmentObject(state.runner)
                .environmentObject(state.jars)
                .environmentObject(state.instances)
                .environmentObject(state.updates)
                .environmentObject(state.versions)
                .environment(\.colorScheme, scheme)
        }

        state.install.jdk = .done("Java")
        state.install.client = .done("client")
        state.install.prepared = .done("ok")
        state.install.assets = .done("ok")
        state.wizardOpen = false

        let env = ProcessInfo.processInfo.environment
        let pageW = CGFloat(Double(env["SILICA_SNAPSHOT_WIDTH"] ?? "") ?? 1080)
        let pageH = CGFloat(Double(env["SILICA_SNAPSHOT_HEIGHT"] ?? "") ?? 700)

        for scheme in [ColorScheme.dark, .light] {
            for tab in Tab.allCases {
                state.tab = tab
                write(root(scheme), "\(tab.rawValue.lowercased())-\(scheme == .dark ? "dark" : "light")", out, width: pageW, height: pageH)
            }
            state.tab = .advanced
            write(root(scheme), "advanced-compact-\(scheme == .dark ? "dark" : "light")", out, width: 960, height: 700)
            state.advancedTab = .logs
            write(root(scheme), "advanced-logs-\(scheme == .dark ? "dark" : "light")", out, width: 960, height: 620)
            state.advancedTab = .benchmark
        }

        state.tab = .play
        state.install.jdk = .pending
        state.install.client = .pending
        state.install.prepared = .pending
        state.install.assets = .pending
        state.wizardOpen = true
        write(root(.dark), "setup-welcome", out, width: 1080, height: 700)

        state.install.jdk = .done("Native arm64 JDK 21")
        state.install.client = .done("client.jar verified")
        state.install.prepared = .running(0.55, "Remapping your jar…")
        state.install.busy = true
        write(root(.dark), "setup-running", out, width: 1080, height: 700)

        state.install.busy = false
        state.install.prepared = .done("readable jar ready")
        state.install.assets = .done("326 sound files verified")
        write(root(.dark), "setup-done", out, width: 1080, height: 700)

        write(
            DitherHorizonStage(vulkan: true, running: false, fixedTime: 7.5).environment(\.colorScheme, .dark),
            "dither-horizon-dark", out, width: 840, height: 260
        )
    }
}
