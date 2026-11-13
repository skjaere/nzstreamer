"""
Tests for add_dovi_config.py, over a small Matroska file built here: the record's bytes, where the mapping goes, and that
every size and position still holds after it.

  python3 -B tools/dovi/test_add_dovi_config.py
"""
import os, sys, unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import add_dovi_config as dovi  # noqa: E402
from add_dovi_config import element  # noqa: E402

EBML, INFO, TIMESTAMP_SCALE, TAGS = 0x1A45DFA3, 0x1549A966, 0x2AD7B1, 0x1254C367
TRACK_NUMBER, CODEC_ID, CLUSTER_TIMESTAMP, SIMPLE_BLOCK = 0xD7, 0x86, 0xE7, 0xA3
CUE_TIME, CUE_TRACK = 0xB3, 0xF7
UNKNOWN = b"\x01\xff\xff\xff\xff\xff\xff\xff"


def uint(id_, value, width=1):
    return element(id_, value.to_bytes(width, "big"))


def track(number, kind, codec):
    return element(dovi.TRACK_ENTRY, uint(TRACK_NUMBER, number) + uint(dovi.TRACK_TYPE, kind) + element(CODEC_ID, codec))


def matroska(tracks, unknown_size=False, crc=False):
    """
    An EBML header and a Segment: a SeekHead (Info, Tracks, Tags, Cues), Info, Tracks, Tags, two Clusters and Cues that
    point at them, as ffmpeg lays a file out. Positions take two bytes, as ffmpeg's do once past 255.
    """
    info = element(INFO, uint(TIMESTAMP_SCALE, 1_000_000, 3))
    tracks_el = element(dovi.TRACKS, (element(dovi.CRC32, bytes(4)) if crc else b"") + b"".join(tracks))
    tags = element(TAGS, bytes(300))
    clusters = [element(dovi.CLUSTER, uint(CLUSTER_TIMESTAMP, t) + element(SIMPLE_BLOCK, bytes([0x81, 0, 0, 0x80]) + bytes(40))) for t in (0, 100)]

    def seek_head(at):
        return element(dovi.SEEK_HEAD, b"".join(
            element(dovi.SEEK, element(dovi.SEEK_ID, id_.to_bytes(4, "big")) + uint(dovi.SEEK_POSITION, at[id_], 2))
            for id_ in (INFO, dovi.TRACKS, TAGS, dovi.CUES)
        ))

    def cues(at):
        return element(dovi.CUES, b"".join(
            element(dovi.CUE_POINT, uint(CUE_TIME, t) + element(dovi.CUE_TRACK_POSITIONS, uint(CUE_TRACK, 1) + uint(dovi.CUE_CLUSTER_POSITION, p, 2)))
            for t, p in zip((0, 100), at)
        ))

    # The SeekHead's and the Cues' sizes don't depend on the positions they hold: lay out once with zeros, then fill in.
    head = len(seek_head({i: 0 for i in (INFO, dovi.TRACKS, TAGS, dovi.CUES)}))
    at, offset = {}, head
    for id_, body in ((INFO, info), (dovi.TRACKS, tracks_el), (TAGS, tags)):
        at[id_] = offset
        offset += len(body)
    cluster_at = [offset, offset + len(clusters[0])]
    at[dovi.CUES] = offset + sum(map(len, clusters))
    body = seek_head(at) + info + tracks_el + tags + b"".join(clusters) + cues(cluster_at)
    size = UNKNOWN if unknown_size else dovi.encode_size(len(body), 8)
    return element(EBML, uint(0x4282, 0)) + dovi.SEGMENT.to_bytes(4, "big") + size + body


HEVC = track(1, dovi.VIDEO, b"V_MPEGH/ISO/HEVC")
AAC = track(2, 2, b"A_AAC")


