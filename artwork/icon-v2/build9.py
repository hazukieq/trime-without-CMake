#!/usr/bin/env python3
"""Round 9 — roof character.  A plain chevron reads as '^'; a Chinese roof
(飞檐 / 山脊) with curved slopes and lifted eaves reads as 宀 and as a ridge.
"""
import math
import os

import cairosvg
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
SVG_DIR, PNG_DIR = os.path.join(HERE, "svg"), os.path.join(HERE, "png")
LABEL_FONT = "/home/hazukie/.fonts/sourcehan/SourceHanSansSC-Medium.otf"

BG1, BG2, FG, GOLD = "#2E4E76", "#111F31", "#F7F1E4", "#E8BE79"
GCY, GR, BAR = 64.0, 16.8, 0.52
STROKE = 9.2


def bowl_path():
    a = math.radians(47)
    p1 = (54 + GR * math.cos(a), GCY - GR * math.sin(a))
    p0 = (54 + GR, GCY)
    return (f"M {p1[0]:.2f} {p1[1]:.2f} A {GR:.2f} {GR:.2f} 0 1 0 {p0[0]:.2f} {p0[1]:.2f} "
            f"L {p0[0] - BAR * GR:.2f} {p0[1]:.2f}")


def roof_straight(apex=(54, 22), half=20.5, foot=42):
    return (f"M {54 - half:.2f} {foot:.2f} L {apex[0]} {apex[1]} "
            f"L {54 + half:.2f} {foot:.2f}")


def roof_curved(apex_y=21.0, half=21.0, foot=42.0, sag=3.5, tip=2.2):
    """Slopes sag slightly (ridge), tips lift = 飞檐."""
    L = (54 - half, foot)
    R = (54 + half, foot)
    A = (54, apex_y)
    # cubic: eave tip -> apex, control points pull the curve below the chord
    d = (f"M {L[0]:.2f} {L[1] - tip:.2f} "
         f"C {L[0] + 5:.2f} {L[1] + sag:.2f} {A[0] - 8:.2f} {A[1] + sag:.2f} "
         f"{A[0]:.2f} {A[1]:.2f} "
         f"C {A[0] + 8:.2f} {A[1] + sag:.2f} {R[0] - 5:.2f} {R[1] + sag:.2f} "
         f"{R[0]:.2f} {R[1] - tip:.2f}")
    return d


def roof_curved_soft(apex_y=21.5, half=21.0, foot=42.0, sag=1.8, tip=1.2):
    L, R, A = (54 - half, foot), (54 + half, foot), (54, apex_y)
    return (f"M {L[0]:.2f} {L[1] - tip:.2f} Q {A[0] - 12:.2f} {A[1] + sag * 2:.2f} "
            f"{A[0]:.2f} {A[1]:.2f} Q {A[0] + 12:.2f} {A[1] + sag * 2:.2f} "
            f"{R[0]:.2f} {R[1] - tip:.2f}")


def roof_dot(apex_y=21.0, half=21.0, foot=42.0, d=0):
    """宀 really has a dot on top: small stroke above the apex."""
    base = roof_straight((54, apex_y), half, foot)
    if d:
        base += f" M 54 {apex_y - 5.5:.2f} L 54 {apex_y - 4.2:.2f}"
    return base


def svg(roof, two_tone=False, radius=25.0, pal=(BG1, BG2, FG)):
    b1, b2, fg = pal
    bowl = bowl_path()
    allp = roof + " " + bowl
    defs = ('<defs><linearGradient id="bg" x1="0" y1="0" x2="0.3" y2="1">'
            f'<stop offset="0" stop-color="{b1}"/><stop offset="1" stop-color="{b2}"/>'
            '</linearGradient>'
            '<filter id="soft" x="-25%" y="-25%" width="150%" height="150%">'
            '<feGaussianBlur stdDeviation="1.2"/></filter></defs>')
    sh = (f'<g filter="url(#soft)" opacity="0.25"><path d="{allp}" fill="none" '
          f'stroke="#000" stroke-width="{STROKE:.2f}" stroke-linecap="round" '
          f'stroke-linejoin="round"/></g>')
    rc = GOLD if two_tone else fg
    el = lambda d, c: (f'<path d="{d}" fill="none" stroke="{c}" stroke-width="{STROKE:.2f}" '
                       f'stroke-linecap="round" stroke-linejoin="round"/>')
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" '
            f'viewBox="0 0 108 108">{defs}'
            f'<rect width="108" height="108" rx="{radius}" fill="url(#bg)"/>'
            f'{sh}{el(bowl, fg)}{el(roof, rc)}</svg>')


CANDS = {
    "n1-straight": dict(roof=roof_straight()),
    "n2-curved": dict(roof=roof_curved()),
    "n3-curved-soft": dict(roof=roof_curved_soft()),
    "n4-curved-gold": dict(roof=roof_curved(), two_tone=True),
    "n5-dot": dict(roof=roof_dot(d=1)),
    "n6-curved-sag": dict(roof=roof_curved(sag=5.0, tip=3.0, half=21.5)),
}


def main():
    names = []
    for name, kw in CANDS.items():
        s = svg(**kw)
        open(os.path.join(SVG_DIR, f"{name}.svg"), "w").write(s)
        for sz in (512, 144, 72, 48):
            cairosvg.svg2png(bytestring=s.encode(),
                             write_to=os.path.join(PNG_DIR, f"{name}-{sz}.png"),
                             output_width=sz, output_height=sz)
        names.append(name)
        print("built", name)
    W, pad = 420, 36
    cols = 3
    rows = (len(names) + cols - 1) // cols
    sheet = Image.new("RGB", (W * cols, (W + 56) * rows), (247, 247, 247))
    dr = ImageDraw.Draw(sheet)
    font = ImageFont.truetype(LABEL_FONT, 24)
    for i, n in enumerate(names):
        im = Image.open(os.path.join(PNG_DIR, f"{n}-512.png")).convert("RGB")
        im.thumbnail((W - 2 * pad, W - 2 * pad), Image.LANCZOS)
        x = (i % cols) * W + (W - im.width) // 2
        y = (i // cols) * (W + 56) + pad
        sheet.paste(im, (x, y))
        dr.text(((i % cols) * W + pad, y + im.height + 6), n, fill=(40, 40, 40),
                font=font)
    sheet.save(os.path.join(PNG_DIR, "contact9.png"))
    print("contact9 done")


if __name__ == "__main__":
    main()
