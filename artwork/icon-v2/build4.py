#!/usr/bin/env python3
"""Round 4 — finalists.  Auto-centres the glyph optically and renders size strips.

Design: 客 + G in one ligature.
  宀  -> the roof / mountain ridge (peak: 高峰)
  各  -> the G bowl (口 becomes the G counter), 夂 implied by the G's bar
"""
import io
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
    "indigo": dict(bg1="#2E4E76", bg2="#111F31", fg="#F7F1E4", roof="#F7F1E4"),
    "indigo2": dict(bg1="#2E4E76", bg2="#111F31", fg="#F7F1E4", roof="#E8BE79"),
    "seal":   dict(bg1="#C74734", bg2="#8B2317", fg="#FFF9EE", roof="#FFF9EE"),
    "paper":  dict(bg1="#FCF9F1", bg2="#EDE5D4", fg="#23262B", roof="#23262B"),
    "jade":   dict(bg1="#20514A", bg2="#0D2422", fg="#F3F0E3", roof="#DCC08A"),
    "night":  dict(bg1="#171A22", bg2="#07090D", fg="#EBE2CC", roof="#EBE2CC"),
}


def pt(cx, cy, r, deg):
    a = math.radians(deg)
    return cx + r * math.cos(a), cy - r * math.sin(a)


def f(p):
    return f"{p[0]:.2f} {p[1]:.2f}"


def g_path(cx, cy, r, gap=47.0, bar=0.52):
    p1 = pt(cx, cy, r, gap)
    p0 = pt(cx, cy, r, 0)
    return (f"M {f(p1)} A {r:.2f} {r:.2f} 0 1 0 {f(p0)} "
            f"L {p0[0] - bar * r:.2f} {p0[1]:.2f}")


def roof_path(apex, foot_y, cx=54.0, half=22.0, flat=0.0, tip=0.0):
    """宀 as a ridge: two slopes (optionally flat-topped), feet slightly splayed."""
    L, R = cx - half, cx + half
    d = f"M {L:.2f} {foot_y:.2f} "
    if flat:
        d += f"L {cx - flat:.2f} {apex:.2f} "
        if tip:                    # tiny cap on the ridge (peak snow / 宀 dot)
            d += f"L {cx - tip:.2f} {apex - tip:.2f} "
            d += f"L {cx + tip:.2f} {apex - tip:.2f} "
        d += f"L {cx + flat:.2f} {apex:.2f} "
    else:
        if tip:
            d += f"L {cx - tip:.2f} {apex + tip * 0.85:.2f} "
        d += f"L {cx:.2f} {apex:.2f} "
        if tip:
            d += f"L {cx + tip:.2f} {apex + tip * 0.85:.2f} "
    d += f"L {R:.2f} {foot_y:.2f}"
    return d


CANDS = {}


def cand(name, pal, stroke, d, radius=25.0, two_tone=False, shadow=True,
         bg_ring=False, label=""):
    CANDS[name] = dict(pal=PAL[pal], stroke=stroke, d=d, radius=radius,
                       two_tone=two_tone, shadow=shadow, bg_ring=bg_ring,
                       label=label)


# base geometry: G bowl cy=64 r=16.5, roof apex 22 feet 43 half 22
G = g_path(54, 64, 16.5, gap=47)
R = roof_path(apex=22.5, foot_y=43, half=22)

cand("f1-indigo", "indigo", 8.4, R + " " + G, label="indigo / ivory")
cand("f2-goldpeak", "indigo2", 8.4, R + " " + G, two_tone=True, label="indigo / gold peak")
cand("f3-seal", "seal", 9.0, R + " " + G, radius=19, shadow=False, label="seal red")
cand("f4-paper", "paper", 8.4, R + " " + G, shadow=False, label="paper / ink")
cand("f5-jade", "jade", 8.4, R + " " + G, two_tone=True, label="jade / gold")
cand("f6-night", "night", 8.4, R + " " + G, label="night")
# peaked ridge with a small cap (reads as the 宀 dot / snow-capped peak)
cand("f7-cap", "indigo", 8.4,
     roof_path(apex=21.5, foot_y=43, half=22, flat=3.0, tip=1.6) + " " + G,
     two_tone=True, label="capped ridge")
# flatter 宀 (roof, less mountain)
cand("f8-flat", "indigo", 8.4,
     roof_path(apex=23, foot_y=42, half=21.5, flat=8.0) + " " + g_path(54, 64, 16.5, gap=50),
     label="flat 宀")
# ---- experiment: 夂 diagonal instead of the G's horizontal bar
def g_diag(cx, cy, r, gap=47.0):
    p1 = pt(cx, cy, r, gap)
    p0 = pt(cx, cy, r, 0)
    return (f"M {f(p1)} A {r:.2f} {r:.2f} 0 1 0 {f(p0)} "
            f"L {cx + 0.10 * r:.2f} {cy - 0.62 * r:.2f}")


