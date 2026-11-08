#!/usr/bin/env python3
"""Round 10 — type-mark with a ROUND G in the 口's slot.

A square 口 swapped for a square G is unreadable at icon size (the font's 口
counter is a thin slot).  A round G — visibly a letter — in the same slot keeps
客 readable and makes the G obvious.
"""
import math
import os

import cairosvg
from PIL import Image, ImageDraw, ImageFont

import build8

HERE = os.path.dirname(os.path.abspath(__file__))
SVG_DIR, PNG_DIR = os.path.join(HERE, "svg"), os.path.join(HERE, "png")
LABEL_FONT = "/home/hazukie/.fonts/sourcehan/SourceHanSansSC-Medium.otf"

PAL = {
    "indigo": dict(bg1="#2E4E76", bg2="#111F31", fg="#F7F1E4"),
    "seal": dict(bg1="#C74734", bg2="#8B2317", fg="#FFF9EE"),
    "ink": dict(bg1="#23262C", bg2="#0B0D11", fg="#F5F2EA"),
    "paper": dict(bg1="#FCF9F1", bg2="#EDE5D4", fg="#23262B"),
}


def round_g(cx, cy, r, gap=48.0, bar=0.55):
    a0 = math.radians(gap)
    p1 = (cx + r * math.cos(a0), cy - r * math.sin(a0))
    p0 = (cx + r, cy)
    return (f"M {p1[0]:.2f} {p1[1]:.2f} A {r:.2f} {r:.2f} 0 1 0 {p0[0]:.2f} {p0[1]:.2f} "
            f"L {p0[0] - bar * r:.2f} {p0[1]:.2f}")


def build(weight, out, palname="indigo", fit=78.0, r_scale=1.06, w_scale=0.80,
          gap=48.0, bar=0.55, radius=25.0):
    font_path = build8.FONTS[weight]
    f, gs, name, raw = build8.load_glyph(font_path)
    from fontTools.pens.boundsPen import BoundsPen
    bp = BoundsPen(gs); gs[name].draw(bp)
    x0, y0, x1, y1 = bp.bounds
    s = fit / (y1 - y0)
    gw, gh = (x1 - x0) * s, (y1 - y0) * s
    ox, oy = 54 - gw / 2, 54 - gh / 2
    ink, (tx, ty) = build8.rasterise(raw, x0, x1, y0, y1)
    kou, _ = build8.find_kou(ink, tx, ty)

    cx_ = lambda fx: ox + (fx - x0) * s
    cy_ = lambda fy: oy + (y1 - fy) * s
    k_cx = (cx_(kou["x0"]) + cx_(kou["x1"])) / 2
    k_cy = (cy_(kou["y_bot"]) + cy_(kou["y_top"])) / 2
    k_hw = (cx_(kou["x1"]) - cx_(kou["x0"])) / 2
    k_hh = (cy_(kou["y_bot"]) - cy_(kou["y_top"])) / 2
    r = min(k_hw, k_hh) * r_scale
    stroke = kou["wall"] * s * w_scale

    from fontTools.pens.svgPathPen import SVGPathPen
    from fontTools.pens.transformPen import TransformPen
    from fontTools.misc.transform import Transform
    pen = SVGPathPen(gs)
    gs[name].draw(TransformPen(pen, Transform(s, 0, 0, -s, ox - x0 * s, oy + y1 * s)))
    d = pen.getCommands()

    pal = PAL[palname]
    gp = round_g(k_cx, k_cy, r, gap, bar)
    defs = ('<defs><linearGradient id="bg" x1="0" y1="0" x2="0.3" y2="1">'
            f'<stop offset="0" stop-color="{pal["bg1"]}"/>'
            f'<stop offset="1" stop-color="{pal["bg2"]}"/></linearGradient>'
            f'<clipPath id="top"><rect x="-12" y="-12" width="132" '
            f'height="{k_cy - k_hh * 0.55 + 12:.2f}"/></clipPath></defs>')
    # clip the glyph slightly inside the 口 so the round G replaces it cleanly
    glyph = f'<g clip-path="url(#top)"><path d="{d}" fill="{pal["fg"]}"/></g>'
    gsvg = (f'<path d="{gp}" fill="none" stroke="{pal["fg"]}" '
            f'stroke-width="{stroke:.2f}" stroke-linecap="round" '
            f'stroke-linejoin="round"/>')
    # small connector: the 夂 leg flows into the G's shoulder
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" '
           f'viewBox="0 0 108 108">{defs}'
           f'<rect width="108" height="108" rx="{radius}" fill="url(#bg)"/>'
           f'{glyph}{gsvg}</svg>')
    open(os.path.join(SVG_DIR, f"{out}.svg"), "w").write(svg)
    for sz in (512, 144, 72, 48):
        cairosvg.svg2png(bytestring=svg.encode(),
                         write_to=os.path.join(PNG_DIR, f"{out}-{sz}.png"),
                         output_width=sz, output_height=sz)
    print(f"{out}: r={r:.2f} stroke={stroke:.2f} center=({k_cx:.1f},{k_cy:.1f})")
    return out


def main():
    outs = [build("bold", "rg-bold"),
            build("medium", "rg-medium"),
            build("heavy", "rg-heavy"),
            build("bold", "rg-bold-seal", "seal", radius=19),
            build("bold", "rg-bold-paper", "paper")]
    W, pad = 420, 36
    cols = 3
    rows = (len(outs) + cols - 1) // cols
    sheet = Image.new("RGB", (W * cols, (W + 52) * rows), (247, 247, 247))
    dr = ImageDraw.Draw(sheet)
    font = ImageFont.truetype(LABEL_FONT, 24)
    for i, n in enumerate(outs):
        im = Image.open(os.path.join(PNG_DIR, f"{n}-512.png")).convert("RGB")
        im.thumbnail((W - 2 * pad, W - 2 * pad), Image.LANCZOS)
        x = (i % cols) * W + (W - im.width) // 2
        y = (i // cols) * (W + 52) + pad
        sheet.paste(im, (x, y))
        dr.text(((i % cols) * W + pad, y + im.height + 6), n, fill=(40, 40, 40),
                font=font)
    sheet.save(os.path.join(PNG_DIR, "contact10.png"))
    print("contact10 done")


if __name__ == "__main__":
    main()
