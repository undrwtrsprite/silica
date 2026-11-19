import SwiftUI

/// Clean, minimal launch screen matching the website's calm card hierarchy and Bayer-dithered landscape.
struct PlayView: View {
    @EnvironmentObject private var state: AppState
    @EnvironmentObject private var runner: GameRunner
    @EnvironmentObject private var install: InstallManager
    @EnvironmentObject private var instances: InstanceStore
    @EnvironmentObject private var jars: JarLibrary
    @EnvironmentObject private var versions: VersionCatalog

    let pointer: CGPoint
    let viewportHeight: CGFloat

    private var ready: Bool { install.baseReady && state.stageReady }
    private var instance: GameInstance { state.activeInstance }
    private var effective: Settings { state.activeSettings }
    private var activeJar: CustomJar? { instance.jarID.flatMap { jars.jar($0) } }
    private var activeVersion: VersionCatalog.McVersion? { versions.version(for: instance.effectiveVersionID) }

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            heroStage

            HStack(spacing: 12) {
                quickCell("Instance") {
                    instancePicker
                }
                quickCell("Renderer") {
                    Segmented(options: [Backend.vulkan, Backend.gl], selection: backendBinding, expanded: true) {
                        $0 == .vulkan ? "Vulkan" : "OpenGL"
                    }
                    .disabled(runner.isRunning)
                }
                quickCell("Display") {
                    Segmented(options: [false, true], selection: fullscreenBinding, expanded: true) {
                        $0 ? "Fullscreen" : "Window"
                    }
                    .disabled(runner.isRunning)
                }
            }
            .fixedSize(horizontal: false, vertical: true)

            if let warning = activeWarningRow {
                warning
            }

