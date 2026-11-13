#!/usr/bin/env python3
"""Draws NZStreamer's launcher icon, TV banner and splash mark into app/src/main/res.

The mark is a play triangle whose back end runs out into three lines, a stream coming in. Its
shape is defined once, below, in the 108 x 108 units of an adaptive icon's layers: the vector
drawables take it as path data, and the PNGs (legacy icons, the TV banner) are drawn from the same
points. The splash animates it on Android 12+, the lines running in from the left. Colours are the
app's (ui/Theme.kt AppColors); the wordmark is Sora Bold, from res/font.

Run from anywhere with Python 3 and Pillow: python3 -I tools/brand/generate.py
With --preview DIR it draws the splash as it plays into DIR instead, and writes nothing else.
"""
import argparse
from pathlib import Path

from PIL import Image, ImageChops, ImageColor, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "app/src/main/res"
SORA = RES / "font/sora.ttf"

BACKGROUND = "#0E0F12"
SURFACE = "#1A1C21"
ACCENT = "#F2A33A"
TEXT = "#F3F1EC"

# The triangle: 44 units tall, centred on (54, 54) so that the middle of its box and its centroid
# straddle the centre (a play triangle centred by its box looks pushed to the left).
HEIGHT = 44.0
WIDTH = 38.0
TOP = 54 - HEIGHT / 2
BOTTOM = 54 + HEIGHT / 2
LEFT = 54 - WIDTH * 5 / 12
RIGHT = LEFT + WIDTH
# Two slits across its back end, as far in as SLIT of its width, cut it into three equal lines.
GAP = 4.0
SLIT = 0.45
LINE = (HEIGHT - 2 * GAP) / 3
INNER = LEFT + WIDTH * SLIT
# The bottoms of the first two lines, where the slits start.
FIRST = TOP + LINE
SECOND = TOP + 2 * LINE + GAP

# The splash: Android 12+ runs the three lines in from the left into the triangle's front, which
# stands still; then the mark is still. Each line (top to bottom) starts as far left as the units
# given and takes the milliseconds given to slide into place, slowing down: the middle one, from
# further, comes in last. All three fade in over SPLASH_FADE.
# Android masks the icon to the circle two thirds as wide as its box (72 of the 108 units): the
# lines keep to the adaptive icon's 66 unit safe zone inside it wherever they are.
SPLASH_LINES = [("line_top", 8.0, 450), ("line_middle", 13.0, 600), ("line_bottom", 8.0, 450)]
SPLASH_FADE = 150
# How far each line runs on under the front, so no seam shows where they meet.
OVERLAP = 0.5

GENERATED = "Made by tools/brand/generate.py: change the generator, not this file."


def mark():
    """The mark's outline, as (x, y) in the layers' 108 units: the triangle, less the two slits."""
    return [
        (LEFT, TOP), (RIGHT, 54.0), (LEFT, BOTTOM),
        (LEFT, SECOND + GAP), (INNER, SECOND + GAP), (INNER, SECOND), (LEFT, SECOND),
        (LEFT, FIRST + GAP), (INNER, FIRST + GAP), (INNER, FIRST), (LEFT, FIRST),
    ]


def edges(x):
    """Where the triangle's top and bottom edges are at [x]."""
    rise = (x - LEFT) / WIDTH * HEIGHT / 2
    return TOP + rise, BOTTOM - rise


def front():
    """The triangle's front, from where the slits end: the part of the mark the splash keeps still."""
    top, bottom = edges(INNER)
    return [(INNER, top), (RIGHT, 54.0), (INNER, bottom)]


def lines():
    """The three lines of the mark's back end, top to bottom, each running OVERLAP on under the front."""
    end = INNER + OVERLAP
    top, bottom = edges(end)
    return [
        [(LEFT, TOP), (end, top), (end, FIRST), (LEFT, FIRST)],
        [(LEFT, FIRST + GAP), (end, FIRST + GAP), (end, SECOND), (LEFT, SECOND)],
        [(LEFT, SECOND + GAP), (end, SECOND + GAP), (end, bottom), (LEFT, BOTTOM)],
    ]


def number(value):
    return f"{value:.2f}".rstrip("0").rstrip(".")


def path_data(points):
    return "M" + " L".join(f"{number(x)},{number(y)}" for x, y in points) + " Z"


def vector(fill, data):
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!-- {GENERATED} -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="{fill}"
        android:pathData="{data}" />
