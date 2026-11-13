#!/usr/bin/env python3
"""The release workflow's canaries, from its secret into masks and a file for the beta's secret check, and a look for
them in the release's notes.

    BETA_SCAN_CANARIES=<lines> python3 -I -B tools/release/canaries.py <out.tsv>
    BETA_SCAN_CANARIES=<lines> python3 -I -B tools/release/canaries.py --scan <text file>

The secret holds lines of label<TAB>value: strings the published beta must never carry. A wt: label's value is a common
word that library data holds too: the APK's check skips it, and the notes' scan still looks for it. Blank lines and
lines starting with # are skipped, and each label and value is trimmed. For each value of 5 characters or more this
prints ::add-mask::<value>, so the runner hides the value wherever a later step's output would show it alone (it masks a
secret only line by line, as a whole). A shorter value isn't masked: the APK's check skips it, and a mask of two letters
would blank them all over the log. Then it writes the entries to <out.tsv> (mode 600), as -PbetaScanExtra reads them,
each on its line in the secret, so the check's report names the secret's own line. With no entries it writes no file.

A line with no TAB, or with no label or no value, stops it before any file is written, named by its line number. It
never prints a label or a value but in a mask command, which the runner consumes and never shows. Exit 0 when done, 1
on a bad line, 2 when <out.tsv> is missing or already there.

With --scan it prints no mask and writes no file: it looks for each canary in the text file (a release's notes), by the
canary file's rules for text: in any case, a w:, w2: or wt: value as a whole word only, a value under 5 characters
skipped unless its label is w2:. A hit is told by its line in the secret and its line in the text, never by its label or
value. Exit 0 when the text holds none (or no canaries are given), 1 when it holds one or a line is bad, 2 when the text
file can't be read.
"""
import os
import re
import sys


def mask(value):
    """A mask command for value, escaped as the runner reads a command's data."""
    return "::add-mask::" + value.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


# Values shorter than this are neither looked for in the APK nor masked (SecretCheck's SHORTEST_CHECKED).
SHORTEST = 5


def entries(text):
    """The secret's (line number, label, value) entries, or the number of its first bad line."""
    found = []
    for number, line in enumerate(text.split("\n"), 1):
        line = line.rstrip("\r")
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        label, tab, value = line.partition("\t")
        label, value = label.strip(), value.strip()
        if not tab or not label or not value:
            return number
        found.append((number, label, value))
    return found


def numbered(found):
    """The entries as a TSV whose line N holds the secret's line N, the lines between them blank."""
    lines = []
    for number, label, value in found:
        lines += [""] * (number - 1 - len(lines))
        lines.append(f"{label}\t{value}")
    return "".join(line + "\n" for line in lines)


def plural(count, one, many):
    return f"{count} {one if count == 1 else many}"


def scan(path):
    """The text file at path against the canaries: exit 0 when it holds none, 1 when it does or a line is bad."""
    try:
        with open(path, encoding="utf-8", errors="replace") as f:
            text = f.read()
    except OSError:
        print("canaries: the text to scan can't be read", file=sys.stderr)
        return 2
    found = entries(os.environ.get("BETA_SCAN_CANARIES", ""))
    if isinstance(found, int):
        print(f"canaries: line {found} is not label<TAB>value, with both set", file=sys.stderr)
        return 1
    looked = []
    for number, label, value in found:
        word = label.startswith(("w:", "w2:", "wt:"))
        if len(value) < (2 if label.startswith("w2:") else SHORTEST):
            continue
        pattern = re.escape(value)
        looked.append((number, re.compile(r"(?<!\w)" + pattern + r"(?!\w)" if word else pattern, re.I)))
    hits = []
    for at, line in enumerate(text.split("\n"), 1):
        hits += [(number, at) for number, rx in looked if rx.search(line)]
    if not hits:
        print(f"canaries: the text holds none of the {plural(len(looked), 'canary', 'canaries')} looked for",
              file=sys.stderr)
        return 0
    for number, at in hits:
        print(f"canaries: line {number} of the canaries is in line {at} of the text", file=sys.stderr)
    count = len({number for number, _ in hits})
    print(f"canaries: the text holds {count} of the {len(looked)} looked for: don't publish it", file=sys.stderr)
    return 1


def main(argv):
    if len(argv) == 2 and argv[0] == "--scan":
        return scan(argv[1])
    if len(argv) != 1 or argv[0].startswith("-"):
        print("usage: BETA_SCAN_CANARIES=<lines> canaries.py <out.tsv> | --scan <text file>", file=sys.stderr)
        return 2
    out = argv[0]
    if os.path.lexists(out):
        print("canaries: the output file is there already: give a new path", file=sys.stderr)
        return 2
    found = entries(os.environ.get("BETA_SCAN_CANARIES", ""))
    if isinstance(found, int):
        print(f"canaries: line {found} is not label<TAB>value, with both set", file=sys.stderr)
        return 1
    if not found:
        print("canaries: none given", file=sys.stderr)
        return 0
    masked = [value for _, _, value in found if len(value) >= SHORTEST]
    for value in masked:
        print(mask(value), flush=True)
    descriptor = os.open(out, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as tsv:
        tsv.write(numbered(found))
    print(f"canaries: {len(found)} written, {len(masked)} masked (none under {SHORTEST} characters)", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
