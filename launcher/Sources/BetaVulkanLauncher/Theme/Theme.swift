import AppKit
import SwiftUI

enum Theme {
    /// Resolves SilicaMark.png from the app bundle, repo Icon folder (dev builds), or draws a fallback voxel mark.
    static let appMark: NSImage = {
        let candidates: [URL?] = [
            Bundle.main.url(forResource: "SilicaMark", withExtension: "png"),
            Bundle.main.resourceURL?.appendingPathComponent("SilicaMark.png"),
            URL(fileURLWithPath: FileManager.default.currentDirectoryPath).appendingPathComponent("launcher/Icon/SilicaMark.png"),
            URL(fileURLWithPath: FileManager.default.currentDirectoryPath).appendingPathComponent("Icon/SilicaMark.png"),
            URL(fileURLWithPath: Bundle.main.executablePath ?? "").deletingLastPathComponent()
                .appendingPathComponent("../../../Icon/SilicaMark.png").standardizedFileURL,
        ]
        for url in candidates.compactMap({ $0 }) {
            if FileManager.default.fileExists(atPath: url.path), let image = NSImage(contentsOf: url) {
                return image
            }
        }
        return makeFallbackMark()
    }()

    private static func makeFallbackMark() -> NSImage {
        let size = NSSize(width: 64, height: 64)
        let img = NSImage(size: size)
        img.lockFocus()
        let rect = NSRect(origin: .zero, size: size)
        let bg = NSBezierPath(roundedRect: rect, xRadius: 14, yRadius: 14)
        NSColor(red: 0.08, green: 0.11, blue: 0.15, alpha: 1).setFill()
        bg.fill()
        NSColor(red: 0.482, green: 0.788, blue: 0.435, alpha: 1).setFill()
        NSRect(x: 16, y: 34, width: 32, height: 14).fill()
        NSColor(red: 0.310, green: 0.561, blue: 0.839, alpha: 1).setFill()
        NSRect(x: 16, y: 16, width: 32, height: 14).fill()
        img.unlockFocus()
        return img
    }

    private static func dyn(_ light: NSColor, _ dark: NSColor) -> Color {
        Color(nsColor: NSColor(name: nil) { $0.bestMatch(from: [.darkAqua, .aqua]) == .darkAqua ? dark : light })
    }

    // Website palette (silicalauncher.xyz) lifted one step lighter & calmer for desktop utility clarity
    static let bg = dyn(NSColor(red: 0.953, green: 0.961, blue: 0.969, alpha: 1),
                        NSColor(red: 0.067, green: 0.082, blue: 0.106, alpha: 1))       // #11151b
    static let bar = dyn(NSColor(white: 1, alpha: 0.88),
                         NSColor(red: 0.082, green: 0.102, blue: 0.129, alpha: 0.88))    // #151a21
    static let card = dyn(.white,
                          NSColor(red: 0.098, green: 0.122, blue: 0.153, alpha: 0.94))   // #191f27
    static let cardHigh = dyn(NSColor(red: 0.929, green: 0.941, blue: 0.953, alpha: 1),
                              NSColor(red: 0.129, green: 0.157, blue: 0.196, alpha: 1))  // #212832
    static let stroke = dyn(NSColor(white: 0, alpha: 0.08), NSColor(white: 1, alpha: 0.085))
    static let text = dyn(NSColor(white: 0.08, alpha: 1), NSColor(red: 0.933, green: 0.945, blue: 0.957, alpha: 1))     // #eef1f4
    static let dim = dyn(NSColor(white: 0.42, alpha: 1), NSColor(red: 0.604, green: 0.647, blue: 0.690, alpha: 1))      // #9aa5b0

    static let tint = Color(red: 0.310, green: 0.561, blue: 0.839)      // #4f8fd6
    static let tintDeep = Color(red: 0.24, green: 0.45, blue: 0.72)
    static let sand = Color(red: 0.886, green: 0.663, blue: 0.294)      // #e2a94b
    static let good = Color(red: 0.482, green: 0.788, blue: 0.435)      // #7bc96f
    static let bad = Color(red: 0.95, green: 0.34, blue: 0.32)

    static let accent = dyn(NSColor(red: 0.17, green: 0.49, blue: 0.19, alpha: 1),
                            NSColor(red: 0.482, green: 0.788, blue: 0.435, alpha: 1))    // #7bc96f
    static let onAccent = dyn(.white, NSColor(red: 0.043, green: 0.090, blue: 0.027, alpha: 1)) // #0b1707
}

enum LauncherMotion {
    static let change = Animation.easeOut(duration: 0.18)
    static let press = Animation.easeOut(duration: 0.12)
    static let page = AnyTransition.asymmetric(
        insertion: .opacity.combined(with: .offset(y: 4)),
        removal: .identity
    )
}