</vector>
"""


def splash_mark():
    """
    drawable-v31/splash_mark.xml: the mark as its pieces, still where the icon has them, so it is
    whole wherever it isn't animated; and the lines' run in, which starts each one to the left,
    unseen, and ends where it rests. Drawn lines first, so the front covers where they run under it.
    """
    pieces = []
    targets = []
    for (name, travel, duration), outline in zip(SPLASH_LINES, lines()):
        pieces.append(f"""            <group android:name="{name}">
                <path
                    android:name="{name}_path"
                    android:fillColor="{ACCENT}"
                    android:pathData="{path_data(outline)}" />
            </group>""")
        targets.append(animation(name, "translateX", -travel, 0, duration, "decelerate_cubic"))
        targets.append(animation(f"{name}_path", "fillAlpha", 0, 1, SPLASH_FADE, "linear"))
    pieces.append(f"""            <path
                android:name="front"
                android:fillColor="{ACCENT}"
                android:pathData="{path_data(front())}" />""")
    body = "\n".join(pieces)
    animations = "\n".join(targets)
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!-- {GENERATED} -->
<animated-vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:aapt="http://schemas.android.com/aapt">
    <aapt:attr name="android:drawable">
        <vector
            android:width="108dp"
            android:height="108dp"
            android:viewportWidth="108"
            android:viewportHeight="108">
{body}
        </vector>
    </aapt:attr>
{animations}
</animated-vector>
"""


def animation(target, prop, start, end, duration, interpolator):
    """One property's animation, from the splash's start: no delays, so each value holds from its first frame."""
    return f"""    <target android:name="{target}">
        <aapt:attr name="android:animation">
            <objectAnimator
                android:propertyName="{prop}"
                android:valueFrom="{number(start)}"
                android:valueTo="{number(end)}"
                android:valueType="floatType"
                android:duration="{duration}"
                android:interpolator="@android:interpolator/{interpolator}" />
        </aapt:attr>
    </target>"""


def splash_duration():
    """How long the splash's animation runs, in milliseconds: until its last line is in place."""
    return max([duration for _, _, duration in SPLASH_LINES] + [SPLASH_FADE])


def splash_values():
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!-- {GENERATED} -->
<resources>
    <!-- How long drawable-v31/splash_mark.xml animates: the splash theme's windowSplashScreenAnimationDuration. -->
    <integer name="splash_mark_duration">{splash_duration()}</integer>
</resources>
"""


ADAPTIVE = f"""<?xml version="1.0" encoding="utf-8"?>
<!-- {GENERATED} -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
"""

DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}

# Supersampled, then reduced: Pillow's polygons have no anti-aliasing of their own.
SUPERSAMPLE = 8


def draw_mark(draw, scale, dx=0.0, dy=0.0, fill=ACCENT):
    """The mark at [scale] pixels a unit, its layer's (0, 0) at (dx, dy)."""
    draw.polygon([(dx + x * scale, dy + y * scale) for x, y in mark()], fill=fill)


def legacy_icon(size, round_shape):
    """A pre-adaptive icon: the layers' middle 72 units (what a launcher's mask shows) in a rounded square or a circle."""
    big = size * SUPERSAMPLE
    image = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    inset = big / 24  # Material's 2 dp of keyline padding in a 48 dp icon
    box = [inset, inset, big - inset, big - inset]
    if round_shape:
        draw.ellipse(box, fill=SURFACE)
    else:
        draw.rounded_rectangle(box, radius=(big - 2 * inset) * 0.2, fill=SURFACE)
    scale = (big - 2 * inset) / 72
    draw_mark(draw, scale, dx=inset - 18 * scale, dy=inset - 18 * scale)
    return image.resize((size, size), Image.LANCZOS)


def banner(width, height):
    """
    The TV launcher's banner: the mark and the wordmark side by side, centred, together about 70 %
    of its width, so the launcher's rounded corners and focus zoom leave them whole.
    """
    big_w, big_h = width * SUPERSAMPLE, height * SUPERSAMPLE
    image = Image.new("RGB", (big_w, big_h), BACKGROUND)
    draw = ImageDraw.Draw(image)
    text = "NZStreamer"

    def layout(size):
        font = ImageFont.truetype(str(SORA), size)
        font.set_variation_by_axes([700])
        left, top, right, _ = draw.textbbox((0, 0), text, font=font, anchor="ls")
        cap = -top
        # The mark half again as tall as the capitals, a third of its width away from them.
        scale = cap * 1.5 / HEIGHT
        gap = WIDTH * scale / 3
        return font, left, right, cap, scale, gap, WIDTH * scale + gap + (right - left)

    size = 100
    *_, total = layout(size)
    font, left, right, cap, scale, gap, total = layout(round(size * big_w * 0.7 / total))
    x = (big_w - total) / 2
    middle = big_h / 2
    draw_mark(draw, scale, dx=x - LEFT * scale, dy=middle - 54 * scale)
    draw.text((x + WIDTH * scale + gap - left, middle + cap / 2), text, font=font, fill=TEXT, anchor="ls")
    return image.resize((width, height), Image.LANCZOS)


def decelerate(fraction):
    """Android's decelerate_cubic: DecelerateInterpolator with a factor of 1.5."""
    return 1 - (1 - fraction) ** 3


