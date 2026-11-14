import SwiftUI

struct SettingsView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @EnvironmentObject private var state: AppState
    @EnvironmentObject private var updates: UpdateManager
    @EnvironmentObject private var runner: GameRunner
    @Local private var advanced = false

    private let sizes: [(String, Int, Int)] = [("1280 × 720", 1280, 720), ("1600 × 900", 1600, 900), ("1920 × 1080", 1920, 1080), ("2560 × 1440", 2560, 1440)]

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            PageHeader(title: "Settings", subtitle: "The defaults are tuned for Apple Silicon. Change things only if you want to.")

            Card(title: "Performance", subtitle: "Pick what matters to you. The game applies it the next time it starts.") {
                HStack(spacing: 12) {
                    ForEach(Preset.allCases) { p in presetCard(p) }
                }
                DisclosureRow(title: "Fine-tune", open: $advanced) {
                    VStack(alignment: .leading, spacing: 12) {
                        Stepper("Memory: \(String(format: "%.1f", Double(state.settings.memoryMB) / 1024)) GB", value: $state.settings.memoryMB, in: 1024...16384, step: 512)
                        Text("The game needs about 250 MB plus 70 KB per loaded chunk; 2 to 4 GB is plenty.").font(.system(size: 11.5)).foregroundStyle(.secondary)
                        Stepper("Chunk building threads: \(state.settings.meshWorkers)", value: $state.settings.meshWorkers, in: 0...4)
                        Stepper("Terrain generation threads: \(state.settings.genWorkers)", value: $state.settings.genWorkers, in: 0...6)
                        Text("One building thread was fastest in testing; 0 turns a pool off (the original behaviour).").font(.system(size: 11.5)).foregroundStyle(.secondary)
                        Toggle("Vsync", isOn: $state.settings.vsync)
                        Picker("Garbage collector", selection: $state.settings.gcProfile) { ForEach(GCProfile.allCases) { Text($0.title).tag($0) } }
                    }
                }
            }

            Card(title: "Display") {
                VStack(alignment: .leading, spacing: 2) {
                    Toggle("Show player skins and capes", isOn: Binding(get: { state.settings.showSkins != false }, set: { state.settings.showSkins = $0 }))
                    Text("The game asks Mojang's servers for the skin and cape of each player by name. Turn it off to keep names on this Mac; everyone then shows as the default skin.")
                        .font(.system(size: 11.5)).foregroundStyle(.secondary)
                }
                HStack {
                    Text("Mode").font(.system(size: 13))
                    Spacer()
                    Segmented(options: [false, true], selection: $state.settings.fullscreen) { $0 ? "Fullscreen" : "Window" }
                }
                if !state.settings.fullscreen {
                    HStack {
                        Text("Window size").font(.system(size: 13))
                        Spacer()
                        Picker("", selection: Binding(get: { sizes.first { $0.1 == state.settings.width && $0.2 == state.settings.height }?.0 ?? "Custom" },
                                                      set: { name in if let s = sizes.first(where: { $0.0 == name }) { state.settings.width = s.1; state.settings.height = s.2 } })) {
                            ForEach(sizes, id: \.0) { Text($0.0).tag($0.0) }
                            if !sizes.contains(where: { $0.1 == state.settings.width && $0.2 == state.settings.height }) { Text("Custom (\(state.settings.width) × \(state.settings.height))").tag("Custom") }
                        }
                        .labelsHidden().frame(width: 190)
                    }
                }
            }

            Card(title: "Files and setup") {
                PathRow(title: "Game data and worlds", path: state.settings.gameDir) { state.choose(\.gameDir, message: "Choose the game data folder") }
                HStack(spacing: 10) {
                    Button { NSWorkspace.shared.activateFileViewerSelecting([AppPaths.support]) } label: { Label("Show in Finder", systemImage: "folder") }.buttonStyle(SecondaryButtonStyle())
                    Button { state.reopenSetup() } label: { Label("Run setup again", systemImage: "arrow.clockwise") }.buttonStyle(SecondaryButtonStyle())
                }
                Text("Java, the game and the sounds live in ~/Library/Application Support/BetaVulkan. Nothing of Mojang's is stored inside this app.").font(.system(size: 11.5)).foregroundStyle(.secondary)
                if AppPaths.bundledStage == nil {
                    PathRow(title: "Game build (gradle :hooks:stageGame)", path: state.settings.stageDir) { state.choose(\.stageDir, message: "Choose hooks/build/stage") }
                }
            }

            Card(title: "About") {
                HStack(spacing: 14) {
                    Image(nsImage: NSApplication.shared.applicationIconImage).resizable().frame(width: 52, height: 52)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Silica \(UpdateManager.currentVersionText)").font(.system(size: 14, weight: .semibold))
                        Text("Minecraft Beta 1.7.3 on Apple Silicon with a Vulkan renderer. You bring your own copy of the game; Silica ships none of Mojang's code or assets.").font(.system(size: 12)).foregroundStyle(.secondary)
                    }
                }
                HStack(spacing: 12) {
                    Text(updateLine).font(.system(size: 12.5)).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                    Spacer()
                    if let info = updates.available {
                        Button("Update to \(info.version)") { Task { await updates.install(info) } }.buttonStyle(PrimaryButtonStyle(large: false)).disabled(runner.isRunning || updates.isBusy)
                    } else {
                        Button("Check for updates") { updates.bannerDismissed = false; Task { await updates.check(quiet: false) } }.buttonStyle(SecondaryButtonStyle()).disabled(updates.isBusy)
                    }
                }
                VStack(alignment: .leading, spacing: 2) {
                    Toggle("Check for updates when Silica starts", isOn: Binding(get: { state.settings.checkForUpdates != false }, set: { state.settings.checkForUpdates = $0 }))
                    Text("Silica asks GitHub for the latest release and sends nothing but its version number. Updates are installed only if they carry the project's signature.")
                        .font(.system(size: 11.5)).foregroundStyle(.secondary)
                }
            }
        }
    }

    private var updateLine: String {
        switch updates.status {
        case .idle: return "Updates are checked from GitHub."
        case .checking: return "Checking…"
        case .upToDate: return "You have the latest version."
        case .available(let info): return "Version \(info.version) is available."
        case .downloading(let p): return "Downloading \(Int((p * 100).rounded()))%…"
        case .verifying: return "Checking the signature…"
        case .installing: return "Installing…"
        case .failed(let message): return message
        }
    }

    private func presetCard(_ p: Preset) -> some View {
        let on = state.settings.preset == p
        return Button { withAnimation(reduceMotion ? nil : LauncherMotion.change) { state.settings.apply(p) } } label: {
            VStack(alignment: .leading, spacing: 6) {
                HStack {
                    Text(p.title).font(.system(size: 14, weight: .semibold, design: .rounded))
                    Spacer()
                    Image(systemName: on ? "checkmark.circle.fill" : "circle").foregroundStyle(on ? AnyShapeStyle(Theme.accent) : AnyShapeStyle(Color.secondary.opacity(0.5)))
                }
                Text(p.detail).font(.system(size: 11.5)).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true).lineLimit(3)
            }
            .padding(14).frame(maxWidth: .infinity, minHeight: 92, alignment: .topLeading)
            .background(Color.primary.opacity(on ? 0.07 : 0.035), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(on ? AnyShapeStyle(Theme.accent) : AnyShapeStyle(Color.clear), lineWidth: 2))
        }
        .buttonStyle(.plain)
    }
}

struct DisclosureRow<Content: View>: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let title: String
    @Binding var open: Bool
    @ViewBuilder var content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Button { withAnimation(reduceMotion ? nil : LauncherMotion.change) { open.toggle() } } label: {
                HStack(spacing: 6) {
                    Image(systemName: "chevron.right").font(.system(size: 10, weight: .bold)).rotationEffect(.degrees(open ? 90 : 0))
                    Text(title).font(.system(size: 12.5, weight: .semibold))
                }
                .foregroundStyle(.secondary).contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            if open { content.transition(.opacity.combined(with: .offset(y: -6))) }
        }
    }
}

struct PathRow: View {
    let title: String
    let path: String
    let choose: () -> Void
    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) { Text(title).font(.system(size: 13)); Text(path).font(.system(size: 11.5)).foregroundStyle(.secondary).lineLimit(1).truncationMode(.middle) }
            Spacer()
            Button("Choose…", action: choose).buttonStyle(SecondaryButtonStyle())
        }
    }
}
