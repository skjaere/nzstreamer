# Third-party notices

This repository's own code and files are under the PolyForm Noncommercial License 1.0.0 (see [LICENSE.md](LICENSE.md)). The material below is not: it keeps its own licence, and LICENSE.md does not cover it. Neither file grants any right in the NZStreamer name and logo, or in the other names and marks listed at the end.

## IBM Plex Sans and IBM Plex Mono

- What: the fonts `app/src/main/res/font/ibm_plex_sans.ttf` (IBM Plex Sans 3.201, variable) and `app/src/main/res/font/ibm_plex_mono.ttf` (IBM Plex Mono 2.3, Regular), unmodified.
- Where from: <https://github.com/IBM/plex>.
- Copyright © 2017 IBM Corp. with Reserved Font Name "Plex". IBM Plex is a trademark of IBM Corp.
- Licence: SIL Open Font License 1.1 (SPDX `OFL-1.1`). The full text is in [app/src/main/assets/licenses/ibm-plex-OFL.txt](app/src/main/assets/licenses/ibm-plex-OFL.txt), which the app ships.

## Sora

- What: the font `app/src/main/res/font/sora.ttf` (Sora 2.000, variable), unmodified. `tools/brand/generate.py` draws the TV banner's wordmark with it, and `tools/screens/art.py` the titles on the README screenshots' posters (`app/src/androidTest/assets/screens/`); those images themselves are this project's.
- Where from: <https://github.com/sora-xor/sora-font>.
- Copyright 2019 The Sora Project Authors.
- Licence: SIL Open Font License 1.1 (SPDX `OFL-1.1`). The full text is in [app/src/main/assets/licenses/sora-OFL.txt](app/src/main/assets/licenses/sora-OFL.txt), which the app ships.

## Material Design icons

- What: path data of Google's Material icons, some scaled:
  - `app/src/main/kotlin/io/skjaere/usenet/app/link/LinkIcons.kt`: tv (outlined), smartphone (outlined), speaker, pause, stop, volume_up, subtitles, cast and cast_connected (see also "Google Cast icon").
  - `app/src/main/kotlin/io/skjaere/usenet/app/search/VoiceSearch.kt`: mic.
  - `app/src/discover/kotlin/io/skjaere/usenet/app/discover/DiscoverExtras.kt`: explore.
  - `app/src/main/res/drawable/ic_stop_casting.xml` (close), `app/src/main/res/drawable/ic_window_play.xml` (play_arrow), `app/src/main/res/drawable/ic_window_pause.xml` (pause), and the "10" in `app/src/main/res/drawable/ic_window_rewind.xml` and `app/src/main/res/drawable/ic_window_forward.xml` (from replay_10 and forward_10, scaled).
- Where from: <https://github.com/google/material-design-icons>.
- Copyright Google LLC.
- Licence: Apache License 2.0 (SPDX `Apache-2.0`). The full text is in [LICENSES/Apache-2.0.txt](LICENSES/Apache-2.0.txt).

## Google Cast icon

- What: the Cast button's icon at rest, connecting and connected (`CAST_WAVES`, `CAST_FRAME`, `CAST` and `CAST_CONNECTED` in `app/src/main/kotlin/io/skjaere/usenet/app/link/LinkIcons.kt`). Its shapes are Material's cast and cast_connected, above, point for point.
- The Cast icon is a Google brand feature. The app shows it as the Google Cast SDK Additional Developer Terms and Google's Cast design checklist require. This repository grants no right in it.

## Media3 PGS decoding (test helper)

- What: `decode()` in `tools/pgs/test_make_sup.py`, the run-length loop of Media3's `PgsParser`, translated to Python. Only that test uses it.
- Where from: <https://github.com/androidx/media>, the class `androidx.media3.extractor.text.pgs.PgsParser`.
- Copyright (C) 2017 The Android Open Source Project. Changed: translated to Python.
- Licence: Apache License 2.0 (SPDX `Apache-2.0`). The full text is in [LICENSES/Apache-2.0.txt](LICENSES/Apache-2.0.txt).

## Gradle Wrapper

- What: `gradlew`, `gradlew.bat` and `gradle/wrapper/gradle-wrapper.jar` (Gradle 8.14.3).
- Where from: <https://gradle.org>.
- Copyright © 2015-2021 the original authors.
- Licence: Apache License 2.0 (SPDX `Apache-2.0`). The full text is in [LICENSES/Apache-2.0.txt](LICENSES/Apache-2.0.txt).

## ZXing's licence and notice

- What: `app/src/main/assets/licenses/zxing-LICENSE.txt` and `app/src/main/assets/licenses/zxing-NOTICE.txt`, ZXing's own LICENSE and NOTICE files, unchanged. The app ships them because it uses ZXing (`com.google.zxing:core`), a build dependency that is not in this repository.
- Licence: Apache License 2.0 (SPDX `Apache-2.0`), and for ZXing's jai-imageio parts a BSD-style licence with its own copyright holders, which `zxing-LICENSE.txt` gives after the Apache text.

## rapidyenc (git submodule)

- What: `third_party/rapidyenc` is a git submodule pointing at rapidyenc's own repository; its files are not part of this repository. `native/rapidyenc` builds it into `librapidyenc.so`, which the app ships.
- Where from: <https://github.com/animetosho/rapidyenc>, by animetosho.
- Licence: public domain, or CC0 1.0 where public domain isn't recognised (SPDX `CC0-1.0`, <https://creativecommons.org/publicdomain/zero/1.0/legalcode>).

rapidyenc includes, and the library is built with:

- crcutil 1.0 (`third_party/rapidyenc/crcutil-1.0/`): Copyright 2010 Google Inc. Apache License 2.0 (SPDX `Apache-2.0`); the full text is in [LICENSES/Apache-2.0.txt](LICENSES/Apache-2.0.txt).
- zlib-ng's PCLMUL CRC32 folding (`third_party/rapidyenc/src/crc_folding.cc`, `third_party/rapidyenc/src/crc_folding_256.cc`), which zlib-ng has from Intel's zlib patch: Copyright (C) 2013 Intel Corporation. The zlib licence (SPDX `Zlib`), as zlib-ng's `LICENSE.md` gives it:

```text
(C) 1995-2024 Jean-loup Gailly and Mark Adler

This software is provided 'as-is', without any express or implied
warranty. In no event will the authors be held liable for any damages
arising from the use of this software.

Permission is granted to anyone to use this software for any purpose,
including commercial applications, and to alter it and redistribute it
freely, subject to the following restrictions:

1. The origin of this software must not be misrepresented; you must not
   claim that you wrote the original software. If you use this software
   in a product, an acknowledgment in the product documentation would be
   appreciated but is not required.

2. Altered source versions must be plainly marked as such, and must not be
   misrepresented as being the original software.

3. This notice may not be removed or altered from any source distribution.
```

- Hedley (`third_party/rapidyenc/src/hedley.h`): CC0 1.0 (SPDX `CC0-1.0`), <https://nemequ.github.io/hedley>.

## Names and marks

NZStreamer's name and logo belong to this project. PolyForm's terms "do not imply any other licenses", so forks need a name and logo of their own. Google Cast and Chromecast are trademarks of Google LLC, and IBM Plex of IBM Corp. TMDB, JustWatch and TheIntroDB are named only to credit their data. This product uses the TMDB API but is not endorsed or certified by TMDB.
