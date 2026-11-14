import AppKit
import SwiftUI

struct LogsView: View {
    @EnvironmentObject private var runner: GameRunner
    @Local private var filter = ""
    @Local private var follow = true

    private var shown: [(Int, String)] {
        let all = Array(runner.lines.enumerated())
        guard !filter.isEmpty else { return all.map { ($0.offset, $0.element) } }
        return all.filter { $0.element.localizedCaseInsensitiveContains(filter) }.map { ($0.offset, $0.element) }
    }

    var body: some View {
        Card(title: "Game output") {
            HStack {
                HStack(spacing: 8) {
                    Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
                    TextField("Filter logs", text: $filter).textFieldStyle(.plain)
                }
                .padding(9)
                .surface(radius: 10, fill: Theme.cardHigh)
                Spacer(minLength: 8)
                Toggle("Follow", isOn: $follow).toggleStyle(.switch)
                Button("Copy") {
                    NSPasteboard.general.clearContents()
                    NSPasteboard.general.setString(runner.lines.joined(separator: "\n"), forType: .string)
                }
                .buttonStyle(SecondaryButtonStyle())
                .disabled(runner.lines.isEmpty)
                Button("Clear") { runner.clearLog() }.disabled(runner.isRunning)
                    .buttonStyle(SecondaryButtonStyle())
                    .disabled(runner.lines.isEmpty)
            }
            if runner.isRunning { StatusDot(ok: true, text: "Game running") }
            ForEach(runner.hints) { h in
                HStack(alignment: .top, spacing: 10) {
                    Image(systemName: "lightbulb.fill").foregroundStyle(Theme.sand)
                    VStack(alignment: .leading, spacing: 2) { Text(h.title).fontWeight(.semibold); Text(h.advice).font(.callout).foregroundStyle(.secondary) }
                }
                .padding(10).frame(maxWidth: .infinity, alignment: .leading)
                .surface(radius: 10, fill: Theme.sand.opacity(0.13))
            }
            if runner.lines.isEmpty {
                ContentUnavailable(title: "Nothing yet", detail: "Output from the game appears here while it runs.")
                    .surface(radius: 12, fill: Theme.cardHigh)
            } else if shown.isEmpty {
                ContentUnavailable(title: "No matching lines", detail: "Try a different filter.")
                    .surface(radius: 12, fill: Theme.cardHigh)
            } else {
                ScrollViewReader { proxy in
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 1) {
                            ForEach(shown, id: \.0) { i, line in
                                Text(line).font(.system(size: 11.5, design: .monospaced)).foregroundStyle(color(for: line)).textSelection(.enabled)
                                    .frame(maxWidth: .infinity, alignment: .leading).id(i)
                            }
                        }
                        .padding(10)
                    }
                    .frame(height: 350)
                    .surface(radius: 12, fill: Theme.cardHigh)
                    .onChange(of: runner.lines.count) { _, _ in
                        if follow, let last = shown.last { proxy.scrollTo(last.0, anchor: .bottom) }
                    }
                }
            }
        }
    }

    private func color(for line: String) -> Color {
        if line.contains("Exception") || line.contains("Error") || line.hasPrefix("\tat ") { return Theme.bad }
        if line.contains("WARN") { return .orange }
        if line.hasPrefix("[betavk]") || line.hasPrefix("[bench]") { return Theme.tintDeep }
        return .primary.opacity(0.88)
    }
}
