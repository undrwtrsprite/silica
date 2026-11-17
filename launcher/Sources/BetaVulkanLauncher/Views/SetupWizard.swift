import AppKit
import SwiftUI

/// Guided setup in the calm website aesthetic: split panel with clean step rail on the left and quiet onboarding stages on the right.
struct SetupWizard: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @EnvironmentObject private var state: AppState
    @EnvironmentObject private var install: InstallManager
    @EnvironmentObject private var jars: JarLibrary

    @Local private var useOwn = false
    @Local private var chosen: URL? = nil
    @Local private var found: URL? = nil
    @Local private var modJar: URL? = nil

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
            DitherAurora()
                .frame(height: 240)
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                .ignoresSafeArea()
                .allowsHitTesting(false)

            HStack(spacing: 0) {
                stepRail
                    .frame(width: 280)
                    .frame(maxHeight: .infinity, alignment: .topLeading)
                    .background(Theme.bar)

                Rectangle().fill(Theme.stroke).frame(width: 1)

                ZStack {
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
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Theme.card)
                .animation(reduceMotion ? nil : LauncherMotion.change, value: stage)
            }
            .frame(width: 860, height: 540)
            .clipShape(RoundedRectangle(cornerRadius: 18, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(Theme.stroke, lineWidth: 1))
            .shadow(color: .black.opacity(0.28), radius: 28, y: 10)
        }
        .onAppear {
            Task.detached {
                let url = InstallManager.detectExistingClient()
                await MainActor.run { found = url }
            }
        }
    }

    private var stepRail: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack(spacing: 10) {
                Image(nsImage: Theme.appMark)
                    .resizable()
                    .frame(width: 36, height: 36)
                    .clipShape(RoundedRectangle(cornerRadius: 9, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    Text("Silica setup").font(.system(size: 16.5, weight: .bold))
                    Text("Beta 1.7.3 for Apple Silicon").font(.system(size: 11.5)).foregroundStyle(.secondary)
                }
            }
            .padding(.bottom, 26)

            let accountStep = InstallManager.StepInfo(
                id: "account",
                title: "Microsoft sign-in",
                detail: "Verify that you own Minecraft: Java Edition.",
                symbol: "person.crop.circle",
                state: verified ? .done(state.player?.name ?? "Verified") : .pending
            )
            let steps = [accountStep] + install.steps
            ForEach(Array(steps.enumerated()), id: \.element.id) { i, st in
                StepRailRow(index: i + 1, info: st, last: i == steps.count - 1)
            }

            Spacer()

            if stage == .account || stage == .welcome {
                Button("Not now") { state.wizardOpen = false }
                    .buttonStyle(.plain)
                    .font(.system(size: 12.5))
                    .foregroundStyle(.secondary)
                    .disabled(state.signingIn)
            }
        }
        .padding(26)
    }

    private var account: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Sign in to set up Silica")
                .font(.system(size: 26, weight: .bold))
                .tracking(-0.7)
                .accessibilityAddTraits(.isHeader)
            Text("Use the Microsoft account that owns Minecraft: Java Edition. Once ownership is verified, Silica can install Beta 1.7.3.")
                .font(.system(size: 13.5))
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
        .padding(32)
    }

    private var welcome: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Get Beta 1.7.3 ready")
                .font(.system(size: 26, weight: .bold))
                .tracking(-0.7)
            Text("Silica sets up Java 21, verifies the game locally, and applies native patches once.")
                .font(.system(size: 13.5))
                .foregroundStyle(.secondary)
                .padding(.top, 5)

            HStack(spacing: 12) {
                sourceCard(
                    title: "Download from Mojang",
                    detail: "Official client checked against Mojang's SHA-1 checksum.",
                    symbol: "arrow.down.circle.fill",
                    selected: !useOwn,
                    badge: "Easiest"
                ) { useOwn = false }

                sourceCard(
                    title: "Use my own copy",
                    detail: ownDetail,
                    symbol: "folder.fill",
                    selected: useOwn,
                    badge: found != nil ? "Found" : nil
                ) {
                    useOwn = true
                    if chosen == nil, let f = found { chosen = f }
                }
            }
            .padding(.top, 20)

            HStack(spacing: 12) {
                Image(systemName: "square.stack.3d.up.fill")
                    .font(.system(size: 17))
                    .foregroundStyle(modJar == nil ? AnyShapeStyle(Color.secondary) : AnyShapeStyle(Theme.accent))
                VStack(alignment: .leading, spacing: 2) {
                    Text(modJar.map { "Add instance for \($0.lastPathComponent)" } ?? "Create a modded instance too? (optional)")
                        .font(.system(size: 13, weight: .semibold))
                    Text("Adds a separate instance alongside official Beta 1.7.3 without modifying your jar.")
                        .font(.system(size: 11.5))
                        .foregroundStyle(.secondary)
                }
                Spacer()
                if modJar != nil { Button("Remove") { modJar = nil }.buttonStyle(SecondaryButtonStyle()) }
                Button(modJar == nil ? "Choose jar…" : "Change…") { pickModJar() }.buttonStyle(SecondaryButtonStyle())
            }
            .padding(12)
            .surface(radius: 12, fill: Theme.cardHigh)
            .padding(.top, 14)

            if let f = install.failure {
                HStack(alignment: .top, spacing: 10) {
                    Image(systemName: "exclamationmark.triangle.fill").foregroundStyle(Theme.sand)
                    VStack(alignment: .leading, spacing: 2) {
                        Text("\(f.title) didn't finish").font(.system(size: 13, weight: .semibold))
                        Text(f.message).font(.system(size: 12)).foregroundStyle(.secondary).lineLimit(3)
                    }
                }
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .surface(radius: 12, fill: Theme.sand.opacity(0.12))
                .padding(.top, 14)
            } else if !state.stageReady {
                HStack(alignment: .top, spacing: 10) {
                    Image(systemName: "hammer.fill").foregroundStyle(Theme.sand)
                    VStack(alignment: .leading, spacing: 4) {
                        Text("This build has no game files bundled.").font(.system(size: 13, weight: .semibold))
                        Text("Run `gradle :hooks:stageGame`, or choose the folder it made.").font(.system(size: 12)).foregroundStyle(.secondary)
                        Button("Choose folder…") { state.choose(\.stageDir, message: "Choose hooks/build/stage") }
                            .buttonStyle(SecondaryButtonStyle(tint: Theme.sand))
                    }
                }
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .surface(radius: 12, fill: Theme.sand.opacity(0.12))
                .padding(.top, 14)
            }

            Spacer()

            HStack {
                Spacer()
                Button { begin() } label: {
                    Label(install.failure != nil ? "Try again" : "Install everything", systemImage: "arrow.right")
                }
                .buttonStyle(PrimaryButtonStyle(large: true))
                .keyboardShortcut(.defaultAction)
                .disabled(useOwn && chosen == nil)
            }
        }
        .padding(32)
    }

    private var running: some View {
        VStack(spacing: 16) {
            Spacer()
            DitherProgressBar(progress: install.overall)
            Text(install.currentTitle)
                .font(.system(size: 16, weight: .semibold))
                .monospacedDigit()
                .multilineTextAlignment(.center)
                .padding(.top, 8)
            Text("This runs automatically.")
                .font(.system(size: 12.5))
                .foregroundStyle(.secondary)
            Spacer()
        }
        .padding(32)
    }

    private var finished: some View {
        VStack(spacing: 14) {
            Spacer()
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 52))
                .foregroundStyle(Theme.accent)
            Text("You're all set")
                .font(.system(size: 28, weight: .bold))
                .tracking(-0.7)
            Text(verified ? "Minecraft Beta 1.7.3 is installed." : "Minecraft Beta 1.7.3 is installed. Sign in on Play to launch.")
                .font(.system(size: 13.5))
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)

            if jars.busy {
                HStack(spacing: 8) {
                    ProgressView().controlSize(.small)
                    Text(jars.status.isEmpty ? "Checking custom instance…" : jars.status)
                        .font(.system(size: 12.5)).foregroundStyle(.secondary)
                }
            } else if let j = jars.jar(state.wizardJarID) {
                VStack(spacing: 6) {
                    JarVerdictBadge(report: j.report, failure: j.failure)
                    Text(j.name).font(.system(size: 13, weight: .semibold))
                }
                .padding(.top, 4)
            }

            Button {
                state.tab = .play
                state.wizardOpen = false
            } label: {
                Label("Continue to Play", systemImage: "arrow.right")
            }
            .buttonStyle(PrimaryButtonStyle(large: true))
            .keyboardShortcut(.defaultAction)
            .disabled(jars.busy)
            .padding(.top, 8)
            Spacer()
        }
        .padding(32)
    }

    private var ownDetail: String {
        if let c = chosen { return c.lastPathComponent }
        if let f = found { return "Found on this Mac: \(f.lastPathComponent)" }
        return "Pick a Beta 1.7.3 jar on this Mac."
    }

    private func sourceCard(title: String, detail: String, symbol: String, selected: Bool, badge: String?, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Image(systemName: symbol).font(.system(size: 18)).foregroundStyle(selected ? AnyShapeStyle(Theme.accent) : AnyShapeStyle(Color.secondary))
                    Spacer()
                    if let badge {
                        Text(badge)
                            .font(.system(size: 10.5, weight: .semibold))
                            .padding(.horizontal, 8).padding(.vertical, 3)
                            .background(Theme.good.opacity(0.16), in: Capsule())
                            .foregroundStyle(Theme.good)
                    }
                }
                Text(title).font(.system(size: 14.5, weight: .semibold))
                Text(detail).font(.system(size: 12)).foregroundStyle(.secondary).lineLimit(3)
                if title.hasPrefix("Use"), selected {
                    Button(chosen == nil ? "Choose file…" : "Change…") { pickJar() }.buttonStyle(SecondaryButtonStyle())
                }
            }
            .padding(14)
            .frame(maxWidth: .infinity, minHeight: 130, alignment: .topLeading)
            .surface(radius: 14, fill: selected ? Theme.cardHigh : Theme.card, selected: selected)
            .animation(reduceMotion ? nil : LauncherMotion.change, value: selected)
        }
        .buttonStyle(.plain)
    }

    private func pickModJar() {
        let panel = NSOpenPanel()
        panel.message = "Choose a modded Minecraft Beta 1.7.3 jar"
        panel.allowedContentTypes = [.init(filenameExtension: "jar")!]
        panel.canChooseDirectories = false
        if panel.runModal() == .OK { modJar = panel.url }
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
}

