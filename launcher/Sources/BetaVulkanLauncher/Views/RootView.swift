import SwiftUI

struct RootView: View {
    @EnvironmentObject private var state: AppState
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @EnvironmentObject private var runner: GameRunner

    private var activeBackdropMode: DitherBackdrop.Mode {
        switch state.tab {
        case .play: return .launchCanyon
        case .worlds: return .worldsTopo
        case .jars: return .jarsMosaic
        case .settings: return .settingsWave
        case .advanced: return .advancedPulse
        }
    }

    var body: some View {
        ZStack {
            Theme.bg.ignoresSafeArea()

            HStack(spacing: 0) {
                LauncherSidebar()
                ZStack(alignment: .topLeading) {
                    if state.tab != .play && !state.wizardOpen {
                        DitherBackdrop(mode: activeBackdropMode, paused: runner.isRunning)
                            .frame(height: 210)
                            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                            .ignoresSafeArea()
                            .allowsHitTesting(false)
                    }
                    GeometryReader { geometry in
                        ScrollOrPlain {
                            Group {
                                switch state.tab {
                                case .play: PlayView(viewportHeight: geometry.size.height).padding(28)
                                case .worlds: WorldsView().padding(28)
                                case .jars: JarsView().padding(28)
                                case .settings: SettingsView().padding(28)
                                case .advanced: AdvancedView().padding(28)
                                }
                            }
                            .modifier(PageFadeIn(enabled: !reduceMotion))
                            .id(state.tab)
                            .frame(maxWidth: .infinity, alignment: .topLeading)
                        }
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .allowsHitTesting(!state.wizardOpen)
            .accessibilityHidden(state.wizardOpen)

            UpdateBanner()
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                .padding(24)
                .allowsHitTesting(!state.wizardOpen)

            if state.wizardOpen {
                SetupWizard()
                    .transition(.opacity)
            }
        }
        .animation(reduceMotion ? nil : LauncherMotion.change, value: state.wizardOpen)
        .alert("Silica", isPresented: Binding(get: { state.message != nil }, set: { if !$0 { state.message = nil } })) {
            Button("OK", role: .cancel) { state.message = nil }
        } message: { Text(state.message ?? "") }
    }
}

/// Fixed left sidebar rail: brand, active instance status, vertical navigation list, and bottom account dock.
private struct LauncherSidebar: View {
    @EnvironmentObject private var state: AppState
    @EnvironmentObject private var install: InstallManager
    @EnvironmentObject private var runner: GameRunner
    @EnvironmentObject private var jars: JarLibrary
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Namespace private var selection

    private var ready: Bool { install.ready && state.stageReady }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // App identity below traffic lights
            HStack(spacing: 10) {
                if let url = Bundle.main.url(forResource: "SilicaMark", withExtension: "png"),
                   let image = NSImage(contentsOf: url) {
                    Image(nsImage: image)
                        .resizable().frame(width: 28, height: 28)
                        .clipShape(RoundedRectangle(cornerRadius: 7, style: .continuous))
                        .accessibilityHidden(true)
                } else {
                    RoundedRectangle(cornerRadius: 7, style: .continuous)
                        .fill(LinearGradient(colors: [Theme.tint, Theme.accent], startPoint: .topLeading, endPoint: .bottomTrailing))
                        .frame(width: 28, height: 28)
                        .overlay(RoundedRectangle(cornerRadius: 7, style: .continuous).strokeBorder(.white.opacity(0.18), lineWidth: 1))
                        .accessibilityHidden(true)
                }
                VStack(alignment: .leading, spacing: 1) {
                    Text("Silica").font(.system(size: 16, weight: .bold)).tracking(-0.3)
                    Text(UpdateManager.currentVersionText)
                        .font(.system(size: 10.5, weight: .medium, design: .monospaced))
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.top, 42)
            .padding(.horizontal, 18)
            .padding(.bottom, 18)

            // Active instance badge
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Circle()
                        .fill(runner.isRunning ? Theme.sand : (ready ? Theme.accent : Theme.sand))
                        .frame(width: 7, height: 7)
                    Text(runner.isRunning ? "Running" : (ready ? "Beta 1.7.3" : "Setup needed"))
                        .font(.system(size: 12, weight: .semibold))
                }
                Text(jars.jar(state.settings.jarID)?.name ?? "Silica patched · \(state.settings.backend == .vulkan ? "Vulkan" : "OpenGL")")
                    .font(.system(size: 11))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            .padding(.horizontal, 12).padding(.vertical, 10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(Theme.card, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(Theme.stroke, lineWidth: 1))
            .padding(.horizontal, 14)
            .padding(.bottom, 16)

            // Vertical navigation links
            VStack(alignment: .leading, spacing: 4) {
                ForEach(Array(Tab.allCases.enumerated()), id: \.element) { index, tab in
                    navRow(tab, count: count(for: tab))
                        .keyboardShortcut(KeyEquivalent(Character(String(index + 1))), modifiers: .command)
                }
            }
            .animation(reduceMotion ? nil : LauncherMotion.change, value: state.tab)
            .padding(.horizontal, 12)

            Spacer(minLength: 12)

            // Pinned account dock at bottom of left rail
            VStack(alignment: .leading, spacing: 8) {
                Rectangle().fill(Theme.stroke).frame(height: 1)
                AccountMenu()
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
            }
            .padding(.bottom, 8)
        }
        .frame(width: 222)
        .frame(maxHeight: .infinity)
        .background(Theme.sidebar)
        .overlay(alignment: .trailing) { Rectangle().fill(Theme.stroke).frame(width: 1) }
        .ignoresSafeArea(.container, edges: .top)
    }

    private func count(for tab: Tab) -> Int? {
        switch tab {
        case .worlds: return state.worlds.isEmpty ? nil : state.worlds.count
        case .jars: return jars.jars.isEmpty ? nil : jars.jars.count
        default: return nil
        }
    }

    private func navRow(_ tab: Tab, count: Int?) -> some View {
        let selected = state.tab == tab
        return Button { state.tab = tab } label: {
            HStack(spacing: 10) {
                Image(systemName: tab.symbol)
                    .font(.system(size: 13.5, weight: .medium))
                    .foregroundStyle(selected ? Theme.accent : .secondary)
                    .frame(width: 18)
                Text(tab.rawValue)
                    .font(.system(size: 13.5, weight: selected ? .semibold : .medium))
                    .foregroundStyle(selected ? Color.primary : Color.secondary)
                Spacer(minLength: 0)
                if let count {
                    Text("\(count)")
                        .font(.system(size: 11, weight: .semibold, design: .monospaced))
                        .foregroundStyle(.secondary)
                        .padding(.horizontal, 6).padding(.vertical, 2)
                        .background(Color.primary.opacity(0.07), in: Capsule())
                }
            }
            .padding(.horizontal, 12).padding(.vertical, 9)
            .background {
                if selected {
                    RoundedRectangle(cornerRadius: 10, style: .continuous)
                        .fill(Theme.cardHigh)
                        .overlay(alignment: .leading) {
                            RoundedRectangle(cornerRadius: 1)
                                .fill(Theme.accent)
                                .frame(width: 3, height: 16)
                                .offset(x: 2)
                        }
                        .matchedGeometryEffect(id: "nav", in: selection)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

/// A small card in the bottom corner when a newer version exists. It shows progress while updating and stays out of the way otherwise.
private struct UpdateBanner: View {
    @EnvironmentObject private var updates: UpdateManager
    @EnvironmentObject private var runner: GameRunner
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Group {
            if let info = updates.available, !updates.bannerDismissed {
                card(title: "Silica \(info.version) is available", detail: runner.isRunning ? "Close the game to update." : "Installs in a moment and restarts Silica.") {
                    Button("Later") { updates.bannerDismissed = true }.buttonStyle(SecondaryButtonStyle())
                    Button("Update") { Task { await updates.install(info) } }.buttonStyle(PrimaryButtonStyle(large: false)).disabled(runner.isRunning)
                }
            } else if case .downloading(let p) = updates.status {
                card(title: "Downloading the update…", detail: "\(Int((p * 100).rounded()))%") { ProgressView(value: p).frame(width: 90) }
            } else if updates.status == .verifying || updates.status == .installing {
                card(title: updates.status == .verifying ? "Checking the update…" : "Installing…", detail: "Silica restarts when it is done.") { ProgressView().controlSize(.small) }
            } else if let message = updates.installError {
                card(title: "Update did not install", detail: message) {
                    Button("OK") { updates.clearInstallError() }.buttonStyle(SecondaryButtonStyle())
                }
            }
        }
        .animation(reduceMotion ? nil : LauncherMotion.change, value: updates.status)
        .animation(reduceMotion ? nil : LauncherMotion.change, value: updates.bannerDismissed)
    }

    private func card<A: View>(title: String, detail: String, @ViewBuilder action: () -> A) -> some View {
        HStack(spacing: 14) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 13, weight: .semibold))
                Text(detail).font(.system(size: 11.5)).foregroundStyle(.secondary)
            }
            action()
        }
        .padding(.horizontal, 16).padding(.vertical, 12)
        .surface(radius: 14, fill: Theme.cardHigh)
        .shadow(color: .black.opacity(0.28), radius: 22, y: 10)
    }
}

private struct PageFadeIn: ViewModifier {
    let enabled: Bool
    @State private var shown = false

    func body(content: Content) -> some View {
        content
            .opacity(enabled ? (shown ? 1 : 0) : 1)
            .offset(y: enabled ? (shown ? 0 : 4) : 0)
            .onAppear {
                guard enabled, !shown else { return }
                withAnimation(LauncherMotion.change) { shown = true }
            }
    }
}
