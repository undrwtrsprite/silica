import AppKit
import SwiftUI

/// The guided first-run setup: pick where Minecraft comes from, press one button, and Silica installs Java, gets and verifies the game, patches it on this Mac
/// and fetches the sounds, showing each step as it happens. It also opens from Settings to repair an install. Everything runs unattended once started.
struct SetupWizard: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @EnvironmentObject private var state: AppState
    @EnvironmentObject private var install: InstallManager
    @Local private var useOwn = false
    @Local private var chosen: URL? = nil
    @Local private var found: URL? = nil
    /// An optional modded jar to add (and check) once the original game is installed.
    @Local private var modJar: URL? = nil
    @EnvironmentObject private var jars: JarLibrary

    private enum Stage { case account, welcome, running, done }

    private var verified: Bool { state.player?.isValid == true }
    private var stage: Stage {
        if install.busy { return .running }
        if install.ready && state.stageReady { return .done }
        return verified ? .welcome : .account
    }

    var body: some View {
        ZStack {
            Theme.bg.ignoresSafeArea()
            LauncherPanel(railWidth: 286) {
                stepRail
            } content: {
                ZStack(alignment: .center) {
                    Group {
                        switch stage {
                        case .account: account
                        case .welcome: welcome
                        case .running: running
                        case .done: finished
                        }
                    }
                    .id(stage)
                    .transition(reduceMotion ? .identity : LauncherMotion.page)
                }
                .animation(reduceMotion ? nil : LauncherMotion.change, value: stage)
            }
            .frame(width: 880, height: 560)
        }
        .onAppear {
            Task.detached {
                let url = InstallManager.detectExistingClient()
                await MainActor.run { found = url }
            }
        }
    }

    // MARK: The step list

    private var stepRail: some View {
        VStack(alignment: .leading, spacing: 0) {
            LauncherBrand(title: "Silica setup")
                .padding(.bottom, 28)
            let accountStep = InstallManager.StepInfo(
                id: "account", title: "Microsoft sign-in", detail: "Verify that you own Minecraft: Java Edition.",
                symbol: "person.crop.circle", state: verified ? .done(state.player?.name ?? "Verified") : .pending
            )
            let steps = [accountStep] + install.steps
            ForEach(Array(steps.enumerated()), id: \.element.id) { i, st in
                StepRailRow(index: i + 1, info: st, last: i == steps.count - 1)
            }
            Spacer()
            if stage == .account || stage == .welcome {
                Button("Not now") { state.wizardOpen = false }.buttonStyle(.plain).font(.system(size: 12.5)).foregroundStyle(.secondary)
                    .disabled(state.signingIn)
            }
        }
        .padding(28)
    }

    private var account: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Sign in to set up Silica")
                .font(.system(size: 28, weight: .bold, design: .rounded))
                .accessibilityAddTraits(.isHeader)
            Text("Use the Microsoft account that owns Minecraft: Java Edition. Once ownership is verified, you can install Beta 1.7.3.")
                .font(.system(size: 14))
                .foregroundStyle(.secondary)
            Spacer()
            HStack {
                Spacer()
                if state.signingIn {
                    ProgressView("Signing in…").controlSize(.small)
                } else {
                    Button { state.signInWithMicrosoft() } label: {
                        Label("Sign in with Microsoft", systemImage: "person.crop.circle")
                    }
                    .buttonStyle(PrimaryButtonStyle(large: true))
                    .keyboardShortcut(.defaultAction)
                }
            }
        }
        .padding(34)
    }

    // MARK: Welcome

    private var welcome: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Let's get Minecraft running natively.").font(.system(size: 28, weight: .bold, design: .rounded)).fixedSize(horizontal: false, vertical: true)
            Text("Silica sets everything up for you. It takes a few minutes and only happens once.").font(.system(size: 14)).foregroundStyle(.secondary).padding(.top, 6)

            Text("WHERE SHOULD MINECRAFT COME FROM?").font(.system(size: 10.5, weight: .bold)).tracking(0.9).foregroundStyle(.secondary).padding(.top, 24).padding(.bottom, 10)
            HStack(spacing: 12) {
                sourceCard(title: "Download it for me", detail: "From Mojang's own servers, checked against their checksum.", symbol: "arrow.down.circle.fill", selected: !useOwn, badge: "Easiest") { useOwn = false }
                sourceCard(title: "Use my own copy", detail: ownDetail, symbol: "folder.fill", selected: useOwn, badge: found != nil ? "Found one" : nil) {
                    useOwn = true
                    if chosen == nil, let f = found { chosen = f }
                }
            }

            modJarRow
            if let f = install.failure {
                HStack(alignment: .top, spacing: 10) {
                    Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(Theme.sand)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("\(f.title) didn't finish").font(.system(size: 13, weight: .semibold))
                        Text(f.message).font(.system(size: 12)).foregroundStyle(.secondary).textSelection(.enabled).lineLimit(4)
                    }
                }
                .padding(12).frame(maxWidth: .infinity, alignment: .leading).background(Theme.sand.opacity(0.12), in: RoundedRectangle(cornerRadius: 12, style: .continuous)).padding(.top, 16)
            } else if !state.stageReady {
                HStack(alignment: .top, spacing: 10) {
                    Image(systemName: "hammer.fill").foregroundStyle(Theme.sand)
                    VStack(alignment: .leading, spacing: 4) {
                        Text("This build has no game files bundled.").font(.system(size: 13, weight: .semibold))
                        Text("Run `gradle :hooks:stageGame`, or choose the folder it made.").font(.system(size: 12)).foregroundStyle(.secondary)
                        Button("Choose folder…") { state.choose(\.stageDir, message: "Choose hooks/build/stage") }.buttonStyle(SecondaryButtonStyle(tint: Theme.sand))
                    }
                }
                .padding(12).frame(maxWidth: .infinity, alignment: .leading).background(Theme.sand.opacity(0.12), in: RoundedRectangle(cornerRadius: 12, style: .continuous)).padding(.top, 16)
            } else {
                VStack(alignment: .leading, spacing: 7) {
                    note("lock.shield.fill", "Nothing from Mojang ships inside Silica.")
                    note("wand.and.stars", "Patching happens here, on your copy of the game.")
                    note("clock.fill", "Takes about three minutes. You can leave this window open.")
                }
                .padding(.top, 18)
            }
            Spacer()
            HStack {
                Spacer()
                Button { begin() } label: { Label(install.failure != nil ? "Try again" : "Install everything", systemImage: install.failure != nil ? "arrow.clockwise" : "sparkles") }
                    .buttonStyle(PrimaryButtonStyle(large: true)).keyboardShortcut(.defaultAction)
                    .disabled(useOwn && chosen == nil)
            }
        }
        .padding(34)
    }

    /// Optional: bring a modded jar. It is added after the original game is installed and checked against Silica's patches.
    private var modJarRow: some View {
        HStack(spacing: 12) {
            Image(systemName: "puzzlepiece.extension.fill").font(.system(size: 18)).foregroundStyle(modJar == nil ? AnyShapeStyle(Color.secondary) : AnyShapeStyle(Theme.accent))
            VStack(alignment: .leading, spacing: 2) {
                Text(modJar.map { "Also add \($0.lastPathComponent)" } ?? "Have a modded jar? (optional)").font(.system(size: 13, weight: .semibold))
                Text("Silica keeps it untouched, checks that its improvements fit, and tells you if anything won't work.").font(.system(size: 11.5)).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            }
            Spacer()
            if modJar != nil { Button("Remove") { modJar = nil }.buttonStyle(SecondaryButtonStyle()) }
            Button(modJar == nil ? "Choose jar…" : "Change…") { pickModJar() }.buttonStyle(SecondaryButtonStyle(tint: Theme.tint))
        }
        .padding(12).background(Theme.card.opacity(0.55), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(Theme.stroke))
        .padding(.top, 14)
    }

    private func pickModJar() {
        let panel = NSOpenPanel()
        panel.message = "Choose a modded Minecraft Beta 1.7.3 jar"
        panel.allowedContentTypes = [.init(filenameExtension: "jar")!]
        panel.canChooseDirectories = false
        if panel.runModal() == .OK { modJar = panel.url }
    }

    private var ownDetail: String {
        if let c = chosen { return c.lastPathComponent }
        if let f = found { return "Found on this Mac: \(f.lastPathComponent)" }
        return "Pick the b1.7.3 jar from the Minecraft launcher."
    }

    private func note(_ symbol: String, _ text: String) -> some View {
        HStack(spacing: 10) {
            Image(systemName: symbol).font(.system(size: 12)).foregroundStyle(Theme.good).frame(width: 18)
            Text(text).font(.system(size: 12.5)).foregroundStyle(.secondary)
        }
    }

    private func sourceCard(title: String, detail: String, symbol: String, selected: Bool, badge: String?, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Image(systemName: symbol).font(.system(size: 20)).foregroundStyle(selected ? AnyShapeStyle(Theme.accent) : AnyShapeStyle(Color.secondary))
                    Spacer()
                    if let badge { Text(badge).font(.system(size: 10, weight: .bold)).padding(.horizontal, 8).padding(.vertical, 3).background(Theme.good.opacity(0.18), in: Capsule()).foregroundStyle(Theme.good) }
                }
                Text(title).font(.system(size: 15, weight: .semibold, design: .rounded))
                Text(detail).font(.system(size: 12)).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true).lineLimit(3)
                if title.hasPrefix("Use"), selected {
                    Button(chosen == nil ? "Choose file…" : "Choose another…") { pickJar() }.buttonStyle(SecondaryButtonStyle(tint: Theme.tint))
                }
            }
            .padding(16).frame(maxWidth: .infinity, minHeight: 138, alignment: .topLeading)
            .background(Theme.card.opacity(selected ? 0.95 : 0.55), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(selected ? AnyShapeStyle(Theme.accent) : AnyShapeStyle(Theme.stroke), lineWidth: selected ? 2 : 1))
            .animation(reduceMotion ? nil : LauncherMotion.change, value: selected)
        }
        .buttonStyle(.plain)
    }

    private func pickJar() {
        let panel = NSOpenPanel()
        panel.message = "Choose your Minecraft Beta 1.7.3 jar"
        panel.allowedContentTypes = [.init(filenameExtension: "jar")!]
        panel.canChooseDirectories = false
        if panel.runModal() == .OK { chosen = panel.url }
    }

    private func begin() {
        if useOwn {
            guard let chosen else { return }
            state.setUp(source: .local(chosen), thenAdd: modJar)
        } else {
            state.setUp(source: .mojang, thenAdd: modJar)
        }
    }

    // MARK: Running

    private var running: some View {
        VStack(spacing: 0) {
            Spacer()
            ProgressRing(progress: install.overall)
            Text(install.currentTitle).font(.system(size: 17, weight: .semibold, design: .rounded)).monospacedDigit().padding(.top, 26).multilineTextAlignment(.center).frame(minHeight: 44, alignment: .top)
            Text("This runs by itself. Feel free to make a coffee.").font(.system(size: 13)).foregroundStyle(.secondary).padding(.top, 6)
            Spacer()
        }
        .padding(34)
    }

    // MARK: Done

    /// What happened to the jar chosen in the setup: still being checked, the verdict, or why it was refused.
    @ViewBuilder private var jarResult: some View {
        if jars.busy {
            HStack(spacing: 10) { ProgressView().controlSize(.small); Text(jars.status.isEmpty ? "Checking your jar…" : jars.status).font(.system(size: 13)).foregroundStyle(.secondary) }
        } else if let j = jars.jar(state.wizardJarID) {
            VStack(spacing: 8) {
                JarVerdictBadge(report: j.report, failure: j.failure)
                Text(j.name).font(.system(size: 13, weight: .semibold))
                Text(j.failure ?? (j.report?.reasons.first ?? j.report?.notes.first ?? "")).font(.system(size: 12)).foregroundStyle(.secondary).multilineTextAlignment(.center).frame(maxWidth: 420)
                if j.report?.level == .red { Text("It stays in your list; the original game is selected.").font(.system(size: 11.5)).foregroundStyle(.secondary) }
            }
        } else if let p = jars.problem {
            Label(p, systemImage: "exclamationmark.triangle.fill").font(.system(size: 12.5)).foregroundStyle(Theme.sand).multilineTextAlignment(.center).frame(maxWidth: 420)
        }
    }

    private var finished: some View {
        VStack(spacing: 0) {
            Spacer()
            SetupCompleteMark()
            Text("You're all set").font(.system(size: 30, weight: .bold, design: .rounded)).padding(.top, 26)
            Text(verified ? "Minecraft Beta 1.7.3 is installed." : "Minecraft Beta 1.7.3 is installed. Sign in on the Play page to continue.").font(.system(size: 14)).foregroundStyle(.secondary).multilineTextAlignment(.center).padding(.top, 8)
            jarResult.padding(.top, 18)
            Button { state.tab = .play; state.wizardOpen = false } label: { Label("Continue to Play", systemImage: "arrow.right") }
                .buttonStyle(PrimaryButtonStyle(large: true)).keyboardShortcut(.defaultAction).padding(.top, 24).disabled(jars.busy)
            Spacer()
        }
        .padding(34)
    }
}

