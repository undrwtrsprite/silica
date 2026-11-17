import AppKit
import SwiftUI

/// Ordered 4x4 Bayer-dithered pixel backdrop matching the Silica website's pixelated canvas headers.
/// Renders at ~4x4 pt pixel scale with nearest-neighbor scaling so edges stay crisp and CPU usage stays tiny.
struct DitherBackdrop: View {
    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase

    var paused = false
    var fixedTime: Double? = CommandLine.arguments.contains("--snapshots") ? 4.0 : nil

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

    var body: some View {
        GeometryReader { _ in
            if let fixed = fixedTime {
                Canvas { ctx, size in draw(&ctx, size, fixed) }
            } else {
                TimelineView(.animation(minimumInterval: 1.0 / 24.0, paused: reduceMotion || paused || scenePhase != .active)) { timeline in
                    Canvas { ctx, size in draw(&ctx, size, reduceMotion ? 4.0 : timeline.date.timeIntervalSinceReferenceDate) }
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
        let ph = max(54, Int(ceil(size.height / CGFloat(px))))
        var bytes = [UInt8](repeating: 0, count: pw * ph * 4)

        for y in 0..<ph {
            let ny = Double(y) / Double(ph)
            let bRow = (y & 3) * 4
            let fadeOut = ny > 0.72 ? max(0.0, 1.0 - (ny - 0.72) / 0.28) : 1.0

            for x in 0..<pw {
                let nx = Double(x) / Double(pw)
                let b = (Double(Self.bayer[bRow + (x & 3)]) + 0.5) / 16.0

                // Top-right green dawn + isometric grid shimmer + rolling emerald wave
                let dawn = exp(-pow((nx - 0.78) / 0.36, 2) - pow((ny + 0.04) / 0.42, 2)) * 0.65
                let w = 0.42 + 0.08 * sin(nx * 4.5 + t * 0.45) + 0.04 * cos(nx * 8.2 - t * 0.32)
                let horizon = exp(-pow((ny - w) / 0.16, 2)) * 0.42
                let u = (Double(x) + Double(y) * 2.0) / 30.0
                let v = (Double(x) - Double(y) * 2.0) / 30.0
                let lattice = (abs(u - u.rounded()) < 0.055 || abs(v - v.rounded()) < 0.055) ? 0.14 : 0.0

                let val = (dawn + horizon + lattice) * fadeOut
                let q = min(1.0, floor(val * 4.0 + b) / 4.0)

                let idx = (y * pw + x) * 4
                bytes[idx] = UInt8(min(255, Int(10.0 + q * 44.0)))
                bytes[idx + 1] = UInt8(min(255, Int(13.0 + q * 116.0)))
                bytes[idx + 2] = UInt8(min(255, Int(17.0 + q * 68.0)))
                bytes[idx + 3] = 255
            }
        }

        // Sparse twinkling stars in upper band
        for i in 0..<24 {
            let sx = Int(Self.hash(Double(i) * 19.3) * Double(pw))
            let sy = Int(Self.hash(Double(i) * 7.1) * Double(ph) * 0.62)
            guard sx >= 0, sx < pw, sy >= 0, sy < ph else { continue }
            let tw = reduceMotion ? 0.6 : (0.2 + 0.6 * pow(0.5 + 0.5 * sin(t * 1.1 + Double(i) * 2.1), 2))
            let idx = (sy * pw + sx) * 4
            bytes[idx] = UInt8(min(255, Int(bytes[idx]) + Int(115 * tw)))
            bytes[idx + 1] = UInt8(min(255, Int(bytes[idx + 1]) + Int(165 * tw)))
            bytes[idx + 2] = UInt8(min(255, Int(bytes[idx + 2]) + Int(135 * tw)))
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
}
