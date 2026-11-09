#!/usr/bin/env python3
"""hkrime poster from the four real screenshots (启动页 / 设置页 / 输入状态 / 候选面板)."""
import os

from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
PNG = os.path.join(HERE, "png")
QQ = os.path.expanduser("~/Downloads/qq")

SHOTS = [
    ("Screenshot_20261109_075016.jpg", "启动页", "开屏即品牌"),
    ("Screenshot_20261109_075011.jpg", "设置页", "方案 · 外观 · 输入配置"),
    ("Screenshot_20261109_074956.jpg", "输入状态", "客语十二键 · 候选条"),
    ("Screenshot_20261109_075001.jpg", "候选面板", "按音节分组的候选"),
]

WENKAI = "/home/hazukie/.fonts/lxgw-wenkai/LXGWWenKai-Regular.ttf"
WENKAI_B = "/home/hazukie/.fonts/lxgw-wenkai/LXGWWenKai-Bold.ttf"
WENKAI_L = "/home/hazukie/.fonts/lxgw-wenkai/LXGWWenKai-Light.ttf"

W, H = 1400, 2140
BG_TOP, BG_BOT = (19, 24, 34), (8, 10, 14)
PAPER = (242, 237, 226)
MUTED = (146, 140, 127)
GOLD = (205, 166, 62)
RULE = (54, 52, 48)

PHONE_W = 300
PHONE_H = int(PHONE_W * 2376 / 1080)          # 726
GAP_X = 112
TOP = 400
ROW_STEP = PHONE_H + 132
LEFT = (W - (2 * PHONE_W + GAP_X)) // 2


def vgrad(size, top, bottom):
    w, h = size
    base = Image.new("RGB", (1, h))
    for y in range(h):
        t = y / max(1, h - 1)
        base.putpixel((0, y), tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)))
    return base.resize((w, h))


def glow(size, center, radius, color, strength):
    layer = Image.new("L", size, 0)
    d = ImageDraw.Draw(layer)
    cx, cy = center
    for i in range(28, 0, -1):
        r = radius * i / 28
        d.ellipse((cx - r, cy - r * 0.8, cx + r, cy + r * 0.8),
                  fill=int(strength * (1 - i / 28) ** 1.5))
    layer = layer.filter(ImageFilter.GaussianBlur(70))
    return layer, Image.new("RGB", size, color)


def phone(img_path, w, h, radius=26, bezel=6):
    im = Image.open(img_path).convert("RGB")
    screen = im.resize((w - 2 * bezel, h - 2 * bezel), Image.LANCZOS)
    mask = Image.new("L", screen.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, screen.size[0] - 1, screen.size[1] - 1),
                                           radius=radius - bezel, fill=255)
    screen.putalpha(mask)

    pad = 70
    out = Image.new("RGBA", (w + 2 * pad, h + 2 * pad), (0, 0, 0, 0))
    sh = Image.new("L", out.size, 0)
    ImageDraw.Draw(sh).rounded_rectangle((pad, pad + 14, pad + w, pad + h + 14),
                                         radius=radius + bezel, fill=210)
    sh = sh.filter(ImageFilter.GaussianBlur(26))
    out.paste(Image.new("RGBA", out.size, (0, 0, 0, 255)), (0, 0), sh)

    body = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(body)
    d.rounded_rectangle((0, 0, w - 1, h - 1), radius=radius + bezel,
                        fill=(22, 24, 29, 255), outline=(64, 68, 78, 255), width=2)
    body.alpha_composite(screen, (bezel, bezel))
    out.alpha_composite(body, (pad, pad))
    return out, pad


