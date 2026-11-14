# README screenshots

The tools here stage what the README's screenshots show. Every title, poster and release in them is invented for
them, and nothing comes from TMDB.

## The player's clip

`make-clip.sh` makes the video the player shows from a silent film in the public domain: an actualité of the 1890s,
free in the US and wherever the term is the author's life plus 70 years. Only its picture is used. The script loops
it to a little over five and a half minutes, pillarboxes it to 1920x1080 and adds three silent audio tracks, three
subtitle tracks with lines written for it, and an "Intro" chapter, so the skip button and the audio and subtitles
panel have something to show.

```sh
tools/screens/make-clip.sh <film> <out dir> [--to <seconds>]
```

`--to` cuts the film there, for one that ends on black frames. The clip is about 130 MB and stays out of the
repository, as does the film. It needs ffmpeg and ffprobe, and the same film and ffmpeg give the same bytes.
