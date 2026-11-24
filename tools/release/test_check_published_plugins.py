#!/usr/bin/env python3
"""Tests of tools/release/check-published-plugins.sh, CI's step "Published plugins against this build".

Run: python3 -I -B -m unittest discover -s tools/release -p 'test_*.py'. Each test lays out a repository's top folder in
a temp folder, the app's PluginSource.kt copied from this tree (so the address read is the app's own), and puts a fake
curl on the PATH that answers from files the test sets, a fake sleep that waits for nothing, a fake SDK's apksigner and a
fake ./gradlew, each logging what it was given. The plugins and their bytes are invented. Nothing here reaches GitHub.
"""
import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "check-published-plugins.sh")
SOURCE = os.path.join("app", "src", "main", "kotlin", "io", "skjaere", "usenet", "app", "plugins", "PluginSource.kt")
REAL_SOURCE = os.path.join(os.path.dirname(os.path.dirname(HERE)), SOURCE)
DIGEST = "ab" * 32
OTHER_DIGEST = "cd" * 32
RELEASES = "https://github.com/skjaere/nzstreamer/releases/download/"

# Answers from $FAKE_DIR/answers.tsv (address, status, file, Retry-After): an address's first line for its first call,
# its second for the second, its last for every one after; no line, or a status of 000, is no answer. It writes the file
# to -o, the head's Retry-After to -D and the status for -w, as curl does, and logs its arguments one to a line, a blank
# line after each call.
FAKE_CURL = r"""#!/bin/bash
printf '%s\n' "$@" "" >> "$FAKE_DIR/curl.log"
out='' write='' fail=false address='' head=''
while (($#)); do
    case $1 in
        -o) out=$2; shift 2 ;;
        -w) write=$2; shift 2 ;;
        -D) head=$2; shift 2 ;;
        -f | --fail) fail=true; shift ;;
        --) address=$2; shift 2 ;;
        *) shift ;;
    esac
done
calls=$(grep -cxF -- "$address" "$FAKE_DIR/curl.log")
answer=$(awk -F '\t' -v a="$address" -v n="$calls" '$1 == a { last = $2 "|" $3 "|" $4; if (++seen == n) { print last; found = 1; exit } }
    END { if (!found && seen) print last }' "$FAKE_DIR/answers.tsv")
IFS='|' read -r status file retry <<<"$answer"
if [[ -z $answer || $status == 000 ]]; then
    echo "curl: (6) Could not resolve host" >&2
    [[ -n $write ]] && printf '000'
    exit 6
fi
if ((status >= 400)) && $fail; then
    echo "curl: (22) The requested URL returned error: $status" >&2
    exit 22
fi
if [[ -n $head ]]; then
    { printf 'HTTP/1.1 %s Answer\r\n' "$status"; [[ -z $retry ]] || printf 'Retry-After: %s\r\n' "$retry"; printf '\r\n'; } > "$head"
fi
[[ -n $file && -n $out ]] && cp "$FAKE_DIR/$file" "$out"
[[ -n $write ]] && printf '%s' "$status"
exit 0
"""

# A sleep that waits for nothing: it logs how long it was asked to wait, so a retry's wait is seen and costs no time.
FAKE_SLEEP = r"""#!/bin/bash
printf '%s\n' "$*" >> "$FAKE_DIR/sleep.log"
"""

FAKE_GRADLEW = r"""#!/bin/bash
printf 'gradlew %s\n' "$*" >> "$FAKE_DIR/gradle.log"
for argument in "$@"; do
    [[ $argument == -PpluginLinks=* ]] && ls "${argument#-PpluginLinks=}" >> "$FAKE_DIR/gradle.log"
done
exit "${FAKE_GRADLE_EXIT:-0}"
"""

FAKE_APKSIGNER = r"""#!/bin/bash
printf 'apksigner %s\n' "$*" >> "$FAKE_DIR/apksigner.log"
[[ $1 == verify && $2 == --print-certs && $# == 3 ]] || exit 98
cat "$FAKE_DIR/certs.txt"
"""


def certs(*digests):
    return "".join(f"Signer #{n} certificate DN: CN=Invented Key {n}\nSigner #{n} certificate SHA-256 digest: {d}\n"
                   for n, d in enumerate(digests, 1))


