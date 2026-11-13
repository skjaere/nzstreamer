#!/usr/bin/env python3
"""The public privacy scan. tools/privacy-scan.sh runs it and says what it looks for.

    scan.py --allowlist <file> <commit> [--since <before>]

<commit> may be a tree id too, as for a tree written from a temp index; --since needs a commit.

It reads git objects only. Text files go through one git grep -I run, whose lines are checked here; key files are found
by their names in the tree. Findings print as "<what>: <file>:<line>", never with the value.
"""
import fnmatch
import os
import re
import subprocess
import sys

AGREED = ("skjaere", "korvusz@proton.me")
WEB_FLOW = ("GitHub", "noreply@github.com")
# The addresses the project's own commits carry: the agreed identity's, GitHub's web-flow and the commit trailer's.
OWN_ADDRESSES = {AGREED[1], WEB_FLOW[1], "noreply@anthropic.com"}
# ZXing's NOTICE keeps its copyright line, as its licence asks.
ZXING_NOTICE = "app/src/main/assets/licenses/zxing-NOTICE.txt"

HOME = re.compile(r"(?<![A-Za-z0-9_$])/home/[a-z_][a-z0-9_-]*/")
IPV4 = re.compile(r"(?<![0-9.])(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})(?![0-9]|\.[0-9])")
IPV6 = re.compile(r"(?<![0-9A-Za-z:])(?:f[cd][0-9a-f]{2}|fe[89ab][0-9a-f]):[0-9a-f:]*", re.I)
EMAIL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
# The reserved domains an example may use: example.com and its kin (mail.example.org), and the reserved top-level
# domains (host.example, host.invalid, host.test, host.localhost).
RESERVED = re.compile(r"(?:[a-z0-9-]+\.)*example\.[a-z]+|.+\.(?:example|invalid|test|localhost)", re.I)
KEY_FILES = re.compile(r"\.(?:jks|keystore|pem|p12|pfx|key|der)$|signing.*\.properties|(?:^|/)\.env|"
                       r"(?:^|/)local\.properties$|google-services\.json|(?:^|/)id_(?:rsa|ed25519|ecdsa)|\.netrc$", re.I)
