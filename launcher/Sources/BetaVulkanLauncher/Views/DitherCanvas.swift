import AppKit
import SwiftUI

/// Clean 4x4 Bayer ordered-dither helpers shared by subtle background aurora, Play horizon, and world seeds.
private enum DitherMath {
    static let bayer: [Int] = [
        0, 8, 2, 10,
        12, 4, 14, 6,
        3, 11, 1, 9,
        15, 7, 13, 5
    ]

    @inline(__always)
    static func threshold(_ x: Int, _ y: Int) -> Double {
        (Double(bayer[((y & 3) << 2) | (x & 3)]) + 0.5) / 16.0
    }

    @inline(__always)
    static func hash(_ n: Double) -> Double {
        let v = sin(n * 127.1 + 311.7) * 43758.5453
        return v - floor(v)
    }

    static func vnoise(_ x: Double, _ y: Double) -> Double {
        let ix = floor(x), iy = floor(y)
        let fx = x - ix, fy = y - iy
        let ux = fx * fx * (3.0 - 2.0 * fx)
        let uy = fy * fy * (3.0 - 2.0 * fy)
        let a = hash(ix * 57.3 + iy * 131.9)
        let b = hash((ix + 1.0) * 57.3 + iy * 131.9)
        let c = hash(ix * 57.3 + (iy + 1.0) * 131.9)
        let d = hash((ix + 1.0) * 57.3 + (iy + 1.0) * 131.9)
        return a + (b - a) * ux + (c - a) * uy + (a - b - c + d) * ux * uy
    }

    static func render(
        _ ctx: inout GraphicsContext,
        size: CGSize,
        pixelSize: Int,
        minW: Int = 80,
        minH: Int = 40,
        fill: (inout [UInt8], Int, Int) -> Void
    ) {
        let pw = max(minW, Int(ceil(size.width / CGFloat(pixelSize))))
        let ph = max(minH, Int(ceil(size.height / CGFloat(pixelSize))))
        var bytes = [UInt8](repeating: 0, count: pw * ph * 4)
        fill(&bytes, pw, ph)
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

    @inline(__always)
    static func put(_ buf: inout [UInt8], _ idx: Int, _ r: Double, _ g: Double, _ b: Double) {
        buf[idx] = UInt8(max(0, min(255, Int(r))))
        buf[idx + 1] = UInt8(max(0, min(255, Int(g))))
        buf[idx + 2] = UInt8(max(0, min(255, Int(b))))
        buf[idx + 3] = 255
    }
}

/// Whisper-quiet ordered-dither atmosphere at the top of the window, matching the website's green dawn glow.
struct DitherAurora: View {
    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase

    var vulkan = true
    var paused = false
    var pointer = CGPoint(x: 0.5, y: 0.3)
    var fixedTime: Double? = CommandLine.arguments.contains("--snapshots") ? 6.0 : nil

    var body: some View {
        GeometryReader { _ in
            if let fixed = fixedTime {
                Canvas { ctx, size in draw(&ctx, size, fixed) }
            } else {
                TimelineView(.animation(minimumInterval: 1.0 / 18.0, paused: reduceMotion || paused || scenePhase != .active)) { timeline in
                    Canvas { ctx, size in
                        draw(&ctx, size, reduceMotion ? 6.0 : timeline.date.timeIntervalSinceReferenceDate)
                    }
                }
            }
        }
        .accessibilityHidden(true)
    }

    private func draw(_ ctx: inout GraphicsContext, _ size: CGSize, _ t: Double) {
        let dark = scheme == .dark
        let br = dark ? 17.0 : 243.0
        let bg = dark ? 21.0 : 245.0
        let bb = dark ? 27.0 : 247.0

        DitherMath.render(&ctx, size: size, pixelSize: 5, minW: 84, minH: 36) { buf, pw, ph in
            for y in 0..<ph {
                let ny = Double(y) / Double(ph)
                let verticalFade = max(0.0, 1.0 - ny * 1.15)
                for x in 0..<pw {
                    let nx = Double(x) / Double(pw)
                    let b = DitherMath.threshold(x, y)

                    let drift = DitherMath.vnoise(nx * 3.2 + t * 0.04, ny * 2.4 - t * 0.03)
                    let dx = nx - (0.68 + (Double(pointer.x) - 0.5) * 0.14)
                    let dy = ny - 0.12
                    let spot = exp(-(dx * dx * 1.8 + dy * dy * 4.5))
                    let level = (spot * 0.45 + drift * 0.25) * verticalFade
                    let q = min(1.0, floor(level * 4.0 + b) / 4.0)
                    let idx = (y * pw + x) * 4

                    if dark {
                        if vulkan {
                            DitherMath.put(&buf, idx, br + q * 22.0, bg + q * 48.0, bb + q * 28.0)
                        } else {
                            DitherMath.put(&buf, idx, br + q * 18.0, bg + q * 28.0, bb + q * 44.0)
                        }
                    } else {
                        DitherMath.put(&buf, idx, br - q * 16.0, bg - q * 4.0, bb - q * 14.0)
                    }
                }
            }
        }
    }
}

/// Play card's calm Bayer-dithered stepped hills backdrop reacting gently to cursor parallax & backend.
struct DitherHorizonStage: View {
    @Environment(\.colorScheme) private var scheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.scenePhase) private var scenePhase

