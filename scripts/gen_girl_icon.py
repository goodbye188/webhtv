#!/usr/bin/env python3
"""生成「影视壳」launcher 图标（少女头像，动漫插画风，全套 mipmap 密度 + 圆形版）。

设计：柔和粉紫渐变圆角卡 + 插画少女头像（后发→脸→五官→三尖刘海→发丝高光）。
分层 alpha_composite 保证头发/脸层次正确。
用法:
    python scripts/gen_girl_icon.py            写入 app/src/main/res/mipmap-*/
    python scripts/gen_girl_icon.py --preview  只输出预览到 build/icon-preview/
"""
import argparse
import os

from PIL import Image, ImageDraw, ImageFilter

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# ---- 配色 ----
BG_A = (0xFF, 0xE6, 0xF2)       # 左上 淡粉
BG_B = (0xDC, 0xCB, 0xFF)       # 右下 淡紫
HAIR = (0x4A, 0x2E, 0x3E, 255)        # 深栗紫发
HAIR_DARK = (0x38, 0x22, 0x30, 255)   # 发暗部
HAIR_HI = (0x9A, 0x62, 0x7A, 255)     # 发丝高光
SKIN = (0xFF, 0xEC, 0xDA, 255)        # 肤色
IRIS = (0x5A, 0x34, 0x28, 255)        # 虹膜 深棕
IRIS_DARK = (0x2A, 0x16, 0x10, 255)   # 虹膜深
WHITE = (255, 255, 255, 255)
BROW = (0x4A, 0x2E, 0x38, 255)
MOUTH = (0xD8, 0x64, 0x72, 255)
BLUSH = (0xFF, 0x9E, 0x92, 70)         # 腮红(半透明)


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3))


def background_card(size):
    SS = 4
    big = size * SS
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    for y in range(big):
        d.line([(0, y), (big, y)], fill=lerp(BG_A, BG_B, y / big) + (255,))
    mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, big - 1, big - 1],
                                           radius=int(big * 0.2237), fill=255)
    img.putalpha(mask)
    return img, big


def layer(big):
    return Image.new("RGBA", (big, big), (0, 0, 0, 0))


def U(big, x):
    """相对坐标 (0..1) -> 像素。"""
    return x * big


def draw_back_hair(big):
    """后发 + 两侧长发（在脸后面）。"""
    L = layer(big)
    d = ImageDraw.Draw(L)
    # 头顶+两耳的椭圆主体
    d.ellipse([U(big, 0.24), U(big, 0.16), U(big, 0.76), U(big, 0.58)], fill=HAIR)
    # 两侧长发垂到接近卡底，底部收口（发尖）
    for x0, x1 in ((0.16, 0.40), (0.60, 0.84)):
        d.rounded_rectangle([U(big, x0), U(big, 0.40), U(big, x1), U(big, 0.96)],
                            radius=int(U(big, 0.10)), fill=HAIR)
        # 发尖（底部一个小 V）
        cx = (x0 + x1) / 2
        d.polygon([(U(big, x0 + 0.02), U(big, 0.94)),
                   (U(big, x1 - 0.02), U(big, 0.94)),
                   (U(big, cx), U(big, 1.02))], fill=HAIR)
    L = L.filter(ImageFilter.GaussianBlur(2))
    return L


def draw_face(big):
    """脸 + 下巴（皮色层）。"""
    L = layer(big)
    d = ImageDraw.Draw(L)
    # 脸主椭圆
    d.ellipse([U(big, 0.30), U(big, 0.32), U(big, 0.70), U(big, 0.82)], fill=SKIN)
    # 下巴收尖：下方再叠一个更窄的椭圆
    d.ellipse([U(big, 0.40), U(big, 0.62), U(big, 0.60), U(big, 0.92)], fill=SKIN)
    L = L.filter(ImageFilter.GaussianBlur(3))
    return L


