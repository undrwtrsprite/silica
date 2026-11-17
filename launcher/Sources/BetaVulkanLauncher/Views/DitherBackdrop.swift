import AppKit
import SwiftUI

/// Five bespoke 4x4 Bayer-dithered pixel artworks—one tailored per launcher screen—
/// rendered at nearest-neighbor 4pt pixel scale with lightweight procedural math.
struct DitherBackdrop: View {
    enum Mode {
        case launchCanyon
        case worldsTopo
        case jarsMosaic
        case settingsWave
        case advancedPulse
    }

    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase

    var mode: Mode = .launchCanyon
    var paused = false
    var fixedTime: Double? = CommandLine.arguments.contains("--snapshots") ? 6.5 : nil

    private static let bayer: [Int] = [
        0, 8, 2, 10,
        12, 4, 14, 6,
        3, 11, 1, 9,
        15, 7, 13, 5
    ]

    private static func hash(_ n: Double) -> Double {
        let x = sin(n * 127.1 + 311.7) * 43758.5453
        return x - floor(x)
    }

    private static func vnoise(_ x: Double, _ y: Double) -> Double {
        let ix = floor(x), iy = floor(y)
        let fx = x - ix, fy = y - iy
        let ux = fx * fx * (3 - 2 * fx), uy = fy * fy * (3 - 2 * fy)
        let a = hash(ix * 57.3 + iy * 131.9)
        let b = hash((ix + 1) * 57.3 + iy * 131.9)
        let c = hash(ix * 57.3 + (iy + 1) * 131.9)
        let d = hash((ix + 1) * 57.3 + (iy + 1) * 131.9)
        return a + (b - a) * ux + (c - a) * uy + (a - b - c + d) * ux * uy
    }

    var body: some View {
        GeometryReader { _ in
            if let fixed = fixedTime {
                Canvas { ctx, size in draw(&ctx, size, fixed) }
            } else {
                TimelineView(.animation(minimumInterval: 1.0 / 24.0, paused: reduceMotion || paused || scenePhase != .active)) { timeline in
                    Canvas { ctx, size in draw(&ctx, size, reduceMotion ? 6.5 : timeline.date.timeIntervalSinceReferenceDate) }
                }
            }
        }
        .accessibilityHidden(true)
    }

    private func draw(_ ctx: inout GraphicsContext, _ size: CGSize, _ t: Double) {
        let dark = scheme == .dark
        guard dark else { return }

        let px = 4
        let pw = max(96, Int(ceil(size.width / CGFloat(px))))
        let ph = max(48, Int(ceil(size.height / CGFloat(px))))
        var bytes = [UInt8](repeating: 0, count: pw * ph * 4)

        switch mode {
        case .launchCanyon:
            drawLaunchCanyon(&bytes, pw, ph, t)
        case .worldsTopo:
            drawWorldsTopo(&bytes, pw, ph, t)
        case .jarsMosaic:
            drawJarsMosaic(&bytes, pw, ph, t)
        case .settingsWave:
            drawSettingsWave(&bytes, pw, ph, t)
        case .advancedPulse:
            drawAdvancedPulse(&bytes, pw, ph, t)
        }

        let data = Data(bytes)
        guard let provider = CGDataProvider(data: data as CFData),
              let cgImage = CGImage(
                width: pw, height: ph, bitsPerComponent: 8, bitsPerPixel: 32,
                bytesPerRow: pw * 4, space: CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.premultipliedLast.rawValue),
                provider: provider, decode: nil, shouldInterpolate: false, intent: .defaultIntent
              ) else { return }

