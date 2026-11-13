# NZStreamer

NZStreamer plays video from Usenet on Android phones, tablets and TVs. It streams straight from your own Usenet
provider, using NZBs from your own indexers: nothing is downloaded first. It comes with no providers, indexers or
content of its own.

## What it does

### Streaming
- Streams NZB releases over NNTP while they play, with yEnc decoding in native code.
- Plays files inside uncompressed RAR (4 and 5) and 7-Zip volumes, and finds obfuscated file names through PAR2.
- Several providers in your order: articles missing on one are fetched from the next.
- Searches your Newznab indexers together.
- Opens `.nzb` files from your device or from other apps, and asks for an archive's password when one needs it.
- Optional check of every article before a release starts.

### Finding what to play
- Title suggestions as you type, with your own TMDB API key; search by show, season and episode, or by movie.
  Without a key you can still search by name and open NZB files.
- Finds an episode inside a season pack when it has no release of its own. A pasted release name finds that release.
- Ranks releases for the device in your hand: its decoders, HDR and Dolby Vision support, the screen's resolution,
  your preferred quality, audio languages and maximum bitrate. Each release says why it ranked where it did.
- Tries the next release when one is incomplete or fails, up to a number you choose, or lets you choose one yourself.
- Voice search on TVs that have a speech recognizer.

### Library
- A collection of shows and movies, with filters, sorting and A–Z jump, and watched state per episode and movie.
- Up next on Home: resume, the next episode, new episodes.
- Posters and stills from TMDB, which you can turn off.

### Player
- Audio and subtitle choices remembered per show; preferred audio languages; subtitles when the audio isn't your
  first language; forced subtitles.
- Skip intro and recap from the file's chapters; optional intro and credits times from TheIntroDB (off by default).
- Next-episode card and autoplay; previous and next episode.
- File details: codecs, HDR and Dolby Vision profile, bitrate.
- Says when the stream is slower than the video needs, and asks what to do when a release turns out to miss parts.
- Phones: picture-in-picture, double-tap to seek, fit, fill and zoom.
- TVs: switches the TV to the video's frame rate (optional), and seeks faster while a remote key is held.

### Phones and TVs together
- Play on TV: pair your phone with your TV on your home network by matching numbers, start plays on the TV from the
  phone, and use the phone as its remote. Each device keeps its own accounts and keys.
- Set up with your phone: the TV shows a QR code, and you enter providers, indexers and the TMDB key in your phone's
  browser, over your home network.
- Google Cast: cast to Cast devices; the phone streams to the device over your home network, and releases are picked
  for what the device can play, with an optional maximum bitrate for casting. Cast stays off until you choose
  "Look for Cast devices". Subtitles don't show on a cast yet.

### Privacy
- No account, analytics, ads or server of its own. Passwords and keys are stored encrypted with the Android Keystore.
  Logs leave the device only when you share them.

### Not in every build
- Discover: TMDB's lists of shows and movies (trending, popular, top rated, airing, upcoming and more), filtered by
  genre and original language, and movies by year and streaming service. Debug, beta and bench (`-PbenchBuild=true`)
  builds have it; release builds leave it out.
- Debrid services: the code for playing through a debrid service (TorBox or Premiumize, with your own Torznab indexers
  in Prowlarr or Jackett) is in this repository. Beta builds hide it unless built with `-PwithDebrid=true`; other
  builds show it unless built with `-PhideDebrid=true`.

### Requirements
Android 8.0 (API 26) or later; phones, tablets and Android TV / Google TV; arm64-v8a, armeabi-v7a or x86_64.
English only for now.

## Install

Each release has one APK, `NZStreamer.apk`, for phones, tablets and TVs. This address always gets the newest release's:

```
https://github.com/skjaere/nzstreamer/releases/latest/download/NZStreamer.apk
```

It installs as NZStreamer Beta (`app.nzstreamer.beta`), the build every release is made from: Discover is in it, and
debrid is hidden. It needs Android 8.0 or newer.

### Phones and tablets
1. Open the address above in the phone's browser, or tap `NZStreamer.apk` on the latest release's page.
2. Open the download. The first time, Android sends you to Install unknown apps for the browser (or the files app you
   opened it from): allow it, go back, and tap Install.

### TVs
1. Install Downloader by AFTVnews: from Google Play on Android TV and Google TV, from the Amazon Appstore on Fire TV.
2. Open Downloader and type the address above into its text field. A short Downloader code for it may come later.
3. Let Downloader install unknown apps, then choose Install. On Android TV and Google TV, choose Settings in the message
   that comes up after the download, and set Downloader to Allowed. On Fire TV, allow it before the download, under
   Settings, My Fire TV, Developer Options, Install unknown apps.

