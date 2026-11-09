#!/usr/bin/env python3
"""hkrime app mark — 客 with its 口 swapped for a G.

Pipeline (all in font units first, transformed once at the end)
  1. pull 客 from Source Han Sans — the character itself stays correct
  2. measure the 口 (outer box + wall thickness) from a 1 px/unit raster
  3. boolean-subtract the 口 from the glyph (skia-pathops) — clean vector, no seams
  4. draw a G in the 口's place as a stroked path of the same optical weight
  5. fit the COMBINED bbox (character + G) to a target size, centred on 108x108
"""
import math
import re

import pathops
from fontTools.misc.transform import Transform
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen

import build8

FONTS = {
    "heavy": "/home/hazukie/.fonts/sourcehan/SourceHanSansSC-Heavy.otf",
    "bold": "/home/hazukie/.fonts/sourcehan/SourceHanSansSC-Bold.otf",
    "medium": "/home/hazukie/.fonts/sourcehan/SourceHanSansSC-Medium.otf",
    "normal": "/home/hazukie/.fonts/sourcehan/SourceHanSansSC-Normal.otf",
    "regular": "/home/hazukie/.fonts/sourcehan/SourceHanSansSC-Regular.otf",
}


def rect_path(x0, y0, x1, y1):
    p = pathops.Path()
    pen = p.getPen()
    pen.moveTo((x0, y0)); pen.lineTo((x1, y0))
    pen.lineTo((x1, y1)); pen.lineTo((x0, y1))
    pen.closePath()
    return p


def to_d(p):
    pen = SVGPathPen(None)
    p.draw(pen)
    return pen.getCommands()


def g_path(cx, cy, r, gap=52.0, bar=0.48):
    a = math.radians(gap)
    p1 = (cx + r * math.cos(a), cy - r * math.sin(a))
    p0 = (cx + r, cy)
    return (f"M {p1[0]:.3f} {p1[1]:.3f} A {r:.3f} {r:.3f} 0 1 0 "
            f"{p0[0]:.3f} {p0[1]:.3f} L {p0[0] - bar * r:.3f} {p0[1]:.3f}")


class Mark:
    def __init__(self, weight="medium", r_scale=1.30, hang=0.20, gap=52.0,
                 bar=0.48, w_scale=0.98, target=72.0, cx=54.0, cy=54.0,
                 cut_pad=0.6, glyph_shift=0.0):
        f, gs, name, raw = build8.load_glyph(FONTS[weight])
        bp = BoundsPen(gs); gs[name].draw(bp)
        gx0, gy0, gx1, gy1 = bp.bounds

        ink, _ = build8.rasterise(raw, gx0, gx1, gy0, gy1)
        kou, _ = build8.find_kou(ink, *build8.rasterise(raw, gx0, gx1, gy0, gy1)[1])
        kx0, kx1 = kou["x0"], kou["x1"]
        ky0, ky1 = kou["y_bot"], kou["y_top"]
        wall = kou["wall"]

        # 1. glyph minus 口 (font units)
        gp = pathops.Path()
        gs[name].draw(gp.getPen())
        # extend the cut downwards: 口's side walls run below the bottom bar
        cut = rect_path(kx0 - 6.0, ky0 - 3.0 * (ky1 - ky0), kx1 + 6.0,
                        ky1 + cut_pad)
        cut_glyph = pathops.op(gp, cut, pathops.PathOp.DIFFERENCE, fix_winding=True)
        cut_glyph.simplify()

        # 2. G sitting in the 口 slot
        k_cx = (kx0 + kx1) / 2.0
        k_cy = (ky0 + ky1) / 2.0
        k_hw = (kx1 - kx0) / 2.0
        k_hh = (ky1 - ky0) / 2.0
        r_u = min(k_hw, k_hh) * r_scale
        stroke_u = wall * w_scale
        g_cy = k_cy - hang * k_hh
        self.g_units = (k_cx, g_cy, r_u, stroke_u)

        # 3. combined bbox in font units -> fit
        bpen = BoundsPen(None)
        cut_glyph.draw(bpen)
        gb = bpen.bounds
        gx = (k_cx - r_u - stroke_u / 2, g_cy - r_u - stroke_u / 2,
              k_cx + r_u + stroke_u / 2, g_cy + r_u + stroke_u / 2)
        bx0, by0 = min(gb[0], gx[0]), min(gb[1], gx[1])
        bx1, by1 = max(gb[2], gx[2]), max(gb[3], gx[3])
        s = target / (by1 - by0)
        tx = cx + glyph_shift - (bx0 + bx1) / 2 * s
        ty = cy + (by0 + by1) / 2 * s
        tr = Transform(s, 0, 0, -s, tx, ty)
        self.tr, self.s = tr, s
        self.units_bbox = (bx0, by0, bx1, by1)

        # 4. emit in canvas coordinates
        gp2 = pathops.Path()
        gs[name].draw(TransformPen(gp2.getPen(), tr))
        cut2 = self._transform_rect(kx0 - 6.0, ky0 - 3.0 * (ky1 - ky0),
                                    kx1 + 6.0, ky1 + cut_pad, tr)
        res = pathops.op(gp2, cut2, pathops.PathOp.DIFFERENCE, fix_winding=True)
        res.simplify()
        self.glyph_d = to_d(res)
        gcx, gcy = tr.transformPoint((k_cx, g_cy))
        self.g_d = g_path(gcx, gcy, r_u * s, gap, bar)
        self.stroke = stroke_u * s
        self.r = r_u * s
        self.kou_canvas = tr.transformPoint((k_cx, k_cy))

    @staticmethod
    def _transform_rect(x0, y0, x1, y1, tr):
        pts = [tr.transformPoint(p) for p in
               ((x0, y0), (x1, y0), (x1, y1), (x0, y1))]
        xs = [p[0] for p in pts]; ys = [p[1] for p in pts]
        return rect_path(min(xs), min(ys), max(xs), max(ys))

    # --- svg helpers -------------------------------------------------------
    def body(self, fg, fill="fill"):
        g = self.glyph_d
        return (f'<path d="{g}" fill="{fg}"/>'
                f'<path d="{self.g_d}" fill="none" stroke="{fg}" '
                f'stroke-width="{self.stroke:.3f}" stroke-linecap="round" '
                f'stroke-linejoin="round"/>')


if __name__ == "__main__":
    m = Mark()
    print("units bbox", [round(v, 1) for v in m.units_bbox])
    print(f"scale={m.s:.4f} stroke={m.stroke:.2f} r={m.r:.2f} "
          f"kou={tuple(round(v, 2) for v in m.kou_canvas)}")
    print("glyph_d", len(m.glyph_d), "chars")
