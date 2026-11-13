"""
Tests for check_workflow.py: the repo's CLA workflow passes, a good sample passes, and each planted fault fails with
its own message.

  python3 -I -B tools/cla/test_check_workflow.py
"""
import os
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import check_workflow  # noqa: E402

REPO_WORKFLOW = os.path.join(HERE, "..", "..", ".github", "workflows", "cla.yml")
REPO_CLA = os.path.join(HERE, "..", "..", "CLA.md")
PIN = "contributor-assistant/github-action@" + "ca4a40a7d1004f18d9960b404b97e5f30a505a08"
SENTENCE = "I have read the CLA Document and I hereby sign the CLA"

GOOD = f"""\
# A comment may hold a # and say actions/ in prose.
name: CLA

on:
  pull_request_target:
    types: [opened, synchronize]
  issue_comment:
    types: [created]

permissions:
  actions: write
  contents: write
  pull-requests: write
  statuses: write

jobs:
  cla:
    if: >-
      github.event_name == 'pull_request_target' ||
      (github.event.issue.pull_request &&
       (contains(github.event.comment.body, 'recheck') ||
        contains(github.event.comment.body, '{SENTENCE}')))
    runs-on: ubuntu-latest
    steps:
      - name: Check the CLA
        uses: {PIN} # v2.6.1
        env:
          GITHUB_TOKEN: ${{{{ secrets.GITHUB_TOKEN }}}}
        with:
          path-to-document: https://github.com/someone/something/blob/main/CLA.md
          branch: cla-signatures
          path-to-signatures: signatures/v1/cla.json
          allowlist: "<nobody>"
          custom-pr-sign-comment: {SENTENCE}
          create-file-commit-message: "chore: start the CLA signature record"
          signed-commit-message: "chore: record a CLA signature"
          signed-empty-commit-message: 'chore: record a CLA signature'
          lock-pullrequest-aftermerge: false
"""


def problems(text, cla=None):
    return check_workflow.check(text, cla)


def cla(version_line):
    return "# Contributor Licence Agreement\n\n" + version_line + "\n\nThe text.\n"


def planted(old, new, text=GOOD):
    assert text.count(old) == 1, old
    return text.replace(old, new)


class RepoWorkflow(unittest.TestCase):
    def test_the_repos_workflow_passes(self):
        with open(REPO_WORKFLOW, encoding="utf-8") as f:
            self.assertEqual([], problems(f.read()))

    def test_the_repos_cla_version_matches_its_signatures_folder(self):
        with open(REPO_WORKFLOW, encoding="utf-8") as f, open(REPO_CLA, encoding="utf-8") as c:
            self.assertEqual([], problems(f.read(), c.read()))


