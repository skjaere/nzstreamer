#!/usr/bin/env python3
"""Draws the README screenshots' posters, backdrops and episode stills.

Every title in app/src/androidTest/assets/screens/catalog.json gets a poster; those marked "backdrop"
get a backdrop, and every named episode a still. Each is a small landscape (a sky, a sun or moon,
then a harbour, hills, a town and so on) drawn from a seed taken from the title's slug and the
image's kind, so the same catalogue gives the same pictures. Posters carry the title in Sora Bold,
one of the app's bundled fonts (res/font, SIL Open Font License). The images are JPEGs, as TMDB's
are, written beside the catalogue; the device tests answer the app's image requests with them.

Run from anywhere with Python 3 and Pillow (10 or newer): python3 -I tools/screens/art.py
With --out DIR it writes into DIR instead. Another Pillow or libjpeg may draw a few pixels differently.
"""
import argparse
import hashlib
import io
import json
import random
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
ASSETS = ROOT / "app/src/androidTest/assets/screens"
CATALOG = ASSETS / "catalog.json"
SORA = ROOT / "app/src/main/res/font/sora.ttf"

POSTER = (500, 750)
BACKDROP = (1280, 720)
STILL = (640, 360)
QUALITY = 85

TEXT = (243, 241, 236)

# Sky top, sky at the horizon, the sun or moon, the far land and the near land.
PALETTES = [
    ((29, 43, 83), (201, 111, 83), (246, 210, 122), (74, 52, 78), (24, 22, 34)),
    ((46, 74, 125), (240, 179, 138), (255, 241, 193), (92, 96, 128), (30, 34, 52)),
    ((11, 16, 38), (44, 62, 116), (232, 230, 217), (28, 36, 70), (8, 10, 22)),
    ((59, 70, 82), (138, 150, 160), (217, 221, 224), (70, 80, 92), (30, 36, 44)),
    ((18, 63, 74), (224, 169, 109), (250, 222, 160), (40, 70, 72), (14, 30, 34)),
    ((108, 138, 168), (223, 230, 236), (255, 255, 250), (120, 140, 160), (52, 64, 80)),
    ((58, 31, 43), (232, 141, 63), (255, 214, 140), (90, 46, 52), (34, 18, 24)),
]

# What each title's pictures show; a title not listed gets one by its seed.
MOTIFS = {
    "kelpmoor": "harbour",
    "quillon-station": "rail",
    "the-gullenkiln-archive": "town",
    "thimbleport": "harbour",
    "brasswick-tide": "fen",
    "the-lamplighters-ledger": "town",
    "tidewick": "harbour",
    "pellowby-lane": "town",
    "copperfen": "fen",
    "the-corrandine-papers": "hills",
    "fathomhill": "island",
    "glimmerholt": "hills",
    "the-gannet-register": "island",
    "lowhatch": "canal",
    "the-ninth-bollard": "harbour",
    "pellucid-shore": "lighthouse",
    "ostlers-landing": "canal",
    "cindermoor": "hills",
}


def seed_for(slug, kind):
    """A seed from the slug and the image's kind ("poster", "backdrop", "s01e02"), the same everywhere."""
    return int.from_bytes(hashlib.sha256(f"{slug}:{kind}".encode()).digest()[:8], "big")


def mix(a, b, t):
    return tuple(round(x + (y - x) * t) for x, y in zip(a, b))


