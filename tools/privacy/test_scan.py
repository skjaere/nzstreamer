#!/usr/bin/env python3
"""Tests of tools/privacy-scan.sh, the public privacy scan, on temp git repos.

Run: python3 -I -B tools/privacy/test_scan.py. Each test makes its own repo under a temp folder and runs the script as
the pre-push guard and CI do: copied with its allowlist and tools/privacy/ into a folder of their own, and run as
`bash <dir>/tools/privacy-scan.sh <commit>` in the repo's top folder. The bad samples are invented and built from
parts at run time, so this file holds none of them whole, and no output may show one.
"""
import os
import shutil
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
TOOLS = os.path.dirname(HERE)
SCRIPT = os.path.join(TOOLS, "privacy-scan.sh")
ALLOWLIST = os.path.join(TOOLS, "privacy-allowlist.txt")


def j(*parts):
    return "".join(parts)


# Identities: the agreed one, GitHub's web-flow (the committer of what GitHub merges), and anyone else.
AGREED = ("skjaere", j("korvusz", "@proton", ".me"))
WEB_FLOW = ("GitHub", j("noreply", "@github", ".com"))
OTHER = ("t", "t@example.invalid")
ZEROS = "0" * 40

# Invented samples, one for each pattern.
HOME_PATH = j("/ho", "me/", "qzvuser", "/proj/app")
LAN = {
    "private, 10": j("10.", "203.", "7.", "41"),
    "private, 172.16 to 172.31": j("172.", "29.", "4.", "5"),
    "private, 192.168": j("192.", "168.", "77.", "5"),
    "CGNAT, 100.64 to 100.127": j("100.", "99.", "3.", "4"),
    "link-local, 169.254": j("169.", "254.", "77.", "8"),
    "IPv6 unique-local, fd": j("fd", "4e:", "71a2::", "9"),
    "IPv6 unique-local, fc": j("fc", "07:", "1::", "2"),
    "IPv6 link-local": j("fe", "80::", "1c2:", "3"),
}
EMAIL = j("pat.", "qzv", "@", "plinth", "whorl", ".org")
KEY_FILES = (j("release", ".jks"), j("certs/server", ".pem"), j("app/upload", ".keystore"), j("signing", ".properties"),
             j(".e", "nv"), j("local", ".properties"), j("app/google-services", ".js", "on"), j("keys/id_", "ed25519"),
             j("tools/", ".netrc"), j("secret", ".p12"))
SAMPLES = (HOME_PATH, EMAIL, *LAN.values())
CLEAN = {"README.md": "# An invented app\n\nIt plays invented things.\n", "src/Main.kt": 'fun main() = println("hi")\n'}


