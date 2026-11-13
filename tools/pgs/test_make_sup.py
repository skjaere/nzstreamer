"""
Tests for make_sup.py: the segments of each display set, their times, and bitmaps that decode back to the pixels as
Media3's PgsParser reads them (its loop is copied below).

decode() is a changed copy: the run-length loop of androidx.media3's PgsParser, translated to Python.
PgsParser is Copyright (C) 2017 The Android Open Source Project, under the Apache License 2.0
(LICENSES/Apache-2.0.txt).

  python3 -B tools/pgs/test_make_sup.py
"""
import os, random, struct, sys, tempfile, unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import make_sup  # noqa: E402


def segments(data):
    """(type, pts in 90 kHz, payload) for each segment of a .sup stream."""
    out, i = [], 0
    while i < len(data):
        magic, pts, dts, kind, length = struct.unpack(">2sIIBH", data[i:i + 13])
        assert magic == b"PG" and dts == 0, (magic, dts)
        out.append((kind, pts, data[i + 13:i + 13 + length]))
        i += 13 + length
    return out


def display_sets(data):
    """The stream's segments grouped into display sets, each ending with END."""
    sets, current = [], []
    for s in segments(data):
        current.append(s)
        if s[0] == make_sup.END:
            sets.append(current)
            current = []
    assert not current, "segments after the last END"
    return sets


def decode(ods_payloads):
    """
    The bitmap of a display set's ODS segments, as palette indices row after row: Media3's PgsParser's reading of the
    object (parseBitmapSection, then build's run-length loop), which ignores the end-of-line codes.
    """
    first = ods_payloads[0]
    assert first[3] & 0x80, "the first ODS lacks its first-in-sequence flag"
    total = int.from_bytes(first[4:7], "big")
    width, height = struct.unpack(">HH", first[7:11])
    data = bytearray(first[11:])
    for p in ods_payloads[1:]:
        assert not p[3] & 0x80
        data += p[4:]
    assert ods_payloads[-1][3] & 0x40, "the last ODS lacks its last-in-sequence flag"
    assert len(data) == total - 4, (len(data), total)
    pixels, i = [], 0
    while len(pixels) < width * height:
        index = data[i]; i += 1
        if index != 0:
            pixels.append(index)
            continue
        switch = data[i]; i += 1
        if switch == 0:
            continue
        run = switch & 0x3F if switch & 0x40 == 0 else ((switch & 0x3F) << 8) | data[i]
        if switch & 0x40:
            i += 1
        color = 0
        if switch & 0x80:
            color = data[i]; i += 1
        pixels.extend([color] * run)
    return width, height, pixels


def expand(rows):
    return [index for row in rows for index, count in row for _ in range(count)]


class EncodeTest(unittest.TestCase):
    def test_each_run_length_code(self):
        rows = [[(0, 5), (0, 100), (1, 1), (2, 2), (3, 20), (1, 300), (0, 20000)]]
        width = sum(n for _, n in rows[0])
        rle = make_sup.encode_row(rows[0])
        self.assertTrue(rle.endswith(b"\x00\x00"))
        # The 0-runs join (105), single and double pixels go one by one, a long run is split at 16383.
        self.assertEqual(
            bytes([0, 0x40, 105, 1, 2, 2, 0, 0x80 | 20, 3, 0, 0xC0 | 300 >> 8, 300 & 0xFF, 1, 0, 0x7F, 0xFF, 0, 0x40 | (20000 - 16383) >> 8, (20000 - 16383) & 0xFF, 0, 0]),
            rle,
        )
        payload = struct.pack(">HBB", 0, 0, 0xC0) + (len(rle) + 4).to_bytes(3, "big") + struct.pack(">HH", width, 1) + rle
        self.assertEqual(expand(rows), decode([payload])[2])


