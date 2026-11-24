#!/usr/bin/env python3
"""Tests of tools/release/check-plugin-apk.sh, the check of a plugin's signed release build before its release takes it.

Run: python3 -I -B -m unittest discover -s tools/release -p 'test_*.py'. Each test lays out a plugin build's outputs in a
temp folder: an APK of invented entries with its metadata, plugin-check's rules report on it, the tree's plugin API
version, and a fake SDK whose apksigner and aapt2 print what the test sets and log what they were given, as
test_check_beta_apk.py does. The canaries are invented and built from parts at run time. Nothing here reads a real APK.
"""
import hashlib
import io
import os
import shutil
import stat
import subprocess
import tempfile
import unittest
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "check-plugin-apk.sh")
PLUGIN = "kestrel"
PACKAGE = "app.nzstreamer.plugin.kestrel"
DIGEST = "ab" * 32
OTHER_DIGEST = "cd" * 32
APK_DIR = "plugins/kestrel/build/outputs/apk/release"
REPORT = "plugins/kestrel/build/reports/checkPluginRulesRelease.txt"
RULES_PASSED = "kestrel-release.apk keeps every plugin's rules: app.nzstreamer.plugin.kestrel 1000300, API 1.0, 12 classes of its own.\n"


def j(*parts):
    return "".join(parts)


# Canaries, as the release workflow's secret gives them: a plain one, a whole word, a common word for text only and a
# short one.
PLAIN = j("qzv", "plinth", "-7")
WORD = j("whorl", "brine")
CITY = j("Qzv", "marind")
SHORT = j("q", "z")

FAKE_TOOL = r"""#!/bin/bash
printf '%s\n' "$(basename "$0") $*" >> "$FAKE_LOG"
case $(basename "$0") in
    apksigner)
        [[ $1 == verify && $2 == --print-certs && $# == 3 ]] || exit 98
        [[ -z ${FAKE_UNVERIFIED:-} ]] || { echo "the fake APK does not verify" >&2; exit 1; }
        cat "$FAKE_CERTS" ;;
    aapt2)
        if [[ $1 == dump && $2 == badging && $3 == --include-meta-data && $# == 4 ]]; then
            cat "$FAKE_BADGING"
        elif [[ $1 == dump && $2 == xmltree && $3 == --file && $4 == AndroidManifest.xml && $# == 5 ]]; then
            cat "$FAKE_XMLTREE"
        else
            exit 98
        fi ;;
esac
"""
OLD_TOOL = "#!/bin/bash\nexit 97\n"


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


def badging(package=PACKAGE, code="1000300", name="1.0.3", plugin_id=PLUGIN, major="1", minor="0", extra=()):
    return "\n".join([
        f"package: name='{package}' versionCode='{code}' versionName='{name}' platformBuildVersionName='16' "
        "platformBuildVersionCode='36' compileSdkVersion='36' compileSdkVersionCodename='16'",
        "minSdkVersion:'26'", "targetSdkVersion:'36'", "application: label='' icon=''",
        f"meta-data: name='nzs.plugin.id' value='{plugin_id}'",
        f"meta-data: name='nzs.plugin.entry' value='{PACKAGE}.KestrelPlugin'",
        f"meta-data: name='nzs.plugin.api.major' value='{major}'",
        f"meta-data: name='nzs.plugin.api.minor' value='{minor}'",
        "meta-data: name='nzs.plugin.libs' value='kotlin-stdlib=2.3.21'",
        *extra,
        "supports-screens: 'small' 'normal' 'large' 'xlarge'",
    ]) + "\n"


ANDROID = "http://schemas.android.com/apk/res/android"


def meta(name, value):
    return [f"          E: meta-data (line=19)",
            f'            A: {ANDROID}:name(0x01010003)="{name}" (Raw: "{name}")',
            f"            A: {ANDROID}:value(0x01010024)={value}"]


