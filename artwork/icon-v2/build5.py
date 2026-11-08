#!/usr/bin/env python3
"""Round 5 — polish.  Fix two-tone (no clip: draw bowl then roof on top),
try square 口 counter, 宀 dot, seal border."""
import io
import math
import os
import sys

import cairosvg
from PIL import Image

from build4 import PAL, autocentre, f, g_path, pt, roof_path

HERE = os.path.dirname(os.path.abspath(__file__))
SVG_DIR, PNG_DIR = os.path.join(HERE, "svg"), os.path.join(HERE, "png")


def g_square(cx, cy, r, corner=8.0, bar=0.50, gap_at=0.58):
    """G with a square-ish 口 counter: rounded-rect ring, gap on the upper right,
    horizontal bar from the mid-right edge running inward."""
    L, R, T, B = cx - r, cx + r, cy - r, cy + r
    c = corner
    return (f"M {L + c:.2f} {T:.2f} L {cx + (gap_at - 0.5) * r:.2f} {T:.2f} "
            f"M {R:.2f} {cy - 0.42 * r:.2f} "
            f"L {R:.2f} {B - c:.2f} "
            f"Q {R:.2f} {B:.2f} {R - c:.2f} {B:.2f} "
            f"L {L + c:.2f} {B:.2f} Q {L:.2f} {B:.2f} {L:.2f} {B - c:.2f} "
            f"L {L:.2f} {T + c:.2f} Q {L:.2f} {T:.2f} {L + c:.2f} {T:.2f} "
            f"M {R:.2f} {cy:.2f} L {cx + (1 - bar) * r:.2f} {cy:.2f}")


def dot(cx, cy, r=1.7):
    return f"M {cx - r:.2f} {cy:.2f} L {cx + r:.2f} {cy:.2f}"


CANDS = {}


def cand(name, pal, stroke, d, roof_d="", radius=25.0, two_tone=False,
         shadow=True, seal_border=False):
    CANDS[name] = dict(pal=PAL[pal], stroke=stroke, d=d, roof_d=roof_d,
                       radius=radius, two_tone=two_tone, shadow=shadow,
                       seal_border=seal_border)


PEAK = roof_path(apex=21.5, foot_y=41.5, half=22)
PEAK_LEGS = (roof_path(apex=21.5, foot_y=41.5, half=21)
             + " M 33 41.5 L 29.5 46.5 M 75 41.5 L 78.5 46.5")   # splayed 宀 legs
FLAT = roof_path(apex=23.5, foot_y=41, half=22, flat=10)
ROUND_G = g_path(54, 64.5, 16.5, gap=47)
SQ_G = g_square(54, 64, 16.5, corner=8.0)

cand("v1-peak", "indigo", 8.4, ROUND_G, PEAK)
cand("v2-dot", "indigo2", 8.4, ROUND_G, PEAK + " " + dot(54, 14.5), two_tone=True)
cand("v3-flat", "indigo", 8.4, ROUND_G, FLAT + " " + dot(54, 17.5))
cand("v4-square", "indigo", 8.2, SQ_G, PEAK)
cand("v5-square-flat", "indigo", 8.2, SQ_G, FLAT)
cand("v6-seal", "seal", 8.8, ROUND_G, PEAK, radius=19, shadow=False, seal_border=True)
cand("v7-paper", "paper", 8.4, ROUND_G, PEAK, shadow=False)
cand("v8-night", "night", 8.4, ROUND_G, PEAK)
cand("v9-goldpeak", "indigo2", 8.4, ROUND_G, PEAK, two_tone=True)
cand("v10-legs", "indigo", 8.4, ROUND_G, PEAK_LEGS)


