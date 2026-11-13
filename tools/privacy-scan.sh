#!/bin/bash
# The public privacy scan, run by CI on every push and pull request and by the maintainer's pre-push check.
#   bash tools/privacy-scan.sh <commit> [--since <before>]
# Run it in the repository's top folder. It scans the commit's tree as git has it (never the work tree) and fails on:
# - a path into a user's home folder under /home (a package path with a folder named home is not one);
# - a private, CGNAT, link-local or unique local (ULA) IP address;
# - an e-mail address, except at the reserved domains (example.com and its kin, and the top-level domains .example,
#   .invalid, .test and .localhost), the project's own addresses (the agreed identity's, GitHub's web-flow's and the
#   commit trailer's) and the ZXing NOTICE's copyright line;
# - a key file by its name (a key store, a PEM or PKCS 12 file, signing properties, .env, local.properties, ...).
# Text files only (git grep -I), and key files by their names. tools/privacy-allowlist.txt, beside this script, lists
# the samples that may stay: the tests' invented addresses and their throwaway keys, each with its reason.
# With --since <before> (a push's old tip, all zeros for a new branch), it also checks each commit the push adds, those
# a merge brings in through its other parents too: its committer is the maintainer's agreed identity or GitHub's
# web-flow, and its author the agreed identity unless GitHub committed it (a squash-merged pull request keeps its
# contributor as author). Pull requests are squash-merged, so a merge on main is a local one, which nothing else checked.
# GitHub signs every commit it makes: one naming web-flow with no signature is refused (the key itself isn't checked).
# It prints file:line and what it found there, never the value, and exits 0 when clean, 1 on a finding and 2 when it
# can't scan.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
exec python3 -I -B "$here/privacy/scan.py" --allowlist "$here/privacy-allowlist.txt" "$@"
