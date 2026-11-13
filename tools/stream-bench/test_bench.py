"""
Tests for bench.sh: which device its runs go to. A fake adb and a fake ./gradlew stand in for the real ones, so
nothing here reaches a device; the disk server runs for the cases that get as far as a run.

  python3 -B tools/stream-bench/test_bench.py
"""
import json, os, shutil, socket, stat, subprocess, tempfile, unittest

HERE = os.path.dirname(os.path.abspath(__file__))
CLASS = "io.skjaere.usenet.app.StreamBenchDeviceTest"

# Says what it was asked and on which device, and logs the run's result where the Gradle plugin keeps a device's log.
FAKE_RUN = r"""#!/bin/bash
set -eu
echo "${ANDROID_SERIAL:-}|$*" >> "$FAKE_CALLS"
test=$(printf '%s\n' "$@" | sed -n 's/^-Pandroid.testInstrumentationRunnerArguments.class=//p')
[ -n "$test" ] || test=$2
mode=${test#*#}
dir="app/build/outputs/androidTest-results/connected/debug/${ANDROID_SERIAL:-some device} - 14"
mkdir -p "$dir"
echo "10-20 01:00:00.000 I StreamBench: result {\"mode\": \"$mode\", \"build\": \"debug\", \"tls\": false, \"mb_per_s\": 33.0${FAKE_RESULT:+, $FAKE_RESULT}}" > "$dir/logcat-${test%#*}-$mode.txt"
exit "${FAKE_EXIT:-0}"
"""

# adb's "devices", as FAKE_DEVICES lists them; anything else asked of it fails the test.
FAKE_ADB = r"""#!/bin/bash
echo "adb $*" >> "$FAKE_CALLS.adb"
[ "$*" = devices ] || { echo "fake adb: not asked for that: $*" >&2; exit 99; }
printf 'List of devices attached\n%b\n' "$FAKE_DEVICES"
"""


def free_ports(n):
    socks = [socket.socket() for _ in range(n)]
    for s in socks:
        s.bind(("127.0.0.1", 0))
    ports = [s.getsockname()[1] for s in socks]
    for s in socks:
        s.close()
    return ports


def executable(path, text):
    with open(path, "w") as f:
        f.write(text)
    os.chmod(path, os.stat(path).st_mode | stat.S_IXUSR)


class BenchCase(unittest.TestCase):
    """bench.sh run in a repository root of its own, with the fakes; one round of one configuration."""

    def setUp(self):
        self.root = tempfile.mkdtemp(prefix="bench-test-")
        # A repository root as bench.sh wants it, with its ./gradlew.
        open(os.path.join(self.root, "settings.gradle.kts"), "w").close()
        os.mkdir(os.path.join(self.root, "app"))
        executable(os.path.join(self.root, "gradlew"), FAKE_RUN)
        self.bin = os.path.join(self.root, "bin")
        os.mkdir(self.bin)
        executable(os.path.join(self.bin, "adb"), FAKE_ADB)
        executable(os.path.join(self.bin, "runner"), FAKE_RUN)
        self.articles = os.path.join(self.root, "articles")
        os.mkdir(self.articles)
        self.nzb = os.path.join(self.root, "bench.nzb")
        open(self.nzb, "w").close()
        self.out = os.path.join(self.root, "out")
        self.calls = os.path.join(self.root, "calls")

    def tearDown(self):
        shutil.rmtree(self.root, ignore_errors=True)

    def bench(self, *args, serial=None, devices="emulator-5554\tdevice\n192.168.1.20:5555\tdevice", **fake):
        env = dict(os.environ, PATH=self.bin + os.pathsep + os.environ["PATH"], FAKE_CALLS=self.calls, FAKE_DEVICES=devices, **fake)
        env.pop("ANDROID_SERIAL", None)
        if serial is not None:
            env["ANDROID_SERIAL"] = serial
        command = ["bash", os.path.join(HERE, "bench.sh"), "--articles", self.articles, "--nzb", self.nzb, "--out", self.out,
                   "--rounds", "1", "--builds", "debug", "--transports", "plain", "--modes", "stream",
                   "--ports", " ".join(map(str, free_ports(3))), *args]
        return subprocess.run(command, cwd=self.root, env=env, capture_output=True, text=True, timeout=120)

    def gradle_calls(self):
        if not os.path.exists(self.calls):
            return []
        with open(self.calls) as f:
            return f.read().splitlines()

    def results(self):
        with open(os.path.join(self.out, "results.jsonl")) as f:
            return [json.loads(line) for line in f if line.strip()]

    def assertRefused(self, run, why):
        self.assertEqual(2, run.returncode, run.stdout + run.stderr)
        self.assertIn(why, run.stderr)
        self.assertEqual([], self.gradle_calls(), "a run went to a device")


