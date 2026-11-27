#!/usr/bin/env python3
"""Tests of the release line's configuration (L588): release-please's packages, and the workflows that release the app
and its plugins, read as text.

Run: python3 -I -B -m unittest discover -s tools/release -p 'test_*.py'. Nothing here runs a workflow or calls GitHub:
the workflows are read as the files they are, and release-please.yml's plugins step runs over invented samples of the
release-please action's paths_released, with a fake gh for GitHub's API (and jq, which GitHub's runners have).

- release-please-config.json: every package but the root's is a plugin's folder, plugins/<id>, released as component
  plugin-<id> with the component in its tag (plugin-<id>-v1.2.3), and its path in the root's exclude-paths, so a commit
  that touches a plugin alone never bumps the app. Sportarr's is the first (L658). Each package has its own release pull
  request, so the app is released alone and a plugin's first release waits on its own.
- .release-please-manifest.json and each plugin's version.txt agree (L658): never released, version.txt holds the version
  release-please gives its first release, its package's initial-version; and its listing.json reads as the release reads
  it, with plugin_index.py's listing().
- release-please.yml: the apk job still follows the root's release alone; the plugins output lists each released plugin
  and its tag, built from paths_released alone, and the plugins job runs release-plugin.yml once for each.
- release-plugin.yml: no test runs while the key is in the runner, the signed build makes the APK alone, and the release
  is published as a prerelease that is never Latest, checked after.
- plugin-index.yml and CI's step "Published plugins against this build".
- Every reusable workflow: no secret required of its caller, as the key's are the release-signing environment's.
"""
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import plugin_index  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(HERE))
WORKFLOWS = os.path.join(ROOT, ".github", "workflows")
PLUGIN_SOURCE = os.path.join(ROOT, "app", "src", "main", "kotlin", "io", "skjaere", "usenet", "app", "plugins",
                             "PluginSource.kt")
ID = re.compile(r"[a-z][a-z0-9]{0,30}")


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def workflow(name):
    return read(os.path.join(WORKFLOWS, name))


# Workflows read as text: the jobs under jobs:, the steps under a job's steps:, a step's keys and its run script.

def indent(line):
    return len(line) - len(line.lstrip(" "))


def jobs(text):
    """Each job's lines, by its id."""
    lines = text.splitlines()
    start = lines.index("jobs:")
    found, current = {}, None
    for line in lines[start + 1:]:
        if line and indent(line) == 0:
            break
        match = re.fullmatch(r"  ([A-Za-z0-9_-]+):", line)
        if match:
            current = found.setdefault(match.group(1), [])
        elif current is not None:
            current.append(line)
    return found


def steps(job):
    """A job's steps, each its lines from its '- ' on, comment lines left out (a step's comment sits above it)."""
    start = job.index("    steps:")
    found = []
    for line in job[start + 1:]:
        if line.strip().startswith("#"):
            continue
        if line.strip() and indent(line) <= 4:
            break
        if line.startswith("      - "):
            found.append([line])
        elif found:
            found[-1].append(line)
    return found


def key(lines, name, level):
    """The value of key name at that indent (a step's first line counts with its '- '), or None."""
    for line in lines:
        match = re.fullmatch(" " * level + r"(?:- )?" + re.escape(name) + r": ?(.*)", line) or \
            re.fullmatch(" " * (level - 2) + r"- " + re.escape(name) + r": ?(.*)", line)
        if match:
            return match.group(1).strip()
    return None


def run(step):
    """A step's run script, its block's lines as written (or its one line)."""
    for number, line in enumerate(step):
        match = re.fullmatch(r"        (?:- )?run: ?(.*)", line) or re.fullmatch(r"      - run: ?(.*)", line)
        if match:
            if match.group(1) not in ("|", ">", "|-", ">-"):
                return match.group(1)
            block = []
            for later in step[number + 1:]:
                if later.strip() and indent(later) <= 8:
                    break
                block.append(later[10:])
            return "\n".join(block)
    return ""


def name(step):
    return key(step, "name", 8) or key(step, "id", 8) or key(step, "uses", 8)


def gradle_commands(script):
    """Each ./gradlew command in a script, its continuation lines joined, as a list of its words."""
    commands = []
    lines = script.splitlines()
    for number, line in enumerate(lines):
        if "./gradlew" not in line:
            continue
        text = line[line.index("./gradlew"):]
        while text.endswith("\\") and number + 1 < len(lines):
            number += 1
            text = text[:-1] + " " + lines[number].strip()
        commands.append(text.split())
    return commands


def tasks(command):
    """The tasks a Gradle command names: its words that are no option, quotes taken off."""
    words = [word.strip("\"'") for word in command[1:]]
    return [word for word in words if not word.startswith("-") and not word.startswith("$") and "=" not in word and
            word not in ("\\",)]


