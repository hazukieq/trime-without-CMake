#!/usr/bin/env python3
"""Round 13 — 客字标, clean boolean cut.

Instead of clipping with a <clipPath> (which leaves seams and can't be exported
to an Android VectorDrawable), subtract the 口 region from the glyph path with
skia-pathops, then drop the G in its place.  Output is one filled path + one
stroked path -> ports straight to VectorDrawable.
"""
import math
import os

import cairosvg
import pathops
from fontTools.misc.transform import Transform
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from PIL import Image, ImageDraw, ImageFont

import build8

HERE = os.path.dirname(os.path.abspath(__file__))
SVG_DIR, PNG_DIR = os.path.join(HERE, "svg"), os.path.join(HERE, "png")
LABEL = "/home/hazukie/.fonts/sourcehan/SourceHanSansSC-Medium.otf"

FONTS = {
    "heavy": "/home/hazukie/.fonts/sourcehan/SourceHanSansSC-Heavy.otf",
    "bold": "/home/hazukie/.fonts/sourcehan/SourceHanSansSC-Bold.otf",
    "medium": "/home/hazukie/.fonts/sourcehan/SourceHanSansSC-Medium.otf",
    "normal": "/home/hazukie/.fonts/sourcehan/SourceHanSansSC-Normal.otf",
    "regular": "/home/hazukie/.fonts/sourcehan/SourceHanSansSC-Regular.otf",
}

SCHEMES = {
    "paper":   dict(bg1="#FFFFFF", bg2="#FFFFFF", fg="#16181C", flat=True),
    "cream":   dict(bg1="#FBF7ED", bg2="#F6F0E2", fg="#1A1C21", flat=True),
    "indigo":  dict(bg1="#2E4E76", bg2="#111F31", fg="#F7F1E4", flat=False),
    "ink":     dict(bg1="#1B1E24", bg2="#0A0C10", fg="#F6F2E8", flat=False),
}


def rect_path(x0, y0, x1, y1):
    p = pathops.Path()
    pen = p.getPen()
    pen.moveTo((x0, y0)); pen.lineTo((x1, y0))
    pen.lineTo((x1, y1)); pen.lineTo((x0, y1))
    pen.closePath()
    return p


def path_to_d(p):
    pen = SVGPathPen(None)
    p.draw(pen)
    return pen.getCommands()


def g_round(cx, cy, r, gap=52.0, bar=0.48):
    a = math.radians(gap)
    p1 = (cx + r * math.cos(a), cy - r * math.sin(a))
    p0 = (cx + r, cy)
    return (f"M {p1[0]:.2f} {p1[1]:.2f} A {r:.2f} {r:.2f} 0 1 0 {p0[0]:.2f} {p0[1]:.2f} "
            f"L {p0[0] - bar * r:.2f} {p0[1]:.2f}")


