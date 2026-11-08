#!/usr/bin/env python3
"""Round 8 — type-mark, done properly.

1. Rasterise 客 (1 px per font unit) and find its enclosed white holes.
2. The lowest hole in the bottom third is the 口 counter -> measure the 口's
   outer box and wall thickness from scanlines through that hole.
3. Bake the glyph transform into the path, clip the glyph at the 口's top edge,
   and drop a G ring with the same footprint and weight in its place.
"""
import io
import math
import os

import cairosvg
import numpy as np
from PIL import Image
from fontTools.misc.transform import Transform
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont
from scipy import ndimage

HERE = os.path.dirname(os.path.abspath(__file__))
SVG_DIR, PNG_DIR = os.path.join(HERE, "svg"), os.path.join(HERE, "png")

FONTS = {
    "heavy": "/home/hazukie/.fonts/sourcehan/SourceHanSansSC-Heavy.otf",
    "bold": "/home/hazukie/.fonts/sourcehan/SourceHanSansSC-Bold.otf",
    "medium": "/home/hazukie/.fonts/sourcehan/SourceHanSansSC-Medium.otf",
}

PALETTES = {
    "indigo": dict(bg1="#2E4E76", bg2="#111F31", fg="#F7F1E4"),
    "seal": dict(bg1="#C74734", bg2="#8B2317", fg="#FFF9EE"),
    "ink": dict(bg1="#23262C", bg2="#0B0D11", fg="#F5F2EA"),
    "paper": dict(bg1="#FCF9F1", bg2="#EDE5D4", fg="#23262B"),
}

# ---------------------------------------------------------------- glyph utils
def load_glyph(font_path, ch="客"):
    f = TTFont(font_path)
    gs = f.getGlyphSet()
    name = f.getBestCmap()[ord(ch)]
    pen = SVGPathPen(gs)
    gs[name].draw(pen)
    return f, gs, name, pen.getCommands()


def rasterise(d, x0, x1, y0, y1, pad=60):
    """White bg, black glyph, 1 px per font unit. Origin offset so all coords > 0."""
    w = int(x1 - x0 + 2 * pad)
    h = int(y1 - y0 + 2 * pad)
    tx, ty = -x0 + pad, y1 + pad          # font (x0,y1) -> (pad, pad)
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" '
           f'viewBox="0 0 {w} {h}"><rect width="{w}" height="{h}" fill="#fff"/>'
           f'<g transform="translate({tx:.2f},{ty:.2f}) scale(1,-1)">'
           f'<path d="{d}" fill="#000"/></g></svg>')
    png = cairosvg.svg2png(bytestring=svg.encode(), output_width=w, output_height=h)
    im = np.array(Image.open(io.BytesIO(png)).convert("L"))
    return im < 128, (tx, ty)


def find_kou(ink, tx, ty):
    """Locate the 口: the enclosed hole with the lowest centre in the lower half."""
    holes = ndimage.binary_fill_holes(ink) & ~ink
    lab, n = ndimage.label(holes)
    best = None
    H = ink.shape[0]
    for i in range(1, n + 1):
        ys, xs = np.where(lab == i)
        if len(ys) < 40:
            continue
        cy = ys.mean()
        if cy < H * 0.45:                 # 口 lives in the bottom part
            continue
        area = len(ys)
        cand = (cy, i, xs.min(), xs.max(), ys.min(), ys.max(), area)
        if best is None or cand[0] > best[0]:
            best = cand
    if best is None:
        raise RuntimeError("no 口 hole found")
    _, _, ix0, ix1, iy0, iy1, area = best
    cx_px, cy_px = (ix0 + ix1) // 2, (iy0 + iy1) // 2

    def runs(vec):
        out, st = [], None
        for k in range(len(vec)):
            if vec[k] and st is None:
                st = k
            elif not vec[k] and st is not None:
                out.append((st, k - 1)); st = None
        if st is not None:
            out.append((st, len(vec) - 1))
        return out

    row = ink[cy_px]
    left = [r for r in runs(row) if r[1] < cx_px]
    right = [r for r in runs(row) if r[0] > cx_px]
    col = ink[:, cx_px]
    top = [r for r in runs(col) if r[1] < cy_px]
    bot = [r for r in runs(col) if r[0] > cy_px]
    if not (left and right and top and bot):
        raise RuntimeError("could not measure 口 walls")
    otx0 = left[-1][0]; otx1 = right[0][1]
    otx_top = top[-1][0]; otx_bot = bot[0][1]
    walls = [(otx0, left[-1][1]), (right[0][0], otx1), (top[-1][0], top[-1][1]),
             (bot[0][0], otx_bot)]
    wall = float(np.mean([b - a + 1 for a, b in walls]))
    # pixel -> font units
    fx0 = otx0 - tx
    fx1 = otx1 - tx
    fy_bot = ty - otx_bot
    fy_top = ty - otx_top
    return dict(x0=fx0, x1=fx1, y_bot=fy_bot, y_top=fy_top, wall=wall), best


