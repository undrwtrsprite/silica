import SwiftUI

/// The account control: the signed-in account as a menu (switch, add, sign out) with a plus button for adding another.
/// It sits in the top-right corner of every page.
struct AccountMenu: View {
    @EnvironmentObject private var state: AppState
    @EnvironmentObject private var runner: GameRunner
    @Environment(\.colorScheme) private var scheme
    @State private var open = false

    /// SILICA_DEMO_NAME replaces the shown account name, so screenshots and recordings for the website do not show a real one.
    private static let demoName = ProcessInfo.processInfo.environment["SILICA_DEMO_NAME"]

    private var busy: Bool { runner.isRunning || state.benchBusy || state.signingIn }
    private var hasAccounts: Bool { state.player != nil || !state.accounts.isEmpty }

    var body: some View {
        HStack(spacing: 14) {
            if hasAccounts {
                menu
                Button { state.signInWithMicrosoft() } label: {
                    Image(systemName: "plus").font(.system(size: 13, weight: .semibold))
                        .frame(width: 28, height: 28)
                        .overlay(Circle().strokeBorder(Theme.stroke.opacity(2)))
                }
                .buttonStyle(.plain)
                .disabled(busy)
                .help("Add account")
                .accessibilityLabel("Add account")
            } else {
                Button { state.signInWithMicrosoft() } label: {
                    Text(state.signingIn ? "Signing in…" : "Sign in").font(.system(size: 14, weight: .medium))
                }
                .buttonStyle(.plain)
                .disabled(busy)
            }
        }
        .foregroundStyle(.primary)
    }

    private var menu: some View {
        Button { open.toggle() } label: {
            HStack(spacing: 8) {
                Image(systemName: "person.crop.circle").font(.system(size: 17)).accessibilityHidden(true)
                Text(Self.demoName ?? state.player?.name ?? (state.signingIn ? "Signing in…" : "Choose an account")).font(.system(size: 14, weight: .medium)).lineLimit(1)
                Image(systemName: "chevron.down").font(.system(size: 9, weight: .bold)).foregroundStyle(.secondary).rotationEffect(.degrees(open ? 180 : 0)).accessibilityHidden(true)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(busy)
        .accessibilityLabel("Account")
        .popover(isPresented: $open, arrowEdge: .bottom) {
            AccountList(close: { open = false })
                .environment(\.colorScheme, scheme)
        }
    }
}

/// The card that opens from the account button: every saved account, then Add account and Sign out.
private struct AccountList: View {
    @EnvironmentObject private var state: AppState
    let close: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            if !state.accounts.isEmpty {
                Text("ACCOUNTS").font(.system(size: 9.5, weight: .bold)).tracking(0.8).foregroundStyle(.secondary)
                    .padding(.horizontal, 10).padding(.bottom, 4)
                ForEach(state.accounts) { account in
                    AccountRow(name: account.name, active: account.profileId == state.activeAccountId) {
                        state.switchAccount(to: account.profileId)
                        close()
                    }
                }
                Divider().padding(.vertical, 6)
            }
            ActionRow(title: "Add account", symbol: "plus.circle") {
                close()
                state.signInWithMicrosoft()
            }
            if let player = state.player {
                ActionRow(title: "Sign out of \(player.name)", symbol: "rectangle.portrait.and.arrow.right", tint: Theme.bad) {
                    close()
                    state.signOut()
                }
            }
        }
        .padding(10)
        .frame(width: 270)
    }
}

private struct AccountRow: View {
    let name: String
    let active: Bool
    let action: () -> Void
    @State private var hover = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Text(String(name.prefix(1)).uppercased())
                    .font(.system(size: 13, weight: .bold, design: .rounded))
                    .foregroundStyle(Theme.onAccent)
                    .frame(width: 30, height: 30)
                    .background(Theme.accent, in: Circle())
                VStack(alignment: .leading, spacing: 1) {
                    Text(name).font(.system(size: 14, weight: active ? .semibold : .medium)).lineLimit(1)
                    Text(active ? "Playing as" : "Switch to this account").font(.system(size: 11)).foregroundStyle(.secondary)
                }
                Spacer(minLength: 8)
                if active { Image(systemName: "checkmark").font(.system(size: 12, weight: .bold)).foregroundStyle(Theme.accent) }
            }
            .padding(.horizontal, 10).padding(.vertical, 7)
            .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Color.primary.opacity(hover || active ? 0.08 : 0)))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { hover = $0 }
    }
}

private struct ActionRow: View {
    let title: String
    let symbol: String
    var tint: Color? = nil
    let action: () -> Void
    @State private var hover = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: symbol).font(.system(size: 15)).frame(width: 30).foregroundStyle(tint ?? .secondary)
                Text(title).font(.system(size: 14, weight: .medium)).foregroundStyle(tint ?? .primary).lineLimit(1)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 10).padding(.vertical, 7)
            .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Color.primary.opacity(hover ? 0.08 : 0)))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { hover = $0 }
    }
}