            HStack(spacing: 12) {
                worldsCard
                utilityCard
            }
            .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .onAppear { state.refreshWorlds() }
    }

    // MARK: - Launch Hero Stage

    private var heroStage: some View {
        let stageHeight = max(206, min(246, viewportHeight * 0.36))
        return ZStack(alignment: .bottomLeading) {
            DitherHorizonStage(
                vulkan: effective.backend == .vulkan,
                running: runner.isRunning,
                pointer: pointer
            )
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .overlay {
                LinearGradient(
                    colors: [Theme.bg.opacity(0.76), Theme.bg.opacity(0.18), .clear],
                    startPoint: .bottomLeading,
                    endPoint: .topTrailing
                )
            }

            VStack(alignment: .leading, spacing: 0) {
                HStack(spacing: 8) {
                    statusPill
                    if activeJar == nil, activeVersion?.isExperimental == true {
                        Text("Experimental")
                            .font(.system(size: 11, weight: .semibold))
                            .foregroundStyle(.orange)
                            .padding(.horizontal, 9)
                            .padding(.vertical, 4)
                            .background(Color.orange.opacity(0.16), in: Capsule())
                            .overlay(Capsule().strokeBorder(Color.orange.opacity(0.35), lineWidth: 1))
                    }
                    Spacer()
                }
                Spacer(minLength: 14)
                HStack(alignment: .bottom, spacing: 20) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(instance.name)
                            .font(.system(size: 30, weight: .bold))
                            .tracking(-0.9)
                            .foregroundStyle(Theme.text)
                            .lineLimit(1)
                            .accessibilityAddTraits(.isHeader)
                        Text(specLine)
                            .font(.system(size: 13))
                            .foregroundStyle(Theme.dim)
                    }
                    Spacer(minLength: 0)
                    playButton
                }
            }
            .padding(22)
        }
        .frame(height: stageHeight)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(Theme.stroke, lineWidth: 1))
    }

    private var statusPill: some View {
        let (label, color): (String, Color) = {
            if runner.isRunning { return ("Running", Theme.sand) }
            if !ready { return ("Setup needed", Theme.sand) }
            if state.player == nil { return ("Sign in required", Theme.sand) }
            return ("Ready", Theme.accent)
        }()
        return HStack(spacing: 6) {
            Circle().fill(color).frame(width: 7, height: 7)
            Text(label).font(.system(size: 12, weight: .medium))
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .background(Theme.bar.opacity(0.85), in: Capsule())
        .overlay(Capsule().strokeBorder(Theme.stroke, lineWidth: 1))
    }

    private var specLine: String {
        let verLabel = activeJar?.name ?? activeVersion?.displayName ?? instance.effectiveVersionID
        let renderer = effective.backend == .vulkan ? "Vulkan (MoltenVK)" : "OpenGL 2.1"
        let screen = effective.fullscreen ? "Fullscreen" : "\(effective.width)×\(effective.height)"
        let mem = String(format: "%.1f GB", Double(effective.memoryMB) / 1024.0)
        return "\(verLabel) · \(renderer) · \(screen) · \(mem)"
    }

    // MARK: - Bindings & Pickers

    private var backendBinding: Binding<Backend> {
        Binding(
            get: { effective.backend },
            set: { next in
                if instance.backend != nil {
                    instances.update(instance.id) { $0.backend = next }
                } else {
                    state.settings.backend = next
                }
            }
        )
    }

    private var fullscreenBinding: Binding<Bool> {
        Binding(
            get: { effective.fullscreen },
            set: { next in
                if instance.fullscreen != nil {
                    instances.update(instance.id) { $0.fullscreen = next }
                } else {
                    state.settings.fullscreen = next
                }
            }
        )
    }

    private var instancePicker: some View {
        Menu {
            ForEach(instances.instances) { item in
                Button {
                    state.selectInstance(item.id)
                } label: {
                    Label(item.name, systemImage: item.id == instance.id ? "checkmark" : "square.stack.3d.up")
                }
            }
            Divider()
            Button { state.tab = .instances } label: {
                Label("Manage instances…", systemImage: "slider.horizontal.3")
            }
        } label: {
            HStack(spacing: 8) {
                Circle()
                    .fill(activeJar?.report?.level.color ?? Theme.accent)
                    .frame(width: 7, height: 7)
                Text(instance.name)
                    .font(.system(size: 13.5, weight: .semibold))
                    .lineLimit(1)
                    .truncationMode(.tail)
                Spacer(minLength: 4)
                Image(systemName: "chevron.up.chevron.down")
                    .font(.system(size: 9.5, weight: .semibold))
                    .foregroundStyle(Theme.dim)
            }
            .padding(.horizontal, 11)
            .padding(.vertical, 8)
            .background(Color.primary.opacity(0.05), in: RoundedRectangle(cornerRadius: 9, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 9, style: .continuous).strokeBorder(Theme.stroke, lineWidth: 1))
            .contentShape(Rectangle())
        }
        .menuStyle(.button)
        .buttonStyle(.plain)
        .menuIndicator(.hidden)
        .disabled(runner.isRunning)
    }

    private func quickCell<Content: View>(_ label: String, @ViewBuilder _ content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(label)
                .font(.system(size: 11.5, weight: .medium))
                .foregroundStyle(Theme.dim)
            content()
                .frame(height: 34)
        }
        .padding(14)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .surface(radius: 14)
    }

    private var activeWarningRow: AnyView? {
        guard let j = activeJar else { return nil }
        let level: JarReport.Level = j.report?.level ?? .red
        guard level != .green else { return nil }
        return AnyView(
            HStack(spacing: 10) {
                Image(systemName: level.symbol).foregroundStyle(level.color)
                VStack(alignment: .leading, spacing: 2) {
                    Text(j.report?.headline ?? "Custom jar issue")
                        .font(.system(size: 13, weight: .semibold))
                    Text(j.failure ?? (j.report?.reasons.first ?? ""))
                        .font(.system(size: 12))
                        .foregroundStyle(.secondary)
                        .lineLimit(2)
                }
                Spacer()
                Button("Inspect") { state.tab = .instances }
                    .buttonStyle(SecondaryButtonStyle(tint: level.color))
            }
            .padding(12)
            .surface(radius: 12, fill: level.color.opacity(0.10))
        )
    }

    // MARK: - Bottom Pair

    private var worldsCard: some View {
        let recent = Array(state.worlds.sorted { ($0.modified ?? .distantPast) > ($1.modified ?? .distantPast) }.prefix(3))
        return VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Recent worlds")
                    .font(.system(size: 14, weight: .semibold))
                Spacer()
                Button { state.tab = .worlds } label: {
                    Text(state.worlds.isEmpty ? "Open" : "All (\(state.worlds.count))")
                        .font(.system(size: 12.5, weight: .medium))
                        .foregroundStyle(Theme.accent)
                }
                .buttonStyle(.plain)
            }

            if recent.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    Text("No worlds in \"\(instance.name)\" yet.")
                        .font(.system(size: 13))
                        .foregroundStyle(.secondary)
                    Button { state.importWorld() } label: {
                        Label("Import world…", systemImage: "square.and.arrow.down")
                    }
                    .buttonStyle(SecondaryButtonStyle())
                    .padding(.top, 2)
                }
                .padding(.vertical, 6)
            } else {
                VStack(spacing: 8) {
                    ForEach(recent) { world in
                        RecentWorldRow(world: world) { state.tab = .worlds }
                    }
                }
            }
        }
        .padding(18)
        .frame(maxWidth: .infinity, minHeight: 156, maxHeight: .infinity, alignment: .topLeading)
        .surface(radius: 16)
    }

    private var utilityCard: some View {
        VStack(alignment: .leading, spacing: 12) {
            if !runner.isRunning, let tip = runner.hints.first {
                Text("Last run note")
                    .font(.system(size: 14, weight: .semibold))
                HStack(alignment: .top, spacing: 10) {
                    Image(systemName: "lightbulb.fill").foregroundStyle(Theme.sand).font(.system(size: 14))
                    VStack(alignment: .leading, spacing: 3) {
                        Text(tip.title).font(.system(size: 13, weight: .semibold))
                        Text(tip.advice).font(.system(size: 12)).foregroundStyle(.secondary)
                    }
                }
            } else {
                HStack {
                    Text("Runtime")
                        .font(.system(size: 14, weight: .semibold))
                    Spacer()
                    Button { state.tab = .settings } label: {
                        Text("Settings").font(.system(size: 12.5, weight: .medium)).foregroundStyle(Theme.accent)
                    }
                    .buttonStyle(.plain)
                }
                VStack(spacing: 8) {
                    statRow("Preset", state.settings.preset?.title ?? "Custom")
                    statRow("Memory", String(format: "%.1f GB", Double(effective.memoryMB) / 1024.0))
                    statRow("Workers", "\(state.settings.meshWorkers) mesh · \(state.settings.genWorkers) terrain")
                    statRow("VSync", state.settings.vsync ? "On" : "Off")
                }
            }
        }
        .padding(18)
        .frame(width: 286, alignment: .topLeading)
        .frame(minHeight: 156, maxHeight: .infinity, alignment: .topLeading)
        .surface(radius: 16)
    }

    private func statRow(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label).font(.system(size: 12.5)).foregroundStyle(.secondary)
            Spacer()
            Text(value).font(.system(size: 12.5, weight: .medium, design: .monospaced))
        }
    }

    @ViewBuilder private var playButton: some View {
        if !ready {
            Button { state.reopenSetup() } label: {
                Label("Set up Silica", systemImage: "arrow.down.circle")
            }
            .buttonStyle(PrimaryButtonStyle(large: true))
        } else if runner.isRunning {
            HStack(spacing: 10) {
                ProgressView().controlSize(.small)
                Button("Stop") { runner.stop() }
                    .buttonStyle(SecondaryButtonStyle())
            }
        } else if state.signingIn {
            HStack(spacing: 8) {
                ProgressView().controlSize(.small)
                Text("Signing in…").font(.system(size: 13))
            }
        } else if state.player == nil {
            Button { state.signInWithMicrosoft() } label: {
                Label("Sign in with Microsoft", systemImage: "person.crop.circle")
            }
            .buttonStyle(PrimaryButtonStyle(large: true))
            .disabled(state.benchBusy)
        } else {
            Button { state.play() } label: {
                Label("Play", systemImage: "play.fill")
            }
            .buttonStyle(PrimaryButtonStyle(large: true))
            .keyboardShortcut(.defaultAction)
            .disabled(!state.canPlay)
        }
    }
}

