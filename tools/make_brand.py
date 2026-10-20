"""Draws LumaTV's app icon and TV banner from the Flora line mark.

Usage: python3 tools/make_brand.py   (needs Pillow and rsvg-convert)
The mark's paths are the final frame of the opening animation (Opening.java uses the same paths).
"""
import io
import os
import subprocess

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONT = os.path.join(ROOT, "app/assets/fonts/InterVariable.ttf")
INK = "#F5F3EF"
GROUND = (20, 16, 15)

OUTLINE = ("M137.1 370.4 Q114.9 376.7 111.8 365.9 Q86.0 369.5 82.6 358.1 Q63.7 353.7 68.3 337.9 Q52.6 330.2 65.7 313.1 "
           "Q52.8 303.2 62.2 289.4 Q50.9 278.0 66.1 265.7 Q54.2 252.9 71.5 243.1 Q63.4 229.3 77.2 221.0 Q71.3 206.5 90.0 201.9 "
           "Q84.0 185.8 103.1 184.2 Q101.4 169.1 119.0 169.2 Q120.6 155.8 137.4 158.1 Q138.4 141.5 151.0 140.9 Q152.9 121.1 167.8 125.9 "
           "Q173.9 110.3 188.6 118.2 Q196.8 102.3 209.8 109.2 Q220.5 97.0 232.6 105.6 Q244.9 94.2 255.4 109.2 Q268.3 103.0 276.6 118.2 "
           "Q291.9 108.6 297.4 125.9 Q312.0 121.7 314.2 140.9 Q327.8 140.0 327.8 158.1 Q344.2 156.2 346.2 169.2 Q365.2 168.1 362.1 184.2 "
           "Q379.9 186.6 375.2 201.9 Q393.0 206.9 388.0 221.0 Q401.4 229.4 393.7 243.1 Q411.3 252.8 399.1 265.7 Q415.2 278.0 403.0 289.4 "
           "Q413.8 303.5 399.5 313.1 Q409.5 329.2 396.9 337.9 Q404.1 355.0 382.6 358.1 Q382.8 371.9 353.4 365.9 Q350.9 377.2 328.2 370.4")
EAR_L = ("M142.9 176.3 Q148.8 184.8 139.0 193.3 Q144.9 201.7 135.1 210.2 Q141.0 218.7 131.2 227.2 Q137.1 235.7 127.3 244.1 "
         "Q133.2 252.6 123.4 261.1 Q132.4 269.9 125.4 278.8 Q134.3 287.6 127.3 296.4 Q136.3 305.2 129.3 314.1 Q138.2 322.9 131.2 331.7 "
         "Q140.2 340.5 133.2 349.4 Q142.1 358.2 135.1 367.0")
EAR_R = ("M322.3 168.4 Q316.4 176.9 326.2 185.4 Q320.3 193.9 330.1 202.4 Q324.2 210.8 334.0 219.3 Q328.1 227.8 337.9 236.3 "
         "Q332.0 244.8 341.8 253.2 Q332.2 262.7 339.8 272.2 Q330.3 281.7 337.9 291.2 Q328.3 300.6 335.9 310.1 Q326.4 319.6 334.0 329.1 "
         "Q324.4 338.6 332.0 348.0 Q322.5 357.5 330.1 367.0")
CURLS = ["M166.3 159.2 a12.7 12.7 0 1 1 17.7 11.4", "M205.3 176.8 a12.7 12.7 0 1 1 17.7 11.4",
         "M244.3 159.2 a12.7 12.7 0 1 1 17.7 11.4", "M278.4 176.8 a12.7 12.7 0 1 1 17.7 11.4"]
EYES = ["M178 243 Q190 230 202 243", "M264 243 Q276 230 288 243"]
NOSE = "M219 276 Q233 269 247 276 Q250 285 240 292 Q233 297 226 292 Q216 285 219 276 Z"
MOUTH = "M211 305 Q222 315 233 307 Q244 315 255 305"
TONGUE = "M226 309 Q233 330 240 309 Z"
VIEWBOX = (40, 85, 385, 300)


def mark_svg(stroke):
    line = f'fill="none" stroke="{INK}" stroke-linecap="round" stroke-linejoin="round"'
    body = (f'<path d="{OUTLINE}" {line} stroke-width="{stroke}"/><path d="{EAR_L}" {line} stroke-width="{stroke}"/>'
            f'<path d="{EAR_R}" {line} stroke-width="{stroke}"/>'
            + "".join(f'<path d="{c}" {line} stroke-width="{stroke * 0.8}"/>' for c in CURLS)
            + "".join(f'<path d="{e}" {line} stroke-width="{stroke * 1.1}"/>' for e in EYES)
            + f'<path d="{NOSE}" fill="{INK}"/><path d="{MOUTH}" {line} stroke-width="{stroke * 0.9}"/>'
            + f'<path d="{TONGUE}" fill="#F29C9C"/>')
    x, y, w, h = VIEWBOX
    return f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{x} {y} {w} {h}">{body}</svg>'


def mark(width, stroke):
    png = subprocess.run(["rsvg-convert", "-w", str(width)], input=mark_svg(stroke).encode(), capture_output=True, check=True).stdout
    return Image.open(io.BytesIO(png)).convert("RGBA")


def font(size, weight):
    f = ImageFont.truetype(FONT, size)
    try:
        f.set_variation_by_axes([32, weight])
    except Exception:
        f.set_variation_by_axes([weight])
    return f


def ground(w, h):
    img = Image.new("RGBA", (w, h), GROUND + (255,))
    glow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(glow)
    for i in range(24, 0, -1):
        a = int(3 * (25 - i))
        r = int(w * 0.03 * i)
        d.ellipse([w * 0.3 - r, h * 0.55 - r, w * 0.3 + r, h * 0.55 + r], fill=(255, 170, 110, max(0, min(255, a // 6))))
    return Image.alpha_composite(img, glow)


def banner(w, h):
    img = ground(w, h)
    m = mark(int(h * 0.50 * 385 / 300), stroke=4.6)
    d = ImageDraw.Draw(img)
    size = int(h * 0.21)
    luma, tv = font(size, 600), font(size, 300)
    gap = int(h * 0.08)
    total = m.width + gap + d.textlength("Luma", font=luma) + d.textlength("TV", font=tv)
    mx, my = int((w - total) / 2), (h - m.height) // 2
    img.alpha_composite(m, (mx, my))
    x = mx + m.width + gap
    top = (h - size) // 2 - int(size * 0.12)
    d.text((x, top), "Luma", font=luma, fill=INK)
    x += d.textlength("Luma", font=luma)
    d.text((x, top), "TV", font=tv, fill=(245, 243, 239, 180))
    return img


def icon(size):
    img = ground(size, size)
    m = mark(int(size * 0.82), stroke=5.4 if size <= 192 else 4.4)
    img.alpha_composite(m, ((size - m.width) // 2, (size - m.height) // 2 + int(size * 0.02)))
    return img


def main():
    res = os.path.join(ROOT, "app/res/drawable-nodpi")
    banner(640, 360).convert("RGB").save(os.path.join(res, "banner.png"), optimize=True)
    icon(192).convert("RGB").save(os.path.join(res, "icon.png"), optimize=True)
    icon(512).convert("RGB").save(os.path.join(ROOT, "docs/assets/icon-512.png"), optimize=True)
    print("banner.png, icon.png, docs/assets/icon-512.png")


if __name__ == "__main__":
    main()
