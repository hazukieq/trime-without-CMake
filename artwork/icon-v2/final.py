#!/usr/bin/env python3
"""Final candidates for review.

A  peak-G  (geometric ligature: 宀 ridge over a G bowl)
A2 peak-G, gold rod
B  type-mark: real 客 with the 口 swapped for a G
C  seal: peak-G as a vermilion seal
"""
import math
import os

import cairosvg
from PIL import Image, ImageDraw, ImageFont

import build8
from build6 import compose

HERE = os.path.dirname(os.path.abspath(__file__))
SVG_DIR, PNG_DIR = os.path.join(HERE, "svg"), os.path.join(HERE, "png")
FONT_LABEL = "/home/hazukie/.fonts/sourcehan/SourceHanSansSC-Medium.otf"

IND = dict(bg1="#2E4E76", bg2="#111F31", fg="#F7F1E4", accent="#E8BE79")
SEAL = dict(bg1="#C74734", bg2="#8B2317", fg="#FFF9EE", accent="#FFF9EE")
PAPER = dict(bg1="#FCF9F1", bg2="#EDE5D4", fg="#23262B", accent="#8C6B3F")

# s7 proportions: thick, balanced
GEO = dict(apex_y=21.5, foot_y=41.5, half=20.5, gcy=64.5, gr=17.6, bar=0.52)
GEO_STROKE = 9.2


def roof_and_g(kw=GEO):
    d = compose(apex_y=kw["apex_y"], foot_y=kw["foot_y"], half=kw["half"],
                gcy=kw["gcy"], gr=kw["gr"], bar=kw["bar"], stroke=GEO_STROKE)
    roof, bowl = d.split(" M ", 1)
    return "M " + roof.strip()[2:] if roof.startswith("M ") else roof, "M " + bowl


def geo_svg(pal, two_tone=False, seal_border=False, radius=25.0):
    # rebuild paths explicitly so the roof can be recoloured
    apex_y, foot_y, half = GEO["apex_y"], GEO["foot_y"], GEO["half"]
    gcy, gr, bar = GEO["gcy"], GEO["gr"], GEO["bar"]
    roof = f"M {54 - half:.2f} {foot_y:.2f} L 54 {apex_y:.2f} L {54 + half:.2f} {foot_y:.2f}"
    p1 = (54 + gr * math.cos(math.radians(47)), gcy - gr * math.sin(math.radians(47)))
    p0 = (54 + gr, gcy)
    bowl = (f"M {p1[0]:.2f} {p1[1]:.2f} A {gr:.2f} {gr:.2f} 0 1 0 {p0[0]:.2f} {p0[1]:.2f} "
            f"L {p0[0] - bar * gr:.2f} {p0[1]:.2f}")
    st = GEO_STROKE
    defs = ('<defs><linearGradient id="bg" x1="0" y1="0" x2="0.3" y2="1">'
            f'<stop offset="0" stop-color="{pal["bg1"]}"/>'
            f'<stop offset="1" stop-color="{pal["bg2"]}"/></linearGradient>'
            '<filter id="soft" x="-25%" y="-25%" width="150%" height="150%">'
            '<feGaussianBlur stdDeviation="1.2"/></filter></defs>')
    allp = roof + " " + bowl
    shadow = (f'<g filter="url(#soft)" opacity="0.25"><path d="{allp}" fill="none" '
              f'stroke="#000" stroke-width="{st:.2f}" stroke-linecap="round" '
              f'stroke-linejoin="round"/></g>')
    bowl_el = (f'<path d="{bowl}" fill="none" stroke="{pal["fg"]}" '
               f'stroke-width="{st:.2f}" stroke-linecap="round" stroke-linejoin="round"/>')
    roof_col = pal["accent"] if two_tone else pal["fg"]
    roof_el = (f'<path d="{roof}" fill="none" stroke="{roof_col}" '
               f'stroke-width="{st:.2f}" stroke-linecap="round" stroke-linejoin="round"/>')
    border = ""
    if seal_border:
        border = (f'<rect x="13" y="13" width="82" height="82" rx="11" fill="none" '
                  f'stroke="{pal["fg"]}" stroke-opacity="0.5" stroke-width="1.5"/>')
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" '
            f'viewBox="0 0 108 108">{defs}'
            f'<rect width="108" height="108" rx="{radius}" fill="url(#bg)"/>'
            f'{shadow}{bowl_el}{roof_el}{border}</svg>')


def write(name, svg):
    p = os.path.join(SVG_DIR, f"{name}.svg")
    open(p, "w").write(svg)
    for s in (512, 144, 72, 48):
        cairosvg.svg2png(bytestring=svg.encode(),
                         write_to=os.path.join(PNG_DIR, f"{name}-{s}.png"),
                         output_width=s, output_height=s)
    print("built", name)


def main():
    write("A-indigo", geo_svg(IND))
    write("A-gold", geo_svg(IND, two_tone=True))
    write("A-paper", geo_svg(PAPER, two_tone=True))
    write("C-seal", geo_svg(SEAL, seal_border=True, radius=19))
    # B: type-mark
    build8.build("bold", "B-comark", "indigo")

    # labelled presentation sheet
    items = [("A-indigo-512.png", "A · 峰脊 G"),
             ("A-gold-512.png", "A2 · 金脊 G"),
             ("C-seal-512.png", "C · 印章"),
             ("rg2-a-512.png", "B · 客字标")]
    W, pad = 420, 40
    sheet = Image.new("RGB", (W * len(items), W + 60), (247, 247, 247))
    dr = ImageDraw.Draw(sheet)
    try:
        font = ImageFont.truetype(FONT_LABEL, 26)
    except OSError:
        font = ImageFont.load_default()
    for i, (fn, label) in enumerate(items):
        im = Image.open(os.path.join(PNG_DIR, fn)).convert("RGB")
        im.thumbnail((W - 2 * pad, W - 2 * pad), Image.LANCZOS)
        x = i * W + (W - im.width) // 2
        sheet.paste(im, (x, pad))
        dr.text((i * W + pad, W + 8), label, fill=(40, 40, 40), font=font)
    sheet.save(os.path.join(PNG_DIR, "finalists.png"))
    print("finalists.png")


if __name__ == "__main__":
    main()