def uses(text):
    return re.findall(r"^\s*(?:- )?uses: (.+?)\s*$", text, re.M)


# Answers gh api for a plugin's version.txt from $FAKE_VERSIONS (JSON, by id), as GitHub's contents API gives a file raw;
# a 404 for an id in $FAKE_FAILS or one it doesn't know. Logs each call.
FAKE_GH = r"""#!/bin/bash
printf '%s\n' "gh $*" >> "$FAKE_LOG"
[[ $1 == api && $2 == -H && $3 == "Accept: application/vnd.github.raw" ]] || exit 98
[[ $4 =~ ^repos/$GITHUB_REPOSITORY/contents/plugins/([a-z0-9]+)/version\.txt\?ref=$GITHUB_SHA$ ]] || exit 97
id=${BASH_REMATCH[1]}
[[ " $FAKE_FAILS " != *" $id "* ]] || { echo "HTTP 404: Not Found" >&2; exit 1; }
jq -je --arg id "$id" '.[$id]' <<<"$FAKE_VERSIONS" 2>/dev/null || { echo "HTTP 404: Not Found" >&2; exit 1; }
"""


# release-please's configuration, by the rules above.

def config_problems(config, manifest, root):
    """What breaks the release line's rules in release-please's config and manifest, plugins/ under root."""
    problems = []
    packages = config.get("packages", {})
    top_component_in_tag = config.get("include-component-in-tag")
    app = packages.get(".")
    if app is None:
        return ["the app's package, '.', is missing"]
    if top_component_in_tag is not False or app.get("include-component-in-tag") or app.get("component"):
        problems.append("the app's tags must stay v<version>: no component in its tag")
    if config.get("draft") is not True:
        problems.append("releases must be made as drafts: the release workflows attach the APK before publishing")
    excluded = app.get("exclude-paths", [])
    if len(packages) > 1 and config.get("separate-pull-requests") is not True:
        problems.append("separate-pull-requests must be true: in one grouped pull request, releasing the app would release "
                        "every plugin with a change waiting, and a plugin's first release waits on its own pull request")
    if set(manifest) != set(packages):
        problems.append(f"the manifest's packages ({sorted(manifest)}) are not the config's ({sorted(packages)})")
    for path, package in packages.items():
        if path == ".":
            continue
        match = re.fullmatch(r"plugins/(.+)", path)
        plugin = match.group(1) if match else None
        if plugin is None or not ID.fullmatch(plugin) or plugin in ("true", "false"):
            problems.append(f"{path}: a package other than the app's is a plugin's folder, plugins/<id>")
            continue
        if plugin == "skeleton":
            problems.append(f"{path}: the skeleton is the platform's example and test plugin, never released (L636)")
        if package.get("component") != f"plugin-{plugin}":
            problems.append(f"{path}: its component must be plugin-{plugin}")
        if package.get("include-component-in-tag") is not True:
            problems.append(f"{path}: include-component-in-tag must be true, so its tags are plugin-{plugin}-v<version>")
        if path not in excluded:
            problems.append(f"{path}: it must be in the app's exclude-paths, or a commit to it alone bumps the app")
        if package.get("release-type") != "simple":
            problems.append(f"{path}: its release-type must be simple, which keeps plugins/{plugin}/version.txt")
        if "prerelease" in package or package.get("draft") is False:
            problems.append(f"{path}: release-plugin.yml publishes it as a prerelease from a draft: set neither "
                            "prerelease nor draft")
        listing = os.path.join(root, path, "listing.json")
        if not os.path.isfile(os.path.join(root, path, "version.txt")) or not os.path.isfile(listing):
            problems.append(f"{path}: it needs version.txt and listing.json (its name and summary in the plugin list)")
    for path in excluded:
        if path.startswith("plugins") and path not in packages:
            problems.append(f"{path}: the app's exclude-paths names it, but it is no package: it would never be released")
    return problems


# A plugin never released: 0.0.0 in the manifest, which release-please reads as no release at all (it makes a release up
# from the manifest only for another version). Its first release is then its package's initial-version, else 1.0.0,
# whatever the commits: bump-minor-pre-major only bumps a release there is. Its version.txt holds that version, which its
# builds already carry (L658).
NEVER_RELEASED = "0.0.0"
RELEASE_PLEASES_FIRST = "1.0.0"


def first_release(config, path):
    """The version release-please gives the package at path when it has never been released."""
    return config["packages"].get(path, {}).get("initial-version", config.get("initial-version", RELEASE_PLEASES_FIRST))