class Mark:
    """客 with its 口 swapped for a G — geometry in a 108x108 box."""

    def __init__(self, weight="medium", fit=74.0, r_pad=1.2, w_scale=0.96,
                 gap=52.0, bar=0.48, x_shift=0.0, y_shift=0.0):
        f, gs, name, raw = build8.load_glyph(FONTS[weight])
        bp = BoundsPen(gs); gs[name].draw(bp)
        x0, y0, x1, y1 = bp.bounds
        s = fit / (y1 - y0)
        gw, gh = (x1 - x0) * s, (y1 - y0) * s
        ox = 54 - gw / 2 + x_shift
        oy = 54 - gh / 2 + y_shift
        tr = Transform(s, 0, 0, -s, ox - x0 * s, oy + y1 * s)
        ink, (tx, ty) = build8.rasterise(raw, x0, x1, y0, y1)
        kou, _ = build8.find_kou(ink, tx, ty)
        cx_ = lambda fx: ox + (fx - x0) * s
        cy_ = lambda fy: oy + (y1 - fy) * s
        self.k_cx = (cx_(kou["x0"]) + cx_(kou["x1"])) / 2
        self.k_cy = (cy_(kou["y_bot"]) + cy_(kou["y_top"])) / 2
        self.k_hw = (cx_(kou["x1"]) - cx_(kou["x0"])) / 2 + r_pad
        self.k_hh = (cy_(kou["y_bot"]) - cy_(kou["y_top"])) / 2 + r_pad
        self.stroke = kou["wall"] * s * w_scale
        self.gap, self.bar = gap, bar

        # glyph path in canvas coords, minus the 口
        p = pathops.Path()
        gs[name].draw(TransformPen(p.getPen(), tr))
        cut = rect_path(self.k_cx - self.k_hw - 0.4, self.k_cy - self.k_hh,
                        self.k_cx + self.k_hw + 0.4, self.k_cy + self.k_hh + 6)
        res = pathops.op(p, cut, pathops.PathOp.DIFFERENCE, fix_winding=True)
        res.simplify()
        self.glyph_d = path_to_d(res)
        r = min(self.k_hw, self.k_hh) - self.stroke / 2
        self.g_d = g_round(self.k_cx, self.k_cy, r, gap, bar)
        self.r = r


def svg_of(m: Mark, scheme="paper", radius=25.0, shadow=False):
    s = SCHEMES[scheme]
    fg = s["fg"]
    defs = (f'<defs><linearGradient id="bg" x1="0" y1="0" x2="0.25" y2="1">'
            f'<stop offset="0" stop-color="{s["bg1"]}"/>'
            f'<stop offset="1" stop-color="{s["bg2"]}"/></linearGradient>')
    if shadow:
        defs += ('<filter id="soft" x="-20%" y="-20%" width="140%" height="140%">'
                 '<feGaussianBlur stdDeviation="0.9"/></filter>')
    defs += "</defs>"
    body = (f'<path d="{m.glyph_d}" fill="{fg}"/>'
            f'<path d="{m.g_d}" fill="none" stroke="{fg}" '
            f'stroke-width="{m.stroke:.2f}" stroke-linecap="round" '
            f'stroke-linejoin="round"/>')
    if shadow:
        body = (f'<g filter="url(#soft)" opacity="0.18" transform="translate(0.6,1.0)">'
                f'{body}</g>{body}')
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" '
            f'viewBox="0 0 108 108">{defs}'
            f'<rect width="108" height="108" rx="{radius}" fill="url(#bg)"/>'
            f'{body}</svg>')


VARIANTS = {
    "d1-mid74": dict(weight="medium", fit=74),
    "d2-mid76": dict(weight="medium", fit=76),
    "d3-reg74": dict(weight="regular", fit=74),
    "d4-nor74": dict(weight="normal", fit=74),
    "d5-mid72": dict(weight="medium", fit=72, r_pad=1.6),
    "d6-mid74p": dict(weight="medium", fit=74, r_pad=2.0, w_scale=1.0),
}


def main():
    names = []
    for n, kw in VARIANTS.items():
        m = Mark(**kw)
        s = svg_of(m, "paper")
        open(os.path.join(SVG_DIR, f"{n}.svg"), "w").write(s)
        for sz in (512, 144, 72, 48):
            cairosvg.svg2png(bytestring=s.encode(),
                             write_to=os.path.join(PNG_DIR, f"{n}-{sz}.png"),
                             output_width=sz, output_height=sz)
        names.append(n)
        print(f"{n}: r={m.r:.2f} stroke={m.stroke:.2f} kou=({m.k_cx:.1f},{m.k_cy:.1f}) "
              f"hw={m.k_hw:.1f} hh={m.k_hh:.1f}")
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
        dr.text(((i % cols) * W + pad, y + im.height + 4), n, fill=(40, 40, 40), font=font)
    sheet.save(os.path.join(PNG_DIR, "contact13.png"))
    print("contact13 done")


if __name__ == "__main__":
    main()
