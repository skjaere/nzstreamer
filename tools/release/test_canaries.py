#!/usr/bin/env python3
"""Tests of tools/release/canaries.py, which turns the release workflow's canary secret into masks and a TSV, and looks
for the canaries in the release's notes.

Run: python3 -I -B -m unittest discover -s tools/release -p 'test_*.py'. The canaries are invented and built from parts
at run time; no output may show one except in a mask command, which the runner consumes and never shows.
"""
import os
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "canaries.py")


def j(*parts):
    return "".join(parts)


FIRST = j("qzv", "plinth", "-7")
SECOND = j("whorl", " 50% ", "brine")
SHORT = j("q", "z")
# A common word that library data also holds, such as a city: a wt: canary, looked for in text only.
CITY = j("Qzv", "marind")


class CanariesTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="canaries-")
        self.addCleanup(shutil.rmtree, self.dir, ignore_errors=True)
        self.out = os.path.join(self.dir, "canaries.tsv")

    def run_script(self, secret, *args):
        env = {"PATH": os.environ["PATH"]}
        if secret is not None:
            env["BETA_SCAN_CANARIES"] = secret
        return subprocess.run([sys.executable, "-I", "-B", SCRIPT, *(args or (self.out,))], env=env,
                              capture_output=True, text=True, timeout=30)

    def read_out(self):
        with open(self.out, newline="") as f:
            return f.read()

    def test_each_value_is_masked_and_the_entries_are_written_trimmed_on_their_own_lines(self):
        secret = j("# the canaries\n", "\n", f"  first one \t {FIRST} \r\n", f"second\t{SECOND}\n", f"short\t{SHORT}\n",
                   "   \n")
        done = self.run_script(secret)
        self.assertEqual(0, done.returncode, done.stderr)
        # A value under 5 characters isn't masked: the check skips it, and masking two letters would hide them all over
        # the log.
        self.assertEqual([f"::add-mask::{FIRST}", f"::add-mask::{SECOND.replace('%', '%25')}"], done.stdout.splitlines())
        # Each entry on its line in the secret, so the check's report names the secret's own line.
        self.assertEqual(f"\n\nfirst one\t{FIRST}\nsecond\t{SECOND}\nshort\t{SHORT}\n", self.read_out())
        self.assertEqual(0o600, stat.S_IMODE(os.stat(self.out).st_mode))
        self.assertIn("3", done.stderr)
        for value in (FIRST, SECOND, SHORT, "first one"):
            self.assertNotIn(value, done.stderr)

    def test_a_text_only_value_is_masked_and_written_as_a_whole_word_one_is(self):
        word = j("Kest", "rel")
        done = self.run_script(j(f"w:bird1\t{word}\n", f"wt:place1\t{CITY}\n", f"wt:place2\t{SHORT}\n"))
        self.assertEqual(0, done.returncode, done.stderr)
        self.assertEqual([f"::add-mask::{word}", f"::add-mask::{CITY}"], done.stdout.splitlines())
        self.assertEqual(f"w:bird1\t{word}\nwt:place1\t{CITY}\nwt:place2\t{SHORT}\n", self.read_out())
        for value in (word, CITY, SHORT, "bird1", "place1", "place2"):
            self.assertNotIn(value, done.stderr)

    def test_no_canaries_write_no_file(self):
        for secret in (None, "", "\n# only a comment\n\n"):
            with self.subTest(secret=secret):
                done = self.run_script(secret)
                self.assertEqual(0, done.returncode, done.stderr)
                self.assertEqual("", done.stdout)
                self.assertFalse(os.path.exists(self.out))

    def test_a_bad_line_stops_it_by_its_number_alone(self):
        for case, bad in {"no TAB": FIRST, "no label": f"\t{FIRST}", "no value": "label\t  "}.items():
            with self.subTest(case):
                done = self.run_script(f"second\t{SECOND}\n# a comment\n{bad}\n")
                self.assertEqual(1, done.returncode, done.stderr)
                self.assertIn("line 3", done.stderr)
                self.assertNotIn(FIRST, done.stdout + done.stderr)
                self.assertNotIn(SECOND, done.stderr)
                self.assertFalse(os.path.exists(self.out))

    def test_an_existing_file_or_a_missing_argument_is_refused(self):
        with open(self.out, "w") as f:
            f.write("earlier\n")
        done = self.run_script(f"first\t{FIRST}\n")
        self.assertEqual(2, done.returncode, done.stderr)
        self.assertEqual("earlier\n", self.read_out())
        self.assertNotIn(FIRST, done.stdout + done.stderr)
        done = subprocess.run([sys.executable, "-I", "-B", SCRIPT], env={"PATH": os.environ["PATH"]},
                              capture_output=True, text=True, timeout=30)
        self.assertEqual(2, done.returncode, done.stderr)


