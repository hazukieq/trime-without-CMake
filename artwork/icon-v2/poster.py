#!/usr/bin/env python3
"""hkrime poster generator — two art directions from the real device shot.

dark : ink field, glowing phone, vertical 高峰客語
paper: cream paper, ink type, phone with soft shadow

Run:  python3 poster.py           -> both
      python3 poster.py dark      -> one
"""
import os
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
PNG = os.path.join(HERE, "png")
SHOT = "/tmp/iconcheck/shot_full.png"
WENKAI = "/home/hazukie/.fonts/lxgw-wenkai/LXGWWenKai-Regular.ttf"
WENKAI_B = "/home/hazukie/.fonts/lxgw-wenkai/LXGWWenKai-Bold.ttf"
WENKAI_L = "/home/hazukie/.fonts/lxgw-wenkai/LXGWWenKai-Light.ttf"

W, H = 1200, 1700

THEMES = {
    "dark": dict(
        top=(18, 22, 32), bottom=(7, 9, 13),
        paper=(243, 238, 226), muted=(148, 142, 128), rule=(56, 53, 48),
        gold=(205, 166, 62), chip_edge=(88, 84, 74), chip_text=(216, 208, 192),
        phone_body=(19, 21, 26), phone_edge=(62, 66, 76),
        glows=[(W * 0.32, H * 0.40, 640, (40, 66, 110), 130),
               (W * 0.90, H * 0.78, 520, (96, 64, 34), 66)],
        icon_style="disc",
    ),
    "paper": dict(
        top=(250, 246, 236), bottom=(236, 229, 215),
        paper=(26, 28, 33), muted=(120, 112, 98), rule=(196, 186, 168),
        gold=(150, 112, 30), chip_edge=(198, 188, 170), chip_text=(70, 64, 54),
        phone_body=(24, 26, 31), phone_edge=(200, 192, 178),
        glows=[(W * 0.30, H * 0.38, 700, (255, 252, 244), 120),
               (W * 0.92, H * 0.80, 480, (232, 220, 196), 90)],
        icon_style="disc",
    ),
}


def vgrad(size, top, bottom):
    w, h = size
    base = Image.new("RGB", (1, h))
    for y in range(h):
        t = y / max(1, h - 1)
        base.putpixel((0, y), tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)))
    return base.resize((w, h))


def radial_glow(size, center, radius, color, strength=255):
    w, h = size
    layer = Image.new("L", (w, h), 0)
    d = ImageDraw.Draw(layer)
    cx, cy = center
    for i in range(30, 0, -1):
        r = radius * i / 30
        a = int(strength * (1 - i / 30) ** 1.5)
        d.ellipse((cx - r, cy - r * 0.8, cx + r, cy + r * 0.8), fill=a)
    layer = layer.filter(ImageFilter.GaussianBlur(70))
    glow = Image.new("RGB", (w, h), color)
    return layer, glow


def rounded_shadow(size, box, radius, blur, opacity):
    layer = Image.new("L", size, 0)
    ImageDraw.Draw(layer).rounded_rectangle(box, radius=radius, fill=opacity)
    return layer.filter(ImageFilter.GaussianBlur(blur))


def phone_mockup(shot_path, phone_h, theme, bezel=15, radius=58):
    shot = Image.open(shot_path).convert("RGB")
    sw, sh = shot.size
    inner_w = int((phone_h - 2 * bezel) * sw / sh)
    screen = shot.resize((inner_w, phone_h - 2 * bezel), Image.LANCZOS)
    mask = Image.new("L", screen.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, inner_w - 1, screen.size[1] - 1),
                                           radius=radius, fill=255)
    screen.putalpha(mask)
    w = inner_w + 2 * bezel
    phone = Image.new("RGBA", (w, phone_h), (0, 0, 0, 0))
    d = ImageDraw.Draw(phone)
    d.rounded_rectangle((0, 0, w - 1, phone_h - 1), radius=radius + bezel,
                        fill=theme["phone_body"] + (255,),
                        outline=theme["phone_edge"] + (255,), width=2)
    phone.alpha_composite(screen, (bezel, bezel))
    d = ImageDraw.Draw(phone)
    cx, cy = w // 2, bezel + 26
    d.ellipse((cx - 9, cy - 9, cx + 9, cy + 9), fill=(8, 8, 10, 255))

    pad = 220
    out = Image.new("RGBA", (w + 2 * pad, phone_h + 2 * pad), (0, 0, 0, 0))
    sh = rounded_shadow(out.size, (pad, pad + 18, pad + w, pad + phone_h + 18),
                        radius + bezel, 46, 200)
    out.paste(Image.new("RGBA", out.size, (0, 0, 0, 255)), (0, 0), sh)
    out.alpha_composite(phone, (pad, pad))
    return out, pad


