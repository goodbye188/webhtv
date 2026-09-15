#!/usr/bin/env python3
"""用照片生成「影视壳」launcher 图标（全套 mipmap 密度 + 圆形版）。

设计：暖白系渐变卡片 + 照片人像（主体居上，避开圆角裁剪区）。
用法:
    python scripts/gen_photo_icon.py <照片路径>           写入 app/src/main/res/mipmap-*/
    python scripts/gen_photo_icon.py <照片路径> --preview 只输出预览到 build/icon-preview/
"""
import argparse
import glob
import os
import shutil
import sys

from PIL import Image, ImageDraw

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# 各密度对应的 launcher 图标像素（与现有 mipmap 目录对应）
DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}

# 暖白系渐变（左上 暖米白 → 右下 暖杏色）
BG_A = (255, 250, 240)
BG_B = (255, 236, 214)


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def make_icon(src, size, circular):
    """生成单个图标：照片原图 1:1 不变，只套 (圆角/圆形) 蒙版。

    人物布局、背景完全保持原图，不做任何换背景/光晕处理。
    """
    SS = 4  # 超采样倍数，保证边缘平滑
    big = size * SS

    # 照片 1:1 直接缩放
    img = src.convert("RGBA").resize((big, big), Image.LANCZOS)

    # 蒙版
    mask = Image.new("L", (big, big), 0)
    if circular:
        ImageDraw.Draw(mask).ellipse([0, 0, big - 1, big - 1], fill=255)
    else:
        ImageDraw.Draw(mask).rounded_rectangle(
            [0, 0, big - 1, big - 1], radius=int(big * 0.2237), fill=255)
    img.putalpha(mask)
    return img.resize((size, size), Image.LANCZOS)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("photo")
    ap.add_argument("--preview", action="store_true")
    args = ap.parse_args()

    src = Image.open(args.photo)
    print(f"源照片: {args.photo} {src.size}")

    if args.preview:
        out = os.path.join(REPO, "build", "icon-preview")
        os.makedirs(out, exist_ok=True)
        make_icon(src, 512, False).save(os.path.join(out, "square.png"))
        make_icon(src, 512, True).save(os.path.join(out, "round.png"))
        print("预览已生成:", out)
        return

    written = []
    for dpi, px in DENSITIES.items():
        mdir = glob.glob(os.path.join(REPO, f"app/src/main/res/mipmap-{dpi}"))
        if not mdir:
            print(f"跳过 {dpi}: 目录不存在")
            continue
        mdir = mdir[0]
        # 方形版：覆盖已有 ic_launcher.png / ic_launcher_round 的 png 形式
        square = make_icon(src, px, False)
        rp = os.path.join(mdir, "ic_launcher.png")
        square.save(rp)
        written.append(rp)
        # 圆形版：现有文件是 .webp（ic_launcher_round.webp），保持 webp 格式
        rnd = make_icon(src, px, True)
        rwp = os.path.join(mdir, "ic_launcher_round.webp")
        if os.path.exists(rwp):
            rnd.save(rwp, "WEBP", quality=90)
            written.append(rwp)
        else:
            pngp = os.path.join(mdir, "ic_launcher_round.png")
            rnd.save(pngp)
            written.append(pngp)
    print(f"已写入 {len(written)} 个文件:")
    for p in written:
        print("  ", os.path.relpath(p, REPO))
    # 清掉旧 anydpi 自适应图标（如有残留，会绕过 PNG）
    adir = os.path.join(REPO, "app/src/main/res/mipmap-anydpi-v26")
    if os.path.isdir(adir):
        shutil.rmtree(adir)
        print("已删除 mipmap-anydpi-v26 (会绕过 PNG)")


if __name__ == "__main__":
    sys.exit(main())
