#!/bin/bash
# The stream bench (StreamBenchDeviceTest): starts the disk NNTP server, packages the release's NZB, runs the test's
# modes over plain NNTP and TLS on the debug and bench builds for some rounds, and stops the server. Each run is an
# instrumented run of its own (a fresh process). The configurations take turns, round by round, so a host that gets
# busier for a while slows them all alike. Each run's "result {json}" line goes to OUT/results.jsonl (only that line of
# the device's log), and summarize.py's table of medians and ranges to OUT/summary.txt.
#
# Usage, from the repository root:
#   tools/stream-bench/bench.sh --articles DIR --nzb FILE [--out DIR] [--rounds 3] [--builds "debug bench"]
#     [--transports "plain tls"] [--modes "stream play"] [--tls-version 1.2|1.3] [--ports "NNTP TLS HTTP"] [--runner CMD]
#     [--trees "DIR..."] [-- test arguments]
#   --articles  the seeded articles (nntp_disk_server.py --dir); --nzb  their NZB, as tools/mock-seed wrote it
#   --tls-version  what the server's TLS speaks: 1.2 with its one CBC suite (the default, as the bench always measured),
#               or 1.3 alone, as a provider's server most likely does; each TLS run's result says which
#   --ports     the server's, on 127.0.0.1, which an emulator reaches as 10.0.2.2 (default "11119 11563 18081")
#   --trees     the git work trees whose code a run measures (default this repository; the libraries' too when the
#               build uses checkouts of them): each result names each one's tree as of its run, its uncommitted changes
#               and new files included and build output left out, the id a snapshot of the work tree gives
#   --runner    runs one test: CMD <build> <class#method> [gradle arguments...], on a device it picks. Without it,
#               ./gradlew's :app:connected<Build>AndroidTest on the one emulator ANDROID_SERIAL names (emulator-NNNN),
#               which adb must list ready: those tests would install on every device adb lists, a phone or a TV box
#               too, and uninstall the app and its data from each after. Without such a serial nothing runs.
#   after --     more gradle arguments for every run, such as -Pandroid.testInstrumentationRunnerArguments.streambench_mb=300
# The last line is "RESULT: PASSED" when every run passed with a valid result, else "RESULT: FAILED ..."; the exit status
# says the same. A run whose window the test found not valid (a stall, a stop, the release's end) fails, saying why.
set -euo pipefail
CLASS=io.skjaere.usenet.app.StreamBenchDeviceTest
ARG=-Pandroid.testInstrumentationRunnerArguments.streambench
here=$(cd "$(dirname "$0")" && pwd)
articles="" nzb="" out="" rounds=3 builds="debug bench" transports="plain tls" modes="stream play" ports="11119 11563 18081" runner=""
tls_version=1.2 trees=.
extra=()
while [ $# -gt 0 ]; do
  case "$1" in
    --articles) articles=$2; shift 2 ;;
    --nzb) nzb=$2; shift 2 ;;
    --out) out=$2; shift 2 ;;
    --rounds) rounds=$2; shift 2 ;;
    --builds) builds=$2; shift 2 ;;
    --transports) transports=$2; shift 2 ;;
    --modes) modes=$2; shift 2 ;;
    --ports) ports=$2; shift 2 ;;
    --tls-version) tls_version=$2; shift 2 ;;
    --trees) trees=$2; shift 2 ;;
    --runner) runner=$2; shift 2 ;;
    --) shift; extra=("$@"); break ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done
