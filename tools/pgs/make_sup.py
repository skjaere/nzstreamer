"""
Writes a PGS subtitle stream (a .sup file) of made-up bitmap cues, for test fixtures and the stream bench: ffmpeg
reads PGS but can't encode it. Mux the result with `ffmpeg ... -i cues.sup -c:s copy`.

Each cue is a display set, PCS (presentation composition), WDS (window), PDS (palette), ODS (the bitmap, run-length
coded, split over several ODS segments past 64 KB) and END, at its start; at its end a display set with no object
(PCS, WDS, END) clears it. Times are 90 kHz, as in the Blu-ray format. A cue's bitmap sits centred near the bottom of
the --size plane; its pixels are palette indices: 0 transparent, 1 white, 2 black, 3 grey.

  --cue START-END[:WxH]   one cue, in seconds, its own bitmap size (default --bitmap); repeat for more
  --every S --length L    a cue of L seconds every S seconds from --start to --end (default 0 and 60)
  --fill box|text         a white box with a black edge (each cue alike, for tests that tell cues by their size),
                          or lines of made-up glyphs with black edges and grey anti-aliasing, a different text each cue
                          (--seed), as real subtitles compress; --variants N takes turns among N texts instead

  python3 -I tools/pgs/make_sup.py out.sup --size 160x90 --cue 1-2:40x10 --cue 3-4:48x10
  python3 -I tools/pgs/make_sup.py out.sup --size 1920x1080 --bitmap 960x540 --fill text --variants 6 --every 2.5 --length 1.5 --end 90
Standard library only.
"""
import argparse, random, struct, sys

PDS, ODS, PCS, WDS, END = 0x14, 0x15, 0x16, 0x17, 0x80
TRANSPARENT, WHITE, BLACK, GREY = 0, 1, 2, 3
# Y, Cr, Cb, alpha for each index, as BT.601 video levels.
PALETTE = {TRANSPARENT: (16, 128, 128, 0), WHITE: (235, 128, 128, 255), BLACK: (16, 128, 128, 255), GREY: (126, 128, 128, 192)}
# A segment's length field is 16 bits; an ODS carries its object id, version and sequence flags (4 bytes) first, and
# the first one also the data length (3) and the size (4).
MAX_SEGMENT = 0xFFFF
FIRST_ODS_DATA = MAX_SEGMENT - 11
NEXT_ODS_DATA = MAX_SEGMENT - 4


def segment(kind, seconds, data):
    """One segment: "PG", its presentation and decoding time stamps (90 kHz; the decoding one 0, as most files have it), its type and length."""
    if len(data) > MAX_SEGMENT:
        raise ValueError("segment of %d bytes" % len(data))
    return b"PG" + struct.pack(">IIBH", round(seconds * 90_000) & 0xFFFFFFFF, 0, kind, len(data)) + data


def encode_row(runs):
    """A row of the bitmap, as (index, count) runs, in PGS run-length code, ending with the end-of-line code 00 00."""
    out = bytearray()
    for index, count in merged(runs):
        while count > 0:
            n = min(count, 0x3FFF)
            count -= n
            if index == TRANSPARENT:
                out += bytes([0, n]) if n < 0x40 else bytes([0, 0x40 | n >> 8, n & 0xFF])
            elif n < 3:
                # One or two pixels are shorter written one by one.
                out += bytes([index]) * n
            elif n < 0x40:
                out += bytes([0, 0x80 | n, index])
            else:
                out += bytes([0, 0xC0 | n >> 8, n & 0xFF, index])
    return bytes(out + b"\x00\x00")


def merged(runs):
    """[runs] with neighbours of one index joined and empty ones dropped."""
    out = []
    for index, count in runs:
        if count <= 0:
            continue
        if out and out[-1][0] == index:
            out[-1] = (index, out[-1][1] + count)
        else:
            out.append((index, count))
    return out


def box(width, height):
    """A white box with a one-pixel black edge, as rows of runs."""
    edge = [(BLACK, width)]
    inside = [(BLACK, 1), (WHITE, width - 2), (BLACK, 1)]
    return [edge if y in (0, height - 1) else inside for y in range(height)]


def glyphs(rng, count=26):
    """Made-up glyphs: 5 x 7 cells, each on or off, about two in five on."""
    return [[[rng.random() < 0.4 for _ in range(5)] for _ in range(7)] for _ in range(count)]


