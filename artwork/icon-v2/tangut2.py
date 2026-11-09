#!/usr/bin/env python3
"""西夏文「高 + 客」合体：各取半边拼成一个方字。

高 = 𗖁 U+17581, 客 = 𗦹 U+179B9 (Noto Serif Tangut)

一半从哪个方向切、拼在左还是右，决定了两半的切口能不能在中间对齐：
  · 拼在左槽的那半，切口必须在它自己的右边 -> 取源字的「左半」
  · 拼在右槽的那半，切口必须在它自己的左边 -> 取源字的「右半」
客 的义（人/客）在左半（那两笔撇捺），所以另有一种把客放左、高放右的排法。
"""
import os

import cairosvg
import pathops
from fontTools.misc.transform import Transform
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
SVG_DIR, PNG_DIR = os.path.join(HERE, "svg"), os.path.join(HERE, "png")

TANGUT = "/usr/share/fonts/truetype/noto/NotoSerifTangut-Regular.ttf"
GAO = "\U00017581"
KE = "\U000179B9"
_f = TTFont(TANGUT)
_gs = _f.getGlyphSet()
_cmap = _f.getBestCmap()


def glyph_path(ch, target=74.0, cx=54.0, cy=54.0):
    name = _cmap[ord(ch)]
    bp = BoundsPen(_gs); _gs[name].draw(bp)
    x0, y0, x1, y1 = bp.bounds
    s = target / max(x1 - x0, y1 - y0)
    gw, gh = (x1 - x0) * s, (y1 - y0) * s
    ox, oy = cx - gw / 2, cy - gh / 2
    tr = Transform(s, 0, 0, -s, ox - x0 * s, oy + y1 * s)
    p = pathops.Path()
    _gs[name].draw(TransformPen(p.getPen(), tr))
    return p


def clip_half(p, side, bbox):
    x0, y0, x1, y1 = bbox
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    if side == "left":
        r = (x0 - 3, y0 - 3, cx, y1 + 3)
    elif side == "right":
        r = (cx, y0 - 3, x1 + 3, y1 + 3)
    elif side == "top":
        r = (x0 - 3, cy, x1 + 3, y1 + 3)
    else:
        r = (x0 - 3, y0 - 3, x1 + 3, cy)
    rp = pathops.Path()
    pen = rp.getPen()
    pen.moveTo((r[0], r[1])); pen.lineTo((r[2], r[1]))
    pen.lineTo((r[2], r[3])); pen.lineTo((r[0], r[3]))
    pen.closePath()
    res = pathops.op(p, rp, pathops.PathOp.INTERSECTION, fix_winding=True)
    res.simplify()
    return res


def d_of(p):
    pen = SVGPathPen(None)
    p.draw(pen)
    return pen.getCommands()


def composed(half_a, half_b, vertical=False, gap=0.6, seam=0.0, target_w=78.0):
    """half_a goes into the leading slot, half_b into the trailing one."""
    (pa, ba), (pb, bb) = half_a, half_b
    if not vertical:
        dxa = (54 - gap) - ba[2]      # cut edge (=ba right) to the seam
        dxb = (54 + gap) - bb[0]      # cut edge (=bb left) to the seam
        dya = dyb = 0.0
    else:
        dxa = dxb = 0.0
        dya = (54 - gap) - ba[3]
        dyb = (54 + gap) - bb[1]
    # combined bbox
    ax0, ay0 = ba[0] + dxa, ba[1] + dya
    ax1, ay1 = ba[2] + dxa, ba[3] + dya
    bx0, by0 = bb[0] + dxb, bb[1] + dyb
    bx1, by1 = bb[2] + dxb, bb[3] + dyb
    ux0, uy0 = min(ax0, bx0), min(ay0, by0)
    ux1, uy1 = max(ax1, bx1), max(ay1, by1)
    s = min(1.0, target_w / max(ux1 - ux0, uy1 - uy0))
    cx, cy = (ux0 + ux1) / 2, (uy0 + uy1) / 2
    tr = (f"translate({54 - cx * s:.3f},{54 - cy * s:.3f}) scale({s:.4f})")
    body = (f'<g transform="{tr}">'
            f'<g transform="translate({dxa:.3f},{dya:.3f})"><path d="{d_of(pa)}"/></g>'
            f'<g transform="translate({dxb:.3f},{dyb:.3f})"><path d="{d_of(pb)}"/></g>'
            f'</g>')
    return body, (ux0, uy0, ux1, uy1, s)


def svg_doc(body, fg="#14161A", bg="#FFFFFF", radius=25.0, bold=1.05, frame=None):
    fr = ""
    if frame:
        fr = (f'<rect x="9.5" y="9.5" width="89" height="89" rx="9" fill="none" '
              f'stroke="{fg}" stroke-opacity="0.9" stroke-width="2.2"/>')
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" '
            f'viewBox="0 0 108 108">'
            f'<rect width="108" height="108" rx="{radius}" fill="{bg}"/>'
            f'<g fill="{fg}" stroke="{fg}" stroke-width="{bold}" '
            f'stroke-linejoin="round">{body}</g>{fr}</svg>')


