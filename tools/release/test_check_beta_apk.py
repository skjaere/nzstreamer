#!/usr/bin/env python3
"""Tests of tools/release/check-beta-apk.sh, the check of a built beta before a release takes it.

Run: python3 -I -B -m unittest discover -s tools/release -p 'test_*.py'. Each test lays out a build's outputs in a temp
folder: an APK of invented bytes with its metadata, checkBetaHasNoDevSettings' report on those bytes, the two tests'
results, and a fake SDK whose apksigner and aapt2 print what the test sets and log what they were given. Nothing here
reads a real APK.
"""
import hashlib
import os
import shutil
import stat
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "check-beta-apk.sh")
PACKAGE = "app.nzstreamer.beta"
DIGEST = "ab" * 32
OTHER_DIGEST = "cd" * 32
TESTS = ("io.skjaere.usenet.app.debrid.HiddenBuildTest", "io.skjaere.usenet.app.VersionCodeTest")
PASSED = "carries none of local.properties' dev settings."
READS_DEX = "  Its dex holds the applicationId (the check's control): the scan reads the dex."

# Logs how it was called, then prints the file the test set, or fails as the test asked.
FAKE_TOOL = r"""#!/bin/bash
printf '%s\n' "$(basename "$0") $*" >> "$FAKE_LOG"
case $(basename "$0") in
    apksigner)
        [[ $1 == verify && $2 == --print-certs ]] || exit 98
        [[ -z ${FAKE_UNVERIFIED:-} ]] || { echo "the fake APK does not verify" >&2; exit 1; }
        cat "$FAKE_CERTS" ;;
    aapt2)
        [[ $1 == dump && $2 == badging ]] || exit 98
        cat "$FAKE_BADGING" ;;
esac
"""
# An older build-tools folder, which the script must not use.
OLD_TOOL = "#!/bin/bash\nexit 97\n"


def certs(*digests):
    lines = []
    for number, digest in enumerate(digests, 1):
        lines += [f"Signer #{number} certificate DN: CN=Invented Key {number}",
                  f"Signer #{number} certificate SHA-256 digest: {digest}",
                  f"Signer #{number} certificate SHA-1 digest: {'0' * 40}"]
    return "\n".join(lines) + "\n"


def badging(package=PACKAGE, code="10000", name="0.1.0"):
    return (f"package: name='{package}' versionCode='{code}' versionName='{name}' platformBuildVersionName='16' "
            "platformBuildVersionCode='36' compileSdkVersion='36' compileSdkVersionCodename='16'\nsdkVersion:'26'\n")


def suite(name, tests=1, skipped=0, failures=0, errors=0):
    return (f'<?xml version="1.0" encoding="UTF-8"?>\n<testsuite name="{name}" tests="{tests}" skipped="{skipped}" '
            f'failures="{failures}" errors="{errors}" time="0.01">\n</testsuite>\n')


class CheckBetaApkTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="check-beta-apk-")
        self.addCleanup(shutil.rmtree, self.root, ignore_errors=True)
        self.out_apk = os.path.join(self.root, "app/build/outputs/apk/beta")
        os.makedirs(self.out_apk)
        self.apk_bytes = os.urandom(4096)
        self.write("app/build/outputs/apk/beta/app-beta.apk", self.apk_bytes, binary=True)
        self.write("app/build/outputs/apk/beta/output-metadata.json",
                   '{"version": 3, "elements": [{"type": "SINGLE", "outputFile": "app-beta.apk"}]}\n')
        self.report(hashlib.sha256(self.apk_bytes).hexdigest())
        for name in TESTS:
            self.write(f"app/build/test-results/testBetaUnitTest/TEST-{name}.xml", suite(name))
        self.digest_file = self.write("cert.sha256", DIGEST + "\n")
        self.sdk = os.path.join(self.root, "sdk")
        for version, body in (("34.0.0", OLD_TOOL), ("35.0.0", FAKE_TOOL)):
            for tool in ("apksigner", "aapt2"):
                path = self.write(f"sdk/build-tools/{version}/{tool}", body)
                os.chmod(path, os.stat(path).st_mode | stat.S_IXUSR)
        self.log = os.path.join(self.root, "tools.log")
        self.certs = self.write("certs.txt", certs(DIGEST))
        self.badging = self.write("badging.txt", badging())
        self.out = os.path.join(self.root, "staged")
        self.env = {"PATH": os.environ["PATH"], "ANDROID_HOME": self.sdk, "FAKE_LOG": self.log,
                    "FAKE_CERTS": self.certs, "FAKE_BADGING": self.badging}

    def write(self, relative, content, binary=False):
        path = os.path.join(self.root, relative)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb" if binary else "w") as f:
            f.write(content)
        return path

    def report(self, sha, first=PASSED, looked="  local.properties sets none of them: nothing to look for.",
               dex=READS_DEX):
        lines = [f"checkBetaHasNoDevSettings: app-beta.apk {first}", looked, dex, f"  SHA-256: {sha}"]
        self.write("app/build/reports/checkBetaHasNoDevSettings.txt", "\n".join(lines) + "\n")

    def run_check(self, *extra, code="10000", version="0.1.0", digest_file=None, out=None):
        args = ["--digest-file", digest_file or self.digest_file, "--code", code, "--version", version,
                "--out", out or self.out, *extra]
        return subprocess.run(["bash", SCRIPT, *args], cwd=self.root, env=self.env, capture_output=True, text=True,
                              timeout=60)

    def assertPasses(self, done):
        self.assertEqual(0, done.returncode, done.stdout + done.stderr)

    def assertFails(self, done, *words):
        self.assertEqual(1, done.returncode, done.stdout + done.stderr)
        for word in words:
            self.assertIn(word, done.stderr)

    def test_a_good_beta_passes_and_only_its_staged_copy_is_read(self):
        done = self.run_check()
        self.assertPasses(done)
        staged = os.path.join(self.out, "NZStreamer-Beta-0.1.0.apk")
        with open(staged, "rb") as f:
            self.assertEqual(self.apk_bytes, f.read())
        with open(staged + ".sha256") as f:
            self.assertEqual(f"{hashlib.sha256(self.apk_bytes).hexdigest()}  NZStreamer-Beta-0.1.0.apk\n", f.read())
        self.assertEqual({"NZStreamer-Beta-0.1.0.apk", "NZStreamer-Beta-0.1.0.apk.sha256"}, set(os.listdir(self.out)))
        with open(self.log) as f:
            calls = f.read().splitlines()
        self.assertEqual([f"apksigner verify --print-certs {staged}", f"aapt2 dump badging {staged}"], calls)
        self.assertIn("NZStreamer-Beta-0.1.0.apk", done.stdout)

    def test_a_local_beta_carries_its_code_in_its_name(self):
        self.write("badging.txt", badging(code="10001", name="0.1.0-beta.10001"))
        self.assertPasses(self.run_check(code="10001", version="0.1.0-beta.10001"))
        self.assertTrue(os.path.isfile(os.path.join(self.out, "NZStreamer-Beta-0.1.0-beta.10001.apk")))

    def test_another_key_fails(self):
        self.write("certs.txt", certs(OTHER_DIGEST))
        done = self.run_check()
        self.assertFails(done, "signed")
        self.assertNotIn(OTHER_DIGEST, done.stdout + done.stderr)

    def test_a_second_signer_fails(self):
        self.write("certs.txt", certs(DIGEST, OTHER_DIGEST))
        self.assertFails(self.run_check(), "2 signers")

    def test_an_apk_apksigner_does_not_verify_fails(self):
        self.env["FAKE_UNVERIFIED"] = "1"
        self.assertFails(self.run_check(), "apksigner")

    def test_another_package_code_or_name_fails(self):
        for case, line in {"package": badging(package="io.example.other"), "code": badging(code="10001"),
                           "name": badging(name="0.1.0-beta.10000"), "a longer name": badging(name="0.1.0-rc.1")}.items():
            with self.subTest(case):
                self.write("badging.txt", line)
                shutil.rmtree(self.out, ignore_errors=True)
                self.assertFails(self.run_check(), PACKAGE)

    def test_a_report_on_other_bytes_fails(self):
        self.report(hashlib.sha256(b"other bytes").hexdigest())
        self.assertFails(self.run_check(), "checkBetaHasNoDevSettings")

    def test_a_failed_or_missing_report_fails(self):
        sha = hashlib.sha256(self.apk_bytes).hexdigest()
        for case, report in {"found settings": dict(first="carries dev settings from local.properties. Don't publish it:"),
                             "no control": dict(dex="  checkBetaHasNoDevSettings: no dex holds the applicationId")}.items():
            with self.subTest(case):
                self.report(sha, **report)
                shutil.rmtree(self.out, ignore_errors=True)
                self.assertFails(self.run_check(), "checkBetaHasNoDevSettings")
        os.remove(os.path.join(self.root, "app/build/reports/checkBetaHasNoDevSettings.txt"))
        shutil.rmtree(self.out, ignore_errors=True)
        self.assertFails(self.run_check(), "checkBetaHasNoDevSettings")

    def test_canaries_must_have_been_looked_for(self):
        sha = hashlib.sha256(self.apk_bytes).hexdigest()
        self.assertFails(self.run_check("--canaries"), "extra entr")
        for looked in ("  Looked in every entry and the file itself, as UTF-8 and UTF-16, for: 1 extra entry.",
                       "  Looked in every entry and the file itself, as UTF-8 and UTF-16, for: devTmdbKey, 12 extra entries."):
            with self.subTest(looked):
                self.report(sha, looked=looked)
                shutil.rmtree(self.out, ignore_errors=True)
                self.assertPasses(self.run_check("--canaries"))

    def test_a_test_that_was_skipped_failed_or_did_not_run_fails(self):
        results = "app/build/test-results/testBetaUnitTest"
        for name in TESTS:
            for case, xml in {"skipped": suite(name, skipped=1), "failed": suite(name, failures=1),
                              "errors": suite(name, errors=1), "none ran": suite(name, tests=0), "missing": None}.items():
                with self.subTest(test=name, case=case):
                    path = os.path.join(self.root, results, f"TEST-{name}.xml")
                    if xml is None:
                        os.remove(path)
                    else:
                        self.write(f"{results}/TEST-{name}.xml", xml)
                    shutil.rmtree(self.out, ignore_errors=True)
                    self.assertFails(self.run_check(), name.rsplit(".", 1)[1])
                    self.write(f"{results}/TEST-{name}.xml", suite(name))

    def test_an_existing_out_folder_is_refused(self):
        os.makedirs(self.out)
        self.assertFails(self.run_check(), "exists")

    def test_metadata_naming_more_than_one_apk_or_a_path_is_refused(self):
        for case, metadata in {
            "two": '{"elements": [{"outputFile": "app-beta.apk"}, {"outputFile": "other.apk"}]}',
            "a path": '{"elements": [{"outputFile": "../app-beta.apk"}]}',
        }.items():
            with self.subTest(case):
                self.write("app/build/outputs/apk/beta/output-metadata.json", metadata)
                self.assertFails(self.run_check(), "metadata")

    def test_a_large_apk_passes_with_a_warning(self):
        path = os.path.join(self.out_apk, "app-beta.apk")
        with open(path, "r+b") as f:
            f.truncate(25 * 1024 * 1024)
        with open(path, "rb") as f:
            self.report(hashlib.sha256(f.read()).hexdigest())
        done = self.run_check()
        self.assertPasses(done)
        self.assertIn("24 MiB", done.stderr)

    def test_bad_arguments_are_refused(self):
        not_a_digest = self.write("not-a-digest.txt", "not a digest\n")
        for case, args in {
            "no code": ["--digest-file", self.digest_file, "--version", "0.1.0", "--out", self.out],
            "a code that is no number": ["--digest-file", self.digest_file, "--code", "1e4", "--version", "0.1.0",
                                         "--out", self.out],
            "a digest file with no digest": ["--digest-file", not_a_digest, "--code", "10000", "--version", "0.1.0",
                                             "--out", self.out],
            "an unknown option": ["--digest-file", self.digest_file, "--code", "10000", "--version", "0.1.0",
                                  "--out", self.out, "--clobber"],
        }.items():
            with self.subTest(case):
                done = subprocess.run(["bash", SCRIPT, *args], cwd=self.root, env=self.env, capture_output=True,
                                      text=True, timeout=60)
                self.assertEqual(2, done.returncode, done.stdout + done.stderr)
                self.assertFalse(os.path.exists(self.out))


if __name__ == "__main__":
    unittest.main()