If that setting isn't there, turn on the TV's developer options first. On Google TV: Settings, System, About, then
select Android TV OS build until it says you are a developer. On Fire TV: Settings, My Fire TV, About, then select the
first item, the device's name, about seven times; Developer Options shows one menu back.

### Staying up to date
Obtainium installs apps from their releases and keeps them up to date. Get it from its GitHub releases, F-Droid or
IzzyOnDroid. It runs on TVs too, where you install it with Downloader.

1. In Obtainium, add an app with this repository's address, `https://github.com/skjaere/nzstreamer`.
2. Install NZStreamer from Obtainium, not from the browser.

Obtainium then checks for new releases in the background. On Android 12 and newer it installs them in the background
too, as long as it installed the version you have and its background updates are on. On older Android it tells you
about a new release, and you confirm the install. This goes for TV boxes as well.

### Checking the APK
Every release is signed with one key, whose certificate's SHA-256 digest is in
[`tools/beta-cert.sha256`](tools/beta-cert.sha256). On a computer with the Android SDK's build-tools, in the folder
that holds `NZStreamer.apk` and the release's `NZStreamer.apk.sha256`:

```sh
sha256sum -c NZStreamer.apk.sha256
apksigner verify --print-certs NZStreamer.apk
```

The first says the download is whole (on macOS, `shasum -a 256 -c NZStreamer.apk.sha256`). In what the second prints,
each line with `certificate SHA-256 digest:` in it must end in the digest that `tools/beta-cert.sha256` holds. Newer
build-tools print one such line for each signature scheme; a second signer would show a second digest.

On the phone, AppVerifier (from Accrescent or its GitHub releases) shows an installed app's package name and signing
certificate digest: they must be `app.nzstreamer.beta` and the digest in `tools/beta-cert.sha256`. AppVerifier writes
the digest in capitals, with a colon between each pair of digits.

## Building from source

You need:
- JDK 17 as `JAVA_HOME`. Gradle 8.14 runs on JDK 17 to 24, not on 25.
- The Android SDK as `ANDROID_HOME`, with these packages:
  ```sh
  sdkmanager "platforms;android-36" "build-tools;35.0.0" "ndk;27.1.12297006" "cmake;3.22.1"
  ```
  The checks read the release dex with build-tools' `dexdump`.
- The rapidyenc submodule (`third_party/rapidyenc`), which the NDK builds into the app.

```sh
git clone --recurse-submodules https://github.com/skjaere/nzstreamer.git
cd nzstreamer
./gradlew :app:assembleDebug
```

In a clone made without `--recurse-submodules`, run `git submodule update --init` first. The debug APK lands in
`app/build/outputs/apk/debug/`. No `local.properties` is needed.

