import AppKit
import SwiftUI

extension JarReport.Level {
    var color: Color { self == .green ? Theme.good : (self == .yellow ? Theme.sand : Theme.bad) }
    var symbol: String { self == .green ? "checkmark.circle.fill" : (self == .yellow ? "exclamationmark.triangle.fill" : "xmark.octagon.fill") }
}

/// The "Jar" cell on the Play page: pick the original game or a jar you added, add another, or open the details.
struct JarPickerCell: View {
    @EnvironmentObject private var state: AppState
    @EnvironmentObject private var jars: JarLibrary

    private var current: CustomJar? { jars.jar(state.settings.jarID) }

    var body: some View {
        HStack(spacing: 8) {
            Menu {
                Button { state.settings.jarID = nil } label: { Label("Silica patched", systemImage: current == nil ? "checkmark" : "shippingbox") }
                if !jars.jars.isEmpty { Divider() }
                ForEach(jars.jars) { j in
                    Button { state.settings.jarID = j.id } label: {
                        Label(j.name + (j.report?.level == .red || j.failure != nil ? " (won't run)" : ""), systemImage: state.settings.jarID == j.id ? "checkmark" : (j.failure != nil ? JarReport.Level.red.symbol : (j.report?.level.symbol ?? "questionmark.circle")))
                    }
                }
                Divider()
                Button { state.chooseJarFile() } label: { Label("Add a jar…", systemImage: "plus") }
                Button { state.tab = .jars } label: { Label("Checks and details…", systemImage: "checklist") }
            } label: {
                // same look and 43pt height as the choices in the Screen and Renderer cells (a menu's own style ignores a label's height, so it is a plain button with its own chevron)
                HStack(spacing: 7) {
                    if let r = current?.report { Image(systemName: r.level.symbol).foregroundStyle(r.level.color) }
                    else if current?.failure != nil { Image(systemName: JarReport.Level.red.symbol).foregroundStyle(Theme.bad) }
                    else { Image(systemName: "shippingbox") }
                    Text(current?.name ?? "Silica patched").lineLimit(1).truncationMode(.middle)
                    Image(systemName: "chevron.up.chevron.down").font(.system(size: 9, weight: .bold)).foregroundStyle(.secondary)
                }
                .font(.system(size: 14, weight: .semibold))
                .frame(height: 43)
                .contentShape(Rectangle())
            }
            .menuStyle(.button).buttonStyle(.plain).menuIndicator(.hidden).fixedSize()
            if jars.busy { ProgressView().controlSize(.small) }
            Spacer(minLength: 0)
        }
        .disabled(state.runner.isRunning)
        .accessibilityLabel("Game jar")
    }
}

/// The Jars tab: the original game and every jar you added, with everything the checker found.
struct JarsView: View {
    @EnvironmentObject private var state: AppState
    @EnvironmentObject private var jars: JarLibrary

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(alignment: .top) {
                PageHeader(title: "Jars", subtitle: "Play modded Beta 1.7.3 jars. Yours are never changed; Silica checks that its improvements fit.")
                Spacer()
                Button { state.chooseJarFile() } label: { Label("Add a jar…", systemImage: "plus") }.buttonStyle(PrimaryButtonStyle(large: false)).disabled(jars.busy)
            }
            if jars.busy {
                HStack(spacing: 10) { ProgressView().controlSize(.small); Text(jars.status).font(.system(size: 12.5)).foregroundStyle(.secondary) }
            }
            if let p = jars.problem {
                Label(p, systemImage: "exclamationmark.triangle.fill").font(.system(size: 12.5)).foregroundStyle(Theme.sand).fixedSize(horizontal: false, vertical: true)
            }
            VStack(spacing: 12) {
                originalRow
                ForEach(jars.jars) { JarRow(jar: $0) }
            }
            Button { NSWorkspace.shared.activateFileViewerSelecting([AppPaths.jars]) } label: { Label("Show in Finder", systemImage: "folder") }.buttonStyle(SecondaryButtonStyle())
        }
    }

    private var originalRow: some View {
        let selected = state.settings.jarID == nil
        return VStack(alignment: .leading, spacing: 10) {
            HStack {
                Image(systemName: "shippingbox.fill").foregroundStyle(Theme.accent)
                VStack(alignment: .leading, spacing: 2) {
                    Text("Silica patched").font(.system(size: 14, weight: .semibold))
                    Text("Mojang's Beta 1.7.3 with Silica's improvements, verified against their checksum.").font(.system(size: 12)).foregroundStyle(.secondary)
                }
                Spacer()
                Button { recheckOriginal() } label: { Label("Re-check", systemImage: "arrow.clockwise") }
                    .buttonStyle(SecondaryButtonStyle()).disabled(jars.originalChecking || jars.busy || state.runner.isRunning)
                if selected { Text("In use").font(.system(size: 11.5, weight: .semibold)).foregroundStyle(.secondary) }
                else { Button("Use") { state.settings.jarID = nil }.buttonStyle(SecondaryButtonStyle()) }
            }
            if jars.originalChecking {
                HStack(spacing: 8) { ProgressView().controlSize(.small); Text("Checking Silica's patches…").font(.system(size: 12.5)).foregroundStyle(.secondary) }
            } else if let r = jars.originalReport {
                let patched = r.identityOk && r.patchFailures.isEmpty
                HStack(spacing: 6) {
                    Image(systemName: patched ? "checkmark.circle.fill" : "xmark.octagon.fill").foregroundStyle(patched ? Theme.good : Theme.bad)
                    Text(patched ? "Silica's patches apply to this jar (\(r.patchedClasses) classes)" : "\(r.patchFailures.count) of Silica's patches cannot apply")
                        .font(.system(size: 12.5, weight: .semibold))
                }
            }
        }
        .padding(14).background(Theme.card.opacity(0.9), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .task { await jars.checkOriginal(stageDir: state.settings.stageDir, java: state.install.javaPath) }
    }

    private func recheckOriginal() {
        Task { await jars.checkOriginal(stageDir: state.settings.stageDir, java: state.install.javaPath) }
    }
}