def main():
    canvas = vgrad((W, H), BG_TOP, BG_BOT).convert("RGB")
    for (cx, cy, r, col, s) in ((W * 0.28, H * 0.30, 700, (38, 64, 106), 120),
                               (W * 0.80, H * 0.78, 620, (96, 64, 34), 70)):
        layer, g = glow((W, H), (cx, cy), r, col, s)
        canvas = Image.composite(g, canvas, layer)
    # faint Tangut watermark behind the phone grid (transparent, tinted)
    wm_path = os.path.join(PNG, "wm.png")
    if not os.path.exists(wm_path):
        import cairosvg
        import tangut2
        parts, _ = tangut2.composite_parts(100.0)
        paths = "".join(f'<path d="{d}" fill="#fff" stroke="#fff" '
                        f'stroke-width="1.7" stroke-linejoin="round"/>' for d in parts)
        svg = ('<svg xmlns="http://www.w3.org/2000/svg" width="1000" height="1000" '
               'viewBox="0 0 108 108">' + paths + '</svg>')
        cairosvg.svg2png(bytestring=svg.encode(), write_to=wm_path,
                         output_width=1000, output_height=1000)
    wm = Image.open(wm_path).convert("L").resize((1060, 1060), Image.LANCZOS)
    alpha = wm.point(lambda v: int(v * 0.085))
    canvas.paste(Image.new("RGB", wm.size, (150, 172, 208)), (W // 2 - 530, 700), alpha)

    d = ImageDraw.Draw(canvas)

    # header ---------------------------------------------------------------
    icon = Image.open(os.path.join(PNG, "tg-prev-192.png")).convert("RGBA")
    icon = icon.resize((104, 104), Image.LANCZOS)
    canvas.paste(icon, (96, 92), icon)
    d.text((228, 66), "客语输入法", font=ImageFont.truetype(WENKAI_B, 64), fill=PAPER)
    d.text((232, 150), "H A K K A   R I M E   ·   hkrime",
           font=ImageFont.truetype(WENKAI, 21), fill=GOLD)
    d.text((232, 184), "高峰客家话 · Android 开源输入法",
           font=ImageFont.truetype(WENKAI, 22), fill=MUTED)

    f_r = ImageFont.truetype(WENKAI_L, 23)
    tw = d.textlength("v3.2.7", font=f_r)
    d.text((W - 96 - tw, 104), "v3.2.7", font=f_r, fill=MUTED)
    d.text((W - 96 - d.textlength("android · librime", font=f_r), 138),
           "android · librime", font=f_r, fill=MUTED)

    # rule under header
    d.line((96, 258, W - 96, 258), fill=RULE, width=1)

    # tagline
    d.text((96, 286), "打出客语，不必切换输入法",
           font=ImageFont.truetype(WENKAI_B, 30), fill=(226, 219, 204))
    d.text((96, 330), "十二键直打  ·  音节候选分组  ·  简繁与音标一并备好",
           font=ImageFont.truetype(WENKAI_L, 21), fill=(132, 126, 112))

    # phones ---------------------------------------------------------------
    f_cap = ImageFont.truetype(WENKAI_B, 27)
    f_sub = ImageFont.truetype(WENKAI_L, 21)
    for i, (fn, title, sub) in enumerate(SHOTS):
        col, row = i % 2, i // 2
        x = LEFT + col * (PHONE_W + GAP_X)
        y = TOP + row * ROW_STEP
        p, pad = phone(os.path.join(QQ, fn), PHONE_W, PHONE_H)
        canvas.paste(p, (x - pad, y - pad), p)
        cy = y + PHONE_H + 26
        idx = f"0{i+1}"
        d.text((x, cy - 4), idx, font=ImageFont.truetype(WENKAI_L, 20), fill=(120, 112, 96))
        d.ellipse((x + 48, cy + 8, x + 57, cy + 17), fill=GOLD)
        d.text((x + 70, cy - 2), title, font=f_cap, fill=PAPER)
        d.text((x + 70, cy + 36), sub, font=f_sub, fill=MUTED)

    # footer ---------------------------------------------------------------
    d.line((96, H - 116, W - 96, H - 116), fill=RULE, width=1)
    f_foot = ImageFont.truetype(WENKAI_L, 23)
    d.text((96, H - 94), "com.hazukieq.hkrime", font=f_foot, fill=MUTED)
    right = "十二键 · 廿六键 · 雲龍音標 · 简繁"
    d.text((W - 96 - d.textlength(right, font=f_foot), H - 94), right,
           font=f_foot, fill=(102, 95, 84))

    out = os.path.join(PNG, "poster-hkrime.png")
    canvas.save(out)
    print("saved", out)


if __name__ == "__main__":
    main()
