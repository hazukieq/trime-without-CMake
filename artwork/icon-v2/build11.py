#!/usr/bin/env python3
"""Round 11 — 客字标 matrix.  The 口 is replaced by a G drawn to the 口's exact
box.  Weight decides whether the G has a counter to read: Heavy/Bold turn the
口's window into a slot, so Medium/Regular are the candidates.

Also tests the white-paper / black-ink minimal treatment.
"""
import math
import os

import cairosvg
from PIL import Image, ImageDraw, ImageFont

import build8

HERE = os.path.dirname(os.path.abspath(__file__))
SVG_DIR, PNG_DIR = os.path.join(HERE, "svg"), os.path.join(HERE, "png")
LABEL = "/home/hazukie/.fonts/sourcehan/SourceHanSansSC-Medium.otf"

FONTS = {
    "heavy": "/home/hazukie/.fonts/sourcehan/SourceHanSansSC-Heavy.otf",
    "bold": "/home/hazukie/.fonts/sourcehan/SourceHanSansSC-Bold.otf",
    "medium": "/home/hazukie/.fonts/sourcehan/SourceHanSansSC-Medium.otf",
    "regular": "/home/hazukie/.fonts/sourcehan/SourceHanSansSC-Regular.otf",
    "normal": "/home/hazukie/.fonts/sourcehan/SourceHanSansSC-Normal.otf",
}

SCHEMES = {
    "indigo": dict(bg1="#2E4E76", bg2="#111F31", fg="#F7F1E4", flat=None),
    "paper": dict(bg1="#FFFFFF", bg2="#FFFFFF", fg="#15171B", flat="#FFFFFF"),
    "cream": dict(bg1="#FBF7ED", bg2="#FBF7ED", fg="#1B1D22", flat="#FBF7ED"),
    "ink": dict(bg1="#1B1E24", bg2="#0A0C10", fg="#F6F2E8", flat=None),
}


def g_round(cx, cy, r, gap=46.0, bar=0.46):
    a = math.radians(gap)
    p1 = (cx + r * math.cos(a), cy - r * math.sin(a))
    p0 = (cx + r, cy)
    return (f"M {p1[0]:.2f} {p1[1]:.2f} A {r:.2f} {r:.2f} 0 1 0 {p0[0]:.2f} {p0[1]:.2f} "
            f"L {p0[0] - bar * r:.2f} {p0[1]:.2f}")


def g_square(cx, cy, hw, hh, corner=3.2, gap_frac=0.34, bar=0.5):
    L, R, T, B = cx - hw, cx + hw, cy - hh, cy + hh
    c = corner
    return (f"M {cx + gap_frac * hw:.2f} {T:.2f} "
            f"L {L + c:.2f} {T:.2f} Q {L:.2f} {T:.2f} {L:.2f} {T + c:.2f} "
            f"L {L:.2f} {B - c:.2f} Q {L:.2f} {B:.2f} {L + c:.2f} {B:.2f} "
            f"L {R - c:.2f} {B:.2f} Q {R:.2f} {B:.2f} {R:.2f} {B - c:.2f} "
            f"L {R:.2f} {T + 0.44 * (B - T):.2f} "
            f"M {R:.2f} {cy:.2f} L {R - bar * hw:.2f} {cy:.2f}")


