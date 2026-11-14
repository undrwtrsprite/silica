import SwiftUI

/// Benchmark and Logs live here, out of the way of everyday use.
struct AdvancedView: View {
    @EnvironmentObject private var state: AppState
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(alignment: .top) {
                PageHeader(title: "Advanced", subtitle: "Compare game performance and inspect logs.")
                Spacer()
                Segmented(options: AdvancedTab.allCases, selection: $state.advancedTab) { $0.rawValue }
            }
            ZStack(alignment: .topLeading) {
                Group {
                    switch state.advancedTab {
                    case .benchmark: BenchmarkView()
                    case .logs: LogsView()
                    }
                }
                .id(state.advancedTab)
                .transition(reduceMotion ? .identity : LauncherMotion.page)
            }
            .animation(reduceMotion ? nil : LauncherMotion.change, value: state.advancedTab)
        }
    }
}
