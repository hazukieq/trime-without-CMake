#!/usr/bin/env python3
"""Round 6 — proportion sweep + launcher-mask preview.

Geometry: 宀 ridge (peak) sitting on a G bowl.
  roof: apex y, feet y, half-width
  bowl: centre, radius
  gap between roof feet and bowl top
Picks the best by eye from the contact sheet.
"""
import math
import os
import sys

import cairosvg
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
SVG_DIR, PNG_DIR = os.path.join(HERE, "svg"), os.path.join(HERE, "png")

PAL = dict(bg1="#2E4E76", bg2="#111F31", fg="#F7F1E4", accent="#E8BE79")


def pt(cx, cy, r, deg):
    a = math.radians(deg)
    return cx + r * math.cos(a), cy - r * math.sin(a)


def f(p):
    return f"{p[0]:.2f} {p[1]:.2f}"


def compose(apex_y=21.0, foot_y=41.0, half=21.0, gcy=64.0, gr=16.5, gap=47.0,
            bar=0.52, stroke=8.4):
    p1 = pt(54, gcy, gr, gap)
    p0 = pt(54, gcy, gr, 0)
    g = (f"M {f(p1)} A {gr:.2f} {gr:.2f} 0 1 0 {f(p0)} "
         f"L {p0[0] - bar * gr:.2f} {p0[1]:.2f}")
    roof = (f"M {54 - half:.2f} {foot_y:.2f} L 54 {apex_y:.2f} "
            f"L {54 + half:.2f} {foot_y:.2f}")
    return roof + " " + g


def svg(d, stroke, radius=25.0, shadow=True, pal=PAL):
    defs = ('<defs><linearGradient id="bg" x1="0" y1="0" x2="0.3" y2="1">'
            f'<stop offset="0" stop-color="{pal["bg1"]}"/>'
            f'<stop offset="1" stop-color="{pal["bg2"]}"/></linearGradient>')
    if shadow:
        defs += ('<filter id="soft" x="-25%" y="-25%" width="150%" height="150%">'
                 '<feGaussianBlur stdDeviation="1.2"/></filter>')
    defs += "</defs>"
    sh = ""
    if shadow:
        sh = (f'<g filter="url(#soft)" opacity="0.25"><path d="{d}" fill="none" '
              f'stroke="#000" stroke-width="{stroke:.2f}" stroke-linecap="round" '
              f'stroke-linejoin="round"/></g>')
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" '
            f'viewBox="0 0 108 108">{defs}'
            f'<rect width="108" height="108" rx="{radius}" fill="url(#bg)"/>{sh}'
            f'<path d="{d}" fill="none" stroke="{pal["fg"]}" stroke-width="{stroke:.2f}" '
            f'stroke-linecap="round" stroke-linejoin="round"/></svg>')


SWEEP = [
    # (name, kwargs)
    ("s1", dict(apex_y=21, foot_y=41, half=21, gcy=64, gr=16.5, stroke=8.4)),
    ("s2", dict(apex_y=22, foot_y=42, half=20, gcy=63.5, gr=17.0, stroke=8.6)),
    ("s3", dict(apex_y=20, foot_y=40, half=20, gcy=64, gr=17.0, stroke=8.6)),
    ("s4", dict(apex_y=21, foot_y=41, half=19.5, gcy=63, gr=17.5, stroke=8.8)),
    ("s5", dict(apex_y=23, foot_y=42, half=22, gcy=65, gr=16, stroke=8.2)),
    ("s6", dict(apex_y=20, foot_y=39, half=20.5, gcy=63.5, gr=17.0, stroke=8.8, bar=0.46)),
    ("s7", dict(apex_y=22, foot_y=42, half=20.5, gcy=64, gr=16.8, stroke=9.2)),
    ("s8", dict(apex_y=19.5, foot_y=39.5, half=19.5, gcy=64, gr=17.2, stroke=8.6,
                gap=52)),
]


def mask_preview(png512, out):
    """Show normal / circle / squircle crops like a launcher would."""
    W = 300
    im = Image.open(png512).convert("RGBA").resize((W, W), Image.LANCZOS)
    circ = Image.new("L", (W, W), 0)
    ImageDraw.Draw(circ).ellipse((W * 0.0, W * 0.0, W, W), fill=255)
    # android: content is the inner 72/108 of the canvas; simulate the visible crop
    zoom = 108 / 72.0
    inner = int(W * 72 / 108)
    off = (W - inner) // 2
    # circle mask on the full-bleed icon, zoomed so the 72dp safe zone fills the frame
    big = im.resize((int(W * zoom), int(W * zoom)), Image.LANCZOS)
    c2 = (big.width - W) // 2
    big = big.crop((c2, c2, c2 + W, c2 + W))
    masked = Image.new("RGBA", (W, W), (0, 0, 0, 0))
    masked.paste(big, (0, 0))
    a = masked.split()[3]
    a = Image.composite(a, Image.new("L", (W, W), 0), circ)
    masked.putalpha(a)
    sheet = Image.new("RGB", (W * 3 + 20, W), (14, 16, 21))
    sheet.paste(im.convert("RGB"), (0, 0))
    sheet.paste(big.convert("RGB"), (W + 10, 0))
    bg = Image.new("RGBA", (W, W), (14, 16, 21, 255))
    bg.alpha_composite(masked, (0, 0))
    sheet.paste(bg.convert("RGB"), (2 * W + 20, 0))
    sheet.save(out)


def main():
    rows = []
    for name, kw in SWEEP:
        d = compose(**kw)
        stroke = kw.pop("stroke")
        s = svg(d, stroke)
        p = os.path.join(SVG_DIR, f"{name}.svg")
        open(p, "w").write(s)
        png = os.path.join(PNG_DIR, f"{name}-512.png")
        cairosvg.svg2png(bytestring=s.encode(), write_to=png,
                         output_width=512, output_height=512)
        mask_preview(png, os.path.join(PNG_DIR, f"mask-{name}.png"))
        rows.append((name, png, stroke))
        print("built", name, "stroke", stroke)

    cell, cols = 300, 4
    master = Image.new("RGB", (cell * cols, cell * 2 * ((len(rows) + cols - 1) // cols)),
                       (248, 248, 248))
    for i, (name, png, _) in enumerate(rows):
        im = Image.open(png).convert("RGB")
        im.thumbnail((cell - 16, cell - 16), Image.LANCZOS)
        master.paste(im, ((i % cols) * cell + 8, (i // cols) * 2 * cell + 8))
        # 48px legibility right below
        small = Image.open(png).convert("RGBA").resize((48, 48), Image.LANCZOS)
        big = small.resize((120, 120), Image.NEAREST)
        master.paste(big.convert("RGB"), ((i % cols) * cell + 8, (i // cols) * 2 * cell + cell))
    master.save(os.path.join(PNG_DIR, "sweep.png"))
    print("sweep done")


if __name__ == "__main__":
    main()
