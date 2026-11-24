#!/usr/bin/env python3
"""Tests of tools/release/plugin_index.py: a plugin release's plugin.json from its APK, the plugin list rebuilt from the
published plugin prereleases, and the list's builds CI checks against the app.

Run: python3 -I -B -m unittest discover -s tools/release -p 'test_*.py'. GitHub's API is a fake gh on the PATH that
answers from files the test lays out and logs what it was asked; the SDK is a fake aapt2 and apksigner, as
test_check_beta_apk.py fakes them. The plugins (Kestrel, Quillmere), their words and their bytes are invented. Nothing
here reaches GitHub or reads a real APK.
"""
import hashlib
import json
import os
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "plugin_index.py")
# The list the app's own test reads too (PluginIndexTest): what this script writes for GOLDEN_RELEASES below.
GOLDEN = os.path.join(HERE, "testdata", "plugin-index.json")
REPO = "skjaere/nzstreamer"
DIGEST = "ab" * 32
OTHER_DIGEST = "cd" * 32
NOW = "2026-12-02T08:30:00Z"
LIBS = ("kotlin-stdlib=2.3.21,kotlinx-coroutines=1.11.0,kotlinx-serialization-json=1.11.0,compose-bom=2026.06.01,"
        "lifecycle-viewmodel-compose=2.10.0,activity-compose=1.13.0,slf4j-api=2.0.20")
LIBS_MAP = dict(pair.split("=") for pair in LIBS.split(","))
WORDS = {"kestrel": ("Kestrel", "Invented games from an invented server, in a tab of their own."),
         "quillmere": ("Quillmere", "An invented plugin's invented summary.")}

# Answers gh api as GitHub's API would: the releases' pages one after another (as --paginate prints them without
# --slurp), and an asset's bytes by its id. Logs each call.
FAKE_GH = r"""#!/bin/bash
printf '%s\n' "gh $*" >> "$FAKE_LOG"
[[ $1 == api ]] || exit 98
shift
if [[ $1 == --paginate ]]; then
    [[ $2 == "repos/$FAKE_REPO/releases?per_page=100" ]] || exit 97
    [[ -z ${FAKE_GH_FAILS:-} ]] || { echo "HTTP 502: Bad Gateway" >&2; exit 1; }
    cat "$FAKE_DIR/releases.json"
elif [[ $1 == -H && $2 == "Accept: application/octet-stream" ]]; then
    id=${3#"repos/$FAKE_REPO/releases/assets/"}
    [[ $id =~ ^[0-9]+$ && -f $FAKE_DIR/assets/$id ]] || { echo "HTTP 404: Not Found" >&2; exit 1; }
    cat "$FAKE_DIR/assets/$id"
else
    exit 96
fi
"""

# Logs how it was called, then prints the file the test set, as test_check_beta_apk.py's fake SDK does.
FAKE_TOOL = r"""#!/bin/bash
printf '%s\n' "$(basename "$0") $*" >> "$FAKE_LOG"
case $(basename "$0") in
    apksigner)
        [[ $1 == verify && $2 == --print-certs && $# == 3 ]] || exit 98
        cat "$FAKE_CERTS" ;;
    aapt2)
        [[ $1 == dump && $2 == badging && $3 == --include-meta-data && $# == 4 ]] || exit 98
        cat "$FAKE_BADGING" ;;
esac
"""
OLD_TOOL = "#!/bin/bash\nexit 97\n"


def code_of(version):
    major, minor, patch = (int(part) for part in version.split("."))
    return ((major * 100 + minor) * 100 + patch) * 100


def plugin_json(plugin_id, version, apk=b"", api="1.0", signer=DIGEST, **changes):
    name, summary = WORDS.get(plugin_id, ("Kestrel", "Invented."))
    tag = f"plugin-{plugin_id}-v{version}"
    described = {
        "format": 1, "id": plugin_id, "package": f"app.nzstreamer.plugin.{plugin_id}", "name": name, "summary": summary,
        "versionCode": code_of(version), "versionName": version, "api": api, "libs": dict(LIBS_MAP), "minSdk": 26,
        "targetSdk": 36, "url": f"https://github.com/{REPO}/releases/download/{tag}/{plugin_id}.apk", "size": len(apk),
        "sha256": hashlib.sha256(apk).hexdigest(), "signer": signer,
    }
    described.update(changes)
    return described