def scene(size, motif, seed):
    """A landscape of the given motif, its palette, sun and shapes picked by the seed."""
    rng = random.Random(seed)
    w, h = size
    sky_top, sky_low, sun, far, near = PALETTES[rng.randrange(len(PALETTES))]
    img = Image.new("RGB", size)
    draw = ImageDraw.Draw(img)
    horizon = round(h * rng.uniform(0.55, 0.66))
    for y in range(horizon):
        draw.line([(0, y), (w, y)], fill=mix(sky_top, sky_low, (y / horizon) ** 1.4))
    # Below the horizon, the land's colour under whatever the motif puts there.
    draw.rectangle([0, horizon, w, h], fill=mix(far, near, 0.5))

    # The sun or moon, in a glow of fainter rings.
    r = round(min(w, h) * rng.uniform(0.07, 0.11))
    cx, cy = round(w * rng.uniform(0.2, 0.8)), round(horizon * rng.uniform(0.3, 0.7))
    behind = mix(sky_top, sky_low, (cy / horizon) ** 1.4)
    for i in range(10, 0, -1):
        grow = r * 0.09 * i
        draw.ellipse([cx - r - grow, cy - r - grow, cx + r + grow, cy + r + grow], fill=mix(behind, sun, 0.5 * (1 - i / 11) ** 2))
    draw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=sun)

    def ridge(base, rough, colour, step):
        y = base
        points = [(0, h), (0, y)]
        for x in range(0, w + step, step):
            y = min(max(y + rng.uniform(-rough, rough), base - rough * 3), base + rough * 3)
            points.append((x, y))
        points.append((w, h))
        draw.polygon(points, fill=colour)

    def water(top, colour_far, colour_near):
        for y in range(top, h):
            draw.line([(0, y), (w, y)], fill=mix(colour_far, colour_near, (y - top) / max(1, h - top)))
        for _ in range(round(w * h / 9000)):
            x, y = rng.uniform(0, w), rng.uniform(top + 2, h)
            length = rng.uniform(w * 0.02, w * 0.08) * (0.4 + (y - top) / (h - top))
            draw.line([(x, y), (x + length, y)], fill=mix(colour_far, sun, 0.25), width=max(1, round(h / 400)))

    def windows(x0, x1, y0, y1, lit):
        for _ in range(rng.randint(2, 6)):
            wx, wy = rng.uniform(x0 + 2, max(x0 + 3, x1 - 8)), rng.uniform(y0 + 4, max(y0 + 5, y1 - 8))
            s = max(2, round(h / 120))
            draw.rectangle([wx, wy, wx + s, wy + s * 1.4], fill=lit)

    lit = mix(sun, (255, 200, 120), 0.5)
    if motif in ("harbour", "lighthouse", "island"):
        water(horizon, mix(sky_low, far, 0.5), near)
        if motif == "harbour":
            quay = round(h * rng.uniform(0.80, 0.86))
            draw.rectangle([0, quay, w, h], fill=near)
            for _ in range(rng.randint(2, 4)):
                x = rng.uniform(w * 0.1, w * 0.9)
                top = horizon - rng.uniform(h * 0.15, h * 0.3)
                draw.line([(x, quay), (x, top)], fill=near, width=max(2, round(w / 250)))
                draw.line([(x - w * 0.04, top + h * 0.05), (x + w * 0.04, top + h * 0.05)], fill=near, width=max(1, round(w / 400)))
                draw.polygon([(x - w * 0.08, quay - h * 0.02), (x + w * 0.08, quay - h * 0.02), (x + w * 0.06, quay), (x - w * 0.06, quay)], fill=near)
            for _ in range(rng.randint(3, 6)):
                x = rng.uniform(0, w)
                draw.rectangle([x, quay - h * 0.012, x + w * 0.012, quay], fill=mix(near, sky_low, 0.15))
        elif motif == "lighthouse":
            cliff = round(w * rng.uniform(0.55, 0.7))
            draw.polygon([(cliff, h), (cliff + w * 0.06, horizon - h * 0.06), (w, horizon - h * 0.09), (w, h)], fill=near)
            tx = cliff + w * 0.18
            draw.polygon([(tx - w * 0.03, horizon - h * 0.07), (tx + w * 0.03, horizon - h * 0.07), (tx + w * 0.02, horizon - h * 0.3), (tx - w * 0.02, horizon - h * 0.3)], fill=mix(near, sky_low, 0.25))
            draw.rectangle([tx - w * 0.025, horizon - h * 0.34, tx + w * 0.025, horizon - h * 0.3], fill=lit)
            draw.polygon([(tx, horizon - h * 0.32), (0, horizon - h * 0.42), (0, horizon - h * 0.26)], fill=mix(sky_low, lit, 0.25))
        else:
            ix = rng.uniform(w * 0.2, w * 0.6)
            draw.polygon([(ix, horizon + 1), (ix + w * 0.08, horizon - h * 0.12), (ix + w * 0.16, horizon - h * 0.08), (ix + w * 0.32, horizon + 1)], fill=far)
            for _ in range(rng.randint(5, 9)):
                bx, by, s = rng.uniform(0, w), rng.uniform(horizon * 0.2, horizon * 0.8), rng.uniform(w * 0.008, w * 0.018)
                draw.line([(bx - s, by - s * 0.6), (bx, by), (bx + s, by - s * 0.6)], fill=near, width=max(1, round(w / 500)))
    elif motif == "rail":
        ridge(round(horizon - h * 0.06), h * 0.02, far, max(8, w // 40))
        ridge(round(h * 0.78), h * 0.015, near, max(8, w // 30))
        vx = w * rng.uniform(0.4, 0.6)
        rail = mix(sky_low, near, 0.25)
        for side in (-1, 1):
            draw.line([(vx + side * w * 0.01, horizon + h * 0.02), (w / 2 + side * w * 0.3, h)], fill=rail, width=max(2, round(w / 200)))
        for i in range(1, 9):
            t = (i / 9) ** 2
            y = horizon + h * 0.02 + (h - horizon - h * 0.02) * t
            half = w * 0.01 + (w * 0.33) * t
            cx2 = vx + (w / 2 - vx) * t
            draw.line([(cx2 - half, y), (cx2 + half, y)], fill=rail, width=max(1, round(h / 300 + 4 * t)))
        sx = w * rng.uniform(0.1, 0.25)
        draw.line([(sx, h * 0.8), (sx, horizon - h * 0.12)], fill=near, width=max(2, round(w / 160)))
        draw.rectangle([sx - w * 0.015, horizon - h * 0.12, sx + w * 0.015, horizon - h * 0.06], fill=near)
        draw.ellipse([sx - w * 0.008, horizon - h * 0.11, sx + w * 0.008, horizon - h * 0.11 + w * 0.016], fill=lit)
    elif motif == "town":
        draw.rectangle([0, horizon, w, h], fill=near)
        x = 0.0
        while x < w:
            bw = rng.uniform(w * 0.06, w * 0.14)
            bh = rng.uniform(h * 0.12, h * 0.38)
            colour = mix(far, near, rng.uniform(0.2, 0.7))
            draw.rectangle([x, horizon - bh, x + bw, horizon + 1], fill=colour)
            if rng.random() < 0.5:
                draw.polygon([(x, horizon - bh), (x + bw / 2, horizon - bh - bw * 0.4), (x + bw, horizon - bh)], fill=colour)
            windows(x, x + bw, horizon - bh, horizon, lit)
            x += bw + rng.uniform(0, w * 0.02)
    elif motif in ("fen", "canal"):
        ridge(horizon, h * 0.006, far, max(8, w // 25))
        water(horizon + round(h * 0.02), mix(sky_low, sky_top, 0.3), mix(near, sky_top, 0.3))
        if motif == "fen":
            for _ in range(round(w / 6)):
                x = rng.uniform(0, w)
                base = rng.uniform(h * 0.85, h)
                draw.line([(x, base), (x + rng.uniform(-6, 6), base - rng.uniform(h * 0.05, h * 0.16))], fill=near, width=max(1, round(w / 350)))
        else:
            bank = round(h * 0.88)
            draw.rectangle([0, bank, w, h], fill=near)
            bx = rng.uniform(w * 0.15, w * 0.55)
            hull = mix(near, sky_low, 0.2)
            draw.rectangle([bx, bank - h * 0.05, bx + w * 0.38, bank - h * 0.01], fill=hull)
            draw.rectangle([bx + w * 0.08, bank - h * 0.1, bx + w * 0.3, bank - h * 0.05], fill=hull)
            windows(bx + w * 0.08, bx + w * 0.3, bank - h * 0.1, bank - h * 0.05, lit)
            for _ in range(rng.randint(2, 4)):
                tx = rng.uniform(0, w)
                draw.ellipse([tx - w * 0.05, horizon - h * 0.16, tx + w * 0.05, horizon + h * 0.01], fill=far)
    else:  # hills
        ridge(horizon - round(h * 0.06), h * 0.025, far, max(8, w // 30))
        ridge(horizon + round(h * 0.06), h * 0.02, mix(far, near, 0.5), max(8, w // 25))
        ridge(round(h * 0.86), h * 0.012, near, max(8, w // 20))
        for _ in range(rng.randint(2, 5)):
            hx, hy = rng.uniform(w * 0.05, w * 0.9), rng.uniform(h * 0.8, h * 0.88)
            hw = w * rng.uniform(0.04, 0.07)
            draw.rectangle([hx, hy - hw * 0.6, hx + hw, hy], fill=near)
            draw.polygon([(hx - hw * 0.1, hy - hw * 0.6), (hx + hw / 2, hy - hw * 1.1), (hx + hw * 1.1, hy - hw * 0.6)], fill=near)
            draw.rectangle([hx + hw * 0.35, hy - hw * 0.45, hx + hw * 0.55, hy - hw * 0.25], fill=lit)
    return img


def poster(title, motif, seed):
    """A poster: the scene with the title in Sora Bold on a darker foot."""
    img = scene(POSTER, motif, seed)
    w, h = POSTER
    shade = Image.new("L", POSTER, 0)
    sd = ImageDraw.Draw(shade)
    for y in range(round(h * 0.55), h):
        sd.line([(0, y), (w, y)], fill=round(200 * (y - h * 0.55) / (h * 0.45)))
    img = Image.composite(Image.new("RGB", POSTER, (10, 10, 14)), img, shade)
    draw = ImageDraw.Draw(img)
    font, size, lines = title_layout(title)
    line_height = round(size * 1.15)
    y = h - 56 - line_height * len(lines)
    for line in lines:
        x = (w - draw.textlength(line, font=font)) / 2
        draw.text((x, y), line, font=font, fill=TEXT)
        y += line_height
    draw.line([(w / 2 - 24, h - 36), (w / 2 + 24, h - 36)], fill=(242, 163, 58), width=3)
    return img


def title_layout(title):
    """The poster's title in capitals: the largest Sora Bold, from 64 px down to 28, that fits three lines or fewer
    within the poster's margins. Returns the font, its size and the lines."""
    draw = ImageDraw.Draw(Image.new("RGB", (1, 1)))
    width = POSTER[0] - 60
    size = 64
    while True:
        font = ImageFont.truetype(str(SORA), size)
        font.set_variation_by_axes([700])
        lines = wrap(draw, title.upper(), font, width)
        if (len(lines) <= 3 and all(draw.textlength(line, font=font) <= width for line in lines)) or size <= 28:
            return font, size, lines
        size -= 4


def wrap(draw, text, font, width):
    lines, line = [], ""
    for word in text.split():
        candidate = f"{line} {word}".strip()
        if line and draw.textlength(candidate, font=font) > width:
            lines.append(line)
            line = word
        else:
            line = candidate
    return lines + [line]


def encode(img):
    out = io.BytesIO()
    img.save(out, "JPEG", quality=QUALITY, optimize=True)
    return out.getvalue()


def motif_of(slug):
    return MOTIFS.get(slug) or ("harbour", "rail", "town", "fen", "island", "canal", "hills")[seed_for(slug, "motif") % 7]


def images(catalog):
    """Every image the catalogue names, as file name -> a function drawing it."""
    wanted = {}
    titles = [(s["slug"], s["name"], s) for s in catalog["shows"]] + [(m["slug"], m["title"], m) for m in catalog["movies"]]
    for slug, name, title in titles:
        motif = motif_of(slug)
        wanted[f"{slug}-poster.jpg"] = lambda n=name, m=motif, s=seed_for(slug, "poster"): poster(n, m, s)
        if title.get("backdrop"):
            wanted[f"{slug}-backdrop.jpg"] = lambda m=motif, s=seed_for(slug, "backdrop"): scene(BACKDROP, m, s)
        for season in title.get("seasons", []):
            if isinstance(season["episodes"], list):
                for number, _ in enumerate(season["episodes"], start=1):
                    kind = f"s{season['number']:02d}e{number:02d}"
                    wanted[f"{slug}-{kind}.jpg"] = lambda m=motif, s=seed_for(slug, kind): scene(STILL, m, s)
    return wanted


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--catalog", type=Path, default=CATALOG)
    parser.add_argument("--out", type=Path, default=ASSETS)
    args = parser.parse_args()
    catalog = json.loads(args.catalog.read_text(encoding="utf-8"))
    args.out.mkdir(parents=True, exist_ok=True)
    total = 0
    for name, draw in sorted(images(catalog).items()):
        data = encode(draw())
        (args.out / name).write_bytes(data)
        total += len(data)
    print(f"Wrote {len(images(catalog))} images, {total // 1024} KiB, to {args.out}")


if __name__ == "__main__":
    main()
