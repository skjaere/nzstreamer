# Stream bench

What streaming a high-bitrate release costs the app, for before and after numbers when the stream path changes:
the CPU per MB streamed by thread group, what is allocated per MB, ART's GCs, and the throughput. It runs on an
emulator, which is x86_64 and not a TV box, so its numbers are for comparing builds and changes. A real device's
come from the stream-cost line in its log (tag `StreamCost`), with Detailed logging off.

- `StreamBenchDeviceTest` (app's instrumented tests) streams the release through the app's own stream path: the
  engine, `UsenetStreamReader` and `UsenetDataSource`. `stream` reads the data source alone as fast as it goes (a
  700 MB window after 100 MB); `play` has `PlayerActivity` play it (50 s after 15 s of play). Each logs one
  `result {json}` line under the tag `StreamBench`, figured by the stream-cost line's sampler.
- `nntp_disk_server.py` (Python 3, standard library) serves the articles from disk, plain and over TLS 1.2 with one
  CBC suite (the one measured since the bench's first runs, so runs compare), or with `--tls-version 1.3` over TLS
  1.3 alone, as a provider's server most likely speaks it, on a self-signed certificate it makes with `openssl`
  (`--make-cert`). `bench.sh --tls-version 1.3` serves TLS so, and each TLS run's result says which. The shared mock
  NNTP server would have to hold the 860 MB of articles in its heap.
- `make-clip.sh` (ffmpeg) makes the release: 90 s of 1080p H.264 noise at a constant 76 Mbps with AAC 5.1, about
  860 MB.
- `bench.sh` starts the server, packages the NZB, runs each mode on each build over plain NNTP and TLS for some
  rounds (3), each run a process of its own, and stops the server. `summarize.py` prints each figure's median over
  the rounds with the lowest and highest beside it.

A run is also not valid when it used an article whose CRC didn't match its =yend line: nzb-streamer uses a damaged
copy rather than none and only logs a warning, so the result counts them (`crc_mismatches_used`, "damaged articles
used" in the summary), and a damaged byte anywhere on the way fails the run instead of passing unseen.

The test is skipped unless the NZB is packaged (`build/stream-bench/stream-bench.nzb`, which `bench.sh` copies
there and removes again) and the server is reachable at `10.0.2.2`, so the normal device suites never run it. Run
it by itself, with `bench.sh`.

## Builds

`debug` is what the instrumented tests build anyway. `-PbenchBuild=true` adds a `bench` build type and runs the
instrumented tests against it: a release build (not debuggable, rapidyenc's native code optimised, no dev provider
or keys) signed with the debug key, so the tests can drive it. A debuggable app runs with CheckJNI on every JNA
call, its JIT keeps code debuggable, and debug builds compile rapidyenc without optimisation, so the two differ.

## Seeding, once

```sh
tools/stream-bench/make-clip.sh build/bench.mkv
python3 -I tools/stream-bench/nntp_disk_server.py --dir build/bench-articles --nntp 11119 --http 18081 &
./gradlew :tools:mock-seed:run --args="seed --video $PWD/build/bench.mkv --out $PWD/build/bench.nzb --mock http://127.0.0.1:18081 --segment 3840000"
kill %1
```

The articles are named by their message ids' SHA-256 (`article_path`) and are about as large as the clip.

## A release with many subtitle tracks

What subtitles cost a play: `make-pgs-clip.sh` copies the first 80 s of the clip and adds 40 PGS tracks (a cue of
1.5 s every 2.5 s each, a quarter-screen bitmap of made-up text, written by `tools/pgs/make_sup.py`, as ffmpeg can't
encode PGS) and an English SRT track, about 900 MB. None is flagged default or forced, so a play shows none of them.
Seed it into an articles directory of its own and bench it in play mode, where Media3's loader thread does the
subtitles' work:

```sh
tools/stream-bench/make-pgs-clip.sh build/bench.mkv build/bench-pgs.mkv
python3 -I tools/stream-bench/nntp_disk_server.py --dir build/bench-pgs-articles --nntp 11119 --http 18081 &
./gradlew :tools:mock-seed:run --args="seed --video $PWD/build/bench-pgs.mkv --out $PWD/build/bench-pgs.nzb --mock http://127.0.0.1:18081 --segment 3840000"
kill %1
ANDROID_SERIAL=emulator-5554 tools/stream-bench/bench.sh --articles build/bench-pgs-articles --nzb build/bench-pgs.nzb --modes play --transports plain
```

## Running

```sh
ANDROID_SERIAL=emulator-5554 tools/stream-bench/bench.sh --articles build/bench-articles --nzb build/bench.nzb
ANDROID_SERIAL=emulator-5554 tools/stream-bench/bench.sh --articles build/bench-articles --nzb build/bench.nzb --builds bench --transports tls --modes stream --rounds 5
```

By default each run is `./gradlew :app:connected<Build>AndroidTest` on the one emulator `ANDROID_SERIAL` names,
which adb must list as ready; without it `bench.sh` runs nothing. The connected tests would otherwise install on
every device adb lists, a phone or a TV box on network adb too, and uninstall the app and its data from each after;
and the test connects to `10.0.2.2`, which only an emulator reaches. `--runner` takes a command of your own, which
picks the device itself. Results go to `build/stream-bench-runs/<time>/`: `results.jsonl`, a line for each run
(with the serial it ran on, by default), and `summary.txt`. The test's arguments
(`streambench_mb`, `streambench_seconds` and others, in its KDoc) go after `--` as
`-Pandroid.testInstrumentationRunnerArguments.streambench_mb=300`.

A MB is 1,000,000 bytes, as the stream-cost line counts it. The per-MB CPU of a thread group is its CPU over the
window divided by the MB streamed in it; `rest` is the process's CPU beyond the other groups', threads that ended
within the window included.

## Tests

```sh
python3 -B tools/stream-bench/test_nntp_disk_server.py
python3 -B tools/stream-bench/test_summarize.py
python3 -B tools/stream-bench/test_bench.py
python3 -B tools/pgs/test_make_sup.py
```