class StreamTest(unittest.TestCase):
    plane = (160, 90)

    def test_a_cue_shows_at_its_start_and_clears_at_its_end(self):
        data = make_sup.sup([(3.0, 4.0, (48, 10)), (1.0, 2.5, (40, 10))], self.plane)
        sets = display_sets(data)
        self.assertEqual([[0x16, 0x17, 0x14, 0x15, 0x80], [0x16, 0x17, 0x80]] * 2, [[s[0] for s in ds] for ds in sets])
        # In order of their start, at 90 kHz, every segment of a set at its time.
        self.assertEqual([90_000, 225_000, 270_000, 360_000], [ds[0][1] for ds in sets])
        self.assertTrue(all(len({s[1] for s in ds}) == 1 for ds in sets))

    def test_the_composition_places_the_bitmap_as_media3_reads_it(self):
        shown, cleared = display_sets(make_sup.sup([(1.0, 2.0, (40, 10))], self.plane))
        pcs = shown[0][2]
        # Media3: plane width and height, 11 bytes it skips, then the object's x and y.
        self.assertEqual((160, 90), struct.unpack(">HH", pcs[:4]))
        self.assertEqual(19, len(pcs))
        self.assertEqual(0x80, pcs[7], "epoch start")
        self.assertEqual(1, pcs[10], "one object")
        self.assertEqual((60, 76), struct.unpack(">HH", pcs[15:19]))
        self.assertEqual(0, cleared[0][2][10], "the clear has no object")
        # The palette: Media3 takes a section whose length is 2 more than a multiple of 5.
        self.assertEqual(2, len(shown[2][2]) % 5)

    def test_a_box_decodes_to_its_pixels(self):
        shown, _ = display_sets(make_sup.sup([(1.0, 2.0, (40, 10))], self.plane))
        width, height, pixels = decode([s[2] for s in shown if s[0] == make_sup.ODS])
        self.assertEqual((40, 10), (width, height))
        self.assertEqual(expand(make_sup.box(40, 10)), pixels)
        self.assertEqual(make_sup.BLACK, pixels[0])
        self.assertEqual(make_sup.WHITE, pixels[41])

    def test_a_large_text_bitmap_spans_several_objects_and_decodes_whole(self):
        data = make_sup.sup([(0.5, 2.0, (960, 540))], (1920, 1080), fill="text", seed=7)
        shown, _ = display_sets(data)
        ods = [s[2] for s in shown if s[0] == make_sup.ODS]
        self.assertGreater(len(ods), 1)
        self.assertTrue(all(len(p) <= 0xFFFF for p in ods))
        width, height, pixels = decode(ods)
        self.assertEqual((960, 540), (width, height))
        rows = make_sup.text(960, 540, random.Random(7), make_sup.glyphs(random.Random(7)))
        # Text: every index used, mostly transparent.
        self.assertEqual({0, 1, 2, 3}, set(pixels))
        self.assertGreater(pixels.count(0), len(pixels) // 2)
        self.assertEqual(len(pixels), len(expand(rows)))

    def test_texts_differ_from_cue_to_cue_unless_variants_limit_them(self):
        def bitmaps(variants):
            sets = display_sets(make_sup.sup([(t, t + 1.0, (200, 60)) for t in (0.0, 2.0, 4.0)], (640, 360), fill="text", variants=variants))
            return [tuple(decode([s[2] for s in ds if s[0] == make_sup.ODS])[2]) for ds in sets[::2]]
        self.assertEqual(3, len(set(bitmaps(0))))
        once, twice, again = bitmaps(2)
        self.assertNotEqual(once, twice)
        self.assertEqual(once, again)


class CommandTest(unittest.TestCase):
    def test_periodic_and_given_cues(self):
        with tempfile.TemporaryDirectory() as d:
            out = os.path.join(d, "cues.sup")
            make_sup.main([out, "--size", "320x180", "--every", "2.5", "--length", "1.5", "--start", "0.5", "--end", "10", "--cue", "9-9.5:20x8"])
            with open(out, "rb") as f:
                sets = display_sets(f.read())
        # 0.5, 3.0, 5.5 and 8.0 (8.0 + 1.5 is within 10), and the one given at 9.
        starts = [ds[0][1] / 90_000 for ds in sets[::2]]
        self.assertEqual([0.5, 3.0, 5.5, 8.0, 9.0], starts)
        self.assertEqual((20, 8), decode([s[2] for s in sets[-2] if s[0] == make_sup.ODS])[:2])
        # The default bitmap: a quarter of the width, a tenth of the height.
        self.assertEqual((80, 18), decode([s[2] for s in sets[0] if s[0] == make_sup.ODS])[:2])

    def test_a_bitmap_larger_than_the_plane_is_refused(self):
        with tempfile.TemporaryDirectory() as d, self.assertRaises(SystemExit):
            sys.stderr, err = open(os.devnull, "w"), sys.stderr
            try:
                make_sup.main([os.path.join(d, "x.sup"), "--size", "160x90", "--cue", "1-2:200x10"])
            finally:
                sys.stderr.close()
                sys.stderr = err


if __name__ == "__main__":
    unittest.main()