class Releases:
    """Invented releases as GitHub's API lists them, and their plugin.json files, laid out for the fake gh."""

    def __init__(self):
        self.releases = []
        self.assets = {}
        self.next_id = 1000

    def asset(self, tag, name, data, **fields):
        self.next_id += 1
        self.assets[self.next_id] = data
        entry = {"id": self.next_id, "name": name, "size": len(data), "digest": "sha256:" + hashlib.sha256(data).hexdigest(),
                 "browser_download_url": f"https://github.com/{REPO}/releases/download/{tag}/{name}"}
        entry.update(fields)
        return entry

    def plugin(self, plugin_id, version, api="1.0", prerelease=True, draft=False, title=None, apk_fields=None,
               published="2026-12-01T10:00:00Z", described=None, **changes):
        tag = f"plugin-{plugin_id}-v{version}"
        apk = f"{plugin_id} {version} {api}".encode() * 40
        described = described if described is not None else plugin_json(plugin_id, version, apk, api, **changes)
        assets = [self.asset(tag, f"{plugin_id}.apk", apk, **(apk_fields or {})),
                  self.asset(tag, f"{plugin_id}.apk.sha256", b"sum\n"),
                  self.asset(tag, "plugin.json", json.dumps(described).encode()),
                  self.asset(tag, "LICENSE.md", b"licence\n")]
        self.releases.append({"tag_name": tag, "name": title if title is not None else f"plugin-{plugin_id}: v{version}",
                              "draft": draft, "prerelease": prerelease, "published_at": None if draft else published,
                              "html_url": f"https://github.com/{REPO}/releases/tag/{tag}", "assets": assets})
        return self

    def app(self, version, prerelease=False):
        tag = f"v{version}"
        self.releases.append({"tag_name": tag, "name": tag, "draft": False, "prerelease": prerelease,
                              "published_at": "2026-11-30T10:00:00Z", "html_url": f"https://github.com/{REPO}/releases/tag/{tag}",
                              "assets": [self.asset(tag, "NZStreamer.apk", b"app")]})
        return self

    def lay_out(self, folder, pages=2):
        os.makedirs(os.path.join(folder, "assets"), exist_ok=True)
        with open(os.path.join(folder, "releases.json"), "w") as f:
            # Pages one after another, as gh api --paginate prints them.
            size = max(1, -(-len(self.releases) // pages))
            for start in range(0, max(1, len(self.releases)), size):
                f.write(json.dumps(self.releases[start:start + size], indent=1) + "\n")
        for asset_id, data in self.assets.items():
            with open(os.path.join(folder, "assets", str(asset_id)), "wb") as f:
                f.write(data)


def golden_releases():
    """The releases behind the golden list: two plugins, an update, a newer API, a withdrawn version and what is left out."""
    return (Releases()
            .app("0.2.0")
            .plugin("kestrel", "1.0.0", published="2026-11-25T09:00:00Z")
            .plugin("kestrel", "1.0.1", published="2026-11-27T09:00:00Z")
            .plugin("kestrel", "1.1.0", api="1.1", published="2026-11-29T09:00:00Z")
            .plugin("kestrel", "1.0.2", title="Withdrawn: plugin-kestrel: v1.0.2", published="2026-11-28T09:00:00Z")
            .plugin("quillmere", "0.3.0", published="2026-11-26T09:00:00Z")
            .plugin("quillmere", "0.4.0", draft=True)
            .plugin("quillmere", "0.3.1", prerelease=False))


class PluginIndexTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="plugin-index-")
        self.addCleanup(shutil.rmtree, self.dir, ignore_errors=True)
        self.bin = os.path.join(self.dir, "bin")
        self.write("bin/gh", FAKE_GH, executable=True)
        self.signer = self.write("beta-cert.sha256", DIGEST + "\n")
        self.log = os.path.join(self.dir, "calls.log")
        self.env = {"PATH": self.bin + os.pathsep + os.environ["PATH"], "FAKE_LOG": self.log,
                    "FAKE_DIR": os.path.join(self.dir, "gh"), "FAKE_REPO": REPO}

    def write(self, relative, content, executable=False, binary=False):
        path = os.path.join(self.dir, relative)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb" if binary else "w") as f:
            f.write(content)
        if executable:
            os.chmod(path, os.stat(path).st_mode | stat.S_IXUSR)
        return path

    def run_script(self, *args):
        return subprocess.run([sys.executable, "-I", "-B", SCRIPT, *args], cwd=self.dir, env=self.env,
                              capture_output=True, text=True, timeout=60)

    def build(self, releases, *extra, out=None, now=NOW, pages=2):
        releases.lay_out(os.path.join(self.dir, "gh"), pages)
        out = out or os.path.join(self.dir, "index.json")
        if os.path.exists(out):
            os.remove(out)
        open(self.log, "w").close()
        done = self.run_script("build", "--repo", REPO, "--signer", self.signer, "--out", out, "--now", now, *extra)
        return done, out

    def built(self, releases, *extra, **options):
        done, out = self.build(releases, *extra, **options)
        self.assertEqual(0, done.returncode, done.stdout + done.stderr)
        with open(out, "rb") as f:
            raw = f.read()
        return json.loads(raw), raw

    def assertFails(self, done, out, *words):
        self.assertEqual(1, done.returncode, done.stdout + done.stderr)
        for word in words:
            self.assertIn(word, done.stderr)
        self.assertFalse(os.path.exists(out), "a list was written although a check failed")

    def asked(self):
        with open(self.log) as f:
            return f.read().splitlines()

    # The list.

    def test_a_published_prerelease_is_listed_with_what_its_plugin_json_says(self):
        releases = Releases().plugin("kestrel", "1.0.3")
        apk = releases.assets[min(releases.assets)]
        index, _ = self.built(releases)
        tag = "plugin-kestrel-v1.0.3"
        self.assertEqual({
            "format": 1, "generated": NOW,
            "plugins": [{
                "id": "kestrel", "package": "app.nzstreamer.plugin.kestrel", "name": "Kestrel", "summary": WORDS["kestrel"][1],
                "source": f"https://github.com/{REPO}/tree/main/plugins/kestrel", "withdrawn": [],
                "versions": [{
                    "versionCode": 1000300, "versionName": "1.0.3", "api": "1.0", "libs": LIBS_MAP, "minSdk": 26,
                    "targetSdk": 36, "url": f"https://github.com/{REPO}/releases/download/{tag}/kestrel.apk",
                    "size": len(apk), "sha256": hashlib.sha256(apk).hexdigest(), "released": "2026-12-01",
                    "changes": f"https://github.com/{REPO}/releases/tag/{tag}",
                }],
            }],
        }, index)
        # The signer is checked here alone: the app never trusts the list's word for it (C19).
        self.assertNotIn("signer", json.dumps(index))
        plugin_json_asset = next(a["id"] for a in releases.releases[0]["assets"] if a["name"] == "plugin.json")
        self.assertEqual([f"gh api --paginate repos/{REPO}/releases?per_page=100",
                          f"gh api -H Accept: application/octet-stream repos/{REPO}/releases/assets/{plugin_json_asset}"],
                         self.asked())

    def test_a_full_release_a_draft_and_a_foreign_tag_are_left_out(self):
        releases = (Releases().plugin("kestrel", "1.0.0")
                    .plugin("kestrel", "1.1.0", prerelease=False)
                    .plugin("kestrel", "1.2.0", draft=True)
                    .app("0.2.0").app("0.3.0", prerelease=True))
        for foreign in ("kestrel-v1.3.0", "plugin-kestrel-1.3.0", "plugin-Kestrel-v1.3.0", "plugin-kestrel-v1.3",
                        "plugin-kestrel-v01.3.0", "plugin-true-v1.0.0", "plugin-kestrel-v1.3.0-rc.1"):
            releases.releases.append(dict(releases.releases[0], tag_name=foreign, assets=[]))
        index, _ = self.built(releases)
        self.assertEqual([("kestrel", [1000000])],
                         [(p["id"], [v["versionCode"] for v in p["versions"]]) for p in index["plugins"]])
        # Only the kept release's plugin.json was asked for; a plugin's full release is named as a warning.
        self.assertEqual(2, len(self.asked()))
        done, _ = self.build(releases)
        self.assertIn("plugin-kestrel-v1.1.0", done.stderr)

    def test_a_withdrawn_title_is_recorded_and_its_version_left_out(self):
        releases = (Releases().plugin("kestrel", "1.0.0").plugin("kestrel", "1.0.1", title="Withdrawn: plugin-kestrel: v1.0.1")
                    .plugin("quillmere", "0.3.0", title="Withdrawn: a bad build"))
        index, _ = self.built(releases)
        kestrel, quillmere = index["plugins"]
        self.assertEqual([1000100], kestrel["withdrawn"])
        self.assertEqual([1000000], [v["versionCode"] for v in kestrel["versions"]])
        # A plugin whose every version is withdrawn stays listed, so the phones it is on turn it off.
        self.assertEqual(("quillmere", "Quillmere", [30000], []),
                         (quillmere["id"], quillmere["name"], quillmere["withdrawn"], quillmere["versions"]))

    def test_the_newest_version_for_each_api_is_kept(self):
        releases = (Releases().plugin("kestrel", "1.0.0").plugin("kestrel", "1.1.0").plugin("kestrel", "1.2.0", api="1.1")
                    .plugin("kestrel", "2.0.0", api="2.0").plugin("kestrel", "1.2.1", api="1.1", title="Withdrawn: v1.2.1"))
        index, _ = self.built(releases)
        plugin, = index["plugins"]
        self.assertEqual([(2000000, "2.0"), (1020000, "1.1"), (1010000, "1.0")],
                         [(v["versionCode"], v["api"]) for v in plugin["versions"]])
        self.assertEqual([1020100], plugin["withdrawn"])

    def test_a_size_digest_signer_or_address_that_disagrees_fails_naming_the_release(self):
        tag = "plugin-kestrel-v1.0.0"
        cases = {
            "the size": dict(apk_fields={"size": 1}),
            "the digest": dict(apk_fields={"digest": "sha256:" + "0" * 64}),
            "the address": dict(apk_fields={"browser_download_url": "https://github.com/someone/else/kestrel.apk"}),
            "the signer": dict(signer=OTHER_DIGEST),
            "its id": dict(id="quillmere"),
            "its package": dict(package="app.nzstreamer.plugin.other"),
            "its versionName": dict(versionName="1.0.1"),
            "its versionCode": dict(versionCode=1000001),
            "its url": dict(url=f"https://github.com/{REPO}/releases/download/{tag}/other.apk"),
            "its format": dict(format=2),
            "its api": dict(api="1"),
            "its libs": dict(libs="kotlin-stdlib=2.3.21"),
            "its name": dict(name="K" * 41),
            "its summary": dict(summary="S" * 201),
            "a line break in its name": dict(name="Kes\ntrel"),
            "a direction override in its summary": dict(summary="Invented‮text"),
            "its minSdk": dict(minSdk="26"),
        }
        for case, change in cases.items():
            with self.subTest(case):
                done, out = self.build(Releases().plugin("kestrel", "1.0.0", **change))
                self.assertFails(done, out, tag)
                self.assertNotIn(OTHER_DIGEST, done.stdout + done.stderr)

    def test_a_missing_digest_or_file_fails_naming_it(self):
        releases = Releases().plugin("kestrel", "1.0.0")
        del releases.releases[0]["assets"][0]["digest"]
        done, out = self.build(releases)
        self.assertFails(done, out, "kestrel.apk", "plugin-kestrel-v1.0.0", "digest")
        for name in ("kestrel.apk", "plugin.json"):
            with self.subTest(name):
                releases = Releases().plugin("kestrel", "1.0.0")
                releases.releases[0]["assets"] = [a for a in releases.releases[0]["assets"] if a["name"] != name]
                done, out = self.build(releases)
                self.assertFails(done, out, name, "plugin-kestrel-v1.0.0")

    def test_a_failed_read_of_the_releases_fails(self):
        self.env["FAKE_GH_FAILS"] = "1"
        done, out = self.build(Releases().plugin("kestrel", "1.0.0"))
        self.assertFails(done, out, "releases")

    def test_the_output_is_byte_stable_and_the_one_the_app_reads(self):
        _, first = self.built(golden_releases(), pages=1)
        _, again = self.built(golden_releases(), pages=3)
        reordered = golden_releases()
        reordered.releases.reverse()
        _, reversed_order = self.built(reordered)
        self.assertEqual(first, again)
        self.assertEqual(first, reversed_order)
        with open(GOLDEN, "rb") as f:
            self.assertEqual(f.read(), first, "testdata/plugin-index.json is what the app's PluginIndexTest reads: "
                                              "write it again from this test's output when the format changes on purpose")
        self.assertTrue(first.endswith(b"\n"))
        self.assertEqual(json.dumps(json.loads(first), indent=2, sort_keys=True, ensure_ascii=False) + "\n", first.decode())

    def test_generated_stays_while_nothing_changes_and_never_goes_back(self):
        _, first = self.built(golden_releases(), now="2026-12-02T08:30:00Z")
        current = self.write("current.json", first, binary=True)
        _, same = self.built(golden_releases(), "--current", current, now="2026-12-09T08:30:00Z")
        self.assertEqual(first, same)
        withdrawn = golden_releases().plugin("quillmere", "0.3.2", title="Withdrawn: v0.3.2")
        changed, _ = self.built(withdrawn, "--current", current, now="2026-12-09T08:30:00Z")
        self.assertEqual("2026-12-09T08:30:00Z", changed["generated"])
        self.assertEqual([30200], changed["plugins"][1]["withdrawn"])
        # A clock behind the list's own time keeps that time: an app ignores a list older than the one it has.
        behind, _ = self.built(withdrawn, "--current", current, now="2026-11-01T00:00:00Z")
        self.assertEqual("2026-12-02T08:30:00Z", behind["generated"])
        # A current list that doesn't read is no list: the time is now.
        unreadable = self.write("unreadable.json", "not json")
        fresh, _ = self.built(golden_releases(), "--current", unreadable, now="2026-12-09T08:30:00Z")
        self.assertEqual("2026-12-09T08:30:00Z", fresh["generated"])

    def test_the_caps(self):
        many = Releases()
        for number in range(51):
            letters = "".join(chr(ord("a") + int(d)) for d in f"{number:02d}")
            many.plugin(f"kestrel{letters}", "1.0.0")
        done, out = self.build(many)
        self.assertFails(done, out, "51 plugins", "50")
        versions = Releases()
        for minor in range(21):
            versions.plugin("kestrel", f"1.{minor}.0", api=f"1.{minor}")
        done, out = self.build(versions)
        self.assertFails(done, out, "kestrel", "21 versions", "20")
        # Fifty plugins of six versions with long library lists pass the counts but not the 256 KiB the app reads.
        large = Releases()
        long_libs = {f"invented-library-{n:03d}": "1.0.0" for n in range(40)}
        for number in range(50):
            letters = "".join(chr(ord("a") + int(d)) for d in f"{number:02d}")
            for minor in range(6):
                large.plugin(f"kestrel{letters}", f"1.{minor}.0", api=f"1.{minor}", libs=long_libs)
        done, out = self.build(large)
        self.assertFails(done, out, "256 KiB")
        exactly = Releases()
        for minor in range(20):
            exactly.plugin("kestrel", f"1.{minor}.0", api=f"1.{minor}")
        index, _ = self.built(exactly)
        self.assertEqual(20, len(index["plugins"][0]["versions"]))

    def test_bad_arguments_are_refused(self):
        for case, args in {
            "no command": [],
            "an unknown command": ["publish"],
            "no repository": ["build", "--signer", self.signer, "--out", "x.json"],
            "a repository that is no owner/name": ["build", "--repo", "skjaere", "--signer", self.signer, "--out", "x.json"],
            "a signer file with no digest": ["build", "--repo", REPO, "--signer", self.write("bad", "nope\n"),
                                             "--out", "x.json"],
            "a time that is no UTC time": ["build", "--repo", REPO, "--signer", self.signer, "--out", "x.json",
                                           "--now", "yesterday"],
        }.items():
            with self.subTest(case):
                done = self.run_script(*args)
                self.assertEqual(2, done.returncode, done.stdout + done.stderr)
                self.assertFalse(os.path.exists(os.path.join(self.dir, "x.json")))


class DescribeTest(unittest.TestCase):
    """plugin_index.py describe: the release's plugin.json, from the staged APK's own bytes and the plugin's listing."""

    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="plugin-describe-")
        self.addCleanup(shutil.rmtree, self.dir, ignore_errors=True)
        self.apk_bytes = os.urandom(3000)
        self.apk = self.write("staged/kestrel.apk", self.apk_bytes, binary=True)
        self.listing = self.write("plugins/kestrel/listing.json",
                                  json.dumps({"name": "Kestrel", "summary": WORDS["kestrel"][1]}) + "\n")
        self.sdk = os.path.join(self.dir, "sdk")
        for version, body in (("34.0.0", OLD_TOOL), ("36.0.0", FAKE_TOOL)):
            for tool in ("apksigner", "aapt2"):
                self.write(f"sdk/build-tools/{version}/{tool}", body, executable=True)
        self.log = os.path.join(self.dir, "tools.log")
        self.certs = self.write("certs.txt", certs(DIGEST))
        self.badging = self.write("badging.txt", badging())
        self.out = os.path.join(self.dir, "staged", "plugin.json")
        self.env = {"PATH": os.environ["PATH"], "ANDROID_HOME": self.sdk, "FAKE_LOG": self.log,
                    "FAKE_CERTS": self.certs, "FAKE_BADGING": self.badging}

    def write(self, relative, content, executable=False, binary=False):
        path = os.path.join(self.dir, relative)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb" if binary else "w") as f:
            f.write(content)
        if executable:
            os.chmod(path, os.stat(path).st_mode | stat.S_IXUSR)
        return path

    def describe(self, *extra, plugin_id="kestrel", tag="plugin-kestrel-v1.0.3"):
        if os.path.exists(self.out):
            os.remove(self.out)
        args = ["describe", "--id", plugin_id, "--tag", tag, "--repo", REPO, "--apk", self.apk, "--listing", self.listing,
                "--out", self.out, *extra]
        return subprocess.run([sys.executable, "-I", "-B", SCRIPT, *args], cwd=self.dir, env=self.env,
                              capture_output=True, text=True, timeout=60)

    def test_plugin_json_says_what_the_apk_is(self):
        done = self.describe()
        self.assertEqual(0, done.returncode, done.stdout + done.stderr)
        with open(self.out) as f:
            described = json.load(f)
        self.assertEqual(plugin_json("kestrel", "1.0.3", self.apk_bytes), described)
        with open(self.log) as f:
            self.assertEqual([f"aapt2 dump badging --include-meta-data {self.apk}", f"apksigner verify --print-certs {self.apk}"],
                             f.read().splitlines())

    def test_build_tools_37s_signer_lines_are_read(self):
        self.write("certs.txt", certs37(DIGEST, schemes=("V2", "V3")))
        done = self.describe()
        self.assertEqual(0, done.returncode, done.stdout + done.stderr)

    def test_what_the_apk_or_its_listing_says_wrongly_is_refused(self):
        cases = {
            "another package": dict(badging=badging(package="app.nzstreamer.plugin.other")),
            "another id": dict(badging=badging(plugin_id="other")),
            "another versionName": dict(badging=badging(name="1.0.4")),
            "another versionCode": dict(badging=badging(code="1000301")),
            "an API that is no two numbers": dict(badging=badging(minor="0.5")),
            "no API minor": dict(badging=badging(minor=None)),
            "libraries that are no list": dict(badging=badging(libs="kotlin-stdlib")),
            "a library named twice": dict(badging=badging(libs="slf4j-api=2.0.20,slf4j-api=2.0.21")),
            "no minSdk": dict(badging=badging(min_sdk=None)),
            "two signers": dict(certs=certs(DIGEST, OTHER_DIGEST)),
            "no signer it can read": dict(certs="Signer certificate SHA-256 digest is " + DIGEST + "\n"),
            "a listing with no summary": dict(listing={"name": "Kestrel"}),
            "a listing with a name of 41": dict(listing={"name": "K" * 41, "summary": ""}),
            "a listing with a blank name": dict(listing={"name": "  ", "summary": ""}),
            "a listing with a control character": dict(listing={"name": "Kestrel", "summary": "a\u0007b"}),
            "a listing with more than its words": dict(listing={"name": "Kestrel", "summary": "", "icon": "x"}),
        }
        for case, change in cases.items():
            with self.subTest(case):
                self.write("badging.txt", change.get("badging", badging()))
                self.write("certs.txt", change.get("certs", certs(DIGEST)))
                self.write("plugins/kestrel/listing.json",
                           json.dumps(change.get("listing", {"name": "Kestrel", "summary": WORDS["kestrel"][1]})))
                done = self.describe()
                self.assertEqual(1, done.returncode, done.stdout + done.stderr)
                self.assertFalse(os.path.exists(self.out))
                self.assertNotIn(OTHER_DIGEST, done.stdout + done.stderr)

    def test_bad_arguments_are_refused(self):
        for case, (plugin_id, tag) in {
            "an id outside the rule": ("Kestrel", "plugin-Kestrel-v1.0.3"),
            "the id true": ("true", "plugin-true-v1.0.3"),
            "a tag of another plugin": ("kestrel", "plugin-quillmere-v1.0.3"),
            "an app tag": ("kestrel", "v1.0.3"),
            "a tag with a suffix": ("kestrel", "plugin-kestrel-v1.0.3-rc.1"),
        }.items():
            with self.subTest(case):
                done = self.describe(plugin_id=plugin_id, tag=tag)
                self.assertEqual(2, done.returncode, done.stdout + done.stderr)
                self.assertFalse(os.path.exists(self.out))
        self.write("staged/plugin.json", "{}")
        done = subprocess.run([sys.executable, "-I", "-B", SCRIPT, "describe", "--id", "kestrel", "--tag",
                               "plugin-kestrel-v1.0.3", "--repo", REPO, "--apk", self.apk, "--listing", self.listing,
                               "--out", self.out], cwd=self.dir, env=self.env, capture_output=True, text=True, timeout=60)
        self.assertEqual(2, done.returncode, done.stdout + done.stderr)