[ -d "$articles" ] && [ -f "$nzb" ] || { echo "bench: --articles DIR and --nzb FILE are needed" >&2; exit 2; }
[ -f settings.gradle.kts ] && [ -d app ] || { echo "bench: run it from the repository root" >&2; exit 2; }
serial=""
if [ -z "$runner" ]; then
  serial=${ANDROID_SERIAL:-}
  [[ $serial =~ ^emulator-[0-9]+$ ]] || {
    echo "bench: set ANDROID_SERIAL to the one emulator to run on (emulator-NNNN), or pass --runner: the connected tests" \
      "run on every device adb lists, and uninstall the app and its data from each after" >&2
    exit 2
  }
  adb devices | awk -v s="$serial" '$1 == s && $2 == "device" { found = 1 } END { exit !found }' ||
    { echo "bench: adb lists no emulator $serial ready (adb devices)" >&2; exit 2; }
  export ANDROID_SERIAL=$serial
  echo "bench: the runs go to $serial" >&2
fi
out=${out:-build/stream-bench-runs/$(date +%Y%m%d-%H%M%S)}
mkdir -p "$out/runs"
read -r nntp_port tls_port http_port <<< "$ports"
for p in $nntp_port $tls_port $http_port; do
  [ -z "$(ss -ltnH "( sport = :$p )")" ] || { echo "bench: port $p is in use; choose others with --ports" >&2; exit 2; }
done
echo "bench: $(free -g | awk '/^Mem:/ {print $7}') GB of memory available" >&2

# The tree [repo] holds as a git tree id: what it has now, committed or not, with build output left out, written to a
# copy of its index so nothing it has staged changes. "-" for a directory that isn't a git work tree.
tree_id() {
  local repo=$1 index p
  git -C "$repo" rev-parse --is-inside-work-tree > /dev/null 2>&1 || { echo -; return; }
  index=$(mktemp)
  cp "$(git -C "$repo" rev-parse --path-format=absolute --git-path index)" "$index" 2>/dev/null || : > "$index"
  local excludes=()
  for p in build .gradle .idea .kotlin local.properties third_party/rapidyenc; do
    git -C "$repo" check-ignore -q "$p" || excludes+=(":!$p")
  done
  excludes+=(':!**/build/**' ':!**/.gradle/**' ':!**/.kotlin/**')
  GIT_INDEX_FILE=$index git -C "$repo" add -A -- . "${excludes[@]}" 2> /dev/null
  GIT_INDEX_FILE=$index git -C "$repo" write-tree 2> /dev/null || echo -
  rm -f "$index"
}

# Each of [trees] and its tree now, as a JSON object.
trees_now() {
  local repo pairs=()
  for repo in $trees; do pairs+=("$repo" "$(tree_id "$repo")"); done
  python3 -I -c 'import json, sys; a = sys.argv[1:]; print(json.dumps(dict(zip(a[::2], a[1::2]))))' "${pairs[@]}"
}

gradle_runner() {
  local build=$1 test=$2; shift 2
  ./gradlew ":app:connected${build^}AndroidTest" "-Pandroid.testInstrumentationRunnerArguments.class=$test" "$@"
}

# The server, with a certificate of its own for 10.0.2.2 made for the run; the test trusts it by its fingerprint.
certs=$(mktemp -d "$out/cert-XXXX")
python3 -I "$here/nntp_disk_server.py" --dir "$articles" --nntp "$nntp_port" --tls "$tls_port" --http "$http_port" \
  --tls-version "$tls_version" --make-cert "$certs" --quiet > "$out/server.log" 2>&1 &
server=$!
stop() {
  kill "$server" 2>/dev/null || true
  wait "$server" 2>/dev/null || true
  rm -f "$certs/bench-key.pem" build/stream-bench/stream-bench.nzb
}
trap stop EXIT
trap 'exit 130' INT TERM HUP
sha=""
for _ in $(seq 100); do
  sha=$(sed -n 's/^certificate .* sha256 \([0-9a-f]*\)$/\1/p' "$out/server.log")
  [ -n "$sha" ] && break
  kill -0 "$server" 2>/dev/null || break
  sleep 0.2
done
[ -n "$sha" ] || { cat "$out/server.log" >&2; echo "RESULT: FAILED (the server did not start)"; exit 1; }
echo "bench: $(head -1 "$out/server.log")" >&2

