#!/usr/bin/env python3
"""生成「AI影」launcher 图标（全套 mipmap 密度 + 圆形版）。

设计：深蓝 -> 亮蓝对角渐变圆角卡 + 白色 "AI" 字标 + "影" 播放徽章。
用法:
    python scripts/gen_ai_icon.py            写入 app/src/main/res/mipmap-*/
    python scripts/gen_ai_icon.py --preview  只输出预览到 build/icon-preview/
"""
import argparse
import os
import sys

from PIL import Image, ImageDraw, ImageFont

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# 候选中文字体（Windows 常见）
FONTS = [
    r"C:\Windows\Fonts\msyhbd.ttc",
    r"C:\Windows\Fonts\msyh.ttc",
    r"C:\Windows\Fonts\simhei.ttf",
    r"C:\Windows\Fonts\simsun.ttc",
]
FONT_PATH = next((p for p in FONTS if os.path.exists(p)), None)
if FONT_PATH is None:
    sys.exit("未找到中文字体（msyhbd/msyh/simhei/simsun），无法生成图标")

# 颜色
A = (0x12, 0x24, 0x5A)   # 左上 深藏蓝
B = (0x4D, 0x8D, 0xF7)   # 右下 亮蓝
WHITE = (255, 255, 255, 255)


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def gradient_card(size, radius_ratio=0.2237):
    """渐变圆角卡（带透明角）。"""
    SS = 4
    big = size * SS
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for y in range(big):
        t = y / big
        d.line([(0, y), (big, y)], fill=lerp(A, B, t) + (255,))
    mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, big - 1, big - 1],
                                           radius=int(big * radius_ratio), fill=255)
    img.putalpha(mask)
    return img, d


def draw_text_centered(img, text, font, center, fill):
    """把 text 画到 center 位置（按 bbox 精确居中）。"""
    d = ImageDraw.Draw(img)
    bb = d.textbbox((0, 0), text, font=font)
    w, h = bb[2] - bb[0], bb[3] - bb[1]
    d.text((center[0] - w / 2 - bb[0], center[1] - h / 2 - bb[1]), text, font=font, fill=fill)


def make_icon(size):
    img, d = gradient_card(size)
    SS = 4
    big = size * SS
    f_ai = ImageFont.truetype(FONT_PATH, int(big * 0.30))
    f_ying = ImageFont.truetype(FONT_PATH, int(big * 0.40))

    # 上部："AI" 居中
    draw_text_centered(img, "AI", f_ai, (big * 0.5, big * 0.30), WHITE)
    # 下部："影" 居中，稍大（视觉主体）
    draw_text_centered(img, "影", f_ying, (big * 0.5, big * 0.70), WHITE)
    return img.resize((size, size), Image.LANCZOS)


def make_round(size):
    """圆形版：图标裁成圆（Android roundIcon）。"""
    im = make_icon(size)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, size - 1, size - 1], fill=255)
    im.putalpha(mask)
    out.paste(im, (0, 0))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--preview", action="store_true", help="只输出预览图到 build/icon-preview/")
    args = ap.parse_args()

    preview_dir = os.path.join(REPO, "build", "icon-preview")
    if args.preview:
        os.makedirs(preview_dir, exist_ok=True)
        make_icon(512).save(os.path.join(preview_dir, "ai_movie_512.png"))
        make_round(512).save(os.path.join(preview_dir, "ai_movie_round_512.png"))
        print("预览已生成:", preview_dir)
        return

    for dpi, size in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
        out = os.path.join(REPO, "app/src/main/res", "mipmap-" + dpi)
        os.makedirs(out, exist_ok=True)
        make_icon(size).save(os.path.join(out, "ic_launcher.png"))
    make_round(192).save(os.path.join(REPO, "app/src/main/res/mipmap-xxxhdpi", "ic_launcher_round.webp"), "WEBP")
    # 其他密度的 round webp 统一用 192 缩小
    for dpi, size in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144)]:
        out = os.path.join(REPO, "app/src/main/res", "mipmap-" + dpi)
        make_round(size).save(os.path.join(out, "ic_launcher_round.webp"), "WEBP")
    print("图标已写入 app/src/main/res/mipmap-*/")


if __name__ == "__main__":
    main()
