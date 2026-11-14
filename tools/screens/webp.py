#!/usr/bin/env python3
"""Saves capture.sh's screens as the main README's images: lossless WebP made from their pixels alone.

  python3 -I tools/screens/webp.py <capture.sh's --out>... [--only p4-player ...] [--to .github/readme]

Each screen's PNG (p1-home.png to t4-phone-setup.png) is decoded and written again from its pixels, as RGB, so no
text chunk, EXIF, XMP or ICC profile goes along, under the name the README shows it by (NAMES): p1-home.png becomes
phone-home.webp. --only takes some of the screens alone. Needs Pillow (10 or newer).
"""
import argparse
import hashlib
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
README_IMAGES = ROOT / ".github/readme"

# capture.sh's screen -> the README's image.
NAMES = {
    "p1-home": "phone-home",
    "p2-show": "phone-show",
    "p3-releases": "phone-releases",
    "p4-player": "phone-player",
    "p5-play-on": "phone-play-on",
    "t1-home": "tv-home",
    "t2-collection": "tv-collection",
    "t3-player": "tv-player",
    "t4-phone-setup": "tv-phone-setup",
}


def save(png, out):
    """Writes [png]'s pixels alone to [out] as lossless WebP."""
    with Image.open(png) as im:
        pixels = im.convert("RGB")
    clean = Image.frombytes("RGB", pixels.size, pixels.tobytes())
    clean.save(out, "WEBP", lossless=True, quality=100, method=6, exact=False)


def chunks(webp):
    """The RIFF chunks of a WebP file, by their four-letter names, in order."""
    data = Path(webp).read_bytes()
    if data[:4] != b"RIFF" or data[8:12] != b"WEBP":
        raise ValueError(f"{webp} is no WebP file")
    names, at = [], 12
    while at + 8 <= len(data):
        size = int.from_bytes(data[at + 4:at + 8], "little")
        names.append(data[at:at + 4].decode("latin-1"))
        at += 8 + size + (size & 1)
    return names


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("dirs", nargs="+", type=Path, help="capture.sh's --out folders")
    parser.add_argument("--only", nargs="+", choices=sorted(NAMES), help="these screens alone")
    parser.add_argument("--to", type=Path, default=README_IMAGES)
    args = parser.parse_args(argv)
    wanted = args.only or sorted(NAMES)
    found = {}
    for folder in args.dirs:
        for screen in wanted:
            png = folder / f"{screen}.png"
            if png.is_file():
                if screen in found:
                    parser.error(f"{screen}.png is in {found[screen].parent} and in {folder}")
                found[screen] = png
    if args.only and set(found) != set(args.only):
        parser.error("no PNG for " + ", ".join(sorted(set(args.only) - set(found))))
    if not found:
        parser.error("no screen's PNG in " + ", ".join(map(str, args.dirs)))
    args.to.mkdir(parents=True, exist_ok=True)
    total = 0
    for screen, png in sorted(found.items()):
        out = args.to / f"{NAMES[screen]}.webp"
        save(png, out)
        assert chunks(out) == ["VP8L"], out
        size = out.stat().st_size
        total += size
        print(f"{out.name} {size} {hashlib.sha256(out.read_bytes()).hexdigest()}")
    print(f"{len(found)} images, {total} bytes")


if __name__ == "__main__":
    sys.exit(main())