def draw_features(big):
    """眼、眉、嘴、腮红。"""
    L = layer(big)
    d = ImageDraw.Draw(L)
    eye_y = U(big, 0.58)
    eye_w, eye_h = U(big, 0.078), U(big, 0.115)
    for ex in (U(big, 0.395), U(big, 0.605)):
        ellipse = d.ellipse([ex - eye_w / 2, eye_y - eye_h / 2, ex + eye_w / 2, eye_y + eye_h / 2],
                            fill=WHITE)
        d.ellipse([ex - eye_w * 0.41, eye_y - eye_h * 0.43, ex + eye_w * 0.41, eye_y + eye_h * 0.43],
                  fill=IRIS)
        d.ellipse([ex - eye_w * 0.24, eye_y - eye_h * 0.25, ex + eye_w * 0.24, eye_y + eye_h * 0.30],
                  fill=IRIS_DARK)
        # 高光一大二小
        d.ellipse([ex - eye_w * 0.30, eye_y - eye_h * 0.42, ex - eye_w * 0.04, eye_y - eye_h * 0.16],
                  fill=WHITE)
        d.ellipse([ex + eye_w * 0.10, eye_y + eye_h * 0.18, ex + eye_w * 0.30, eye_y + eye_h * 0.38],
                  fill=(255, 255, 255, 210))
        # 上眼线
        d.line([(ex - eye_w * 0.5, eye_y - eye_h * 0.46), (ex + eye_w * 0.5, eye_y - eye_h * 0.46)],
               fill=BROW, width=max(2, int(U(big, 0.011))))
        # 眉（略开，在眼上方）
        d.arc([ex - eye_w * 0.62, U(big, 0.455), ex + eye_w * 0.62, U(big, 0.51)],
              start=205, end=335, fill=BROW, width=max(2, int(U(big, 0.012))))
    # 嘴（微笑小弧）
    d.arc([U(big, 0.455), U(big, 0.655), U(big, 0.545), U(big, 0.715)],
          start=15, end=165, fill=MOUTH, width=max(2, int(U(big, 0.013))))
    # 腮红（小，贴近眼下）
    b = layer(big)
    bd = ImageDraw.Draw(b)
    for bx in (U(big, 0.335), U(big, 0.665)):
        bd.ellipse([bx - U(big, 0.05), U(big, 0.635), bx + U(big, 0.05), U(big, 0.675)],
                   fill=BLUSH)
    b = b.filter(ImageFilter.GaussianBlur(6))
    L = Image.alpha_composite(L, b)
    return L


def draw_front_hair(big):
    """三尖刘海（盖额头，露双眼）+ 发丝高光。"""
    L = layer(big)
    d = ImageDraw.Draw(L)
    # 额头覆盖椭圆
    d.ellipse([U(big, 0.26), U(big, 0.16), U(big, 0.74), U(big, 0.50)], fill=HAIR)
    d.rectangle([U(big, 0.30), U(big, 0.30), U(big, 0.70), U(big, 0.47)], fill=HAIR)
    # 三尖（向下垂的 M 形刘海尖，底端止于眉上方）
    tips = [
        (0.30, 0.42, 0.44, 0.42, 0.37, 0.55),   # 左尖
        (0.44, 0.40, 0.56, 0.40, 0.50, 0.56),   # 中尖(最长)
        (0.56, 0.42, 0.70, 0.42, 0.63, 0.55),   # 右尖
    ]
    for x0, y0, x1, y1, cx, cy in tips:
        d.polygon([(U(big, x0), U(big, y0)),
                   (U(big, x1), U(big, y1)),
                   (U(big, cx), U(big, cy))], fill=HAIR)
    # 刘海发丝高光
    for fx, fy in ((0.38, 0.24), (0.45, 0.22), (0.55, 0.22), (0.62, 0.24)):
        d.line([(U(big, fx), U(big, fy)), (U(big, fx + 0.015), U(big, fy + 0.14))],
               fill=HAIR_HI, width=max(1, int(U(big, 0.008))))
    # 两侧发束高光
    for fx in (0.21, 0.79):
        d.line([(U(big, fx), U(big, 0.44)), (U(big, fx), U(big, 0.90))],
               fill=HAIR_HI, width=max(1, int(U(big, 0.010))))
    L = L.filter(ImageFilter.GaussianBlur(1))
    return L


def make_icon(size):
    img, big = background_card(size)
    img = Image.alpha_composite(img, draw_back_hair(big))
    img = Image.alpha_composite(img, draw_face(big))
    img = Image.alpha_composite(img, draw_features(big))
    img = Image.alpha_composite(img, draw_front_hair(big))
    return img.resize((size, size), Image.LANCZOS)


def make_round(size):
    im = make_icon(size)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, size - 1, size - 1], fill=255)
    im.putalpha(mask)
    out.paste(im, (0, 0))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--preview", action="store_true")
    args = ap.parse_args()

    preview_dir = os.path.join(REPO, "build", "icon-preview")
    if args.preview:
        os.makedirs(preview_dir, exist_ok=True)
        make_icon(512).save(os.path.join(preview_dir, "girl_512.png"))
        make_round(512).save(os.path.join(preview_dir, "girl_round_512.png"))
        print("预览已生成:", preview_dir)
        return

    for dpi, sz in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
        out = os.path.join(REPO, "app/src/main/res", "mipmap-" + dpi)
        os.makedirs(out, exist_ok=True)
        make_icon(sz).save(os.path.join(out, "ic_launcher.png"))
    make_round(192).save(os.path.join(REPO, "app/src/main/res/mipmap-xxxhdpi", "ic_launcher_round.webp"), "WEBP")
    for dpi, sz in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144)]:
        out = os.path.join(REPO, "app/src/main/res", "mipmap-" + dpi)
        make_round(sz).save(os.path.join(out, "ic_launcher_round.webp"), "WEBP")
    print("图标已写入 app/src/main/res/mipmap-*/")


if __name__ == "__main__":
    main()
