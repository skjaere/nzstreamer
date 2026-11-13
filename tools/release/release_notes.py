#!/usr/bin/env python3
"""A release's notes: the licence footer the release workflow adds, and the privacy scan they pass first.

    release_notes.py pins <libs.versions.toml>
        Prints owner/repo<TAB>version for each library the catalog takes from JitPack (com.github.<owner>:<repo>).
    release_notes.py notes <body> <libraries.tsv>
        Prints the body with the licence footer at its end, in place of any footer an earlier run added. The libraries
        are owner/repo<TAB>version<TAB>commit<TAB>yes|no, the last saying whether the library has THIRD_PARTY_NOTICES.md
        at that commit.
    release_notes.py scan <file>
        Runs tools/privacy-scan.sh on the file, as the only file of a tree, named release-notes.txt.

Until the app shows its licences itself, each release says them here, and the workflow attaches LICENSE.md,
THIRD_PARTY_NOTICES.md and Apache-2.0.txt. The libraries' links name the commit each version was built from.
Exit 0 when done, 1 on bad input or a scan finding, 2 on bad arguments.
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile
import tomllib

HERE = os.path.dirname(os.path.abspath(__file__))
PRIVACY_SCAN = os.path.join(os.path.dirname(HERE), "privacy-scan.sh")
MARKER = "<!-- release-apk: licences -->"
NAME = re.compile(r"[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?")
VERSION = re.compile(r"[A-Za-z0-9][A-Za-z0-9._+-]*")
COMMIT = re.compile(r"[0-9a-f]{40}")


class Bad(Exception):
    """Input the command can't take: exit 1."""


def pins(catalog):
    try:
        with open(catalog, "rb") as f:
            data = tomllib.load(f)
    except (OSError, tomllib.TOMLDecodeError) as error:
        raise Bad(f"can't read the catalog: {error.__class__.__name__}")
    versions = data.get("versions", {})
    lines = []
    for alias, library in data.get("libraries", {}).items():
        module = library.get("module", "") if isinstance(library, dict) else ""
        group, _, repo = module.partition(":")
        if not group.startswith("com.github."):
            continue
        owner = group[len("com.github."):]
        version = library.get("version")
        if isinstance(version, dict):
            version = versions.get(version.get("ref"))
        if not (NAME.fullmatch(owner) and NAME.fullmatch(repo) and isinstance(version, str) and VERSION.fullmatch(version)):
            raise Bad(f"the library {alias} has no owner, repository and plain version")
        lines.append(f"{owner}/{repo}\t{version}")
    return lines


def footer(libraries):
    lines = [MARKER, "---",
             "**Licences.** NZStreamer is under the PolyForm Noncommercial License 1.0.0: LICENSE.md, attached. The",
             "third-party material in the APK is listed in THIRD_PARTY_NOTICES.md, attached with Apache-2.0.txt, the",
             "Apache License 2.0's text. The usenet libraries in it, at the versions it is built from:", ""]
    for repo, version, commit, notices in libraries:
        blob = f"https://github.com/{repo}/blob/{commit}"
        links = [f"[licence]({blob}/LICENSE.md)"]
        if notices == "yes":
            links.append(f"[third-party notices]({blob}/THIRD_PARTY_NOTICES.md)")
        lines.append(f"- {repo.split('/')[1]} {version} ({commit[:12]}): {', '.join(links)}")
    return "\n".join(lines) + "\n"


def read_libraries(path):
    libraries = []
    with open(path, encoding="utf-8") as f:
        for number, line in enumerate(f.read().splitlines(), 1):
            fields = line.split("\t")
            if len(fields) != 4:
                raise Bad(f"libraries line {number}: four fields, split by TABs")
            repo, version, commit, notices = fields
            owner, _, name = repo.partition("/")
            if not (NAME.fullmatch(owner) and NAME.fullmatch(name) and VERSION.fullmatch(version)
                    and COMMIT.fullmatch(commit) and notices in ("yes", "no")):
                raise Bad(f"libraries line {number}: owner/repo, a version, a commit id and yes or no")
            libraries.append(fields)
    if not libraries:
        raise Bad("no libraries given")
    return libraries


def notes(body_path, libraries_path):
    libraries = read_libraries(libraries_path)
    with open(body_path, encoding="utf-8") as f:
        body = f.read().split(MARKER, 1)[0].rstrip()
    return (body + "\n\n" if body else "") + footer(libraries)


def scan(path):
    with tempfile.TemporaryDirectory(prefix="release-notes-scan-") as repo:
        shutil.copyfile(path, os.path.join(repo, "release-notes.txt"))
        git = ["git", "-c", "init.defaultBranch=main", "-C", repo]
        subprocess.run([*git, "init", "-q"], check=True)
        subprocess.run([*git, "add", "release-notes.txt"], check=True)
        tree = subprocess.run([*git, "write-tree"], check=True, capture_output=True, text=True).stdout.strip()
        return subprocess.run(["bash", PRIVACY_SCAN, tree], cwd=repo).returncode


def main(argv):
    command, args = (argv[0], argv[1:]) if argv else (None, [])
    try:
        if command == "pins" and len(args) == 1:
            print("\n".join(pins(args[0])))
        elif command == "notes" and len(args) == 2:
            sys.stdout.write(notes(*args))
        elif command == "scan" and len(args) == 1:
            return 0 if scan(args[0]) == 0 else 1
        else:
            print("usage: release_notes.py pins <catalog> | notes <body> <libraries.tsv> | scan <file>", file=sys.stderr)
            return 2
    except (Bad, OSError, UnicodeDecodeError) as error:
        print(f"release_notes: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