def list_address():
    with open(REAL_SOURCE, encoding="utf-8") as f:
        return re.search(r'const val LIST = "([^"]+)"', f.read()).group(1)


class CheckPublishedPluginsTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="published-plugins-")
        self.addCleanup(shutil.rmtree, self.root, ignore_errors=True)
        self.fake = os.path.join(self.root, "fake")
        os.makedirs(self.fake)
        os.makedirs(os.path.dirname(os.path.join(self.root, SOURCE)))
        shutil.copyfile(REAL_SOURCE, os.path.join(self.root, SOURCE))
        self.write("core/plugin/plugin-api.txt", "1.0\n")
        self.write("tools/beta-cert.sha256", DIGEST + "\n")
        self.write("gradlew", FAKE_GRADLEW, executable=True)
        self.write("fake/bin/curl", FAKE_CURL, executable=True)
        self.write("fake/bin/sleep", FAKE_SLEEP, executable=True)
        self.write("sdk/build-tools/34.0.0/apksigner", "#!/bin/bash\nexit 97\n", executable=True)
        self.write("sdk/build-tools/36.0.0/apksigner", FAKE_APKSIGNER, executable=True)
        self.write("fake/certs.txt", certs(DIGEST))
        self.answers = []
        self.env = {"PATH": os.path.join(self.fake, "bin") + os.pathsep + os.environ["PATH"], "FAKE_DIR": self.fake,
                    "ANDROID_HOME": os.path.join(self.root, "sdk"), "GITHUB_ACTIONS": "true",
                    "TMPDIR": os.path.join(self.root, "tmp")}
        os.makedirs(self.env["TMPDIR"])

    def write(self, relative, content, executable=False, binary=False):
        path = os.path.join(self.root, relative)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb" if binary else "w") as f:
            f.write(content)
        if executable:
            os.chmod(path, os.stat(path).st_mode | stat.S_IXUSR)
        return path

    def answer(self, address, status, data=None, retry_after=""):
        name = ""
        if data is not None:
            name = f"answer-{len(self.answers)}"
            self.write(f"fake/{name}", data, binary=True)
        self.answers.append(f"{address}\t{status}\t{name}\t{retry_after}")
        self.write("fake/answers.tsv", "".join(line + "\n" for line in self.answers))

    def listed(self, builds, generated="2026-12-02T08:30:00Z", fmt=1):
        """A list of (id, version, api) builds, each APK answered at its address; the list answered at the app's."""
        plugins = {}
        for plugin_id, version, api in builds:
            data = f"{plugin_id} {version}".encode() * 50
            major, minor, patch = (int(p) for p in version.split("."))
            url = f"{RELEASES}plugin-{plugin_id}-v{version}/{plugin_id}.apk"
            self.answer(url, 200, data)
            plugins.setdefault(plugin_id, []).append({
                "versionCode": ((major * 100 + minor) * 100 + patch) * 100, "versionName": version, "api": api,
                "libs": {}, "minSdk": 26, "targetSdk": 36, "url": url, "size": len(data),
                "sha256": hashlib.sha256(data).hexdigest()})
        index = {"format": fmt, "generated": generated, "plugins": [
            {"id": plugin_id, "package": f"app.nzstreamer.plugin.{plugin_id}", "name": plugin_id.title(), "summary": "",
             "withdrawn": [], "versions": versions} for plugin_id, versions in sorted(plugins.items())]}
        self.answer(list_address(), 200, json.dumps(index).encode())
        return index

    def run_check(self, *args):
        return subprocess.run(["bash", SCRIPT, *args], cwd=self.root, env=self.env, capture_output=True, text=True,
                              timeout=60)

    def forget(self, *logs):
        """The fakes' logs named emptied, as before the script first ran."""
        for log in logs:
            if os.path.exists(os.path.join(self.fake, log)):
                os.remove(os.path.join(self.fake, log))

    def calls(self, log):
        path = os.path.join(self.fake, log)
        if not os.path.exists(path):
            return []
        with open(path) as f:
            text = f.read()
        return [call.splitlines() for call in text.split("\n\n") if call] if log == "curl.log" else text.splitlines()

    def test_a_404_passes_with_a_notice(self):
        self.answer(list_address(), 404)
        done = self.run_check("--", "--max-workers=4")
        self.assertEqual(0, done.returncode, done.stdout + done.stderr)
        self.assertIn("::notice::", done.stdout)
        self.assertEqual([], self.calls("gradle.log"))
        call, = self.calls("curl.log")
        self.assertEqual(list_address(), call[-1])
        self.assertEqual("--", call[-2])
        joined = " ".join(call)
        for words in ("--proto =https", "--max-filesize 262144", "-A NZStreamer"):
            self.assertIn(words, joined)
        self.assertNotIn("-L", call)

    def test_each_build_of_the_trees_api_is_downloaded_checked_and_linked(self):
        self.listed([("kestrel", "1.1.0", "1.1"), ("kestrel", "1.0.1", "1.0"), ("kestrel", "2.0.0", "2.0"),
                     ("quillmere", "0.3.0", "1.0")])
        done = self.run_check("--", "--max-workers=4", "-Pkotlin.compiler.execution.strategy=in-process")
        self.assertEqual(0, done.returncode, done.stdout + done.stderr)
        list_call, *downloads = self.calls("curl.log")
        self.assertEqual([f"{RELEASES}plugin-kestrel-v1.1.0/kestrel.apk", f"{RELEASES}plugin-kestrel-v1.0.1/kestrel.apk",
                          f"{RELEASES}plugin-quillmere-v0.3.0/quillmere.apk"], [call[-1] for call in downloads])
        for call in downloads:
            joined = " ".join(call)
            for words in ("-L", "--proto =https", "--proto-redir =https", "--max-redirs 3", "-A NZStreamer", "-f"):
                self.assertIn(words, call if words in ("-L", "-f") else joined)
            self.assertRegex(joined, r"--max-filesize \d+")
        self.assertEqual(3, len(self.calls("apksigner.log")))
        gradle = self.calls("gradle.log")
        self.assertRegex(gradle[0], r"^gradlew --max-workers=4 -Pkotlin\.compiler\.execution\.strategy=in-process "
                                    r":app:checkPluginLinks -PpluginLinks=\S+$")
        self.assertEqual(["kestrel-1000100.apk", "kestrel-1010000.apk", "quillmere-30000.apk"], sorted(gradle[1:]))
        # The downloads went into a temp folder of the script's own, gone once it ends.
        self.assertEqual([], os.listdir(self.env["TMPDIR"]))

    def test_a_build_that_isnt_the_lists_fails_before_the_link_check(self):
        for case in ("another SHA-256", "another size", "another signer", "two signers", "no download"):
            with self.subTest(case):
                self.answers = []
                for log in ("curl.log", "gradle.log", "apksigner.log"):
                    if os.path.exists(os.path.join(self.fake, log)):
                        os.remove(os.path.join(self.fake, log))
                index = self.listed([("kestrel", "1.0.1", "1.0")])
                version = index["plugins"][0]["versions"][0]
                self.write("fake/certs.txt", certs(DIGEST))
                if case == "another SHA-256":
                    version["sha256"] = "0" * 64
                elif case == "another size":
                    version["size"] += 1
                elif case == "another signer":
                    self.write("fake/certs.txt", certs(OTHER_DIGEST))
                elif case == "two signers":
                    self.write("fake/certs.txt", certs(DIGEST, OTHER_DIGEST))
                else:
                    self.answer(version["url"], 404)
                    self.answers = [a for a in self.answers if not (a.startswith(version["url"]) and "\t200\t" in a)]
                self.answers = [a for a in self.answers if not a.startswith(list_address())]
                self.answer(list_address(), 200, json.dumps(index).encode())
                done = self.run_check()
                self.assertEqual(1, done.returncode, done.stdout + done.stderr)
                self.assertIn("kestrel", done.stderr)
                self.assertEqual([], self.calls("gradle.log"))
                self.assertNotIn(OTHER_DIGEST, done.stdout + done.stderr)

    def test_an_address_outside_the_apps_rule_fails_with_nothing_downloaded(self):
        index = self.listed([("kestrel", "1.0.1", "1.0")])
        index["plugins"][0]["versions"][0]["url"] = "https://github.com/someone/else/releases/download/plugin-kestrel-v1.0.1/kestrel.apk"
        self.answers = [a for a in self.answers if not a.startswith(list_address())]
        self.answer(list_address(), 200, json.dumps(index).encode())
        done = self.run_check()
        self.assertEqual(1, done.returncode, done.stdout + done.stderr)
        self.assertEqual(1, len(self.calls("curl.log")))
        self.assertEqual([], self.calls("gradle.log"))

    def test_a_list_that_cant_be_read_fails(self):
        for case in ("a server error", "no answer", "a later format", "no JSON"):
            with self.subTest(case):
                self.answers = []
                if case == "a server error":
                    self.answer(list_address(), 503)
                elif case == "a later format":
                    self.listed([("kestrel", "1.0.1", "1.0")], fmt=2)
                elif case == "no JSON":
                    self.answer(list_address(), 200, b"<html>")
                else:
                    self.write("fake/answers.tsv", "")
                done = self.run_check()
                self.assertEqual(1, done.returncode, done.stdout + done.stderr)
                self.assertEqual([], self.calls("gradle.log"))

    def test_a_busy_github_or_no_answer_is_asked_again_twice_at_most(self):
        # A 503, then the list; a 429 whose Retry-After says 30 s, then the list; no answer, then the list.
        for case, first, retry, waits in (("a 503", 503, "", ["5"]), ("a 429 with Retry-After", 429, "30", ["30"]),
                                          ("a Retry-After of an hour", 503, "3600", ["60"]), ("no answer", "000", "", ["5"])):
            with self.subTest(case):
                self.answers = []
                self.forget("curl.log", "gradle.log", "sleep.log")
                self.answer(list_address(), first, retry_after=retry)
                self.answer(list_address(), 404)
                done = self.run_check()
                self.assertEqual(0, done.returncode, done.stdout + done.stderr)
                self.assertIn("::notice::", done.stdout)
                self.assertEqual(2, len(self.calls("curl.log")))
                self.assertEqual(waits, self.calls("sleep.log"), "the wait: GitHub's, a minute at most, else a few seconds")
        # Busy every time: it fails, after three asks.
        self.answers = []
        self.forget("curl.log", "sleep.log")
        self.answer(list_address(), 503)
        done = self.run_check()
        self.assertEqual(1, done.returncode, done.stdout + done.stderr)
        self.assertIn("503", done.stderr)
        self.assertEqual(3, len(self.calls("curl.log")))
        self.assertEqual(["5", "10"], self.calls("sleep.log"))
        # Any other answer isn't asked again.
        self.answers = []
        self.forget("curl.log", "sleep.log")
        self.answer(list_address(), 403)
        self.assertEqual(1, self.run_check().returncode)
        self.assertEqual(1, len(self.calls("curl.log")))
        self.assertEqual([], self.calls("sleep.log"))

    def test_a_download_is_tried_again_by_curl_on_any_failure(self):
        self.listed([("kestrel", "1.0.1", "1.0")])
        self.assertEqual(0, self.run_check().returncode)
        _, download = self.calls("curl.log")
        joined = " ".join(download)
        self.assertIn("--retry 3", joined)
        self.assertIn("--retry-all-errors", download)

    def test_no_build_of_the_trees_api_passes_with_a_notice(self):
        self.listed([("kestrel", "2.0.0", "2.0")])
        done = self.run_check()
        self.assertEqual(0, done.returncode, done.stdout + done.stderr)
        self.assertIn("::notice::", done.stdout)
        self.assertEqual(1, len(self.calls("curl.log")))
        self.assertEqual([], self.calls("gradle.log"))

    def test_a_link_check_that_fails_fails(self):
        self.listed([("kestrel", "1.0.1", "1.0")])
        self.env["FAKE_GRADLE_EXIT"] = "1"
        done = self.run_check()
        self.assertEqual(1, done.returncode, done.stdout + done.stderr)
        self.assertIn("checkPluginLinks", done.stderr)

    def test_bad_arguments_are_refused(self):
        not_a_digest = self.write("not-a-digest.txt", "nope\n")
        for case, args in {"an unknown option": ["--clobber"], "a digest file with no value": ["--digest-file"],
                           "a digest file with no digest": ["--digest-file", not_a_digest],
                           "no digest file there": ["--digest-file", "missing.sha256"]}.items():
            with self.subTest(case):
                done = self.run_check(*args)
                self.assertEqual(2, done.returncode, done.stdout + done.stderr)
                self.assertFalse(os.path.exists(os.path.join(self.fake, "curl.log")))


if __name__ == "__main__":
    unittest.main()