def draw_vertical(d, x, y, text, font, fill, step):
    for i, ch in enumerate(text):
        bb = d.textbbox((0, 0), ch, font=font)
        wch = bb[2] - bb[0]
        d.text((x - wch / 2 - bb[0], y + i * step), ch, font=font, fill=fill)


def build(name):
    th = THEMES[name]
    canvas = vgrad((W, H), th["top"], th["bottom"]).convert("RGB")
    for (cx, cy, r, col, s) in th["glows"]:
        layer, glow = radial_glow((W, H), (cx, cy), r, col, s)
        canvas = Image.composite(glow, canvas, layer)
    d = ImageDraw.Draw(canvas)

    # ---- phone ----------------------------------------------------------
    phone, padoff = phone_mockup(SHOT, 1180, th)
    ph_x, ph_y = 70 - padoff, 350 - padoff
    canvas.paste(phone, (ph_x, ph_y), phone)

    # ---- brand row ------------------------------------------------------
    icon = Image.open(os.path.join(PNG, "tg-prev-192.png")).convert("RGBA")
    icon = icon.resize((126, 126), Image.LANCZOS)
    ix, iy = 92, 92
    canvas.paste(icon, (ix, iy), icon)
    f_word = ImageFont.truetype(WENKAI_B, 58)
    f_sub = ImageFont.truetype(WENKAI, 25)
    d.text((ix + 152, iy + 6), "hkrime", font=f_word, fill=th["paper"])
    d.text((ix + 154, iy + 80), "客语输入法 · 高峰客家话", font=f_sub, fill=th["muted"])

    # ---- right column: vertical title -----------------------------------
    f_title = ImageFont.truetype(WENKAI_B, 124)
    tx = 880
    ty = 300
    step = 138
    draw_vertical(d, tx, ty, "高峰客語", f_title, th["paper"], step)
    f_tag = ImageFont.truetype(WENKAI, 30)
    draw_vertical(d, tx + 96, ty + 36, "输入法", f_tag, th["gold"], 42)
    d.line((tx + 70, ty + 10, tx + 70, ty + step * 4 - 60), fill=th["gold"], width=2)

    # ---- feature list ----------------------------------------------------
    f_feat = ImageFont.truetype(WENKAI, 27)
    fy = 1010
    for i, t in enumerate(("客语十二键", "客语廿六键", "雲龍國際音標", "简繁转换")):
        d.ellipse((tx - 118, fy + 12 + i * 62, tx - 108, fy + 22 + i * 62), fill=th["gold"])
        d.text((tx - 90, fy + i * 62), t, font=f_feat, fill=th["chip_text"])

    # ---- caption under phone --------------------------------------------
    f_cap = ImageFont.truetype(WENKAI_L, 25)
    d.text((100, 1560), "客语十二键 · 候选条", font=f_cap, fill=th["muted"])

    # ---- footer ----------------------------------------------------------
    d.line((92, 1608, W - 92, 1608), fill=th["rule"], width=1)
    f_foot = ImageFont.truetype(WENKAI_L, 23)
    d.text((92, 1630), "com.hazukieq.hkrime   ·   Android   ·   librime", font=f_foot,
           fill=th["muted"])

    out = os.path.join(PNG, f"poster-{name}.png")
    canvas.save(out)
    print("saved", out)
    return out


if __name__ == "__main__":
    names = sys.argv[1:] or ["dark", "paper"]
    for n in names:
        build(n)