def g_ring(cx, cy, half_w, half_h, stroke, gap_frac=0.46, bar=0.5, corner=None):
    """G as a stroked rounded rect: gap on the upper right, bar at mid height."""
    L, R, T, B = cx - half_w, cx + half_w, cy - half_h, cy + half_h
    c = corner if corner is not None else min(half_w, half_h) * 0.34
    gap_x = cx + gap_frac * half_w      # where the top edge stops
    shoulder_y = T + (B - T) * 0.42     # where the right edge resumes below the gap
    start = (gap_x, T)
    return (f"M {start[0]:.2f} {start[1]:.2f} "
            f"L {L + c:.2f} {T:.2f} Q {L:.2f} {T:.2f} {L:.2f} {T + c:.2f} "
            f"L {L:.2f} {B - c:.2f} Q {L:.2f} {B:.2f} {L + c:.2f} {B:.2f} "
            f"L {R - c:.2f} {B:.2f} Q {R:.2f} {B:.2f} {R:.2f} {B - c:.2f} "
            f"L {R:.2f} {shoulder_y:.2f} "
            f"M {R:.2f} {cy:.2f} L {R - bar * half_w:.2f} {cy:.2f}")


def build(weight, out, palname="indigo", fit=76.0, gap_frac=0.46, bar=0.5,
          radius=25.0, clip_extra=1.0):
    font_path = FONTS[weight]
    f, gs, name, raw = load_glyph(font_path)
    upem = f["head"].unitsPerEm
    # glyph bbox
    xs, ys = [], []
    from fontTools.pens.boundsPen import BoundsPen
    bp = BoundsPen(gs); gs[name].draw(bp)
    x0, y0, x1, y1 = bp.bounds
    s = fit / (y1 - y0)
    gw, gh = (x1 - x0) * s, (y1 - y0) * s
    ox, oy = 54 - gw / 2, 54 - gh / 2
    # measure 口 on a 1:1 raster
    ink, (tx, ty) = rasterise(raw, x0, x1, y0, y1)
    kou, hole = find_kou(ink, tx, ty)
    # canvas coords of 口
    def cx_(fx):
        return ox + (fx - x0) * s
    def cy_(fy):
        return oy + (y1 - fy) * s
    k_cx = (cx_(kou["x0"]) + cx_(kou["x1"])) / 2
    k_cy = (cy_(kou["y_bot"]) + cy_(kou["y_top"])) / 2
    k_hw = (cx_(kou["x1"]) - cx_(kou["x0"])) / 2
    k_hh = (cy_(kou["y_bot"]) - cy_(kou["y_top"])) / 2
    stroke = kou["wall"] * s
    clip_y = cy_(kou["y_top"]) + clip_extra

    # bake transform into the path so clip rects live in canvas space
    pen = SVGPathPen(gs)
    tpen = TransformPen(pen, Transform(s, 0, 0, -s, ox - x0 * s, oy + y1 * s))
    gs[name].draw(tpen)
    d_canvas = pen.getCommands()

    pal = PALETTES[palname]
    gp = g_ring(k_cx, k_cy, k_hw, k_hh, stroke, gap_frac, bar)
    defs = (f'<defs><linearGradient id="bg" x1="0" y1="0" x2="0.3" y2="1">'
            f'<stop offset="0" stop-color="{pal["bg1"]}"/>'
            f'<stop offset="1" stop-color="{pal["bg2"]}"/></linearGradient>'
            f'<clipPath id="top"><rect x="-12" y="-12" width="132" '
            f'height="{clip_y + 12:.2f}"/></clipPath>'
            f'<filter id="soft" x="-25%" y="-25%" width="150%" height="150%">'
            f'<feGaussianBlur stdDeviation="1.0"/></filter></defs>')
    glyph = (f'<g clip-path="url(#top)"><path d="{d_canvas}" fill="{pal["fg"]}"/></g>')
    gsvg = (f'<path d="{gp}" fill="none" stroke="{pal["fg"]}" '
            f'stroke-width="{stroke:.2f}" stroke-linejoin="round" '
            f'stroke-linecap="butt"/>')
    # paint order: glyph, then the G (covers the cut), shadow underneath
    body = (f'<g filter="url(#soft)" opacity="0.30" transform="translate(0.8,1.4)">'
            f'{glyph}{gsvg}</g>{glyph}{gsvg}')
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" '
           f'viewBox="0 0 108 108">{defs}'
           f'<rect width="108" height="108" rx="{radius}" fill="url(#bg)"/>'
           f'{body}</svg>')
    open(os.path.join(SVG_DIR, f"{out}.svg"), "w").write(svg)
    cairosvg.svg2png(bytestring=svg.encode(),
                     write_to=os.path.join(PNG_DIR, f"{out}-512.png"),
                     output_width=512, output_height=512)
    for sz in (144, 72, 48):
        cairosvg.svg2png(bytestring=svg.encode(),
                         write_to=os.path.join(PNG_DIR, f"_{out}_{sz}.png"),
                         output_width=sz, output_height=sz)
    print(f"{out}: kou=({k_cx:.1f},{k_cy:.1f}) half=({k_hw:.1f},{k_hh:.1f}) "
          f"wall={stroke:.2f} clipY={clip_y:.1f} glyphScale={s:.4f}")
    return out


def main():
    outs = []
    for w in ("heavy", "bold", "medium"):
        outs.append(build(w, f"t8-{w}"))
    outs.append(build("bold", "t8-bold-seal", "seal", radius=19))
    outs.append(build("bold", "t8-bold-paper", "paper"))
    cell = 300
    cols = 5
    master = Image.new("RGB", (cell * cols, cell), (248, 248, 248))
    for i, n in enumerate(outs):
        im = Image.open(os.path.join(PNG_DIR, f"{n}-512.png")).convert("RGB")
        im.thumbnail((cell - 16, cell - 16), Image.LANCZOS)
        master.paste(im, (i * cell + 8, 8))
    master.save(os.path.join(PNG_DIR, "contact8.png"))
    print("contact8 done")


if __name__ == "__main__":
    main()
