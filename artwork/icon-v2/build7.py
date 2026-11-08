#!/usr/bin/env python3
"""Round 7 — type-mark: the real 客 glyph with its 口 swapped for a G.

The 口 of 客 sits at a known place in the font, so we clip the glyph at the
口's top edge and drop a G ring (same weight, same footprint) in its place.
Result: still reads 客, but the 口 is a G.
"""
import math
import os

import cairosvg
from PIL import Image
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen

HERE = os.path.dirname(os.path.abspath(__file__))
SVG_DIR, PNG_DIR = os.path.join(HERE, "svg"), os.path.join(HERE, "png")

FONTS = {
    "heavy": "/home/hazukie/.fonts/sourcehan/SourceHanSansSC-Heavy.otf",
    "bold": "/home/hazukie/.fonts/sourcehan/SourceHanSansSC-Bold.otf",
    "medium": "/home/hazukie/.fonts/sourcehan/SourceHanSansSC-Medium.otf",
}
BG1, BG2, FG = "#2E4E76", "#111F31", "#F7F1E4"


def glyph_path(font_path, ch="客"):
    f = TTFont(font_path)
    gs = f.getGlyphSet()
    name = f.getBestCmap()[ord(ch)]
    pen = SVGPathPen(gs)
    gs[name].draw(pen)
    return pen.getCommands(), f["head"].unitsPerEm


def g_ring_path(cx, cy, half_w, half_h, stroke, gap=0.55, bar=0.52, corner=10.0):
    """G as a stroked rounded-rect ring (square 口 counter)."""
    L, R = cx - half_w, cx + half_w
    T, B = cy - half_h, cy + half_h
    c = corner
    # ring: start just right of the gap on the top edge, go CCW around, and end
    # at the right edge above the bar, then the bar runs inward
    start_x = cx + (gap - 0.5) * half_w * 2 * 0.55
    d = (f"M {start_x:.2f} {T:.2f} "
         f"L {L + c:.2f} {T:.2f} Q {L:.2f} {T:.2f} {L:.2f} {T + c:.2f} "
         f"L {L:.2f} {B - c:.2f} Q {L:.2f} {B:.2f} {L + c:.2f} {B:.2f} "
         f"L {R - c:.2f} {B:.2f} Q {R:.2f} {B:.2f} {R:.2f} {B - c:.2f} "
         f"L {R:.2f} {cy - 0.42 * half_h:.2f} "
         f"M {R:.2f} {cy:.2f} L {cx + (1 - bar) * half_w:.2f} {cy:.2f}")
    return d


def build(weight, out, scale_fit=76.0, g_scale=1.0, two_tone=False):
    d, upem = glyph_path(FONTS[weight])
    # 客 bbox in font units: x 22..980, y -95..869
    x0, x1, y0, y1 = 22, 980, -95, 869
    s = scale_fit / (y1 - y0)
    gw, gh = (x1 - x0) * s, (y1 - y0) * s
    ox = 54 - gw / 2
    oy = 54 - gh / 2
    to_canvas = (f"translate({ox - x0 * s:.3f},{oy + y1 * s:.3f}) "
                 f"scale({s:.6f},{-s:.6f})")
    # 口 in font units (measured from Source Han Sans SC Heavy/Bold/Medium alike)
    kou = dict(x0=204, x1=801, y_bot=-95, y_top=215, inner_y0=52, inner_y1=104,
               wall=140)
    kx0, kx1 = 204, 801
    ky_top = 215
    # canvas coords of the 口
    def cx_of(fx):
        return ox + (fx - x0) * s

    def cy_of(fy):
        return oy + (y1 - fy) * s

    k_cx = (cx_of(kx0) + cx_of(kx1)) / 2
    k_cy = (cy_of(kou["y_bot"]) + cy_of(ky_top)) / 2
    k_half_w = (cx_of(kx1) - cx_of(kx0)) / 2
    k_half_h = (cy_of(kou["y_bot"]) - cy_of(ky_top)) / 2
    stroke = (cx_of(345) - cx_of(kx0))
    clip_y = cy_of(ky_top)
    pal_fg = FG
    roof_col = "#E8BE79"
    defs = (f'<defs><linearGradient id="bg" x1="0" y1="0" x2="0.3" y2="1">'
            f'<stop offset="0" stop-color="{BG1}"/><stop offset="1" stop-color="{BG2}"/>'
            f'</linearGradient>'
            f'<clipPath id="top"><rect x="-10" y="-10" width="128" height="{clip_y + 10:.2f}"/>'
            f'</clipPath>'
            f'<filter id="soft" x="-25%" y="-25%" width="150%" height="150%">'
            f'<feGaussianBlur stdDeviation="1.1"/></filter></defs>')
    glyph = (f'<g transform="{to_canvas}" clip-path="url(#top)">'
             f'<path d="{d}" fill="{pal_fg}"/></g>')
    gp = g_ring_path(k_cx, k_cy, k_half_w, k_half_h, stroke, gap=0.5, bar=0.5,
                     corner=8.0)
    gsvg = (f'<path d="{gp}" fill="none" stroke="{pal_fg}" stroke-width="{stroke:.2f}" '
            f'stroke-linecap="butt" stroke-linejoin="round"/>')
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" '
           f'viewBox="0 0 108 108">{defs}'
           f'<rect width="108" height="108" rx="25" fill="url(#bg)"/>'
           f'<g filter="url(#soft)" opacity="0.28" transform="translate(0.6,1.2)">'
           f'{glyph}{gsvg}</g>{glyph}{gsvg}</svg>')
    open(os.path.join(SVG_DIR, f"{out}.svg"), "w").write(svg)
    png = os.path.join(PNG_DIR, f"{out}-512.png")
    cairosvg.svg2png(bytestring=svg.encode(), write_to=png,
                     output_width=512, output_height=512)
    for s2 in (144, 72, 48):
        cairosvg.svg2png(bytestring=svg.encode(),
                         write_to=os.path.join(PNG_DIR, f"_{out}_{s2}.png"),
                         output_width=s2, output_height=s2)
    print(f"built {out}: glyph scale={s:.4f} kou=({k_cx:.1f},{k_cy:.1f}) "
          f"half=({k_half_w:.1f},{k_half_h:.1f}) stroke={stroke:.2f} clip_y={clip_y:.2f}")
    return png


def main():
    names = []
    for w in ("heavy", "bold", "medium"):
        names.append(w)
        build(w, f"t-{w}")
    cell = 300
    master = Image.new("RGB", (cell * len(names), cell), (248, 248, 248))
    for i, n in enumerate(names):
        im = Image.open(os.path.join(PNG_DIR, f"t-{n}-512.png")).convert("RGB")
        im.thumbnail((cell - 16, cell - 16), Image.LANCZOS)
        master.paste(im, (i * cell + 8, 8))
    master.save(os.path.join(PNG_DIR, "contact7.png"))
    print("contact7 done")


if __name__ == "__main__":
    main()
