import AppKit
import SwiftUI

enum Theme {
    private static func dyn(_ light: NSColor, _ dark: NSColor) -> Color {
        Color(nsColor: NSColor(name: nil) { $0.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua ? dark : light })
    }

    static let bg = dyn(NSColor(white: 0.96, alpha: 1), NSColor(white: 0.08, alpha: 1))
    static let sidebar = dyn(NSColor(white: 0.99, alpha: 1), NSColor(white: 0.115, alpha: 1))
    static let card = dyn(.white, NSColor(white: 0.125, alpha: 1))
    static let cardHigh = dyn(NSColor(white: 0.94, alpha: 1), NSColor(white: 0.16, alpha: 1))
    static let stroke = dyn(NSColor(white: 0, alpha: 0.07), NSColor(white: 1, alpha: 0.07))
    static let text = Color.primary
    static let dim = Color.secondary

    static let tint = Color(red: 0.09, green: 0.70, blue: 0.66)        // glass teal
    static let tintDeep = Color(red: 0.10, green: 0.46, blue: 0.84)    // glass blue
    static let sand = Color(red: 0.95, green: 0.74, blue: 0.38)
    static let good = Color(red: 0.26, green: 0.78, blue: 0.52)
    static let bad = Color(red: 0.95, green: 0.34, blue: 0.32)

    static let accent = dyn(NSColor(red: 0.20, green: 0.34, blue: 0.16, alpha: 1),
                            NSColor(red: 0.77, green: 0.88, blue: 0.63, alpha: 1))
    static let onAccent = dyn(.white, NSColor(red: 0.10, green: 0.14, blue: 0.09, alpha: 1))
}

enum LauncherMotion {
    static let change = Animation.easeOut(duration: 0.18)
    static let press = Animation.easeOut(duration: 0.12)
    static let page = AnyTransition.asymmetric(
        insertion: .opacity.combined(with: .offset(y: 5)),
        removal: .identity
    )
}

extension View {
    /// The standard surface: a rounded card with a hairline edge.
    func surface(radius: CGFloat = 10, fill: Color = Theme.card) -> some View {
        background(fill, in: RoundedRectangle(cornerRadius: radius, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: radius, style: .continuous).strokeBorder(Theme.stroke, lineWidth: 1))
    }
}

struct PillControlLabel: View {
    let title: String
    let symbol: String
    var selected = false

    var body: some View {
        Label(title, systemImage: symbol)
            .font(.system(size: 14, weight: selected ? .semibold : .medium))
            .foregroundStyle(selected ? Color.primary : Color.secondary)
            .frame(height: 43)
            .contentShape(Rectangle())
    }
}

struct PrimaryButtonStyle: ButtonStyle {
    var large = false
    @Environment(\.isEnabled) private var enabled
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Local private var hover = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: large ? 17 : 14, weight: .semibold, design: .default))
            .foregroundStyle(Theme.onAccent)
            .padding(.horizontal, large ? 20 : 14).padding(.vertical, large ? 11 : 8)
            .background(Theme.accent.opacity(hover || configuration.isPressed ? 0.84 : 1),
                        in: RoundedRectangle(cornerRadius: 7))
            .opacity(enabled ? 1 : 0.45)
            .scaleEffect(!reduceMotion && configuration.isPressed ? 0.98 : 1)
            .animation(reduceMotion ? nil : LauncherMotion.press, value: configuration.isPressed)
            .animation(reduceMotion ? nil : LauncherMotion.press, value: hover)
            .onHover { hover = $0 }
    }
}

/// A quiet button for everything else.
struct SecondaryButtonStyle: ButtonStyle {
    var tint: Color = .primary
    @Environment(\.isEnabled) private var enabled
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Local private var hover = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 13, weight: .semibold))
            .foregroundStyle(tint)
            .padding(.horizontal, 16).padding(.vertical, 8)
            .background(tint.opacity(hover || configuration.isPressed ? 0.16 : 0.09), in: RoundedRectangle(cornerRadius: 7))
            .opacity(enabled ? 1 : 0.45)
            .scaleEffect(!reduceMotion && configuration.isPressed ? 0.98 : 1)
            .animation(reduceMotion ? nil : LauncherMotion.press, value: configuration.isPressed)
            .animation(reduceMotion ? nil : LauncherMotion.press, value: hover)
            .onHover { hover = $0 }
    }
}

struct Segmented<T: Hashable>: View {
    let options: [T]
    @Binding var selection: T
    let title: (T) -> String
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Namespace private var highlight

    var body: some View {
        HStack(spacing: 2) {
            ForEach(options, id: \.self) { o in
                Button {
                    selection = o
                } label: {
                    Text(title(o)).font(.system(size: 12.5, weight: .semibold))
                        .foregroundStyle(selection == o ? Color.primary : Color.secondary)
                        .padding(.horizontal, 14).padding(.vertical, 7)
                        .background {
                            if selection == o {
                                RoundedRectangle(cornerRadius: 5)
                                    .fill(Color.primary.opacity(0.12))
                                    .matchedGeometryEffect(id: "selection", in: highlight)
                            }
                        }
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(selection == o ? .isSelected : [])
            }
        }
        .padding(3).background(Color.primary.opacity(0.07), in: RoundedRectangle(cornerRadius: 7))
        .animation(reduceMotion ? nil : LauncherMotion.change, value: selection)
    }
}

struct PageHeader: View {
    let title: String
    let subtitle: String
    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.system(size: 30, weight: .bold, design: .default))
            Text(subtitle).font(.system(size: 14)).foregroundStyle(.secondary)
        }
    }
}

struct Card<Content: View>: View {
    let title: String
    var subtitle: String? = nil
    @ViewBuilder var content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(.system(size: 15, weight: .semibold, design: .default))
                if let subtitle { Text(subtitle).font(.system(size: 12)).foregroundStyle(.secondary) }
            }
            content
        }
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .surface()
    }
}

struct StatusDot: View {
    let ok: Bool
    let text: String
    var body: some View {
        HStack(spacing: 6) {
            Circle().fill(ok ? Theme.good : Theme.sand).frame(width: 8, height: 8)
            Text(text).font(.caption).foregroundStyle(.secondary)
        }
    }
}

/// ImageRenderer (used by --snapshots) draws nothing for a ScrollView, so snapshots use a plain container.
struct ScrollOrPlain<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        if CommandLine.arguments.contains("--snapshots") {
            GeometryReader { geometry in
                content
                    .frame(width: geometry.size.width)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(height: geometry.size.height, alignment: .top)
                    .clipped()
            }
        } else {
            ScrollView { content }.scrollIndicators(.never)
        }
    }
}

struct ContentUnavailable: View {
    let title: String
    let detail: String
    var body: some View {
        VStack(spacing: 6) {
            Image(systemName: "tray").font(.system(size: 30)).foregroundStyle(.secondary)
            Text(title).font(.system(size: 15, weight: .semibold, design: .default))
            Text(detail).font(.system(size: 12.5)).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity).padding(.vertical, 40)
    }
}
