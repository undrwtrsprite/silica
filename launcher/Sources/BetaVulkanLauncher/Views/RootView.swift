import SwiftUI

struct RootView: View {
    @EnvironmentObject private var state: AppState
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorScheme) private var colorScheme
    @EnvironmentObject private var runner: GameRunner

    @State private var sceneAwake = true
    private var showsScene: Bool { state.tab == .play && !state.wizardOpen }

    var body: some View {
        ZStack {
            Theme.bg.ignoresSafeArea()
            // The scene stays in the tree and only its opacity changes, so it really fades in when Play opens and out when you leave.
            HeroScene(paused: runner.isRunning || !sceneAwake)
                .overlay {
                    LinearGradient(colors: [.black.opacity(0.5), .clear], startPoint: .bottomLeading, endPoint: UnitPoint(x: 0.55, y: 0.45))
                }
                .overlay {
                    LinearGradient(stops: [.init(color: Theme.bg.opacity(0), location: 0.42), .init(color: Theme.bg.opacity(0.10), location: 0.55),
                                           .init(color: Theme.bg.opacity(0.35), location: 0.68), .init(color: Theme.bg.opacity(0.72), location: 0.84),
                                           .init(color: Theme.bg, location: 1)], startPoint: .top, endPoint: .bottom)
                }
                .ignoresSafeArea()
                .allowsHitTesting(false)
                .opacity(showsScene ? 1 : 0)
                .animation(reduceMotion ? nil : (showsScene ? .easeInOut(duration: 0.9) : .easeOut(duration: 0.3)), value: showsScene)   // in slowly, out quickly
                .onChange(of: showsScene) { _, shown in
                    if shown { sceneAwake = true; return }
                    // keep drawing until it has faded out, then stop so a hidden scene costs nothing
                    Task {
                        try? await Task.sleep(for: .seconds(0.6))
                        if !showsScene { sceneAwake = false }
                    }
                }
            VStack(spacing: 0) {
                LauncherNavigation()
                    .environment(\.colorScheme, state.tab == .play ? .dark : colorScheme)
                GeometryReader { geometry in
                    ScrollOrPlain {
                        ZStack(alignment: .topLeading) {
                            Group {
                                switch state.tab {
                                case .play: PlayView(viewportHeight: geometry.size.height)
                                case .worlds: WorldsView().padding(32)
                                case .jars: JarsView().padding(32)
                                case .settings: SettingsView().padding(32)
                                case .advanced: AdvancedView().padding(32)
                                }
                            }
                            .modifier(PageFadeIn(enabled: !reduceMotion))
                            .id(state.tab)   // a new page each time, and the old one is gone at once: nothing half-faded can be left behind when you switch quickly
                            .frame(maxWidth: .infinity, alignment: .topLeading)
                        }
                    }
                }
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

/// The floating bar at the top: the app's name, then the four pages. The selected page carries the accent underline.
private struct LauncherNavigation: View {
    @EnvironmentObject private var state: AppState
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Namespace private var selection

    var body: some View {
        HStack(spacing: 30) {
            HStack(spacing: 24) {
                ForEach(Array(Tab.allCases.enumerated()), id: \.element) { index, tab in
                    navItem(tab)
                        .keyboardShortcut(KeyEquivalent(Character(String(index + 1))), modifiers: .command)
                }
            }
        }
        .animation(reduceMotion ? nil : LauncherMotion.change, value: state.tab)
        .padding(.horizontal, 14)
        .background {
            if state.tab == .play {
                Capsule().fill(.ultraThinMaterial)
            } else {
                Capsule().fill(Theme.sidebar)
            }
        }
        .overlay { Capsule().strokeBorder(Theme.stroke).allowsHitTesting(false) }
        .padding(.horizontal, 24)
        .padding(.top, 28)
        .padding(.bottom, 8)
        .frame(maxWidth: .infinity)
        .overlay(alignment: .leading) {
            // the name sits in the corner beside the window buttons, so the bar itself is only the four pages
            HStack(spacing: 8) {
                Image(nsImage: Theme.appMark)
                    .resizable().frame(width: 28, height: 28).accessibilityHidden(true)
                Text("Silica").font(.system(size: 17, weight: .semibold))
            }
            .padding(.leading, 84)
            .offset(y: 10)   // the bar has 28 pt above it and 8 below: this puts the name on the bar's own centre line
        }
        .overlay(alignment: .trailing) {
            AccountMenu()
                .padding(.trailing, 28)
                .offset(y: 10)   // same centre line as the bar and the logo
        }
    }

    private func navItem(_ tab: Tab) -> some View {
        let selected = state.tab == tab
        return Button { state.tab = tab } label: {
            PillControlLabel(title: tab.rawValue, symbol: tab.symbol, selected: selected)
                .overlay(alignment: .bottom) {
                    if selected {
                        Rectangle().fill(Theme.accent).frame(height: 2)
                            .matchedGeometryEffect(id: "underline", in: selection)
                    }
                }
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
    }

    private func card<Actions: View>(title: String, detail: String, @ViewBuilder actions: () -> Actions) -> some View {
        HStack(spacing: 14) {
            VStack(alignment: .leading, spacing: 3) {
                Text(title).font(.system(size: 13.5, weight: .semibold))
                Text(detail).font(.system(size: 11.5)).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            }
            actions()
        }
        .padding(.horizontal, 16).padding(.vertical, 12)
        .frame(maxWidth: 440, alignment: .leading)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(Theme.stroke.opacity(2)))
        .shadow(color: .black.opacity(0.25), radius: 18, y: 6)
        .transition(.opacity.combined(with: .move(edge: .bottom)))
    }
}

/// Fades a page in (with a small slide) when it first appears. The page it replaces is removed immediately, so quick tab switches cannot leave pieces of an old page on screen.
private struct PageFadeIn: ViewModifier {
    let enabled: Bool
    @State private var shown = false

    func body(content: Content) -> some View {
        content
            .opacity(enabled && !shown ? 0 : 1)
            .offset(y: enabled && !shown ? 5 : 0)
            .onAppear {
                guard enabled else { return }
                withAnimation(LauncherMotion.change) { shown = true }
            }
    }
}
