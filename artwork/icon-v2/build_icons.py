#!/usr/bin/env python3
"""hkrime icon lab — parametric SVG icon candidates.

Concept: 客 + G ligature.  客 (guest) = 宀 roof over 各 (夂 + 口).
高峰 (peak) supplies the roof-as-mountain-ridge reading; 口 becomes the counter
of a capital G, the 夂 stroke becomes the G's right stem + crossbar.

Outputs: svg/<name>.svg  png/<name>-512.png  png/sheet-<name>.png
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

# ---------------------------------------------------------------- palettes
PAL = {
    "indigo": dict(bg1="#2A466A", bg2="#152437", fg="#F5EFE2", glow="#8FB4DA"),
    "ink": dict(bg1="#20232A", bg2="#0D0F13", fg="#F7F5EF", glow="#7F8794"),
    "seal": dict(bg1="#C2402F", bg2="#93271B", fg="#FFF8EC", glow="#E0765F"),
    "jade": dict(bg1="#1F4B45", bg2="#0F2825", fg="#F2EFE2", glow="#6FB3A1"),
    "paper": dict(bg1="#FBF7EE", bg2="#EFE7D6", fg="#23262B", glow="#C9BFA9"),
}


def pt(cx, cy, r, deg):
    a = math.radians(deg)
    return cx + r * math.cos(a), cy - r * math.sin(a)


def fmt(p):
    return f"{p[0]:.2f} {p[1]:.2f}"


# ---------------------------------------------------------------- G geometry
def g_ring(cx, cy, r, gap_a=52.0, gap_b=0.0, bar=0.46):
    """Capital G: ring open between gap_b..gap_a (upper right), horizontal bar
    at mid-height running inward from the gap_b end (the 夂/捺 stroke)."""
    p1 = pt(cx, cy, r, gap_a)
    p2 = pt(cx, cy, r, gap_b)
    d = (f"M {fmt(p1)} "
         f"A {r:.2f} {r:.2f} 0 1 0 {fmt(p2)} "
         f"L {cx + r - bar * r:.2f} {p2[1]:.2f}")
    return d


def g_square(cx, cy, r, corner=6.0, bar=0.5):
    """Capital G built from a rounded square ring (口 reading) — square counter."""
    L, R, T, B = cx - r, cx + r, cy - r, cy + r
    c = corner
    d = (f"M {L + c:.2f} {T:.2f} "
         f"L {cx + 0.30 * r:.2f} {T:.2f} "              # top edge stops early (gap)
         f"M {R:.2f} {T + 0.62 * r:.2f} "               # gap edge, right side resumes
         f"L {R:.2f} {B - c:.2f} "
         f"Q {R:.2f} {B:.2f} {R - c:.2f} {B:.2f} "
         f"L {L + c:.2f} {B:.2f} "
         f"Q {L:.2f} {B:.2f} {L:.2f} {B - c:.2f} "
         f"L {L:.2f} {T + c:.2f} "
         f"Q {L:.2f} {T:.2f} {L + c:.2f} {T:.2f} "
         f"M {R:.2f} {cy:.2f} L {cx + r - bar * r:.2f} {cy:.2f}")
    return d


def roof(apex_y=19.0, span=23.5, base_y=39.0, cx=54.0, dot=False):
    """宀 as a mountain ridge: apex at top, two slopes. Optional 宀 dot."""
    d = (f"M {cx - span:.2f} {base_y:.2f} "
         f"L {cx:.2f} {apex_y:.2f} "
         f"L {cx + span + 0.5:.2f} {base_y:.2f}")
    if dot:
        d += f"M {cx:.2f} {apex_y - 5.8:.2f} L {cx:.2f} {apex_y - 4.0:.2f}"
    return d


def stem(cx=54.0, x=77.0, y0=39.0, y1=62.0, bar_x=62.0):
    """夂/捺 stroke: right side falls from the ridge, turns inward as G's bar."""
    return (f"M {x:.2f} {y0:.2f} L {x - 0.5:.2f} {y1:.2f} "
            f"L {bar_x:.2f} {y1:.2f}")


# ---------------------------------------------------------------- svg wrapper
def svg_doc(paths, pal, stroke, rounded=True, radius=24.0, shadow=False, note=""):
    bg1, bg2 = pal["bg1"], pal["bg2"]
    fg = pal["fg"]
    card = (f'<rect x="0" y="0" width="108" height="108" '
            f'rx="{radius if rounded else 0}" fill="url(#bg)"/>')
    defs = (f'<defs><linearGradient id="bg" x1="0" y1="0" x2="0.35" y2="1">'
            f'<stop offset="0" stop-color="{bg1}"/>'
            f'<stop offset="1" stop-color="{bg2}"/></linearGradient></defs>')
    sh = ""
    if shadow:
        sh = (f'<g filter="url(#soft)">'
              f'<path d="{paths}" fill="none" stroke="#000" stroke-opacity="0.28" '
              f'stroke-width="{stroke + 1.2:.2f}" stroke-linecap="round" '
              f'stroke-linejoin="round"/></g>')
        defs = defs.replace("</defs>",
                            '<filter id="soft" x="-20%" y="-20%" width="140%" height="140%">'
                            '<feGaussianBlur stdDeviation="1.6"/></filter></defs>')
    body = (f'{sh}<path d="{paths}" fill="none" stroke="{fg}" '
            f'stroke-width="{stroke:.2f}" stroke-linecap="round" '
            f'stroke-linejoin="round"/>')
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" '
            f'viewBox="0 0 108 108">{defs}{card}{body}</svg>')


