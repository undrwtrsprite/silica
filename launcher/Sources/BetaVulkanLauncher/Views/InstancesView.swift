import AppKit
import SwiftUI

extension JarReport.Level {
    var color: Color { self == .green ? Theme.good : (self == .yellow ? Theme.sand : Theme.bad) }
    var symbol: String {
        switch self {
        case .green: return "checkmark.circle.fill"
        case .yellow: return "exclamationmark.triangle.fill"
        case .red: return "xmark.octagon.fill"
        }
    }
}

/// Manages isolated Beta 1.7.3 instances with optional per-instance settings or custom client jars.
struct InstancesView: View {
    @EnvironmentObject private var state: AppState
    @EnvironmentObject private var instances: InstanceStore
    @EnvironmentObject private var jars: JarLibrary
    @Local private var creating = false
    @Local private var confirmDelete: GameInstance?

    var body: some View {
        VStack(alignment: .leading, spacing: 20) {
            HStack(alignment: .bottom) {
                PageHeader(
                    eyebrow: "Profiles",
                    title: "Instances",
                    subtitle: "Each instance keeps its own worlds, configs, and optional custom jar."
                )
                Spacer()
                Button { creating = true } label: {
                    Label("New instance", systemImage: "plus")
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(jars.busy || state.runner.isRunning)
            }

            if jars.busy {
                HStack(spacing: 10) {
                    ProgressView().controlSize(.small)
                    Text(jars.status.isEmpty ? "Preparing instance…" : jars.status)
                        .font(.system(size: 13))
                        .foregroundStyle(.secondary)
                }
                .padding(14)
                .frame(maxWidth: .infinity, alignment: .leading)
                .surface(radius: 14)
            }

            if let p = jars.problem {
                Label(p, systemImage: "exclamationmark.triangle.fill")
                    .font(.system(size: 13))
                    .foregroundStyle(Theme.sand)
                    .padding(14)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .surface(radius: 14, fill: Theme.sand.opacity(0.10))
            }

            VStack(spacing: 12) {
                ForEach(instances.instances) { item in
                    InstanceRow(instance: item) {
                        confirmDelete = item
                    }
                }
            }
        }
        .sheet(isPresented: $creating) {
            NewInstanceSheet { name, jarURL in
                creating = false
                state.createInstance(name: name, customJarURL: jarURL)
            } onCancel: {
                creating = false
            }
        }
        .confirmationDialog(
            "Delete \"\(confirmDelete?.name ?? "")\"?",
            isPresented: Binding(get: { confirmDelete != nil }, set: { if !$0 { confirmDelete = nil } })
        ) {
            Button("Delete instance and saves", role: .destructive) {
                if let item = confirmDelete {
                    if state.activeInstance.id == item.id {
                        state.selectInstance(GameInstance.defaultID)
                    }
                    instances.delete(item.id, jars: jars)
                    state.refreshWorlds()
                }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This deletes the instance folder and any worlds stored inside it.")
        }
    }
}

private struct InstanceRow: View {
    @EnvironmentObject private var state: AppState
    @EnvironmentObject private var instances: InstanceStore
    @EnvironmentObject private var jars: JarLibrary
    @Environment(\.colorScheme) private var scheme

    let instance: GameInstance
    let onDelete: () -> Void

    @Local private var editing = false
    @Local private var showDetails = false

    private var selected: Bool { state.activeInstance.id == instance.id }
    private var jar: CustomJar? { instance.jarID.flatMap { jars.jar($0) } }
    private var effective: Settings { instances.effectiveSettings(state.settings, for: instance) }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 14) {
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 8) {
                        Text(instance.name)
                            .font(.system(size: 16, weight: .semibold))
                            .lineLimit(1)
                        if selected {
                            Text("Active")
                                .font(.system(size: 11, weight: .semibold))
                                .foregroundStyle(Theme.onAccent)
                                .padding(.horizontal, 8)
                                .padding(.vertical, 2)
                                .background(Theme.accent, in: Capsule())
                        }
                        if let jar {
                            JarVerdictBadge(report: jar.report, failure: jar.failure)
                        }
                    }

                    HStack(spacing: 8) {
                        Text(jar?.name ?? "Beta 1.7.3 (Silica)")
                        Text("·")
                        Text(effective.backend == .vulkan ? "Vulkan" : "OpenGL")
                        Text("·")
                        Text(effective.fullscreen ? "Fullscreen" : "\(effective.width)×\(effective.height)")
                        if let played = instance.lastPlayed {
                            Text("·")
                            Text("Played \(played.formatted(.relative(presentation: .named)))")
                        }
                    }
                    .font(.system(size: 12.5))
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                }