extension View {
    /// Clean website-style lifted card (--r-card ~ 16px, hairline 1px stroke).
    func surface(radius: CGFloat = 16, fill: Color = Theme.card, selected: Bool = false) -> some View {
        background(fill, in: RoundedRectangle(cornerRadius: radius, style: .continuous))
            .overlay(
                RoundedRectangle(cornerRadius: radius, style: .continuous)
                    .strokeBorder(selected ? Theme.accent.opacity(0.6) : Theme.stroke, lineWidth: selected ? 1.5 : 1)
            )
    }
}

/// Website-matching three-square green eyebrow kicker (used sparingly on top-level page headers).
struct Eyebrow: View {
    let text: String
    var body: some View {
        HStack(spacing: 10) {
            HStack(spacing: 3) {
                Rectangle().fill(Theme.accent).frame(width: 6, height: 6)
                Rectangle().fill(Theme.accent.opacity(0.45)).frame(width: 6, height: 6)
                Rectangle().fill(Theme.accent.opacity(0.18)).frame(width: 6, height: 6)
            }
            Text(text)
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(Theme.accent)
        }
    }
}

/// Minimal pill switch matching website `.toggle .sw` cleanly in both live macOS UI and ImageRenderer snapshots.
struct SilicaToggleStyle: ToggleStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 12) {
            configuration.label
            Spacer(minLength: 8)
            Button {
                configuration.isOn.toggle()
            } label: {
                ZStack(alignment: configuration.isOn ? .trailing : .leading) {
                    Capsule()
                        .fill(configuration.isOn ? Theme.accent : Color.primary.opacity(0.14))
                        .frame(width: 38, height: 22)
                    Circle()
                        .fill(.white)
                        .frame(width: 16, height: 16)
                        .padding(3)
                }
                .animation(LauncherMotion.change, value: configuration.isOn)
            }
            .buttonStyle(.plain)
            .accessibilityAddTraits(.isButton)
        }
    }
}

/// Website primary green button (`.btn.primary`).
struct PrimaryButtonStyle: ButtonStyle {
    var large = false
    @Environment(\.isEnabled) private var enabled
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Local private var hover = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: large ? 16 : 13.5, weight: .semibold))
            .foregroundStyle(Theme.onAccent)
            .padding(.horizontal, large ? 22 : 15)
            .padding(.vertical, large ? 11 : 8)
            .background {
                RoundedRectangle(cornerRadius: large ? 11 : 9, style: .continuous)
                    .fill(Theme.accent.opacity(hover || configuration.isPressed ? 0.88 : 1))
                    .overlay(alignment: .bottom) {
                        RoundedRectangle(cornerRadius: large ? 11 : 9, style: .continuous)
                            .fill(Color.black.opacity(0.16))
                            .frame(height: 2.5)
                    }
                    .clipShape(RoundedRectangle(cornerRadius: large ? 11 : 9, style: .continuous))
            }
            .shadow(color: hover && enabled ? Theme.accent.opacity(0.32) : .clear, radius: 12, y: 4)
            .opacity(enabled ? 1 : 0.45)
            .scaleEffect(!reduceMotion && configuration.isPressed ? 0.985 : 1)
            .animation(reduceMotion ? nil : LauncherMotion.press, value: configuration.isPressed)
            .animation(reduceMotion ? nil : LauncherMotion.press, value: hover)
            .onHover { hover = $0 }
    }
}

/// Quiet secondary button (`.btn.secondary`).
struct SecondaryButtonStyle: ButtonStyle {
    var tint: Color = .primary
    @Environment(\.isEnabled) private var enabled
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Local private var hover = false

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.system(size: 13, weight: .medium))
            .foregroundStyle(tint)
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(tint.opacity(hover || configuration.isPressed ? 0.12 : 0.065),
                        in: RoundedRectangle(cornerRadius: 9, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 9, style: .continuous).strokeBorder(Theme.stroke, lineWidth: 1))
            .opacity(enabled ? 1 : 0.45)
            .scaleEffect(!reduceMotion && configuration.isPressed ? 0.985 : 1)
            .animation(reduceMotion ? nil : LauncherMotion.press, value: configuration.isPressed)
            .animation(reduceMotion ? nil : LauncherMotion.press, value: hover)
            .onHover { hover = $0 }
    }
}

/// Website `.seg` control.
struct Segmented<T: Hashable>: View {
    let options: [T]
    @Binding var selection: T
    var expanded = false
    let title: (T) -> String
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Namespace private var highlight

