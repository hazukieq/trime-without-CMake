#!/usr/bin/env python3
"""西夏文合体：高 (𗖁 U+17581) + 客 (𗦹 U+179B9) 两字合成一个方形标记。

Tangut builds a lot of characters by composing two glyphs (音意合成/互换),
so a 高+客 composite is stylistically honest: it reads as "a Tangut
character", which is the point — 高峰客语 in one square.
"""
import os

import cairosvg
from fontTools.misc.transform import Transform
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
SVG_DIR, PNG_DIR = os.path.join(HERE, "svg"), os.path.join(HERE, "png")

TANGUT = "/usr/share/fonts/truetype/noto/NotoSerifTangut-Regular.ttf"
GAO = "\U00017581"   # 高 high
KE = "\U000179B9"    # 客 guest
_f = TTFont(TANGUT)
_cmap = _f.getBestCmap()
_gs = _f.getGlyphSet()


def glyph(ch, target, cx, cy, bold=1.15):
    """Path for one Tangut char, its bbox centred at (cx, cy) in the 108 grid."""
    name = _cmap[ord(ch)]
    bp = BoundsPen(_gs); _gs[name].draw(bp)
    x0, y0, x1, y1 = bp.bounds
    s = target / max(x1 - x0, y1 - y0)
    gw, gh = (x1 - x0) * s, (y1 - y0) * s
    ox, oy = cx - gw / 2, cy - gh / 2
    tr = Transform(s, 0, 0, -s, ox - x0 * s, oy + y1 * s)
    pen = SVGPathPen(_gs)
    _gs[name].draw(TransformPen(pen, tr))
    return pen.getCommands(), gw, gh, bold


def svg_of(parts, fg="#14161A", bg="#FFFFFF", radius=25.0, frame=None):
    body = ""
    for d, bold in parts:
        body += (f'<path d="{d}" fill="{fg}" stroke="{fg}" '
                 f'stroke-width="{bold:.2f}" stroke-linejoin="round"/>')
    fr = ""
    if frame:
        fr = (f'<rect x="9" y="9" width="90" height="90" rx="10" fill="none" '
              f'stroke="{fg}" stroke-opacity="0.85" stroke-width="2.4"/>')
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" '
            f'viewBox="0 0 108 108">'
            f'<rect width="108" height="108" rx="{radius}" fill="{bg}"/>'
            f'{body}{fr}</svg>')


def build(name, layout, fg="#14161A", bg="#FFFFFF", radius=25.0, frame=None):
    parts = layout()
    svg = svg_of(parts, fg=fg, bg=bg, radius=radius, frame=frame)
    open(os.path.join(SVG_DIR, f"{name}.svg"), "w").write(svg)
    for sz in (512, 144, 72, 48):
        cairosvg.svg2png(bytestring=svg.encode(),
                         write_to=os.path.join(PNG_DIR, f"{name}-{sz}.png"),
                         output_width=sz, output_height=sz)
    return name


def L_side():
    """左右合体 — 高 left, 客 right, equal optical weight."""
    a = glyph(GAO, 74, 33.5, 54)[:1] + (1.0,)
    b = glyph(KE, 74, 74.5, 54)[:1] + (1.0,)
    return [a, b]


def L_side_tight():
    a = glyph(GAO, 82, 34.0, 54)[:1] + (0.9,)
    b = glyph(KE, 82, 73.0, 54)[:1] + (0.9,)
    return [a, b]


def L_stack():
    """上下合体 — 高 over 客."""
    a = glyph(GAO, 49, 54, 34)[:1] + (0.85,)
    b = glyph(KE, 49, 54, 73)[:1] + (0.85,)
    return [a, b]


def L_overlap():
    """真·合体：客 占主位，高 缩进左上角压在其上，笔画交错."""
    a = glyph(KE, 74, 55, 55)[:1] + (1.0,)
    b = glyph(GAO, 42, 38, 38)[:1] + (0.9,)
    return [b, a]


def L_corner():
    """客 主体，高 作为右下角小章."""
    a = glyph(KE, 68, 53, 50)[:1] + (1.0,)
    b = glyph(GAO, 30, 76, 80)[:1] + (0.8,)
    return [a, b]


CANDS = {
    "t1-side": (L_side, dict()),
    "t2-side-tight": (L_side_tight, dict()),
    "t3-stack": (L_stack, dict()),
    "t4-overlap": (L_overlap, dict()),
    "t5-corner": (L_corner, dict()),
    "t6-side-dark": (L_side, dict(fg="#F6F1E4", bg="#1A2330")),
    "t7-overlap-seal": (L_overlap, dict(fg="#FFF7EC", bg="#C2402F", radius=18)),
}


def main():
    LabelF = "/home/hazukie/.fonts/lxgw-wenkai/LXGWWenKai-Bold.ttf"
    names = []
    for n, (fn, kw) in CANDS.items():
        build(n, fn, **kw)
        names.append(n)
        print("built", n)
    W, pad, cols = 420, 30, 4
    rows = (len(names) + cols - 1) // cols
    sheet = Image.new("RGB", (W * cols, (W + 46) * rows), (232, 232, 232))
    dr = ImageDraw.Draw(sheet)
    f = ImageFont.truetype(LabelF, 22)
    for i, n in enumerate(names):
        im = Image.open(os.path.join(PNG_DIR, f"{n}-512.png")).convert("RGB")
        im.thumbnail((W - 2 * pad, W - 2 * pad), Image.LANCZOS)
        x = (i % cols) * W + (W - im.width) // 2
        y = (i // cols) * (W + 46) + pad
        sheet.paste(im, (x, y))
        dr.text(((i % cols) * W + pad, y + im.height + 2), n, fill=(40, 40, 40), font=f)
    sheet.save(os.path.join(PNG_DIR, "tangut-hex.png"))
    print("tangut-hex.png")


if __name__ == "__main__":
    main()