private struct StepRailRow: View {
    let index: Int
    let info: InstallManager.StepInfo
    let last: Bool

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(spacing: 0) {
                ZStack {
                    Circle()
                        .fill(circleFill)
                        .frame(width: 28, height: 28)
                    if info.state.isDone {
                        Image(systemName: "checkmark").font(.system(size: 11.5, weight: .bold)).foregroundStyle(Theme.onAccent)
                    } else if case .failed = info.state {
                        Image(systemName: "exclamationmark").font(.system(size: 12, weight: .bold)).foregroundStyle(.white)
                    } else {
                        Image(systemName: info.symbol).font(.system(size: 12)).foregroundStyle(.secondary)
                    }
                }
                if !last {
                    Rectangle()
                        .fill(info.state.isDone ? Theme.good.opacity(0.5) : Theme.stroke)
                        .frame(width: 2, height: 32)
                        .padding(.vertical, 4)
                }
            }

            VStack(alignment: .leading, spacing: 2) {
                Text(info.title).font(.system(size: 13.5, weight: .semibold))
                Group {
                    switch info.state {
                    case .running(_, let m): Text(m)
                    case .done(let m): Text(m)
                    case .failed: Text("Needs attention").foregroundStyle(Theme.bad)
                    case .pending: Text(info.detail)
                    }
                }
                .font(.system(size: 11.5))
                .foregroundStyle(.secondary)
                .lineLimit(2)
                .fixedSize(horizontal: false, vertical: true)
                .frame(minHeight: 28, alignment: .topLeading)
            }
        }
    }

    private var circleFill: Color {
        switch info.state {
        case .done: return Theme.good
        case .failed: return Theme.bad
        case .running: return Theme.tint.opacity(0.25)
        case .pending: return Color.primary.opacity(0.08)
        }
    }
}
