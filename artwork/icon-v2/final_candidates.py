#!/usr/bin/env python3
"""两个方案的正稿：
C1/C2  霞鹜文楷「客」—— 一个字占满图标（白底黑字 / 靛蓝米白）
C3/C4  西夏文「高+客」各取半边合体（白底黑字 / 墨底米白）

同时给出 48px 小尺寸预览，用来判断真机上还认不认得出。
"""
import os

import cairosvg
from fontTools.misc.transform import Transform
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont
from PIL import Image, ImageDraw, ImageFont

import tangut2

HERE = os.path.dirname(os.path.abspath(__file__))
SVG_DIR, PNG_DIR = os.path.join(HERE, "svg"), os.path.join(HERE, "png")

WENKAI = "/home/hazukie/.fonts/lxgw-wenkai/LXGWWenKai-Bold.ttf"
LABEL = "/home/hazukie/.fonts/lxgw-wenkai/LXGWWenKai-Bold.ttf"


def wenkai_ke(target=76.0, faux_bold=0.9):
    f = TTFont(WENKAI)
    gs = f.getGlyphSet()
    name = f.getBestCmap()[ord("客")]
    bp = BoundsPen(gs); gs[name].draw(bp)
    x0, y0, x1, y1 = bp.bounds
    s = target / (y1 - y0)
    gw, gh = (x1 - x0) * s, (y1 - y0) * s
    ox, oy = 54 - gw / 2, 54 - gh / 2
    tr = Transform(s, 0, 0, -s, ox - x0 * s, oy + y1 * s)
    pen = SVGPathPen(gs)
    gs[name].draw(TransformPen(pen, tr))
    return pen.getCommands(), faux_bold


def wrap(body, fg, bg, radius=25.0, grad=None, size=108):
    g = ""
    if grad:
        g = ('<defs><linearGradient id="bg" x1="0" y1="0" x2="0.3" y2="1">'
             f'<stop offset="0" stop-color="{bg}"/><stop offset="1" stop-color="{grad}"/>'
             '</linearGradient></defs>')
        bgfill = "url(#bg)"
    else:
        bgfill = bg
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" '
            f'viewBox="0 0 108 108">{g}'
            f'<rect width="108" height="108" rx="{radius}" fill="{bgfill}"/>'
            f'<g fill="{fg}" stroke="{fg}" stroke-width="0.9" '
            f'stroke-linejoin="round">{body}</g></svg>')


def emit(name, svg):
    open(os.path.join(SVG_DIR, f"{name}.svg"), "w").write(svg)
    for sz in (512, 144, 72, 48):
        cairosvg.svg2png(bytestring=svg.encode(),
                         write_to=os.path.join(PNG_DIR, f"{name}-{sz}.png"),
                         output_width=sz, output_height=sz)
    return name


def main():
    d, bold = wenkai_ke()
    c1 = f'<path d="{d}" stroke-width="{bold}"/>'
    names = [
        emit("c1-wenkai-paper", wrap(c1, "#15171B", "#FFFFFF")),
        emit("c2-wenkai-indigo", wrap(c1, "#F7F1E4", "#20395B", grad="#0F1D2E")),
    ]
    # tangut composite h2 already rendered by tangut2; reuse its paths
    pa = tangut2.glyph_path(tangut2.KE, 74)
    pb = tangut2.glyph_path(tangut2.GAO, 74)
    from fontTools.pens.boundsPen import BoundsPen as BP
    ba = BP(None); pa.draw(ba)
    bb = BP(None); pb.draw(bb)
    ha = tangut2.clip_half(pa, "left", ba.bounds)
    hb = tangut2.clip_half(pb, "right", bb.bounds)
    hba = BP(None); ha.draw(hba)
    hbb = BP(None); hb.draw(hbb)
    body, _ = tangut2.composed((ha, hba.bounds), (hb, hbb.bounds), False, 0.6)
    c3 = emit("c3-tangut-paper", wrap(body, "#15171B", "#FFFFFF"))
    c4 = emit("c4-tangut-ink", wrap(body, "#F6F1E4", "#1A2330"))
    names += [c3, c4]

    # review sheet: 512 + 48px row
    W, pad = 400, 28
    sheet = Image.new("RGB", (W * len(names), W + 170), (232, 232, 232))
    dr = ImageDraw.Draw(sheet)
    f = ImageFont.truetype(LABEL, 22)
    for i, n in enumerate(names):
        im = Image.open(os.path.join(PNG_DIR, f"{n}-512.png")).convert("RGB")
        im.thumbnail((W - 2 * pad, W - 2 * pad), Image.LANCZOS)
        sheet.paste(im, (i * W + (W - im.width) // 2, pad))
        s48 = Image.open(os.path.join(PNG_DIR, f"{n}-48.png")).convert("RGBA")
        s48 = s48.resize((120, 120), Image.NEAREST)
        sheet.paste(s48, (i * W + 30, W + 10), s48)
        dr.text((i * W + 10, W + 140), n, fill=(40, 40, 40), font=f)
    sheet.save(os.path.join(PNG_DIR, "final-two.png"))
    print("final-two.png")


if __name__ == "__main__":
    main()