def mappings(data):
    """The (track type, BlockAddIDType, record) of each BlockAdditionMapping, by track number."""
    segment = next(e for e in dovi.children(data, 0, len(data)) if e.id == dovi.SEGMENT)
    found = []
    dovi.walk(data, segment.body, len(data) if segment.end is None else segment.end, found)
    out = {}
    for entry in (e for e in found if e.id == dovi.TRACK_ENTRY):
        parts = dovi.children(data, entry.body, entry.end)
        number = next(dovi.uint(data, c) for c in parts if c.id == TRACK_NUMBER)
        for m in (c for c in parts if c.id == dovi.BLOCK_ADDITION_MAPPING):
            inner = {c.id: data[c.body:c.end] for c in dovi.children(data, m.body, m.end)}
            out[number] = (inner[dovi.BLOCK_ADD_ID_TYPE], inner[dovi.BLOCK_ADD_ID_EXTRA_DATA])
    return out


def every_size_holds(data):
    """That the file parses to its last byte: each element ends where its parent's next one starts."""
    def through(body, end):
        for e in dovi.children(data, body, end):
            stop = end if e.end is None else e.end
            if e.id in dovi.WALKED or e.id == dovi.CLUSTER:
                through(e.body, stop)
            assert stop <= end, "an element runs past its parent"
        return True
    return through(0, len(data))


class RecordTest(unittest.TestCase):
    def test_the_record_for_each_profile(self):
        # Profile 7 level 6, RPU, enhancement and base layers, compatibility id 6 (Blu-ray): ffprobe reads it so.
        self.assertEqual(bytes.fromhex("01000e3760") + bytes(19), dovi.record(7, 6, compat=6, el=True))
        # Profile 5: no enhancement layer, compatibility id 0 (none). Profile 8 with HDR10 compatibility (1).
        self.assertEqual(bytes.fromhex("01000a3500") + bytes(19), dovi.record(5, 6, compat=0, el=False))
        self.assertEqual(bytes.fromhex("0100103510") + bytes(19), dovi.record(8, 6, compat=1, el=False))
        # A level from 32 up sets the profile byte's last bit.
        self.assertEqual(0x0F, dovi.record(7, 32, compat=6, el=True)[2])

    def test_profile_8_is_dvvC_and_the_others_dvcC(self):
        self.assertEqual({1: (b"dvvC", dovi.record(8, 6, 1, False))}, mappings(dovi.add(matroska([HEVC, AAC]), 8, 6)))
        self.assertEqual({1: (b"dvcC", dovi.record(7, 6, 6, True))}, mappings(dovi.add(matroska([HEVC, AAC]), 7, 6)))
        self.assertEqual({1: (b"dvcC", dovi.record(5, 6, 0, False))}, mappings(dovi.add(matroska([HEVC, AAC]), 5, 6)))


class AddTest(unittest.TestCase):
    def test_the_mapping_goes_in_the_video_track_and_every_size_and_position_follows(self):
        for unknown_size in (False, True):
            before = matroska([AAC, HEVC, track(3, 2, b"A_OPUS")], unknown_size=unknown_size)
            after = dovi.add(before, 7, 6)
            self.assertEqual(len(before) + 37, len(after))
            self.assertEqual({1: (b"dvcC", dovi.record(7, 6, 6, True))}, mappings(after))
            self.assertTrue(every_size_holds(after))
            # The SeekHead and the Cues still point at their elements (add checks this too, before returning).
            dovi.check(after)
            if unknown_size:
                self.assertIn(UNKNOWN, after[:60])

    def test_the_options_override_the_profile_defaults(self):
        after = dovi.add(matroska([HEVC]), 7, 6, compat=2, el=False)
        self.assertEqual(dovi.record(7, 6, compat=2, el=False), mappings(after)[1][1])

    def test_a_file_without_one_video_track_is_refused(self):
        with self.assertRaisesRegex(ValueError, "0 video tracks"):
            dovi.add(matroska([AAC]), 7, 6)
        with self.assertRaisesRegex(ValueError, "2 video tracks"):
            dovi.add(matroska([HEVC, track(2, dovi.VIDEO, b"V_MPEG4/ISO/AVC")]), 7, 6)

    def test_a_track_with_a_mapping_already_or_a_crc_is_refused(self):
        with self.assertRaisesRegex(ValueError, "already"):
            dovi.add(dovi.add(matroska([HEVC]), 7, 6), 7, 6)
        with self.assertRaisesRegex(ValueError, "write_crc32"):
            dovi.add(matroska([HEVC], crc=True), 7, 6)


if __name__ == "__main__":
    unittest.main()