# The NZB goes into the instrumented tests' assets (app/build.gradle.kts' build/stream-bench).
mkdir -p build/stream-bench
cp "$nzb" build/stream-bench/stream-bench.nzb

total=$((rounds * $(wc -w <<< "$builds") * $(wc -w <<< "$transports") * $(wc -w <<< "$modes")))
n=0 failed=0
for round in $(seq "$rounds"); do
  for build in $builds; do
    for transport in $transports; do
      for mode in $modes; do
        n=$((n + 1))
        name="r$round-$build-$transport-$mode"
        args=("$ARG"_tls=$([ "$transport" = tls ] && echo true || echo false))
        if [ "$transport" = tls ]; then args+=("$ARG"_port="$tls_port" "$ARG"_cert_sha256="$sha"); else args+=("$ARG"_port="$nntp_port"); fi
        [ "$build" = debug ] || args+=(-PbenchBuild=true)
        marker="$out/runs/.$name.start"
        : > "$marker"
        status=ok
        if [ -n "$runner" ]; then
          $runner "$build" "$CLASS#$mode" "${args[@]}" "${extra[@]}" > "$out/runs/$name.log" 2>&1 || status=failed
        else
          gradle_runner "$build" "$CLASS#$mode" "${args[@]}" "${extra[@]}" > "$out/runs/$name.log" 2>&1 || status=failed
        fi
        # The run's log of the device, as the Gradle plugin keeps it: only its result line is kept.
        log=$(find "app/build/outputs/androidTest-results/connected/$build" -name "logcat-$CLASS-$mode.txt" -newer "$marker" 2>/dev/null | head -1)
        line=""
        [ -n "$log" ] && line=$(grep -o 'result {.*' "$log" | tail -1 || true)
        # A window that didn't measure steady streaming (a stall, a stop, the release's end): the test logs why, and fails.
        invalid=""
        [ -n "$line" ] && invalid=$(python3 -I -c 'import json, sys; r = json.loads(sys.argv[1][len("result "):]); print("" if r.get("valid") is not False else r.get("invalid") or "no reason given")' "$line")
        if [ -n "$invalid" ]; then
          failed=$((failed + 1))
          echo "bench: run $n/$total $name: not valid, $invalid (log $out/runs/$name.log)" >&2
        # A run that failed after its result (in the test's teardown, say) is not counted: its figures are in doubt.
        elif [ -n "$line" ] && [ $status = ok ]; then
          emulator=$(basename "$(dirname "$log")")
          python3 -I -c 'import json, sys; r = json.loads(sys.argv[1][len("result "):]); r.update(round=int(sys.argv[2]), emulator=sys.argv[3])
if sys.argv[4]: r["serial"] = sys.argv[4]
if sys.argv[5]: r["tls_version"] = sys.argv[5]
r["trees"] = json.loads(sys.argv[6])
print(json.dumps(r))' "$line" "$round" "$emulator" "$serial" "$([ "$transport" = tls ] && echo "$tls_version")" "$(trees_now)" >> "$out/results.jsonl"
          echo "bench: run $n/$total $name: $status, $(python3 -I -c 'import json, sys; r = json.loads(sys.argv[1][len("result "):]); print("%.1f MB/s, %.0f ms/MB, %.2f MB/MB" % (r["mb_per_s"], r.get("cpu_ms_per_mb") or 0, r.get("alloc_mb_per_mb") or 0))' "$line")" >&2
        else
          failed=$((failed + 1))
          echo "bench: run $n/$total $name: $status, no result (log $out/runs/$name.log)" >&2
        fi
        rm -f "$marker"
      done
    done
  done
done
[ -f "$out/results.jsonl" ] && python3 -I "$here/summarize.py" "$out/results.jsonl" | tee "$out/summary.txt"
echo "bench: results in $out" >&2
if [ $failed = 0 ]; then echo "RESULT: PASSED ($total runs)"; else echo "RESULT: FAILED ($failed of $total runs failed or gave no result)"; exit 1; fi
