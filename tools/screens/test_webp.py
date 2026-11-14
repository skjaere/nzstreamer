#!/usr/bin/env python3
"""Tests of tools/screens/webp.py: the main README's images are lossless WebP holding their pixels alone, one for each
screen capture.sh takes, and a screen saved by webp.py keeps its pixels and nothing else. Needs Pillow.

  python3 -I -B tools/screens/test_webp.py
"""
import contextlib
import io
import os
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import webp  # noqa: E402
from PIL import Image, ImageDraw, PngImagePlugin  # noqa: E402


class ReadmeImagesTest(unittest.TestCase):
    def test_the_screens_are_the_five_a_phone_takes_and_the_four_a_tv_takes(self):
        self.assertEqual(["p1", "p2", "p3", "p4", "p5", "t1", "t2", "t3", "t4"], sorted(s.split("-")[0] for s in webp.NAMES))
        self.assertEqual(len(webp.NAMES), len(set(webp.NAMES.values())))

    def test_the_readme_holds_one_image_for_each_screen_and_no_other(self):
        on_disk = {p.name for p in webp.README_IMAGES.iterdir()}
        self.assertEqual({f"{name}.webp" for name in webp.NAMES.values()}, on_disk)

    def test_each_is_lossless_webp_with_its_pixels_alone(self):
        for image in sorted(webp.README_IMAGES.glob("*.webp")):
            with self.subTest(image.name):
                # A VP8L chunk alone: lossless, with no VP8X header, so no EXIF, XMP or ICC chunk can follow.
                self.assertEqual(["VP8L"], webp.chunks(image))


class SaveTest(unittest.TestCase):
    def test_a_screen_keeps_its_pixels_and_nothing_else(self):
        with tempfile.TemporaryDirectory() as tmp:
            screens, out = Path(tmp, "screens"), Path(tmp, "out")
            screens.mkdir()
            screen = Image.new("RGBA", (96, 64), (12, 34, 56, 255))
            draw = ImageDraw.Draw(screen)
            for x in range(0, 96, 8):
                draw.rectangle((x, 0, x + 3, 63), fill=(x * 2, 200 - x, 90, 255))
            text = PngImagePlugin.PngInfo()
            text.add_text("Comment", "Invented device")
            exif = Image.Exif()
            exif[0x010F] = "Invented maker"
            screen.save(screens / "p1-home.png", pnginfo=text, exif=exif, icc_profile=b"invented profile")
            with Image.open(screens / "p1-home.png") as saved:
                self.assertIn("Comment", saved.info)
                self.assertIn("icc_profile", saved.info)

            with contextlib.redirect_stdout(io.StringIO()):
                webp.main([str(screens), "--to", str(out)])

            made = out / "phone-home.webp"
            self.assertEqual(["phone-home.webp"], [p.name for p in out.iterdir()])
            self.assertEqual(["VP8L"], webp.chunks(made))
            with Image.open(made) as again:
                self.assertEqual(screen.convert("RGB").tobytes(), again.convert("RGB").tobytes())
                self.assertNotIn("icc_profile", again.info)
                self.assertNotIn("exif", again.info)

    def test_only_takes_the_screens_named_and_fails_on_one_not_there(self):
        with tempfile.TemporaryDirectory() as tmp:
            screens, out = Path(tmp, "screens"), Path(tmp, "out")
            screens.mkdir()
            for name in ("p4-player", "t3-player"):
                Image.new("RGB", (8, 8), (1, 2, 3)).save(screens / f"{name}.png")
            with contextlib.redirect_stdout(io.StringIO()):
                webp.main([str(screens), "--only", "p4-player", "--to", str(out)])
            self.assertEqual(["phone-player.webp"], [p.name for p in out.iterdir()])
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                webp.main([str(screens), "--only", "p1-home", "--to", str(out)])


if __name__ == "__main__":
    unittest.main()