                Spacer(minLength: 10)

                HStack(spacing: 8) {
                    Button("Folder") {
                        let path = instances.gameDir(for: instance, defaultDir: state.settings.gameDir, jars: jars)
                        AppPaths.ensure(URL(fileURLWithPath: path))
                        NSWorkspace.shared.activateFileViewerSelecting([URL(fileURLWithPath: path)])
                    }
                    .buttonStyle(SecondaryButtonStyle())

                    Button("Options") { editing.toggle() }
                        .buttonStyle(SecondaryButtonStyle())
                        .popover(isPresented: $editing, arrowEdge: .bottom) {
                            InstanceOptionsPopover(instance: instance) { editing = false }
                                .environment(\.colorScheme, scheme)
                        }

                    Button {
                        let copy = instances.duplicate(instance, defaultDir: state.settings.gameDir, jars: jars)
                        state.selectInstance(copy.id)
                    } label: {
                        Image(systemName: "plus.square.on.square")
                    }
                    .buttonStyle(SecondaryButtonStyle())
                    .disabled(state.runner.isRunning)
                    .help("Duplicate instance")

                    if !selected {
                        Button("Use") { state.selectInstance(instance.id) }
                            .buttonStyle(PrimaryButtonStyle())
                            .disabled(state.runner.isRunning)
                    }

                    if !instance.isDefault {
                        Button(action: onDelete) {
                            Image(systemName: "trash")
                        }
                        .buttonStyle(SecondaryButtonStyle(tint: Theme.bad))
                        .disabled(state.runner.isRunning)
                        .help("Delete instance")
                    }
                }
            }

            if let jar {
                if let failure = jar.failure {
                    Text(failure)
                        .font(.system(size: 12))
                        .foregroundStyle(Theme.bad)
                        .textSelection(.enabled)
                } else if let r = jar.report {
                    ForEach(r.reasons + r.notes, id: \.self) { line in
                        Text(line)
                            .font(.system(size: 12.5))
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    HStack(spacing: 12) {
                        Button {
                            Task { await jars.recheck(jar.id, stageDir: state.settings.stageDir, java: state.install.javaPath) }
                        } label: {
                            Label("Re-check jar", systemImage: "arrow.clockwise")
                        }
                        .buttonStyle(SecondaryButtonStyle())
                        .disabled(jars.busy || state.runner.isRunning)

                        if !(r.overlap + r.patchFailures + r.brokenLinks + r.keyMissing).isEmpty || r.classesAdded + r.classesChanged > 0 {
                            Button(showDetails ? "Hide details" : "Show details") { showDetails.toggle() }
                                .buttonStyle(.plain)
                                .font(.system(size: 12.5, weight: .medium))
                                .foregroundStyle(Theme.dim)
                        }
                    }

                    if showDetails {
                        VStack(alignment: .leading, spacing: 8) {
                            detailList("Patches that cannot apply", r.patchFailures)
                            detailList("Not provided by Silica", r.brokenLinks)
                            detailList("Overlap", r.overlap)
                            detailList("Missing key classes", r.keyMissing)
                            detailList("Changed classes", r.changedClassNames)
                            detailList("Added classes", r.addedClassNames)
                        }
                        .padding(.top, 4)
                    }
                }
            }
        }
        .padding(18)
        .surface(radius: 16, selected: selected)
    }

    @ViewBuilder private func detailList(_ title: String, _ items: [String]) -> some View {
        if !items.isEmpty {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 11, weight: .semibold)).foregroundStyle(Theme.dim)
                Text(items.joined(separator: "\n"))
                    .font(.system(size: 11, design: .monospaced))
                    .textSelection(.enabled)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

private struct InstanceOptionsPopover: View {
    @EnvironmentObject private var instances: InstanceStore
    let instance: GameInstance
    let close: () -> Void

    @State private var name = ""
    @State private var backendChoice: String = "default"
    @State private var screenChoice: String = "default"

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text("Instance options").font(.system(size: 14, weight: .semibold))

            VStack(alignment: .leading, spacing: 5) {
                Text("Name").font(.system(size: 11.5)).foregroundStyle(.secondary)
                TextField("Instance name", text: $name)
                    .textFieldStyle(.roundedBorder)
            }