### The usenet libraries
The app is built on four libraries:
[nzb-streamer](https://github.com/skjaere/nzb-streamer),
[ktor-nntp-client](https://github.com/skjaere/ktor-nntp-client),
[rapidyenc-kotlin-wrapper](https://github.com/skjaere/rapidyenc-kotlin-wrapper) and
[kotlin-compression-utils](https://github.com/skjaere/kotlin-compression-utils).
By default Gradle takes their releases from JitPack, at the versions pinned in `gradle/libs.versions.toml`. JitPack
builds a version the first time anyone asks for it, so a first build can wait a few minutes.

To build against your own checkouts of them instead, name the folder that holds the four:

```sh
./gradlew -PusenetLibsDir=<folder> :app:assembleDebug
```

The folder holds `nzb-streamer`, `ktor-nntp-client`, `kotlin-compression-utils` and the wrapper, named
`rapidyenc-kotlin-wrapper` or `yenc_kotlin_wrapper` (with both there, the first is used, as `ktor-nntp-client`'s own
build uses it). The build fails when one of them is missing or holds no Gradle build.

### A mirror of Google's Maven repository
If `dl.google.com` can't be reached from your network, set a mirror in `~/.gradle/gradle.properties`:

```
googleMavenMirror=<mirror URL>
```

The mirror then replaces Google's repository. Gradle stops resolving at a repository that times out, so the two
can't be combined.

### Debug builds and your own accounts
On its first start, a debug build saves the mock NNTP server below as its provider (`10.0.2.2:1119`, TLS off). To
start one with your own accounts instead, add them to `local.properties`, which git ignores:

```
devProvider.host=news.example.com
devProvider.port=563
devProvider.username=...
devProvider.password=...
devProvider.tls=true
devProvider.connections=8
devIndexer.1.url=https://indexer.example/api
devIndexer.1.apikey=...
devTmdbKey=...
```

The comment at the top of `app/build.gradle.kts` lists every such setting. They are compiled into the debug APK, never
into a release build, so don't share a debug APK built with them.

## Tests

```sh
./gradlew check
```

`check` runs the unit tests and lint, and these checks of the built app, of the release build unless said otherwise:
- `:app:checkMinApi`: no io.skjaere code in the release dex uses a JDK API that minSdk devices lack. Known safe uses
  go in `app/min-api-allowlist.txt`.
- `:app:checkMinApiDiscover`: the same for Discover's code, read from the debug build's dex, as the release build
  leaves Discover out.
- `:app:checkNativeLibs` and `:app:checkBundleNativeLibs`: the APK and the App Bundle carry a native library for
  each of the three ABIs, with no debug sections, and 64-bit ones with 16 KB alignment.
- `:app:checkTvManifest`: the merged manifest keeps the app on TVs and in the TV launcher.
- `:app:checkNoDiscover`: the release build carries no Discover code, words or resources.
- `:app:checkCastStartsLate`: nothing in the merged manifests starts Google's Cast software without the app's gate.
- Lint's TV checks are errors. `app/lint-baseline.xml` holds the issues there were when they were turned on, so
  only new ones fail.

Each unit test has 2 minutes and each test task 15 minutes, in every module (the root `build.gradle.kts` sets both). A
test that hangs fails with its name and its thread's stack, and the tests after it still run. For a test in
`runBlocking`, that stack ends at its `runBlocking` line, not at the call the coroutine waits in. The test's limit is off
while a debugger is attached. The runs against a device, a TV or an account that a `-P` property turns on
(`serveSpike`, `serveProbe`, `debridLive` and `linkPeer`) have neither limit: they take as long as they need.

### Device tests
Instrumented tests run on an emulator or a device: `./gradlew :app:connectedDebugAndroidTest`. The tests that stream
need the mock NNTP server, reachable from the emulator at `10.0.2.2:1119`, with test media seeded into it by
`tools/mock-seed`. The tests are written against [mock-nntp-server](https://github.com/skjaere/mock-nntp-server) 0.2.1,
the release built on the same library releases as the app's pins; its image carries that version as its tag. From the
repository's top folder:

```sh
docker run -d --rm --name mock-nntp -p 1119:1119 -p 8081:8081 ghcr.io/skjaere/mock-nntp-server:0.2.1
mkdir -p build/smoke
ffmpeg -f lavfi -i testsrc=duration=90:size=1280x720:rate=30 -f lavfi -i sine=frequency=440:duration=90 \
  -c:v libx264 -profile:v high -pix_fmt yuv420p -b:v 1M -c:a aac -shortest build/sample.mkv
./gradlew :tools:mock-seed:run --args="seed --video build/sample.mkv --out build/sample.nzb"
./gradlew :tools:mock-seed:run --args="verify --nzb build/sample.nzb --video build/sample.mkv"
cp build/sample.nzb build/sample.mkv build/smoke/
./gradlew :app:connectedDebugAndroidTest
```

The end-to-end smoke test (`EngineSmokeTest`) streams the clip through the `.nzb` that the build packs from
`build/smoke/`. Without those two files it is skipped, not run.

Article ids come from the file's contents, so seeding the same file again after a restart of the mock keeps its
`.nzb` valid. The fixtures under `app/src/androidTest/` are seeded the same way, for example
`./gradlew :tools:mock-seed:run --args="seed --video app/src/androidTest/fixtures/chapters.mkv --out build/chapters.nzb --segment 100000"`.

The fake indexer seeds an invented show and movie into the mock and serves them as a Newznab indexer; it prints its
address and API key. It needs a second clip too, a copy with E-AC-3 audio that one of its releases plays. Make it once:

```sh
ffmpeg -i build/sample.mkv -c:v copy -c:a eac3 build/sample-eac3.mkv
./gradlew :tools:mock-seed:run --args="indexer"
```

The other commands are listed in `tools/mock-seed/src/main/kotlin/io/skjaere/usenet/mockseed/Main.kt`.

## Licence

NZStreamer's own code is under the [PolyForm Noncommercial License 1.0.0](LICENSE.md) (SPDX
`PolyForm-Noncommercial-1.0.0`): free for any noncommercial use; commercial use needs the licensor's permission.
Fonts, icons and other third-party material keep their own licences: see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Required Notice: Copyright 2026 skjaere (https://github.com/skjaere)

Show and movie data, posters and stills come from [TMDB](https://www.themoviedb.org), with your own API key. This
product uses the TMDB API but is not endorsed or certified by TMDB.

Intro and credits times, when you turn them on, come from [TheIntroDB](https://theintrodb.org).

## Contributing

Pull requests are welcome once you have signed the [contributor licence agreement](CLA.md): see
[CONTRIBUTING.md](CONTRIBUTING.md).