def splash_frame(ms, width, height, box):
    """
    The splash [ms] into its animation, on a [width] x [height] px screen with the icon's box [box]
    px wide: each line where it is and as faded in, masked to the circle Android masks it to.
    """
    big = box * SUPERSAMPLE
    scale = big / 108
    icon = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    draw = ImageDraw.Draw(icon)
    for (_, travel, duration), outline in zip(SPLASH_LINES, lines()):
        dx = -travel * (1 - decelerate(min(ms / duration, 1.0)))
        alpha = round(255 * min(ms / SPLASH_FADE, 1.0))
        # Lines don't overlap one another, so drawing each with its alpha is as compositing them.
        draw.polygon([((x + dx) * scale, y * scale) for x, y in outline], fill=ImageColor.getrgb(ACCENT) + (alpha,))
    draw.polygon([(x * scale, y * scale) for x, y in front()], fill=ACCENT)
    mask = Image.new("L", (big, big), 0)
    ImageDraw.Draw(mask).ellipse([big / 6, big / 6, big * 5 / 6, big * 5 / 6], fill=255)
    icon.putalpha(ImageChops.multiply(icon.getchannel("A"), mask))
    return on_screen(icon, width, height, box)


def still_splash(width, height, box):
    """The splash before Android 12: the mark still, in the icon's box."""
    big = box * SUPERSAMPLE
    icon = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    draw_mark(ImageDraw.Draw(icon), big / 108)
    return on_screen(icon, width, height, box)


def on_screen(icon, width, height, box):
    """A supersampled [icon] reduced to [box] px, in the middle of a [width] x [height] px splash."""
    screen = Image.new("RGBA", (width, height), BACKGROUND)
    screen.alpha_composite(icon.resize((box, box), Image.LANCZOS), ((width - box) // 2, (height - box) // 2))
    return screen.convert("RGB")


def preview(out):
    """
    The splash for a look, not for the app: on a phone (412 x 915 dp at 1.5 px a dp), Android 12+'s
    animation at 50 frames a second, first and last frames held; and Android 8-11's still splash on
    a 1080p TV (2 px a dp). Android draws the icon in a 288 dp box with no icon background.
    """
    out.mkdir(parents=True, exist_ok=True)
    step = 20
    frames = [splash_frame(ms, 618, 1372, 432) for ms in range(0, splash_duration() + step, step)]
    durations = [500] + [step] * (len(frames) - 2) + [1500]
    frames[0].save(out / "splash-preview.webp", save_all=True, append_images=frames[1:], duration=durations, loop=0, lossless=True)
    still_splash(1920, 1080, 576).save(out / "splash-android11.png", optimize=True)


def write(path, content):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content)


def main():
    parser = argparse.ArgumentParser(description="Draws NZStreamer's brand assets into app/src/main/res.")
    parser.add_argument("--preview", type=Path, metavar="DIR", help="draw the splash as it plays into DIR, and nothing else")
    args = parser.parse_args()
    if args.preview:
        preview(args.preview)
        return
    data = path_data(mark())
    write(RES / "drawable/ic_launcher_background.xml", vector(SURFACE, "M0,0 L108,0 L108,108 L0,108 Z"))
    write(RES / "drawable/ic_launcher_foreground.xml", vector(ACCENT, data))
    # Themed icons take the monochrome layer's alpha only and tint it.
    write(RES / "drawable/ic_launcher_monochrome.xml", vector("#FFFFFFFF", data))
    # Every device the app runs on (minSdk 26) takes these over the PNGs.
    for name in ("ic_launcher", "ic_launcher_round"):
        write(RES / f"mipmap-anydpi-v26/{name}.xml", ADAPTIVE)
    # 80 dp, so xhdpi is 160 px; every launcher uses the adaptive icon, these are for tools that don't.
    for density, factor in DENSITIES.items():
        size = round(80 * factor)
        for name, round_shape in (("ic_launcher", False), ("ic_launcher_round", True)):
            out = RES / f"mipmap-{density}/{name}.png"
            out.parent.mkdir(parents=True, exist_ok=True)
            legacy_icon(size, round_shape).save(out, optimize=True)
    # 160 x 90 dp: 320 x 180 px at xhdpi (a 1080p TV), 640 x 360 at xxxhdpi (4K).
    for density, factor in DENSITIES.items():
        out = RES / f"drawable-{density}/tv_banner.png"
        out.parent.mkdir(parents=True, exist_ok=True)
        banner(round(160 * factor), round(90 * factor)).save(out, optimize=True)
    # The splash's icon: animated on Android 12+, which plays it; before, the library draws it still.
    write(RES / "drawable-v31/splash_mark.xml", splash_mark())
    write(RES / "drawable/splash_mark.xml", vector(ACCENT, data))
    write(RES / "values/splash_mark.xml", splash_values())


if __name__ == "__main__":
    main()