def version_problems(config, manifest, root):
    """Where a plugin package's version.txt and the manifest disagree (L658): never released, the manifest's 0.0.0 and in
    version.txt its first release's version; once released, release-please writes both in its release pull request, the
    same version."""
    problems = []
    for path, released in manifest.items():
        if path == ".":
            continue
        try:
            version = read(os.path.join(root, path, "version.txt")).strip()
        except OSError:
            problems.append(f"{path}: it has no version.txt")
            continue
        first = first_release(config, path)
        if released == NEVER_RELEASED and version != first:
            why = "its initial-version" if first != RELEASE_PLEASES_FIRST else "with no initial-version, its own default"
            problems.append(f"{path}: never released, release-please gives its first release {first} ({why}), but its "
                            f"version.txt holds {version}")
        elif released != NEVER_RELEASED and version != released:
            problems.append(f"{path}: released as {released} in the manifest, but its version.txt holds {version}")
    return problems


# release-please reads at most this many commits unless commit-search-depth says otherwise, and builds each package's
# notes from those alone.
RELEASE_PLEASES_COMMIT_SEARCH = 500


class ReleaseConfigTest(unittest.TestCase):
    def setUp(self):
        self.config = json.loads(read(os.path.join(ROOT, "release-please-config.json")))
        self.manifest = json.loads(read(os.path.join(ROOT, ".release-please-manifest.json")))

    def test_the_configs_packages_keep_the_rules(self):
        self.assertEqual([], config_problems(self.config, self.manifest, ROOT))

    def test_sportarrs_package_is_the_one_l658_writes_first_released_as_its_builds_are(self):
        # L658's package, and its first release 0.1.0 (versionCode 10000, L657), the version its builds carry: with no
        # initial-version, release-please would release it as 1.0.0.
        self.assertEqual({"release-type": "simple", "component": "plugin-sportarr", "include-component-in-tag": True,
                          "initial-version": "0.1.0"}, self.config["packages"].get("plugins/sportarr"))
        self.assertIn("plugins/sportarr", self.config["packages"]["."].get("exclude-paths", []))

    def test_each_plugins_version_agrees_with_the_manifest(self):
        # Sportarr's among them: 0.0.0 in the manifest and 0.1.0 in version.txt until its first release.
        self.assertIn("plugins/sportarr", self.manifest)
        self.assertEqual([], version_problems(self.config, self.manifest, ROOT))

    def test_the_version_rule_catches_a_plugin_out_of_step_with_the_manifest(self):
        root = tempfile.mkdtemp(prefix="release-versions-")
        self.addCleanup(shutil.rmtree, root, ignore_errors=True)
        os.makedirs(os.path.join(root, "plugins", "kestrel"))
        first = {"packages": {"plugins/kestrel": {"initial-version": "0.1.0"}}}
        cases = {
            ("0.0.0", "0.1.0", True): True, ("0.1.0", "0.1.0", True): True, ("1.2.3", "1.2.3", True): True,
            ("0.0.0", "1.0.0", False): True, ("0.1.0", "0.1.0", False): True,
            ("0.0.0", "0.0.0", True): False, ("0.0.0", "0.2.0", True): False, ("0.0.0", "1.0.0", True): False,
            ("0.0.0", "0.1.0", False): False, ("0.1.0", "0.2.0", True): False, ("0.2.0", "0.1.0", True): False,
        }
        for (released, version, initial), agrees in cases.items():
            with self.subTest(released=released, version=version, initial_version=initial):
                with open(os.path.join(root, "plugins", "kestrel", "version.txt"), "w") as f:
                    f.write(version + "\n")
                config = first if initial else {"packages": {"plugins/kestrel": {}}}
                self.assertEqual(agrees, not version_problems(config, {".": "0.2.0", "plugins/kestrel": released}, root))
        # A top-level initial-version counts for every package that sets none.
        self.assertEqual([], version_problems({"initial-version": "0.1.0", "packages": {"plugins/kestrel": {}}},
                                              {".": "0.2.0", "plugins/kestrel": "0.0.0"}, root))
        self.assertTrue(version_problems(first, {".": "0.2.0", "plugins/quillmere": "0.0.0"}, root), "no version.txt")

    def test_each_plugins_listing_reads_as_the_release_reads_it(self):
        # release-plugin.yml's describe reads plugins/<id>/listing.json with plugin_index.py's listing() for the plugin
        # list's name and summary: each package's must pass it now, not first at its release.
        plugins = [path for path in self.config["packages"] if path != "."]
        self.assertIn("plugins/sportarr", plugins)
        for path in plugins:
            with self.subTest(path):
                try:
                    name, summary = plugin_index.listing(os.path.join(ROOT, path, "listing.json"))
                except plugin_index.Bad as error:
                    self.fail(f"{path}: {error}")
                self.assertTrue(name.strip() and summary.strip(), "the Plugins page shows both")

    def test_the_commit_search_reaches_back_to_the_apps_last_release(self):
        # Past the app's last release, its oldest changes would be left out of its notes. While a package has no release
        # yet (Sportarr's, until its first), release-please reads that many commits on every run, whatever it finds.
        # This runs in CI too, whose checkout carries the full history and tags: CI turns red once the commits since the
        # app's last release reach commit-search-depth, and the fix then is to release the app or raise the depth.
        tag = "v" + self.manifest["."]
        done = subprocess.run(["git", "-C", ROOT, "rev-list", "--count", f"{tag}..HEAD"], capture_output=True, text=True,
                              timeout=60)
        if done.returncode != 0:
            self.skipTest(f"no {tag} in this checkout")
        since = int(done.stdout)
        depth = self.config.get("commit-search-depth", RELEASE_PLEASES_COMMIT_SEARCH)
        self.assertLess(since, depth, f"{since} commits since {tag}, but release-please reads the newest {depth} alone")

    def test_the_rules_catch_each_way_a_plugin_package_breaks_them(self):
        root = tempfile.mkdtemp(prefix="release-config-")
        self.addCleanup(shutil.rmtree, root, ignore_errors=True)
        os.makedirs(os.path.join(root, "plugins", "kestrel"))
        for name, text in (("version.txt", "0.0.0\n"), ("listing.json", '{"name": "Kestrel", "summary": ""}\n')):
            with open(os.path.join(root, "plugins", "kestrel", name), "w") as f:
                f.write(text)
        good_config = {"include-component-in-tag": False, "draft": True, "separate-pull-requests": True, "packages": {
            ".": {"release-type": "simple", "package-name": "nzstreamer", "exclude-paths": ["plugins/kestrel"]},
            "plugins/kestrel": {"release-type": "simple", "component": "plugin-kestrel", "include-component-in-tag": True},
        }}
        good_manifest = {".": "0.2.0", "plugins/kestrel": "0.0.0"}
        self.assertEqual([], config_problems(good_config, good_manifest, root))

        def broken(change, manifest=None):
            config = json.loads(json.dumps(good_config))
            change(config)
            return config_problems(config, manifest or good_manifest, root)

        cases = {
            "not in exclude-paths": (lambda c: c["packages"]["."].pop("exclude-paths"), "exclude-paths"),
            "another component": (lambda c: c["packages"]["plugins/kestrel"].update(component="kestrel"), "component"),
            "no component in its tag": (lambda c: c["packages"]["plugins/kestrel"].pop("include-component-in-tag"),
                                        "include-component-in-tag"),
            "a prerelease flag": (lambda c: c["packages"]["plugins/kestrel"].update(prerelease=True), "prerelease"),
            "no draft": (lambda c: c["packages"]["plugins/kestrel"].update(draft=False), "draft"),
            "another release type": (lambda c: c["packages"]["plugins/kestrel"].update({"release-type": "node"}),
                                     "release-type"),
            "a package outside plugins/": (lambda c: c["packages"].update({"tools/kestrel": {}}), "plugins/<id>"),
            "a nested folder": (lambda c: c["packages"].update({"plugins/kestrel/inner": {}}), "plugins/<id>"),
            "an id outside the rule": (lambda c: c["packages"].update({"plugins/Kestrel": {}}), "plugins/<id>"),
            "the skeleton": (lambda c: c["packages"].update({"plugins/skeleton": {
                "release-type": "simple", "component": "plugin-skeleton", "include-component-in-tag": True}}), "skeleton"),
            "a folder with no listing": (lambda c: c["packages"].update({"plugins/quillmere": {
                "release-type": "simple", "component": "plugin-quillmere", "include-component-in-tag": True}}),
                "listing.json"),
            "an excluded path with no package": (lambda c: c["packages"]["."]["exclude-paths"].append("plugins/quillmere"),
                                                 "never be released"),
            "the app's tags with a component": (lambda c: c.update({"include-component-in-tag": True}), "v<version>"),
            "one grouped pull request": (lambda c: c.pop("separate-pull-requests"), "separate-pull-requests"),
        }
        for case, (change, word) in cases.items():
            with self.subTest(case):
                problems = broken(change)
                self.assertTrue(any(word in problem for problem in problems), problems)
        self.assertTrue(any("manifest" in p for p in broken(lambda c: None, manifest={".": "0.2.0"})))


class ReleasePleaseWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.text = workflow("release-please.yml")
        self.jobs = jobs(self.text)

    def plugins_step(self):
        found = [step for step in steps(self.jobs["release-please"]) if key(step, "id", 8) == "plugins"]
        self.assertEqual(1, len(found), "release-please's job has one step with id plugins")
        return found[0]

    def run_plugins_step(self, paths, versions, fails=()):
        """The plugins step's script as the runner runs it (bash -eo pipefail), its env given: paths_released as PATHS,
        and a fake gh answering each plugin's version.txt at the commit from versions (not for an id in fails). Its
        outputs and the gh calls it made."""
        folder = tempfile.mkdtemp(prefix="release-please-step-")
        self.addCleanup(shutil.rmtree, folder, ignore_errors=True)
        os.makedirs(os.path.join(folder, "bin"))
        script, output, log = (os.path.join(folder, name) for name in ("step.sh", "output", "gh.log"))
        with open(script, "w") as f:
            f.write(run(self.plugins_step()) + "\n")
        gh = os.path.join(folder, "bin", "gh")
        with open(gh, "w") as f:
            f.write(FAKE_GH)
        os.chmod(gh, 0o755)
        open(output, "w").close()
        env = {"PATH": os.path.join(folder, "bin") + os.pathsep + os.environ["PATH"], "PATHS": paths, "GH_TOKEN": "token",
               "GITHUB_REPOSITORY": "skjaere/nzstreamer", "GITHUB_SHA": "c0ffee", "GITHUB_OUTPUT": output,
               "FAKE_LOG": log, "FAKE_VERSIONS": json.dumps(versions), "FAKE_FAILS": " ".join(fails)}
        done = subprocess.run(["bash", "--noprofile", "--norc", "-eo", "pipefail", script], env=env, capture_output=True,
                              text=True, timeout=60)
        self.assertEqual(0, done.returncode, done.stdout + done.stderr)
        with open(output) as f:
            lines = f.read().splitlines()
        calls = read(log).splitlines() if os.path.exists(log) else []
        return lines, calls

    def test_the_apk_job_still_follows_the_apps_release_alone(self):
        apk = self.jobs["apk"]
        self.assertEqual("needs.release-please.outputs.release_created == 'true'", key(apk, "if", 4))
        self.assertEqual("./.github/workflows/release-apk.yml", key(apk, "uses", 4))
        self.assertEqual("${{ needs.release-please.outputs.tag_name }}", key(apk, "tag", 6))

    def test_the_plugins_output_is_built_from_the_paths_released_alone(self):
        job = self.jobs["release-please"]
        self.assertEqual("${{ steps.plugins.outputs.plugins }}", key(job, "plugins", 6))
        ids = [key(step, "id", 8) for step in steps(job)]
        self.assertLess(ids.index("release"), ids.index("plugins"))
        step = self.plugins_step()
        # The action's other outputs carry release notes and the release pull request's body: in one environment
        # variable they can pass Linux's 128 KiB for one, and fail the job, the app's apk job with it.
        self.assertEqual("${{ steps.release.outputs.paths_released }}", key(step, "PATHS", 10))
        self.assertEqual("${{ github.token }}", key(step, "GH_TOKEN", 10))
        self.assertNotIn("toJSON(steps.release.outputs)", self.text)
        env = [line.strip() for line in step if re.fullmatch(r" {10}[A-Z_]+: .*", line)]
        self.assertEqual(2, len(env), env)
        self.assertIn('echo "plugins=$plugins" >>"$GITHUB_OUTPUT"', run(step))
        self.assertEqual("bash", key(step, "shell", 8))

    def test_the_plugins_job_runs_release_plugin_once_for_each_released_plugin(self):
        job = self.jobs["plugins"]
        text = "\n".join(job)
        self.assertEqual("release-please", key(job, "needs", 4))
        self.assertEqual("needs.release-please.outputs.plugins != '[]'", key(job, "if", 4))
        self.assertRegex(text, r"\n      matrix:\n        include: \$\{\{ fromJSON\(needs\.release-please\.outputs\.plugins\) \}\}")
        self.assertEqual("./.github/workflows/release-plugin.yml", key(job, "uses", 4))
        self.assertEqual("${{ matrix.id }}", key(job, "id", 6))
        self.assertEqual("${{ matrix.tag }}", key(job, "tag", 6))
        self.assertEqual("inherit", key(job, "secrets", 4))
        self.assertEqual("write", key(job, "contents", 6))

    def test_the_plugins_step_over_the_paths_released(self):
        self.assertIsNotNone(shutil.which("jq"), "jq is needed, as GitHub's runners have it")
        versions = {"kestrel": "1.0.0\n", "quillmere": "0.3.0\n"}
        cases = {
            "two plugins with the app": (json.dumps([".", "plugins/kestrel", "plugins/quillmere"]),
                                         [{"id": "kestrel", "tag": "plugin-kestrel-v1.0.0"},
                                          {"id": "quillmere", "tag": "plugin-quillmere-v0.3.0"}]),
            "a plugin alone": (json.dumps(["plugins/quillmere"]), [{"id": "quillmere", "tag": "plugin-quillmere-v0.3.0"}]),
            "the app alone": (json.dumps(["."]), []),
            "no release": ("[]", []),
            "no paths_released at all": ("", []),
        }
        for case, (paths, expected) in cases.items():
            with self.subTest(case):
                lines, calls = self.run_plugins_step(paths, versions)
                self.assertEqual(1, len(lines), lines)
                self.assertTrue(lines[0].startswith("plugins="), lines)
                self.assertEqual(expected, json.loads(lines[0][len("plugins="):]))
                # Each version from the commit released, its raw file, one call a plugin.
                self.assertEqual([f"gh api -H Accept: application/vnd.github.raw repos/skjaere/nzstreamer/contents/plugins/"
                                  f"{plugin['id']}/version.txt?ref=c0ffee" for plugin in expected], calls)
        # A version that can't be read leaves the plugin listed with no tag: its release-plugin.yml then fails loudly,
        # and this job, which the app's apk job needs, doesn't.
        lines, _ = self.run_plugins_step(json.dumps([".", "plugins/kestrel", "plugins/quillmere"]), versions, fails=("kestrel",))
        self.assertEqual([{"id": "kestrel", "tag": None}, {"id": "quillmere", "tag": "plugin-quillmere-v0.3.0"}],
                         json.loads(lines[0][len("plugins="):]))


class ReleasePluginWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.text = workflow("release-plugin.yml")
        self.jobs = jobs(self.text)
        self.steps = steps(self.jobs["plugin"])
        self.scripts = [run(step) for step in self.steps]

    def index_of(self, words):
        found = [number for number, script in enumerate(self.scripts) if words in script]
        self.assertEqual(1, len(found), f"one step runs {words!r}")
        return found[0]

    def test_it_runs_from_main_in_the_release_signing_environment(self):
        self.assertRegex(self.text, r"\npermissions: \{\}\n")
        self.assertEqual("release-signing", key(self.jobs["plugin"], "environment", 4))
        self.assertIn("[[ $GITHUB_REF == refs/heads/main ]]", self.scripts[0])
        self.assertIn("isDraft", self.scripts[0])
        self.assertRegex(self.text, r"\n  workflow_call:\n    inputs:\n      id:\n")
        self.assertRegex(self.text, r"\n  workflow_dispatch:\n    inputs:\n      id:\n")
        self.assertNotRegex(self.text, r"\n  (pull_request|pull_request_target|push):")

    def test_no_test_runs_while_the_key_is_in_the_runner(self):
        written = self.index_of("base64 --decode")
        removed = self.index_of('rm -rf "$RUNNER_TEMP/key"')
        self.assertLess(written, removed)
        self.assertEqual("always()", key(self.steps[removed], "if", 8))
        for script in self.scripts[written:removed + 1]:
            for command in gradle_commands(script):
                words = [word.strip("\"'") for word in command]
                self.assertNotIn("--tests", words)
                for task in tasks(command):
                    self.assertNotRegex(task.lower(), r"(^|:)(test|check|build$|connected)", command)
        built = [gradle_commands(script) for script in self.scripts[written:removed]]
        self.assertEqual([[], [[":plugins:$ID:assembleRelease"]]], [[tasks(c) for c in commands] for commands in built],
                         "the key's step writes it alone, and the next builds the plugin's release and nothing else")
        # The key's properties reach that build alone.
        holding = [number for number, step in enumerate(self.steps) if "ORG_GRADLE_PROJECT_beta" in "\n".join(step)]
        self.assertEqual([written + 1], holding)

    def test_the_plugins_tests_and_rules_run_before_the_key_is_written(self):
        tested = self.index_of(":plugins:$ID:test")
        self.assertLess(tested, self.index_of("base64 --decode"))
        command, = gradle_commands(self.scripts[tested])
        self.assertEqual([":plugins:$ID:test", ":plugins:$ID:checkPluginRulesDebug"], tasks(command))
        self.assertNotIn("secrets.", "\n".join(self.steps[tested]))

    def test_it_is_published_as_a_prerelease_never_latest_and_checked_after(self):
        published = self.index_of("--draft=false")
        script = self.scripts[published]
        line = next(line for line in script.splitlines() if "--draft=false" in line)
        self.assertRegex(line, r'^gh release edit "\$TAG" -R "\$GITHUB_REPOSITORY" --draft=false --prerelease --latest=false$')
        after = script[script.index(line) + len(line):]
        for words in ("isPrerelease", "isDraft", "releases/latest", "=~ ^v"):
            self.assertIn(words, after)
        self.assertNotRegex(self.text, r"--latest(?!=false)")

    def test_it_keeps_release_apks_rules(self):
        pins = set(uses(workflow("release-apk.yml")))
        for value in uses(self.text):
            if not value.startswith("./.github/workflows/"):
                self.assertIn(value, pins, "actions pinned by commit id as release-apk.yml pins them")
        for script in self.scripts:
            self.assertNotIn("--clobber", script, "bytes once attached are never replaced")
        setup = next(step for step in self.steps if (key(step, "uses", 8) or "").startswith("gradle/actions/setup-gradle@"))
        self.assertEqual("true", key(setup, "cache-disabled", 10))
        for script in self.scripts:
            self.assertNotIn("${{", script, "event values reach the shell through env alone")
        upload = self.scripts[self.index_of("gh release upload")]
        for name in ('"$RUNNER_TEMP/apk/$ID.apk"', '"$RUNNER_TEMP/apk/$ID.apk.sha256"', '"$RUNNER_TEMP/apk/plugin.json"',
                     "LICENSE.md"):
            self.assertIn(name, upload)
        self.assertIn("tools/release/check-plugin-apk.sh", "\n".join(self.scripts))
        self.assertIn("release_notes.py scan", "\n".join(self.scripts))

    def test_the_keys_secrets_are_checked_by_the_signing_job_not_required_by_the_call(self):
        # They are the release-signing environment's secrets, which only a job in that environment reads. A call's
        # required secrets are checked for each job of the called workflow, so a required one failed the index job, which
        # runs in no environment, at the first plugin release: "Secret BETA_KEYSTORE_BASE64 is required, but not
        # provided while calling", the list left unwritten. The plugin job's own check fails on any that arrives empty.
        block = re.search(r"\n  workflow_call:\n(?:.*\n)*?    secrets:\n((?:      .*\n)+)", self.text).group(1)
        names = ("BETA_KEYSTORE_BASE64", "BETA_STORE_PASSWORD", "BETA_KEY_ALIAS", "BETA_KEY_PASSWORD")
        for name in names + ("BETA_SCAN_CANARIES",):
            self.assertRegex(block, rf"(?m)^      {name}:\n        required: false$")
        checked = self.index_of("[[ -n ${!name} ]]")
        self.assertLess(checked, self.index_of("base64 --decode"))
        for name in names:
            self.assertIn(name, self.scripts[checked])
            self.assertIn(f"{name}: ${{{{ secrets.{name} }}}}", "\n".join(self.steps[checked]))

    def test_the_list_is_written_again_after_the_release(self):
        index = self.jobs["index"]
        self.assertEqual("plugin", key(index, "needs", 4))
        self.assertEqual("./.github/workflows/plugin-index.yml", key(index, "uses", 4))
        self.assertEqual("inherit", key(index, "secrets", 4))
        self.assertEqual("write", key(index, "contents", 6))


