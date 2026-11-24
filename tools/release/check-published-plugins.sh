#!/bin/bash
# The published plugins against this build (L625), CI's step "Published plugins against this build", after Gradle's check:
#   tools/release/check-published-plugins.sh [--digest-file <file>] [-- <Gradle's arguments>]
# Run it in the repository's top folder. It reads the plugin list at the app's own address, PluginSource.LIST in
# app/src/main/kotlin/io/skjaere/usenet/app/plugins/PluginSource.kt, so the address lives in one place, as the app reads
# it: https alone, no redirect followed, 256 KiB at most, the User-Agent NZStreamer. A 404 passes with a notice: no list
# is published until the first plugin release makes the plugin-index branch's file. A 429, a 5xx or no answer is asked
# again, twice at most, after GitHub's Retry-After (a minute at most) or a few seconds, so a passing hiccup isn't a failed
# check; anything else but a 200 fails at once, and so does a third busy answer. Each download is tried again by curl.
# Each version the list gives that was built against the tree's plugin API major (core/plugin/plugin-api.txt) is
# downloaded as the app downloads one (https on every hop, three redirects at most, the list's size as a cap), and must
# have the list's size and SHA-256 and one signer, the beta key (tools/beta-cert.sha256, or the --digest-file given).
# Then plugin-check's link check runs each against this build's release dex (:app:checkPluginLinks, with the Gradle
# arguments given): a host change that would break a published plugin fails here, before NZStreamer is released.
# The downloads go into a temp folder of its own, deleted when it ends. Exit 0 when every version passes or none is
# listed, 1 when one fails or the list can't be read, 2 on bad arguments.
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
SOURCE=app/src/main/kotlin/io/skjaere/usenet/app/plugins/PluginSource.kt
API_FILE=core/plugin/plugin-api.txt

fail() {
    echo "check-published-plugins: $*" >&2
    exit 1
}
usage() {
    echo "check-published-plugins: $*" >&2
    echo "usage: tools/release/check-published-plugins.sh [--digest-file <file>] [-- <Gradle's arguments>]" >&2
    exit 2
}
notice() {
    if [[ ${GITHUB_ACTIONS:-} == true ]]; then
        echo "::notice::$*"
    else
        echo "check-published-plugins: $*"
    fi
}

digest_file=tools/beta-cert.sha256
gradle=()
while (($#)); do
    case $1 in
        --digest-file)
            (($# >= 2)) || usage "$1 needs a value"
            digest_file=$2
            shift 2
            ;;
        --)
            shift
            gradle=("$@")
            break
            ;;
        *) usage "unknown argument: $1" ;;
    esac
done
[[ -f $digest_file ]] || usage "no digest file at $digest_file"
expected=$(tr -d '[:space:]' <"$digest_file")
[[ $expected =~ ^[0-9a-f]{64}$ ]] || usage "$digest_file holds no SHA-256 digest (64 lower-case hex digits)"
[[ -n ${ANDROID_HOME:-} ]] || usage "ANDROID_HOME is not set: the check needs build-tools' apksigner"
[[ -f $SOURCE && -f $API_FILE ]] || usage "no $SOURCE or $API_FILE: run it in the repository's top folder"

# The app's own addresses: the list's, and where its APKs are released (GitHub's source's host, then RELEASES' path).
list=$(sed -n -E 's|^ *const val LIST = "(https://[^"]+)"$|\1|p' "$SOURCE")
origin=$(sed -n -E 's|^ *val GITHUB = PluginSource\(LIST, releases = "(https://[^"/]+)"\)$|\1|p' "$SOURCE")
path=$(sed -n -E 's|^ *const val RELEASES = "(/[^"]+/)"$|\1|p' "$SOURCE")
[[ $(grep -c . <<<"$list") == 1 && $(grep -c . <<<"$origin") == 1 && $(grep -c . <<<"$path") == 1 ]] ||
    fail "$SOURCE gives no one list address, GitHub source and RELEASES path as this script reads them"
api=$(tr -d '[:space:]' <"$API_FILE")
[[ $api =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] || fail "$API_FILE holds '$api', not MAJOR.MINOR"
major=${BASH_REMATCH[1]}
tools=$(printf '%s\n' "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)
[[ -x $tools/apksigner ]] || fail "no apksigner in $ANDROID_HOME/build-tools"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

# The list, as the app asks for it; asked again while GitHub is busy or gives no answer, three times at most.
for try in 1 2 3; do
    rm -f "$work/head"
    status=$(curl -sS --proto =https --max-filesize 262144 --max-time 60 -A NZStreamer -D "$work/head" \
        -o "$work/index.json" -w '%{http_code}' -- "$list") || status=000
    case $status in
        429 | 5?? | 000) ((try < 3)) || break ;;
        *) break ;;
    esac
    wait=$({ sed -n -E 's/^[Rr]etry-[Aa]fter: *([0-9]+)\r?$/\1/p' "$work/head" 2>/dev/null || true; } | tail -1)
    wait=${wait:-$((try * 5))}
    ((wait <= 60)) || wait=60
    sleep "$wait"
done
case $status in
    404)
        notice "No plugin list is published yet ($list answers 404): no plugin to check against this build"
        exit 0
        ;;
    200) ;;
    000) fail "couldn't read the plugin list at $list" ;;
    *) fail "the plugin list at $list answered $status" ;;
esac
builds=$(python3 -I -B "$here/plugin_index.py" builds --list "$work/index.json" --releases "$origin$path" \
    --api-major "$major") || fail "the plugin list at $list breaks the app's rules"
if [[ -z $builds ]]; then
    notice "The plugin list gives no version of plugin API $major: none to check against this build"
    exit 0
fi

mkdir "$work/apks"
count=0
while IFS=$'\t' read -r id code url size sha; do
    file=$work/apks/$id-$code.apk
    curl -sS -f -L --proto =https --proto-redir =https --max-redirs 3 --max-filesize "$size" --max-time 300 \
        --retry 3 --retry-all-errors -A NZStreamer -o "$file" -- "$url" || fail "couldn't download $id $code"
    [[ $(stat -c %s "$file") == "$size" ]] || fail "$id $code is not the list's size"
    [[ $(sha256sum "$file" | cut -d' ' -f1) == "$sha" ]] || fail "$id $code doesn't have the list's SHA-256"
    certs=$("$tools/apksigner" verify --print-certs "$file") || fail "apksigner doesn't verify $id $code"
    # Up to build-tools 36 "Signer #1 certificate SHA-256 digest: <hex>", from 37 "V2 Signer: certificate SHA-256
    # digest: <hex>" for each scheme: the signers are the distinct digests.
    digests=$(sed -n -E 's/^(Signer #[0-9]+ |V[0-9.]+ Signer: )certificate SHA-256 digest: ([0-9a-f]{64})$/\2/p' <<<"$certs" | sort -u)
    [[ $(grep -c . <<<"$digests" || true) == 1 && $digests == "$expected" ]] ||
        fail "$id $code isn't signed by the beta key alone, $digest_file's"
    count=$((count + 1))
done <<<"$builds"

./gradlew "${gradle[@]}" :app:checkPluginLinks -PpluginLinks="$work/apks" ||
    fail "checkPluginLinks: a published plugin names what this build lacks (above)"
echo "check-published-plugins: $count published plugin versions of API $major link against this build"
