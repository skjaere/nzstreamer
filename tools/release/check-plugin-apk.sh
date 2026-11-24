#!/bin/bash
# Checks a plugin's release build, signed in release-plugin.yml, and stages a copy of it for the release:
#   tools/release/check-plugin-apk.sh --id <id> --digest-file <file> --code <N> --version <MAJOR.MINOR.PATCH> \
#       --out <folder> [--canaries <file>]
# Run it in the repository's top folder after ./gradlew :plugins:<id>:assembleRelease, whose finalizer runs plugin-check's
# rules on the APK (the root build's plugin convention, L625). check-beta-apk.sh checks the app's beta; this, a plugin
# (L588). It copies the APK the build's metadata names into <folder>, which must not exist yet, as <id>.apk, with an
# <id>.apk.sha256 naming the copy by its file name alone, so sha256sum -c checks it in any folder that holds the two. From
# then on it reads only that copy. It fails unless:
# - plugin-check's rules passed on that build: its report (checkPluginRulesRelease) says so for this APK, version and API;
# - the APK has one signer, whose certificate's SHA-256 digest is the digest file's (tools/beta-cert.sha256 in a release);
# - it is app.nzstreamer.plugin.<id> at versionCode <N> and versionName <MAJOR.MINOR.PATCH>, its nzs.plugin.id is <id>,
#   and its API stamp, nzs.plugin.api.major and .minor stored as integers, is the tree's (core/plugin/plugin-api.txt);
# - its minSdk is 26;
# - it has no launcher activity, no component (activity, service, receiver, provider), no permission of any kind and no
#   sharedUserId: a plugin is code NZStreamer loads, never an app Android starts (L624);
# - with --canaries, the file canaries.py wrote from the release workflow's secret: none of them is in any of its entries,
#   inflated, or in the file's own bytes, as UTF-8 or UTF-16, by the canary file's rules for a binary, as the beta's
#   secret check reads them (tools/feature-check's SecretCheck: in any case, a w: or w2: value as a whole word, a wt:
#   value and one under 5 characters never). A dex of the APK must hold the plugin's own classes, so a scan that can't
#   read the dex can't pass blind. A hit is told by its line in that file and the entry it is in, never its label or
#   value.
# Exit 0 when it passes, 1 when a check fails, 2 on bad arguments.
set -euo pipefail

API_FILE=core/plugin/plugin-api.txt

fail() {
    echo "check-plugin-apk: $*" >&2
    exit 1
}
usage() {
    echo "check-plugin-apk: $*" >&2
    echo "usage: tools/release/check-plugin-apk.sh --id <id> --digest-file <file> --code <N> --version <MAJOR.MINOR.PATCH> --out <folder> [--canaries <file>]" >&2
    exit 2
}

id='' digest_file='' code='' version='' out='' canaries=''
while (($#)); do
    case $1 in
        --id | --digest-file | --code | --version | --out | --canaries)
            (($# >= 2)) || usage "$1 needs a value"
            case $1 in
                --id) id=$2 ;;
                --digest-file) digest_file=$2 ;;
                --code) code=$2 ;;
                --version) version=$2 ;;
                --out) out=$2 ;;
                --canaries) canaries=$2 ;;
            esac
            shift 2
            ;;
        *) usage "unknown argument: $1" ;;
    esac
done
[[ -n $id && -n $digest_file && -n $code && -n $version && -n $out ]] ||
    usage "--id, --digest-file, --code, --version and --out are all needed"
[[ $id =~ ^[a-z][a-z0-9]{0,30}$ && $id != true && $id != false ]] ||
    usage "--id takes a plugin's id, a lower-case letter and up to 30 lower-case letters and digits, not '$id'"
[[ $code =~ ^[1-9][0-9]{0,9}$ ]] || usage "--code takes a versionCode, not '$code'"
[[ $version =~ ^(0|[1-9][0-9]{0,3})\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)$ ]] ||
    usage "--version takes the release's MAJOR.MINOR.PATCH, not '$version'"