def text(width, height, rng, font, scale=4):
    """
    Lines of made-up words in [font]'s glyphs, each cell [scale] pixels: white where a glyph's cell is on, black where
    it borders one, grey for a pixel where white meets black. Transparent elsewhere. As rows of runs.
    """
    cell_rows = height // scale
    cell_cols = width // scale
    grid = [[TRANSPARENT] * cell_cols for _ in range(cell_rows)]
    # Lines with a glyph's height and more between them, so most of the bitmap stays transparent, as with real subtitles.
    line_height = 7 + 10
    for top in range(1, cell_rows - 8, line_height):
        col = 1
        while col < cell_cols - 6:
            for _ in range(rng.randint(2, 8)):
                if col >= cell_cols - 6:
                    break
                glyph = rng.choice(font)
                for y, row in enumerate(glyph):
                    for x, on in enumerate(row):
                        if on:
                            grid[top + y][col + x] = WHITE
                col += 6
            col += 3
    # The black edge: cells next to a white one.
    edged = [row[:] for row in grid]
    for y in range(cell_rows):
        for x in range(cell_cols):
            if grid[y][x] != TRANSPARENT:
                continue
            if any(grid[y + dy][x + dx] == WHITE for dy in (-1, 0, 1) for dx in (-1, 0, 1)
                   if 0 <= y + dy < cell_rows and 0 <= x + dx < cell_cols):
                edged[y][x] = BLACK
    rows = []
    for y in range(height):
        cells = edged[min(y // scale, cell_rows - 1)]
        runs = []
        for x, index in enumerate(cells):
            # Grey where white meets black, as anti-aliasing would have it.
            left_grey = index == WHITE and x > 0 and cells[x - 1] == BLACK
            right_grey = index == WHITE and x + 1 < len(cells) and cells[x + 1] == BLACK
            inner = scale - left_grey - right_grey
            if left_grey:
                runs.append((GREY, 1))
            runs.append((index, inner))
            if right_grey:
                runs.append((GREY, 1))
        runs.append((TRANSPARENT, width - cell_cols * scale))
        rows.append(runs)
    return rows


def display_set(seconds, number, plane, size, rows):
    """The display set that shows a bitmap of [size] made of [rows] from [seconds], composition [number]."""
    (pw, ph), (w, h) = plane, size
    x, y = (pw - w) // 2, max(0, ph - h - ph // 20)
    rle = b"".join(encode_row(r) for r in rows)
    out = segment(PCS, seconds, struct.pack(">HHBHBBBB", pw, ph, 0x10, number, 0x80, 0, 0, 1) + struct.pack(">HBBHH", 0, 0, 0, x, y))
    out += segment(WDS, seconds, struct.pack(">BBHHHH", 1, 0, x, y, w, h))
    out += segment(PDS, seconds, struct.pack(">BB", 0, 0) + b"".join(struct.pack(">BBBBB", i, *v) for i, v in sorted(PALETTE.items())))
    chunks = [rle[:FIRST_ODS_DATA]] + [rle[i:i + NEXT_ODS_DATA] for i in range(FIRST_ODS_DATA, len(rle), NEXT_ODS_DATA)]
    for i, chunk in enumerate(chunks):
        flags = (0x80 if i == 0 else 0) | (0x40 if i == len(chunks) - 1 else 0)
        head = struct.pack(">HBB", 0, 0, flags)
        if i == 0:
            head += (len(rle) + 4).to_bytes(3, "big") + struct.pack(">HH", w, h)
        out += segment(ODS, seconds, head + chunk)
    return out + segment(END, seconds, b"")


def clear_set(seconds, number, plane, size):
    """The display set that clears the screen at [seconds]: a composition with no object, in the same window."""
    (pw, ph), (w, h) = plane, size
    x, y = (pw - w) // 2, max(0, ph - h - ph // 20)
    out = segment(PCS, seconds, struct.pack(">HHBHBBBB", pw, ph, 0x10, number, 0x00, 0, 0, 0))
    out += segment(WDS, seconds, struct.pack(">BBHHHH", 1, 0, x, y, w, h))
    return out + segment(END, seconds, b"")


def sup(cues, plane, fill="box", seed=1, variants=0):
    """
    The .sup stream of [cues], (start, end, (width, height)) in seconds, on a [plane] of (width, height). With
    [variants], text fills take turns among that many texts of each size, which makes long streams quicker to write.
    """
    rng = random.Random(seed)
    font = glyphs(rng)
    texts = {}
    out = bytearray()
    for i, (start, end, size) in enumerate(sorted(cues)):
        if end <= start:
            raise ValueError("a cue must end after it starts: %s-%s" % (start, end))
        if fill == "box":
            rows = box(*size)
        else:
            key = (size, i % variants if variants else i)
            if key not in texts:
                texts[key] = text(size[0], size[1], rng, font)
            rows = texts[key]
        # The composition number is 16 bits, counting on through a long stream.
        out += display_set(start, 2 * i & 0xFFFF, plane, size, rows)
        out += clear_set(end, (2 * i + 1) & 0xFFFF, plane, size)
    return bytes(out)


def size(text):
    w, h = text.lower().split("x")
    return int(w), int(h)


def cue(text, default):
    times, _, dims = text.partition(":")
    start, end = (float(t) for t in times.split("-"))
    return start, end, size(dims) if dims else default


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("out")
    p.add_argument("--size", type=size, required=True, help="the plane (the video's size), WxH")
    p.add_argument("--bitmap", type=size, default=None, help="each cue's bitmap, WxH (default a quarter of the plane's width by a tenth of its height)")
    p.add_argument("--cue", action="append", default=[], help="START-END[:WxH], in seconds")
    p.add_argument("--every", type=float, help="a cue every S seconds")
    p.add_argument("--length", type=float, default=1.0, help="each periodic cue's length in seconds")
    p.add_argument("--start", type=float, default=0.0)
    p.add_argument("--end", type=float, default=60.0)
    p.add_argument("--fill", choices=("box", "text"), default="box")
    p.add_argument("--seed", type=int, default=1)
    p.add_argument("--variants", type=int, default=0, help="with --fill text, take turns among this many texts")
    a = p.parse_args(argv)
    bitmap = a.bitmap or (a.size[0] // 4, a.size[1] // 10)
    cues = [cue(c, bitmap) for c in a.cue]
    if a.every:
        t = a.start
        while t + a.length <= a.end:
            cues.append((round(t, 3), round(t + a.length, 3), bitmap))
            t += a.every
    if not cues:
        p.error("no cues: give --cue or --every")
    for start, end, (w, h) in cues:
        if w > a.size[0] or h > a.size[1] or w < 3 or h < 3:
            p.error("a %dx%d bitmap doesn't fit the %dx%d plane" % (w, h, *a.size))
    with open(a.out, "wb") as f:
        f.write(sup(cues, a.size, a.fill, a.seed, a.variants))
    print("%s: %d cues" % (a.out, len(cues)), file=sys.stderr)


if __name__ == "__main__":
    main()
