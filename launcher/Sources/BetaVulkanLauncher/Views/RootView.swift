import SwiftUI

/// Clean website-inspired shell: integrated top navigation bar, subtle dithered dawn atmosphere, and calm breathable workspace.
struct RootView: View {
    @EnvironmentObject private var state: AppState
    @EnvironmentObject private var runner: GameRunner
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Local private var pointer = CGPoint(x: 0.5, y: 0.3)

    var body: some View {
        ZStack {
            Theme.bg.ignoresSafeArea()

            GeometryReader { geo in
                DitherAurora(
                    vulkan: state.activeSettings.backend == .vulkan,
                    paused: runner.isRunning,
                    pointer: pointer
                )
                .frame(height: 220)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                .ignoresSafeArea()
                .allowsHitTesting(false)
                .onContinuousHover { phase in
                    if case .active(let loc) = phase, geo.size.width > 0, geo.size.height > 0 {
                        pointer = CGPoint(
                            x: min(1, max(0, loc.x / geo.size.width)),
                            y: min(1, max(0, loc.y / geo.size.height))
                        )
                    }
                }
            }

            VStack(spacing: 0) {
                TopNavBar()
                Rectangle().fill(Theme.stroke).frame(height: 1)

                GeometryReader { viewport in
                    ScrollOrPlain {
                        Group {
                            switch state.tab {
                            case .play: PlayView(pointer: pointer, viewportHeight: viewport.size.height)
                            case .instances: InstancesView()
                            case .worlds: WorldsView()
                            case .settings: SettingsView()
                            case .advanced: AdvancedView()
                            }
                        }
                        .frame(maxWidth: 960, alignment: .topLeading)
                        .frame(maxWidth: .infinity, alignment: .top)
                        .padding(.horizontal, 28)
                        .padding(.vertical, 24)
                        .modifier(PageFadeIn(enabled: !reduceMotion))
                        .id(state.tab)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .allowsHitTesting(!state.wizardOpen)
            .accessibilityHidden(state.wizardOpen)

            UpdateToast()
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
                .padding(20)
                .allowsHitTesting(!state.wizardOpen)

            if state.wizardOpen {
                SetupWizard()
                    .transition(.opacity)
            }
        }
        .animation(reduceMotion ? nil : LauncherMotion.change, value: state.wizardOpen)
        .alert("Silica", isPresented: Binding(get: { state.message != nil }, set: { if !$0 { state.message = nil } })) {
            Button("OK", role: .cancel) { state.message = nil }
        } message: {
            Text(state.message ?? "")
        }
    }
}

/// Website-style masthead bar: Silica mark + calm centered links with the signature green pixel dot + AccountMenu.
private struct TopNavBar: View {
    @EnvironmentObject private var state: AppState
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Namespace private var activeDot

    var body: some View {
        HStack(spacing: 16) {
            HStack(spacing: 9) {
                Image(nsImage: Theme.appMark)
                    .resizable()
                    .frame(width: 24, height: 24)
                    .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
                    .accessibilityHidden(true)
                Text("Silica")
                    .font(.system(size: 16, weight: .semibold))
                    .tracking(-0.3)
            }
            .padding(.leading, 76)

            HStack(spacing: 4) {
                ForEach(Array(Tab.allCases.enumerated()), id: \.element) { index, tab in
                    navItem(tab)
                        .keyboardShortcut(KeyEquivalent(Character(String(index + 1))), modifiers: .command)
                }
            }
            .padding(.leading, 14)
            .animation(reduceMotion ? nil : LauncherMotion.change, value: state.tab)

            Spacer(minLength: 12)

            AccountMenu()
                .padding(.trailing, 18)
        }
        .frame(height: 54)
        .background(Theme.bar)
    }

    private func navItem(_ tab: Tab) -> some View {
        let selected = state.tab == tab
        return Button {
            state.tab = tab
        } label: {
            Text(tab.rawValue)
                .font(.system(size: 13.5, weight: selected ? .semibold : .medium))
                .foregroundStyle(selected ? Theme.text : Theme.dim)
                .padding(.horizontal, 12)
                .padding(.vertical, 7)
                .background(selected ? Color.primary.opacity(0.05) : Color.clear,
                            in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                .overlay(alignment: .bottom) {
                    if selected {
                        Rectangle()
                            .fill(Theme.accent)
                            .frame(width: 5, height: 5)
                            .offset(y: 4)
                            .matchedGeometryEffect(id: "nav-dot", in: activeDot)
                    }
                }
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

private struct UpdateToast: View {
    @EnvironmentObject private var updates: UpdateManager
    @EnvironmentObject private var runner: GameRunner
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Group {
            if let info = updates.available, !updates.bannerDismissed {
                toast(title: "Silica \(info.version) is available", detail: runner.isRunning ? "Close the game to update." : "Installs in a moment and restarts.") {
                    Button("Later") { updates.bannerDismissed = true }.buttonStyle(SecondaryButtonStyle())
                    Button("Update") { Task { await updates.install(info) } }.buttonStyle(PrimaryButtonStyle()).disabled(runner.isRunning)
                }
            } else if case .downloading(let p) = updates.status {
                toast(title: "Downloading update…", detail: "\(Int((p * 100).rounded()))%") {
                    ProgressView(value: p).frame(width: 84)
                }
            } else if updates.status == .verifying || updates.status == .installing {
                toast(title: updates.status == .verifying ? "Checking update…" : "Installing…", detail: "Silica restarts when finished.") {
                    ProgressView().controlSize(.small)
                }
            } else if let message = updates.installError {
                toast(title: "Update could not install", detail: message) {
                    Button("OK") { updates.clearInstallError() }.buttonStyle(SecondaryButtonStyle())
                }
            }
        }
        .animation(reduceMotion ? nil : LauncherMotion.change, value: updates.status)
        .animation(reduceMotion ? nil : LauncherMotion.change, value: updates.bannerDismissed)
    }

    private func toast<A: View>(title: String, detail: String, @ViewBuilder action: () -> A) -> some View {
        HStack(spacing: 14) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 13, weight: .semibold))
                Text(detail).font(.system(size: 11.5)).foregroundStyle(.secondary)
            }
            action()
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 11)
        .surface(radius: 13, fill: Theme.cardHigh)
        .shadow(color: .black.opacity(0.25), radius: 20, y: 8)
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
