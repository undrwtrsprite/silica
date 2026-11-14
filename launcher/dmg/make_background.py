"""Draws the installer window background (660x400 points) as dmg/background.tiff with a 2x Retina version.
Run with the launcher/.dmgtools virtualenv (it needs Pillow): .dmgtools/bin/python dmg/make_background.py
Icon positions are set in make_dmg.sh and must match the arrow here: app at (170,185), Applications at (490,185)."""
import subprocess, os
from PIL import Image, ImageDraw, ImageFont, ImageFilter

HERE = os.path.dirname(os.path.abspath(__file__))
W, H = 660, 400
INK, MUTED, GREEN = (20, 32, 12), (95, 108, 96), (126, 170, 70)

def font(size, bold=False):
    f = ImageFont.truetype("/System/Library/Fonts/SFNS.ttf", size)
    try: f.set_variation_by_name("Bold" if bold else "Regular")
    except Exception: pass
    return f

def draw(scale):
    w, h = W * scale, H * scale
    img = Image.new("RGB", (w, h), (244, 247, 241))
    # a soft green glow in the top left, a pale one bottom right
    glow = Image.new("RGB", (w, h), (244, 247, 241))
    g = ImageDraw.Draw(glow)
    g.ellipse([-w * .25, -h * .6, w * .55, h * .45], fill=(214, 235, 184))
    g.ellipse([w * .55, h * .7, w * 1.2, h * 1.5], fill=(226, 238, 210))
    img = glow.filter(ImageFilter.GaussianBlur(70 * scale))
    d = ImageDraw.Draw(img)
    S = lambda v: int(v * scale)

    title = "Drag Silica into Applications"
    f = font(S(22), True)
    tw = d.textlength(title, font=f)
    d.text(((w - tw) / 2, S(34)), title, font=f, fill=INK)
    sub = "Minecraft Beta 1.7.3, native on Apple Silicon"
    f2 = font(S(13))
    sw = d.textlength(sub, font=f2)
    d.text(((w - sw) / 2, S(68)), sub, font=f2, fill=MUTED)

    # arrow between the two icons
    y = S(185); x0, x1 = S(262), S(398)
    d.line([(x0, y), (x1 - S(12), y)], fill=GREEN, width=S(4))
    d.polygon([(x1 - S(20), y - S(13)), (x1, y), (x1 - S(20), y + S(13))], fill=GREEN)
    # a dotted tail so it reads as motion
    for i in range(3):
        cx = x0 - S(14) - i * S(11)
        r = S(2.5)
        d.ellipse([cx - r, y - r, cx + r, y + r], fill=(*GREEN,))

    note = "macOS asks you to confirm the first open:  System Settings  ›  Privacy & Security  ›  Open Anyway"
    f3 = font(S(11.5))
    nw = d.textlength(note, font=f3)
    d.text(((w - nw) / 2, S(352)), note, font=f3, fill=MUTED)
    return img

draw(1).save(os.path.join(HERE, "background.png"))
draw(2).save(os.path.join(HERE, "background@2x.png"))
subprocess.run(["tiffutil", "-cathidpicheck", os.path.join(HERE, "background.png"), os.path.join(HERE, "background@2x.png"),
                "-out", os.path.join(HERE, "background.tiff")], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
os.remove(os.path.join(HERE, "background.png")); os.remove(os.path.join(HERE, "background@2x.png"))
print("wrote dmg/background.tiff")
