import SwiftUI

struct BenchmarkView: View {
    @EnvironmentObject private var state: AppState
    @Local private var baselineName = ""
    @Local private var comparisonName = ""
    @Local private var selectedDistance: Int? = nil
    @Local private var openSelector: String? = nil
    @Local private var comparisonMode: Bool? = nil
    @Environment(\.colorScheme) private var colorScheme

    private var comparing: Bool {
        comparisonMode ?? (state.benchResultNames.isEmpty
            ? !openGLRuns.isEmpty && !vulkanRuns.isEmpty : state.benchResultNames.count == 2)
    }

    private var openGLRuns: [BenchRun] { state.benchRuns.filter { backendName($0) == "OpenGL" } }
    private var vulkanRuns: [BenchRun] { state.benchRuns.filter { backendName($0) == "Vulkan" } }

    private var baseline: BenchRun? {
        let runs = comparing ? openGLRuns : state.benchRuns
        return runs.first { $0.name == baselineName }
            ?? runs.first { state.benchResultNames.contains($0.name) } ?? runs.first
    }

    private var comparison: BenchRun? {
        guard comparing else { return nil }
        return vulkanRuns.first { $0.name == comparisonName }
            ?? vulkanRuns.first { state.benchResultNames.contains($0.name) } ?? vulkanRuns.first
    }

    private var distances: [Int] {
        Set((baseline?.results ?? []).map(\.distance) + (comparison?.results ?? []).map(\.distance)).sorted()
    }

    private struct Metric {
        let title: String
        let key: KeyPath<BenchResult, Double>
        let higherIsBetter: Bool
        let digits: Int
    }