class BuildsTest(unittest.TestCase):
    """plugin_index.py builds: the list's versions of one plugin API major, which CI checks against the app's build."""

    RELEASES = f"https://github.com/{REPO}/releases/download/"

    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="plugin-builds-")
        self.addCleanup(shutil.rmtree, self.dir, ignore_errors=True)

    def builds(self, index, major="1"):
        path = os.path.join(self.dir, "index.json")
        with open(path, "w") as f:
            f.write(index if isinstance(index, str) else json.dumps(index))
        return subprocess.run([sys.executable, "-I", "-B", SCRIPT, "builds", "--list", path, "--releases", self.RELEASES,
                               "--api-major", major], cwd=self.dir, env={"PATH": os.environ["PATH"]},
                              capture_output=True, text=True, timeout=60)

    def test_each_build_of_the_major_is_a_line(self):
        with open(GOLDEN) as f:
            golden = json.load(f)
        done = self.builds(golden)
        self.assertEqual(0, done.returncode, done.stderr)
        lines = [line.split("\t") for line in done.stdout.splitlines()]
        self.assertEqual([["kestrel", "1010000"], ["kestrel", "1000100"], ["quillmere", "30000"]],
                         [line[:2] for line in lines])
        for plugin_id, code, url, size, sha in lines:
            version = next(v for p in golden["plugins"] if p["id"] == plugin_id for v in p["versions"]
                           if v["versionCode"] == int(code))
            self.assertEqual([version["url"], str(version["size"]), version["sha256"]], [url, size, sha])
        done = self.builds(golden, major="2")
        self.assertEqual(("", 0), (done.stdout, done.returncode))

    def test_an_entry_outside_the_apps_rules_fails(self):
        with open(GOLDEN) as f:
            golden = json.load(f)
        tag = "plugin-kestrel-v1.1.0"
        for case, change in {
            "another repository": {"url": f"https://github.com/someone/else/releases/download/{tag}/kestrel.apk"},
            "a dot-dot segment": {"url": self.RELEASES + f"{tag}/../{tag}/kestrel.apk"},
            "http": {"url": "http" + self.RELEASES[5:] + f"{tag}/kestrel.apk"},
            "another plugin's file": {"url": self.RELEASES + "plugin-quillmere-v1.1.0/quillmere.apk"},
            "an upper-case hash": {"sha256": "AB" * 32},
            "no size": {"size": 0},
        }.items():
            with self.subTest(case):
                broken = json.loads(json.dumps(golden))
                broken["plugins"][0]["versions"][0].update(change)
                done = self.builds(broken)
                self.assertEqual(1, done.returncode, done.stdout + done.stderr)
                self.assertIn("kestrel", done.stderr)
        for case, text in {"no JSON": "not json", "a later format": json.dumps(dict(golden, format=2)),
                           "an id outside the rule": json.dumps(dict(golden, plugins=[dict(golden["plugins"][0], id="../x")]))}.items():
            with self.subTest(case):
                self.assertEqual(1, self.builds(text).returncode)