class ReusableWorkflowSecretsTest(unittest.TestCase):
    def test_no_reusable_workflow_requires_a_secret_of_its_caller(self):
        # Every secret these workflows take is the release-signing environment's, or optional: a required one fails any
        # of the called workflow's jobs that runs in no environment (see ReleasePluginWorkflowTest), and each job that
        # signs checks the key's for empty itself.
        called = [name for name in sorted(os.listdir(WORKFLOWS))
                  if name.endswith(".yml") and "\n  workflow_call:\n" in workflow(name)]
        self.assertEqual(["plugin-index.yml", "release-apk.yml", "release-plugin.yml"], called)
        for name in called:
            text = workflow(name)
            block = re.search(r"\n  workflow_call:\n((?:    .*\n|\n)*)", text).group(1)
            secrets = re.search(r"(?m)^    secrets:\n((?:      .*\n)+)", block)
            if secrets:
                self.assertNotIn("required: true", secrets.group(1), name)
            if "secrets.BETA_KEYSTORE_BASE64" in text:
                self.assertIn("[[ -n ${!name} ]] || empty+=(\"$name\")", text, name)


class PluginIndexWorkflowTest(unittest.TestCase):
    def setUp(self):
        self.text = workflow("plugin-index.yml")
        self.job = jobs(self.text)["index"]
        self.scripts = "\n".join(run(step) for step in steps(self.job))

    def test_it_rebuilds_the_list_from_main_with_no_environment(self):
        self.assertRegex(self.text, r"\non:\n  workflow_call:\n(.*\n)*  workflow_dispatch:")
        self.assertNotRegex(self.text, r"\n  (pull_request|pull_request_target|push|schedule):")
        self.assertRegex(self.text, r"\npermissions: \{\}\n")
        self.assertEqual("write", key(self.job, "contents", 6))
        self.assertIsNone(key(self.job, "environment", 4))
        self.assertEqual("plugin-index", key(self.job, "group", 6))
        self.assertEqual("false", key(self.job, "cancel-in-progress", 6))
        self.assertIn("[[ $GITHUB_REF == refs/heads/main ]]", self.scripts)
        self.assertIn("tools/release/plugin_index.py build", self.scripts)
        self.assertIn("tools/release/release_notes.py scan", self.scripts)
        self.assertIn("tools/release/canaries.py --scan", self.scripts)
        # Read from the branch, written back to it with the old blob's id, so a change made meanwhile fails the write.
        self.assertIn('gh api "repos/$GITHUB_REPOSITORY/contents/index.json?ref=plugin-index"', self.scripts)
        self.assertIn('gh api -X PUT "repos/$GITHUB_REPOSITORY/contents/index.json" --input', self.scripts)
        self.assertIn("{sha: $sha}", self.scripts)
        self.assertIn('branch: "plugin-index"', self.scripts)
        pins = set(uses(workflow("release-apk.yml")))
        for value in uses(self.text):
            self.assertIn(value, pins)
        for step in steps(self.job):
            self.assertNotIn("${{", run(step))


