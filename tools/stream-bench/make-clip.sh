#!/bin/bash
# The stream bench's synthetic high-bitrate release: 90 s of 1080p24 H.264 noise at a constant 76 Mbps (78 Mbps with
# the container and audio), AAC 5.1 at 384 kbps, muxed by ffmpeg with the Cues at the end. About 860 MB.
# Usage: make-clip.sh <output.mkv>
set -euo pipefail
out=${1:?output mkv}
ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i "color=c=gray:s=1920x1080:r=24:d=90,noise=alls=50:allf=t+u,format=yuv420p" \
  -f lavfi -i "aevalsrc=0.1*sin(2*PI*440*t)|0.1*sin(2*PI*550*t)|0.1*sin(2*PI*660*t)|0.05*sin(2*PI*60*t)|0.1*sin(2*PI*770*t)|0.1*sin(2*PI*880*t):s=48000:c=5.1:d=90" \
  -c:v libx264 -preset ultrafast -g 48 -b:v 76M -minrate 76M -maxrate 76M -bufsize 38M -x264-params nal-hrd=cbr -level 5.1 \
  -c:a aac -b:a 384k -shortest "$out"
