#!/usr/bin/env python3
"""Tests of tools/screens/art.py: a seed always draws the same picture, and the assets hold exactly the
images the screens catalogue names, each the one art.py draws for it. Needs Pillow.

  python3 -I -B tools/screens/test_art.py
"""
import io
import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import art  # noqa: E402
from PIL import Image, ImageChops, ImageStat  # noqa: E402

MOTIFS = ("harbour", "lighthouse", "island", "rail", "town", "fen", "canal", "hills")

# How far, on average over its pixels and channels (0 to 255), a picture in the assets may be from art.py's drawing of
# it. Another Pillow or libjpeg encodes a few pixels differently (the same picture saved at quality 70 rather than 85 is
# about 1 away); another picture is 30 or more.
MAX_MEAN_DIFFERENCE = 3


def mean_difference(a, b):
    return sum(ImageStat.Stat(ImageChops.difference(a, b)).mean) / 3


def metadata_segments(jpeg):
    """The markers of a JPEG's segments before its picture's data that hold metadata: APP1 to APP15 and comments."""
    found, at = [], 2
    while at + 4 <= len(jpeg) and jpeg[at] == 0xFF and jpeg[at + 1] != 0xDA:
        marker = jpeg[at + 1]
        if 0xE1 <= marker <= 0xEF or marker == 0xFE:
            found.append(marker)
        at += 2 + int.from_bytes(jpeg[at + 2:at + 4], "big")
    return found


class SeedTest(unittest.TestCase):
    def test_a_seed_draws_the_same_bytes_every_time_and_another_seed_other_bytes(self):
        draws = {
            "poster": lambda seed: art.poster("Kelpmoor", "harbour", seed),
            "backdrop": lambda seed: art.scene(art.BACKDROP, "rail", seed),
            "still": lambda seed: art.scene(art.STILL, "town", seed),
        }
        for kind, draw in draws.items():
            with self.subTest(kind):
                first = art.encode(draw(41))
                self.assertEqual(first, art.encode(draw(41)))
                self.assertNotEqual(first, art.encode(draw(42)))

    def test_every_motif_draws_every_size(self):
        for motif in MOTIFS:
            for size in (art.POSTER, art.BACKDROP, art.STILL):
                with self.subTest(motif=motif, size=size):
                    self.assertEqual(size, art.scene(size, motif, 7).size)
        self.assertEqual(art.POSTER, art.poster("The Lamplighter's Ledger", "town", 7).size)

    def test_seeds_come_from_the_slug_and_the_kind_alone(self):
        # Pinned: the images in the assets were drawn with these, and a new scheme would draw others.
        self.assertEqual(1287412912297540356, art.seed_for("kelpmoor", "poster"))
        self.assertEqual(8140649873548146723, art.seed_for("kelpmoor", "s01e04"))
        self.assertNotEqual(art.seed_for("kelpmoor", "poster"), art.seed_for("tidewick", "poster"))

    def test_a_jpeg_carries_no_metadata(self):
        data = art.encode(art.scene(art.STILL, "fen", 3))
        self.assertTrue(data.startswith(b"\xff\xd8\xff\xe0"), "a JFIF header first")
        # JFIF's own APP0 alone: no EXIF or XMP (APP1), ICC profile (APP2), other application segment or comment.
        self.assertEqual([], metadata_segments(data))
        for marker in (b"Exif", b"ICC_PROFILE"):
            self.assertNotIn(marker, data)

    def test_the_segment_check_finds_exif_and_a_comment(self):
        exif = Image.Exif()
        exif[0x010F] = "Invented maker"
        out = io.BytesIO()
        art.scene(art.STILL, "fen", 3).save(out, "JPEG", quality=art.QUALITY, exif=exif, comment=b"invented")
        self.assertEqual([0xE1, 0xFE], sorted(metadata_segments(out.getvalue())))


class CatalogueTest(unittest.TestCase):
    catalog = json.loads(art.CATALOG.read_text(encoding="utf-8"))

    def test_the_assets_hold_every_image_the_catalogue_names_and_no_other(self):
        on_disk = {p.name for p in art.ASSETS.iterdir()} - {"catalog.json"}
        self.assertEqual(set(art.images(self.catalog)), on_disk)

    def test_every_image_in_the_assets_is_the_one_art_draws_for_it(self):
        for name, draw in sorted(art.images(self.catalog).items()):
            with self.subTest(name):
                drawn = Image.open(io.BytesIO(art.encode(draw()))).convert("RGB")
                with Image.open(art.ASSETS / name) as stored:
                    self.assertEqual("JPEG", stored.format)
                    stored = stored.convert("RGB")
                self.assertEqual(drawn.size, stored.size)
                self.assertLess(mean_difference(drawn, stored), MAX_MEAN_DIFFERENCE)

    def test_another_picture_is_far_past_the_difference_allowed(self):
        with Image.open(art.ASSETS / "kelpmoor-poster.jpg") as one, Image.open(art.ASSETS / "tidewick-poster.jpg") as other:
            self.assertGreater(mean_difference(one.convert("RGB"), other.convert("RGB")), 5 * MAX_MEAN_DIFFERENCE)

    def test_every_title_has_a_motif_of_its_own_choosing(self):
        for title in self.catalog["shows"] + self.catalog["movies"]:
            self.assertIn(title["slug"], art.MOTIFS)
            self.assertIn(art.MOTIFS[title["slug"]], MOTIFS)

    def test_every_title_fits_its_poster_in_three_lines_or_fewer(self):
        draw = art.ImageDraw.Draw(art.Image.new("RGB", (1, 1)))
        for title in [s["name"] for s in self.catalog["shows"]] + [m["title"] for m in self.catalog["movies"]]:
            with self.subTest(title):
                font, size, lines = art.title_layout(title)
                self.assertLessEqual(len(lines), 3)
                self.assertGreater(size, 28, "not at the smallest size, where it would stop trying")
                for line in lines:
                    self.assertLessEqual(draw.textlength(line, font=font), art.POSTER[0] - 60)
                self.assertEqual(title.upper().split(), " ".join(lines).split())


if __name__ == "__main__":
    unittest.main()
