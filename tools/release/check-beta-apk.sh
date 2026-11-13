#!/bin/bash
# Checks a beta the build just made, and stages a copy of it for a release:
#   tools/release/check-beta-apk.sh --digest-file <file> --code <N> --version <versionName> --out <folder> [--canaries]
# Run it in the repository's top folder after
#   ./gradlew -PbetaBuild=<N> -PexpectHideDebrid=true ... \
#       :app:assembleBeta :app:testBetaUnitTest --tests '*HiddenBuildTest' --tests '*VersionCodeTest'
# The release workflow runs it with tools/beta-cert.sha256, the beta key's certificate digest; CI runs it on a beta
# signed with a throwaway key, with that key's digest.
# It copies the APK the build's metadata names into <folder>, which must not exist yet, as
# NZStreamer-Beta-<versionName>.apk with its .sha256 beside it, and from then on reads only that copy. It fails unless:
# - checkBetaHasNoDevSettings passed on those bytes: its report says so, its control shows that it read the dex, and it
#   holds the copy's SHA-256 (with --canaries, it also counts the extra entries it looked for);
# - HiddenBuildTest and VersionCodeTest each ran in that build, none skipped, failed or in error;
# - the APK has one signer, whose certificate's SHA-256 digest is the digest file's;
# - it is app.nzstreamer.beta at versionCode <N> and versionName <versionName>.
# It warns when the APK is over 24 MiB. Exit 0 when it passes, 1 when a check fails, 2 on bad arguments.
set -euo pipefail

PACKAGE=app.nzstreamer.beta
WARN_BYTES=$((24 * 1024 * 1024))
TESTS=(io.skjaere.usenet.app.debrid.HiddenBuildTest io.skjaere.usenet.app.VersionCodeTest)
APK_DIR=app/build/outputs/apk/beta
REPORT=app/build/reports/checkBetaHasNoDevSettings.txt
RESULTS=app/build/test-results/testBetaUnitTest

fail() {
    echo "check-beta-apk: $*" >&2
    exit 1
}
usage() {
    echo "check-beta-apk: $*" >&2
    echo "usage: tools/release/check-beta-apk.sh --digest-file <file> --code <N> --version <versionName> --out <folder> [--canaries]" >&2
    exit 2
}
warn() {
    if [[ ${GITHUB_ACTIONS:-} == true ]]; then
        echo "::warning::$*"
    else
        echo "check-beta-apk: warning: $*" >&2
    fi
}

digest_file='' code='' version='' out='' canaries=false
while (($#)); do
    case $1 in
        --digest-file | --code | --version | --out)
            (($# >= 2)) || usage "$1 needs a value"
            case $1 in
                --digest-file) digest_file=$2 ;;
                --code) code=$2 ;;
                --version) version=$2 ;;
                --out) out=$2 ;;
            esac
            shift 2
            ;;
        --canaries) canaries=true && shift ;;
        *) usage "unknown argument: $1" ;;
    esac
done
[[ -n $digest_file && -n $code && -n $version && -n $out ]] || usage "--digest-file, --code, --version and --out are all needed"
[[ $code =~ ^[1-9][0-9]{0,9}$ ]] || usage "--code takes a versionCode, not '$code'"
[[ $version =~ ^[0-9A-Za-z][0-9A-Za-z.+-]*$ ]] || usage "--version takes a versionName, not '$version'"
[[ -f $digest_file ]] || usage "no digest file at $digest_file"
expected=$(tr -d '[:space:]' <"$digest_file")
[[ $expected =~ ^[0-9a-f]{64}$ ]] || usage "$digest_file holds no SHA-256 digest (64 lower-case hex digits)"
[[ -n ${ANDROID_HOME:-} ]] || usage "ANDROID_HOME is not set: the check needs build-tools' apksigner and aapt2"

