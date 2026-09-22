import os
from PIL import Image, ImageDraw

BG = (34, 38, 46)
BLUE = (75, 139, 190)
YELLOW = (255, 212, 59)
SS = 8
MASTER = 432

DENSITIES = {
    "mdpi": (48, 108),
    "hdpi": (72, 162),
    "xhdpi": (96, 216),
    "xxhdpi": (144, 324),
    "xxxhdpi": (192, 432),
}

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(os.path.dirname(HERE), "res")


def art(draw, size, inset):
    s = size
    x0 = y0 = inset
    x1 = y1 = s - inset
    w = x1 - x0
    h = y1 - y0

    stroke = int(0.150 * w)
    pts = [
        (x0 + 0.16 * w, y0 + 0.22 * h),
        (x0 + 0.42 * w, y0 + 0.50 * h),
        (x0 + 0.16 * w, y0 + 0.78 * h),
    ]
    draw.line(pts, fill=BLUE, width=stroke, joint="curve")
    r = stroke // 2
    for (px, py) in pts:
        draw.ellipse([px - r, py - r, px + r, py + r], fill=BLUE)

    bar = [
        x0 + 0.54 * w,
        y0 + 0.66 * h,
        x0 + 0.82 * w,
        y0 + 0.80 * h,
    ]
    draw.rounded_rectangle(bar, radius=int(0.07 * w), fill=YELLOW)


def foreground(size):
    img = Image.new("RGBA", (size * SS, size * SS), (0, 0, 0, 0))
    art(ImageDraw.Draw(img), size * SS, int(size * SS * 0.22))
    return img.resize((size, size), Image.LANCZOS)


def legacy(size):
    img = Image.new("RGBA", (size * SS, size * SS), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle(
        [0, 0, size * SS - 1, size * SS - 1], radius=int(size * SS * 0.22), fill=BG
    )
    art(d, size * SS, int(size * SS * 0.20))
    return img.resize((size, size), Image.LANCZOS)


def main():
    for name, (legacy_px, fg_px) in DENSITIES.items():
        out = os.path.join(RES, "mipmap-" + name)
        os.makedirs(out, exist_ok=True)
        legacy(legacy_px).save(os.path.join(out, "ic_launcher.png"))
        foreground(fg_px).save(os.path.join(out, "ic_launcher_fg.png"))

    anydpi = os.path.join(RES, "mipmap-anydpi-v26")
    os.makedirs(anydpi, exist_ok=True)
    with open(os.path.join(anydpi, "ic_launcher.xml"), "w") as f:
        f.write(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="@color/ic_launcher_background"/>\n'
            '    <foreground android:drawable="@mipmap/ic_launcher_fg"/>\n'
            "</adaptive-icon>\n"
        )

    values = os.path.join(RES, "values")
    os.makedirs(values, exist_ok=True)
    with open(os.path.join(values, "colors.xml"), "w") as f:
        f.write(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            "<resources>\n"
            '    <color name="ic_launcher_background">#%02X%02X%02X</color>\n'
            "</resources>\n" % BG
        )

    preview = Image.new("RGBA", (432, 432), (0, 0, 0, 0))
    preview.alpha_composite(foreground(432))
    preview.save(os.path.join(HERE, "preview_fg.png"))
    legacy(432).save(os.path.join(HERE, "preview_legacy.png"))


main()