    var vulkan = true
    var running = false
    var pointer = CGPoint(x: 0.5, y: 0.5)
    var fixedTime: Double? = CommandLine.arguments.contains("--snapshots") ? 7.5 : nil

    var body: some View {
        GeometryReader { _ in
            if let fixed = fixedTime {
                Canvas { ctx, size in draw(&ctx, size, fixed) }
            } else {
                TimelineView(.animation(minimumInterval: running ? 0.25 : 1.0 / 24.0, paused: reduceMotion || scenePhase != .active)) { timeline in
                    Canvas { ctx, size in
                        draw(&ctx, size, reduceMotion ? 7.5 : timeline.date.timeIntervalSinceReferenceDate)
                    }
                }
            }
        }
        .accessibilityHidden(true)
    }

    private func draw(_ ctx: inout GraphicsContext, _ size: CGSize, _ t: Double) {
        let dark = scheme == .dark
        let shiftX = (Double(pointer.x) - 0.5) * 0.18
        let shiftY = (Double(pointer.y) - 0.5) * 0.05

        DitherMath.render(&ctx, size: size, pixelSize: 4, minW: 112, minH: 52) { buf, pw, ph in
            for y in 0..<ph {
                let ny = Double(y) / Double(ph)
                for x in 0..<pw {
                    let nx = Double(x) / Double(pw)
                    let b = DitherMath.threshold(x, y)

                    let farRidge = 0.48 + shiftY * 0.4 + 0.14 * floor(DitherMath.vnoise(nx * 4.2 + t * 0.03 + shiftX * 0.5, 1.7) * 5.0) / 5.0
                    let nearRidge = 0.68 + shiftY + 0.12 * floor(DitherMath.vnoise(nx * 7.5 + t * 0.07 + shiftX, 5.3) * 4.0) / 4.0

                    let skyBand = (1.0 - ny) * 0.22 + exp(-pow((ny - 0.44) / 0.24, 2)) * 0.42
                    let lakeWave = 0.5 + 0.5 * sin(nx * 14.0 + ny * 9.0 - t * 0.65)
                    let dx = nx - Double(pointer.x), dy = ny - Double(pointer.y)
                    let hoverGlow = exp(-(dx * dx + dy * dy) * 7.0) * 0.22

                    var val = 0.0
                    var crest = 0.0
                    if ny >= nearRidge {
                        val = 0.18 + lakeWave * 0.16 + hoverGlow * 0.5
                    } else if ny >= farRidge {
                        let rim = (ny - farRidge) < 0.032 ? 0.35 : 0.0
                        val = 0.28 + rim + hoverGlow * 0.4
                        crest = rim
                    } else {
                        val = skyBand + hoverGlow
                    }

                    let q = min(1.0, floor(val * 4.0 + b) / 4.0)
                    let qc = min(1.0, floor(crest * 4.0 + b) / 4.0)
                    let idx = (y * pw + x) * 4

                    if dark {
                        if running {
                            DitherMath.put(&buf, idx, 22 + q * 92 + qc * 70, 26 + q * 74 + qc * 56, 30 + q * 34)
                        } else if vulkan {
                            DitherMath.put(&buf, idx, 18 + q * 46 + qc * 44, 24 + q * 96 + qc * 80, 30 + q * 54 + qc * 36)
                        } else {
                            DitherMath.put(&buf, idx, 20 + q * 42, 26 + q * 56, 34 + q * 74 + qc * 40)
                        }
                    } else {
                        if running {
                            DitherMath.put(&buf, idx, 240 - q * 36, 232 - q * 58, 220 - q * 90)
                        } else if vulkan {
                            DitherMath.put(&buf, idx, 236 - q * 72, 244 - q * 34, 238 - q * 62)
                        } else {
                            DitherMath.put(&buf, idx, 234 - q * 56, 238 - q * 46, 245 - q * 32)
                        }
                    }
                }
            }
        }
    }
}

