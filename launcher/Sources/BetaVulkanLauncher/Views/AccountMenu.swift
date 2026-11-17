import SwiftUI

/// Minimal account button in the top bar and crisp account menu popover.
struct AccountMenu: View {
    @EnvironmentObject private var state: AppState
    @EnvironmentObject private var runner: GameRunner
    @Environment(\.colorScheme) private var scheme
    @State private var open = false
    @State private var hover = false

    /// SILICA_DEMO_NAME replaces the shown account name, so screenshots and recordings do not show a real one.
    private static let demoName = ProcessInfo.processInfo.environment["SILICA_DEMO_NAME"]

    private var busy: Bool { runner.isRunning || state.benchBusy || state.signingIn }
    private var hasAccounts: Bool { state.player != nil || !state.accounts.isEmpty }
    private var displayName: String {
        Self.demoName ?? state.player?.name ?? (state.signingIn ? "Signing in…" : "Choose an account")
    }

    var body: some View {
        Group {
            if hasAccounts {
                accountButton
            } else {
                Button {
                    state.signInWithMicrosoft()
                } label: {
                    Text(state.signingIn ? "Signing in…" : "Sign in")
                        .font(.system(size: 13.5, weight: .medium))
                        .foregroundStyle(hover ? Theme.text : Theme.dim)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background(hover ? Color.primary.opacity(0.06) : Color.clear,
                                    in: RoundedRectangle(cornerRadius: 8, style: .continuous))
                }
                .buttonStyle(.plain)
                .disabled(busy)
                .onHover { hover = $0 }
            }
        }
    }

    private var accountButton: some View {
        Button {
            open.toggle()
        } label: {
            HStack(spacing: 8) {
                Circle()
                    .fill(state.player?.isValid == true ? Theme.accent : Theme.sand)
                    .frame(width: 7, height: 7)
                Text(displayName)
                    .font(.system(size: 13.5, weight: .medium))
                    .lineLimit(1)
                Image(systemName: "chevron.down")
                    .font(.system(size: 9, weight: .bold))
                    .foregroundStyle(Theme.dim)
                    .rotationEffect(.degrees(open ? 180 : 0))
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(hover || open ? Color.primary.opacity(0.06) : Color.clear,
                        in: RoundedRectangle(cornerRadius: 8, style: .continuous))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(busy)
        .accessibilityLabel("Account")
        .onHover { hover = $0 }
        .popover(isPresented: $open, arrowEdge: .bottom) {
            AccountList(close: { open = false })
                .environment(\.colorScheme, scheme)
        }
    }
}

private struct AccountList: View {
    @EnvironmentObject private var state: AppState
    let close: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            if !state.accounts.isEmpty {
                Text("Accounts")
                    .font(.system(size: 11, weight: .semibold))
                    .foregroundStyle(Theme.dim)
                    .padding(.horizontal, 10)
                    .padding(.bottom, 4)
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
        .frame(width: 264)
    }
}

private struct AccountRow: View {
    let name: String
    let active: Bool
    let action: () -> Void
    @State private var hover = false

    var body: some View {
        Button(action: action) {
            HStack(spacing: 10) {
                Text(String(name.prefix(1)).uppercased())
                    .font(.system(size: 12, weight: .bold))
                    .foregroundStyle(Theme.onAccent)
                    .frame(width: 28, height: 28)
                    .background(active ? Theme.accent : Theme.dim, in: Circle())
                VStack(alignment: .leading, spacing: 1) {
                    Text(name)
                        .font(.system(size: 13.5, weight: active ? .semibold : .medium))
                        .lineLimit(1)
                    Text(active ? "Active" : "Switch account")
                        .font(.system(size: 11))
                        .foregroundStyle(.secondary)
                }
                Spacer(minLength: 8)
                if active {
                    Image(systemName: "checkmark")
                        .font(.system(size: 11.5, weight: .bold))
                        .foregroundStyle(Theme.accent)
                }
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .fill(Color.primary.opacity(hover || active ? 0.08 : 0))
            )
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
            HStack(spacing: 10) {
                Image(systemName: symbol)
                    .font(.system(size: 14))
                    .frame(width: 28)
                    .foregroundStyle(tint ?? .secondary)
                Text(title)
                    .font(.system(size: 13.5, weight: .medium))
                    .foregroundStyle(tint ?? .primary)
                    .lineLimit(1)
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 6)
            .background(
                RoundedRectangle(cornerRadius: 10, style: .continuous)
                    .fill(Color.primary.opacity(hover ? 0.08 : 0))
            )
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .onHover { hover = $0 }
    }
}
