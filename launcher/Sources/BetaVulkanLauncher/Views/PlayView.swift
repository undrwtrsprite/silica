import SwiftUI

struct PlayView: View {
    @EnvironmentObject private var state: AppState
    @EnvironmentObject private var runner: GameRunner
    @EnvironmentObject private var install: InstallManager
    @EnvironmentObject private var jars: JarLibrary

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    let viewportHeight: CGFloat

    private var ready: Bool { install.ready && state.stageReady }
    private var activeJarName: String { jars.jar(state.settings.jarID)?.name ?? "Silica patched" }

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            instanceStage
            launchControls
            jarWarning
            bottomDeck
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .onAppear { state.refreshWorlds() }
    }

    /// Top instance console card with unique Bayer-dithered canyon artwork and direct Play control.
    private var instanceStage: some View {
        ZStack(alignment: .bottomLeading) {
            DitherBackdrop(mode: .launchCanyon, paused: runner.isRunning)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .overlay {
                    LinearGradient(
                        colors: [.black.opacity(0.72), .black.opacity(0.25), .clear],
                        startPoint: .bottomLeading,
                        endPoint: .topTrailing
                    )
                }

            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 8) {
                    statusBadge
                    Text(activeJarName)
                        .font(.system(size: 11.5, weight: .medium, design: .monospaced))
                        .foregroundStyle(.white.opacity(0.85))
                        .padding(.horizontal, 10).padding(.vertical, 5)
                        .background(.black.opacity(0.45), in: Capsule())
                        .overlay(Capsule().strokeBorder(.white.opacity(0.14), lineWidth: 1))
                    Spacer()
                }
                Spacer(minLength: 16)
                HStack(alignment: .bottom, spacing: 20) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Beta 1.7.3")
                            .font(.system(size: 34, weight: .bold))
                            .tracking(-1.2)
                            .foregroundStyle(.white)
                            .accessibilityAddTraits(.isHeader)
                        Text(runtimeSummary)
                            .font(.system(size: 13, design: .monospaced))
                            .foregroundStyle(.white.opacity(0.72))
                    }
                    Spacer(minLength: 0)
                    playButton
                }
            }
            .padding(22)
        }
        .frame(height: 204)
        .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(Theme.stroke, lineWidth: 1))
        .environment(\.colorScheme, .dark)
    }

    private var statusBadge: some View {
        let (label, col): (String, Color) = {
            if runner.isRunning { return ("Running", Theme.sand) }
            if !ready { return ("Setup required", Theme.sand) }
            if state.player == nil { return ("Sign in required", Theme.sand) }
            return ("Ready", Theme.accent)
        }()
        return HStack(spacing: 6) {
            Circle().fill(col).frame(width: 7, height: 7)
            Text(label).font(.system(size: 11.5, weight: .semibold)).foregroundStyle(.white)
        }
        .padding(.horizontal, 10).padding(.vertical, 5)
        .background(.black.opacity(0.5), in: Capsule())
        .overlay(Capsule().strokeBorder(.white.opacity(0.14), lineWidth: 1))
    }

    private var runtimeSummary: String {
        let gfx = state.settings.backend == .vulkan ? "Vulkan" : "OpenGL"
        let disp = state.settings.fullscreen ? "Fullscreen" : "\(state.settings.width)×\(state.settings.height)"
        let mem = String(format: "%.1f GB", Double(state.settings.memoryMB) / 1024.0)
        return "\(gfx)  ·  \(disp)  ·  \(mem)"
    }

    /// Three-column quick-toggle configuration strip.
    private var launchControls: some View {
        HStack(alignment: .top, spacing: 12) {
            optionCell("Display") {
                HStack(spacing: 12) {
                    launchChoice("Windowed", symbol: "macwindow", selection: $state.settings.fullscreen, value: false)
                    launchChoice("Fullscreen", symbol: "arrow.up.left.and.arrow.down.right", selection: $state.settings.fullscreen, value: true)
                }
            }
            optionCell("Renderer") {
                HStack(spacing: 12) {
                    launchChoice("Vulkan", symbol: "bolt", selection: $state.settings.backend, value: Backend.vulkan)
                    launchChoice("OpenGL", symbol: "cube", selection: $state.settings.backend, value: Backend.gl)
                }
            }
            optionCell("Game jar") { JarPickerCell() }
        }
    }

    private var bottomDeck: some View {
        HStack(alignment: .top, spacing: 14) {
            worldsCard
            rightUtilityCard
        }
    }

    private var worldsCard: some View {
        let recent = state.worlds.sorted { ($0.modified ?? .distantPast) > ($1.modified ?? .distantPast) }.prefix(3)
        return VStack(alignment: .leading, spacing: 12) {
            HStack {
                caption("Recent worlds")
                Spacer()
                Button { state.tab = .worlds } label: {
                    Text(state.worlds.isEmpty ? "Manage" : "All (\(state.worlds.count))")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(Theme.accent)
                }
                .buttonStyle(.plain)
            }
            if state.worlds.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    Text("No worlds in your save folder yet.")
                        .font(.system(size: 13.5, weight: .medium))
                    Text("Launch to create one, or import a Beta 1.7.3 world zip or folder.")
                        .font(.system(size: 12)).foregroundStyle(.secondary)
                    Button { state.importWorld() } label: { Label("Import world…", systemImage: "square.and.arrow.down") }
                        .buttonStyle(SecondaryButtonStyle())
                        .padding(.top, 2)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, 6)
            } else {
                VStack(spacing: 8) {
                    ForEach(Array(recent)) { w in WorldRowTile(world: w) { state.tab = .worlds } }
                }
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, minHeight: 168, alignment: .topLeading)
        .surface(radius: 18)
    }

    private var rightUtilityCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            if !runner.isRunning, let tip = runner.hints.first {
                caption("Last run note")
                HStack(alignment: .top, spacing: 10) {
                    Image(systemName: "lightbulb.fill").foregroundStyle(Theme.sand).font(.system(size: 14))
                    VStack(alignment: .leading, spacing: 3) {
                        Text(tip.title).font(.system(size: 13.5, weight: .semibold))
                        Text(tip.advice).font(.system(size: 12)).foregroundStyle(.secondary)
                    }
                }
            } else {
                HStack {
                    caption("Runtime profile")
                    Spacer()
                    Button { state.tab = .settings } label: {
                        Text("Configure").font(.system(size: 12, weight: .semibold)).foregroundStyle(Theme.accent)
                    }
                    .buttonStyle(.plain)
                }
                VStack(spacing: 8) {
                    statRow("Preset", state.settings.preset?.title ?? "Custom")
                    statRow("Memory cap", String(format: "%.1f GB (%@)", Double(state.settings.memoryMB) / 1024.0, state.settings.gcProfile == .balanced ? "G1" : state.settings.gcProfile.rawValue))
                    statRow("Workers", "\(state.settings.meshWorkers) mesh · \(state.settings.genWorkers) terrain")
                    statRow("VSync", state.settings.vsync ? "On" : "Uncapped")
                }
            }
        }
        .padding(18)
        .frame(width: 280, alignment: .topLeading)
        .frame(minHeight: 168, alignment: .topLeading)
        .surface(radius: 18)
    }

    private func statRow(_ label: String, _ val: String) -> some View {
        HStack {
            Text(label).font(.system(size: 12.5)).foregroundStyle(.secondary)
            Spacer()
            Text(val).font(.system(size: 12.5, weight: .medium, design: .monospaced))
        }
    }

    @ViewBuilder private var jarWarning: some View {
        if let j = jars.jar(state.settings.jarID) {
            let level: JarReport.Level = j.report?.level ?? .red
            if level != .green {
                HStack(alignment: .top, spacing: 10) {
                    Image(systemName: level.symbol).foregroundStyle(level.color)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(j.report?.headline ?? "This jar could not be checked").font(.system(size: 13, weight: .semibold))
                        Text(j.failure ?? (j.report?.reasons.first ?? "")).font(.system(size: 12)).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
                        Button("Inspect in Jars") { state.tab = .jars }.buttonStyle(SecondaryButtonStyle(tint: level.color))
                    }
                }
                .padding(14).frame(maxWidth: .infinity, alignment: .leading)
                .background(level.color.opacity(0.12), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
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
        .surface(radius: 16)
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

    private func caption(_ title: String) -> some View {
        Text(title.uppercased())
            .font(.system(size: 10, weight: .bold, design: .monospaced)).tracking(0.8)
            .foregroundStyle(.secondary)
    }

    @ViewBuilder private var playButton: some View {
        if !ready {
            Button { state.reopenSetup() } label: { Label("Set up Silica", systemImage: "arrow.down.circle") }
                .buttonStyle(PrimaryButtonStyle(large: true))
        } else if runner.isRunning {
            HStack(spacing: 12) {
                ProgressView().controlSize(.small)
                Text("Running").font(.system(size: 14, weight: .semibold))
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

/// Isometric cube icon for worlds (reused across Play and Worlds).
struct WorldThumb: View {
    let name: String
    var body: some View {
        let h = Double(abs(name.hashValue % 360)) / 360
        return ZStack {
            RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Color(hue: h, saturation: 0.32, brightness: 0.24))
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

private struct WorldRowTile: View {
    let world: WorldInfo
    let open: () -> Void
    @State private var hover = false

    var body: some View {
        Button(action: open) {
            HStack(spacing: 12) {
                WorldThumb(name: world.name).frame(width: 38, height: 38)
                VStack(alignment: .leading, spacing: 2) {
                    Text(world.name).font(.system(size: 13.5, weight: .semibold)).lineLimit(1)
                    Text("\(ByteCountFormatter.string(fromByteCount: world.sizeBytes, countStyle: .file)) · \(world.modified.map { $0.formatted(.relative(presentation: .named)) } ?? "never played")")
                        .font(.system(size: 11)).foregroundStyle(.secondary)
                }
                Spacer()
                Image(systemName: "chevron.right").font(.system(size: 11, weight: .semibold)).foregroundStyle(.secondary)
            }
            .padding(10)
            .background(Color.primary.opacity(hover ? 0.06 : 0.03), in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(hover ? Theme.accent.opacity(0.45) : Theme.stroke, lineWidth: 1))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { hover = $0 }
        .animation(.easeOut(duration: 0.16), value: hover)
    }
}
