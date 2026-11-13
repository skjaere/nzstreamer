"""
Tests for summarize.py: the median of each figure over a configuration's runs, with its spread.

  python3 -B tools/stream-bench/test_summarize.py
"""
import io, json, os, sys, unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import summarize  # noqa: E402


def run(mode="stream", build="debug", tls=False, **figures):
    return dict(mode=mode, build=build, tls=tls, **figures)


class SpreadTest(unittest.TestCase):
    def test_the_median_and_the_lowest_and_highest(self):
        self.assertEqual((72.0, 70.0, 80.0), summarize.spread([80.0, 70.0, 72.0]))

    def test_an_even_count_takes_the_middle_two(self):
        self.assertEqual((2.5, 1.0, 4.0), summarize.spread([4, 1, 3, 2]))

    def test_runs_without_the_figure_are_left_out(self):
        self.assertEqual((5.0, 5.0, 5.0), summarize.spread([None, 5.0, None]))
        self.assertIsNone(summarize.spread([None, None]))
        self.assertIsNone(summarize.spread([]))


class SummaryTest(unittest.TestCase):
    runs = [
        run(cpu_ms_per_mb=70.0, mb_per_s=30.0),
        run(cpu_ms_per_mb=74.0, mb_per_s=28.0),
        run(cpu_ms_per_mb=71.0, mb_per_s=29.5),
        run(tls=True, cpu_ms_per_mb=95.0, mb_per_s=25.0),
        run(build="bench", cpu_ms_per_mb=50.0, mb_per_s=36.0),
        run(mode="play", cpu_ms_per_mb=120.0, mb_per_s=9.7),
    ]

    def test_runs_are_grouped_by_mode_build_and_transport(self):
        groups = summarize.group(self.runs)
        self.assertEqual(
            [("stream", "debug", "plain"), ("stream", "debug", "tls"), ("stream", "bench", "plain"), ("play", "debug", "plain")],
            list(groups),
        )
        self.assertEqual(3, len(groups[("stream", "debug", "plain")]))

    def test_a_cell_is_the_median_then_the_range_and_a_lone_run_its_figure(self):
        groups = summarize.group(self.runs)
        self.assertEqual("71 [70-74]", summarize.cell(groups[("stream", "debug", "plain")], "cpu_ms_per_mb", 0))
        self.assertEqual("29.5 [28.0-30.0]", summarize.cell(groups[("stream", "debug", "plain")], "mb_per_s", 1))
        self.assertEqual("95", summarize.cell(groups[("stream", "debug", "tls")], "cpu_ms_per_mb", 0))
        self.assertEqual("-", summarize.cell(groups[("stream", "debug", "tls")], "gc_count", 0))

    def test_the_table_has_a_column_for_each_configuration_of_a_mode(self):
        out = io.StringIO()
        summarize.table(self.runs, out, metrics=[("cpu_ms_per_mb", "CPU ms/MB", 0), ("mb_per_s", "MB/s", 1)])
        lines = out.getvalue().splitlines()
        self.assertEqual("stream (runs: 3, 1, 1)", lines[0])
        self.assertEqual(["", "debug plain", "debug tls", "bench plain"], [c.strip() for c in lines[1].split("|")])
        self.assertEqual(["CPU ms/MB", "71 [70-74]", "95", "50"], [c.strip() for c in lines[2].split("|")])
        self.assertEqual(["MB/s", "29.5 [28.0-30.0]", "25.0", "36.0"], [c.strip() for c in lines[3].split("|")])
        self.assertIn("play (runs: 1)", lines)

    def test_runs_whose_window_isnt_valid_are_left_out_and_counted(self):
        runs = [
            run(mode="play", cpu_ms_per_mb=70.0, valid=True),
            run(mode="play", cpu_ms_per_mb=200.0, valid=False, invalid="it rebuffered 1 time"),
            # From before the test said.
            run(mode="play", cpu_ms_per_mb=72.0),
        ]
        out = io.StringIO()
        summarize.table(runs, out, metrics=[("cpu_ms_per_mb", "CPU ms/MB", 0)])
        lines = out.getvalue().splitlines()
        self.assertEqual("play (runs: 2; 1 not valid, left out)", lines[0])
        self.assertEqual(["CPU ms/MB", "71 [70-72]"], [c.strip() for c in lines[2].split("|")])

    def test_a_plays_rebuffers_and_whether_it_played_on_to_the_end_have_rows(self):
        runs = [
            run(mode="play", rebuffers=0, playing_at_end=True),
            run(mode="play", rebuffers=2, playing_at_end=False),
            run(mode="play", rebuffers=0, playing_at_end=True),
        ]
        out = io.StringIO()
        summarize.table(runs, out)
        rows = {row[0]: row[1:] for row in ([c.strip() for c in line.split("|")] for line in out.getvalue().splitlines()[1:] if "|" in line)}
        self.assertEqual(["0 [0-2]"], rows["rebuffers"])
        self.assertEqual(["2 of 3"], rows["playing at the end"])

    def test_damaged_articles_a_run_used_have_a_row(self):
        runs = [run(crc_mismatches_used=0), run(crc_mismatches_used=0), run()]
        out = io.StringIO()
        summarize.table(runs, out)
        rows = {row[0]: row[1:] for row in ([c.strip() for c in line.split("|")] for line in out.getvalue().splitlines()[1:] if "|" in line)}
        self.assertEqual(["0 [0-0]"], rows["damaged articles used"])

    def test_the_trees_measured_are_listed_and_one_that_changed_during_the_runs_says_so(self):
        runs = [run(trees={"app": "a1", "lib": "l1"}), run(trees={"app": "a1", "lib": "l2"}), run(trees={"app": "a1", "lib": "l2"})]
        out = io.StringIO()
        summarize.trees(runs, out)
        self.assertEqual(["trees measured", "  app a1", "  lib changed during the runs: l1 (1 run), l2 (2 runs)", ""], out.getvalue().split("\n")[:4])

    def test_result_lines_are_read_from_logs_with_anything_around_them(self):
        log = io.StringIO(
            "10-20 01:00:00.000 I/StreamBench( 123): something else\n"
            "10-20 01:00:01.000 I/StreamBench( 123): result " + json.dumps(run(cpu_ms_per_mb=70.0)) + "\n"
            "garbage result {not json\n"
        )
        self.assertEqual([run(cpu_ms_per_mb=70.0)], summarize.read_results(log))

    def test_bench_sh_results_are_read_a_json_object_a_line(self):
        jsonl = io.StringIO(json.dumps(run(cpu_ms_per_mb=70.0, round=1)) + "\n\n" + json.dumps(run(tls=True, round=1)) + "\n")
        self.assertEqual([run(cpu_ms_per_mb=70.0, round=1), run(tls=True, round=1)], summarize.read_results(jsonl))


if __name__ == "__main__":
    unittest.main()
