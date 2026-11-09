#!/usr/bin/env python3
"""Render the 客+G mark into review sheets and all Android asset sizes."""
import math
import os

import cairosvg
from PIL import Image

from mark import Mark

HERE = os.path.dirname(os.path.abspath(__file__))
SVG_DIR, PNG_DIR = os.path.join(HERE, "svg"), os.path.join(HERE, "png")
for d in (SVG_DIR, PNG_DIR):
    os.makedirs(d, exist_ok=True)

SCHEMES = {
    "paper":  dict(bg="#FFFFFF", fg="#16181C"),
    "cream":  dict(bg="#FBF7ED", fg="#1A1C21"),
    "indigo": dict(bg="#20395B", fg="#F7F1E4", grad="#0F1D2E"),
    "ink":    dict(bg="#171A20", fg="#F5F1E7", grad="#080A0E"),
}


def svg_icon(m: Mark, scheme="paper", radius=25.0, size=108, with_bg=True):
    s = SCHEMES[scheme]
    grad = s.get("grad")
    defs = ""
    bg = ""
    if with_bg:
        if grad:
            defs = ('<defs><linearGradient id="bg" x1="0" y1="0" x2="0.3" y2="1">'
                    f'<stop offset="0" stop-color="{s["bg"]}"/>'
                    f'<stop offset="1" stop-color="{grad}"/></linearGradient></defs>')
            fill = "url(#bg)"
        else:
            fill = s["bg"]
        bg = f'<rect width="108" height="108" rx="{radius}" fill="{fill}"/>'
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" '
            f'viewBox="0 0 108 108">{defs}{bg}{m.body(s["fg"])}</svg>')


def render(svg, out, size):
    cairosvg.svg2png(bytestring=svg.encode(), write_to=out,
                     output_width=size, output_height=size)


def review():
    """3 r_scale x 2 colourways, plus a size row."""
    rows = []
    for r_scale in (1.20, 1.30, 1.42):
        m = Mark(r_scale=r_scale, target=72)
        for scheme in ("paper", "indigo"):
            name = f"r{int(r_scale*100)}-{scheme}"
            svg = svg_icon(m, scheme, radius=25)
            open(os.path.join(SVG_DIR, f"{name}.svg"), "w").write(svg)
            render(svg, os.path.join(PNG_DIR, f"{name}-512.png"), 512)
            rows.append((name, scheme))
    W, pad, cols = 380, 26, 3
    sheet = Image.new("RGB", (W * cols, (W + 20) * 2), (232, 232, 232))
    for i, (name, scheme) in enumerate(rows):
        im = Image.open(os.path.join(PNG_DIR, f"{name}-512.png")).convert("RGB")
        im.thumbnail((W - 2 * pad, W - 2 * pad), Image.LANCZOS)
        col = i // 2
        row = i % 2
        sheet.paste(im, (col * W + (W - im.width) // 2, row * (W + 20) + pad))
    sheet.save(os.path.join(PNG_DIR, "review-ke.png"))
    print("review-ke.png")


if __name__ == "__main__":
    review()
