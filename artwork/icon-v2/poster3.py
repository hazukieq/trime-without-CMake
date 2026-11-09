#!/usr/bin/env python3
"""hkrime poster v3 — 浅色系 / 超高清。

Layout is authored in 1400x2140 design units, then everything (fonts, geometry,
image sizes) is multiplied by SCALE and rendered directly at that resolution, so
nothing is upscaled: the 1080px-wide device shots are always downsampled.

    python3 poster3.py light 3.0     -> 4200 x 6420
    python3 poster3.py dark  3.0
"""
import os
import sys

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

W, H = 1400, 2140                 # design units
PHONE_W = 300
PHONE_H = int(PHONE_W * 2376 / 1080)
GAP_X = 112
TOP = 400
ROW_STEP = PHONE_H + 132
LEFT = (W - (2 * PHONE_W + GAP_X)) // 2

THEMES = {
    "light": dict(
        bg_top=(252, 248, 240), bg_bot=(238, 231, 217),
        ink=(24, 26, 31), muted=(122, 114, 100), faint=(168, 158, 140),
        gold=(158, 116, 32), rule=(206, 196, 178),
        phone_body=(26, 28, 33), phone_edge=(206, 198, 184),
        halo=(255, 255, 255), wm=(90, 84, 72), wm_alpha=0.045,
        shadow=120,
        glows=[(W * 0.22, H * 0.16, 760, (255, 252, 244), 210),
               (W * 0.86, H * 0.62, 700, (246, 238, 222), 170)],
    ),
    "dark": dict(
        bg_top=(19, 24, 34), bg_bot=(8, 10, 14),
        ink=(242, 237, 226), muted=(146, 140, 127), faint=(110, 104, 92),
        gold=(205, 166, 62), rule=(54, 52, 48),
        phone_body=(22, 24, 29), phone_edge=(64, 68, 78),
        halo=(255, 255, 255), wm=(150, 172, 208), wm_alpha=0.085,
        shadow=210,
        glows=[(W * 0.28, H * 0.30, 700, (38, 64, 106), 120),
               (W * 0.80, H * 0.78, 620, (96, 64, 34), 70)],
    ),
}


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
    layer = layer.filter(ImageFilter.GaussianBlur(int(radius * 0.11) + 1))
    return layer, Image.new("RGB", size, color)


def icon_disc(size=1024):
    """White disc + 西夏文合体 mark, rendered from vector at any size."""
    path = os.path.join(PNG, f"icon-disc-{size}.png")
    if not os.path.exists(path):
        import cairosvg
        import tangut2
        parts, _ = tangut2.composite_parts(66.0)
        paths = "".join(f'<path d="{d}" fill="#000" stroke="#000" '
                        f'stroke-width="1.5" stroke-linejoin="round"/>' for d in parts)
        svg = ('<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" '
               'viewBox="0 0 108 108"><circle cx="54" cy="54" r="54" fill="#FFFFFF"/>'
               + paths + '</svg>')
        cairosvg.svg2png(bytestring=svg.encode(), write_to=path,
                         output_width=size, output_height=size)
    return path


def watermark_png(fg):
    wm_path = os.path.join(PNG, f"wm-{fg}.png")
    if not os.path.exists(wm_path):
        import cairosvg
        import tangut2
        parts, _ = tangut2.composite_parts(100.0)
        paths = "".join(f'<path d="{d}" fill="{fg}" stroke="{fg}" '
                        f'stroke-width="1.7" stroke-linejoin="round"/>' for d in parts)
        n = 3600
        svg = (f'<svg xmlns="http://www.w3.org/2000/svg" width="{n}" height="{n}" '
               'viewBox="0 0 108 108">' + paths + '</svg>')
        cairosvg.svg2png(bytestring=svg.encode(), write_to=wm_path,
                         output_width=n, output_height=n)
    return wm_path


def phone(img_path, w, h, th, s, radius=26, bezel=6):
    im = Image.open(img_path).convert("RGB")
    tw, th_ = max(1, int(round((w - 2 * bezel) * s))), max(1, int(round((h - 2 * bezel) * s)))
    screen = im.resize((tw, th_), Image.LANCZOS)
    mask = Image.new("L", screen.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, screen.size[0] - 1, screen.size[1] - 1),
        radius=int((radius - bezel) * s), fill=255)
    screen.putalpha(mask)

    pad = int(70 * s)
    Wp, Hp = int(round(w * s)), int(round(h * s))
    out = Image.new("RGBA", (Wp + 2 * pad, Hp + 2 * pad), (0, 0, 0, 0))
    sh = Image.new("L", out.size, 0)
    ImageDraw.Draw(sh).rounded_rectangle(
        (pad, pad + int(14 * s), pad + Wp, pad + Hp + int(14 * s)),
        radius=int((radius + bezel) * s), fill=th["shadow"])
    sh = sh.filter(ImageFilter.GaussianBlur(int(26 * s)))
    out.paste(Image.new("RGBA", out.size, (0, 0, 0, 255)), (0, 0), sh)

    body = Image.new("RGBA", (Wp, Hp), (0, 0, 0, 0))
    d = ImageDraw.Draw(body)
    d.rounded_rectangle((0, 0, Wp - 1, Hp - 1), radius=int((radius + bezel) * s),
                        fill=th["phone_body"] + (255,),
                        outline=th["phone_edge"] + (255,), width=max(2, int(2 * s)))
    body.alpha_composite(screen, (int(bezel * s), int(bezel * s)))
    out.alpha_composite(body, (pad, pad))
    return out, pad


