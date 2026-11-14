import AppKit
import SwiftUI

@main
struct BetaVulkanLauncherApp: App {
    @StateObject private var state = AppState()

    init() {
        // A SwiftPM executable is not an app bundle: make it a regular, focusable app.
        NSApplication.shared.setActivationPolicy(.regular)
        NSApplication.shared.activate(ignoringOtherApps: true)
        if let i = CommandLine.arguments.firstIndex(of: "--snapshots"), i + 1 < CommandLine.arguments.count {
            let dir = CommandLine.arguments[i + 1]
            Task { @MainActor in Snapshots.run(into: dir); exit(0) }
        }
        if CommandLine.arguments.contains("--selftest") {
            Task { @MainActor in exit(await SelfTest.run(state: AppState())) }
        }
    }

    var body: some Scene {
        WindowGroup("Silica") {
            RootView()
                .environmentObject(state)
                .environmentObject(state.install)
                .environmentObject(state.runner)
                .frame(minWidth: 940, minHeight: 600)
        }
        .defaultSize(width: 1080, height: 700)
        .windowStyle(.hiddenTitleBar)
    }
}