def xmltree(major="1", minor="0", min_sdk="=26", manifest_extra=(), application_extra=()):
    lines = [f"N: android={ANDROID} (line=6)",
             "  E: manifest (line=6)",
             f"    A: {ANDROID}:versionCode(0x0101021b)=1000300",
             f'    A: {ANDROID}:versionName(0x0101021c)="1.0.3" (Raw: "1.0.3")',
             f'    A: package="{PACKAGE}" (Raw: "{PACKAGE}")',
             *manifest_extra,
             "      E: uses-sdk (line=11)"]
    if min_sdk is not None:
        lines.append(f"        A: {ANDROID}:minSdkVersion(0x0101020c){min_sdk}")
    lines += [f"        A: {ANDROID}:targetSdkVersion(0x01010270)=36",
              "      E: application (line=15)",
              f"        A: {ANDROID}:hasCode(0x0101000c)=true",
              *meta("nzs.plugin.id", f'"{PLUGIN}" (Raw: "{PLUGIN}")'),
              *meta("nzs.plugin.api.major", major),
              *meta("nzs.plugin.api.minor", minor),
              *application_extra]
    return "\n".join(lines) + "\n"


def apk(entries):
    """A zip of the given (name, bytes, stored) entries, as an APK is one."""
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as z:
        for name, data, stored in entries:
            z.writestr(zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0)), data,
                       compress_type=zipfile.ZIP_STORED if stored else zipfile.ZIP_DEFLATED)
    return buffer.getvalue()


DEX = b"dex\n035\x00" + b"\x00" * 64 + b"Lapp/nzstreamer/plugin/kestrel/KestrelPlugin;\x00Lkotlin/Unit;\x00" + bytes(range(256))
MANIFEST = b"\x03\x00\x08\x00" + "app.nzstreamer.plugin.kestrel".encode("utf-16-le")


class CheckPluginApkTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="check-plugin-apk-")
        self.addCleanup(shutil.rmtree, self.root, ignore_errors=True)
        self.write(f"{APK_DIR}/output-metadata.json",
                   '{"version": 3, "elements": [{"type": "SINGLE", "outputFile": "kestrel-release.apk"}]}\n')
        self.lay_out_apk()
        self.write(REPORT, RULES_PASSED)
        self.write("core/plugin/plugin-api.txt", "1.0\n")
        self.digest_file = self.write("cert.sha256", DIGEST + "\n")
        self.sdk = os.path.join(self.root, "sdk")
        for version, body in (("34.0.0", OLD_TOOL), ("35.0.0", FAKE_TOOL)):
            for tool in ("apksigner", "aapt2"):
                path = self.write(f"sdk/build-tools/{version}/{tool}", body)
                os.chmod(path, os.stat(path).st_mode | stat.S_IXUSR)
        self.log = os.path.join(self.root, "tools.log")
        self.certs = self.write("certs.txt", certs(DIGEST))
        self.badging = self.write("badging.txt", badging())
        self.xmltree = self.write("xmltree.txt", xmltree())
        # As canaries.py writes them: each entry on its line in the secret, blank lines between.
        self.canaries = self.write("canaries.tsv", f"h1\t{PLAIN}\n\nw:\t{WORD}\nwt:\t{CITY}\nw2:\t{SHORT}\n")
        self.out = os.path.join(self.root, "staged")
        self.env = {"PATH": os.environ["PATH"], "ANDROID_HOME": self.sdk, "FAKE_LOG": self.log,
                    "FAKE_CERTS": self.certs, "FAKE_BADGING": self.badging, "FAKE_XMLTREE": self.xmltree}

    def write(self, relative, content, binary=False):
        path = os.path.join(self.root, relative)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "wb" if binary else "w") as f:
            f.write(content)
        return path

    def lay_out_apk(self, dex=DEX, extra=()):
        self.apk_bytes = apk([("AndroidManifest.xml", MANIFEST, False), ("classes.dex", dex, False), *extra])
        self.write(f"{APK_DIR}/kestrel-release.apk", self.apk_bytes, binary=True)

    def run_check(self, *extra, code="1000300", version="1.0.3", plugin_id=PLUGIN, digest_file=None):
        shutil.rmtree(self.out, ignore_errors=True)
        args = ["--id", plugin_id, "--digest-file", digest_file or self.digest_file, "--code", code, "--version", version,
                "--out", self.out, *extra]
        return subprocess.run(["bash", SCRIPT, *args], cwd=self.root, env=self.env, capture_output=True, text=True,
                              timeout=60)

    def assertPasses(self, done):
        self.assertEqual(0, done.returncode, done.stdout + done.stderr)

    def assertFails(self, done, *words):
        self.assertEqual(1, done.returncode, done.stdout + done.stderr)
        for word in words:
            self.assertIn(word, done.stderr)

    def assertTellsNoCanary(self, done):
        for value in (PLAIN, WORD, CITY, PLAIN.upper()):
            self.assertNotIn(value, done.stdout + done.stderr)

    def test_a_good_build_passes_and_only_its_staged_copy_is_read(self):
        done = self.run_check("--canaries", self.canaries)
        self.assertPasses(done)
        staged = os.path.join(self.out, "kestrel.apk")
        with open(staged, "rb") as f:
            self.assertEqual(self.apk_bytes, f.read())
        with open(staged + ".sha256") as f:
            self.assertEqual(f"{hashlib.sha256(self.apk_bytes).hexdigest()}  kestrel.apk\n", f.read())
        self.assertEqual({"kestrel.apk", "kestrel.apk.sha256"}, set(os.listdir(self.out)))
        with open(self.log) as f:
            calls = f.read().splitlines()
        self.assertEqual([f"apksigner verify --print-certs {staged}", f"aapt2 dump badging --include-meta-data {staged}",
                          f"aapt2 dump xmltree --file AndroidManifest.xml {staged}"], calls)
        self.assertIn("kestrel.apk passes", done.stdout)
        checked = subprocess.run(["sha256sum", "-c", "kestrel.apk.sha256"], cwd=self.out, capture_output=True, text=True,
                                 timeout=60)
        self.assertEqual(0, checked.returncode, checked.stdout + checked.stderr)

    def test_build_tools_37s_signer_lines_pass(self):
        self.write("certs.txt", certs37(DIGEST, schemes=("V2", "V3")))
        self.assertPasses(self.run_check())

    def test_a_signer_other_than_the_beta_keys_fails(self):
        for case, text in {"another key": certs(OTHER_DIGEST), "another key, build-tools 37": certs37(OTHER_DIGEST),
                           "a second signer": certs(DIGEST, OTHER_DIGEST),
                           "lines it can't read": f"Signer certificate SHA-256 digest is {DIGEST}\n"}.items():
            with self.subTest(case):
                self.write("certs.txt", text)
                done = self.run_check()
                self.assertFails(done, "sign")
                self.assertNotIn(OTHER_DIGEST, done.stdout + done.stderr)
        self.write("certs.txt", certs(DIGEST))
        self.env["FAKE_UNVERIFIED"] = "1"
        self.assertFails(self.run_check(), "apksigner")

    def test_another_package_code_or_name_fails(self):
        for case, line in {"package": badging(package="app.nzstreamer.plugin.other"), "code": badging(code="1000301"),
                           "name": badging(name="1.0.4"), "a longer name": badging(name="1.0.3-rc.1")}.items():
            with self.subTest(case):
                self.write("badging.txt", line)
                self.assertFails(self.run_check(), PACKAGE)

    def test_its_id_and_api_stamp_must_be_the_trees(self):
        for case, (line, tree, words) in {
            "another id": (badging(plugin_id="other"), xmltree(), "nzs.plugin.id"),
            "another major": (badging(major="2"), xmltree(major="2"), "API"),
            "another minor": (badging(minor="1"), xmltree(minor="1"), "API"),
            "the minor as a string": (badging(), xmltree(minor='"0" (Raw: "0")'), "integer"),
            "the major as a float": (badging(major="1065353216"), xmltree(major="1.0"), "API"),
            "no minor": (badging().replace("meta-data: name='nzs.plugin.api.minor' value='0'\n", ""),
                         xmltree().replace("nzs.plugin.api.minor", "nzs.plugin.other"), "API"),
        }.items():
            with self.subTest(case):
                self.write("badging.txt", line)
                self.write("xmltree.txt", tree)
                self.assertFails(self.run_check(), words)
        # The tree moved on: the build is stamped with the API it was built from, which is no longer the tree's.
        self.write("badging.txt", badging())
        self.write("xmltree.txt", xmltree())
        self.write("core/plugin/plugin-api.txt", "1.1\n")
        self.assertFails(self.run_check(), "API", "1.1")

    def test_its_min_sdk_must_be_26(self):
        for case, value in {"21": "=21", "a string": '="26" (Raw: "26")', "not set": None}.items():
            with self.subTest(case):
                self.write("xmltree.txt", xmltree(min_sdk=value))
                self.assertFails(self.run_check(), "minSdk")

    def test_a_launcher_component_permission_or_shared_user_fails(self):
        cases = {
            "a launcher": (badging(extra=["launchable-activity: name='app.nzstreamer.plugin.kestrel.Main' label=''"]),
                           xmltree(), "launcher"),
            "a TV launcher": (badging(extra=["leanback-launchable-activity: name='app.nzstreamer.plugin.kestrel.Main'"]),
                              xmltree(), "launcher"),
            "a shared user": (badging(), xmltree(manifest_extra=[
                f'    A: {ANDROID}:sharedUserId(0x0101000b)="io.skjaere.usenet" (Raw: "io.skjaere.usenet")']), "sharedUserId"),
            "a permission": (badging(), xmltree(manifest_extra=[
                "      E: uses-permission (line=9)",
                f'        A: {ANDROID}:name(0x01010003)="android.permission.INTERNET" (Raw: "android.permission.INTERNET")']),
                "permission"),
            "a permission of its own": (badging(), xmltree(manifest_extra=["      E: permission (line=9)"]), "permission"),
        }
        for component in ("activity", "activity-alias", "service", "receiver", "provider"):
            cases[f"an {component}"] = (badging(), xmltree(application_extra=[f"          E: {component} (line=30)"]),
                                        "component")
        for case, (line, tree, words) in cases.items():
            with self.subTest(case):
                self.write("badging.txt", line)
                self.write("xmltree.txt", tree)
                self.assertFails(self.run_check(), words)

    def test_plugin_checks_rules_must_have_passed_on_this_build(self):
        for case, report in {
            "failed": "kestrel-release.apk breaks the plugins' rules (1):\n  it declares a component (service)\n",
            "another build's": RULES_PASSED.replace("kestrel-release.apk", "kestrel-debug.apk"),
            "a name like it": RULES_PASSED.replace("kestrel-release.apk", "kestrel-releaseXapk"),
            "not on its first line": "plugin-check: notes\n" + RULES_PASSED,
            "another code": RULES_PASSED.replace("1000300", "1000200"),
            "another API": RULES_PASSED.replace("API 1.0", "API 1.1"),
            "missing": None,
        }.items():
            with self.subTest(case):
                path = os.path.join(self.root, REPORT)
                if report is None:
                    os.remove(path)
                else:
                    self.write(REPORT, report)
                self.assertFails(self.run_check(), "checkPluginRulesRelease")
                self.write(REPORT, RULES_PASSED)

    def test_a_canary_in_the_apk_fails_naming_its_line_alone(self):
        for case, dex, extra in (
            ("in the dex", DEX + PLAIN.encode(), ()),
            ("in another case", DEX + PLAIN.upper().encode(), ()),
            ("as UTF-16", DEX, (("assets/words.bin", PLAIN.encode("utf-16-le"), False),)),
            ("as UTF-16 big-endian", DEX, (("assets/words.bin", PLAIN.encode("utf-16-be"), False),)),
            ("in a stored entry", DEX, (("lib/words.txt", b"x " + PLAIN.encode() + b" y", True),)),
            ("a word, alone", DEX + b" " + WORD.encode() + b".", ()),
        ):
            with self.subTest(case):
                self.lay_out_apk(dex, extra)
                self.write(REPORT, RULES_PASSED)
                done = self.run_check("--canaries", self.canaries)
                line = "line 3" if case == "a word, alone" else "line 1"
                self.assertFails(done, "canar", line)
                self.assertTellsNoCanary(done)

    def test_what_the_apk_scan_leaves_to_the_text_scans_passes(self):
        # Inside a longer word, a w: canary is no hit; a wt: one is never looked for in a binary, nor one under 5.
        self.lay_out_apk(DEX + b"x" + WORD.encode() + b"y " + CITY.encode() + b" " + SHORT.encode())
        done = self.run_check("--canaries", self.canaries)
        self.assertPasses(done)
        self.assertTellsNoCanary(done)
        # With no canaries given, nothing is looked for.
        self.lay_out_apk(DEX + PLAIN.encode())
        self.assertPasses(self.run_check())

    def test_a_scan_that_cant_read_the_dex_fails(self):
        self.lay_out_apk(b"dex\n035\x00" + b"\x00" * 64)
        self.assertFails(self.run_check("--canaries", self.canaries), "dex")

    def test_an_existing_out_folder_is_refused(self):
        os.makedirs(self.out)
        done = subprocess.run(["bash", SCRIPT, "--id", PLUGIN, "--digest-file", self.digest_file, "--code", "1000300",
                               "--version", "1.0.3", "--out", self.out], cwd=self.root, env=self.env,
                              capture_output=True, text=True, timeout=60)
        self.assertFails(done, "exists")

    def test_metadata_naming_more_than_one_apk_or_a_path_is_refused(self):
        for case, metadata in {
            "two": '{"elements": [{"outputFile": "kestrel-release.apk"}, {"outputFile": "other.apk"}]}',
            "a path": '{"elements": [{"outputFile": "../kestrel-release.apk"}]}',
        }.items():
            with self.subTest(case):
                self.write(f"{APK_DIR}/output-metadata.json", metadata)
                self.assertFails(self.run_check(), "metadata")

    def test_bad_arguments_are_refused(self):
        not_a_digest = self.write("not-a-digest.txt", "not a digest\n")
        good = ["--digest-file", self.digest_file, "--code", "1000300", "--version", "1.0.3", "--out", self.out]
        for case, args in {
            "no id": good,
            "an id outside the rule": ["--id", "Kestrel", *good],
            "the id false": ["--id", "false", *good],
            "an id holding a path": ["--id", "../kestrel", *good],
            "no code": ["--id", PLUGIN, "--digest-file", self.digest_file, "--version", "1.0.3", "--out", self.out],
            "a code that is no number": ["--id", PLUGIN, *good[:2], "--code", "1e6", *good[4:]],
            "a version that is no M.m.p": ["--id", PLUGIN, *good[:4], "--version", "1.0", *good[6:]],
            "a digest file with no digest": ["--id", PLUGIN, "--digest-file", not_a_digest, *good[2:]],
            "canaries with no file": ["--id", PLUGIN, *good, "--canaries"],
            "canaries from a file that isn't there": ["--id", PLUGIN, *good, "--canaries", "missing.tsv"],
            "an unknown option": ["--id", PLUGIN, *good, "--clobber"],
        }.items():
            with self.subTest(case):
                shutil.rmtree(self.out, ignore_errors=True)
                done = subprocess.run(["bash", SCRIPT, *args], cwd=self.root, env=self.env, capture_output=True,
                                      text=True, timeout=60)
                self.assertEqual(2, done.returncode, done.stdout + done.stderr)
                self.assertFalse(os.path.exists(self.out))


if __name__ == "__main__":
    unittest.main()