def compose(weight, glyphtype="round", scheme="paper", fit=74.0, r_pad=0.0,
            w_scale=0.92, gap=46.0, bar=0.46, radius=25.0, shadow=False,
            clip_bias=0.55, out=None):
    f, gs, name, raw = build8.load_glyph(FONTS[weight])
    from fontTools.pens.boundsPen import BoundsPen
    from fontTools.pens.svgPathPen import SVGPathPen
    from fontTools.pens.transformPen import TransformPen
    from fontTools.misc.transform import Transform
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
    k_hw = (cx_(kou["x1"]) - cx_(kou["x0"])) / 2 + r_pad
    k_hh = (cy_(kou["y_bot"]) - cy_(kou["y_top"])) / 2 + r_pad
    stroke = kou["wall"] * s * w_scale

    pen = SVGPathPen(gs)
    gs[name].draw(TransformPen(pen, Transform(s, 0, 0, -s, ox - x0 * s, oy + y1 * s)))
    dglyph = pen.getCommands()

    if glyphtype == "round":
        r = min(k_hw, k_hh) - stroke / 2
        gp = g_round(k_cx, k_cy, r, gap, bar)
    else:
        gp = g_square(k_cx, k_cy, k_hw - stroke / 2, k_hh - stroke / 2,
                      corner=min(k_hw, k_hh) * 0.30, bar=bar)

    sch = SCHEMES[scheme]
    fg = sch["fg"]
    clip_y = k_cy - k_hh * clip_bias
    defs = (f'<defs><linearGradient id="bg" x1="0" y1="0" x2="0.3" y2="1">'
            f'<stop offset="0" stop-color="{sch["bg1"]}"/>'
            f'<stop offset="1" stop-color="{sch["bg2"]}"/></linearGradient>'
            f'<clipPath id="top"><rect x="-14" y="-14" width="136" '
            f'height="{clip_y + 14:.2f}"/></clipPath>')
    if shadow:
        defs += ('<filter id="soft" x="-25%" y="-25%" width="150%" height="150%">'
                 '<feGaussianBlur stdDeviation="1.1"/></filter>')
    defs += "</defs>"
    glyph = f'<g clip-path="url(#top)"><path d="{dglyph}" fill="{fg}"/></g>'
    el = (f'<path d="{gp}" fill="none" stroke="{fg}" stroke-width="{stroke:.2f}" '
          f'stroke-linecap="round" stroke-linejoin="round"/>')
    body = glyph + el
    if shadow:
        body = (f'<g filter="url(#soft)" opacity="0.22" transform="translate(0.7,1.1)">'
                f'{body}</g>{body}')
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" '
           f'viewBox="0 0 108 108">{defs}'
           f'<rect width="108" height="108" rx="{radius}" fill="url(#bg)"/>'
           f'{body}</svg>')
    if out:
        open(os.path.join(SVG_DIR, f"{out}.svg"), "w").write(svg)
        for sz in (512, 144, 72, 48):
            cairosvg.svg2png(bytestring=svg.encode(),
                             write_to=os.path.join(PNG_DIR, f"{out}-{sz}.png"),
                             output_width=sz, output_height=sz)
    return svg


CANDS = {
    "b1-medium-round": dict(weight="medium", glyphtype="round", scheme="paper"),
    "b2-bold-round": dict(weight="bold", glyphtype="round", scheme="paper"),
    "b3-medium-square": dict(weight="medium", glyphtype="square", scheme="paper"),
    "b4-medium-round-indigo": dict(weight="medium", glyphtype="round",
                                   scheme="indigo", shadow=True),
    "b5-regular-round": dict(weight="regular", glyphtype="round", scheme="paper"),
    "b6-medium-round-pad": dict(weight="medium", glyphtype="round", scheme="paper",
                                r_pad=1.6, w_scale=0.86),
}


def main():
    names = []
    for n, kw in CANDS.items():
        compose(out=n, **kw)
        names.append(n)
        print("built", n)
    W, pad, cols = 420, 34, 3
    rows = (len(names) + cols - 1) // cols
    sheet = Image.new("RGB", (W * cols, (W + 50) * rows), (235, 235, 235))
    dr = ImageDraw.Draw(sheet)
    font = ImageFont.truetype(LABEL, 22)
    for i, n in enumerate(names):
        im = Image.open(os.path.join(PNG_DIR, f"{n}-512.png")).convert("RGB")
        im.thumbnail((W - 2 * pad, W - 2 * pad), Image.LANCZOS)
        x = (i % cols) * W + (W - im.width) // 2
        y = (i // cols) * (W + 50) + pad
        sheet.paste(im, (x, y))
        dr.text(((i % cols) * W + pad, y + im.height + 4), n, fill=(40, 40, 40),
                font=font)
    sheet.save(os.path.join(PNG_DIR, "contact11.png"))
    print("contact11 done")


if __name__ == "__main__":
    main()