    private let metrics = [
        Metric(title: "Average FPS", key: \.avgFPS, higherIsBetter: true, digits: 1),
        Metric(title: "1% low FPS", key: \.lowFPS, higherIsBetter: true, digits: 1),
        Metric(title: "99th percentile frame · ms", key: \.p99ms, higherIsBetter: false, digits: 2),
        Metric(title: "Longest frame · ms", key: \.maxMs, higherIsBetter: false, digits: 1),
        Metric(title: "Chunk rebuild · ms", key: \.rebuildMs, higherIsBetter: false, digits: 3),
    ]

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            runCard
            if baseline != nil {
                resultsCard
            } else {
                ContentUnavailable(title: "No benchmark results", detail: "Run a benchmark to see its results here.", symbol: "speedometer")
                    .surface()
            }
        }
        .onAppear { state.benchRuns = BenchmarkStore.runs() }
        .onChange(of: state.benchResultNames) { _, _ in
            baselineName = ""
            comparisonName = ""
            selectedDistance = nil
            comparisonMode = nil
        }
    }

    private var runCard: some View {
        Card(title: "Run a benchmark", subtitle: "Fixed world and camera path · Three render distances · Fullscreen") {
            HStack(spacing: 10) {
                Button {
                    state.startBenchmark([state.activeSettings.backend])
                } label: {
                    Label("Run " + (state.activeSettings.backend == .vulkan ? "Vulkan" : "OpenGL"), systemImage: "speedometer")
                }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(!state.canPlay || state.benchBusy)

                Button {
                    state.startBenchmark([.gl, .vulkan])
                } label: {
                    Label("Compare OpenGL and Vulkan", systemImage: "arrow.left.arrow.right")
                }
                .buttonStyle(SecondaryButtonStyle())
                .disabled(!state.canPlay || state.benchBusy)

                Spacer(minLength: 0)
                if state.benchBusy {
                    Button { state.stopBenchmark() } label: {
                        Label(state.benchCountdown == nil ? "Stop" : "Cancel", systemImage: "stop.fill")
                    }
                    .buttonStyle(SecondaryButtonStyle(tint: Theme.bad))
                    .keyboardShortcut(.cancelAction)
                }
            }

            if state.player == nil && !state.benchBusy {
                Button("Sign in on the Play page to run a benchmark") { state.tab = .play }
                    .buttonStyle(SecondaryButtonStyle(tint: Theme.accent))
            }

            if let seconds = state.benchCountdown {
                HStack(spacing: 12) {
                    Image(systemName: "timer").font(.title2).foregroundStyle(Theme.tintDeep)
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Starting in \(seconds) s").font(.callout.weight(.semibold)).monospacedDigit()
                        Text("The game will open fullscreen. Cancel to stay in the launcher.")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                    Spacer()
                }
                .padding(12)
                .surface(radius: 12, fill: Theme.cardHigh)
                .accessibilityElement(children: .combine)
            } else if state.benchBusy {
                VStack(alignment: .leading, spacing: 6) {
                    HStack {
                        Text(state.benchDetail)
                        Spacer()
                        Text(state.benchProgress, format: .percent.precision(.fractionLength(0))).monospacedDigit()
                    }
                    .font(.callout.weight(.medium))
                    ProgressView(value: state.benchProgress).tint(Theme.accent)
                    Text(state.benchStage).font(.caption).foregroundStyle(.secondary)
                }
                .padding(12)
                .surface(radius: 12, fill: Theme.cardHigh)
            }

            if let status = state.benchStatus, !status.isEmpty {
                Text(status).font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    private var resultsCard: some View {
        let shared = Set((baseline?.results ?? []).map(\.distance)).intersection((comparison?.results ?? []).map(\.distance))
        let distance = selectedDistance.flatMap { distances.contains($0) ? $0 : nil } ?? shared.min() ?? distances.first ?? 0
        let before = baseline?.results.first { $0.distance == distance } ?? (shared.isEmpty ? baseline?.results.first : nil)
        let after = comparison?.results.first { $0.distance == distance } ?? (shared.isEmpty ? comparison?.results.first : nil)
        let differentDistances = before != nil && after != nil && before?.distance != after?.distance

        return Card(title: "Results") {
            if !openGLRuns.isEmpty && !vulkanRuns.isEmpty {
                Segmented(options: [false, true], selection: Binding(get: { comparing }, set: {
                    comparisonMode = $0
                    baselineName = ""
                    comparisonName = ""
                    selectedDistance = nil
                })) { $0 ? "Compare backends" : "Single run" }
            }

            HStack(spacing: 10) {
                runSelector(comparing ? "OpenGL run" : "Benchmark run", run: baseline, tint: Theme.tint,
                            selection: $baselineName, runs: comparing ? openGLRuns : state.benchRuns)
                if comparing {
                    runSelector("Vulkan run", run: comparison, tint: Theme.accent, selection: $comparisonName, runs: vulkanRuns)
                }
            }

            if differentDistances && distances.count == 2 {
                Text("These runs used different render distances. Percentage changes require matching distances.")
                    .font(.caption).foregroundStyle(.secondary)
            } else {
                HStack {
                    Text("Render distance").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                    Spacer()
                    Segmented(options: distances, selection: Binding(get: { distance }, set: { selectedDistance = $0 })) {
                        BenchResult.distanceNames[$0] ?? "Distance \($0)"
                    }
                }
                .padding(.vertical, 3)
            }

            VStack(spacing: 0) {
                metricRow {
                    Text("Metric").frame(maxWidth: .infinity, alignment: .leading)
                    resultHeading(comparing ? "OpenGL" : baseline.map(backendName) ?? "Result", result: before, distance: distance)
                    if comparing {
                        resultHeading("Vulkan", result: after, distance: distance)
                        changeColumn("Vulkan vs GL")
                    }
                }
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
                .padding(.vertical, 7)

                ForEach(metrics.indices, id: \.self) { index in
                    let metric = metrics[index]
                    let first = before?[keyPath: metric.key]
                    let second = after?[keyPath: metric.key]
                    Divider()
                    metricRow {
                        Text(metric.title).frame(maxWidth: .infinity, alignment: .leading)
                        valueColumn(formatted(first, metric: metric))
                        if comparing {
                            valueColumn(formatted(second, metric: metric))
                            change(differentDistances ? nil : first, second, metric: metric)
                        }
                    }
                    .font(.system(size: 13, weight: index == 0 ? .semibold : .regular))
                    .padding(.vertical, 9)
                }
            }
            .padding(.horizontal, 12)
            .surface(radius: 12, fill: Theme.cardHigh)
        }
    }

    private func runSelector(_ title: String, run: BenchRun?, tint: Color, selection: Binding<String>, runs: [BenchRun]) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 6) {
                Circle().fill(tint).frame(width: 7, height: 7)
                Text(title).font(.caption.weight(.semibold)).foregroundStyle(.secondary)
            }
            Button { openSelector = title } label: {
                HStack(spacing: 8) {
                    Text(run.map(backendName) ?? "Select a run").font(.system(size: 16, weight: .semibold)).lineLimit(1)
                    Image(systemName: "chevron.down").font(.caption.weight(.semibold)).foregroundStyle(.secondary)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("\(title) run, \(run.map(backendName) ?? "select a run")")
            .popover(isPresented: Binding(get: { openSelector == title }, set: { if !$0 { openSelector = nil } }), arrowEdge: .bottom) {
                ScrollView {
                    VStack(alignment: .leading, spacing: 5) {
                        ForEach(runs) { option in
                            Button {
                                selection.wrappedValue = option.name
                                selectedDistance = nil
                                openSelector = nil
                            } label: {
                                Label(runTitle(option), systemImage: option.id == run?.id ? "checkmark.circle.fill" : "circle")
                                    .frame(maxWidth: .infinity, alignment: .leading)
                            }
                            .buttonStyle(SecondaryButtonStyle())
                        }
                    }
                    .padding(10)
                }
                .frame(width: 310, height: min(CGFloat(runs.count) * 38 + 20, 280))
            }
            if let run {
                Text(run.date.formatted(date: .abbreviated, time: .shortened)).font(.caption).foregroundStyle(.secondary)
                if let result = run.results.first, !result.renderer.isEmpty {
                    Text(result.renderer).font(.caption).foregroundStyle(.secondary).lineLimit(1).help(result.renderer)
                }
            } else {
                Text("Run another benchmark to compare.").font(.caption).foregroundStyle(.secondary)
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, minHeight: 100, alignment: .topLeading)
        .surface(radius: 12, fill: Theme.cardHigh)
    }

    private func runTitle(_ run: BenchRun) -> String {
        "\(backendName(run)) · \(run.date.formatted(date: .abbreviated, time: .standard))"
    }

    private func backendName(_ run: BenchRun) -> String {
        if run.name.hasSuffix("-vulkan") { return "Vulkan" }
        if run.name.hasSuffix("-gl") || run.name.hasSuffix("-opengl") { return "OpenGL" }
        return run.name
    }

    private func metricRow<Content: View>(@ViewBuilder content: () -> Content) -> some View {
        HStack(spacing: 12, content: content)
    }

    private func valueColumn(_ value: String) -> some View {
        Text(value).monospacedDigit().frame(width: 95, alignment: .trailing)
    }

    private func resultHeading(_ title: String, result: BenchResult?, distance: Int) -> some View {
        VStack(alignment: .trailing, spacing: 3) {
            Text(title).foregroundStyle(.primary)
            Text(result?.distanceName ?? BenchResult.distanceNames[distance] ?? "Distance \(distance)")
        }
        .frame(width: 95, alignment: .trailing)
    }

    private func changeColumn(_ value: String) -> some View {
        Text(value).monospacedDigit().frame(width: 74, alignment: .trailing)
    }

    private func formatted(_ value: Double?, metric: Metric) -> String {
        guard let value else { return "—" }
        return String(format: "%.*f", metric.digits, value)
    }

    private func change(_ baseline: Double?, _ comparison: Double?, metric: Metric) -> some View {
        let percent = baseline.flatMap { first in
            comparison.flatMap { second in first == 0 ? nil : (second / first - 1) * 100 }
        }
        let favorable = percent.map { metric.higherIsBetter ? $0 > 0 : $0 < 0 } ?? false
        let positive = colorScheme == .dark ? Theme.good : Color(red: 0.06, green: 0.44, blue: 0.26)
        let negative = colorScheme == .dark ? Theme.bad : Color(red: 0.69, green: 0.19, blue: 0.18)
        return changeColumn(percent.map { abs($0) < 0.05 ? "0%" : String(format: "%+.1f%%", $0) } ?? "—")
            .foregroundStyle(abs(percent ?? 0) < 0.05 ? Color.secondary : favorable ? positive : negative)
    }
}
