import AppKit
import CoreGraphics

// Draws the Silica brand (original art, nothing from the game). The mark is a silicon wafer: a disc with one flat edge, etched into dies, with a single die
// lit in warm sand. Silica is sand, glass and the stuff chips are made of; the wafer says "native on Apple Silicon" and the one warm die is the old game
// running on it. One geometry feeds the app icon, the one-colour mark (PNG and SVG), the lockups and the banner.
// usage: swift make_icon.swift <out.iconset dir> [assets dir]

let cs = CGColorSpace(name: CGColorSpace.sRGB)!
func col(_ r: CGFloat, _ g: CGFloat, _ b: CGFloat, _ a: CGFloat = 1) -> CGColor { CGColor(colorSpace: cs, components: [r, g, b, a])! }
func gradient(_ colors: [CGColor], _ locations: [CGFloat]) -> CGGradient { CGGradient(colorsSpace: cs, colors: colors as CFArray, locations: locations)! }
func newContext(_ w: Int, _ h: Int) -> CGContext {
    let ctx = CGContext(data: nil, width: w, height: h, bitsPerComponent: 8, bytesPerRow: 0, space: cs, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    ctx.interpolationQuality = .high
    return ctx
}
func png(_ ctx: CGContext) -> Data { NSBitmapImageRep(cgImage: ctx.makeImage()!).representation(using: .png, properties: [:])! }
typealias P = CGPoint

// ---- geometry, in a 1024 box with y up ---------------------------------------------------------------------

let C = P(x: 512, y: 512)
let R: CGFloat = 318
let flatCut: CGFloat = 0.84                    // the flat sits at this fraction of the radius below the centre
let theta = acos(flatCut)                      // half the angle the flat spans
let pitch: CGFloat = R / 3.05                  // die size
let line: CGFloat = 11                         // width of the scribe lines between dies
/// The lit die: one cell up and to the left of the centre.
let lit = CGRect(x: C.x - pitch * 1.5 + line / 2, y: C.y + pitch * 0.5 + line / 2, width: pitch - line, height: pitch - line)

func waferPath(_ r: CGFloat = R) -> CGPath {
    let p = CGMutablePath()
    p.addArc(center: C, radius: r, startAngle: -.pi / 2 + theta, endAngle: -.pi / 2 - theta + 2 * .pi, clockwise: false)
    p.closeSubpath()                           // the chord is the flat
    return p
}

/// The scribe lines as thin rectangles across the whole disc.
func scribeRects() -> [CGRect] {
    var out: [CGRect] = []
    for k in -3...3 {
        let o = (CGFloat(k) + 0.5) * pitch
        if abs(o) < R {
            out.append(CGRect(x: C.x + o - line / 2, y: C.y - R, width: line, height: 2 * R))
            out.append(CGRect(x: C.x - R, y: C.y + o - line / 2, width: 2 * R, height: line))
        }
    }
    return out
}

// ---- the mark in colour -----------------------------------------------------------------------------------

let teal = col(0.14, 0.80, 0.72), blue = col(0.11, 0.50, 0.86), indigo = col(0.33, 0.33, 0.80)
let sand = col(0.99, 0.86, 0.56), sandDeep = col(0.95, 0.66, 0.30)

/// The wafer with depth, for the icon and the banner.
func drawWafer(_ ctx: CGContext) {
    // drop shadow and the glass body
    ctx.saveGState()
    ctx.setShadow(offset: CGSize(width: 0, height: -20), blur: 44, color: col(0, 0, 0, 0.55))
    ctx.addPath(waferPath()); ctx.setFillColor(col(0.07, 0.25, 0.33)); ctx.fillPath()
    ctx.restoreGState()
    ctx.saveGState()
    ctx.addPath(waferPath()); ctx.clip()
    ctx.drawLinearGradient(gradient([teal, blue, indigo], [0, 0.62, 1]), start: P(x: C.x - R * 0.7, y: C.y + R * 0.9), end: P(x: C.x + R * 0.8, y: C.y - R), options: [.drawsBeforeStartLocation, .drawsAfterEndLocation])
    // the sheen a wafer has: a soft diagonal band of light
    ctx.saveGState()
    ctx.translateBy(x: C.x, y: C.y); ctx.rotate(by: -.pi / 5); ctx.translateBy(x: -C.x, y: -C.y)
    ctx.drawLinearGradient(gradient([col(1, 1, 1, 0), col(1, 1, 1, 0.34), col(1, 1, 1, 0)], [0, 0.5, 1]), start: P(x: C.x - R * 0.55, y: C.y), end: P(x: C.x + R * 0.20, y: C.y), options: [])
    ctx.restoreGState()
    // scribe lines, etched darker
    ctx.setFillColor(col(0.02, 0.13, 0.22, 0.50))
    for r in scribeRects() { ctx.fill(r) }
    // the lit die, with its glow spilling onto the neighbours
    ctx.saveGState()
    ctx.setShadow(offset: .zero, blur: 46, color: col(1, 0.78, 0.36, 0.95))
    ctx.addRect(lit); ctx.setFillColor(sand); ctx.fillPath()
    ctx.restoreGState()
    ctx.saveGState()
    ctx.addRect(lit); ctx.clip()
    ctx.drawLinearGradient(gradient([col(1, 0.95, 0.74), sand, sandDeep], [0, 0.4, 1]), start: P(x: lit.minX, y: lit.maxY), end: P(x: lit.maxX, y: lit.minY), options: [])
    ctx.restoreGState()
    ctx.restoreGState()
    // a bright rim at the top edge and a darker one along the bottom, so the disc has thickness
    ctx.saveGState()
    ctx.addPath(waferPath()); ctx.clip()
    ctx.addPath(waferPath(R - 3)); ctx.setLineWidth(6)
    ctx.replacePathWithStrokedPath(); ctx.clip()
    ctx.drawLinearGradient(gradient([col(1, 1, 1, 0.55), col(1, 1, 1, 0.05), col(0, 0, 0, 0.28)], [0, 0.5, 1]), start: P(x: C.x, y: C.y + R), end: P(x: C.x, y: C.y - R), options: [])
    ctx.restoreGState()
}

/// The wafer flat, for the lockups: the same shapes without lighting, the lines see-through.
func drawWaferFlat(_ ctx: CGContext) {
    ctx.saveGState()
    let p = CGMutablePath()
    p.addPath(waferPath())
    ctx.addPath(p); ctx.clip()
    ctx.drawLinearGradient(gradient([teal, blue], [0, 1]), start: P(x: C.x - R * 0.7, y: C.y + R * 0.9), end: P(x: C.x + R * 0.8, y: C.y - R), options: [.drawsBeforeStartLocation, .drawsAfterEndLocation])
    ctx.setBlendMode(.clear)
    for r in scribeRects() { ctx.fill(r) }
    ctx.setBlendMode(.normal)
    ctx.addRect(lit); ctx.clip()
    ctx.drawLinearGradient(gradient([sand, sandDeep], [0, 1]), start: P(x: lit.minX, y: lit.maxY), end: P(x: lit.maxX, y: lit.minY), options: [])
    ctx.restoreGState()
}

// ---- outputs ---------------------------------------------------------------------------------------------

func renderIcon(_ px: Int) -> Data {
    let ctx = newContext(px, px)
    ctx.scaleBy(x: CGFloat(px) / 1024, y: CGFloat(px) / 1024)
    let squircle = CGPath(roundedRect: CGRect(x: 100, y: 100, width: 824, height: 824), cornerWidth: 190, cornerHeight: 190, transform: nil)
    ctx.saveGState()
    ctx.setShadow(offset: CGSize(width: 0, height: -14), blur: 28, color: col(0, 0, 0, 0.38))
    ctx.addPath(squircle); ctx.setFillColor(col(0.07, 0.10, 0.12)); ctx.fillPath()
    ctx.restoreGState()
    ctx.saveGState()
    ctx.addPath(squircle); ctx.clip()
    ctx.drawLinearGradient(gradient([col(0.145, 0.19, 0.215), col(0.045, 0.065, 0.08)], [0, 1]), start: P(x: 512, y: 924), end: P(x: 512, y: 100), options: [])
    ctx.drawRadialGradient(gradient([col(0.14, 0.75, 0.75, 0.22), col(0.14, 0.75, 0.75, 0)], [0, 1]), startCenter: C, startRadius: R * 0.6, endCenter: C, endRadius: R * 1.5, options: [])
    drawWafer(ctx)
    ctx.restoreGState()
    ctx.saveGState(); ctx.addPath(squircle); ctx.clip()
    ctx.drawLinearGradient(gradient([col(1, 1, 1, 0.09), col(1, 1, 1, 0)], [0, 1]), start: P(x: 512, y: 924), end: P(x: 512, y: 680), options: [])
    ctx.restoreGState()
    ctx.addPath(squircle); ctx.setStrokeColor(col(1, 1, 1, 0.10)); ctx.setLineWidth(3); ctx.strokePath()
    return png(ctx)
}

/// The colored wafer alone, for in-window branding without the app icon's dark squircle.
func renderMark(_ px: Int) -> Data {
    let ctx = newContext(px, px)
    ctx.scaleBy(x: CGFloat(px) / 1024, y: CGFloat(px) / 1024)
    ctx.translateBy(x: C.x, y: C.y); ctx.scaleBy(x: 1.45, y: 1.45); ctx.translateBy(x: -C.x, y: -C.y - 12)
    drawWaferFlat(ctx)
    return png(ctx)
}

/// The mark alone in one flat colour on a transparent background: the wafer with its lines and the lit die cut out.
func renderMono(_ px: Int, _ color: CGColor) -> Data {
    let ctx = newContext(px, px)
    ctx.scaleBy(x: CGFloat(px) / 1024, y: CGFloat(px) / 1024)
    ctx.translateBy(x: C.x, y: C.y); ctx.scaleBy(x: 1.45, y: 1.45); ctx.translateBy(x: -C.x, y: -C.y - 12)
    ctx.addPath(waferPath()); ctx.setFillColor(color); ctx.fillPath()
    ctx.setBlendMode(.clear)
    for r in scribeRects() { ctx.fill(r) }
    ctx.fill(lit.insetBy(dx: -line / 2, dy: -line / 2))
    return png(ctx)
}

func wordmark(_ size: CGFloat, _ color: NSColor) -> NSAttributedString {
    let base = NSFont.systemFont(ofSize: size, weight: .semibold)
    let font = NSFont(descriptor: base.fontDescriptor.withDesign(.rounded) ?? base.fontDescriptor, size: size) ?? base
    return NSAttributedString(string: "Silica", attributes: [.font: font, .foregroundColor: color, .kern: -size * 0.035])
}

func renderLockup(onDark: Bool) -> Data {
    let h = 360, markSize: CGFloat = 290, gap: CGFloat = 34
    let text = wordmark(190, onDark ? .white : NSColor(red: 0.07, green: 0.11, blue: 0.13, alpha: 1))
    let w = Int(34 + markSize + gap + text.size().width + 44)
    let ctx = newContext(w, h)
    ctx.saveGState()
    ctx.translateBy(x: 34, y: (CGFloat(h) - markSize) / 2)
    ctx.scaleBy(x: markSize / 1024, y: markSize / 1024)
    ctx.translateBy(x: C.x, y: C.y); ctx.scaleBy(x: 1.55, y: 1.55); ctx.translateBy(x: -C.x, y: -C.y - 12)
    drawWaferFlat(ctx)
    ctx.restoreGState()
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(cgContext: ctx, flipped: false)
    text.draw(at: NSPoint(x: 34 + markSize + gap, y: (CGFloat(h) - text.size().height) / 2 + 4))
    NSGraphicsContext.restoreGraphicsState()
    return png(ctx)
}

/// 1280 x 640, GitHub's social preview size.
func renderBanner() -> Data {
    let w = 1280, h = 640
    let ctx = newContext(w, h)
    ctx.drawLinearGradient(gradient([col(0.13, 0.18, 0.205), col(0.04, 0.06, 0.075)], [0, 1]), start: P(x: 0, y: CGFloat(h)), end: P(x: CGFloat(w) * 0.45, y: 0), options: [.drawsAfterEndLocation])
    ctx.drawRadialGradient(gradient([col(0.14, 0.75, 0.75, 0.20), col(0.14, 0.75, 0.75, 0)], [0, 1]), startCenter: P(x: 300, y: 320), startRadius: 0, endCenter: P(x: 300, y: 320), endRadius: 440, options: [])
    ctx.saveGState()
    ctx.translateBy(x: 100, y: 120); ctx.scaleBy(x: 400.0 / 1024, y: 400.0 / 1024)
    ctx.translateBy(x: C.x, y: C.y); ctx.scaleBy(x: 1.30, y: 1.30); ctx.translateBy(x: -C.x, y: -C.y)
    drawWafer(ctx)
    ctx.restoreGState()
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(cgContext: ctx, flipped: false)
    wordmark(168, .white).draw(at: NSPoint(x: 566, y: 300))
    let sub = NSFont.systemFont(ofSize: 33, weight: .medium)
    NSAttributedString(string: "Minecraft Beta 1.7.3,", attributes: [.font: sub, .foregroundColor: NSColor(white: 1, alpha: 0.72)]).draw(at: NSPoint(x: 574, y: 232))
    NSAttributedString(string: "native on Apple Silicon.", attributes: [.font: sub, .foregroundColor: NSColor(red: 0.30, green: 0.85, blue: 0.78, alpha: 1)]).draw(at: NSPoint(x: 574, y: 186))
    NSGraphicsContext.restoreGraphicsState()
    return png(ctx)
}

func svgMark() -> String {
    func f(_ v: CGFloat) -> String { String(format: "%.1f", v) }
    let ax = C.x + R * sin(theta), bx = C.x - R * sin(theta), fy = 1024 - (C.y - R * flatCut)
    var d = "M\(f(ax)) \(f(fy)) A\(f(R)) \(f(R)) 0 1 0 \(f(bx)) \(f(fy)) Z"
    for r in scribeRects() + [lit.insetBy(dx: -line / 2, dy: -line / 2)] {
        d += " M\(f(r.minX)) \(f(1024 - r.maxY)) h\(f(r.width)) v\(f(r.height)) h-\(f(r.width)) Z"
    }
    return """
    <svg xmlns="http://www.w3.org/2000/svg" viewBox="180 170 664 664" role="img" aria-label="Silica">
      <defs><clipPath id="w"><path d="M\(f(ax)) \(f(fy)) A\(f(R)) \(f(R)) 0 1 0 \(f(bx)) \(f(fy)) Z"/></clipPath></defs>
      <path clip-path="url(#w)" fill="currentColor" fill-rule="evenodd" d="\(d)"/>
    </svg>

    """
}

let out = CommandLine.arguments[1]
try? FileManager.default.createDirectory(atPath: out, withIntermediateDirectories: true)
let mark = URL(fileURLWithPath: out).deletingLastPathComponent().appendingPathComponent("SilicaMark.png")
try! renderMark(512).write(to: mark)
print("wrote brand mark to \(mark.path)")
for (name, px) in [("icon_16x16", 16), ("icon_16x16@2x", 32), ("icon_32x32", 32), ("icon_32x32@2x", 64), ("icon_128x128", 128), ("icon_128x128@2x", 256),
                   ("icon_256x256", 256), ("icon_256x256@2x", 512), ("icon_512x512", 512), ("icon_512x512@2x", 1024)] {
    try! renderIcon(px).write(to: URL(fileURLWithPath: out + "/" + name + ".png"))
}
print("wrote iconset to \(out)")
if CommandLine.arguments.count > 2 {
    let assets = CommandLine.arguments[2]
    try? FileManager.default.createDirectory(atPath: assets, withIntermediateDirectories: true)
    func save(_ d: Data, _ n: String) { try! d.write(to: URL(fileURLWithPath: assets + "/" + n)) }
    save(renderIcon(1024), "icon.png")
    save(renderMono(1024, col(0.07, 0.11, 0.13)), "mark-black.png")
    save(renderMono(1024, col(1, 1, 1)), "mark-white.png")
    save(Data(svgMark().utf8), "mark.svg")
    save(renderLockup(onDark: true), "lockup-on-dark.png")
    save(renderLockup(onDark: false), "lockup-on-light.png")
    save(renderBanner(), "banner.png")
    print("wrote icon, mark (black, white, svg), lockups and banner to \(assets)")
}
