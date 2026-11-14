import SwiftUI

struct PlayView: View {
    @EnvironmentObject private var state: AppState
    @EnvironmentObject private var runner: GameRunner
    @EnvironmentObject private var install: InstallManager
    @EnvironmentObject private var jars: JarLibrary

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    let viewportHeight: CGFloat

    private var ready: Bool { install.ready && state.stageReady }

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            banner
            VStack(alignment: .leading, spacing: 20) {
                launchOptions
                if !state.worlds.isEmpty { recentWorlds }
                jarWarning
                if !runner.isRunning { ForEach(runner.hints) { hint($0) } }
                about
            }
            .padding(.vertical, 22)
            .frame(maxWidth: .infinity, minHeight: max(0, viewportHeight - bannerHeight), alignment: .topLeading)
        }
        .onAppear { state.refreshWorlds() }
    }

    private var bannerHeight: CGFloat { min(820, max(460, viewportHeight * 0.74)) }

    /// The title band. The animated scene is drawn by RootView across the whole window, behind this.
    private var banner: some View {
        HStack(alignment: .bottom, spacing: 24) {
            VStack(alignment: .leading, spacing: 6) {
                Text("Minecraft Beta 1.7.3")
                    .font(.system(size: 46, weight: .heavy))
                    .tracking(-2.5)
                    .accessibilityAddTraits(.isHeader)
                Text("NATIVE ON APPLE SILICON · VULKAN")
                    .font(.system(size: 11, weight: .bold)).tracking(1.2)
                    .foregroundStyle(.white.opacity(0.7))
            }
            Spacer(minLength: 0)
            playButton
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 28).padding(.bottom, 26)
        .frame(maxWidth: .infinity, alignment: .leading)
        .frame(height: bannerHeight, alignment: .bottomLeading)
        .environment(\.colorScheme, .dark)
    }

    /// Two captioned cells: how the window opens and which renderer runs (the account lives in the top-right corner). Choices keep the underline selection.
    private var launchOptions: some View {
        HStack(alignment: .top, spacing: 12) {
            optionCell("Screen") {
                HStack(spacing: 14) {
                    launchChoice("Windowed", symbol: "macwindow", selection: $state.settings.fullscreen, value: false)
                    launchChoice("Fullscreen", symbol: "arrow.up.left.and.arrow.down.right", selection: $state.settings.fullscreen, value: true)
                }
            }
            optionCell("Renderer") {
                HStack(spacing: 14) {
                    launchChoice("Vulkan", symbol: "bolt", selection: $state.settings.backend, value: Backend.vulkan)
                    launchChoice("OpenGL", symbol: "cube", selection: $state.settings.backend, value: Backend.gl)
                }
            }
            optionCell("Game jar") { JarPickerCell() }
        }
        .font(.callout)
        .padding(.horizontal, 24)
    }

    /// Shown when the jar you picked did not pass Silica's checks cleanly.
    @ViewBuilder private var jarWarning: some View {
        if let j = jars.jar(state.settings.jarID) {
            let level: JarReport.Level = j.report?.level ?? .red
            if level != .green {
                HStack(alignment: .top, spacing: 10) {
                    Image(systemName: level.symbol).foregroundStyle(level.color)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(j.report?.headline ?? "This jar could not be checked").font(.system(size: 13, weight: .semibold))
                        Text(j.failure ?? (j.report?.reasons.first ?? "")).font(.system(size: 12)).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                        Button("See the details") { state.tab = .jars }.buttonStyle(SecondaryButtonStyle(tint: level.color))
                    }
                }
                .padding(12).frame(maxWidth: .infinity, alignment: .leading)
                .background(level.color.opacity(0.12), in: RoundedRectangle(cornerRadius: 12, style: .continuous)).padding(.horizontal, 24)
            }
        }
    }

    private func optionCell<Content: View>(_ title: String, @ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            caption(title)
            content()
        }
        .padding(.horizontal, 16).padding(.vertical, 10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(Theme.stroke.opacity(2)))
    }

    private func launchChoice<Value: Equatable>(
        _ title: String, symbol: String, selection: Binding<Value>, value: Value
    ) -> some View {
        let selected = selection.wrappedValue == value
        return Button { selection.wrappedValue = value } label: {
            PillControlLabel(title: title, symbol: symbol, selected: selected)
                .lineLimit(1)
                .fixedSize()
                .overlay(alignment: .bottom) {
                    Rectangle().fill(Theme.accent).frame(height: 2).opacity(selected ? 1 : 0)
                }
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .animation(reduceMotion ? nil : LauncherMotion.change, value: selected)
    }

    // MARK: Worlds and hints

    /// Small uppercase caption, the same one the launch bar uses above its groups.
    private func caption(_ title: String) -> some View {
        Text(title.uppercased())
            .font(.system(size: 9.5, weight: .bold)).tracking(0.8)
            .foregroundStyle(.secondary)
    }

    private var recentWorlds: some View {
        let recent = state.worlds.sorted { ($0.modified ?? .distantPast) > ($1.modified ?? .distantPast) }.prefix(3)
        return VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .firstTextBaseline) {
                caption("Your worlds")
                Spacer()
                Button { state.tab = .worlds } label: {
                    Text("See all").font(.system(size: 13, weight: .semibold)).foregroundStyle(.primary)
                        .overlay(alignment: .bottom) { Rectangle().fill(Theme.accent).frame(height: 2).offset(y: 4) }
                }
                .buttonStyle(.plain)
            }
            HStack(spacing: 12) {
                ForEach(Array(recent)) { w in WorldTile(world: w) { state.tab = .worlds } }
                if recent.count < 3 { Spacer(minLength: 0) }
            }
            Text("Open the game and pick \"Resume\" on the title screen to continue the last world.")
                .font(.system(size: 11.5)).foregroundStyle(.secondary)
        }
        .padding(.horizontal, 24).padding(.vertical, 18)
        .overlay(alignment: .top) { Rectangle().fill(Theme.stroke).frame(height: 1) }
    }

    private var about: some View {
        Text("Silica launches Minecraft Beta 1.7.3 natively on Apple Silicon. Sign in with the Microsoft account that owns Java Edition; the first run downloads and prepares everything on this Mac.")
            .font(.system(size: 13))
            .foregroundStyle(.secondary)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: 560, alignment: .leading)
            .padding(.horizontal, 24)
    }

    private func hint(_ h: CrashHint) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            caption("Tip")
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: "lightbulb").foregroundStyle(Theme.sand).font(.system(size: 15))
                VStack(alignment: .leading, spacing: 3) {
                    Text(h.title).font(.system(size: 14, weight: .semibold))
                    Text(h.advice).font(.system(size: 12.5)).foregroundStyle(.secondary)
                }
            }
        }
        .padding(.horizontal, 20).padding(.vertical, 14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(Theme.sand.opacity(0.45)))
        .padding(.horizontal, 24)
    }

    @ViewBuilder private var playButton: some View {
        if !ready {
            Button { state.reopenSetup() } label: { Label("Set up Silica", systemImage: "arrow.down.circle") }.buttonStyle(PrimaryButtonStyle(large: true))
        } else if runner.isRunning {
            HStack(spacing: 14) {
                ProgressView().controlSize(.small)
                Text("Playing").font(.system(size: 15, weight: .semibold, design: .rounded))
                Button("Stop") { runner.stop() }.buttonStyle(SecondaryButtonStyle())
            }
        } else if state.signingIn {
            HStack(spacing: 10) {
                ProgressView().controlSize(.small)
                Text("Signing in…")
            }
        } else if state.player == nil {
            Button { state.signInWithMicrosoft() } label: {
                Label("Sign in with Microsoft", systemImage: "person.crop.circle")
            }
            .buttonStyle(PrimaryButtonStyle(large: true))
            .disabled(state.benchBusy)
        } else {
            Button { state.play() } label: { Label("Play", systemImage: "play.fill") }
                .buttonStyle(PrimaryButtonStyle(large: true)).keyboardShortcut(.defaultAction).disabled(!state.canPlay)
        }
    }
}