def certs(*digests):
    lines = []
    for number, digest in enumerate(digests, 1):
        lines += [f"Signer #{number} certificate DN: CN=Invented Key {number}",
                  f"Signer #{number} certificate SHA-256 digest: {digest}",
                  f"Signer #{number} certificate SHA-1 digest: {'0' * 40}"]
    return "\n".join(lines) + "\n"


def certs37(*digests, schemes=("V2",)):
    lines = []
    for scheme in schemes:
        for number, digest in enumerate(digests, 1):
            lines += [f"{scheme} Signer: certificate DN: CN=Invented Key {number}",
                      f"{scheme} Signer: certificate SHA-256 digest: {digest}"]
    return "\n".join(lines) + "\n"


def badging(package="app.nzstreamer.plugin.kestrel", code="1000300", name="1.0.3", plugin_id="kestrel", major="1",
            minor="0", libs=LIBS, min_sdk="26"):
    lines = [f"package: name='{package}' versionCode='{code}' versionName='{name}' platformBuildVersionName='16' "
             "platformBuildVersionCode='36' compileSdkVersion='36' compileSdkVersionCodename='16'"]
    if min_sdk is not None:
        lines.append(f"minSdkVersion:'{min_sdk}'")
    lines += ["targetSdkVersion:'36'", "application: label='' icon=''",
              f"meta-data: name='nzs.plugin.id' value='{plugin_id}'",
              f"meta-data: name='nzs.plugin.entry' value='app.nzstreamer.plugin.{plugin_id}.KestrelPlugin'",
              f"meta-data: name='nzs.plugin.api.major' value='{major}'"]
    if minor is not None:
        lines.append(f"meta-data: name='nzs.plugin.api.minor' value='{minor}'")
    lines += [f"meta-data: name='nzs.plugin.libs' value='{libs}'", "supports-screens: 'small' 'normal' 'large' 'xlarge'"]
    return "\n".join(lines) + "\n"


if __name__ == "__main__":
    unittest.main()