private struct RecentWorldRow: View {
    let world: WorldInfo
    let open: () -> Void
    @State private var hover = false

    var body: some View {
        Button(action: open) {
            HStack(spacing: 11) {
                WorldDitherMap(seedName: world.name)
                    .frame(width: 36, height: 36)
                    .clipShape(RoundedRectangle(cornerRadius: 8, style: .continuous))
                    .overlay(RoundedRectangle(cornerRadius: 8, style: .continuous).strokeBorder(Theme.stroke, lineWidth: 1))
                VStack(alignment: .leading, spacing: 2) {
                    Text(world.name).font(.system(size: 13.5, weight: .semibold)).lineLimit(1)
                    Text("\(ByteCountFormatter.string(fromByteCount: world.sizeBytes, countStyle: .file)) · \(world.modified.map { $0.formatted(.relative(presentation: .named)) } ?? "never played")")
                        .font(.system(size: 11.5)).foregroundStyle(.secondary).lineLimit(1)
                }
                Spacer()
                Image(systemName: "chevron.right").font(.system(size: 11, weight: .semibold)).foregroundStyle(Theme.dim)
            }
            .padding(8)
            .background(Color.primary.opacity(hover ? 0.055 : 0.025),
                        in: RoundedRectangle(cornerRadius: 11, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 11, style: .continuous).strokeBorder(Theme.stroke, lineWidth: 1))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { hover = $0 }
        .animation(.easeOut(duration: 0.14), value: hover)
    }
}