class BenchDeviceTest(BenchCase):
    def test_without_a_serial_the_default_runner_runs_nothing(self):
        # The phone on network adb beside the emulator: the connected tests would install on both.
        self.assertRefused(self.bench(), "ANDROID_SERIAL")

    def test_a_serial_that_is_no_emulator_is_refused(self):
        self.assertRefused(self.bench(serial="192.168.1.20:5555"), "ANDROID_SERIAL")

    def test_more_than_one_serial_is_refused(self):
        self.assertRefused(self.bench(serial="emulator-5554,192.168.1.20:5555"), "ANDROID_SERIAL")

    def test_an_emulator_adb_doesnt_list_ready_is_refused(self):
        self.assertRefused(self.bench(serial="emulator-5556"), "emulator-5556")
        self.assertRefused(self.bench(serial="emulator-5554", devices="emulator-5554\toffline"), "emulator-5554")

    def test_the_runs_go_to_the_one_emulator_named_and_its_serial_is_in_the_results(self):
        run = self.bench(serial="emulator-5554")
        self.assertEqual(0, run.returncode, run.stdout + run.stderr)
        self.assertTrue(run.stdout.rstrip().endswith("RESULT: PASSED (1 runs)"), run.stdout)
        calls = self.gradle_calls()
        self.assertEqual(1, len(calls), calls)
        self.assertTrue(calls[0].startswith(f"emulator-5554|:app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class={CLASS}#stream "), calls[0])
        [result] = self.results()
        self.assertEqual("emulator-5554", result["serial"])
        self.assertEqual("emulator-5554 - 14", result["emulator"])

    def test_a_runner_of_ones_own_picks_the_device_itself(self):
        run = self.bench("--runner", os.path.join(self.bin, "runner"))
        self.assertEqual(0, run.returncode, run.stdout + run.stderr)
        self.assertEqual(1, len(self.gradle_calls()))
        [result] = self.results()
        self.assertNotIn("serial", result)
        self.assertFalse(os.path.exists(self.calls + ".adb"), "adb was asked")


class BenchValidityTest(BenchCase):
    """Runs whose window didn't measure steady streaming: the test logs its result with why, then fails."""

    WHY = '"valid": false, "invalid": "the release ended 759 MB into the 800 MB window"'

    def test_a_run_whose_window_isnt_valid_fails_and_says_why(self):
        run = self.bench(serial="emulator-5554", FAKE_RESULT=self.WHY, FAKE_EXIT="1")
        self.assertEqual(1, run.returncode, run.stdout + run.stderr)
        self.assertTrue(run.stdout.rstrip().endswith("RESULT: FAILED (1 of 1 runs failed or gave no result)"), run.stdout)
        self.assertIn("r1-debug-plain-stream: not valid, the release ended 759 MB into the 800 MB window", run.stderr)
        self.assertFalse(os.path.exists(os.path.join(self.out, "results.jsonl")))

    def test_a_result_that_isnt_valid_fails_the_run_though_its_runner_passed(self):
        run = self.bench(serial="emulator-5554", FAKE_RESULT=self.WHY)
        self.assertEqual(1, run.returncode, run.stdout + run.stderr)
        self.assertIn("not valid, the release ended 759 MB into the 800 MB window", run.stderr)
        self.assertFalse(os.path.exists(os.path.join(self.out, "results.jsonl")))

    def test_the_tls_version_goes_to_the_server_and_into_each_tls_runs_result(self):
        run = self.bench("--transports", "tls", "--tls-version", "1.3", serial="emulator-5554")
        self.assertEqual(0, run.returncode, run.stdout + run.stderr)
        with open(os.path.join(self.out, "server.log")) as f:
            self.assertIn("(TLSv1.3)", f.readline())
        self.assertEqual(["1.3"], [r["tls_version"] for r in self.results()])

    def test_a_valid_run_is_counted(self):
        run = self.bench(serial="emulator-5554", FAKE_RESULT='"valid": true')
        self.assertEqual(0, run.returncode, run.stdout + run.stderr)
        self.assertEqual([True], [r["valid"] for r in self.results()])


class BenchTreesTest(BenchCase):
    """Which code a run measured: each result names the trees it ran, as a snapshot of each work tree names it."""

    def git(self, repo, *args):
        return subprocess.run(["git", "-C", repo, *args], check=True, capture_output=True, text=True).stdout.strip()

    def repository(self, name):
        repo = os.path.join(self.root, name)
        os.mkdir(repo)
        self.git(repo, "init", "-q")
        with open(os.path.join(repo, "a.txt"), "w") as f:
            f.write("one\n")
        self.git(repo, "add", "-A")
        self.git(repo, "-c", "user.name=t", "-c", "user.email=t@example.invalid", "commit", "-q", "-m", "one")
        return repo

    def test_each_result_names_the_tree_of_each_work_tree_given_uncommitted_changes_and_all(self):
        clean, changed = self.repository("clean"), self.repository("changed")
        # Of another size: git tells a file changed in the same clock tick as its commit by its size.
        with open(os.path.join(changed, "a.txt"), "w") as f:
            f.write("one and two\n")
        with open(os.path.join(changed, "new.txt"), "w") as f:
            f.write("new\n")
        # What a run measured in it: its files as they are, build output left out.
        os.makedirs(os.path.join(changed, "build"))
        with open(os.path.join(changed, "build", "out.bin"), "w") as f:
            f.write("built\n")
        run = self.bench("--trees", f"{clean} {changed}", serial="emulator-5554")
        self.assertEqual(0, run.returncode, run.stdout + run.stderr)
        trees = self.results()[0]["trees"]
        self.assertEqual(self.git(clean, "rev-parse", "HEAD^{tree}"), trees[clean])
        self.assertNotEqual(self.git(changed, "rev-parse", "HEAD^{tree}"), trees[changed])
        # The same tree once the changes are committed, the build output not.
        self.git(changed, "add", "-A", "--", ".", ":!build")
        self.git(changed, "-c", "user.name=t", "-c", "user.email=t@example.invalid", "commit", "-q", "-m", "two")
        self.assertEqual(self.git(changed, "rev-parse", "HEAD^{tree}"), trees[changed])
        with open(os.path.join(self.out, "summary.txt")) as f:
            summary = f.read()
        self.assertIn(f"{changed} {trees[changed]}", summary)


if __name__ == "__main__":
    unittest.main()