        ctx.draw(Image(decorative: cgImage, scale: 1), in: CGRect(origin: .zero, size: size))
    }

    /// Launch card: stepped night canyon silhouettes + glowing cratered emerald moon + rising embers.
    private func drawLaunchCanyon(_ buf: inout [UInt8], _ pw: Int, _ ph: Int, _ t: Double) {
        let moonX = Int(Double(pw) * 0.84)
        let moonY = Int(Double(ph) * 0.28)
        let moonR = max(5, Int(Double(min(pw, ph)) * 0.11))

        for y in 0..<ph {
            let ny = Double(y) / Double(ph)
            let bRow = (y & 3) * 4

            for x in 0..<pw {
                let nx = Double(x) / Double(pw)
                let b = (Double(Self.bayer[bRow + (x & 3)]) + 0.5) / 16.0

                // Three layered stepped mesa ridges scrolling gently left
                let m1 = 0.44 + 0.16 * floor(Self.vnoise(nx * 5.0 + t * 0.04, 1.3) * 5.0) / 5.0
                let m2 = 0.58 + 0.14 * floor(Self.vnoise(nx * 7.5 + t * 0.08, 4.7) * 4.0) / 4.0
                let m3 = 0.74 + 0.12 * floor(Self.vnoise(nx * 11.0 + t * 0.13, 9.1) * 4.0) / 4.0

                let mdx = x - moonX, mdy = y - moonY
                let halo = exp(-hypot(Double(mdx), Double(mdy)) / Double(moonR * 3)) * 0.42

                // Warm amber horizon glow tucked behind mesa silhouette + emerald nebula arch
                let horizon = exp(-pow((ny - 0.56) / 0.22, 2)) * (0.35 + 0.25 * sin(nx * 3.2 + t * 0.25))
                let skyGrad = (1.0 - ny) * 0.12

                var r = 10.0, g = 13.0, bl = 17.0
                if ny >= m3 {
                    let q = min(1.0, floor((0.18 + (ny - m3) * 0.4) * 3.0 + b) / 3.0)
                    r = 11 + q * 8; g = 16 + q * 16; bl = 20 + q * 14
                } else if ny >= m2 {
                    let rim = (ny - m2) < 0.03 ? 0.35 : 0.0
                    let q = min(1.0, floor((0.28 + rim + halo * 0.4) * 4.0 + b) / 4.0)
                    r = 12 + q * 18; g = 21 + q * 34; bl = 22 + q * 24
                } else if ny >= m1 {
                    let q = min(1.0, floor((0.36 + halo * 0.5) * 4.0 + b) / 4.0)
                    r = 16 + q * 26; g = 28 + q * 50; bl = 26 + q * 34
                } else {
                    let band = min(1.0, floor((horizon + halo + skyGrad) * 5.0 + b) / 5.0)
                    let warm = min(1.0, floor(horizon * exp(-pow((nx - 0.82) / 0.35, 2)) * 4.0 + b) / 4.0)
                    r = 10 + band * 46 + warm * 78
                    g = 13 + band * 118 + warm * 54
                    bl = 17 + band * 76 + warm * 18
                }

                // Square cratered moon behind upper mesa ridges
                if ny < m1 && abs(mdx) <= moonR && abs(mdy) <= moonR {
                    let cr = (mdx == -2 && mdy == -1) || (mdx == 2 && mdy == 2) || (mdx == 0 && mdy == -2)
                    if cr { r = 158; g = 196; bl = 148 }
                    else { r = 218; g = 242; bl = 208 }
                }

                let idx = (y * pw + x) * 4
                buf[idx] = UInt8(min(255, Int(r)))
                buf[idx + 1] = UInt8(min(255, Int(g)))
                buf[idx + 2] = UInt8(min(255, Int(bl)))
                buf[idx + 3] = 255
            }
        }

        // Twinkling upper sky stars & rising emerald embers
        for i in 0..<28 {
            let sx = Int(Self.hash(Double(i) * 13.7) * Double(pw))
            let sy = Int(Self.hash(Double(i) * 5.3) * Double(ph) * 0.44)
            guard sx >= 0, sx < pw, sy >= 0, sy < ph else { continue }
            let tw = reduceMotion ? 0.8 : (0.25 + 0.75 * pow(0.5 + 0.5 * sin(t * 1.3 + Double(i) * 2.1), 2))
            let idx = (sy * pw + sx) * 4
            buf[idx] = UInt8(min(255, Int(buf[idx]) + Int(150 * tw)))
            buf[idx + 1] = UInt8(min(255, Int(buf[idx + 1]) + Int(205 * tw)))
            buf[idx + 2] = UInt8(min(255, Int(buf[idx + 2]) + Int(170 * tw)))
        }
    }

    /// Worlds screen: drifting topographic contour lines from 2D value noise.
    private func drawWorldsTopo(_ buf: inout [UInt8], _ pw: Int, _ ph: Int, _ t: Double) {
        for y in 0..<ph {
            let ny = Double(y) / Double(ph)
            let bRow = (y & 3) * 4
            let fade = ny > 0.72 ? max(0.0, 1.0 - (ny - 0.72) / 0.28) : 1.0
            for x in 0..<pw {
                let nx = Double(x) / Double(pw)
                let b = (Double(Self.bayer[bRow + (x & 3)]) + 0.5) / 16.0
                let n = Self.vnoise(nx * 5.5 + t * 0.08, ny * 3.5 + t * 0.05) * 0.7
                    + Self.vnoise(nx * 11.0 - t * 0.05, ny * 7.0) * 0.3
                let iso = abs((n * 7.0).truncatingRemainder(dividingBy: 1.0) - 0.5) * 2.0
                let ring = iso > 0.78 ? (iso - 0.78) / 0.22 : 0.0
                let glow = (ring * 0.55 + n * 0.22) * fade * (0.4 + 0.6 * nx)
                let q = min(1.0, floor(glow * 4.0 + b) / 4.0)
                let idx = (y * pw + x) * 4
                buf[idx] = UInt8(min(255, Int(10.0 + q * 48.0)))
                buf[idx + 1] = UInt8(min(255, Int(13.0 + q * 112.0)))
                buf[idx + 2] = UInt8(min(255, Int(17.0 + q * 64.0)))
                buf[idx + 3] = 255
            }
        }
    }

    /// Jars screen: isometric voxel checkerboard blocks pulsing across the header.
    private func drawJarsMosaic(_ buf: inout [UInt8], _ pw: Int, _ ph: Int, _ t: Double) {
        for y in 0..<ph {
            let ny = Double(y) / Double(ph)
            let bRow = (y & 3) * 4
            let fade = ny > 0.72 ? max(0.0, 1.0 - (ny - 0.72) / 0.28) : 1.0
            for x in 0..<pw {
                let nx = Double(x) / Double(pw)
                let b = (Double(Self.bayer[bRow + (x & 3)]) + 0.5) / 16.0
                let u = floor((Double(x) + Double(y) * 2.0) / 14.0)
                let v = floor((Double(x) - Double(y) * 2.0) / 14.0)
                let pulse = 0.5 + 0.5 * sin(u * 0.9 + v * 1.1 - t * 1.1)
                let val = pulse * 0.32 * fade * (0.35 + 0.65 * nx)
                let q = min(1.0, floor(val * 4.0 + b) / 4.0)
                let idx = (y * pw + x) * 4
                buf[idx] = UInt8(min(255, Int(10.0 + q * 54.0)))
                buf[idx + 1] = UInt8(min(255, Int(13.0 + q * 118.0)))
                buf[idx + 2] = UInt8(min(255, Int(17.0 + q * 58.0)))
                buf[idx + 3] = 255
            }
        }
    }

    /// Settings screen: calm twin dithered cosine signal bands.
    private func drawSettingsWave(_ buf: inout [UInt8], _ pw: Int, _ ph: Int, _ t: Double) {
        for y in 0..<ph {
            let ny = Double(y) / Double(ph)
            let bRow = (y & 3) * 4
            let fade = ny > 0.75 ? max(0.0, 1.0 - (ny - 0.75) / 0.25) : 1.0
            for x in 0..<pw {
                let nx = Double(x) / Double(pw)
                let b = (Double(Self.bayer[bRow + (x & 3)]) + 0.5) / 16.0
                let w1 = 0.42 + 0.12 * sin(nx * 4.5 + t * 0.5)
                let w2 = 0.52 + 0.09 * cos(nx * 3.8 - t * 0.35)
                let v1 = exp(-pow((ny - w1) / 0.14, 2)) * 0.45 * fade
                let v2 = exp(-pow((ny - w2) / 0.12, 2)) * 0.32 * fade
                let q1 = min(1.0, floor(v1 * 4.0 + b) / 4.0)
                let q2 = min(1.0, floor(v2 * 4.0 + b) / 4.0)
                let idx = (y * pw + x) * 4
                buf[idx] = UInt8(min(255, Int(10.0 + q1 * 42.0 + q2 * 26.0)))
                buf[idx + 1] = UInt8(min(255, Int(13.0 + q1 * 105.0 + q2 * 72.0)))
                buf[idx + 2] = UInt8(min(255, Int(17.0 + q1 * 62.0 + q2 * 96.0)))
                buf[idx + 3] = 255
            }
        }
    }

    /// Advanced screen: vertical quantized frame-pacing column meters.
    private func drawAdvancedPulse(_ buf: inout [UInt8], _ pw: Int, _ ph: Int, _ t: Double) {
        for y in 0..<ph {
            let ny = Double(y) / Double(ph)
            let bRow = (y & 3) * 4
            let fade = ny > 0.75 ? max(0.0, 1.0 - (ny - 0.75) / 0.25) : 1.0
            for x in 0..<pw {
                let b = (Double(Self.bayer[bRow + (x & 3)]) + 0.5) / 16.0
                let col = x / 3
                let peak = 0.28 + 0.34 * (0.5 + 0.5 * sin(Double(col) * 0.55 + t * 1.4))
                let bar = (x % 3 != 0 && ny > (0.68 - peak) && ny < 0.72) ? 0.36 : 0.08
                let q = min(1.0, floor(bar * fade * 4.0 + b) / 4.0)
                let idx = (y * pw + x) * 4
                buf[idx] = UInt8(min(255, Int(10.0 + q * 48.0)))
                buf[idx + 1] = UInt8(min(255, Int(13.0 + q * 124.0)))
                buf[idx + 2] = UInt8(min(255, Int(17.0 + q * 82.0)))
                buf[idx + 3] = 255
            }
        }
    }
}