cand("x1-diag", "indigo", 8.4, R + " " + g_diag(54, 64, 16.5), label="夂 diagonal bar")


# ---------------------------------------------------------------- rendering
def _glyph_png(d, stroke, size=512):
    svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" '
           f'viewBox="0 0 108 108"><path d="{d}" fill="none" stroke="#000" '
           f'stroke-width="{stroke:.2f}" stroke-linecap="round" '
           f'stroke-linejoin="round"/></svg>')
    return Image.open(io.BytesIO(cairosvg.svg2png(
        bytestring=svg.encode(), output_width=size, output_height=size))).convert("RGBA")


def autocentre(d, stroke):
    im = _glyph_png(d, stroke)
    bbox = im.split()[3].getbbox()
    if not bbox:
        return d, (0, 0)
    scale = 108 / 512
    x0, y0, x1, y1 = [v * scale for v in bbox]
    dx = 54 - (x0 + x1) / 2
    dy = 54 - (y0 + y1) / 2
    return d, (round(dx, 2), round(dy, 2))


def svg_doc(c):
    pal, stroke = c["pal"], c["stroke"]
    d, (dx, dy) = autocentre(c["d"], stroke)
    c["dxdy"] = (dx, dy)
    defs = (f'<defs><linearGradient id="bg" x1="0" y1="0" x2="0.3" y2="1">'
            f'<stop offset="0" stop-color="{pal["bg1"]}"/>'
            f'<stop offset="1" stop-color="{pal["bg2"]}"/></linearGradient>')
    sh = ""
    if c["shadow"]:
        defs += ('<filter id="soft" x="-25%" y="-25%" width="150%" height="150%">'
                 '<feGaussianBlur stdDeviation="1.3"/></filter>')
    defs += "</defs>"
    body = ""
    if c["shadow"]:
        body += (f'<g filter="url(#soft)"><path d="{d}" fill="none" stroke="#000" '
                 f'stroke-opacity="0.26" stroke-width="{stroke + 0.8:.2f}" '
                 f'stroke-linecap="round" stroke-linejoin="round"/></g>')
    if c["two_tone"]:
        body += ('<clipPath id="up"><rect x="0" y="0" width="108" height="47"/></clipPath>'
                 '<clipPath id="dn"><rect x="0" y="47" width="108" height="61"/></clipPath>')
    def draw(color, clip=""):
        cp = f' clip-path="url(#{clip})"' if clip else ""
        return (f'<path d="{d}" fill="none" stroke="{color}" stroke-width="{stroke:.2f}" '
                f'stroke-linecap="round" stroke-linejoin="round"{cp}/>')
    if c["two_tone"]:
        body += draw(pal["roof"], "up") + draw(pal["fg"], "dn")
    else:
        body += draw(pal["fg"])
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" '
            f'viewBox="0 0 108 108">{defs}'
            f'<rect x="0" y="0" width="108" height="108" rx="{c["radius"]}" '
            f'fill="url(#bg)"/><g transform="translate({dx:.2f},{dy:.2f})">{body}</g></svg>')


def sizes(name, path):
    out = {}
    for s in (512, 256, 144, 96, 72, 48):
        p = os.path.join(PNG_DIR, f"_{name}_{s}.png")
        cairosvg.svg2png(url=path, write_to=p, output_width=s, output_height=s)
        out[s] = Image.open(p).convert("RGBA")
    return out


def strip(name, imgs):
    w = 256
    cells = [(imgs[512].resize((w, w), Image.LANCZOS), "512"),
             (imgs[144].resize((w, w), Image.LANCZOS), "144"),
             (imgs[96].resize((w, w), Image.NEAREST), "96"),
             (imgs[72].resize((w, w), Image.NEAREST), "72"),
             (imgs[48].resize((w, w), Image.NEAREST), "48")]
    sheet = Image.new("RGBA", (w * len(cells) + 8 * (len(cells) - 1), w), (250, 250, 250, 255))
    for i, (im, _) in enumerate(cells):
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
        sizes(name, p)
        strip(name, sizes(name, p))
        names.append(name)
        print(f"built {name}  shift={c['dxdy']}")
    cell, cols = 300, min(4, len(names))
    rows = (len(names) + cols - 1) // cols
    master = Image.new("RGB", (cell * cols, cell * rows), (248, 248, 248))
    for i, name in enumerate(names):
        im = Image.open(os.path.join(PNG_DIR, f"{name}-512.png")).convert("RGB")
        im.thumbnail((cell - 20, cell - 20), Image.LANCZOS)
        master.paste(im, ((i % cols) * cell + 10, (i // cols) * cell + 10))
    master.save(os.path.join(PNG_DIR, "contact4.png"))
    print("contact4 done")


if __name__ == "__main__":
    main()
