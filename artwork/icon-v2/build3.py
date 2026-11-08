#!/usr/bin/env python3
"""Round 3 — 宀 roof must OVERHANG the G (brim wider than the bowl).

客 = 宀 (roof) over 各 (夂 + 口).  口 -> the G's counter, so the bowl is 口;
the 宀 brim sits on top of that bowl like a hat.
"""
import math
import os
import sys

import cairosvg
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
SVG_DIR, PNG_DIR = os.path.join(HERE, "svg"), os.path.join(HERE, "png")
for d in (SVG_DIR, PNG_DIR):
    os.makedirs(d, exist_ok=True)

PAL = {
    "indigo": dict(bg1="#2C4A70", bg2="#132234", fg="#F6F0E3", accent="#E7BE79"),
    "ink":    dict(bg1="#22252C", bg2="#0C0E12", fg="#F7F5EF", accent="#C9B48A"),
    "seal":   dict(bg1="#C2402F", bg2="#8E2418", fg="#FFF8EC", accent="#F3D8A8"),
    "jade":   dict(bg1="#1F4B45", bg2="#0E2624", fg="#F2EFE2", accent="#D9BE84"),
    "paper":  dict(bg1="#FCF8EF", bg2="#EDE4D2", fg="#23262B", accent="#8C6B3F"),
    "night":  dict(bg1="#1A1D26", bg2="#080A0E", fg="#EDE4CE", accent="#C9A96A"),
}


def pt(cx, cy, r, deg):
    a = math.radians(deg)
    return cx + r * math.cos(a), cy - r * math.sin(a)


def f(p):
    return f"{p[0]:.2f} {p[1]:.2f}"


def g_path(cx, cy, r, gap=46.0, bar=0.52):
    """Ring open between 0 and gap degrees; bar runs inward from the 0deg end."""
    p1 = pt(cx, cy, r, gap)
    p0 = pt(cx, cy, r, 0)
    return (f"M {f(p1)} A {r:.2f} {r:.2f} 0 1 0 {f(p0)} "
            f"L {p0[0] - bar * r:.2f} {p0[1]:.2f}")


def cap(apex, foot_y, cx=54.0, overhang=21.0, flat=0.0, legs=0.0, drop=0.0):
    """宀 / roof: peak (or flat-topped peak) with optional splayed legs."""
    L, R = cx - overhang, cx + overhang
    d = ""
    if flat:
        d = f"M {L:.2f} {foot_y:.2f} L {cx - flat:.2f} {apex:.2f} " \
            f"L {cx + flat:.2f} {apex:.2f} L {R:.2f} {foot_y:.2f} "
    else:
        d = f"M {L:.2f} {foot_y:.2f} L {cx:.2f} {apex:.2f} L {R:.2f} {foot_y:.2f} "
    if legs:   # splayed down-strokes at the brim ends (宀's 丿 乀)
        d += (f"M {L:.2f} {foot_y:.2f} L {L - legs:.2f} {foot_y + legs:.2f} "
              f"M {R:.2f} {foot_y:.2f} L {R + legs:.2f} {foot_y + legs:.2f} ")
    if drop:   # dot of 宀
        d += f"M {cx:.2f} {apex - drop - 5.0:.2f} L {cx:.2f} {apex - drop - 3.4:.2f} "
    return d


def slash(x0, y0, x1, y1):
    return f"M {x0:.2f} {y0:.2f} L {x1:.2f} {y1:.2f} "


CANDS = {}


def cand(name, pal, stroke, parts, radius=24.0, shadow=True, two_tone=False):
    CANDS[name] = dict(pal=PAL[pal], stroke=stroke, parts=parts,
                       radius=radius, shadow=shadow, two_tone=two_tone)


# --- r-series: brim overhang -------------------------------------------------
# ring: cy=64 r=16 -> outer edge y 44..84, x 34..74
cand("r1", "indigo", 8.2,
     [cap(apex=22, foot_y=42, overhang=23) + g_path(54, 64, 16, gap=46)])
cand("r2", "indigo", 8.2,
     [cap(apex=24, foot_y=43, overhang=21) + g_path(54, 64, 16, gap=52)])
cand("r3", "indigo", 8.2,
     [cap(apex=23, foot_y=42, overhang=22, legs=3.2) + g_path(54, 64, 16, gap=48)])
cand("r4", "indigo", 8.2,
     [cap(apex=22, foot_y=42, overhang=22, flat=3.0) + g_path(54, 64, 16, gap=48)])
cand("r5", "indigo", 8.2,
     [cap(apex=24, foot_y=41, overhang=21, drop=0) + g_path(54, 65, 15.5, gap=50),
      f"M 54 {18:.0f} L 54 {20:.0f}"])
# --- 客 literal: 宀 + 夂 slash + G bowl --------------------------------------
cand("r6", "indigo", 8.0,
     [cap(apex=22, foot_y=40, overhang=19) + g_path(54, 65, 15.5, gap=50),
      slash(43, 45, 60, 53)])
