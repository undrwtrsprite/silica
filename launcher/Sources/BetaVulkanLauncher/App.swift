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
                .environmentObject(state.jars)
                .environmentObject(state.instances)
                .environmentObject(state.updates)
                .task {
                    if state.settings.checkForUpdates != false { await state.updates.check(quiet: true) }
                }
                .preferredColorScheme(.dark)
                .frame(minWidth: 960, minHeight: 620)
        }
        .defaultSize(width: 1120, height: 700)
        .windowStyle(.hiddenTitleBar)
    }
}