class ScanTest(unittest.TestCase):
    """--scan <file>: the release's notes hold none of the canaries, by the canary file's rules for text."""

    WORD = j("Kest", "rel")
    BRAND = j("Q", "x")

    def setUp(self):
        self.dir = tempfile.mkdtemp(prefix="canaries-scan-")
        self.addCleanup(shutil.rmtree, self.dir, ignore_errors=True)
        self.secret = j("# canaries\n", f"first\t{FIRST}\n", f"w:bird1\t{self.WORD}\n", f"w2:brand1\t{self.BRAND}\n",
                        f"short\t{SHORT}\n")

    def scan(self, notes, secret=None):
        path = os.path.join(self.dir, "notes.txt")
        with open(path, "w", encoding="utf-8") as f:
            f.write(notes)
        env = {"PATH": os.environ["PATH"], "BETA_SCAN_CANARIES": self.secret if secret is None else secret}
        done = subprocess.run([sys.executable, "-I", "-B", SCRIPT, "--scan", path], env=env, capture_output=True, text=True,
                              timeout=30)
        for value in (FIRST, self.WORD, self.BRAND, SHORT, CITY, "bird1", "brand1", "place1", "place2"):
            self.assertNotIn(value.lower(), (done.stdout + done.stderr).lower(), "a canary shows in the output")
        return done

    def test_notes_without_a_canary_pass(self):
        notes = j("## 0.2.0\n", "* feat: a ", "kestrelsong", " screen\n", "* fix: ", "Qxy", " and ", SHORT, " names\n")
        done = self.scan(notes)
        self.assertEqual(0, done.returncode, done.stderr)
        self.assertIn("none of the 3", done.stderr)

    def test_a_canary_in_any_case_fails_by_its_lines_alone(self):
        notes = j("## 0.2.0\n", "\n", "* fix: ", FIRST.upper(), " in the list\n")
        done = self.scan(notes)
        self.assertEqual(1, done.returncode, done.stderr)
        self.assertIn("line 2 of the canaries", done.stderr)
        self.assertIn("line 3 of the text", done.stderr)

    def test_a_whole_word_canary_is_found_as_a_word_only(self):
        for notes, found in ((j("* the ", "kestrel", " flies\n"), True), (j("* ", "kestrels", "\n"), False),
                             (j("* by ", "QX", ".\n"), True), (j("* ", "qxz", "\n"), False)):
            with self.subTest(found=found):
                self.assertEqual(1 if found else 0, self.scan(notes).returncode)

    def test_a_text_only_canary_is_found_in_the_notes_as_a_whole_word(self):
        secret = j("# canaries\n", f"wt:place1\t{CITY}\n", f"wt:place2\t{SHORT}\n")
        for notes, found in ((j("* fix: the ", CITY, " zone\n"), True), (j("* ", CITY.upper(), ".\n"), True),
                             (j("* ", CITY.lower(), "_zone\n"), False), (j("* ", CITY, "s\n"), False),
                             (j("* x", CITY.lower(), "\n"), False), (j("* the ", SHORT, " zone\n"), False)):
            with self.subTest(found=found):
                done = self.scan(j("## 0.2.0\n", notes), secret=secret)
                self.assertEqual(1 if found else 0, done.returncode, done.stderr)
                if found:
                    self.assertIn("line 2 of the canaries is in line 2 of the text", done.stderr)
                else:
                    # A value under 5 characters is skipped, as a w: one is.
                    self.assertIn("none of the 1 canary looked for", done.stderr)

    def test_no_canaries_pass_and_a_bad_line_is_refused(self):
        done = self.scan(j("* the ", FIRST, "\n"), secret="")
        self.assertEqual(0, done.returncode, done.stderr)
        done = self.scan("* notes\n", secret=j("first\t", FIRST, "\n", FIRST, "\n"))
        self.assertEqual(1, done.returncode, done.stderr)
        self.assertIn("line 2", done.stderr)
        done = subprocess.run([sys.executable, "-I", "-B", SCRIPT, "--scan", os.path.join(self.dir, "none.txt")],
                              env={"PATH": os.environ["PATH"], "BETA_SCAN_CANARIES": self.secret}, capture_output=True,
                              text=True, timeout=30)
        self.assertEqual(2, done.returncode, done.stderr)


if __name__ == "__main__":
    unittest.main()