def svg_doc(c):
    pal, stroke = c["pal"], c["stroke"]
    alld = c["d"] + " " + c["roof_d"]
    d_all, (dx, dy) = autocentre(alld, stroke)
    defs = (f'<defs><linearGradient id="bg" x1="0" y1="0" x2="0.3" y2="1">'
            f'<stop offset="0" stop-color="{pal["bg1"]}"/>'
            f'<stop offset="1" stop-color="{pal["bg2"]}"/></linearGradient>')
    if c["shadow"]:
        defs += ('<filter id="soft" x="-25%" y="-25%" width="150%" height="150%">'
                 '<feGaussianBlur stdDeviation="1.3"/></filter>')
    defs += "</defs>"

    def path_svg(d, color, extra=""):
        return (f'<path d="{d}" fill="none" stroke="{color}" stroke-width="{stroke:.2f}" '
                f'stroke-linecap="round" stroke-linejoin="round"{extra}/>')

    shadow = ""
    if c["shadow"]:
        shadow = (f'<g filter="url(#soft)" opacity="0.26">'
                  f'{path_svg(alld, "#000")}</g>')
    # bowl first, roof painted on top -> no clipping artefacts
    bowl_color = pal["fg"]
    roof_color = pal["roof"] if c["two_tone"] else pal["fg"]
    if c["two_tone"]:
        # shift colour boundary: draw the whole thing in bowl colour, then
        # re-draw only the roof strokes in the accent colour
        body = path_svg(alld, bowl_color)
        body = path_svg(c["d"], bowl_color) + path_svg(c["roof_d"], roof_color)
    else:
        body = path_svg(alld, bowl_color)
    border = ""
    if c["seal_border"]:
        border = (f'<rect x="12" y="12" width="84" height="84" rx="12" fill="none" '
                  f'stroke="{pal["fg"]}" stroke-opacity="0.55" stroke-width="1.6"/>')
    # 48px legibility: also give a slight inner light on the top edge
    gloss = ('<rect x="0" y="0" width="108" height="108" rx="{r}" fill="url(#gl)"/>')
    defs = defs.replace("</defs>",
                        '<linearGradient id="gl" x1="0" y1="0" x2="0" y2="1">'
                        '<stop offset="0" stop-color="#fff" stop-opacity="0.10"/>'
                        '<stop offset="0.45" stop-color="#fff" stop-opacity="0"/>'
                        '</linearGradient></defs>')
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" '
            f'viewBox="0 0 108 108">{defs}'
            f'<rect x="0" y="0" width="108" height="108" rx="{c["radius"]}" fill="url(#bg)"/>'
            f'{gloss.format(r=c["radius"])}'
            f'<g transform="translate({dx:.2f},{dy:.2f})">{shadow}{body}</g>'
            f'{border}</svg>')


def sizes(name, path):
    out = {}
    for s in (512, 144, 72, 48, 32):
        p = os.path.join(PNG_DIR, f"_{name}_{s}.png")
        cairosvg.svg2png(url=path, write_to=p, output_width=s, output_height=s)
        out[s] = Image.open(p).convert("RGBA")
    return out


def strip(name, imgs):
    w = 220
    cells = [imgs[512].resize((w, w), Image.LANCZOS),
             imgs[144].resize((w, w), Image.LANCZOS),
             imgs[72].resize((w, w), Image.NEAREST),
             imgs[48].resize((w, w), Image.NEAREST),
             imgs[32].resize((w, w), Image.NEAREST)]
    sheet = Image.new("RGBA", (w * len(cells) + 8 * (len(cells) - 1), w), (250, 250, 250, 255))
    for i, im in enumerate(cells):
        sheet.alpha_composite(im, (i * (w + 8), 0))
    sheet.convert("RGB").save(os.path.join(PNG_DIR, f"sheet-{name}.png"))


def main():
    only = sys.argv[1:] or list(CANDS)
    names = []
    for name in only:
        c = CANDS[name]
        svg = svg_doc(c)
        p = os.path.join(SVG_DIR, f"{name}.svg")
        open(p, "w").write(svg)
        cairosvg.svg2png(bytestring=svg.encode(), write_to=os.path.join(
            PNG_DIR, f"{name}-512.png"), output_width=512, output_height=512)
        imgs = sizes(name, p)
        strip(name, imgs)
        names.append(name)
        print("built", name)
    cell, cols = 300, min(5, len(names))
    rows = (len(names) + cols - 1) // cols
    master = Image.new("RGB", (cell * cols, cell * rows), (248, 248, 248))
    for i, name in enumerate(names):
        im = Image.open(os.path.join(PNG_DIR, f"{name}-512.png")).convert("RGB")
        im.thumbnail((cell - 20, cell - 20), Image.LANCZOS)
        master.paste(im, ((i % cols) * cell + 10, (i // cols) * cell + 10))
    master.save(os.path.join(PNG_DIR, "contact5.png"))
    print("contact5 done")


if __name__ == "__main__":
    main()
