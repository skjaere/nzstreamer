"""
The stream bench's figures over its runs: each figure's median for a configuration (mode, build, plain or TLS), with
the lowest and highest run beside it. Reads StreamBenchDeviceTest's "result {json}" lines, one a run, from logcat
files, or bench.sh's results.jsonl, and prints a table for each mode. A run whose window the test found not valid (a
stall, a stop, the release's end) is left out.

  python3 -I tools/stream-bench/summarize.py RESULTS...
"""
import json, sys

# What the table shows, in order: the result's key, its label, its decimals (None for a yes or no).
METRICS = [
    ("mb_per_s", "streamed MB/s", 1),
    ("seconds", "window s", 1),
    ("mb", "window MB", 0),
    ("cpu_ms_per_mb", "CPU ms/MB", 1),
    ("cores", "cores busy", 2),
    ("workers_ms_per_mb", "  workers", 1),
    ("loader_ms_per_mb", "  loader", 1),
    ("playback_ms_per_mb", "  playback", 1),
    ("codec_ms_per_mb", "  codec", 1),
    ("gc_ms_per_mb", "  GC", 1),
    ("jit_ms_per_mb", "  JIT", 1),
    ("rest_ms_per_mb", "  rest", 1),
    ("alloc_mb_per_mb", "allocated MB/MB", 2),
    ("gc_count", "GCs", 0),
    ("gc_ms", "GC time ms", 0),
    ("blocking_gc_count", "blocking GCs", 0),
    ("blocking_gc_ms", "blocking GC ms", 0),
    ("fetched_mb_per_s", "fetched MB/s", 1),
    ("busy_connections", "connections busy", 1),
    ("connection_mb_per_s", "MB/s a connection", 2),
    ("dropped_frames", "dropped frames", 0),
    ("rebuffers", "rebuffers", 0),
    ("playing_at_end", "playing at the end", None),
    ("crc_mismatches_used", "damaged articles used", 0),
]


def spread(values):
    """The median of [values] and the lowest and highest, leaving out runs without it (None); None if none has it."""
    xs = sorted(v for v in values if v is not None)
    if not xs:
        return None
    middle = len(xs) // 2
    median = xs[middle] if len(xs) % 2 else (xs[middle - 1] + xs[middle]) / 2
    return median, xs[0], xs[-1]


def key(result):
    return result["mode"], result["build"], "tls" if result["tls"] else "plain"


def valid(result):
    """Whether the run's window measured steady streaming, as the test says ("valid"); a result from before it said is."""
    return result.get("valid") is not False


def group(results):
    """The runs of each configuration, (mode, build, "plain" or "tls"), in the order they first came."""
    groups = {}
    for result in results:
        groups.setdefault(key(result), []).append(result)
    return groups


def cell(runs, metric, decimals):
    """
    'median [lowest-highest]' of [metric] over [runs]; the figure alone for one run; '-' for none. A yes or no
    ([decimals] None): 'yeses of runs that have it'.
    """
    if decimals is None:
        said = [r[metric] for r in runs if r.get(metric) is not None]
        return f"{sum(1 for x in said if x)} of {len(said)}" if said else "-"
    s = spread([r.get(metric) for r in runs])
    if s is None:
        return "-"
    median, low, high = (f"{x:.{decimals}f}" for x in s)
    return median if sum(r.get(metric) is not None for r in runs) == 1 else f"{median} [{low}-{high}]"


def table(results, out=sys.stdout, metrics=METRICS):
    """
    For each mode, a row for each of [metrics] that any run has, a column for each configuration. The runs not valid
    are left out, and counted in the mode's heading.
    """
    groups = group(r for r in results if valid(r))
    left_out = {}
    for r in results:
        if not valid(r):
            left_out[r["mode"]] = left_out.get(r["mode"], 0) + 1
    for mode in dict.fromkeys(r["mode"] for r in results):
        columns = [(k, runs) for k, runs in groups.items() if k[0] == mode]
        heading = f"{mode} (runs: {', '.join(str(len(runs)) for _, runs in columns) or 'none'}"
        if mode in left_out:
            heading += f"; {left_out[mode]} not valid, left out"
        print(heading + ")", file=out)
        if not columns:
            print(file=out)
            continue
        rows = [[""] + [f"{build} {transport}" for (_, build, transport), _ in columns]]
        for metric, label, decimals in metrics:
            if any(r.get(metric) is not None for _, runs in columns for r in runs):
                rows.append([label] + [cell(runs, metric, decimals) for _, runs in columns])
        widths = [max(len(row[i]) for row in rows) for i in range(len(rows[0]))]
        for row in rows:
            print(" | ".join(c.ljust(w) for c, w in zip(row, widths)).rstrip(), file=out)
        print(file=out)


def read_results(lines):
    """
    The results in [lines]: what follows "result " on a line of a log, or a line that is a JSON object (bench.sh's
    results.jsonl), as JSON; lines that are neither are skipped.
    """
    results = []
    for line in lines:
        at = line.find("result {")
        text = line[at + len("result "):] if at >= 0 else line.strip()
        if not text.startswith("{"):
            continue
        try:
            results.append(json.loads(text))
        except ValueError:
            continue
    return results


def trees(results, out=sys.stdout):
    """
    The trees the runs measured (bench.sh --trees), each work tree's tree id: one line each, or each id with how many
    runs had it when the tree changed during the runs.
    """
    seen = {}
    for r in results:
        for path, tree in (r.get("trees") or {}).items():
            seen.setdefault(path, {})
            seen[path][tree] = seen[path].get(tree, 0) + 1
    if not seen:
        return
    print("trees measured", file=out)
    for path, ids in seen.items():
        if len(ids) == 1:
            print(f"  {path} {next(iter(ids))}", file=out)
        else:
            each = ", ".join(f"{tree} ({n} run{'' if n == 1 else 's'})" for tree, n in ids.items())
            print(f"  {path} changed during the runs: {each}", file=out)
    print(file=out)


def main(paths):
    results = []
    for path in paths:
        with open(path, errors="replace") as f:
            results += read_results(f)
    trees(results)
    table(results)


if __name__ == "__main__":
    main(sys.argv[1:])