/// Deterministic Bayer-dithered biome seed preview for each saved world.
struct WorldDitherMap: View {
    let seedName: String

    var body: some View {
        Canvas { ctx, size in
            let seed = Double(abs(seedName.utf8.reduce(5381) { (($0 << 5) &+ $0) &+ Int($1) } % 10000)) * 0.13
            DitherMath.render(&ctx, size: size, pixelSize: 3, minW: 16, minH: 16) { buf, pw, ph in
                for y in 0..<ph {
                    let ny = Double(y) / Double(ph)
                    for x in 0..<pw {
                        let nx = Double(x) / Double(pw)
                        let b = DitherMath.threshold(x, y)
                        let elev = DitherMath.vnoise(nx * 3.8 + seed, ny * 3.8 + seed * 0.7) * 0.7
                            + DitherMath.vnoise(nx * 7.6 - seed, ny * 7.6) * 0.3
                        let q = min(1.0, floor(elev * 5.0 + b) / 5.0)
                        let idx = (y * pw + x) * 4

                        if q < 0.32 {
                            DitherMath.put(&buf, idx, 36 + q * 46, 84 + q * 105, 138 + q * 130)
                        } else if q < 0.44 {
                            DitherMath.put(&buf, idx, 194 + q * 36, 174 + q * 30, 114 + q * 28)
                        } else if q < 0.78 {
                            let gMix = (q - 0.44) / 0.34
                            DitherMath.put(&buf, idx, 62 + gMix * 48, 134 + gMix * 64, 60 + gMix * 36)
                        } else {
                            let sMix = (q - 0.78) / 0.22
                            DitherMath.put(&buf, idx, 136 + sMix * 78, 146 + sMix * 78, 152 + sMix * 76)
                        }
                    }
                }
            }
        }
        .accessibilityHidden(true)
    }
}

/// Clean Bayer-dithered progress bar used inside SetupWizard.
struct DitherProgressBar: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let progress: Double

    var body: some View {
        let clamped = min(1, max(0, progress))
        VStack(spacing: 12) {
            Text("\(Int((clamped * 100).rounded()))%")
                .font(.system(size: 40, weight: .bold, design: .monospaced))
                .monospacedDigit()
                .contentTransition(.numericText())

            GeometryReader { geo in
                TimelineView(.animation(minimumInterval: 1.0 / 16.0, paused: reduceMotion)) { timeline in
                    let t = reduceMotion ? 0 : timeline.date.timeIntervalSinceReferenceDate
                    Canvas { ctx, size in
                        DitherMath.render(&ctx, size: size, pixelSize: 4, minW: 60, minH: 4) { buf, pw, ph in
                            let fillX = clamped * Double(pw)
                            for y in 0..<ph {
                                for x in 0..<pw {
                                    let b = DitherMath.threshold(x, y)
                                    let dx = Double(x) - fillX
                                    let on: Double = {
                                        if Double(x) < fillX - 3 { return 1.0 }
                                        if dx < 5 {
                                            let pulse = 0.5 + 0.5 * sin(t * 5.0 + Double(y))
                                            return max(0, min(1, 1.0 - (dx + 3) / 8.0 + pulse * 0.2))
                                        }
                                        return 0.08
                                    }()
                                    let q = min(1.0, floor(on * 3.0 + b) / 3.0)
                                    let idx = (y * pw + x) * 4
                                    DitherMath.put(&buf, idx, 28 + q * 95, 36 + q * 165, 42 + q * 69)
                                }
                            }
                        }
                    }
                    .frame(width: geo.size.width, height: geo.size.height)
                }
            }
            .frame(width: 280, height: 16)
            .clipShape(RoundedRectangle(cornerRadius: 6, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: 6, style: .continuous).strokeBorder(Theme.stroke, lineWidth: 1))
        }
        .animation(reduceMotion ? nil : LauncherMotion.change, value: clamped)
    }
}
