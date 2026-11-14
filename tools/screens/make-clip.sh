#!/bin/bash
# The README screenshots' player clip, made from a silent film in the public domain: its picture alone, looped to a
# little over five and a half minutes, as the player only takes an intro chapter that ends five minutes or more before
# the end of the file. The picture is scaled to 1080 lines and pillarboxed to 1920x1080 at 24 fps, in H.264 High. Three
# silent AAC tracks (English, French and Spanish) and three subtitle tracks in the same languages give the audio and
# subtitles panel something to list; the lines are written here, the same ones every pass of the film. Chapters:
# "Intro" (the first 15 s), then "Part 1".
#
# Usage: tools/screens/make-clip.sh <film> <out dir> [--from <seconds>] [--to <seconds>]
#   <film>     any video ffmpeg reads; only its first video stream is used.
#   <out dir>  gets clip.mkv; made if missing. Keep it outside the repository.
#   --from     use the film from this time on, else from its start;
#   --to       up to this time, else to its end. Together they keep the film's own picture alone, for a copy with a title
#              card, black frames or a fade at either end.
# Needs ffmpeg and ffprobe. The same film and ffmpeg give the same bytes.
set -euo pipefail

usage() { echo "usage: $0 <film> <out dir> [--from <seconds>] [--to <seconds>]" >&2; exit 2; }
[ $# -ge 2 ] || usage
film=$1 out=$2 from= to=
shift 2
while [ $# -gt 0 ]; do
  [ $# -ge 2 ] || usage
  case "$1" in
    --from) from=$2 ;;
    --to) to=$2 ;;
    *) usage ;;
  esac
  [[ $2 =~ ^[0-9]+(\.[0-9]+)?$ ]] || { echo "$1 takes seconds, as 42 or 42.4" >&2; exit 2; }
  shift 2
done
command -v ffmpeg >/dev/null && command -v ffprobe >/dev/null || { echo "ffmpeg and ffprobe are needed" >&2; exit 2; }
[ -f "$film" ] || { echo "no such film: $film" >&2; exit 2; }
ffprobe -v error -select_streams v:0 -show_entries stream=codec_type -of csv=p=0 "$film" | grep -qx video ||
  { echo "$film holds no video stream" >&2; exit 2; }

# The intro, and how long the file must at least be for the player to offer to skip it.
INTRO_END=15
MIN_LENGTH=$((INTRO_END + 300 + 15))

film_length=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$film")
start=${from:-0} end=${to:-$film_length}
awk -v s="$start" -v e="$end" -v l="$film_length" 'BEGIN { exit !(s >= 0 && s < e && e <= l) }' ||
  { echo "--from ${from:-0} and --to ${to:-the end} aren't a part of the film (${film_length} s)" >&2; exit 2; }
length=$(awk -v s="$start" -v e="$end" 'BEGIN { printf "%.3f", e - s }')
awk -v l="$length" -v i="$INTRO_END" 'BEGIN { exit !(l > i + 5) }' || { echo "the film is too short: ${length} s" >&2; exit 2; }
passes=$(awk -v l="$length" -v m="$MIN_LENGTH" 'BEGIN { n = int(m / l); if (n * l < m) n++; print n }')
total=$(awk -v l="$length" -v n="$passes" 'BEGIN { printf "%.3f", l * n }')

mkdir -p "$out"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# The film's picture alone, cut to --from and --to frame by frame (decoded up to there, not sought), into a lossless
# file of whole frames that the passes below repeat: nothing of the rest of the film, nor its tags, goes along.
ffmpeg -hide_banner -loglevel error -y -i "$film" -ss "$start" -to "$end" \
  -map 0:v:0 -map_metadata -1 -map_metadata:s -1 -map_chapters -1 \
  -c:v ffv1 -level 3 -g 1 -slices 4 -threads 4 -fflags +bitexact -flags:v +bitexact "$work/film.mkv"

# The film, pass after pass, through ffmpeg's concat demuxer.
for _ in $(seq "$passes"); do
  printf "file '%s'\n" "$work/film.mkv"
done >"$work/passes.txt"

# Seven lines per pass, 4 s each, every 6 s from 2 s in, as many as the pass has room for; written for this clip (a
# crowd on a quay as a guard parade marches past, steamboats on the water behind), in each language.
EN=(
  "Here they come, right on time."
  "Stand back a little, let them pass."
  "Can you see the band from there?"
  "They march past at noon every day."
  "Hold my hat, I want a better look."
  "The boats are out early this morning."
  "That's the last of them. Shall we go?"
)
FR=(
  "Les voilà, juste à l'heure."
  "Reculez un peu, laissez-les passer."
  "Tu vois la fanfare d'ici ?"
  "Ils défilent à midi tous les jours."
  "Tiens mon chapeau, je veux mieux voir."
  "Les bateaux sont sortis tôt ce matin."
  "Ce sont les derniers. On y va ?"
)
ES=(
  "Ahí vienen, justo a tiempo."
  "Apartaos un poco, dejadlos pasar."
  "¿Ves la banda desde ahí?"
  "Desfilan a mediodía todos los días."
  "Sujétame el sombrero, quiero verlo mejor."
  "Los barcos han salido temprano esta mañana."
  "Son los últimos. ¿Nos vamos?"
)

