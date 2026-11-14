import SwiftUI

/// An animated, procedurally drawn blocky landscape with parallax. All art is generated here (no game assets). It follows the display
/// refresh rate (TimelineView(.animation) runs at 120 Hz on ProMotion) and stops moving when Reduce Motion is on.
struct HeroBackground: View {
    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    /// Set while the game is running: the animation costs CPU and WindowServer time that the game's windowed presentation needs.
    var paused = false
    @Local private var pointer = CGPoint(x: 0.5, y: 0.5)   // 0...1 across the window

    private struct Layer {
        let block: CGFloat, base: CGFloat, amp: CGFloat, speed: CGFloat, parallax: CGFloat, seed: Double
        let top: Color, body: Color
    }

    private var day: Bool { scheme == .light }

    private var layers: [Layer] {
        if day {
            return [
                Layer(block: 26, base: 0.60, amp: 0.10, speed: 4, parallax: 8, seed: 1, top: Color(red: 0.55, green: 0.78, blue: 0.66), body: Color(red: 0.47, green: 0.66, blue: 0.62)),
                Layer(block: 32, base: 0.70, amp: 0.12, speed: 9, parallax: 18, seed: 7, top: Color(red: 0.36, green: 0.68, blue: 0.36), body: Color(red: 0.42, green: 0.55, blue: 0.30)),
                Layer(block: 44, base: 0.82, amp: 0.10, speed: 16, parallax: 34, seed: 13, top: Color(red: 0.28, green: 0.58, blue: 0.27), body: Color(red: 0.45, green: 0.32, blue: 0.20)),
            ]
        }
        return [
            Layer(block: 26, base: 0.60, amp: 0.10, speed: 4, parallax: 8, seed: 1, top: Color(red: 0.16, green: 0.24, blue: 0.34), body: Color(red: 0.12, green: 0.18, blue: 0.27)),
            Layer(block: 32, base: 0.70, amp: 0.12, speed: 9, parallax: 18, seed: 7, top: Color(red: 0.13, green: 0.28, blue: 0.22), body: Color(red: 0.10, green: 0.18, blue: 0.17)),
            Layer(block: 44, base: 0.82, amp: 0.10, speed: 16, parallax: 34, seed: 13, top: Color(red: 0.10, green: 0.22, blue: 0.15), body: Color(red: 0.16, green: 0.12, blue: 0.09)),
        ]
    }

    /// Deterministic value noise on integers, smoothed: stepped into blocks by the caller.
    private func height(_ i: Int, seed: Double) -> CGFloat {
        let x = Double(i) * 0.21 + seed
        return CGFloat((sin(x) * 0.5 + sin(x * 2.3 + seed) * 0.3 + sin(x * 0.45) * 0.4) / 1.2)
    }

    var body: some View {
        GeometryReader { geo in
        TimelineView(.animation(minimumInterval: nil, paused: reduceMotion || paused || scenePhase != .active)) { timeline in
            Canvas { ctx, size in
                let t = reduceMotion ? 0 : timeline.date.timeIntervalSinceReferenceDate
                // sky
                let sky = day ? [Color(red: 0.55, green: 0.76, blue: 0.97), Color(red: 0.83, green: 0.92, blue: 0.99)]
                              : [Color(red: 0.03, green: 0.05, blue: 0.14), Color(red: 0.12, green: 0.15, blue: 0.30)]
                ctx.fill(Path(CGRect(origin: .zero, size: size)), with: .linearGradient(Gradient(colors: sky), startPoint: .zero, endPoint: CGPoint(x: 0, y: size.height * 0.75)))
                // sun / moon, a square like everything else here
                let sunX = size.width * (0.72 + 0.04 * (pointer.x - 0.5)), sunY = size.height * 0.2
                ctx.fill(Path(CGRect(x: sunX - 34, y: sunY - 34, width: 68, height: 68)), with: .color(day ? Color(red: 1, green: 0.93, blue: 0.62) : Color(white: 0.9)))
                // stars at night
                if !day {
                    for i in 0..<60 {
                        let sx = CGFloat((sin(Double(i) * 12.9898) * 43758.5453).truncatingRemainder(dividingBy: 1)) , sy = CGFloat((sin(Double(i) * 78.233) * 12345.678).truncatingRemainder(dividingBy: 1))
                        let twinkle = reduceMotion ? 1 : 0.55 + 0.45 * sin(t * 1.5 + Double(i))
                        ctx.fill(Path(CGRect(x: abs(sx) * size.width, y: abs(sy) * size.height * 0.5, width: 3, height: 3)), with: .color(.white.opacity(twinkle * 0.8)))
                    }
                }
                // clouds drift
                for c in 0..<6 {
                    let w: CGFloat = 120 + CGFloat(c % 3) * 40, y = size.height * (0.10 + 0.06 * CGFloat(c))
                    let x = (CGFloat(t) * (6 + CGFloat(c) * 2) + CGFloat(c) * 260 + (pointer.x - 0.5) * 24).truncatingRemainder(dividingBy: size.width + 300) - 150
                    ctx.fill(Path(CGRect(x: x, y: y, width: w, height: 26)), with: .color(.white.opacity(day ? 0.85 : 0.10)))
                    ctx.fill(Path(CGRect(x: x + 24, y: y - 16, width: w - 56, height: 18)), with: .color(.white.opacity(day ? 0.85 : 0.10)))
                }
                // landscape layers, far to near, each scrolling and shifting with the pointer
                for l in layers {
                    let shift = CGFloat(t) * l.speed + (pointer.x - 0.5) * l.parallax * 2
                    let cols = Int(size.width / l.block) + 3
                    let first = Int(floor(shift / l.block))
                    for k in 0..<cols {
                        let idx = first + k
                        let h = height(idx, seed: l.seed)
                        let top = size.height * (l.base - l.amp * h) + (pointer.y - 0.5) * l.parallax
                        let steppedTop = (top / l.block).rounded() * l.block
                        let x = CGFloat(idx) * l.block - shift
                        ctx.fill(Path(CGRect(x: x, y: steppedTop, width: l.block + 0.5, height: size.height - steppedTop)), with: .color(l.body))
                        ctx.fill(Path(CGRect(x: x, y: steppedTop, width: l.block + 0.5, height: l.block * 0.35)), with: .color(l.top))
                    }
                }
            }
        }
        .onContinuousHover { phase in
            if case .active(let p) = phase {
                pointer = CGPoint(x: min(max(p.x / max(geo.size.width, 1), 0), 1), y: min(max(p.y / max(geo.size.height, 1), 0), 1))
            }
        }
        }
        .ignoresSafeArea()
        .accessibilityHidden(true)
    }
}
