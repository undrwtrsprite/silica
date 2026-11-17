import SwiftUI

/// The animated scene behind the Play card and the setup screens: a procedurally drawn blocky landscape (all art generated here, nothing from the game)
/// with a day that passes (it starts at the real time of day and a full cycle takes four minutes), a square sun and moon, drifting clouds, stars, a lake that
/// catches the light, trees, fireflies and embers, and depth haze between the layers. It reacts to the pointer with parallax, follows the display's refresh rate,
/// stops when Reduce Motion is on and can be paused (while the game runs).
struct HeroScene: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase
    var paused = false
    var pointer = CGPoint(x: 0.5, y: 0.5)
    /// Snapshots draw one frame at this time (seconds) instead of animating.
    var fixedTime: Double? = CommandLine.arguments.contains("--snapshots") ? 30 : nil
    /// 0 to 1 time of day to start from (0.5 = noon); nil follows the real clock.
    /// SILICA_SCENE_PHASE=0.52 forces the starting time of day (used to take daytime screenshots).
    var startPhase: Double? = ProcessInfo.processInfo.environment["SILICA_SCENE_PHASE"].flatMap(Double.init)

    /// Holds the eased pointer between frames (a class, so the drawing code can update it).
    private final class Eased { var x = 0.5, y = 0.5 }
    @Local private var eased = Eased()

    /// When the app opened: the day starts from the real time of day (or SILICA_SCENE_PHASE) and moves on from there.
    private static let opened = Date.timeIntervalSinceReferenceDate

    private struct RGB {
        var r: Double, g: Double, b: Double
        init(_ r: Double, _ g: Double, _ b: Double) { self.r = r; self.g = g; self.b = b }
        func mix(_ o: RGB, _ t: Double) -> RGB { RGB(r + (o.r - r) * t, g + (o.g - g) * t, b + (o.b - b) * t) }
        func scaled(_ k: Double) -> RGB { RGB(min(1, r * k), min(1, g * k), min(1, b * k)) }
        var color: Color { Color(red: min(1, max(0, r)), green: min(1, max(0, g)), blue: min(1, max(0, b))) }
    }

    private static func smooth(_ a: Double, _ b: Double, _ x: Double) -> Double {
        let t = min(1, max(0, (x - a) / (b - a)))
        return t * t * (3 - 2 * t)
    }

    private static func hash(_ i: Int, _ s: Double) -> Double {
        let v = sin(Double(i) * 127.1 + s * 311.7) * 43758.5453
        return v - floor(v)
    }

    private static let bayer: [Int] = [
        0, 8, 2, 10,
        12, 4, 14, 6,
        3, 11, 1, 9,
        15, 7, 13, 5
    ]

    private func drawDitheredSky(_ ctx: inout GraphicsContext, _ size: CGSize, _ t: Double, _ skyTop: RGB, _ skyBottom: RGB, _ night: Double, _ pxShift: Double) {
        let px = 4
        let pw = max(96, Int(ceil(size.width / CGFloat(px))))
        let ph = max(48, Int(ceil(size.height * 0.78 / CGFloat(px))))
        var bytes = [UInt8](repeating: 0, count: pw * ph * 4)

        for y in 0..<ph {
            let ny = Double(y) / Double(ph)
            let bRow = (y & 3) * 4
            let sky = skyTop.mix(skyBottom, ny)
            for x in 0..<pw {
                let nx = Double(x) / Double(pw)
                let b = (Double(Self.bayer[bRow + (x & 3)]) + 0.5) / 16.0
                // subtle Bayer dithered sky banding + night/dusk emerald aurora ribbon
                let bandMix = min(1.0, max(0.0, floor((ny * 6.0) + b) / 6.0))
                let c = skyTop.mix(skyBottom, bandMix)
                var r = c.r * 255.0, g = c.g * 255.0, bl = c.b * 255.0

                if night > 0.04 {
                    let w1 = 0.54 + 0.12 * sin(nx * 5.0 + t * 0.35 + pxShift * 0.8) + 0.04 * cos(nx * 9.5 - t * 0.25)
                    let d1 = exp(-pow((ny - w1) / 0.16, 2))
                    let w2 = 0.68 + 0.09 * cos(nx * 4.2 - t * 0.28 - pxShift * 0.5)
                    let d2 = exp(-pow((ny - w2) / 0.14, 2))
                    let aur1 = min(1.0, floor(d1 * night * 4.0 + b) / 4.0)
                    let aur2 = min(1.0, floor(d2 * night * 3.0 + b) / 3.0)
                    r += aur1 * 48.0 + aur2 * 64.0
                    g += aur1 * 124.0 + aur2 * 56.0
                    bl += aur1 * 72.0 + aur2 * 24.0
                }

                let idx = (y * pw + x) * 4
                bytes[idx] = UInt8(min(255, max(0, Int(r))))
                bytes[idx + 1] = UInt8(min(255, max(0, Int(g))))
                bytes[idx + 2] = UInt8(min(255, max(0, Int(bl))))
                bytes[idx + 3] = 255
            }
        }

        let data = Data(bytes)
        if let provider = CGDataProvider(data: data as CFData),
           let cgImage = CGImage(
            width: pw, height: ph, bitsPerComponent: 8, bitsPerPixel: 32,
            bytesPerRow: pw * 4, space: CGColorSpaceCreateDeviceRGB(),
            bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
            provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent
           ) {
            ctx.draw(Image(decorative: cgImage, scale: 1), in: CGRect(x: 0, y: 0, width: size.width, height: size.height * 0.78))
        }
    }

    private func sky(_ e: Double) -> (top: RGB, bottom: RGB) {
        let keys: [(Double, RGB, RGB)] = [
            (-1.00, RGB(0.020, 0.028, 0.043), RGB(0.039, 0.051, 0.067)),
            (-0.20, RGB(0.039, 0.065, 0.082), RGB(0.086, 0.212, 0.195)),
            (0.00, RGB(0.10, 0.16, 0.24), RGB(0.91, 0.56, 0.31)),
            (0.22, RGB(0.24, 0.45, 0.78), RGB(0.95, 0.76, 0.56)),
            (1.00, RGB(0.22, 0.48, 0.90), RGB(0.66, 0.85, 0.98)),
        ]
        for i in 1..<keys.count where e <= keys[i].0 {
            let a = keys[i - 1], b = keys[i]
            let t = (e - a.0) / (b.0 - a.0)
            return (a.1.mix(b.1, t), a.2.mix(b.2, t))
        }
        return (keys.last!.1, keys.last!.2)
    }

    private struct Layer {
        let block: CGFloat, base: CGFloat, amp: CGFloat, speed: CGFloat, parallax: CGFloat, seed: Double
        let top: RGB, body: RGB, haze: Double, trees: Double
    }

    private let layers: [Layer] = [
        Layer(block: 30, base: 0.50, amp: 0.20, speed: 2.0, parallax: 6, seed: 1, top: RGB(0.80, 0.86, 0.95), body: RGB(0.42, 0.50, 0.66), haze: 0.62, trees: 0),
        Layer(block: 28, base: 0.62, amp: 0.13, speed: 5.0, parallax: 14, seed: 7, top: RGB(0.40, 0.66, 0.46), body: RGB(0.29, 0.45, 0.38), haze: 0.40, trees: 0.10),
        Layer(block: 34, base: 0.73, amp: 0.10, speed: 10.0, parallax: 26, seed: 13, top: RGB(0.33, 0.62, 0.33), body: RGB(0.35, 0.41, 0.28), haze: 0.20, trees: 0.22),
        Layer(block: 44, base: 0.86, amp: 0.10, speed: 18.0, parallax: 46, seed: 21, top: RGB(0.27, 0.54, 0.26), body: RGB(0.30, 0.21, 0.14), haze: 0.0, trees: 0.16),
    ]

    private func height(_ i: Int, _ seed: Double) -> CGFloat {
        let x = Double(i) * 0.19 + seed
        return CGFloat((sin(x) * 0.5 + sin(x * 2.3 + seed) * 0.28 + sin(x * 0.43) * 0.42) / 1.2)
    }

    var body: some View {
        GeometryReader { _ in
            if let fixed = fixedTime {
                Canvas { ctx, size in draw(&ctx, size, fixed) }
            } else {
                TimelineView(.animation(minimumInterval: nil, paused: reduceMotion || paused || scenePhase != .active)) { timeline in
                    Canvas { ctx, size in draw(&ctx, size, reduceMotion ? 0 : timeline.date.timeIntervalSinceReferenceDate) }
                }
            }
        }
        .accessibilityHidden(true)
    }

    private func draw(_ ctx: inout GraphicsContext, _ size: CGSize, _ t: Double) {
        let w = size.width, h = size.height
        // the clock: a day lasts four minutes, starting from the real time of day (or noon for snapshots)
        let cal = Calendar.current.dateComponents([.hour, .minute], from: Date())
        let real = (Double(cal.hour ?? 12) + Double(cal.minute ?? 0) / 60) / 24
        let phase = ((startPhase ?? (fixedTime != nil ? 0.52 : real)) + (fixedTime != nil ? 0 : (t - Self.opened) / 240)).truncatingRemainder(dividingBy: 1)
        let a = 2 * Double.pi * (phase - 0.25)
        let e = sin(a)
        let day = Self.smooth(-0.14, 0.32, e)
        let (skyTop, skyBottom) = sky(e)
        // ease toward the pointer a little each frame, so parallax glides instead of jumping when the pointer enters, leaves or moves fast
        eased.x += (Double(pointer.x) - eased.x) * 0.10
        eased.y += (Double(pointer.y) - eased.y) * 0.10
        let px = eased.x - 0.5, py = eased.y - 0.5

        // sky (ordered Bayer-dithered bands + emerald aurora ribbons at dusk/night)
        ctx.fill(Path(CGRect(origin: .zero, size: size)), with: .color(skyBottom.color))
        let night = 1 - day
        drawDitheredSky(&ctx, size, t, skyTop, skyBottom, night, px)

        // stars
        if night > 0.02 {
            for i in 0..<90 {
                let sx = Self.hash(i, 3) * w - px * 8, sy = Self.hash(i, 9) * h * 0.55
                let tw = 0.55 + 0.45 * sin(t * (0.8 + Self.hash(i, 5) * 1.6) + Double(i))
                let s = 1.5 + Self.hash(i, 11) * 2
                ctx.fill(Path(CGRect(x: sx, y: sy, width: s, height: s)), with: .color(.white.opacity(night * night * tw * 0.9)))
            }
        }

        // sun and moon on their arcs
        let cx = w * 0.5, rx = w * 0.46, ry = h * 0.62, horizon = h * 0.64
        func orb(_ ang: Double, size s: CGFloat, color: Color, glow: Color, glowR: CGFloat, alpha: Double) {
            let x = cx - cos(ang) * rx - px * 10, y = horizon - sin(ang) * ry - py * 6
            guard y < horizon + s else { return }
            let g = Gradient(colors: [glow.opacity(0.55 * alpha), glow.opacity(0)])
            ctx.fill(Path(ellipseIn: CGRect(x: x - glowR, y: y - glowR, width: glowR * 2, height: glowR * 2)), with: .radialGradient(g, center: CGPoint(x: x, y: y), startRadius: 0, endRadius: glowR))
            ctx.fill(Path(CGRect(x: x - s / 2, y: y - s / 2, width: s, height: s)), with: .color(color.opacity(alpha)))
        }
        let low = 1 - Self.smooth(0.0, 0.55, abs(e))   // warmer near the horizon
        let sunColor = RGB(1.0, 0.93, 0.66).mix(RGB(1.0, 0.55, 0.25), low * 0.8)
        orb(a, size: 64, color: sunColor.color, glow: sunColor.color, glowR: 190, alpha: 1)
        orb(a + .pi, size: 52, color: Color(red: 0.93, green: 0.94, blue: 1.0), glow: Color(red: 0.55, green: 0.62, blue: 0.95), glowR: 130, alpha: Self.smooth(-0.05, 0.25, -e))

        // clouds
        let cloudTint = RGB(1, 1, 1).mix(skyBottom, (1 - day) * 0.65 + low * 0.25).scaled(0.38 + 0.62 * day)
        for c in 0..<7 {
            let cw: CGFloat = 130 + CGFloat(c % 3) * 50, cy = h * (0.08 + 0.055 * CGFloat(c)) + CGFloat(Self.hash(c, 2)) * 12
            let span = w + 420
            let x = (CGFloat(t) * (5 + CGFloat(c) * 1.6) + CGFloat(c) * 230 - CGFloat(px) * 30).truncatingRemainder(dividingBy: span) - 220
            let alpha = 0.50 + 0.38 * day
            ctx.fill(Path(roundedRect: CGRect(x: x, y: cy, width: cw, height: 26), cornerRadius: 4), with: .color(cloudTint.color.opacity(alpha)))
            ctx.fill(Path(roundedRect: CGRect(x: x + 26, y: cy - 17, width: cw - 62, height: 20), cornerRadius: 4), with: .color(cloudTint.color.opacity(alpha)))
            ctx.fill(Path(CGRect(x: x, y: cy + 17, width: cw, height: 9)), with: .color(cloudTint.scaled(0.84).color.opacity(alpha)))   // the shaded underside
        }

        // landscape: far to near, each layer drifting at its own speed, hazier the further it is
        let waterY = h * 0.835
        for (li, l) in layers.enumerated() {
            if li == 3 { drawLake(&ctx, size, t, y: waterY, skyTop: skyTop, skyBottom: skyBottom, day: day, sunX: cx - cos(a) * rx - px * 10, sunUp: e > 0) }
            let shift = CGFloat(t) * l.speed + CGFloat(px) * l.parallax * 2
            // two extra columns on each side: a tree is wider than its column, so one standing just outside the view must still be drawn or it pops in and out at the edge
            let cols = Int(w / l.block) + 7
            let first = Int(floor(shift / l.block)) - 3
            let lit = 0.20 + 0.80 * day
            let haze = skyBottom
            func shade(_ c: RGB, _ k: Double = 1) -> Color { c.scaled(lit * k).mix(haze, l.haze * (0.55 + 0.45 * day)).color }
            for k in 0..<cols {
                let idx = first + k
                let hh = height(idx, l.seed)
                // the terrain is stepped into blocks first and the pointer's vertical parallax is added after: stepping the sum made whole columns jump a block at a time
                let baseTop = (h * (l.base - l.amp * hh) / l.block).rounded() * l.block
                let top = baseTop + CGFloat(py) * l.parallax
                let x = CGFloat(idx) * l.block - shift
                let bw = l.block + 0.6
                // body, with a few lighter and darker blocks so it reads as cubes rather than a flat fill
                ctx.fill(Path(CGRect(x: x, y: top, width: bw, height: h - top)), with: .color(shade(l.body)))
                for row in 1..<5 {
                    let r = Self.hash(idx &* 7 &+ row, l.seed)
                    if r > 0.55 { ctx.fill(Path(CGRect(x: x, y: top + CGFloat(row) * l.block * 0.9, width: bw, height: l.block * 0.9)), with: .color(shade(l.body, r > 0.8 ? 1.12 : 0.9))) }
                }
                ctx.fill(Path(CGRect(x: x, y: top, width: bw, height: l.block * 0.34)), with: .color(shade(l.top)))
                ctx.fill(Path(CGRect(x: x, y: top + l.block * 0.34, width: bw, height: l.block * 0.10)), with: .color(shade(l.top, 0.78)))   // the lip under the grass
                // trees stand on the grass
                if l.trees > 0, baseTop < (li == 3 ? waterY - 2 : h), Self.hash(idx, l.seed + 40) < l.trees {
                    let tw = l.block * 0.34, th = l.block * (1.6 + Self.hash(idx, 4))
                    ctx.fill(Path(CGRect(x: x + (bw - tw) / 2, y: top - th, width: tw, height: th)), with: .color(shade(RGB(0.36, 0.25, 0.15), 0.9)))
                    let lw = l.block * 1.9, lh = l.block * 0.85
                    let leaf = RGB(0.20, 0.47, 0.22)
                    ctx.fill(Path(CGRect(x: x + (bw - lw) / 2, y: top - th - lh * 0.35, width: lw, height: lh)), with: .color(shade(leaf)))
                    ctx.fill(Path(CGRect(x: x + (bw - lw * 0.56) / 2, y: top - th - lh * 1.2, width: lw * 0.56, height: lh * 0.9)), with: .color(shade(leaf, 1.1)))
                }
            }
        }

        // fireflies and embers drifting up through the foreground
        var glowCtx = ctx
        glowCtx.blendMode = .plusLighter
        let flies = 34
        for i in 0..<flies {
            let sp = 0.018 + Self.hash(i, 1) * 0.03
            let yy = 1 - ((t * sp + Self.hash(i, 2)).truncatingRemainder(dividingBy: 1))
            let xx = Self.hash(i, 3) + sin(t * 0.4 + Double(i)) * 0.015 - px * 0.02
            let s = 2 + Self.hash(i, 6) * 3
            let flick = 0.45 + 0.55 * sin(t * (1.2 + Self.hash(i, 8) * 2) + Double(i) * 2)
            let amber = i % 3 == 0
            let col = amber ? Color(red: 1, green: 0.62, blue: 0.22) : Color(red: 0.78, green: 1, blue: 0.45)
            let fade = amber ? 0.9 : (0.25 + 0.75 * night)
            let rect = CGRect(x: xx * w, y: h * (0.35 + 0.65 * yy), width: s, height: s)
            glowCtx.fill(Path(ellipseIn: rect.insetBy(dx: -s * 2.2, dy: -s * 2.2)), with: .color(col.opacity(0.16 * flick * fade)))
            glowCtx.fill(Path(rect), with: .color(col.opacity(0.9 * flick * fade)))
        }

        // a soft vignette keeps the corners calm
        ctx.fill(Path(CGRect(origin: .zero, size: size)), with: .radialGradient(Gradient(colors: [.clear, .black.opacity(0.28)]), center: CGPoint(x: w / 2, y: h * 0.55), startRadius: min(w, h) * 0.45, endRadius: max(w, h) * 0.8))
    }

    private func drawLake(_ ctx: inout GraphicsContext, _ size: CGSize, _ t: Double, y: CGFloat, skyTop: RGB, skyBottom: RGB, day: Double, sunX: CGFloat, sunUp: Bool) {
        let w = size.width, h = size.height
        let deep = RGB(0.05, 0.22, 0.30).scaled(0.25 + 0.75 * day)
        let surface = skyBottom.mix(deep, 0.45)
        ctx.fill(Path(CGRect(x: 0, y: y, width: w, height: h - y)), with: .linearGradient(Gradient(colors: [surface.color, deep.color]), startPoint: CGPoint(x: 0, y: y), endPoint: CGPoint(x: 0, y: h)))
        var g = ctx
        g.blendMode = .plusLighter
        // shimmer: short horizontal dashes, densest under the sun
        for i in 0..<70 {
            let r = Self.hash(i, 15)
            let yy = y + 4 + CGFloat(Self.hash(i, 16)) * (h - y - 6)
            let depth = (yy - y) / (h - y)
            var xx = CGFloat(Self.hash(i, 17)) * w
            if sunUp, r < 0.5 { xx = sunX + CGFloat(Self.hash(i, 18) - 0.5) * 120 * (0.4 + depth) }
            let tw = 0.5 + 0.5 * sin(t * (1 + r * 2) + Double(i))
            let len = CGFloat(10 + r * 26) * (0.6 + depth)
            g.fill(Path(roundedRect: CGRect(x: xx + CGFloat(sin(t * 0.5 + Double(i))) * 4, y: yy, width: len, height: 2), cornerRadius: 1), with: .color(.white.opacity(0.22 * tw * (0.3 + 0.7 * day))))
        }
        // the shore line
        ctx.fill(Path(CGRect(x: 0, y: y - 1, width: w, height: 2)), with: .color(.white.opacity(0.10)))
    }
}
