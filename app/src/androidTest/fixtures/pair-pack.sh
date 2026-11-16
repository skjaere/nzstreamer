#!/usr/bin/env bash
# Makes the season pack pair's two files and seeds the mock NNTP server with them: two generated videos of about
# 24 MB each (H.264 and AAC in Matroska, a test pattern and a tone), the episodes S03E07 and S03E08 of the invented
# pack Harbor.Lights.S03.1080p.WEB.h264-KESTREL. The pair SeasonPackKill (StartSeasonPackKill and CheckSeasonPackKill in
# the app's androidTest download package) downloads both through app/src/androidTest/assets/pair-pack.nzb, and checks
# each file against app/src/androidTest/assets/pair-pack.sha256.
#
# The files are the same at every run (ffmpeg's bit-exact flags, one thread, fixed settings), so their articles' ids,
# which come from the bytes, are the asset's. Before it seeds, the script checks both SHA-256s against the asset and
# stops on a difference (an ffmpeg that encodes differently). mock-seed's seed takes one video, so each file is seeded
# on its own and the two NZBs' <file> elements are merged into one: it then differs from the asset only in its dates.
#
# Run it from the repository's top folder, with the mock up (mock-seed's default http://localhost:8081):
#
#     bash app/src/androidTest/fixtures/pair-pack.sh build/pair-pack
#
# MOCK_SEED names another command to run mock-seed's seed with (default: ./gradlew -q :tools:mock-seed:run --args=...);
# it gets seed's own arguments. NO_SEED=1 makes and checks the files only. With no asset yet, the SHA-256s are written
# to OUT/pair-pack.sha256 and nothing is checked.
#
# usage: pair-pack.sh OUT
set -euo pipefail
OUT=${1:?usage: pair-pack.sh OUT}
SUMS=app/src/androidTest/assets/pair-pack.sha256
mkdir -p "$OUT"
seed() { if [ -n "${MOCK_SEED:-}" ]; then $MOCK_SEED "$@"; else ./gradlew -q :tools:mock-seed:run --args="$*"; fi; }

# The two episodes: their names, and the pattern and tone that keep their bytes apart.
names=(Harbor.Lights.S03E07.1080p.WEB.h264-KESTREL.mkv Harbor.Lights.S03E08.1080p.WEB.h264-KESTREL.mkv)
videos=("testsrc2=size=1920x1080:rate=24:duration=60" "testsrc2=size=1920x1080:rate=24:duration=60,hue=H=2*PI*t/10,vflip")
tones=("sine=frequency=440:sample_rate=48000:duration=60" "sine=frequency=660:sample_rate=48000:duration=60")
for i in 0 1; do
  ffmpeg -nostdin -hide_banner -loglevel error -y -threads 1 \
    -f lavfi -i "${videos[$i]}" -f lavfi -i "${tones[$i]}" \
    -map 0:v -map 1:a -map_metadata -1 -map_chapters -1 \
    -c:v libx264 -threads 1 -preset veryfast -profile:v high -pix_fmt yuv420p -b:v 3000k -maxrate 3000k -bufsize 6000k \
    -x264-params "keyint=48:min-keyint=48:scenecut=0:threads=1:lookahead-threads=1:sliced-threads=0" -flags:v +bitexact \
    -c:a aac -b:a 128k -ac 2 -flags:a +bitexact \
    -fflags +bitexact -f matroska "$OUT/${names[$i]}"
done
(cd "$OUT" && sha256sum "${names[@]}") > "$OUT/pair-pack.sha256"
if [ -f "$SUMS" ]; then
  if ! diff -q "$SUMS" "$OUT/pair-pack.sha256" > /dev/null; then
    echo "pair-pack: DIFFERENT from $SUMS (this ffmpeg encodes other bytes); nothing seeded" >&2
    diff "$SUMS" "$OUT/pair-pack.sha256" >&2 || true
    exit 1
  fi
  echo "pair-pack: the SHA-256s are the asset's"
else
  echo "pair-pack: no $SUMS yet; the SHA-256s are in $OUT/pair-pack.sha256"
fi
ls -l "$OUT/${names[0]}" "$OUT/${names[1]}" | awk '{ print "pair-pack:", $9, $5, "B" }'
[ "${NO_SEED:-0}" = 1 ] && exit 0

for i in 0 1; do seed seed --video "$OUT/${names[$i]}" --out "$OUT/part-$i.nzb"; done
# One NZB: the first's head, both files' <file> elements, the first's end.
python3 -I - "$OUT/part-0.nzb" "$OUT/part-1.nzb" > "$OUT/pair-pack.nzb" <<'PY'
import re, sys
first, second = (open(p).read() for p in sys.argv[1:])
files = re.findall(r"  <file .*?</file>\n", second, re.S)
assert len(files) == 1 and len(re.findall(r"  <file .*?</file>\n", first, re.S)) == 1, "one <file> in each"
end = first.rindex("</nzb>")
sys.stdout.write(first[:end] + files[0] + first[end:])
PY
rm -f "$OUT/part-0.nzb" "$OUT/part-1.nzb"
echo "pair-pack: seeded, $(grep -c '<segment ' "$OUT/pair-pack.nzb") articles in $OUT/pair-pack.nzb"