# --- square-counter G (口 reading) -------------------------------------------
def g_square_path(cx, cy, r, corner=7.0, bar=0.5, gap=0.34):
    L, R, T, B = cx - r, cx + r, cy - r, cy + r
    c = corner
    return (f"M {L + c:.2f} {T:.2f} L {cx + gap * r:.2f} {T:.2f} "      # top, stops for gap
            f"M {R:.2f} {T + 0.55 * r:.2f} L {R:.2f} {B - c:.2f} "
            f"Q {R:.2f} {B:.2f} {R - c:.2f} {B:.2f} "
            f"L {L + c:.2f} {B:.2f} Q {L:.2f} {B:.2f} {L:.2f} {B - c:.2f} "
            f"L {L:.2f} {T + c:.2f} Q {L:.2f} {T:.2f} {L + c:.2f} {T:.2f} "
            f"M {R:.2f} {cy:.2f} L {cx + r - bar * r:.2f} {cy:.2f} ")


cand("r7", "indigo", 8.0,
     [cap(apex=22, foot_y=42, overhang=22) + g_square_path(54, 64, 16.5)])
cand("r8", "indigo", 8.0,
     [cap(apex=23, foot_y=43, overhang=20) + g_square_path(54, 64, 15.5, gap=0.30)])

# --- palettes on the best guess ---------------------------------------------
cand("r1-seal", "seal", 8.8,
     [cap(apex=22, foot_y=42, overhang=23) + g_path(54, 64, 16, gap=46)],
     radius=18, shadow=False)
cand("r1-paper", "paper", 8.2,
     [cap(apex=22, foot_y=42, overhang=23) + g_path(54, 64, 16, gap=46)],
     shadow=False)
cand("r1-jade", "jade", 8.2,
     [cap(apex=22, foot_y=42, overhang=23) + g_path(54, 64, 16, gap=46)])
cand("r1-twotone", "indigo", 8.2,
     [cap(apex=22, foot_y=42, overhang=23) + g_path(54, 64, 16, gap=46)],
     two_tone=True)


def svg_doc(c):
    pal, parts, stroke = c["pal"], c["parts"], c["stroke"]
    defs = (f'<defs><linearGradient id="bg" x1="0" y1="0" x2="0.35" y2="1">'
            f'<stop offset="0" stop-color="{pal["bg1"]}"/>'
            f'<stop offset="1" stop-color="{pal["bg2"]}"/></linearGradient>')
    sh = ""
    if c["shadow"]:
        defs += ('<filter id="soft" x="-25%" y="-25%" width="150%" height="150%">'
                 '<feGaussianBlur stdDeviation="1.4"/></filter>')
    defs += "</defs>"
    body = ""
    if c["shadow"]:
        for p in parts:
            body += (f'<path d="{p}" fill="none" stroke="#000" stroke-opacity="0.28" '
                     f'stroke-width="{stroke + 0.9:.2f}" stroke-linecap="round" '
                     f'stroke-linejoin="round" filter="url(#soft)"/>')
    if c["two_tone"] and len(parts) == 1:
        # split: everything above y=46 is the roof (accent), rest the G
        body += (f'<clipPath id="roof"><rect x="0" y="0" width="108" height="46"/></clipPath>'
                 f'<clipPath id="bowl"><rect x="0" y="46" width="108" height="62"/></clipPath>')
        body += (f'<path d="{parts[0]}" fill="none" stroke="{pal["accent"]}" '
                 f'stroke-width="{stroke:.2f}" stroke-linecap="round" '
                 f'stroke-linejoin="round" clip-path="url(#roof)"/>')
        body += (f'<path d="{parts[0]}" fill="none" stroke="{pal["fg"]}" '
                 f'stroke-width="{stroke:.2f}" stroke-linecap="round" '
                 f'stroke-linejoin="round" clip-path="url(#bowl)"/>')
    else:
        for p in parts:
            body += (f'<path d="{p}" fill="none" stroke="{pal["fg"]}" '
                     f'stroke-width="{stroke:.2f}" stroke-linecap="round" '
                     f'stroke-linejoin="round"/>')
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" '
            f'viewBox="0 0 108 108">{defs}'
            f'<rect x="0" y="0" width="108" height="108" rx="{c["radius"]}" '
            f'fill="url(#bg)"/>{body}</svg>')


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
    sheet.convert("RGB").save(os.path.join(PNG_DIR, f"sheet-{name}.png"))


def main():
    only = sys.argv[1:] or list(CANDS)
    names = []
    for name in only:
        c = CANDS[name]
        svg = svg_doc(c)
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
    master.save(os.path.join(PNG_DIR, "contact3.png"))
    print("contact3 done")


if __name__ == "__main__":
    main()
