"""
Adds a Dolby Vision configuration record to the video track of a Matroska file, for test fixtures: ffmpeg writes one only
for a stream that carries Dolby Vision metadata, which none of its encoders makes from a test pattern. The record goes in
a BlockAdditionMapping in the video's TrackEntry, as ffmpeg and mkvmerge write it: BlockAddIDType "dvcC" ("dvvC" for
profile 8), then BlockAddIDExtraData, the 24-byte record. Media3 and ffmpeg read it from there.

Only the record is added: the video stays what it was (say HEVC Main 10, with no RPU or enhancement layer in it), so the
file shows how a player labels and routes Dolby Vision, not a Dolby Vision picture.

The Segment, Tracks and TrackEntry grow by the mapping's length, and everything after it in the file moves by as much:
the SeekHead's SeekPositions and the Cues' CueClusterPositions are rewritten to match, each in the width it had. Write the
input with ffmpeg's `-write_crc32 0`: a CRC-32 element would no longer match, so a file with one is refused.

  python3 -I tools/dovi/add_dovi_config.py in.mkv out.mkv --profile 7 --level 6
  --compat N   the base layer's signal compatibility id (default 0 for profile 5, 6 for 7, 1 for 8)
  --el         an enhancement layer is present (default: only for profile 7, the Blu-ray profile)
Standard library only.
"""
import argparse, sys

SEGMENT = 0x18538067
SEEK_HEAD, SEEK, SEEK_ID, SEEK_POSITION = 0x114D9B74, 0x4DBB, 0x53AB, 0x53AC
TRACKS, TRACK_ENTRY, TRACK_TYPE = 0x1654AE6B, 0xAE, 0x83
BLOCK_ADDITION_MAPPING, BLOCK_ADD_ID_TYPE, BLOCK_ADD_ID_EXTRA_DATA = 0x41E4, 0x41E7, 0x41ED
CUES, CUE_POINT, CUE_TRACK_POSITIONS, CUE_CLUSTER_POSITION = 0x1C53BB6B, 0xBB, 0xB7, 0xF1
CLUSTER, CRC32 = 0x1F43B675, 0xBF
VIDEO = 1

# The master elements the rewrite looks inside; the rest (Info, Tags, Clusters, a track's Video) are copied as they are.
WALKED = {SEGMENT, SEEK_HEAD, SEEK, TRACKS, TRACK_ENTRY, CUES, CUE_POINT, CUE_TRACK_POSITIONS}

PROFILES = {5: 0, 7: 6, 8: 1}


class Element:
    """One element: where its ID starts ([start]), where its data starts and ends ([body], [end]; None for an unknown size)."""

    def __init__(self, id_, start, size_at, body, end):
        self.id, self.start, self.size_at, self.body, self.end = id_, start, size_at, body, end

    @property
    def size_width(self):
        return self.body - self.size_at


def read_id(data, i):
    width = vint_width(data[i])
    return int.from_bytes(data[i:i + width], "big"), width


def read_size(data, i):
    """A size and its width; None for the all-ones value, an unknown size."""
    width = vint_width(data[i])
    value = int.from_bytes(data[i:i + width], "big") & ((1 << (7 * width)) - 1)
    return (None if value == (1 << (7 * width)) - 1 else value), width


def vint_width(first):
    for width in range(1, 9):
        if first & (0x80 >> (width - 1)):
            return width
    raise ValueError("not an EBML number: 0x%02x" % first)


def encode_size(value, width=None):
    """[value] as an EBML size, in [width] bytes (the fewest that hold it when None)."""
    if width is None:
        width = next(w for w in range(1, 9) if value < (1 << (7 * w)) - 1)
    if value >= (1 << (7 * width)) - 1:
        raise ValueError("%d doesn't fit a size of %d bytes" % (value, width))
    return ((1 << (7 * width)) | value).to_bytes(width, "big")


