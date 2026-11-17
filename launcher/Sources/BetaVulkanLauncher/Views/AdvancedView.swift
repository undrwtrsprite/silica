import SwiftUI

/// Clean container for Benchmark and Logs.
struct AdvancedView: View {
    @EnvironmentObject private var state: AppState
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(alignment: .bottom) {
                PageHeader(
                    eyebrow: "Diagnostics",
                    title: "Advanced",
                    subtitle: "Compare Vulkan against OpenGL and inspect runtime output."
                )
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