private struct StepRailRow: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let index: Int
    let info: InstallManager.StepInfo
    let last: Bool

    var body: some View {
        HStack(alignment: .top, spacing: 14) {
            VStack(spacing: 0) {
                StepBadge(index: index, symbol: info.symbol, state: info.state)
                if !last { Rectangle().fill(done ? Theme.good.opacity(0.55) : Color.primary.opacity(0.12)).frame(width: 2, height: 34).padding(.vertical, 4).animation(reduceMotion ? nil : LauncherMotion.change, value: done) }
            }
            VStack(alignment: .leading, spacing: 3) {
                Text(info.title).font(.system(size: 14, weight: .semibold))
                Group {
                    switch info.state {
                    case .running(_, let m): Text(m)
                    case .done(let m): Text(m)
                    case .failed: Text("Needs attention").foregroundStyle(Theme.bad)
                    case .pending: Text(info.detail)
                    }
                }
                .font(.system(size: 11.5)).monospacedDigit().foregroundStyle(.secondary).lineLimit(2).fixedSize(horizontal: false, vertical: true)
                // Two lines are always reserved, so a count that grows ("Downloading sounds 9/1200") never changes the row's height and shoves the steps below it
                .frame(minHeight: 30, alignment: .topLeading)
            }
            .padding(.top, 2)
        }
    }

    private var done: Bool { info.state.isDone }
}