def render(svg, out_base):
    p512 = os.path.join(PNG_DIR, f"{out_base}-512.png")
    cairosvg.svg2png(bytestring=svg.encode(), write_to=p512,
                     output_width=512, output_height=512)
    return p512


def strip(name, svg_path):
    """4 sizes: 512, 144, 72, 48(nearest ×4 for pixel inspection)."""
    imgs = []
    for s in (256, 144, 72, 48):
        p = os.path.join(PNG_DIR, f"_{name}_{s}.png")
        cairosvg.svg2png(url=svg_path, write_to=p, output_width=s, output_height=s)
        imgs.append(Image.open(p).convert("RGBA"))
    big = [imgs[0].resize((256, 256), Image.LANCZOS),
           imgs[1].resize((256, 256), Image.LANCZOS),
           imgs[2].resize((256, 256), Image.LANCZOS),
           imgs[3].resize((256, 256), Image.NEAREST)]
    sheet = Image.new("RGBA", (256 * 4 + 30, 256), (250, 250, 250, 255))
    for i, im in enumerate(big):
        sheet.alpha_composite(im, (i * 266, 0))
    out = os.path.join(PNG_DIR, f"sheet-{name}.png")
    sheet.convert("RGB").save(out)
    return out


# ---------------------------------------------------------------- candidates
CANDS = {}


def cand(name, paths, pal, stroke, **kw):
    CANDS[name] = dict(paths=paths, pal=PAL[pal], stroke=stroke, **kw)


# A — ridge roof + round G (indigo)
cand("a-ridge-g", roof() + " " + g_ring(54, 62, 21) + " " + stem(bar_x=62.5),
     "indigo", 8.6, shadow=True)

# A2 — same, no separate stem (arch + G only)
cand("a2-clean", roof() + " " + g_ring(54, 63, 21, gap_a=64, bar=0.55),
     "indigo", 9.0, shadow=True)

# B — monochrome paper variant (light bg / ink glyph)
cand("b-paper", roof() + " " + g_ring(54, 62, 21) + " " + stem(bar_x=62.5),
     "paper", 8.6)

# C — vermilion seal
cand("c-seal", roof() + " " + g_ring(54, 62, 21) + " " + stem(bar_x=62.5),
     "seal", 9.4, radius=18)

# D — squared 口 / square-counter G
cand("d-square-g", roof(base_y=38) + " " + g_square(54, 62, 20.5),
     "indigo", 8.4, shadow=True)

# E — G as the frame, 客 nested inside the counter
cand("e-g-frame", g_ring(54, 54, 30, gap_a=46, bar=0.42) + " "
     + roof(apex_y=33, span=11.5, base_y=45) + " "
     f"M 42.5 52 L 65.5 52 L 65.5 67 L 42.5 67 Z",
     "indigo", 7.0, shadow=True)

# F — jade, thicker, no shadow
cand("f-jade", roof() + " " + g_ring(54, 62, 21) + " " + stem(bar_x=62.5),
     "jade", 8.6)


def main():
    only = sys.argv[1:] or list(CANDS)
    sheets = []
    for name in only:
        c = CANDS[name]
        svg = svg_doc(c["paths"], c["pal"], c["stroke"], **
                      {k: v for k, v in c.items()
                       if k in ("rounded", "radius", "shadow")})
        p = os.path.join(SVG_DIR, f"{name}.svg")
        with open(p, "w") as f:
            f.write(svg)
        render(svg, name)
        sheets.append((name, strip(name, p)))
        print("built", name)
    # master contact sheet
    cell = 300
    cols = min(3, len(sheets))
    rows = (len(sheets) + cols - 1) // cols
    master = Image.new("RGB", (cell * cols, cell * rows), (250, 250, 250))
    for i, (name, _) in enumerate(sheets):
        im = Image.open(os.path.join(PNG_DIR, f"{name}-512.png")).convert("RGB")
        im.thumbnail((cell - 24, cell - 24), Image.LANCZOS)
        master.paste(im, ((i % cols) * cell + 12, (i // cols) * cell + 12))
    out = os.path.join(PNG_DIR, "contact.png")
    master.save(out)
    print("contact:", out)


if __name__ == "__main__":
    main()