# What git grep picks lines by (in any case); each line is then checked by the patterns above.
GREP = (r"/home/", r"[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}", r"f[cd][0-9a-f][0-9a-f]:", r"fe[89ab][0-9a-f]:",
        r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
KINDS = {"home": "home path", "lan": "private address", "email": "e-mail address", "key": "key file"}
# What to do about a finding, said once under them.
CLEAR_TREE = ("To clear a finding: write an example address at a reserved domain (example.com, .invalid) and an example "
              "IP address in a documentation range (192.0.2.0/24, 2001:db8::/32), keep paths relative to the repository, "
              "and commit no key file. A sample that must stay goes in tools/privacy-allowlist.txt, with its reason.")
CLEAR_IDENTITY = ("To clear an identity finding: make the commit as the agreed identity (AGREED in tools/privacy/scan.py; "
                  "set user.name and user.email in the repository), then rewrite it before the push, as "
                  "git commit --amend --reset-author does for the last one.")
ZERO = re.compile(r"0{40}|0{64}")


class Stop(Exception):
    """The scan can't run: exit 2."""


def git(*args, ok=(0,)):
    done = subprocess.run(["git", *args], capture_output=True)
    if done.returncode not in ok:
        raise Stop(f"git {args[0]} failed")
    return done.returncode, done.stdout


def read_allowlist(path):
    """kind<TAB>path glob<TAB>regex<TAB># why: a sample passes where the glob matches its file (* spans folders) and
    the regex the whole sample, in any case. Every entry says why it may stay."""
    name = "privacy-allowlist.txt"
    try:
        with open(path, encoding="utf-8") as lines:
            text = lines.read()
    except OSError:
        raise Stop(f"no {name} beside the script")
    except UnicodeDecodeError:
        raise Stop(f"{name} is not UTF-8")
    entries = []
    for number, line in enumerate(text.split("\n"), 1):
        line = line.rstrip("\r")
        if not line.strip() or line.startswith("#"):
            continue
        fields = line.split("\t")
        if len(fields) != 4:
            raise Stop(f"{name}: line {number}: an entry is kind, path glob, regex and # why, split by TABs")
        kind, glob, pattern, why = fields
        if kind not in KINDS:
            raise Stop(f"{name}: line {number}: the kind is one of {', '.join(KINDS)}")
        if not glob or not pattern:
            raise Stop(f"{name}: line {number}: an entry needs a path glob and a regex")
        if not why.startswith("#") or not why[1:].strip():
            raise Stop(f"{name}: line {number}: an entry needs a comment saying why it may stay")
        try:
            regex = re.compile(pattern, re.I)
        except re.error:
            raise Stop(f"{name}: line {number}: the regex doesn't compile")
        entries.append((kind, glob, regex))
    return entries


def allowed(entries, kind, path, sample):
    return any(k == kind and fnmatch.fnmatchcase(path, glob) and regex.fullmatch(sample) for k, glob, regex in entries)


def private_v4(match):
    a, b, c, d = (int(x) for x in match.groups())
    if max(a, b, c, d) > 255:
        return False
    return a == 10 or (a == 172 and 16 <= b <= 31) or (a == 192 and b == 168) or (a == 100 and 64 <= b <= 127) \
        or (a == 169 and b == 254)


def samples(path, text):
    """What a line holds, as (kind, sample)."""
    found = [("home", m.group(0)) for m in HOME.finditer(text)]
    found += [("lan", m.group(0)) for m in IPV4.finditer(text) if private_v4(m)]
    found += [("lan", m.group(0)) for m in IPV6.finditer(text) if m.group(0).count(":") >= 2]
    for m in EMAIL.finditer(text):
        address = m.group(0)
        local, _, domain = address.partition("@")
        if RESERVED.fullmatch(domain) or address.lower() in OWN_ADDRESSES or path == ZXING_NOTICE:
            continue
        # Kotlin's labelled this or super, this@Outer before a member, is not an address.
        if path.endswith((".kt", ".kts")) and local in ("this", "super"):
            continue
        found.append(("email", address))
    return found


def grep_lines(rev):
    """Each (path, line number, text) of the tree's text files that git grep picks."""
    args = ["grep", "-I", "-n", "-z", "-i", "-E"]
    for pattern in GREP:
        args += ["-e", pattern]
    _, out = git(*args, rev, ok=(0, 1))
    prefix = (rev + ":").encode()
    at = 0
    while at < len(out):
        name_end = out.index(b"\0", at)
        number_end = out.index(b"\0", name_end + 1)
        line_end = out.find(b"\n", number_end + 1)
        line_end = len(out) if line_end < 0 else line_end
        name = out[at:name_end]
        if name.startswith(prefix):
            name = name[len(prefix):]
        yield (name.decode("utf-8", "surrogateescape"), int(out[name_end + 1:number_end]),
               out[number_end + 1:line_end].decode("utf-8", "replace"))
        at = line_end + 1


def scan_tree(rev, entries):
    findings = set()
    for path, number, text in grep_lines(rev):
        for kind, sample in samples(path, text):
            if not allowed(entries, kind, path, sample):
                findings.add((path, number, KINDS[kind]))
    lines = [f"{what}: {path}:{number}" for path, number, what in sorted(findings)]
    _, out = git("ls-tree", "-r", "-z", "--name-only", "--full-tree", rev)
    for raw in out.split(b"\0"):
        path = raw.decode("utf-8", "surrogateescape")
        if path and KEY_FILES.search(path) and not allowed(entries, "key", path, path):
            lines.append(f"{KINDS['key']}: {path}")
    return lines


def who(name, email):
    return name, email.lower()


def signed(shas):
    """Which of these commits carry a signature header. GitHub signs every commit it makes as web-flow; the scan can't
    check its key, but one with no signature at all was made elsewhere, under GitHub's name."""
    if not shas:
        return set()
    done = subprocess.run(["git", "cat-file", "--batch"], input="".join(sha + "\n" for sha in shas).encode(),
                          capture_output=True)
    if done.returncode != 0:
        raise Stop("git cat-file failed")
    out, at, found = done.stdout, 0, set()
    for sha in shas:
        header_end = out.index(b"\n", at)
        size = int(out[at:header_end].split()[2])
        body = out[header_end + 1:header_end + 1 + size]
        at = header_end + 1 + size + 1
        headers = body.split(b"\n\n", 1)[0]
        if any(line.startswith((b"gpgsig ", b"gpgsig-sha256 ")) for line in headers.split(b"\n")):
            found.add(sha)
    return found


def scan_identities(rev, before):
    """Every commit a push adds, a merge's other parents' too, each with an identity the push may carry. Pull requests are
    squash-merged, so GitHub adds no merge commit: a merge is a local one, whose other parents no pull request checked."""
    if ZERO.fullmatch(before):
        span = [rev]
    else:
        code, out = git("rev-parse", "--verify", "--quiet", before + "^{commit}", ok=(0, 1))
        if code != 0:
            raise Stop("--since names no commit here: fetch the history before the push first")
        span = [out.decode().strip() + ".." + rev]
    _, out = git("log", "--format=%H%x1f%an%x1f%ae%x1f%cn%x1f%ce", *span, "--")
    agreed, web_flow = who(*AGREED), who(*WEB_FLOW)
    records = [record.split("\x1f") for record in out.decode("utf-8", "replace").splitlines()]
    claims = signed([sha for sha, _, _, name, email in records if who(name, email) == web_flow])
    lines = []
    for sha, author_name, author_email, committer_name, committer_email in records:
        author, committer = who(author_name, author_email), who(committer_name, committer_email)
        # GitHub's web-flow as committer counts only on a signed commit, as every commit GitHub makes is.
        github = committer == web_flow and sha in claims
        if committer == web_flow and not github:
            lines.append(f"identity: commit {sha[:12]}: it names GitHub's web-flow as committer but carries no signature")
        elif committer not in (agreed, web_flow):
            lines.append(f"identity: commit {sha[:12]}: its committer is neither the agreed identity nor GitHub's web-flow")
        if not github and author != agreed:
            lines.append(f"identity: commit {sha[:12]}: its author is not the agreed identity")
    return lines


def main(argv):
    # A path git stores in another encoding prints escaped, never as a crash.
    sys.stdout.reconfigure(errors="backslashreplace")
    sys.stderr.reconfigure(errors="backslashreplace")
    allowlist = since = rev = None
    args = list(argv)
    while args:
        arg = args.pop(0)
        if arg in ("--allowlist", "--since") and args and not args[0].startswith("-"):
            value = args.pop(0)
            if arg == "--allowlist" and allowlist is None:
                allowlist = value
            elif arg == "--since" and since is None:
                since = value
            else:
                return usage()
        elif not arg.startswith("-") and rev is None:
            rev = arg
        else:
            return usage()
    if allowlist is None or rev is None:
        return usage()
    try:
        entries = read_allowlist(allowlist)
        code, top = git("rev-parse", "--show-toplevel", ok=(0, 128))
        if code != 0:
            raise Stop("not in a git repository")
        os.chdir(top.decode().strip())
        code, out = git("rev-parse", "--verify", "--quiet", rev + "^{tree}", ok=(0, 1))
        if code != 0:
            raise Stop(f"{rev} is not a commit or a tree here")
        _, full = git("rev-parse", "--verify", rev)
        full = full.decode().strip()
        if since is not None:
            code, _ = git("rev-parse", "--verify", "--quiet", full + "^{commit}", ok=(0, 1))
            if code != 0:
                raise Stop("--since needs a commit to scan, not a tree")
        lines = scan_tree(full, entries)
        if since is not None:
            lines += scan_identities(full, since)
    except Stop as stop:
        print(f"privacy-scan: {stop}", file=sys.stderr)
        return 2
    for line in lines:
        print(f"privacy-scan: {line}")
    if lines:
        print(f"privacy-scan: {len(lines)} finding(s)")
        if any(not line.startswith("identity:") for line in lines):
            print(f"privacy-scan: {CLEAR_TREE}")
        if any(line.startswith("identity:") for line in lines):
            print(f"privacy-scan: {CLEAR_IDENTITY}")
        return 1
    print("privacy-scan: clean")
    return 0


def usage():
    print("usage: tools/privacy-scan.sh <commit> [--since <before>]", file=sys.stderr)
    return 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