def element(id_, data):
    return id_.to_bytes((id_.bit_length() + 7) // 8, "big") + encode_size(len(data)) + data


def children(data, parent_body, parent_end):
    """The elements in [parent_body, parent_end); one of unknown size (a Segment, a Cluster) runs to the end and stops the list."""
    out, i = [], parent_body
    while i < parent_end:
        id_, id_width = read_id(data, i)
        size, size_width = read_size(data, i + id_width)
        body = i + id_width + size_width
        end = None if size is None else body + size
        out.append(Element(id_, i, i + id_width, body, end))
        if end is None:
            break
        i = end
    return out


def walk(data, body, end, found):
    """Every element under [body, end) that [WALKED] reaches, in file order, into [found]."""
    for e in children(data, body, end):
        found.append(e)
        if e.id in WALKED:
            walk(data, e.body, len(data) if e.end is None else e.end, found)


def uint(data, e):
    return int.from_bytes(data[e.body:e.end], "big")


def record(profile, level, compat, el, rpu=True, bl=True):
    """The 24-byte dvcC/dvvC record: version 1.0, the profile, level, which layers are present, the compatibility id, then zeros."""
    if not 0 <= level < 64 or not 0 <= compat < 16:
        raise ValueError("level %d or compatibility id %d out of range" % (level, compat))
    return bytes([1, 0, profile << 1 | level >> 5, (level & 0x1F) << 3 | rpu << 2 | el << 1 | bl, compat << 4]) + bytes(19)


def mapping(profile, level, compat, el):
    fourcc = b"dvvC" if profile > 7 else b"dvcC"
    return element(
        BLOCK_ADDITION_MAPPING,
        element(BLOCK_ADD_ID_TYPE, fourcc) + element(BLOCK_ADD_ID_EXTRA_DATA, record(profile, level, compat, el)),
    )


def add(data, profile, level, compat=None, el=None):
    """[data], a Matroska file, with the record in its one video track."""
    compat = PROFILES[profile] if compat is None else compat
    el = profile == 7 if el is None else el
    segment = next((e for e in children(data, 0, len(data)) if e.id == SEGMENT), None)
    if segment is None:
        raise ValueError("no Segment")
    found = []
    walk(data, segment.body, len(data) if segment.end is None else segment.end, found)
    if any(e.id == CRC32 for e in found):
        raise ValueError("the file has CRC-32 elements, which the change would break: write it with -write_crc32 0")
    tracks = [e for e in found if e.id == TRACKS]
    entries = [e for e in found if e.id == TRACK_ENTRY]
    video = [t for t in entries if any(c.id == TRACK_TYPE and uint(data, c) == VIDEO for c in children(data, t.body, t.end))]
    if len(tracks) != 1 or len(video) != 1:
        raise ValueError("%d Tracks elements and %d video tracks: one of each is needed" % (len(tracks), len(video)))
    (track,) = video
    if any(c.id == BLOCK_ADDITION_MAPPING for c in children(data, track.body, track.end)):
        raise ValueError("the video track already has a BlockAdditionMapping")
    added = mapping(profile, level, compat, el)
    at = track.end
    out = bytearray(data[:at] + added + data[at:])
    # The elements that hold the mapping grow; a Segment of unknown size stays so. Each size keeps its width, as the
    # positions of everything after it are worked out from that.
    for e in (track, tracks[0], segment):
        if e.end is not None:
            out[e.size_at:e.body] = encode_size(e.end - e.body + len(added), e.size_width)
    # Positions are from the Segment's data, which starts before the change; a target the mapping pushed on moves with it.
    for e in found:
        if e.id in (SEEK_POSITION, CUE_CLUSTER_POSITION):
            value = uint(data, e)
            if segment.body + value >= at:
                width = e.end - e.body
                if value + len(added) >= 1 << (8 * width):
                    raise ValueError("a position past %d bytes no longer fits its element" % value)
                start = e.body + (len(added) if e.start >= at else 0)
                out[start:start + width] = (value + len(added)).to_bytes(width, "big")
    check(bytes(out))
    return bytes(out)


def check(data):
    """That every SeekPosition reaches its element and every CueClusterPosition a Cluster."""
    segment = next(e for e in children(data, 0, len(data)) if e.id == SEGMENT)
    found = []
    walk(data, segment.body, len(data) if segment.end is None else segment.end, found)
    for seek in (e for e in found if e.id == SEEK):
        parts = {c.id: c for c in children(data, seek.body, seek.end)}
        target = read_id(data, segment.body + uint(data, parts[SEEK_POSITION]))[0]
        if target != int.from_bytes(data[parts[SEEK_ID].body:parts[SEEK_ID].end], "big"):
            raise AssertionError("a SeekPosition misses its element")
    for position in (e for e in found if e.id == CUE_CLUSTER_POSITION):
        if read_id(data, segment.body + uint(data, position))[0] != CLUSTER:
            raise AssertionError("a CueClusterPosition misses its Cluster")


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("input")
    parser.add_argument("output")
    parser.add_argument("--profile", type=int, required=True, choices=sorted(PROFILES))
    parser.add_argument("--level", type=int, required=True)
    parser.add_argument("--compat", type=int)
    parser.add_argument("--el", action="store_true", default=None)
    args = parser.parse_args(argv)
    with open(args.input, "rb") as f:
        data = f.read()
    with open(args.output, "wb") as f:
        f.write(add(data, args.profile, args.level, args.compat, args.el))


if __name__ == "__main__":
    sys.exit(main())
