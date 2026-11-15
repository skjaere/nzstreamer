#!/usr/bin/env python3
"""Makes the two-phase download pairs' file: 48,000,000 seeded bytes (48 MB), the same on every run, and prints their
SHA-256 (the asset app/src/androidTest/assets/pair-download.sha256).

The pairs (Start<X> and Check<X> in the app's androidTest download package) download it from the mock NNTP server, as
the release Harbor.Lights.S03E07.1080p.WEB.h264-KESTREL, through the NZB in app/src/androidTest/assets/pair-download.nzb.
Seed the mock with it from the repository's top folder, under exactly that file name, in mock-seed's default articles of
750,000 bytes (64 of them):

    python3 -I app/src/androidTest/fixtures/pair-download.py build/Harbor.Lights.S03E07.1080p.WEB.h264-KESTREL.mkv
    ./gradlew :tools:mock-seed:run --args="seed --video build/Harbor.Lights.S03E07.1080p.WEB.h264-KESTREL.mkv --out build/pair-download.nzb"

The articles' ids come from the bytes and the file's name goes into their yEnc headers, so the mock then holds every
article the asset names: the NZB the seeding writes differs from the asset only in its date. The bytes are random, not a
video, so no player reads them.

usage: pair-download.py OUT
"""
import hashlib
import random
import sys

SIZE = 48_000_000
SEED = 250367

data = random.Random(SEED).randbytes(SIZE)
with open(sys.argv[1], "wb") as out:
    out.write(data)
print(hashlib.sha256(data).hexdigest())