class Sample(unittest.TestCase):
    def assertFails(self, text, words, cla_text=None):
        found = problems(text, cla_text)
        self.assertTrue(any(words in p for p in found), f"no problem with {words!r} in {found}")

    def test_the_good_sample_passes(self):
        self.assertEqual([], problems(GOOD))

    def test_a_checkout_fails(self):
        self.assertFails(planted("      - name: Check the CLA\n",
                                 "      - uses: actions/checkout@" + "1" * 40 + "\n      - name: Check the CLA\n"),
                         "actions/checkout")

    def test_a_run_step_fails(self):
        self.assertFails(planted("      - name: Check the CLA\n", "      - run: echo hi\n      - name: Check the CLA\n"),
                         "run")

    def test_a_run_key_inside_a_step_fails(self):
        self.assertFails(planted("        uses: " + PIN, "        run: make\n        uses: " + PIN), "run")

    def test_an_extra_permission_fails(self):
        self.assertFails(planted("  statuses: write\n", "  statuses: write\n  issues: write\n"), "permissions")

    def test_a_missing_permission_fails(self):
        self.assertFails(planted("  actions: write\n", ""), "permissions")

    def test_a_read_permission_fails(self):
        self.assertFails(planted("  contents: write\n", "  contents: read\n"), "permissions")

    def test_write_all_fails(self):
        text = planted("permissions:\n  actions: write\n  contents: write\n  pull-requests: write\n  statuses: write\n",
                       "permissions: write-all\n")
        self.assertFails(text, "permissions")

    def test_no_permissions_fails(self):
        text = planted("permissions:\n  actions: write\n  contents: write\n  pull-requests: write\n  statuses: write\n", "")
        self.assertFails(text, "permissions")

    def test_permissions_in_a_job_fail(self):
        self.assertFails(planted("    runs-on: ubuntu-latest\n",
                                 "    runs-on: ubuntu-latest\n    permissions:\n      contents: read\n"),
                         "permissions")

    def test_a_tag_instead_of_the_commit_fails(self):
        self.assertFails(planted(PIN, "contributor-assistant/github-action@v2.6.1"), "pinned")

    def test_another_commit_fails(self):
        self.assertFails(planted(PIN, "contributor-assistant/github-action@" + "0" * 40), "pinned")

    def test_no_action_step_fails(self):
        self.assertFails(planted("        uses: " + PIN + " # v2.6.1\n", ""), "pinned")

    def test_a_hash_in_each_message_fails(self):
        for key in check_workflow.MESSAGES:
            with self.subTest(key=key):
                text = GOOD.splitlines(keepends=True)
                i = next(n for n, line in enumerate(text) if line.strip().startswith(key + ":"))
                text[i] = f"          {key}: \"chore: signed in pull request " + "#" + "7\"\n"
                self.assertFails("".join(text), key)

    def test_a_comment_after_a_message_fails(self):
        self.assertFails(planted("signed-commit-message: \"chore: record a CLA signature\"",
                                 "signed-commit-message: \"chore: record a CLA signature\" # by the bot"),
                         "signed-commit-message")

    def test_a_missing_message_fails(self):
        self.assertFails(planted("          create-file-commit-message: \"chore: start the CLA signature record\"\n", ""),
                         "create-file-commit-message")

    def test_a_message_as_a_block_fails(self):
        self.assertFails(planted("signed-commit-message: \"chore: record a CLA signature\"",
                                 "signed-commit-message: >-\n            chore: record a CLA signature"),
                         "signed-commit-message")

    def test_an_empty_message_fails(self):
        # The action falls back to its own message, which holds '#' and the pull request's number.
        for key in check_workflow.MESSAGES:
            for value in ('""', "''", '"  "'):
                with self.subTest(key=key, value=value):
                    text = GOOD.splitlines(keepends=True)
                    i = next(n for n, line in enumerate(text) if line.strip().startswith(key + ":"))
                    text[i] = f"          {key}: {value}\n"
                    self.assertFails("".join(text), key)

    def test_secrets_other_than_the_token_fail(self):
        for expression in ("toJSON(secrets)", "secrets", "fromJSON(toJSON(secrets)).OTHER", "secrets . OTHER",
                           "SECRETS.OTHER", "Secrets.GITHUB_TOKENS"):
            with self.subTest(expression=expression):
                self.assertFails(planted("          GITHUB_TOKEN: ",
                                         "          ALL: ${{ " + expression + " }}\n          GITHUB_TOKEN: "),
                                 "secret")

    def test_another_secret_fails(self):
        self.assertFails(planted("secrets.GITHUB_TOKEN", "secrets.PERSONAL_ACCESS_TOKEN"), "secret")

    def test_a_pat_by_name_fails(self):
        self.assertFails(planted("          GITHUB_TOKEN: ", "          PERSONAL_ACCESS_TOKEN: x\n          GITHUB_TOKEN: "),
                         "PERSONAL_ACCESS_TOKEN")

    def test_another_allowlist_fails(self):
        # The action matches an entry against a commit's free-text git name when its address is linked to no account.
        for entries in ("someone", "dependabot[bot]", "*", "<nobody>,someone", "''"):
            with self.subTest(entries=entries):
                self.assertFails(planted('allowlist: "<nobody>"', f"allowlist: {entries}"), "allowlist")

    def test_no_allowlist_fails(self):
        # With no list the action's lone empty entry matches an empty git name.
        self.assertFails(planted('          allowlist: "<nobody>"\n', ""), "allowlist")

    def test_a_pull_request_trigger_fails(self):
        self.assertFails(planted("  issue_comment:\n", "  pull_request:\n  issue_comment:\n"), "triggers")

    def test_a_push_trigger_fails(self):
        self.assertFails(planted("on:\n", "on:\n  push:\n"), "triggers")

    def test_closed_in_the_pull_request_types_fails(self):
        # A closed or merged pull request needs no check: it would only get a stray comment or a red mark.
        self.assertFails(planted("types: [opened, synchronize]", "types: [opened, synchronize, closed]"), "types")

    def test_no_pull_request_types_fails(self):
        # Without a list GitHub's default runs on reopened too.
        self.assertFails(planted("    types: [opened, synchronize]\n", ""), "types")

    def test_another_comment_type_fails(self):
        self.assertFails(planted("types: [created]", "types: [created, edited]"), "types")

    def test_triggers_in_one_line_fail_for_their_types(self):
        text = planted("""on:
  pull_request_target:
    types: [opened, synchronize]
  issue_comment:
    types: [created]
""", "on: [pull_request_target, issue_comment]\n")
        self.assertFails(text, "types")

    def test_types_as_a_block_list_or_quoted_pass(self):
        text = planted("    types: [opened, synchronize]\n", "    types:\n      - opened\n      - 'synchronize'\n")
        self.assertEqual([], problems(text))
        self.assertEqual([], problems(planted("types: [created]", 'types: ["created"]')))

    def test_another_sign_sentence_in_the_condition_fails(self):
        self.assertFails(planted(f"contains(github.event.comment.body, '{SENTENCE}')",
                                 "contains(github.event.comment.body, 'I agree')"), "sign sentence")

    def test_no_recheck_in_the_condition_fails(self):
        self.assertFails(planted("contains(github.event.comment.body, 'recheck') ||\n        ", ""), "recheck")

    def test_an_exact_comparison_in_the_condition_fails(self):
        # The action trims a comment before it compares; == would skip the job for a comment it takes.
        self.assertFails(planted(f"contains(github.event.comment.body, '{SENTENCE}')",
                                 f"github.event.comment.body == '{SENTENCE}'"), "sign sentence")
        self.assertFails(planted("contains(github.event.comment.body, 'recheck')",
                                 "github.event.comment.body == 'recheck'"), "recheck")

    # CLA.md's version and the signatures' folder: each version keeps its own record.

    def test_version_1_0_goes_with_v1(self):
        self.assertEqual([], problems(GOOD, cla("Version 1.0. This text has not yet been reviewed.")))

    def test_a_new_version_with_the_old_folder_fails(self):
        self.assertFails(GOOD, "signatures/v2/", cla("Version 2.0. This text has not yet been reviewed."))

    def test_a_minor_version_needs_its_own_folder(self):
        self.assertFails(GOOD, "signatures/v1.1/", cla("Version 1.1."))
        text = planted("signatures/v1/", "signatures/v1.1/")
        self.assertEqual([], problems(text, cla("Version 1.1.")))

    def test_a_cla_with_no_version_fails(self):
        self.assertFails(GOOD, "Version", cla("This text has not yet been reviewed."))

    def test_a_cla_with_two_versions_fails(self):
        self.assertFails(GOOD, "Version", cla("Version 1.0.\n\nVersion 2.0."))

    def test_a_tab_is_refused(self):
        self.assertFails(planted("    runs-on: ubuntu-latest\n", "    runs-on:\tubuntu-latest\n\t\n"), "tab")

    def test_a_flow_mapping_is_refused(self):
        self.assertFails(planted("      - name: Check the CLA\n", "      - { name: x, run: y }\n      - name: Check the CLA\n"),
                         "flow mapping")

    def test_an_anchor_or_alias_is_refused(self):
        self.assertFails(planted("    runs-on: ubuntu-latest\n", "    runs-on: &os ubuntu-latest\n"), "anchor")
        self.assertFails(planted("    runs-on: ubuntu-latest\n", "    runs-on: *os\n"), "anchor")

    def test_a_quoted_key_is_refused(self):
        self.assertFails(planted("      - name: Check the CLA\n", "      - \"run\": echo\n      - name: Check the CLA\n"),
                         "quoted key")

    def test_an_apostrophe_in_a_plain_value_leaves_its_comment_a_comment(self):
        text = planted("    timeout-minutes: 5\n", "    timeout-minutes: 5\n    name: who's checking # never actions/checkout\n",
                       planted("    runs-on: ubuntu-latest\n", "    runs-on: ubuntu-latest\n    timeout-minutes: 5\n"))
        self.assertEqual([], problems(text))

    def test_a_second_document_is_refused(self):
        self.assertFails(GOOD + "---\nname: other\n", "document")


