#!/bin/bash
# The stream bench's release with many PGS subtitle tracks, for what subtitles cost a play (a 4K remux can carry dozens):
# make-clip.sh's clip, its first SECONDS (80) as they are, with TRACKS (40) PGS tracks in as many languages, each a cue
# of 1.5 s every 2.5 s with a quarter-screen bitmap of made-up text (tools/pgs/make_sup.py), and an English SRT track
# with a line every 2.5 s. None is flagged default or forced, so a play shows none of them unless one is picked. With
# the defaults about 900 MB, as much of it as the clip's 80 s and 130 MB of subtitles.
# Usage: make-pgs-clip.sh <clip.mkv from make-clip.sh> <output.mkv> [tracks] [seconds]
set -euo pipefail
clip=${1:?clip mkv} out=${2:?output mkv} tracks=${3:-40} seconds=${4:-80}
here=$(cd "$(dirname "$0")" && pwd)
languages=(ger fre spa ita por dut swe nor dan fin pol cze slo hun rum bul gre tur rus ukr heb ara hin tha vie ind may chi
  jpn kor est lav lit slv hrv srp ice cat baq glg)
[ "$tracks" -le ${#languages[@]} ] || { echo "make-pgs-clip: at most ${#languages[@]} tracks" >&2; exit 2; }
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
size=$(ffprobe -v error -select_streams v:0 -show_entries stream=width,height -of csv=s=x:p=0 "$clip")
width=${size%x*} height=${size#*x}
inputs=() maps=() tags=()
for i in $(seq 0 $((tracks - 1))); do
  python3 -I "$here/../pgs/make_sup.py" "$work/$i.sup" --size "$size" --bitmap "$((width / 2))x$((height / 2))" --fill text \
    --variants 6 --seed "$((i + 1))" --every 2.5 --length 1.5 --start 0.5 --end "$seconds" 2> /dev/null
  inputs+=(-i "$work/$i.sup")
  maps+=(-map "$((i + 1)):s")
  tags+=("-metadata:s:s:$i" "language=${languages[$i]}" "-disposition:s:$i" 0)
done
python3 -I -c 'import sys
n, end = 0, float(sys.argv[1])
t = 0.5
while t + 1.5 <= end:
    n += 1
    ms = lambda s: "%02d:%02d:%02d,%03d" % (s // 3600, s // 60 % 60, s % 60, round(s * 1000) % 1000)
    print("%d\n%s --> %s\nLine %d\n" % (n, ms(t), ms(t + 1.5), n))
    t += 2.5' "$seconds" > "$work/en.srt"
inputs+=(-i "$work/en.srt")
maps+=(-map "$((tracks + 1)):s")
tags+=("-metadata:s:s:$tracks" language=eng "-disposition:s:$tracks" 0)
# -copyts keeps the cues' times: ffmpeg would otherwise move each input's first packet to 0.
ffmpeg -hide_banner -loglevel error -y -copyts -i "$clip" "${inputs[@]}" -map 0:v -map 0:a "${maps[@]}" -t "$seconds" \
  -c copy "${tags[@]}" "$out"
echo "make-pgs-clip: $out, $tracks PGS tracks and an SRT track, $(du -m "$out" | cut -f1) MB" >&2