# Writes an SRT of the given lines, repeated every pass; a line that would run past its pass is left out.
srt() {
  printf '%s\n' "$@" | awk -v l="$length" -v n="$passes" '
    function stamp(t,  ms) { ms = int(t * 1000 + 0.5); return sprintf("%02d:%02d:%02d,%03d", int(ms / 3600000), int(ms / 60000) % 60, int(ms / 1000) % 60, ms % 1000) }
    { line[NR] = $0 }
    END {
      cue = 0
      for (p = 0; p < n; p++) for (i = 1; i <= NR; i++) {
        start = 2 + 6 * (i - 1); end = start + 4
        if (end > l) continue
        cue++
        printf "%d\n%s --> %s\n%s\n\n", cue, stamp(p * l + start), stamp(p * l + end), line[i]
      }
    }'
}
srt "${EN[@]}" >"$work/en.srt"
srt "${FR[@]}" >"$work/fr.srt"
srt "${ES[@]}" >"$work/es.srt"

end_ms=$(awk -v t="$total" 'BEGIN { printf "%d", t * 1000 }')
cat >"$work/chapters.txt" <<EOF
;FFMETADATA1
[CHAPTER]
TIMEBASE=1/1000
START=0
END=$((INTRO_END * 1000))
title=Intro
[CHAPTER]
TIMEBASE=1/1000
START=$((INTRO_END * 1000))
END=$end_ms
title=Part 1
EOF

# CRF 26 looks the same as finer settings on an old film's picture and keeps the clip near 125 MB: the screens indexer
# seeds it twice into the mock NNTP server, which holds every article in memory.
# The file's own tags come from the chapters file alone, which has none, and no stream keeps a tag of its input's (a
# title or a handler's name): nothing of the film's goes along but its picture.
ffmpeg -hide_banner -loglevel error -y \
  -f concat -safe 0 -i "$work/passes.txt" \
  -f lavfi -i "anullsrc=channel_layout=stereo:sample_rate=48000" \
  -f lavfi -i "anullsrc=channel_layout=stereo:sample_rate=48000" \
  -f lavfi -i "anullsrc=channel_layout=stereo:sample_rate=48000" \
  -i "$work/en.srt" -i "$work/fr.srt" -i "$work/es.srt" \
  -i "$work/chapters.txt" \
  -map 0:v:0 -map 1:a -map 2:a -map 3:a -map 4:s -map 5:s -map 6:s -map_metadata 7 -map_metadata:s -1 -map_chapters 7 \
  -t "$total" \
  -vf "fps=24,scale=-2:1080:flags=lanczos,pad=1920:1080:(ow-iw)/2:(oh-ih)/2:black,setsar=1,format=yuv420p" \
  -c:v libx264 -profile:v high -preset slow -crf 26 -g 48 -keyint_min 48 -sc_threshold 0 -threads 4 \
  -c:a aac -b:a 32k -c:s srt \
  -metadata:s:a:0 language=eng -metadata:s:a:1 language=fre -metadata:s:a:2 language=spa \
  -metadata:s:s:0 language=eng -metadata:s:s:1 language=fre -metadata:s:s:2 language=spa \
  -disposition:v:0 default -disposition:a:0 default -disposition:a:1 0 -disposition:a:2 0 \
  -disposition:s:0 0 -disposition:s:1 0 -disposition:s:2 0 \
  -fflags +bitexact -flags:v +bitexact -flags:a +bitexact \
  "$work/clip.mkv"

# What the screenshots rely on.
streams=$(ffprobe -v error -show_entries stream=codec_name,codec_type:stream_tags=language -of csv=p=0 "$work/clip.mkv")
expected=$'h264,video\naac,audio,eng\naac,audio,fre\naac,audio,spa\nsubrip,subtitle,eng\nsubrip,subtitle,fre\nsubrip,subtitle,spa'
[ "$streams" = "$expected" ] || { printf 'unexpected streams:\n%s\n' "$streams" >&2; exit 1; }
size=$(ffprobe -v error -select_streams v:0 -show_entries stream=width,height -of csv=p=0:s=x "$work/clip.mkv")
[ "$size" = 1920x1080 ] || { echo "the picture is $size, not 1920x1080" >&2; exit 1; }
chapters=$(ffprobe -v error -show_entries chapter_tags=title -of csv=p=0 "$work/clip.mkv" | paste -sd,)
[ "$chapters" = "Intro,Part 1" ] || { echo "unexpected chapters: $chapters" >&2; exit 1; }
# No tag of the film's: the muxer's and encoders' own (encoder, ENCODER, DURATION) and the tracks' languages alone,
# and none but the first two on the picture.
other=$(ffprobe -v error -show_entries stream_tags:format_tags -of default=nw=1 "$work/clip.mkv" | sed 's/=.*//' |
  grep -v -x -E 'TAG:(encoder|ENCODER|DURATION|language)' | sort -u | paste -sd, || true)
[ -z "$other" ] || { echo "the clip carries other tags: $other" >&2; exit 1; }
video_tags=$(ffprobe -v error -select_streams v:0 -show_entries stream_tags -of default=nw=1 "$work/clip.mkv" | sed 's/=.*//' | LC_ALL=C sort | paste -sd,)
[ "$video_tags" = "TAG:DURATION,TAG:ENCODER" ] || { echo "the picture carries other tags: $video_tags" >&2; exit 1; }
made=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$work/clip.mkv")
awk -v d="$made" -v m="$MIN_LENGTH" 'BEGIN { exit !(d >= m - 1) }' || { echo "the clip is ${made} s, shorter than ${MIN_LENGTH} s" >&2; exit 1; }

mv "$work/clip.mkv" "$out/clip.mkv"
echo "Wrote $out/clip.mkv: $passes passes of ${length} s, ${made} s, $(stat -c %s "$out/clip.mkv") bytes"
