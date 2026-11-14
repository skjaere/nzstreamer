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

## Titles and pictures

`app/src/androidTest/assets/screens/catalog.json` lists the screenshots' shows and films, with their seasons,
episodes and ids. Their names were made up for them and searched for on the web, and any that matched a real film,
show, book, game, brand or company was dropped. Their ids lie past the ranges TMDB, TheTVDB, TVmaze and IMDb use
today, so none of them is a real title's.

`art.py` draws a poster for each title, a backdrop for those marked `"backdrop"` and a still for each named episode,
as JPEGs beside the catalogue. Each one is a small landscape drawn from a seed taken from the title's slug and the
image's kind, so the same catalogue gives the same pictures; posters carry the title in Sora Bold, one of the app's
bundled fonts. After changing the catalogue, draw them again:

```sh
python3 -I tools/screens/art.py
python3 -I -B tools/screens/test_art.py
```

It needs Python 3 and Pillow (10 or newer). Another Pillow or libjpeg may draw a few pixels differently. The test
checks that a seed always draws the same bytes and that the assets hold exactly the images
the catalogue names.