            VStack(alignment: .leading, spacing: 5) {
                Text("Renderer").font(.system(size: 11.5)).foregroundStyle(.secondary)
                Picker("", selection: $backendChoice) {
                    Text("Use global default").tag("default")
                    Text("Vulkan").tag("vulkan")
                    Text("OpenGL").tag("gl")
                }
                .labelsHidden()
            }

            VStack(alignment: .leading, spacing: 5) {
                Text("Display").font(.system(size: 11.5)).foregroundStyle(.secondary)
                Picker("", selection: $screenChoice) {
                    Text("Use global default").tag("default")
                    Text("Windowed").tag("window")
                    Text("Fullscreen").tag("fullscreen")
                }
                .labelsHidden()
            }

            HStack {
                Spacer()
                Button("Save") {
                    let trimmed = name.trimmingCharacters(in: .whitespacesAndNewlines)
                    instances.update(instance.id) { item in
                        if !trimmed.isEmpty { item.name = trimmed }
                        item.backend = backendChoice == "vulkan" ? .vulkan : (backendChoice == "gl" ? .gl : nil)
                        item.fullscreen = screenChoice == "fullscreen" ? true : (screenChoice == "window" ? false : nil)
                    }
                    close()
                }
                .buttonStyle(PrimaryButtonStyle())
            }
        }
        .padding(16)
        .frame(width: 270)
        .onAppear {
            name = instance.name
            backendChoice = instance.backend?.rawValue ?? "default"
            screenChoice = instance.fullscreen.map { $0 ? "fullscreen" : "window" } ?? "default"
        }
    }
}

private struct NewInstanceSheet: View {
    let onCreate: (String, URL?) -> Void
    let onCancel: () -> Void

    @State private var name = ""
    @State private var jarURL: URL? = nil

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            Text("New instance")
                .font(.system(size: 20, weight: .bold))

            VStack(alignment: .leading, spacing: 6) {
                Text("Name")
                    .font(.system(size: 12.5, weight: .medium))
                    .foregroundStyle(.secondary)
                TextField("Beta 1.7.3 Survival", text: $name)
                    .textFieldStyle(.roundedBorder)
            }

            VStack(alignment: .leading, spacing: 6) {
                Text("Game version")
                    .font(.system(size: 12.5, weight: .medium))
                    .foregroundStyle(.secondary)
                HStack(spacing: 10) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(jarURL?.lastPathComponent ?? "Official Beta 1.7.3 (Silica patched)")
                            .font(.system(size: 13, weight: .medium))
                        Text(jarURL == nil ? "Clean isolated folder using your verified Mojang client." : "Modded client jar checked cleanly against Silica's patches.")
                            .font(.system(size: 11.5))
                            .foregroundStyle(.secondary)
                    }
                    Spacer()
                    if jarURL != nil {
                        Button("Reset") { jarURL = nil }.buttonStyle(SecondaryButtonStyle())
                    }
                    Button("Custom .jar…") { pickJar() }.buttonStyle(SecondaryButtonStyle())
                }
                .padding(12)
                .surface(radius: 12, fill: Theme.cardHigh)
            }

            HStack {
                Spacer()
                Button("Cancel", action: onCancel).buttonStyle(SecondaryButtonStyle())
                Button("Create") {
                    let finalName = name.trimmingCharacters(in: .whitespacesAndNewlines)
                    onCreate(finalName.isEmpty ? (jarURL?.deletingPathExtension().lastPathComponent ?? "Beta 1.7.3") : finalName, jarURL)
                }
                .buttonStyle(PrimaryButtonStyle())
                .keyboardShortcut(.defaultAction)
            }
        }
        .padding(24)
        .frame(width: 440)
        .background(Theme.bg)
    }

    private func pickJar() {
        let panel = NSOpenPanel()
        panel.message = "Choose a modded Minecraft Beta 1.7.3 jar (optional)"
        panel.allowedContentTypes = [.init(filenameExtension: "jar")!]
        panel.canChooseDirectories = false
        if panel.runModal() == .OK { jarURL = panel.url }
    }
}

struct JarVerdictBadge: View {
    let report: JarReport?
    let failure: String?

    var body: some View {
        let level: JarReport.Level = report?.level ?? .red
        HStack(spacing: 5) {
            Image(systemName: level.symbol)
            Text(report?.headline ?? "Could not be checked")
        }
        .font(.system(size: 11.5, weight: .semibold))
        .foregroundStyle(level.color)
        .padding(.horizontal, 8)
        .padding(.vertical, 3)
        .background(level.color.opacity(0.12), in: Capsule())
    }
}