class Repo:
    """A temp git repo whose commits take the identities a test gives."""

    def __init__(self, root):
        self.path = os.path.join(root, "repo")
        self.home = os.path.join(root, "home")
        os.makedirs(self.home)
        self.git("init", "-q", self.path, cwd=root)

    def env(self, author=OTHER, committer=None):
        committer = committer or author
        env = {"PATH": os.environ["PATH"], "HOME": self.home, "LC_ALL": "C.UTF-8", "GIT_CONFIG_NOSYSTEM": "1",
               "GIT_AUTHOR_NAME": author[0], "GIT_AUTHOR_EMAIL": author[1],
               "GIT_COMMITTER_NAME": committer[0], "GIT_COMMITTER_EMAIL": committer[1]}
        return env

    def git(self, *args, cwd=None, author=OTHER, committer=None, stdin=None):
        done = subprocess.run(["git", "-c", "user.name=t", "-c", "user.email=t@example.invalid",
                               "-c", "commit.gpgsign=false", "-c", "init.defaultBranch=main", *args],
                              cwd=cwd or self.path, env=self.env(author, committer), capture_output=True, text=True,
                              input=stdin)
        if done.returncode != 0:
            raise AssertionError(f"git {' '.join(args)} failed: {done.stderr}")
        return done.stdout.strip()

    def commit(self, files=None, remove=(), author=OTHER, committer=None, message="feat: a change"):
        for path, content in (files or {}).items():
            full = os.path.join(self.path, path)
            os.makedirs(os.path.dirname(full), exist_ok=True)
            with open(full, "wb") as out:
                out.write(content if isinstance(content, bytes) else content.encode())
        for path in remove:
            os.remove(os.path.join(self.path, path))
        self.git("add", "-A")
        self.git("commit", "-q", "--allow-empty", "-m", message, author=author, committer=committer)
        return self.git("rev-parse", "HEAD")

    def signed_commit(self, files, author, committer=WEB_FLOW, message="feat: a change"):
        """A commit as GitHub makes one: with a signature header. The signature is invented: the scan looks for one, it
        can't check GitHub's key."""
        for path, content in files.items():
            with open(os.path.join(self.path, path), "w", encoding="utf-8") as out:
                out.write(content)
        self.git("add", "-A")
        tree, parent = self.git("write-tree"), self.git("rev-parse", "HEAD")
        when = "1700000000 +0000"
        body = (f"tree {tree}\nparent {parent}\nauthor {author[0]} <{author[1]}> {when}\n"
                f"committer {committer[0]} <{committer[1]}> {when}\n"
                "gpgsig -----BEGIN PGP SIGNATURE-----\n \n wsBcBAABCAAQBQJinvented\n -----END PGP SIGNATURE-----\n"
                f"\n{message}\n")
        sha = self.git("hash-object", "-t", "commit", "-w", "--stdin", stdin=body)
        self.git("update-ref", "HEAD", sha)
        return sha