private struct JarRow: View {
    @EnvironmentObject private var state: AppState
    @EnvironmentObject private var jars: JarLibrary
    let jar: CustomJar
    @State private var open = false

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 10) {
                JarVerdictBadge(report: jar.report, failure: jar.failure)
                Spacer()
                Button { Task { await jars.recheck(jar.id, stageDir: state.settings.stageDir, java: state.install.javaPath) } } label: { Label("Re-check", systemImage: "arrow.clockwise") }
                    .buttonStyle(SecondaryButtonStyle()).disabled(jars.busy || state.runner.isRunning)
                if state.settings.jarID == jar.id { Text("In use").font(.system(size: 11.5, weight: .semibold)).foregroundStyle(.secondary) }
                else { Button("Use") { state.settings.jarID = jar.id }.buttonStyle(SecondaryButtonStyle()) }
                Button { if state.settings.jarID == jar.id { state.settings.jarID = nil }; jars.remove(jar.id) } label: { Image(systemName: "trash") }
                    .buttonStyle(SecondaryButtonStyle(tint: Theme.bad)).help("Remove this jar and its game folder").disabled(state.runner.isRunning)
            }
            Text(jar.name).font(.system(size: 14, weight: .semibold)).lineLimit(1)
            if let f = jar.failure { Text(f).font(.system(size: 12)).foregroundStyle(Theme.bad).textSelection(.enabled) }
            if let r = jar.report {
                ForEach(r.reasons + r.notes, id: \.self) { Text($0).font(.system(size: 12.5)).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true) }
                HStack(spacing: 6) {
                    let patched = r.identityOk && r.patchFailures.isEmpty
                    Image(systemName: patched ? "checkmark.circle.fill" : "xmark.octagon.fill").foregroundStyle(patched ? Theme.good : Theme.bad)
                    Text(patched ? "Silica's patches apply to this jar (\(r.patchedClasses) classes)" : "\(r.patchFailures.count) of Silica's patches cannot apply")
                        .font(.system(size: 12.5, weight: .semibold))
                }
                Text("\(r.methodsChanged) methods changed · \(r.classesAdded) classes added · \(r.patchedClasses) classes patched").font(.system(size: 11.5)).foregroundStyle(.secondary)
                if !(r.overlap + r.patchFailures + r.brokenLinks + r.keyMissing).isEmpty || r.classesAdded + r.classesChanged > 0 {
                    DisclosureGroup(isExpanded: $open) { details(r) } label: { Text("Details").font(.system(size: 12, weight: .semibold)) }
                }
            }
            Text("Its worlds and mod files live in their own folder, separate from the original game's.").font(.system(size: 11)).foregroundStyle(.tertiary)
        }
        .padding(14).background(Theme.card.opacity(0.9), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    @ViewBuilder private func details(_ r: JarReport) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            list("Patches that cannot apply", r.patchFailures)
            list("Not provided by Silica", r.brokenLinks)
            list("Changed by the jar and patched by Silica", r.overlap)
            list("Missing key classes", r.keyMissing)
            list("Classes the jar changed", r.changedClassNames)
            list("Classes the jar added", r.addedClassNames)
        }.padding(.top, 6)
    }

    @ViewBuilder private func list(_ title: String, _ items: [String]) -> some View {
        if !items.isEmpty {
            VStack(alignment: .leading, spacing: 3) {
                Text(title.uppercased()).font(.system(size: 9.5, weight: .bold)).tracking(0.8).foregroundStyle(.secondary)
                Text(items.joined(separator: "\n")).font(.system(size: 11, design: .monospaced)).textSelection(.enabled).fixedSize(horizontal: false, vertical: true)
            }
        }
    }
}

struct JarVerdictBadge: View {
    let report: JarReport?
    let failure: String?
    var body: some View {
        let level: JarReport.Level = report?.level ?? .red
        HStack(spacing: 6) {
            Image(systemName: level.symbol)
            Text(report?.headline ?? "Could not be checked")
        }
        .font(.system(size: 12, weight: .bold)).foregroundStyle(level.color)
        .padding(.horizontal, 10).padding(.vertical, 4).background(level.color.opacity(0.14), in: Capsule())
    }
}