private struct StepBadge: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let index: Int
    let symbol: String
    let state: InstallManager.State
    @Local private var pop = false

    var body: some View {
        ZStack {
            Circle().fill(fill).frame(width: 34, height: 34)
            switch state {
            case .done:
                Image(systemName: "checkmark").font(.system(size: 14, weight: .heavy)).foregroundStyle(.white).scaleEffect(pop ? 1 : 0.2).opacity(pop ? 1 : 0)
            case .failed:
                Image(systemName: "exclamationmark").font(.system(size: 15, weight: .heavy)).foregroundStyle(.white)
            case .running(let p, _):
                Circle().trim(from: 0, to: max(0.06, p)).stroke(Theme.accent, style: StrokeStyle(lineWidth: 3, lineCap: .round)).rotationEffect(.degrees(-90)).frame(width: 34, height: 34)
                    .animation(reduceMotion ? nil : LauncherMotion.change, value: p)
                Image(systemName: symbol).font(.system(size: 13, weight: .semibold)).foregroundStyle(Theme.tint)
            case .pending:
                Image(systemName: symbol).font(.system(size: 13, weight: .medium)).foregroundStyle(.secondary)
            }
        }
        .onChange(of: state.isDone) { _, d in
            withAnimation(reduceMotion ? nil : LauncherMotion.change) { pop = d }
        }
        .onAppear { pop = state.isDone }
    }

    private var fill: Color {
        switch state {
        case .done: return Theme.good
        case .failed: return Theme.bad
        case .running: return Theme.tint.opacity(0.14)
        case .pending: return Color.primary.opacity(0.08)
        }
    }
}

/// The overall progress as a ring with a percentage.
struct ProgressRing: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let progress: Double
    var body: some View {
        ZStack {
            Circle().stroke(Color.primary.opacity(0.10), lineWidth: 12)
            Circle().trim(from: 0, to: max(0.015, progress))
                .stroke(Theme.accent, style: StrokeStyle(lineWidth: 12, lineCap: .round))
                .rotationEffect(.degrees(-90))
            VStack(spacing: 0) {
                Text("\(Int((progress * 100).rounded()))").font(.system(size: 52, weight: .bold, design: .rounded)).monospacedDigit().contentTransition(.numericText())
                Text("percent").font(.system(size: 12, weight: .medium)).foregroundStyle(.secondary)
            }
        }
        .frame(width: 190, height: 190)
        .animation(reduceMotion ? nil : LauncherMotion.change, value: progress)
    }
}

struct SetupCompleteMark: View {
    var body: some View {
        Image(systemName: "checkmark.circle")
            .font(.system(size: 62, weight: .regular))
            .foregroundStyle(Theme.accent)
            .frame(width: 110, height: 110)
            .accessibilityHidden(true)
    }
}
