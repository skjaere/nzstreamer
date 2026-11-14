#!/bin/bash
# Takes the README's screenshots (ReadmeScreensTest) on one emulator: the phone's (P1-P5) or the TV's (T1-T4).
#
# Usage, from anywhere in the repository:
#   tools/screens/capture.sh phone|tv --clip <clip.mkv> --out <dir> [--port <port>] [--test <method>]
#   phone|tv  which screens: the phone's on the emulator whose AVD is phone34, the TV's on the one whose AVD is tv36.
#   --clip    the player's clip, as make-clip.sh makes it.
#   --out     gets each screen's PNG and the words on it (.txt): a new or empty folder.
#   --port    the screens indexer's port on this computer, 8093 or above but not 8096 or 8097 (default: the first free
#             one from 8093). 8089 is the device tests' fake indexer, and the ports beside it are left to it; 8096 and
#             8097 are mock-seed's torznab and debrid fakes' own.
#   --test    one test of the class alone, as p1Home.
#
# The emulator is found by its AVD name among the running emulators; any other device (another emulator, or a phone
# or a TV box on USB or the network) is never used, and nothing runs without the emulator. The mock NNTP server must be running on
# this computer (ports 1119 and 8081, as the main README's device tests start it): this script never starts or stops
# it. It starts mock-seed's screens indexer on a free port, which seeds the clip into the mock first, and stops it
# after. On the emulator it clears the app's data, names the device (a TV "Living room TV", a phone "Phone"), puts the
# phone's status bar in demo mode (a set clock, full battery and signal, no notifications), runs the bench build's test
# (-PbenchBuild=true -PhideDebrid=true), pulls what it saved, and puts the name, the demo mode setting and the test's
# folder back as they were, also when something fails. Gradle's run uninstalls the app and the test APK after.
set -euo pipefail

usage() { echo "usage: $0 phone|tv --clip <clip.mkv> --out <dir> [--port <port>] [--test <method>]" >&2; exit 2; }
[ $# -ge 1 ] || usage
kind=$1; shift
case "$kind" in phone) avd=phone34 ;; tv) avd=tv36 ;; *) usage ;; esac
clip="" out="" port="" only=""
while [ $# -gt 0 ]; do
  [ $# -ge 2 ] || usage
  case "$1" in
    --clip) clip=$2 ;;
    --out) out=$2 ;;
    --port) port=$2 ;;
    --test) only=$2 ;;
    *) usage ;;
  esac
  shift 2
done
if [ -z "$clip" ] || [ -z "$out" ]; then usage; fi
[ -f "$clip" ] || { echo "no such clip: $clip (tools/screens/make-clip.sh makes it)" >&2; exit 2; }
[[ $only =~ ^[A-Za-z0-9_]*$ ]] || { echo "--test takes a test method's name, as p1Home" >&2; exit 2; }
# mock-seed's torznab and debrid fakes' own ports, which a screens indexer never takes.
fakes_ports() { [ "$1" = 8096 ] || [ "$1" = 8097 ]; }
if [ -n "$port" ]; then
  [[ $port =~ ^[0-9]{4,5}$ ]] && [ "$port" -ge 8093 ] && [ "$port" -le 65535 ] && ! fakes_ports "$port" ||
    { echo "--port takes a port from 8093 up, but not 8096 or 8097" >&2; exit 2; }
fi
clip=$(cd "$(dirname "$clip")" && pwd)/$(basename "$clip")
# A new or empty folder, so no screen from an earlier run is taken for one of this run's.
if [ -e "$out" ] && [ -n "$(ls -A "$out")" ]; then echo "--out $out isn't empty" >&2; exit 2; fi
mkdir -p "$out"
out=$(cd "$out" && pwd)
root=$(cd "$(dirname "$0")/../.." && pwd)