    var body: some View {
        HStack(spacing: 2) {
            ForEach(options, id: \.self) { option in
                let on = selection == option
                Button {
                    selection = option
                } label: {
                    Text(title(option))
                        .font(.system(size: 12.5, weight: on ? .semibold : .medium))
                        .foregroundStyle(on ? Theme.bg : Theme.dim)
                        .frame(maxWidth: expanded ? .infinity : nil)
                        .padding(.horizontal, 12)
                        .padding(.vertical, 6)
                        .background {
                            if on {
                                RoundedRectangle(cornerRadius: 7, style: .continuous)
                                    .fill(Theme.text)
                                    .matchedGeometryEffect(id: "seg", in: highlight)
                            }
                        }
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
        .padding(3)
        .frame(maxWidth: expanded ? .infinity : nil)
        .background(Color.primary.opacity(0.06), in: RoundedRectangle(cornerRadius: 10, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 10, style: .continuous).strokeBorder(Theme.stroke, lineWidth: 1))
        .animation(reduceMotion ? nil : LauncherMotion.change, value: selection)
    }
}

struct PageHeader: View {
    let eyebrow: String
    let title: String
    let subtitle: String

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            Eyebrow(text: eyebrow)
            Text(title)
                .font(.system(size: 28, weight: .bold))
                .tracking(-0.8)
            Text(subtitle)
                .font(.system(size: 13.5))
                .foregroundStyle(.secondary)
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
                Text(title).font(.system(size: 15, weight: .semibold))
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
    let color: Color
    let text: String

    init(ok: Bool, text: String) {
        self.color = ok ? Theme.good : Theme.sand
        self.text = text
    }

    init(color: Color, text: String) {
        self.color = color
        self.text = text
    }

    var body: some View {
        HStack(spacing: 6) {
            Circle().fill(color).frame(width: 7, height: 7)
            Text(text).font(.system(size: 12, weight: .medium)).foregroundStyle(.secondary)
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
    var symbol = "square.grid.2x2"

    var body: some View {
        VStack(spacing: 6) {
            Image(systemName: symbol).font(.system(size: 26)).foregroundStyle(.secondary)
            Text(title).font(.system(size: 14.5, weight: .semibold))
            Text(detail).font(.system(size: 12.5)).foregroundStyle(.secondary).multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 36)
    }
}

/// Renders lightweight GitHub release markdown (headings, bullet lists, inline bold/italic/code/links).
struct MarkdownView: View {
    let text: String

    private enum LineKind {
        case spacer
        case heading(Int, String)
        case bullet(String)
        case paragraph(String)
    }

    private var lines: [LineKind] {
        text.replacingOccurrences(of: "\r\n", with: "\n")
            .components(separatedBy: "\n")
            .map { raw in
                let trimmed = raw.trimmingCharacters(in: .whitespaces)
                if trimmed.isEmpty { return .spacer }
                if trimmed.hasPrefix("### ") { return .heading(3, String(trimmed.dropFirst(4))) }
                if trimmed.hasPrefix("## ") { return .heading(2, String(trimmed.dropFirst(3))) }
                if trimmed.hasPrefix("# ") { return .heading(1, String(trimmed.dropFirst(2))) }
                if trimmed.hasPrefix("- ") || trimmed.hasPrefix("* ") || trimmed.hasPrefix("• ") {
                    return .bullet(String(trimmed.dropFirst(2)))
                }
                return .paragraph(trimmed)
            }
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 5) {
            ForEach(Array(lines.enumerated()), id: \.offset) { _, kind in
                switch kind {
                case .spacer:
                    Spacer().frame(height: 3)
                case .heading(let level, let raw):
                    inlineText(raw)
                        .font(.system(size: level == 1 ? 14 : 13, weight: .semibold))
                        .foregroundStyle(Theme.text)
                        .padding(.top, 2)
                case .bullet(let raw):
                    HStack(alignment: .firstTextBaseline, spacing: 8) {
                        Circle()
                            .fill(Theme.accent.opacity(0.8))
                            .frame(width: 5, height: 5)
                            .offset(y: -1.5)
                        inlineText(raw)
                            .font(.system(size: 12))
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                case .paragraph(let raw):
                    inlineText(raw)
                        .font(.system(size: 12))
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
        .textSelection(.enabled)
    }

    private func inlineText(_ raw: String) -> Text {
        guard var attributed = try? AttributedString(
            markdown: raw,
            options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace)
        ) else {
            return Text(raw)
        }
        for run in attributed.runs {
            if let intent = run.inlinePresentationIntent {
                if intent.contains(.code) {
                    attributed[run.range].font = .system(size: 11.5, design: .monospaced)
                    attributed[run.range].foregroundColor = Theme.text
                }
                if intent.contains(.stronglyEmphasized) {
                    attributed[run.range].foregroundColor = Theme.text
                }
            }
        }
        return Text(attributed)
    }
}