def build(mode="light", s=3.0):
    th = THEMES[mode]
    Wp, Hp = int(W * s), int(H * s)
    canvas = vgrad((Wp, Hp), th["bg_top"], th["bg_bot"]).convert("RGB")
    for (cx, cy, r, col, st) in th["glows"]:
        layer, g = glow((Wp, Hp), (cx * s, cy * s), r * s, col, st)
        canvas = Image.composite(g, canvas, layer)

    # watermark
    wm = Image.open(watermark_png("#%02X%02X%02X" % th["wm"])).convert("L")
    wm = wm.resize((int(1060 * s), int(1060 * s)), Image.LANCZOS)
    alpha = wm.point(lambda v: int(v * th["wm_alpha"]))
    canvas.paste(Image.new("RGB", wm.size, th["wm"]),
                 (int((W / 2 - 530) * s), int(700 * s)), alpha)

    d = ImageDraw.Draw(canvas)

    def F(path, size):
        return ImageFont.truetype(path, int(size * s))

    def text(xy, t, font, fill, anchor=None):
        d.text((xy[0] * s, xy[1] * s), t, font=font, fill=fill, anchor=anchor)

    # ---- header ---------------------------------------------------------
    icon_px = int(104 * s)
    # soft shadow so the white disc separates from the paper
    sh = Image.new("L", (Wp, Hp), 0)
    ImageDraw.Draw(sh).rounded_rectangle(
        (int((92 + 4) * s), int((88 + 7) * s), int((92 + 104 + 4) * s), int((88 + 104 + 7) * s)),
        radius=int(52 * s), fill=70 if mode == "light" else 150)
    sh = sh.filter(ImageFilter.GaussianBlur(int(9 * s)))
    canvas.paste(Image.new("RGB", (Wp, Hp), (90, 80, 62)), (0, 0), sh)
    icon = Image.open(icon_disc(1024)).convert("RGBA").resize((icon_px, icon_px),
                                                              Image.LANCZOS)
    canvas.paste(icon, (int(92 * s), int(88 * s)), icon)

    text((228, 64), "客语输入法", F(WENKAI_B, 64), th["ink"])
    text((232, 150), "H A K K A   R I M E   ·   hkrime", F(WENKAI, 21), th["gold"])
    text((232, 184), "高峰客家话 · Android 开源输入法", F(WENKAI, 22), th["muted"])

    fr = F(WENKAI_L, 23)
    for i, line in enumerate(("v3.2.7", "android · librime")):
        tw = d.textlength(line, font=fr)
        text((W - 96 - tw / s, 104 + i * 34), line, fr, th["muted"])

    d.line((96 * s, 258 * s, (W - 96) * s, 258 * s), fill=th["rule"],
           width=max(2, int(1.5 * s)))

    # ---- tagline --------------------------------------------------------
    text((96, 284), "打出客语，不必切换输入法", F(WENKAI_B, 32), th["ink"])
    text((96, 332), "十二键直打  ·  音节候选分组  ·  简繁与音标一并备好",
         F(WENKAI_L, 21), th["muted"])

    # ---- phones ---------------------------------------------------------
    f_cap = F(WENKAI_B, 27)
    f_sub = F(WENKAI_L, 21)
    f_idx = F(WENKAI_L, 20)
    for i, (fn, title, sub) in enumerate(SHOTS):
        col, row = i % 2, i // 2
        x = LEFT + col * (PHONE_W + GAP_X)
        y = TOP + row * ROW_STEP
        p, pad = phone(os.path.join(QQ, fn), PHONE_W, PHONE_H, th, s)
        canvas.paste(p, (int(x * s) - pad, int(y * s) - pad), p)

        cy = y + PHONE_H + 26
        text((x, cy - 4), f"0{i+1}", f_idx, th["faint"])
        dot = int(4.5 * s)
        cx0, cy0 = int((x + 52) * s), int((cy + 12) * s)
        d.ellipse((cx0 - dot, cy0 - dot, cx0 + dot, cy0 + dot), fill=th["gold"])
        text((x + 70, cy - 2), title, f_cap, th["ink"])
        text((x + 70, cy + 36), sub, f_sub, th["muted"])

    # ---- footer ---------------------------------------------------------
    d.line((96 * s, (H - 116) * s, (W - 96) * s, (H - 116) * s),
           fill=th["rule"], width=max(2, int(1.5 * s)))
    ff = F(WENKAI_L, 23)
    text((96, H - 94), "com.hazukieq.hkrime", ff, th["muted"])
    right = "十二键 · 廿六键 · 雲龍音標 · 简繁"
    tw = d.textlength(right, font=ff)
    text((W - 96 - tw / s, H - 94), right, ff, th["faint"])

    out = os.path.join(PNG, f"poster-{mode}-hi.png")
    canvas.save(out, compress_level=6)
    print(f"saved {out}  {Wp}x{Hp}")
    return out


if __name__ == "__main__":
    mode = sys.argv[1] if len(sys.argv) > 1 else "light"
    scale = float(sys.argv[2]) if len(sys.argv) > 2 else 3.0
    build(mode, scale)
