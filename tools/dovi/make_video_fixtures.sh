#!/bin/bash
# Makes core:player's video fixtures (see the README beside them) and prints their SHA-256s, which the README records:
# made with ffmpeg 8.0.1 (libx264 0.165.3222, libx265 4.1, SVT-AV1 2.3.0), reruns give the same bytes. The Dolby Vision ones
# are hevc10.mkv or hdr10-hevc.mkv with a configuration record added (add_dovi_config.py).
#   bash tools/dovi/make_video_fixtures.sh [output directory, by default core/player/src/androidTest/assets]
set -euo pipefail
root=$(cd "$(dirname "$0")/../.." && pwd)
out=$(cd "${1:-$root/core/player/src/androidTest/assets}" && pwd)
cd "$out"
ffmpeg -nostdin -hide_banner -loglevel error -y -f lavfi -i testsrc=duration=1:size=160x90:rate=10 -f lavfi -i sine=frequency=440:duration=1:sample_rate=8000 -map 0:v -map 1:a -c:v libx265 -profile:v main10 -pix_fmt yuv420p10le -preset veryslow -crf 45 -g 10 -x265-params log-level=error:info=0:frame-threads=1:pools=none -c:a aac -ac 1 -b:a 8k -write_crc32 0 -fflags +bitexact -flags:v +bitexact -flags:a +bitexact hevc10.mkv
python3 -I "$root/tools/dovi/add_dovi_config.py" hevc10.mkv dv-p5.mkv --profile 5 --level 6
python3 -I "$root/tools/dovi/add_dovi_config.py" hevc10.mkv dv-p7.mkv --profile 7 --level 6
python3 -I "$root/tools/dovi/add_dovi_config.py" hevc10.mkv dv-p8.mkv --profile 8 --level 6
# MPEG-2 has no 10 fps.
ffmpeg -nostdin -hide_banner -loglevel error -y -f lavfi -i testsrc=duration=1:size=160x90:rate=25 -f lavfi -i sine=frequency=440:duration=1:sample_rate=8000 -map 0:v -map 1:a -c:v mpeg2video -q:v 31 -g 25 -c:a aac -ac 1 -b:a 8k -write_crc32 0 -fflags +bitexact -flags:v +bitexact -flags:a +bitexact mpeg2.mkv
# HEVC Main (8-bit) at level 6.2, above the levels the emulators' HEVC decoders list (5.2).
ffmpeg -nostdin -hide_banner -loglevel error -y -f lavfi -i testsrc=duration=1:size=160x90:rate=10 -f lavfi -i sine=frequency=440:duration=1:sample_rate=8000 -map 0:v -map 1:a -c:v libx265 -profile:v main -pix_fmt yuv420p -preset veryslow -crf 45 -g 10 -x265-params log-level=error:info=0:frame-threads=1:pools=none:level-idc=6.2:high-tier=0 -c:a aac -ac 1 -b:a 8k -write_crc32 0 -fflags +bitexact -flags:v +bitexact -flags:a +bitexact hevc-level62.mkv
ffmpeg -nostdin -hide_banner -loglevel error -y -f lavfi -i testsrc=duration=1:size=160x90:rate=10 -f lavfi -i sine=frequency=440:duration=1:sample_rate=8000 -map 0:v -map 1:a -c:v libsvtav1 -pix_fmt yuv420p10le -preset 8 -crf 60 -g 5 -svtav1-params lp=1 -c:a aac -ac 1 -b:a 8k -write_crc32 0 -fflags +bitexact -flags:v +bitexact -flags:a +bitexact av1-10bit.mkv 2>/dev/null
# H.264 at 24000/1001 fps, which the emulators play: Matroska's DefaultDuration is then 41,708,333 ns. One thread, as
# x264's output depends on how many it uses.
ffmpeg -nostdin -hide_banner -loglevel error -y -f lavfi -i testsrc=duration=1:size=160x90:rate=24000/1001 -f lavfi -i sine=frequency=440:duration=1:sample_rate=8000 -map 0:v -map 1:a -c:v libx264 -profile:v high -pix_fmt yuv420p -preset veryslow -crf 45 -g 24 -threads 1 -c:a aac -ac 1 -b:a 8k -write_crc32 0 -fflags +bitexact -flags:v +bitexact -flags:a +bitexact rate-23976.mkv
# HDR10: Matroska's Colour element gets its mastering display and MaxCLL only from frames that carry them, so the first
# pass writes them into the HEVC stream (its SEI), and the second decodes that stream and encodes it again.
hdr10='log-level=error:info=0:frame-threads=1:pools=none:hdr10=1:hdr10-opt=0:master-display=G(13250,34500)B(7500,3000)R(34000,16000)WP(15635,16450)L(10000000,50):max-cll=812,400'
ffmpeg -nostdin -hide_banner -loglevel error -y -f lavfi -i testsrc=duration=1:size=160x90:rate=10 -vf setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc:range=tv -c:v libx265 -profile:v main10 -pix_fmt yuv420p10le -preset veryslow -crf 45 -g 10 -x265-params "$hdr10" -write_crc32 0 -fflags +bitexact -flags:v +bitexact hdr10-sei.mkv
ffmpeg -nostdin -hide_banner -loglevel error -y -i hdr10-sei.mkv -f lavfi -i sine=frequency=440:duration=1:sample_rate=8000 -map 0:v -map 1:a -c:v libx265 -profile:v main10 -pix_fmt yuv420p10le -preset veryslow -crf 45 -g 10 -x265-params "$hdr10" -c:a aac -ac 1 -b:a 8k -write_crc32 0 -fflags +bitexact -flags:v +bitexact -flags:a +bitexact hdr10-hevc.mkv
rm hdr10-sei.mkv
ffmpeg -nostdin -hide_banner -loglevel error -y -f lavfi -i testsrc=duration=1:size=160x90:rate=10 -f lavfi -i sine=frequency=440:duration=1:sample_rate=8000 -map 0:v -map 1:a -vf setparams=color_primaries=bt2020:color_trc=arib-std-b67:colorspace=bt2020nc:range=tv -c:v libx265 -profile:v main10 -pix_fmt yuv420p10le -preset veryslow -crf 45 -g 10 -x265-params log-level=error:info=0:frame-threads=1:pools=none -c:a aac -ac 1 -b:a 8k -write_crc32 0 -fflags +bitexact -flags:v +bitexact -flags:a +bitexact hlg-hevc.mkv
# A profile 8.1 as releases have it: the record over an HDR10 stream.
python3 -I "$root/tools/dovi/add_dovi_config.py" hdr10-hevc.mkv dv-p8-hdr10.mkv --profile 8 --level 6
sha256sum hevc10.mkv dv-p5.mkv dv-p7.mkv dv-p8.mkv mpeg2.mkv hevc-level62.mkv av1-10bit.mkv rate-23976.mkv hdr10-hevc.mkv hlg-hevc.mkv dv-p8-hdr10.mkv
# The app's NoVideoGuardDeviceTest plays a copy of dv-p7.mkv; its FileDetailsDeviceTest copies of these four, and
# TvFileDetailsTest of hevc-level62.mkv.
if [ -z "${1:-}" ]; then cp dv-p7.mkv hevc-level62.mkv rate-23976.mkv av1-10bit.mkv "$root/app/src/androidTest/assets/"; fi