/// A square picture for a world: a little isometric cube whose colours come from the name.
struct WorldThumb: View {
    let name: String
    var body: some View {
        let h = Double(abs(name.hashValue % 360)) / 360
        return ZStack {
            RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Color(hue: h, saturation: 0.32, brightness: 0.24))
            Canvas { ctx, size in
                let c = CGPoint(x: size.width / 2, y: size.height / 2), r = min(size.width, size.height) * 0.30
                func pt(_ d: Double) -> CGPoint { CGPoint(x: c.x + r * cos(d * .pi / 180), y: c.y + r * sin(d * .pi / 180)) }
                func face(_ p: [CGPoint], _ col: Color) { var path = Path(); path.move(to: p[0]); p.dropFirst().forEach { path.addLine(to: $0) }; path.closeSubpath(); ctx.fill(path, with: .color(col)) }
                let top = pt(-90), ur = pt(-30), lr = pt(30), bot = pt(90), ll = pt(150), ul = pt(210)
                face([top, ur, c, ul], Color(hue: h, saturation: 0.5, brightness: 0.98))
                face([ul, c, bot, ll], Color(hue: h, saturation: 0.65, brightness: 0.78))
                face([c, ur, lr, bot], Color(hue: h, saturation: 0.75, brightness: 0.55))
            }
        }
    }
}

/// A world in the recent list: hairline outline, and the accent underline appears on hover like the bar's selection.
private struct WorldTile: View {
    let world: WorldInfo
    let open: () -> Void
    @State private var hover = false
    var body: some View {
        Button(action: open) {
            HStack(spacing: 12) {
                WorldThumb(name: world.name).frame(width: 46, height: 46)
                VStack(alignment: .leading, spacing: 2) {
                    Text(world.name).font(.system(size: 13.5, weight: .semibold)).lineLimit(1)
                    Text(world.modified.map { $0.formatted(.relative(presentation: .named)) } ?? "Never played")
                        .font(.system(size: 11)).foregroundStyle(.secondary)
                }
                Spacer(minLength: 0)
            }
            .padding(12).frame(maxWidth: .infinity)
            .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(Theme.stroke.opacity(2)))
            .overlay(alignment: .bottom) { Rectangle().fill(Theme.accent).frame(width: 40, height: 2).opacity(hover ? 1 : 0) }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { hover = $0 }
        .animation(.easeOut(duration: 0.18), value: hover)
    }
}