def build(name, a_ch, a_side, b_ch, b_side, vertical=False, gap=0.6, **kw):
    pa = glyph_path(a_ch)
    pb = glyph_path(b_ch)
    ba = BoundsPen(None); pa.draw(ba)
    bb = BoundsPen(None); pb.draw(bb)
    ha = clip_half(pa, a_side, ba.bounds)
    hb = clip_half(pb, b_side, bb.bounds)
    hba = BoundsPen(None); ha.draw(hba)
    hbb = BoundsPen(None); hb.draw(hbb)
    body, meta = composed((ha, hba.bounds), (hb, hbb.bounds), vertical, gap)
    svg = svg_doc(body, **kw)
    open(os.path.join(SVG_DIR, f"{name}.svg"), "w").write(svg)
    for sz in (512, 144, 72, 48):
        cairosvg.svg2png(bytestring=svg.encode(),
                         write_to=os.path.join(PNG_DIR, f"{name}-{sz}.png"),
                         output_width=sz, output_height=sz)
    print(f"built {name}  scale={meta[4]:.3f}")
    return name


CANDS = [
    # 高左 | 客右 —— 客用「结构/竖枋」那半
    ("h1-gao-ke", GAO, "left", KE, "right", dict()),
    # 客左（人/撇捺那半，义符）| 高右
    ("h2-ke-gao", KE, "left", GAO, "right", dict()),
    # 上下：高上 | 客下
    ("h3-stack", GAO, "top", KE, "bottom", dict(vertical=True)),
    # 客上（义符）| 高下
    ("h4-stack-ke", KE, "top", GAO, "bottom", dict(vertical=True)),
]
COLORS = [
    ("dark", dict(fg="#F6F1E4", bg="#1A2330")),
    ("seal", dict(fg="#FFF7EC", bg="#C2402F", radius=18, frame=True)),
]


def main():
    LabelF = "/home/hazukie/.fonts/lxgw-wenkai/LXGWWenKai-Bold.ttf"
    names = []
    for n, ac, asd, bc, bsd, kw in CANDS:
        names.append(build(n, ac, asd, bc, bsd, **kw))
    build("h2-ke-gao-dark", KE, "left", GAO, "right", **COLORS[0][1])
    build("h2-ke-gao-seal", KE, "left", GAO, "right", **COLORS[1][1])
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
    sheet.save(os.path.join(PNG_DIR, "tangut-halves.png"))
    print("tangut-halves.png")


if __name__ == "__main__":
    main()


def composite_parts(target=72.0, gap=0.6, cx=54.0, cy=54.0):
    """客左半 + 高右半，合成一个西夏方字；返回 (paths_in_108_grid, bbox)。

    Each returned string is SVG path data already baked into the 108x108 grid.
    """
    pa = glyph_path(KE, 74.0)
    pb = glyph_path(GAO, 74.0)
    ba = BoundsPen(None); pa.draw(ba)
    bb = BoundsPen(None); pb.draw(bb)
    ha = clip_half(pa, "left", ba.bounds)
    hb = clip_half(pb, "right", bb.bounds)
    hba = BoundsPen(None); ha.draw(hba)
    hbb = BoundsPen(None); hb.draw(hbb)
    ba_, bb_ = hba.bounds, hbb.bounds
    dxa = (0 - gap) - ba_[2]
    dxb = (0 + gap) - bb_[0]
    ax = (ba_[0] + dxa, ba_[1], ba_[2] + dxa, ba_[3])
    bx = (bb_[0] + dxb, bb_[1], bb_[2] + dxb, bb_[3])
    ux0, uy0 = min(ax[0], bx[0]), min(ax[1], bx[1])
    ux1, uy1 = max(ax[2], bx[2]), max(ax[3], bx[3])
    s = target / max(ux1 - ux0, uy1 - uy0)
    mx, my = (ux0 + ux1) / 2, (uy0 + uy1) / 2
    tr = Transform(s, 0, 0, s, cx - mx * s, cy - my * s)

    out = []
    for p, dx in ((ha, dxa), (hb, dxb)):
        moved = pathops.Path()
        tr2 = Transform(s, 0, 0, s, cx - mx * s + dx * s, cy - my * s)
        p.draw(TransformPen(moved.getPen(), tr2))
        moved.simplify()
        out.append(d_of(moved))
    bb_out = (cx - (ux1 - ux0) * s / 2, cy - (uy1 - uy0) * s / 2,
              cx + (ux1 - ux0) * s / 2, cy + (uy1 - uy0) * s / 2)
    return out, bb_out