[[ -f $digest_file ]] || usage "no digest file at $digest_file"
expected=$(tr -d '[:space:]' <"$digest_file")
[[ $expected =~ ^[0-9a-f]{64}$ ]] || usage "$digest_file holds no SHA-256 digest (64 lower-case hex digits)"
# The canaries' file's path is not shown: the secret it holds is the release workflow's.
[[ -z $canaries || -f $canaries ]] || usage "no canaries file at the path given"
[[ -n ${ANDROID_HOME:-} ]] || usage "ANDROID_HOME is not set: the check needs build-tools' apksigner and aapt2"
[[ -f $API_FILE ]] || usage "no $API_FILE: run it in the repository's top folder"
api=$(tr -d '[:space:]' <"$API_FILE")
[[ $api =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$ ]] || fail "$API_FILE holds '$api', not MAJOR.MINOR"
package=app.nzstreamer.plugin.$id
apk_dir=plugins/$id/build/outputs/apk/release
report=plugins/$id/build/reports/checkPluginRulesRelease.txt

# The one APK the build's metadata names, by its file name in the APK folder.
metadata=$apk_dir/output-metadata.json
[[ -f $metadata ]] || fail "no $metadata: build :plugins:$id:assembleRelease first"
built=$(python3 -I -c '
import json, sys
elements = json.load(open(sys.argv[1]))["elements"]
names = [element["outputFile"] for element in elements]
if len(names) != 1 or "/" in names[0] or names[0] in ("", ".", ".."):
    sys.exit(1)
print(names[0])
' "$metadata") || fail "$metadata names more than one APK, or a path: one APK is expected"
[[ -f $apk_dir/$built ]] || fail "$metadata names $built, which isn't there"

# plugin-check's rules, the finalizer of that assembleRelease, on this build: its report's first line names the APK, its
# versionCode and its API stamp, compared as text.
[[ -f $report ]] || fail "no $report: plugin-check's rules (checkPluginRulesRelease) didn't run on this build"
passed=$(head -n 1 -- "$report")
[[ $passed == "$built keeps every plugin's rules: $package $code, API $api, "* ]] ||
    fail "checkPluginRulesRelease didn't pass on $built as $package $code, API $api: see $report"

# The staged copy: everything below reads it alone, so what is checked is what is uploaded.
[[ ! -e $out ]] || fail "$out exists: give a new folder, so no earlier file can pass for this build's"
mkdir -p -- "$out"
out=$(cd -- "$out" && pwd)
name=$id.apk
staged=$out/$name
cp -- "$apk_dir/$built" "$staged"
sha=$(sha256sum -- "$staged" | cut -d' ' -f1)
(cd -- "$out" && sha256sum -- "$name" >"$name.sha256")

# The signer, the package and the manifest, by the newest build-tools.
tools=$(printf '%s\n' "$ANDROID_HOME"/build-tools/* | sort -V | tail -1)
[[ -x $tools/apksigner && -x $tools/aapt2 ]] || fail "no apksigner and aapt2 in $ANDROID_HOME/build-tools"
certs=$("$tools/apksigner" verify --print-certs "$staged") || fail "apksigner doesn't verify $name"
# Up to build-tools 36, apksigner prints "Signer #1 certificate SHA-256 digest: <hex>". From 37 it prints
# "V2 Signer: certificate SHA-256 digest: <hex>", once for each signature scheme it verified. Either way a signer is a
# certificate, so the signers are the distinct digests. Lines in any other form count for nothing, so the check fails.
digests=$(sed -n -E 's/^(Signer #[0-9]+ |V[0-9.]+ Signer: )certificate SHA-256 digest: ([0-9a-f]{64})$/\2/p' <<<"$certs" | sort -u)
signers=$(grep -c . <<<"$digests" || true)
[[ $signers == 1 ]] || fail "$name has $signers signers: a plugin has one, the beta key"
[[ $digests == "$expected" ]] || fail "$name isn't signed by the key whose certificate digest $digest_file holds"
badging=$("$tools/aapt2" dump badging --include-meta-data "$staged") || fail "aapt2 can't read $name's badging"
xmltree=$("$tools/aapt2" dump xmltree --file AndroidManifest.xml "$staged") || fail "aapt2 can't read $name's manifest"
line=$(grep '^package: ' <<<"$badging") || fail "aapt2 gives $name no package"
[[ "$line " == "package: name='$package' versionCode='$code' versionName='$version' "* ]] ||
    fail "$name is not $package at versionCode $code, versionName $version: $line"

# The manifest: aapt2's badging prints the meta-data's values, and its xmltree how each is stored (a string quoted, an
# integer bare), as plugin-check's rules read them. Each problem on a line of its own.
problems=$(python3 -I - "$id" "$api" "$badging" "$xmltree" <<'PY'
import re, sys
plugin_id, api, badging, xmltree = sys.argv[1:5]
major, minor = api.split(".")
problems = []
meta = dict(re.findall(r"^meta-data: name='([^']*)' value='([^']*)'", badging, re.M))
if meta.get("nzs.plugin.id") != plugin_id:
    problems.append(f"its nzs.plugin.id is not {plugin_id}")
if re.search(r"^(leanback-)?launchable-activity:", badging, re.M):
    problems.append("it has a launcher activity: plugins have none")

# The manifest's elements, each with its parent's name and its attributes by their local names, values as printed.
elements, open_elements = [], []
for line in xmltree.splitlines():
    element = re.match(r"^(\s*)E: (\S+)", line)
    if element:
        depth = len(element.group(1))
        while open_elements and open_elements[-1]["depth"] >= depth:
            open_elements.pop()
        entry = {"name": element.group(2), "depth": depth, "attributes": {},
                 "parent": open_elements[-1]["name"] if open_elements else None}
        elements.append(entry)
        open_elements.append(entry)
        continue
    attribute = re.match(r"^\s*A: (?:\S*:)?([A-Za-z]+)(?:\(0x[0-9a-f]+\))?=(.*)$", line)
    if attribute and open_elements:
        open_elements[-1]["attributes"][attribute.group(1)] = attribute.group(2)


def stamp(name, wanted):
    """The API stamp's meta-data: once, stored as an integer (printed bare), the tree's in both dumps."""
    found = [e for e in elements if e["name"] == "meta-data" and e["parent"] == "application"
             and e["attributes"].get("name", "").startswith(f'"{name}" (Raw: ')]
    value = found[0]["attributes"].get("value") if len(found) == 1 else None
    if value is None:
        problems.append(f"its API stamp has no single {name}")
    elif not re.fullmatch(r"-?[0-9]+", value):
        problems.append(f"its API stamp's {name} is not stored as an integer, as NZStreamer reads it")
    elif value != wanted or meta.get(name) != wanted:
        problems.append(f"its API stamp is not the tree's {api}")


stamp("nzs.plugin.api.major", major)
stamp("nzs.plugin.api.minor", minor)
if [e["attributes"].get("minSdkVersion") for e in elements if e["name"] == "uses-sdk"] != ["26"]:
    problems.append("its minSdk is not 26")
for e in elements:
    kind = e["name"]
    if kind in ("activity", "activity-alias", "service", "receiver", "provider"):
        problems.append(f"it declares a component ({kind}): plugins declare none")
    elif kind in ("uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m", "permission", "permission-group",
                  "permission-tree"):
        problems.append(f"it declares a permission ({kind}): plugins declare none")
    if "sharedUserId" in e["attributes"]:
        problems.append("it has a sharedUserId: plugins have none")
print("\n".join(dict.fromkeys(problems)))
PY
) || fail "the manifest's check didn't run"
if [[ -n $problems ]]; then
    while IFS= read -r problem; do
        echo "check-plugin-apk: $name: $problem" >&2
    done <<<"$problems"
    fail "$name breaks a plugin's rules above"
fi

# The canaries, by the canary file's rules for a binary (tools/feature-check's SecretCheck reads the beta's so).
if [[ -n $canaries ]]; then
    python3 -I - "$id" "$staged" "$canaries" <<'PY' || exit $?
import sys, zipfile
plugin_id, apk, canaries = sys.argv[1:4]
name = apk.rsplit("/", 1)[-1]
# Each byte with ASCII and Latin-1 capitals lowered, as SecretCheck lowers a binary read as Latin-1.
LOWER = bytes((b + 0x20) if 0x41 <= b <= 0x5A or (0xC0 <= b <= 0xDE and b != 0xD7) else b for b in range(256))


def word_byte(b):
    return chr(b).isascii() and (chr(b).isalnum() or chr(b) == "_")


def word_char(high, low):
    c = chr(high << 8 | low)
    return c.isalnum() or c == "_"


def alone(data, start, length, charset):
    """Whether nothing that belongs to a word sits right before or after the found bytes, as SecretCheck reads one."""
    end = start + length
    if charset == "utf-8":
        return (start == 0 or not word_byte(data[start - 1])) and (end == len(data) or not word_byte(data[end]))
    if charset == "utf-16-le":
        return (start < 2 or not word_char(data[start - 1], data[start - 2])) and \
            (end + 1 >= len(data) or not word_char(data[end + 1], data[end]))
    return (start < 2 or not word_char(data[start - 2], data[start - 1])) and \
        (end + 1 >= len(data) or not word_char(data[end], data[end + 1]))


needles, skipped = [], 0
with open(canaries, encoding="utf-8") as f:
    for number, raw in enumerate(f.read().split("\n"), 1):
        line = raw.rstrip("\r")
        if not line.strip() or line.startswith("#"):
            continue
        label, tab, value = line.partition("\t")
        if not tab:
            print(f"check-plugin-apk: line {number} of the canaries file has no TAB", file=sys.stderr)
            sys.exit(2)
        if len(value) < 5 or label.startswith("wt:"):
            skipped += 1
            continue
        forms = {charset: value.encode(charset).translate(LOWER) for charset in ("utf-8", "utf-16-le", "utf-16-be")}
        needles.append((number, label.startswith(("w:", "w2:")), forms))


def found_in(data):
    folded = data.translate(LOWER)
    found = set()
    for number, word, forms in needles:
        for charset, form in forms.items():
            at = folded.find(form)
            while at != -1 and number not in found:
                if not word or alone(folded, at, len(form), charset):
                    found.add(number)
                at = folded.find(form, at + 1)
    return found


where, control = {}, False
own = f"Lapp/nzstreamer/plugin/{plugin_id}/".encode()
with zipfile.ZipFile(apk) as z:
    for entry in z.infolist():
        if entry.is_dir():
            continue
        data = z.read(entry)
        if entry.filename.startswith("classes") and entry.filename.endswith(".dex") and own in data:
            control = True
        for number in found_in(data):
            where.setdefault(number, set()).add(entry.filename)
with open(apk, "rb") as f:
    # The file's own bytes: what is in no entry (the signing block), and stored entries again.
    for number in found_in(f.read()):
        where.setdefault(number, set()).add("the file itself")
if not control:
    print(f"check-plugin-apk: no dex in {name} holds its own classes, so the canaries' scan can't be trusted",
          file=sys.stderr)
    sys.exit(1)
if where:
    lines = sorted(where)
    on = "line " + str(lines[0]) if len(lines) == 1 else "lines " + ", ".join(map(str, lines))
    print(f"check-plugin-apk: {name} holds {len(lines)} of the {len(needles)} canaries looked for, on {on} of the "
          "canaries file. Don't publish it:", file=sys.stderr)
    for entry in sorted({entry for entries in where.values() for entry in entries}):
        print(f"check-plugin-apk:   {entry}: {sum(entry in entries for entries in where.values())}", file=sys.stderr)
    sys.exit(1)
print(f"check-plugin-apk: {name} holds none of the {len(needles)} canaries looked for ({skipped} left to the text "
      "scans)", file=sys.stderr)
PY
fi

size=$(stat -c %s "$staged")
echo "check-plugin-apk: $name passes: $package $code ($version), API $api, one signer with the expected certificate, $size bytes, SHA-256 $sha"