class Cli(unittest.TestCase):
    def run_on(self, text, cla_text=None):
        with tempfile.TemporaryDirectory() as d:
            path = os.path.join(d, "cla.yml")
            with open(path, "w", encoding="utf-8") as f:
                f.write(text)
            extra = []
            if cla_text is not None:
                extra = [os.path.join(d, "CLA.md")]
                with open(extra[0], "w", encoding="utf-8") as f:
                    f.write(cla_text)
            return subprocess.run([sys.executable, "-I", "-B", os.path.join(HERE, "check_workflow.py"), path, *extra],
                                  capture_output=True, text=True)

    def test_exit_0_on_the_good_sample(self):
        out = self.run_on(GOOD)
        self.assertEqual(0, out.returncode, out.stdout + out.stderr)

    def test_it_reads_the_repos_cla_by_default(self):
        out = self.run_on(planted("signatures/v1/", "signatures/v9/"))
        self.assertEqual(1, out.returncode, out.stdout + out.stderr)
        self.assertIn("signatures/v1/", out.stdout)

    def test_exit_1_when_the_cla_given_has_another_version(self):
        out = self.run_on(GOOD, cla("Version 2.0."))
        self.assertEqual(1, out.returncode, out.stdout + out.stderr)
        self.assertIn("signatures/v2/", out.stdout)

    def test_exit_1_and_a_line_a_problem_on_a_bad_one(self):
        out = self.run_on(planted("  statuses: write\n", "  statuses: write\n  issues: write\n"))
        self.assertEqual(1, out.returncode, out.stdout + out.stderr)
        self.assertIn("permissions", out.stdout)


if __name__ == "__main__":
    unittest.main()