class CiPublishedPluginsTest(unittest.TestCase):
    def test_ci_checks_the_published_plugins_after_gradles_check(self):
        check = steps(jobs(workflow("ci.yml"))["check"])
        names = [name(step) for step in check]
        step = check[names.index("Published plugins against this build")]
        self.assertLess(names.index("Gradle check"), names.index("Published plugins against this build"))
        self.assertIn("bash tools/release/check-published-plugins.sh", run(step))
        self.assertIsNone(key(step, "continue-on-error", 8))

    def test_the_lists_address_lives_in_plugin_source_alone(self):
        self.assertRegex(read(PLUGIN_SOURCE),
                         r'\n        const val LIST = "https://raw\.githubusercontent\.com/skjaere/nzstreamer/refs/heads/'
                         r'plugin-index/index\.json"\n')
        for name in os.listdir(WORKFLOWS):
            with self.subTest(name):
                self.assertNotIn("raw.githubusercontent.com", workflow(name))



class ReadmePluginsTest(unittest.TestCase):
    """README's words on plugins (L632): what a plugin is and where its list is, the app's own address, the key that
    signs them as it signs the app, and Obtainium kept off plugin prereleases."""

    def setUp(self):
        self.readme = read(os.path.join(ROOT, "README.md"))

    def section(self, heading):
        match = re.search(r"^### " + re.escape(heading) + r"\n(.*?)(?=^#)", self.readme, re.M | re.S)
        self.assertIsNotNone(match, f"README has no ### {heading}")
        return match.group(1)

    def test_its_plugins_section_follows_not_in_every_build_and_names_the_apps_list(self):
        headings = re.findall(r"^### (.+)$", self.readme, re.M)
        self.assertEqual(headings.index("Not in every build") + 1, headings.index("Plugins"))
        plugins = self.section("Plugins")
        address = re.search(r'const val LIST = "([^"]+)"', read(PLUGIN_SOURCE)).group(1)
        for words in (address, "tools/beta-cert.sha256", "prerelease", "inside the app", "Settings › Plugins",
                      "plugin-<id>-v"):
            self.assertIn(words, plugins)
        self.assertRegex(plugins, r"[Uu]pdates? (are )?automatic|updates (itself|by itself)")

    def test_privacy_names_github_for_plugins(self):
        self.assertIn("to GitHub when you use plugins", self.section("Privacy"))

    def test_each_plugin_with_a_listing_has_a_bullet_named_as_the_list_names_it(self):
        # L659: a plugin's bullet in Plugins starts with the name its listing.json gives the plugin list (Sportarr's
        # first), so the README and the Plugins page call it the same.
        plugins = self.section("Plugins")
        listed = [folder for folder in sorted(os.listdir(os.path.join(ROOT, "plugins")))
                  if os.path.isfile(os.path.join(ROOT, "plugins", folder, "listing.json"))]
        self.assertIn("sportarr", listed)
        for folder in listed:
            with self.subTest(folder):
                name = json.loads(read(os.path.join(ROOT, "plugins", folder, "listing.json")))["name"]
                self.assertRegex(plugins, r"(?m)^- " + re.escape(name) + r": ")

    def test_install_says_only_the_apps_releases_hold_its_apk(self):
        # Plugin prereleases sit among the app's releases, each with its own <id>.apk and no NZStreamer.apk.
        install = re.search(r"^## Install\n(.*?)(?=^### )", self.readme, re.M | re.S)
        self.assertIsNotNone(install, "README has no ## Install")
        self.assertNotIn("Each release has", install.group(1))
        self.assertRegex(install.group(1), r"Each app release, tagged `v<version>`, has one APK, `NZStreamer\.apk`")
        self.assertIn("plugin", install.group(1))

    def test_staying_up_to_date_keeps_obtainium_off_plugin_prereleases(self):
        update = self.section("Staying up to date")
        self.assertIn("Include prereleases", update)
        self.assertIn("`^v`", update)


if __name__ == "__main__":
    unittest.main()