class ScanTest(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="privacy-scan-")
        self.addCleanup(shutil.rmtree, self.root, True)
        self.repo = Repo(self.root)

    def scan(self, rev="HEAD", *extra, allowlist="# Nothing allowed.\n", cwd=None, env=None):
        """Runs a copy of the script with this allowlist beside it, as the guard does; gives (exit code, output)."""
        copy = tempfile.mkdtemp(prefix="copy-", dir=self.root)
        os.makedirs(os.path.join(copy, "tools"))
        shutil.copy2(SCRIPT, os.path.join(copy, "tools", "privacy-scan.sh"))
        shutil.copytree(HERE, os.path.join(copy, "tools", "privacy"), ignore=shutil.ignore_patterns("__pycache__"))
        if allowlist is None:
            shutil.copy2(ALLOWLIST, os.path.join(copy, "tools", "privacy-allowlist.txt"))
        else:
            with open(os.path.join(copy, "tools", "privacy-allowlist.txt"), "w", encoding="utf-8") as out:
                out.write(allowlist)
        done = subprocess.run(["bash", os.path.join(copy, "tools", "privacy-scan.sh"), rev, *extra],
                              cwd=cwd or self.repo.path, env=env or self.repo.env(), capture_output=True, text=True)
        out = done.stdout + done.stderr
        for sample in SAMPLES:
            self.assertNotIn(sample.lower(), out.lower(), "a sample shows in the output")
        return done.returncode, out

    def assertClean(self, code, out):
        self.assertEqual(code, 0, out)
        self.assertIn("privacy-scan: clean", out)

    def assertFound(self, code, out, *says):
        self.assertEqual(code, 1, out)
        for text in says:
            self.assertIn(text, out)

    # Clean trees.

    def test_a_clean_tree_passes(self):
        self.repo.commit(CLEAN)
        self.assertClean(*self.scan())

    def test_the_home_package_path_passes(self):
        # A package or resource path with a folder named home, as the app's sources have.
        self.repo.commit(dict(CLEAN, **{
            "src/HomeTest.kt": 'val a = sources.getValue("$APP/home/Setup.kt")\n'
                               "val b = \"Lio/skjaere/usenet/app/home/HomeScreen;\"\n"
                               'val c = "io/skjaere/usenet/app/home/setup/state.kt"\n'
                               'val d = "${root}/ho' 'me/"\n',
        }))
        self.assertClean(*self.scan())

    def test_public_and_other_number_shapes_pass(self):
        self.repo.commit(dict(CLEAN, **{"src/Net.kt": "val dns = \"8.8.8.8\"\nval doc = \"203.0.113.12\"\n"
                                                      "val p = \"172.32.0.1\"\n"
                                                      "val ver = \"10.0.2.15.1\"\nval fee = \"feed:beef\"\n"}))
        self.assertClean(*self.scan())

    def test_reserved_domains_and_the_trailer_pass(self):
        self.repo.commit(dict(CLEAN, **{
            "notes.txt": "Write to a@example.com, b@mail.example.org, c@example.net, d@host.invalid, e@box.example,\n"
                         "f@box.test or " + j("g", "@box", ".localhost") + ".\n"
                         "Co-Authored-By: Claude Opus 5.5 <" + j("noreply", "@anthropic", ".com") + ">\n",
            "src/Label.kt": j("val s = this", "@MainActivity.lifecycleScope\nval t = super", "@Outer.name\n"),
            "src/Url.kt": 'val u = "https://' + j("login", "@indexer.example") + '/api"\n',
        }))
        self.assertClean(*self.scan())

    def test_the_identities_own_addresses_pass(self):
        self.repo.commit(dict(CLEAN, **{"tools/who.py": f'AGREED = ("skjaere", "{AGREED[1]}")\n'
                                                        f'WEB_FLOW = ("GitHub", "{WEB_FLOW[1]}")\n'}))
        self.assertClean(*self.scan())

    def test_a_kotlin_label_outside_kotlin_is_found(self):
        self.repo.commit(dict(CLEAN, **{"notes.txt": j("this", "@MainActivity.lifecycleScope\n")}))
        self.assertFound(*self.scan(), "e-mail address", "notes.txt:1")

    def test_the_zxing_notice_line_passes(self):
        self.repo.commit(dict(CLEAN, **{"app/src/main/assets/licenses/zxing-NOTICE.txt": "Copyright " + EMAIL + "\n"}))
        self.assertClean(*self.scan())

    def test_a_binary_file_is_not_read(self):
        self.repo.commit(dict(CLEAN, **{"art/a.bin": b"\x00\x01" + LAN["private, 192.168"].encode() + b"\x00"}))
        self.assertClean(*self.scan())

    # One finding for each pattern.

    def test_a_home_path_is_found(self):
        self.repo.commit(dict(CLEAN, **{"build.sh": "#!/bin/sh\ncd " + HOME_PATH + "\n"}))
        self.assertFound(*self.scan(), "home path", "build.sh:2")

    def test_a_home_path_at_the_start_of_a_line_is_found(self):
        self.repo.commit(dict(CLEAN, **{"paths.txt": HOME_PATH + "\n"}))
        self.assertFound(*self.scan(), "home path", "paths.txt:1")

    def test_each_private_address_range_is_found(self):
        for kind, address in LAN.items():
            with self.subTest(kind):
                self.repo.commit({"notes.txt": "a\nThe box answers at " + address + ".\n"})
                self.assertFound(*self.scan(), "private address", "notes.txt:2")

    def test_an_e_mail_address_is_found(self):
        self.repo.commit(dict(CLEAN, **{"src/Contact.kt": "\n\nval mail = \"" + EMAIL + "\"\n"}))
        self.assertFound(*self.scan(), "e-mail address", "src/Contact.kt:3")

    def test_an_e_mail_address_in_another_licence_file_is_found(self):
        self.repo.commit(dict(CLEAN, **{"app/src/main/assets/licenses/other-NOTICE.txt": "Copyright " + EMAIL + "\n"}))
        self.assertFound(*self.scan(), "e-mail address", "other-NOTICE.txt:1")

    def test_each_key_file_is_found(self):
        for name in KEY_FILES:
            with self.subTest(name):
                self.repo.commit({name: b"\x00\x01invented"})
                self.assertFound(*self.scan(), "key file", name)
                self.repo.commit(remove=[name])

    def test_a_finding_says_how_to_clear_it(self):
        self.repo.commit(dict(CLEAN, **{"a.txt": EMAIL + "\n"}))
        code, out = self.scan()
        self.assertFound(code, out, "reserved domain", "documentation range", "tools/privacy-allowlist.txt")
        self.assertNotIn("--reset-author", out)

    def test_several_findings_are_all_listed(self):
        self.repo.commit(dict(CLEAN, **{"a.txt": HOME_PATH + "\n", "b.txt": "x " + EMAIL + "\n"}))
        code, out = self.scan()
        self.assertFound(code, out, "a.txt:1", "b.txt:1")

    # It scans the commit's tree, not the work tree.

    def test_it_scans_the_commit_not_the_work_tree(self):
        self.repo.commit(CLEAN)
        with open(os.path.join(self.repo.path, "README.md"), "a") as out:
            out.write(EMAIL + "\n")
        self.assertClean(*self.scan())
        self.repo.git("checkout", "--", "README.md")
        dirty = self.repo.commit({"notes.txt": EMAIL + "\n"})
        self.repo.commit(remove=["notes.txt"])
        self.assertFound(*self.scan(dirty), "notes.txt:1")
        self.assertClean(*self.scan("HEAD"))

    def test_it_runs_from_a_sub_folder_on_the_whole_tree(self):
        self.repo.commit(dict(CLEAN, **{"top.txt": EMAIL + "\n"}))
        self.assertFound(*self.scan(cwd=os.path.join(self.repo.path, "src")), "top.txt:1")

    # The allowlist: kind, path glob, regex matching the whole address or path, and a comment each.

    def test_an_allowlisted_sample_passes_in_its_file_only(self):
        address = LAN["private, 192.168"]
        allow = "lan\tsrc/*Test.kt\t" + address.replace(".", r"\.") + "\t# an invented address of the tests\n"
        self.repo.commit(dict(CLEAN, **{"src/NetTest.kt": "val a = \"" + address + "\"\n"}))
        self.assertClean(*self.scan(allowlist=allow))
        self.repo.commit({"src/Net.kt": "val a = \"" + address + "\"\n"})
        self.assertFound(*self.scan(allowlist=allow), "src/Net.kt:1")

    def test_an_allowlist_entry_matches_the_whole_address(self):
        address = LAN["private, 10"]
        allow = "lan\t*\t" + address.rsplit(".", 1)[0].replace(".", r"\.") + "\t# a prefix only\n"
        self.repo.commit(dict(CLEAN, **{"a.txt": address + "\n"}))
        self.assertFound(*self.scan(allowlist=allow), "a.txt:1")

    def test_an_allowlisted_test_key_passes(self):
        allow = "key\tapp/src/androidTest/assets/tls-*.p12\t.*\t# the device tests' throwaway TLS keys\n"
        self.repo.commit(dict(CLEAN, **{"app/src/androidTest/assets/tls-server.p12": b"\x00key"}))
        self.assertClean(*self.scan(allowlist=allow))
        self.repo.commit({"app/src/main/assets/tls-server.p12": b"\x00key"})
        self.assertFound(*self.scan(allowlist=allow), "key file", "app/src/main/assets/tls-server.p12")

    def test_an_allowlist_entry_without_a_comment_is_refused(self):
        self.repo.commit(CLEAN)
        code, out = self.scan(allowlist="lan\t*\t10\\.0\\.2\\.2\n")
        self.assertEqual(code, 2, out)
        self.assertIn("privacy-allowlist.txt: line 1", out)

    def test_a_broken_allowlist_entry_is_refused(self):
        self.repo.commit(CLEAN)
        for line in ("lan\t*\t(\t# a broken regex\n", "host\t*\t.*\t# an unknown kind\n", "lan\t*\n"):
            with self.subTest(line):
                code, out = self.scan(allowlist="# header\n" + line)
                self.assertEqual(code, 2, out)
                self.assertIn("privacy-allowlist.txt: line 2", out)

    def test_the_allowlist_beside_the_script_is_read_not_the_trees(self):
        address = LAN["private, 172.16 to 172.31"]
        in_tree = "lan\t*\t" + address.replace(".", r"\.") + "\t# in the tree\n"
        self.repo.commit(dict(CLEAN, **{"tools/privacy-allowlist.txt": in_tree, "a.txt": address + "\n"}))
        self.assertFound(*self.scan(), "a.txt:1")

    def test_the_repos_allowlist_is_well_formed_and_passes_a_clean_tree(self):
        self.repo.commit(CLEAN)
        self.assertClean(*self.scan(allowlist=None))

    # The identities of a push (--since <before>): every commit it adds, a merge's other parents' too. Pull requests are
    # squash-merged, so GitHub adds no merge commits; a merge made by hand brings in commits no pull request checked.

    def history(self):
        """A first commit with the agreed identity; gives its id."""
        return self.repo.commit(CLEAN, author=AGREED)

    def test_the_agreed_identity_passes(self):
        before = self.history()
        self.repo.commit({"a.txt": "a\n"}, author=AGREED)
        self.repo.commit({"b.txt": "b\n"}, author=AGREED)
        self.assertClean(*self.scan("HEAD", "--since", before))

    def test_another_author_is_found(self):
        before = self.history()
        sha = self.repo.commit({"a.txt": "a\n"}, author=OTHER, committer=AGREED)
        code, out = self.scan("HEAD", "--since", before)
        self.assertFound(code, out, "identity", sha[:12], "author")
        # What to change: the identity, then the commit.
        self.assertIn("--reset-author", out)
        self.assertNotIn("tools/privacy-allowlist.txt", out)

    def test_another_committer_is_found(self):
        before = self.history()
        sha = self.repo.commit({"a.txt": "a\n"}, author=AGREED, committer=OTHER)
        self.assertFound(*self.scan("HEAD", "--since", before), "identity", sha[:12], "committer")

    def test_a_web_flow_squash_merge_keeps_its_contributor_as_author(self):
        before = self.history()
        self.repo.signed_commit({"a.txt": "a\n"}, author=OTHER, message="feat: a contribution")
        self.repo.signed_commit({"b.txt": "b\n"}, author=AGREED, message="docs: edited on GitHub")
        self.assertClean(*self.scan("HEAD", "--since", before))

    def test_an_unsigned_commit_naming_web_flow_as_committer_is_found(self):
        # GitHub signs what it commits: one that names it and carries no signature was made elsewhere.
        before = self.history()
        sha = self.repo.commit({"a.txt": "a\n"}, author=OTHER, committer=WEB_FLOW, message="feat: a contribution")
        code, out = self.scan("HEAD", "--since", before)
        self.assertFound(code, out, "identity", sha[:12], "no signature", "author")

    def test_a_merges_other_parents_are_checked(self):
        before = self.history()
        self.repo.git("switch", "-q", "-c", "old")
        first = self.repo.commit({"a.txt": "a\n"}, author=OTHER)
        second = self.repo.commit({"b.txt": "b\n"}, author=OTHER)
        self.repo.git("switch", "-q", "main")
        self.repo.git("merge", "-q", "--no-ff", "-m", "chore: carry the old branch over", "old",
                      author=AGREED, committer=AGREED)
        code, out = self.scan("HEAD", "--since", before)
        self.assertFound(code, out, "identity", first[:12], second[:12])
        self.assertEqual(out.count("identity: commit"), 4, out)

    def test_a_merge_of_commits_already_pushed_checks_only_the_new_ones(self):
        # main was pushed with a squash-merged contribution on it; the maintainer's own commit, made before pulling it,
        # is merged with it: the push adds that commit and the merge, nothing older.
        base = self.history()
        before = self.repo.commit({"a.txt": "a\n"}, author=OTHER, committer=WEB_FLOW, message="feat: a contribution")
        self.repo.git("switch", "-q", "-c", "mine", base)
        self.repo.commit({"b.txt": "b\n"}, author=AGREED)
        self.repo.git("merge", "-q", "--no-ff", "-m", "chore: merge main", before, author=AGREED, committer=AGREED)
        self.assertClean(*self.scan("HEAD", "--since", before))

    def test_a_merge_commit_itself_is_checked(self):
        before = self.history()
        self.repo.git("switch", "-q", "-c", "pr")
        # Its other parent is the agreed identity's: the merge's own author is the one finding.
        self.repo.commit({"a.txt": "a\n"}, author=AGREED)
        self.repo.git("switch", "-q", "main")
        self.repo.git("merge", "-q", "--no-ff", "-m", "merge", "pr", author=OTHER, committer=AGREED)
        merge = self.repo.git("rev-parse", "HEAD")
        code, out = self.scan("HEAD", "--since", before)
        self.assertFound(code, out, "identity", merge[:12], "author")
        self.assertEqual(out.count("identity: commit"), 1, out)

    def test_commits_before_the_push_are_not_checked(self):
        self.repo.commit(CLEAN, author=OTHER)
        before = self.repo.commit({"a.txt": "a\n"}, author=OTHER)
        self.repo.commit({"b.txt": "b\n"}, author=AGREED)
        self.assertClean(*self.scan("HEAD", "--since", before))

    def test_a_new_branch_checks_every_commit(self):
        first = self.repo.commit(CLEAN, author=OTHER)
        self.repo.commit({"a.txt": "a\n"}, author=AGREED)
        self.assertFound(*self.scan("HEAD", "--since", ZEROS), "identity", first[:12])

    def test_a_before_it_cannot_read_is_refused(self):
        self.history()
        code, out = self.scan("HEAD", "--since", "1" * 40)
        self.assertEqual(code, 2, out)
        self.assertIn("--since", out)

    def test_without_since_no_identity_is_checked(self):
        self.repo.commit(CLEAN, author=OTHER, committer=OTHER)
        self.assertClean(*self.scan())

    def test_an_identity_finding_never_shows_the_address(self):
        before = self.history()
        hidden = ("Pat Q", j("pat", "@", "plinth", "whorl", ".org"))
        self.repo.commit({"a.txt": "a\n"}, author=hidden, committer=hidden)
        code, out = self.scan("HEAD", "--since", before)
        self.assertFound(code, out, "author", "committer")
        self.assertNotIn(hidden[1], out)
        self.assertNotIn(hidden[0], out)

    # The call contract.

    def test_it_needs_no_ci_variable(self):
        self.repo.commit(CLEAN)
        env = {"PATH": os.environ["PATH"], "HOME": self.repo.home}
        self.assertClean(*self.scan(env=env))

    def test_a_tree_id_is_scanned_too(self):
        self.repo.commit(dict(CLEAN, **{"a.txt": EMAIL + "\n"}))
        tree = self.repo.git("rev-parse", "HEAD^{tree}")
        self.assertFound(*self.scan(tree), "a.txt:1")

    def test_a_bad_call_is_a_usage_error(self):
        self.repo.commit(CLEAN)
        for args in ((), ("no-such-rev",), ("HEAD", "extra"), ("HEAD", "--since"), ("HEAD", "--since", "--all"),
                     ("--all",)):
            with self.subTest(args):
                copy_args = list(args)
                if not copy_args:
                    done = subprocess.run(["bash", SCRIPT], cwd=self.repo.path, env=self.repo.env(),
                                          capture_output=True, text=True)
                    code = done.returncode
                else:
                    code, _ = self.scan(*copy_args)
                self.assertEqual(code, 2)


if __name__ == "__main__":
    unittest.main(verbosity=2)
