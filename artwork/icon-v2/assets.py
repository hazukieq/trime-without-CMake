#!/usr/bin/env python3
"""Write the new hkrime launcher icon into the Android project.

Mark: 霞鹜文楷「客」, one character filling the tile (white field, black ink).

Produced:
  res/drawable/ic_app_icon_foreground.xml   adaptive foreground (vector)
  res/drawable/ic_app_icon_monochrome.xml   Android 13 themed-icon layer
  res/mipmap-*/ic_app_icon.png              legacy icon (white disc + 客)
  res/mipmap-*/ic_app_icon_round.png        legacy round icon
  res/mipmap-anydpi-v26/ic_app_icon*.xml    + monochrome layer
  ic_app_icon-playstore.png                 512x512 store icon
"""
import os
import subprocess

import cairosvg
from fontTools.misc.transform import Transform
from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont
from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
RES = os.path.join(REPO, "app", "src", "main", "res")
MAIN = os.path.join(REPO, "app", "src", "main")
PNG_DIR = os.path.join(HERE, "png")

WENKAI = "/home/hazukie/.fonts/lxgw-wenkai/LXGWWenKai-Bold.ttf"
INK = "#000000"
STORE_DIR = os.path.join(HERE, "store")

# scale of the character inside the 108 grid
LEGACY_CHAR = 66.0     # fits comfortably inside a disc
ADAPTIVE_CHAR = 48.0   # 108 layer -> visible 72dp box, see mask preview
STORE_CHAR = 72.0


def ke_path(ch=160.0, cx=54.0, cy=54.0):
    """SVG path data for 客 in the 108x108 grid."""
    f = TTFont(WENKAI)
    gs = f.getGlyphSet()
    name = f.getBestCmap()[ord("客")]
    bp = BoundsPen(gs); gs[name].draw(bp)
    x0, y0, x1, y1 = bp.bounds
    s = ch / (y1 - y0)
    gw, gh = (x1 - x0) * s, (y1 - y0) * s
    ox, oy = cx - gw / 2, cy - gh / 2
    tr = Transform(s, 0, 0, -s, ox - x0 * s, oy + y1 * s)
    pen = SVGPathPen(gs)
    gs[name].draw(TransformPen(pen, tr))
    return pen.getCommands()


def svg_legacy(path_d, size, shape="circle", shadow=True):
    """White disc/squircle + black 客, like the previous icon language."""
    if shape == "circle":
        bg = '<circle cx="54" cy="54" r="54" fill="#FFFFFF"/>'
    else:
        bg = '<rect width="108" height="108" rx="24" fill="#FFFFFF"/>'
    sh = ""
    if shadow:
        sh = ('<defs><filter id="sh" x="-20%" y="-20%" width="140%" height="140%">'
              '<feDropShadow dx="0" dy="1.1" stdDeviation="1.8" '
              'flood-color="#000000" flood-opacity="0.22"/></filter></defs>')
        bg = f'<g filter="url(#sh)">{bg}</g>'
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" '
            f'viewBox="0 0 108 108">{sh}{bg}'
            f'<path d="{path_d}" fill="{INK}"/></svg>')


def vector_xml(path_d, color="#000000", tint=False):
    body = (f'  <path\n      android:fillColor="{color}"\n'
            f'      android:pathData="{path_d}" />\n')
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="108dp"\n'
            '    android:height="108dp"\n'
            '    android:viewportWidth="108"\n'
            '    android:viewportHeight="108">\n'
            f'{body}</vector>\n')


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)
    print("wrote", os.path.relpath(path, REPO))


def main():
    legacy_d = ke_path(LEGACY_CHAR)
    adaptive_d = ke_path(ADAPTIVE_CHAR)
    store_d = ke_path(STORE_CHAR)

    # --- adaptive layers -------------------------------------------------
    write(os.path.join(RES, "drawable", "ic_app_icon_foreground.xml"),
          vector_xml(adaptive_d))
    write(os.path.join(RES, "drawable", "ic_app_icon_monochrome.xml"),
          vector_xml(adaptive_d))
    write(os.path.join(RES, "values", "ic_app_icon_background.xml"),
          '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
          '    <color name="ic_app_icon_background">#FFFFFF</color>\n</resources>\n')
    for n in ("ic_app_icon", "ic_app_icon_round"):
        write(os.path.join(RES, "mipmap-anydpi-v26", f"{n}.xml"),
              '<?xml version="1.0" encoding="utf-8"?>\n'
              '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
              '    <background android:drawable="@color/ic_app_icon_background"/>\n'
              '    <foreground android:drawable="@drawable/ic_app_icon_foreground"/>\n'
              '    <monochrome android:drawable="@drawable/ic_app_icon_monochrome"/>\n'
              '</adaptive-icon>\n')

    # --- legacy mipmaps --------------------------------------------------
    DENS = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for dpi, sz in DENS.items():
        for name, shape in (("ic_app_icon", "circle"), ("ic_app_icon_round", "circle")):
            svg = svg_legacy(legacy_d, sz, shape=shape)
            out = os.path.join(RES, f"mipmap-{dpi}", f"{name}.png")
            cairosvg.svg2png(bytestring=svg.encode(), write_to=out,
                             output_width=sz, output_height=sz)
            print("wrote", os.path.relpath(out, REPO))

    # --- play store -------------------------------------------------------
    os.makedirs(os.path.join(HERE, "store"), exist_ok=True)
    store_svg = ('<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" '
                 'viewBox="0 0 108 108"><rect width="108" height="108" fill="#FFFFFF"/>'
                 f'<path d="{store_d}" fill="{INK}"/></svg>')
    cairosvg.svg2png(bytestring=store_svg.encode(),
                     write_to=os.path.join(MAIN, "ic_app_icon-playstore.png"),
                     output_width=512, output_height=512)
    print("wrote", os.path.relpath(os.path.join(MAIN, "ic_app_icon-playstore.png"), REPO))

    # --- previews ---------------------------------------------------------
    prev = os.path.join(PNG_DIR, "asset-preview.png")
    cell = 260
    tiles = []
    for sz in (48, 72, 96, 144, 192):
        svg = svg_legacy(legacy_d, sz)
        p = os.path.join(PNG_DIR, f"prev-{sz}.png")
        cairosvg.svg2png(bytestring=svg.encode(), write_to=p,
                         output_width=sz, output_height=sz)
        tiles.append((sz, p))
    sheet = Image.new("RGB", (cell * len(tiles), cell + 40), (238, 238, 238))
    from PIL import ImageDraw
    dr = ImageDraw.Draw(sheet)
    for i, (sz, p) in enumerate(tiles):
        im = Image.open(p).convert("RGBA")
        up = im.resize((200, 200), Image.LANCZOS)
        sheet.paste(up, (i * cell + 30, 20), up)
        dr.text((i * cell + 90, cell + 6), f"{sz}px", fill=(60, 60, 60))
    sheet.save(prev)
    print("preview", prev)


if __name__ == "__main__":
    main()
