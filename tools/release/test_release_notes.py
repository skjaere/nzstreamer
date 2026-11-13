#!/usr/bin/env python3
"""Tests of tools/release/release_notes.py: the libraries' pins, the notes' licence footer, and the scan of the notes.

Run: python3 -I -B -m unittest discover -s tools/release -p 'test_*.py'. The catalogs and notes are invented, built in
temp folders; one test reads the repository's own catalog. The bad samples are built from parts at run time.
"""
import os
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "release_notes.py")
CATALOG = os.path.join(os.path.dirname(os.path.dirname(HERE)), "gradle", "libs.versions.toml")
MARKER = "<!-- release-apk: licences -->"
SHA_A = "a" * 40
SHA_B = "b" * 40


def j(*parts):
    return "".join(parts)


INVENTED_CATALOG = """[versions]
kotlin = "2.3.21"
alpha = "v1.2.0"
beta-lib = "v0.3.4"

[libraries]
alpha = { module = "com.github.inventor:alpha", version.ref = "alpha" }
beta-lib = { module = "com.github.inventor:beta-lib", version.ref = "beta-lib" }
gamma = { module = "com.github.other-owner:gamma", version = "v2.0.1" }
kotlin-test = { module = "org.jetbrains.kotlin:kotlin-test", version.ref = "kotlin" }
"""


class ReleaseNotesTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="release-notes-")
        self.addCleanup(shutil.rmtree, self.dir, ignore_errors=True)

    def write(self, name, text):
        path = os.path.join(self.dir, name)
        with open(path, "w") as f:
            f.write(text)
        return path

    def run_script(self, *args):
        return subprocess.run([sys.executable, "-I", "-B", SCRIPT, *args], cwd=self.dir,
                              env={"PATH": os.environ["PATH"]}, capture_output=True, text=True, timeout=60)

    def test_pins_lists_each_jitpack_library_by_repository_and_version(self):
        done = self.run_script("pins", self.write("libs.versions.toml", INVENTED_CATALOG))
        self.assertEqual(0, done.returncode, done.stderr)
        self.assertEqual(["inventor/alpha\tv1.2.0", "inventor/beta-lib\tv0.3.4", "other-owner/gamma\tv2.0.1"],
                         done.stdout.splitlines())

    def test_pins_reads_the_repositorys_own_catalog(self):
        done = self.run_script("pins", CATALOG)
        self.assertEqual(0, done.returncode, done.stderr)
        repos = [line.split("\t")[0] for line in done.stdout.splitlines()]
        self.assertEqual(sorted(["skjaere/nzb-streamer", "skjaere/ktor-nntp-client", "skjaere/kotlin-compression-utils",
                                 "skjaere/rapidyenc-kotlin-wrapper"]), sorted(repos))

    def test_pins_refuses_a_version_that_is_no_plain_tag(self):
        for case, version in {"a missing ref": 'version.ref = "nowhere"', "a space": 'version = "v1 2"',
                              "a range": 'version = "[1.0,2.0)"'}.items():
            with self.subTest(case):
                catalog = f'[versions]\n[libraries]\nalpha = {{ module = "com.github.inventor:alpha", {version} }}\n'
                done = self.run_script("pins", self.write("libs.versions.toml", catalog))
                self.assertEqual(1, done.returncode, done.stdout)
                self.assertEqual("", done.stdout)

    def libraries(self):
        return self.write("libraries.tsv", f"inventor/alpha\tv1.2.0\t{SHA_A}\tyes\ninventor/beta-lib\tv0.3.4\t{SHA_B}\tno\n")

    def test_notes_adds_the_licence_footer_after_the_body(self):
        body = "## 0.2.0\n\n### Features\n\n* an invented feature\n"
        done = self.run_script("notes", self.write("body.txt", body), self.libraries())
        self.assertEqual(0, done.returncode, done.stderr)
        notes = done.stdout
        self.assertTrue(notes.startswith(body.rstrip("\n") + "\n\n" + MARKER + "\n"), notes)
        for name in ("LICENSE.md", "THIRD_PARTY_NOTICES.md", "Apache-2.0.txt", "PolyForm Noncommercial License 1.0.0"):
            self.assertIn(name, notes)
        self.assertIn(f"https://github.com/inventor/alpha/blob/{SHA_A}/LICENSE.md", notes)
        self.assertIn(f"https://github.com/inventor/alpha/blob/{SHA_A}/THIRD_PARTY_NOTICES.md", notes)
        self.assertIn(f"https://github.com/inventor/beta-lib/blob/{SHA_B}/LICENSE.md", notes)
        self.assertNotIn(f"inventor/beta-lib/blob/{SHA_B}/THIRD_PARTY_NOTICES.md", notes)
        self.assertIn("alpha v1.2.0", notes)
        self.assertTrue(notes.endswith("\n") and not notes.endswith("\n\n"))

    def test_notes_replaces_an_earlier_footer(self):
        body = self.write("body.txt", "## 0.2.0\n\n* an invented fix\n")
        first = self.run_script("notes", body, self.libraries()).stdout
        again = self.run_script("notes", self.write("again.txt", first), self.libraries())
        self.assertEqual(0, again.returncode, again.stderr)
        self.assertEqual(first, again.stdout)
        self.assertEqual(1, again.stdout.count(MARKER))

    def test_notes_on_an_empty_body_is_the_footer_alone(self):
        done = self.run_script("notes", self.write("body.txt", ""), self.libraries())
        self.assertEqual(0, done.returncode, done.stderr)
        self.assertTrue(done.stdout.startswith(MARKER + "\n"), done.stdout)

    def test_notes_refuses_a_bad_libraries_line(self):
        body = self.write("body.txt", "notes\n")
        for case, line in {"three fields": f"inventor/alpha\tv1.2.0\t{SHA_A}",
                           "no commit id": "inventor/alpha\tv1.2.0\tmain\tyes",
                           "notices neither yes nor no": f"inventor/alpha\tv1.2.0\t{SHA_A}\tmaybe",
                           "a repository with a path": f"inventor/alpha/x\tv1.2.0\t{SHA_A}\tyes",
                           "none at all": ""}.items():
            with self.subTest(case):
                done = self.run_script("notes", body, self.write("libraries.tsv", line + "\n" if line else ""))
                self.assertEqual(1, done.returncode, done.stdout)
                self.assertEqual("", done.stdout)

    def test_scan_passes_clean_notes_and_fails_a_private_value_by_line_alone(self):
        clean = self.run_script("scan", self.write("notes.txt", "## 0.2.0\n\n* an invented fix\n"))
        self.assertEqual(0, clean.returncode, clean.stdout + clean.stderr)
        for case, value in {"a private address": j("192.", "168.", "77.", "5"),
                            "an e-mail address": j("pat.", "qzv", "@", "plinth", "whorl", ".org"),
                            "a home path": j("/ho", "me/", "qzvuser", "/notes")}.items():
            with self.subTest(case):
                done = self.run_script("scan", self.write("notes.txt", f"## 0.2.0\n\n* fixed for {value}\n"))
                self.assertEqual(1, done.returncode, done.stdout + done.stderr)
                self.assertIn("release-notes.txt:3", done.stdout)
                self.assertNotIn(value, done.stdout + done.stderr)


if __name__ == "__main__":
    unittest.main()