# The one APK the build's metadata names, by its file name in the APK folder.
metadata=$APK_DIR/output-metadata.json
[[ -f $metadata ]] || fail "no $metadata: build :app:assembleBeta first"
built=$(python3 -I -c '
import json, sys
elements = json.load(open(sys.argv[1]))["elements"]
names = [element["outputFile"] for element in elements]
if len(names) != 1 or "/" in names[0] or names[0] in ("", ".", ".."):
    sys.exit(1)
print(names[0])
' "$metadata") || fail "$metadata names more than one APK, or a path: one APK is expected"
[[ -f $APK_DIR/$built ]] || fail "$metadata names $built, which isn't there"

# The staged copy: everything below reads it alone, so what is checked is what is uploaded.
[[ ! -e $out ]] || fail "$out exists: give a new folder, so no earlier file can pass for this build's"
mkdir -p -- "$out"
out=$(cd -- "$out" && pwd)
name=NZStreamer-Beta-$version.apk
staged=$out/$name
cp -- "$APK_DIR/$built" "$staged"
sha=$(sha256sum -- "$staged" | cut -d' ' -f1)
(cd -- "$out" && sha256sum -- "$name" >"$name.sha256")

# checkBetaHasNoDevSettings, a finalizer of assembleBeta, on these bytes.
[[ -f $REPORT ]] || fail "no $REPORT: checkBetaHasNoDevSettings didn't run"
grep -q "carries none of local.properties' dev settings" "$REPORT" && grep -q "the scan reads the dex" "$REPORT" ||
    fail "checkBetaHasNoDevSettings didn't pass: see $REPORT"
grep -qx "  SHA-256: $sha" "$REPORT" || fail "checkBetaHasNoDevSettings scanned other bytes than $built's"
if $canaries; then
    grep -qE '^  Looked in every entry .*[0-9]+ extra entr(y|ies)\.$' "$REPORT" ||
        fail "checkBetaHasNoDevSettings looked for none of the canaries' extra entries: see $REPORT"
fi

# The two tests, each run in full: a skipped one would pass for nothing.
for test in "${TESTS[@]}"; do
    xml=$RESULTS/TEST-$test.xml
    [[ -f $xml ]] || fail "no results for ${test##*.} in $RESULTS: run it in the same build"
    python3 -I -c '
import sys, xml.etree.ElementTree as ET
suite = ET.parse(sys.argv[1]).getroot()
counts = [int(suite.get(name, "-1")) for name in ("tests", "skipped", "failures", "errors")]
sys.exit(0 if counts[0] >= 1 and counts[1:] == [0, 0, 0] else 1)
' "$xml" || fail "${test##*.} didn't run in full (a test skipped, failed or in error): see $xml"
done

# The signer and the package, by the newest build-tools.
tools=$(printf '%s\n' "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)
[[ -x $tools/apksigner && -x $tools/aapt2 ]] || fail "no apksigner and aapt2 in $ANDROID_HOME/build-tools"
certs=$("$tools/apksigner" verify --print-certs "$staged") || fail "apksigner doesn't verify $name"
signers=$(grep -c '^Signer #[0-9]* certificate SHA-256 digest: ' <<<"$certs" || true)
[[ $signers == 1 ]] || fail "$name has $signers signers: a beta has one, the beta key"
[[ $(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' <<<"$certs") == "$expected" ]] ||
    fail "$name isn't signed by the key whose certificate digest $digest_file holds"
package=$("$tools/aapt2" dump badging "$staged" | grep '^package: ') || fail "aapt2 can't read $name's package"
[[ "$package " == "package: name='$PACKAGE' versionCode='$code' versionName='$version' "* ]] ||
    fail "$name is not $PACKAGE at versionCode $code, versionName $version: $package"

size=$(stat -c %s "$staged")
((size <= WARN_BYTES)) || warn "$name is $size bytes, over 24 MiB: the site's limit for one file is 25 MiB"
echo "check-beta-apk: $name passes: $PACKAGE $code ($version), one signer with the expected certificate, $size bytes, SHA-256 $sha"
