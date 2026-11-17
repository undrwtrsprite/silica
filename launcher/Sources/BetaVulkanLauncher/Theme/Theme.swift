import AppKit
import SwiftUI

enum Theme {
    static var appMark: NSImage {
        if let url = Bundle.main.url(forResource: "SilicaMark", withExtension: "png"),
           let image = NSImage(contentsOf: url) {
            return image
        }
        return NSApplication.shared.applicationIconImage
    }

    private static func dyn(_ light: NSColor, _ dark: NSColor) -> Color {
        Color(nsColor: NSColor(name: nil) { $0.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua ? dark : light })
    }

    static let bg = dyn(NSColor(white: 0.96, alpha: 1), NSColor(red: 0.039, green: 0.051, blue: 0.067, alpha: 1))       // #0a0d11
    static let sidebar = dyn(NSColor(white: 0.99, alpha: 1), NSColor(red: 0.051, green: 0.067, blue: 0.086, alpha: 1))  // #0d1116
    static let card = dyn(.white, NSColor(red: 0.067, green: 0.082, blue: 0.102, alpha: 1))                             // ~rgba(255,255,255,.035) over bg
    static let cardHigh = dyn(NSColor(white: 0.94, alpha: 1), NSColor(red: 0.094, green: 0.114, blue: 0.137, alpha: 1)) // ~rgba(255,255,255,.065) over bg
    static let stroke = dyn(NSColor(white: 0, alpha: 0.08), NSColor(white: 1, alpha: 0.08))
    static let text = dyn(NSColor(white: 0.08, alpha: 1), NSColor(red: 0.933, green: 0.945, blue: 0.957, alpha: 1))     // #eef1f4
    static let dim = dyn(NSColor(white: 0.42, alpha: 1), NSColor(red: 0.604, green: 0.647, blue: 0.690, alpha: 1))      // #9aa5b0

    static let tint = Color(red: 0.310, green: 0.561, blue: 0.839)      // #4f8fd6 (water blue)
    static let tintDeep = Color(red: 0.24, green: 0.45, blue: 0.72)
    static let sand = Color(red: 0.886, green: 0.663, blue: 0.294)      // #e2a94b (amber)
    static let good = Color(red: 0.482, green: 0.788, blue: 0.435)      // #7bc96f (accent)
    static let bad = Color(red: 0.95, green: 0.34, blue: 0.32)

    static let accent = dyn(NSColor(red: 0.17, green: 0.49, blue: 0.19, alpha: 1),
                            NSColor(red: 0.482, green: 0.788, blue: 0.435, alpha: 1))                                    // #7bc96f
    static let onAccent = dyn(.white, NSColor(red: 0.043, green: 0.090, blue: 0.027, alpha: 1))                          // #0b1707
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
    /// The standard surface: a rounded card with a hairline edge matching website `--r-card: 18px`.
    func surface(radius: CGFloat = 18, fill: Color = Theme.card) -> some View {
        background(fill, in: RoundedRectangle(cornerRadius: radius, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: radius, style: .continuous).strokeBorder(Theme.stroke, lineWidth: 1))
    }
}

/// Website-matching three-square green eyebrow kicker.
struct Eyebrow: View {
    let text: String
    var uppercase = false
    var body: some View {
        HStack(spacing: 10) {
            HStack(spacing: 3) {
                Rectangle().fill(Theme.accent).frame(width: 7, height: 7)
                Rectangle().fill(Theme.accent.opacity(0.45)).frame(width: 7, height: 7)
                Rectangle().fill(Theme.accent.opacity(0.18)).frame(width: 7, height: 7)
            }
            Text(uppercase ? text.uppercased() : text)
                .font(.system(size: uppercase ? 10.5 : 13, weight: .semibold, design: uppercase ? .monospaced : .default))
                .tracking(uppercase ? 0.8 : 0)
                .foregroundStyle(Theme.accent)
        }
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
            .padding(.horizontal, large ? 24 : 16).padding(.vertical, large ? 13 : 9)
            .background {
                RoundedRectangle(cornerRadius: large ? 12 : 10, style: .continuous)
                    .fill(Theme.accent.opacity(hover || configuration.isPressed ? 0.88 : 1))
                    .overlay(alignment: .bottom) {
                        RoundedRectangle(cornerRadius: large ? 12 : 10, style: .continuous)
                            .fill(Color.black.opacity(0.16))
                            .frame(height: 3)
                    }
                    .clipShape(RoundedRectangle(cornerRadius: large ? 12 : 10, style: .continuous))
            }
            .shadow(color: hover ? Theme.accent.opacity(0.38) : .clear, radius: 14, y: 5)
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
            .background(tint.opacity(hover || configuration.isPressed ? 0.14 : 0.075), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).strokeBorder(Theme.stroke, lineWidth: 1))
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
        VStack(alignment: .leading, spacing: 6) {
            Eyebrow(text: title)
            Text(title).font(.system(size: 32, weight: .bold, design: .default)).tracking(-0.8)
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
