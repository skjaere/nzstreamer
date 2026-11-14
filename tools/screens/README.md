# README screenshots

The tools here stage what the README's screenshots show. Every title, poster and release in them is invented for
them, and nothing comes from TMDB.

## The player's clip

`make-clip.sh` makes the video the player shows from a silent film in the public domain: a short of the 1890s whose
director is unknown, free in the US, where it was published before 1931, and in the EU, where an anonymous work's term
ends 70 years after it was made public. The copy is one the archive holding the film publishes and marks as public
domain, with no condition on its use; never a restoration sold on disc or a copy taken from a video site, which a
restorer or distributor may claim rights in. Only its picture is used. The script scales it to 1080 lines, loops it to
a little over five and a half minutes, pillarboxes it to 1920x1080 and adds three silent audio tracks, three subtitle
tracks with lines written for it, and an "Intro" chapter, so the skip button and the audio and subtitles panel have
something to show.

```sh
tools/screens/make-clip.sh <film> <out dir> [--from <seconds>] [--to <seconds>] [--crop <w:h:x:y>]
```

`--from` and `--to` keep the part of the copy between them, for one with a title card, black frames or a fade at
either end. `--crop` keeps one rectangle of its frame, `width:height:x:y` in its pixels as ffmpeg's crop filter takes
it, for a copy with black bars or a border around its picture (ffmpeg's cropdetect finds them); it is cut before the
picture is scaled. The clip is about 110 MB and stays out of the repository, as does the film. It needs ffmpeg and
ffprobe, and the same film and ffmpeg give the same bytes.

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

It needs Python 3 and Pillow (10 or newer). The test checks that a seed always draws the same bytes, that art.py's
JPEGs carry no metadata, that the assets hold exactly the images the catalogue names, and that each of them is the
picture art.py draws for it. Another Pillow or libjpeg may draw a few pixels differently, which the test allows.

## Releases and lookups

mock-seed serves the catalogue's releases as a Newznab indexer, with the mock NNTP server running (see the main
README's device tests):

```sh
./gradlew :tools:mock-seed:run --args="indexer --catalog screens --video <clip> --port <port>"
```

Give the clip's full path, as Gradle runs the tool from the repository's top folder, and a port of 8093 or above: 8089
is the device tests' fake indexer's. The port is taken before the clip is seeded, so one in use fails at once. Every
release lists a size of its own. Only the two the screenshots play (`ScreensCatalog.PLAYABLE`) carry the clip; the
others are seeded with every article missing, so a play of one fails at once rather than show the clip under another
release's name.

On the device, `ScreensTitles` reads the same catalogue and builds a strict `FakeTmdb` from it: searches are answered
from the catalogue alone, a lookup of any other title, season or key fails the run, and nothing goes to TMDB. `ScaleImages.screens` answers the pictures'
paths with the images above, and fails the run on a path it has no image for. Both live in `app/src/androidTest`.

## Taking the screenshots

`ReadmeScreensTest` (`app/src/androidTest`, in the `screens` package) stages each screen over the catalogue and
saves it, as `screencap` takes it, with every word on it in a text file beside it. It runs only with the
instrumentation argument `screens=true`, so other device runs skip it. Before anything shows, it fails if the app
holds a provider or indexer on any host but the emulator's, or a debrid account, and it saves an invented TMDB key
that TMDB would refuse. After each screen it puts back what it changed: the settings and the key, and in the library
the titles it collected, the watch rows of the shows it marked or played, its searches, the releases it tried and the
track choice. A collected title leaves what the app keeps of any title taken out of the collection: its tombstone, and
TMDB's facts until the app prunes them. It fails if a title or picture was asked for that the catalogue doesn't have.
`ScreensStageTest`, which `capture.sh` runs first, checks that putting back, also after a stage fails part way.

`capture.sh` runs it on an emulator, with the mock NNTP server running:

```sh
tools/screens/capture.sh phone --clip <clip.mkv> --out <dir>
tools/screens/capture.sh tv --clip <clip.mkv> --out <dir>
```

The phone's screens are taken on the emulator whose AVD is named `phone34`, the TV's on the one named `tv36`
(Android 12 or later); no other device is used. The script starts its own screens indexer on a free port from 8093
and stops it after. On the emulator it clears the app's data, sets the device's name and, on a phone, the status
bar's demo mode, and puts both back when it ends, also after a failure. It runs the bench build
(`-PbenchBuild=true -PhideDebrid=true`), which has no developer settings, and pulls the screens into `<dir>`.

The main README's images, in `.github/readme/`, are these screens saved again by `webp.py` as lossless WebP from their
pixels alone, so they carry no metadata, under the names the README shows them by (`p1-home.png` is `phone-home.webp`):

```sh
python3 -I tools/screens/webp.py <phone's --out> <TV's --out> [--only p4-player t3-player]
python3 -I -B tools/screens/test_webp.py
```

The test checks that each image holds its pixels alone. Every word on them was read in the text files against the
catalogue's names.