APP=io.skjaere.usenet
CLASS=io.skjaere.usenet.app.screens.ReadmeScreensTest
DEVICE_DIR=/data/local/tmp/readme-screens
KEY=readme-screens-key
if [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/platform-tools/adb" ]; then ADB=$ANDROID_HOME/platform-tools/adb; else ADB=adb; fi
command -v "$ADB" >/dev/null || { echo "adb not found: set ANDROID_HOME" >&2; exit 2; }

# The emulator whose AVD is $avd: emulators alone are asked, by their emulator-NNNN serials.
serial=""
for s in $("$ADB" devices | awk '$1 ~ /^emulator-[0-9]+$/ && $2 == "device" { print $1 }'); do
  name=$("$ADB" -s "$s" emu avd name 2>/dev/null | head -1 | tr -d '\r')
  if [ "$name" = "$avd" ]; then serial=$s; break; fi
done
[ -n "$serial" ] || { echo "no running emulator has the AVD $avd: start it first" >&2; exit 2; }
adb_() { "$ADB" -s "$serial" "$@"; }
shell() { adb_ shell "$@" | tr -d '\r'; }
# A value as one word for the device's shell, which adb hands the command line to: single-quoted.
quoted() { printf "'%s'" "${1//\'/\'\\\'\'}"; }
echo "capture: the $kind screens on $serial (AVD $avd)"

listening() { (exec 3<>"/dev/tcp/127.0.0.1/$1") 2>/dev/null; }
if ! listening 1119 || ! listening 8081; then
  echo "the mock NNTP server isn't running on ports 1119 and 8081: start it as the main README's device tests do" >&2
  exit 1
fi

if [ -z "$port" ]; then
  port=8093
  while listening "$port" || fakes_ports "$port"; do port=$((port + 1)); done
elif listening "$port"; then
  echo "port $port is taken" >&2; exit 2
fi

indexer_pid="" name_before="" demo_before="" demo_on=false
# shellcheck disable=SC2317 # run by the traps below
put_back() {
  local status=$?
  set +e
  if [ -n "$indexer_pid" ]; then
    kill "$indexer_pid" 2>/dev/null
    wait "$indexer_pid" 2>/dev/null
  fi
  if $demo_on; then
    shell am broadcast -a com.android.systemui.demo -e command exit >/dev/null
    if [ "$demo_before" = null ]; then shell settings delete global sysui_demo_allowed >/dev/null
    else shell settings put global sysui_demo_allowed "$(quoted "$demo_before")"; fi
  fi
  if [ -n "$name_before" ]; then
    if [ "$name_before" = null ]; then shell settings delete global device_name >/dev/null
    else shell settings put global device_name "$(quoted "$name_before")"; fi
  fi
  shell rm -rf "$DEVICE_DIR"
  # Read back, so a setting that didn't go back is said, and fails the run.
  local left=()
  [ -z "$name_before" ] || [ "$(shell settings get global device_name)" = "$name_before" ] || left+=("the device's name")
  if $demo_on; then [ "$(shell settings get global sysui_demo_allowed)" = "$demo_before" ] || left+=("the demo mode setting"); fi
  [ -z "$(shell ls -d "$DEVICE_DIR" 2>/dev/null)" ] || left+=("$DEVICE_DIR")
  if [ ${#left[@]} -gt 0 ]; then
    echo "capture: not put back: ${left[*]}" >&2
    [ "$status" != 0 ] || status=1
  else
    echo "capture: the emulator's settings are as they were"
  fi
  exit "$status"
}
trap put_back EXIT
trap 'exit 130' INT TERM

# The screens indexer, from mock-seed's own start script, so it is this script's process to stop.
(cd "$root" && ./gradlew -q :tools:mock-seed:installDist)
"$root/tools/mock-seed/build/install/mock-seed/bin/mock-seed" indexer --catalog screens --video "$clip" --port "$port" --apikey "$KEY" \
  --catalog-file "$root/app/src/androidTest/assets/screens/catalog.json" > "$out/indexer.log" 2>&1 &
indexer_pid=$!
echo "capture: seeding the clip and starting the screens indexer on port $port"
# Ready when it says so itself, once the clip is seeded: a port that only listens may be another program's.
ready="Fake indexer (screens) on http://localhost:$port/"
for _ in $(seq 1 600); do
  kill -0 "$indexer_pid" 2>/dev/null || { echo "the screens indexer stopped: see $out/indexer.log" >&2; exit 1; }
  grep -q -F "$ready" "$out/indexer.log" && break
  sleep 1
done
grep -q -F "$ready" "$out/indexer.log" || { echo "the screens indexer didn't start in 10 minutes: see $out/indexer.log" >&2; exit 1; }

# The emulator as the screenshots have it.
if [ -n "$(shell pm path "$APP")" ]; then shell pm clear "$APP" >/dev/null; fi
shell rm -rf "$DEVICE_DIR"
name_before=$(shell settings get global device_name)
if [ "$kind" = tv ]; then shell settings put global device_name "$(quoted "Living room TV")"; else shell settings put global device_name Phone; fi
if [ "$kind" = phone ]; then
  demo_before=$(shell settings get global sysui_demo_allowed)
  demo_on=true
  shell settings put global sysui_demo_allowed 1
  demo() { shell am broadcast -a com.android.systemui.demo -e command "$@" >/dev/null; }
  demo enter
  demo clock -e hhmm 1030
  demo battery -e level 100 -e plugged false -e powersave false
  demo network -e wifi show -e level 4 -e fully true
  demo network -e mobile show -e datatype none -e level 4 -e fully true
  demo notifications -e visible false
  demo status -e volume hide -e bluetooth hide -e location hide -e alarm hide -e zen hide -e sync hide -e mute hide -e speakerphone hide
fi

test=$CLASS${only:+#$only}
set +e
(cd "$root" && ANDROID_SERIAL=$serial ./gradlew :app:connectedBenchAndroidTest -PbenchBuild=true -PhideDebrid=true \
  -Pandroid.testInstrumentationRunnerArguments.class="$test" \
  -Pandroid.testInstrumentationRunnerArguments.screens=true \
  -Pandroid.testInstrumentationRunnerArguments.screensIndexer="http://10.0.2.2:$port" \
  -Pandroid.testInstrumentationRunnerArguments.screensIndexerKey="$KEY")
status=$?
set -e
if [ -n "$(shell ls "$DEVICE_DIR" 2>/dev/null)" ]; then
  adb_ pull "$DEVICE_DIR/." "$out" >/dev/null
fi
# The class saves five phone screens (P1-P5) and four TV screens (T1-T4); a test that fails saves failed-<name>.
if [ -n "$only" ]; then expected=1; elif [ "$kind" = phone ]; then expected=5; else expected=4; fi
saved=$(find "$out" -maxdepth 1 -name '*.png' ! -name 'failed-*' | wc -l)
echo "capture: $saved of $expected screens in $out"
if [ "$status" = 0 ] && [ "$saved" != "$expected" ]; then
  echo "capture: the test passed but saved $saved screens, not $expected" >&2
  status=1
fi
exit $status
