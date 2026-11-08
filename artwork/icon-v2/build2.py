#!/usr/bin/env python3
"""Round 2 — integrate 宀 peak INTO the G so the whole mark is one stroke.

Path order: G's bar (inside) -> ring at 0deg -> counter-clockwise all the way
round -> upper-right terminal -> up the peak's right slope -> apex -> down the
peak's left slope -> lands on the ring -> ring continues -> closes back at 0deg.
"""
import math
import os
import sys

import cairosvg
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
SVG_DIR = os.path.join(HERE, "svg")
PNG_DIR = os.path.join(HERE, "png")
for d in (SVG_DIR, PNG_DIR):
    os.makedirs(d, exist_ok=True)

PAL = {
    "indigo": dict(bg1="#2C4A70", bg2="#132234", fg="#F6F0E3"),
    "ink":    dict(bg1="#22252C", bg2="#0C0E12", fg="#F7F5EF"),
    "seal":   dict(bg1="#C2402F", bg2="#8E2418", fg="#FFF8EC"),
    "jade":   dict(bg1="#1F4B45", bg2="#0E2624", fg="#F2EFE2"),
    "paper":  dict(bg1="#FCF8EF", bg2="#EDE4D2", fg="#23262B"),
    "night":  dict(bg1="#1A1D26", bg2="#080A0E", fg="#EDE4CE"),
}


def pt(cx, cy, r, deg):
    a = math.radians(deg)
    return cx + r * math.cos(a), cy - r * math.sin(a)


def f(p):
    return f"{p[0]:.2f} {p[1]:.2f}"


def peak_g(cx=54.0, cy=62.0, r=17.5, gap=40.0, apex_y=26.0, bar=0.5,
           apex_w=0.0, dot=False, bar_drop=0.0):
    """One continuous stroke: peak roof fused with the G ring."""
    pr = pt(cx, cy, r, gap)               # upper-right terminal (peak right foot)
    pl = pt(cx, cy, r, 180 - gap)         # peak left foot on the ring
    p0 = pt(cx, cy, r, 0)                 # mid-right, start of the G bar
    ax = cx
    ay = apex_y
    d = f"M {f(pr)} "
    if apex_w:                            # small flat top (mountain plateau)
        d += f"L {ax - apex_w:.2f} {ay:.2f} L {ax + apex_w:.2f} {ay:.2f} "
    else:
        d += f"L {ax:.2f} {ay:.2f} "
    d += f"L {f(pl)} "
    d += f"A {r:.2f} {r:.2f} 0 1 0 {f(p0)} "
    d += f"L {p0[0] - bar * r:.2f} {p0[1]:.2f} "
    if dot:
        d += (f"M {ax:.2f} {ay - 6.4:.2f} L {ax:.2f} {ay - 5.0:.2f}")
    return d


def svg_doc(d, pal, stroke, radius=24.0, shadow=False):
    defs = (f'<defs><linearGradient id="bg" x1="0" y1="0" x2="0.35" y2="1">'
            f'<stop offset="0" stop-color="{pal["bg1"]}"/>'
            f'<stop offset="1" stop-color="{pal["bg2"]}"/></linearGradient>')
    sh = ""
    if shadow:
        defs += ('<filter id="soft" x="-25%" y="-25%" width="150%" height="150%">'
                 '<feGaussianBlur stdDeviation="1.5"/></filter>')
        sh = (f'<g filter="url(#soft)"><path d="{d}" fill="none" stroke="#000" '
              f'stroke-opacity="0.30" stroke-width="{stroke + 1.0:.2f}" '
              f'stroke-linecap="round" stroke-linejoin="round"/></g>')
    defs += "</defs>"
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" '
            f'viewBox="0 0 108 108">{defs}'
            f'<rect x="0" y="0" width="108" height="108" rx="{radius}" fill="url(#bg)"/>'
            f'{sh}<path d="{d}" fill="none" stroke="{pal["fg"]}" '
            f'stroke-width="{stroke:.2f}" stroke-linecap="round" '
            f'stroke-linejoin="round"/></svg>')


CANDS = {}


def cand(name, pal, stroke, **kw):
    CANDS[name] = dict(pal=PAL[pal], stroke=stroke, **kw)


# --- round G, various peak shapes
cand("p1", "indigo", 8.0, d=peak_g(apex_y=26), shadow=True)
cand("p2", "indigo", 8.0, d=peak_g(apex_y=24, apex_w=2.4), shadow=True)
cand("p3", "indigo", 8.0, d=peak_g(apex_y=28, gap=34, dot=True), shadow=True)
cand("p4", "indigo", 8.4, d=peak_g(cy=61, r=16.5, gap=46, apex_y=27), shadow=True)
# --- same form in other palettes
cand("p1-seal", "seal", 8.6, d=peak_g(apex_y=26), radius=18)
cand("p1-paper", "paper", 8.0, d=peak_g(apex_y=26))
cand("p1-jade", "jade", 8.0, d=peak_g(apex_y=26), shadow=True)


def strip(name, path):
    imgs = []
    for s in (256, 144, 72, 48):
        p = os.path.join(PNG_DIR, f"_{name}_{s}.png")
        cairosvg.svg2png(url=path, write_to=p, output_width=s, output_height=s)
        imgs.append(Image.open(p).convert("RGBA"))
    big = [imgs[0], imgs[1].resize((256, 256), Image.LANCZOS),
           imgs[2].resize((256, 256), Image.LANCZOS),
           imgs[3].resize((256, 256), Image.NEAREST)]
    sheet = Image.new("RGBA", (256 * 4 + 30, 256), (250, 250, 250, 255))
    for i, im in enumerate(big):
        sheet.alpha_composite(im, (i * 266, 0))
    out = os.path.join(PNG_DIR, f"sheet-{name}.png")
    sheet.convert("RGB").save(out)
    return out


def main():
    only = sys.argv[1:] or list(CANDS)
    names = []
    for name in only:
        c = CANDS[name]
        svg = svg_doc(c["d"], c["pal"], c["stroke"],
                      radius=c.get("radius", 24.0), shadow=c.get("shadow", False))
        p = os.path.join(SVG_DIR, f"{name}.svg")
        open(p, "w").write(svg)
        cairosvg.svg2png(bytestring=svg.encode(),
                         write_to=os.path.join(PNG_DIR, f"{name}-512.png"),
                         output_width=512, output_height=512)
        strip(name, p)
        names.append(name)
        print("built", name)

    cell, cols = 300, min(3, len(names))
    rows = (len(names) + cols - 1) // cols
    master = Image.new("RGB", (cell * cols, cell * rows), (250, 250, 250))
    for i, name in enumerate(names):
        im = Image.open(os.path.join(PNG_DIR, f"{name}-512.png")).convert("RGB")
        im.thumbnail((cell - 24, cell - 24), Image.LANCZOS)
        master.paste(im, ((i % cols) * cell + 12, (i // cols) * cell + 12))
    master.save(os.path.join(PNG_DIR, "contact2.png"))
    print("contact2 done")


if __name__ == "__main__":
    main()
