#!/usr/bin/env python3
"""Write the 西夏文合体 (客左半 + 高右半) launcher icon into the Android project.

Same asset layout as assets.py:
  drawable/ic_app_icon_foreground.xml   adaptive foreground (2 vector paths)
  drawable/ic_app_icon_monochrome.xml   Android 13 themed layer
  mipmap-*/ic_app_icon(.round).png      legacy icons (white disc + glyph)
  mipmap-anydpi-v26/*.xml               + monochrome
  ic_app_icon-playstore.png             512
"""
import os

import cairosvg
from PIL import Image, ImageDraw, ImageFont

import assets
import tangut2

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
RES = os.path.join(REPO, "app", "src", "main", "res")
MAIN = os.path.join(REPO, "app", "src", "main")
PNG_DIR = os.path.join(HERE, "png")

INK = "#000000"
LEGACY_CHAR = 70.0     # inside the 108 disc (inscribed square is 76.4)
ADAPTIVE_CHAR = 50.0   # 108 layer -> 72dp visible box
STORE_CHAR = 78.0
BOLD = 1.70            # faux weight: Noto Serif Tangut is a little light for a 48px icon
FONT_LABEL = "/home/hazukie/.fonts/lxgw-wenkai/LXGWWenKai-Bold.ttf"


def svg_legacy(parts, size, shape="circle", shadow=True):
    bg = ('<circle cx="54" cy="54" r="54" fill="#FFFFFF"/>' if shape == "circle"
          else '<rect width="108" height="108" rx="24" fill="#FFFFFF"/>')
    sh = ""
    if shadow:
        sh = ('<defs><filter id="sh" x="-20%" y="-20%" width="140%" height="140%">'
              '<feDropShadow dx="0" dy="1.1" stdDeviation="1.8" '
              'flood-color="#000000" flood-opacity="0.22"/></filter></defs>')
        bg = f'<g filter="url(#sh)">{bg}</g>'
    body = "".join(
        f'<path d="{d}" fill="{INK}" stroke="{INK}" stroke-width="{BOLD}" '
        f'stroke-linejoin="round"/>' for d in parts)
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{size}" height="{size}" '
            f'viewBox="0 0 108 108">{sh}{bg}{body}</svg>')


def vector_xml(parts):
    body = "".join(
        f'  <path\n      android:fillColor="{INK}"\n'
        f'      android:strokeColor="{INK}"\n'
        f'      android:strokeWidth="{BOLD}"\n'
        f'      android:strokeLineJoin="round"\n'
        f'      android:pathData="{d}" />\n' for d in parts)
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:width="108dp"\n    android:height="108dp"\n'
            '    android:viewportWidth="108"\n    android:viewportHeight="108">\n'
            f'{body}</vector>\n')


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)
    print("wrote", os.path.relpath(path, REPO))


def main():
    legacy_parts, _ = tangut2.composite_parts(LEGACY_CHAR)
    adaptive_parts, _ = tangut2.composite_parts(ADAPTIVE_CHAR)
    store_parts, _ = tangut2.composite_parts(STORE_CHAR)

    write(os.path.join(RES, "drawable", "ic_app_icon_foreground.xml"),
          vector_xml(adaptive_parts))
    write(os.path.join(RES, "drawable", "ic_app_icon_monochrome.xml"),
          vector_xml(adaptive_parts))
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

    DENS = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
    for dpi, sz in DENS.items():
        for name in ("ic_app_icon", "ic_app_icon_round"):
            svg = svg_legacy(legacy_parts, sz)
            out = os.path.join(RES, f"mipmap-{dpi}", f"{name}.png")
            cairosvg.svg2png(bytestring=svg.encode(), write_to=out,
                             output_width=sz, output_height=sz)
            print("wrote", os.path.relpath(out, REPO))

    store_svg = ('<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" '
                 'viewBox="0 0 108 108"><rect width="108" height="108" fill="#FFFFFF"/>'
                 + "".join(f'<path d="{d}" fill="{INK}" stroke="{INK}" '
                           f'stroke-width="{BOLD}" stroke-linejoin="round"/>'
                           for d in store_parts) + '</svg>')
    cairosvg.svg2png(bytestring=store_svg.encode(),
                     write_to=os.path.join(MAIN, "ic_app_icon-playstore.png"),
                     output_width=512, output_height=512)
    print("wrote playstore")

    # ---------- previews ----------
    tiles = []
    for sz in (48, 72, 96, 144, 192):
        svg = svg_legacy(legacy_parts, sz)
        p = os.path.join(PNG_DIR, f"tg-prev-{sz}.png")
        cairosvg.svg2png(bytestring=svg.encode(), write_to=p,
                         output_width=sz, output_height=sz)
        tiles.append((sz, p))
    # adaptive layer + circle mask
    lay = ('<svg xmlns="http://www.w3.org/2000/svg" width="1080" height="1080" '
           'viewBox="0 0 108 108"><rect width="108" height="108" fill="#FFFFFF"/>'
           + "".join(f'<path d="{d}" fill="{INK}" stroke="{INK}" '
                     f'stroke-width="{BOLD}" stroke-linejoin="round"/>'
                     for d in adaptive_parts) + '</svg>')
    cairosvg.svg2png(bytestring=lay.encode(), write_to=os.path.join(PNG_DIR, "tg-layer.png"),
                     output_width=1080, output_height=1080)
    ad = Image.open(os.path.join(PNG_DIR, "tg-layer.png")).convert("RGBA")
    crop = ad.crop((180, 180, 900, 900)).resize((200, 200), Image.LANCZOS)
    m = Image.new("L", (200, 200), 0)
    ImageDraw.Draw(m).ellipse((0, 0, 199, 199), fill=255)
    a = Image.new("RGBA", (200, 200), (0, 0, 0, 0))
    a.paste(crop, (0, 0)); a.putalpha(m)
    bgr = Image.new("RGBA", (200, 200), (28, 30, 36, 255))
    bgr.alpha_composite(a)

    W, H = 1330, 430
    sheet = Image.new("RGB", (W, H), (246, 246, 246))
    dr = ImageDraw.Draw(sheet)
    f = ImageFont.truetype(FONT_LABEL, 19)
    fs = ImageFont.truetype(FONT_LABEL, 15)
    store = Image.open(os.path.join(MAIN, "ic_app_icon-playstore.png")).convert("RGB")
    sheet.paste(store.resize((250, 250), Image.LANCZOS), (30, 50))
    dr.text((30, 320), "Play 512", fill=(70, 70, 70), font=fs)
    sheet.paste(bgr.convert("RGB"), (320, 75))
    dr.text((320, 320), "自适应层 · 圆形遮罩", fill=(70, 70, 70), font=fs)
    x = 590
    for sz, p in tiles:
        im = Image.open(p).convert("RGBA")
        sheet.paste(im, (x, 90 + (192 - sz) // 2), im)
        dr.text((x, 90 + 192 + 8), f"{sz}px", fill=(90, 90, 90), font=fs)
        x += sz + 24
    dr.text((590, 60), "launcher 实际尺寸", fill=(70, 70, 70), font=f)
    sheet.save(os.path.join(PNG_DIR, "tangut-delivered.png"))
    print("preview png/tangut-delivered.png")


if __name__ == "__main__":
    main()
