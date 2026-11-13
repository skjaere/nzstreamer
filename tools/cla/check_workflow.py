"""
Checks the CLA workflow for what makes it safe to run on pull_request_target, where it holds a write token.

  python3 -I -B tools/cla/check_workflow.py [.github/workflows/cla.yml [CLA.md]]

- No actions/checkout and no `run:` key: it never checks out or runs a pull request's code.
- Permissions exactly actions, contents, pull-requests and statuses at write, set once for the whole workflow.
- Every `uses:` is the CLA action, pinned by its full commit id.
- Secrets: secrets.GITHUB_TOKEN is the only mention of secrets, and no personal access token is set.
- Triggers: pull_request_target and issue_comment only, each with its types listed: a pull request opened or pushed
  to, and a comment created. Not a closed or merged pull request: with lock-pullrequest-aftermerge false the action
  would only check it again, and a stray comment or a red mark is all that could come of it.
- The three commit messages are set, not empty, each on its line, with no '#' (GitHub turns '#' and a number into a
  link).
- The allowlist matches no one. Where a commit's address is linked to no account, the action matches the list
  against the commit's git name, which anyone can set, and with no list it matches an empty name. So every
  committer signs.
- path-to-signatures is in the folder for CLA.md's version: Version 1.0 in signatures/v1/, 1.1 in
  signatures/v1.1/, 2.0 in signatures/v2/. A signature stays with the version signed, so a new version starts a new
  record.
- The job's condition lets through a comment that holds `recheck` or the sign sentence that custom-pr-sign-comment
  sets, with contains(): the action trims a comment before it compares it.

It reads the file as text, so it refuses the YAML it has no need for: tabs, flow mappings, anchors and aliases,
quoted or complex keys and a second document. Another spelling can't slip past a check that way. Exit 0 when the
file passes, 1 with one line a problem.
"""
import os
import re
import sys

PIN = "contributor-assistant/github-action@ca4a40a7d1004f18d9960b404b97e5f30a505a08"
PERMISSIONS = {"actions": "write", "contents": "write", "pull-requests": "write", "statuses": "write"}
TRIGGERS = {"pull_request_target", "issue_comment"}
# The events each trigger runs on. Without a list, GitHub's default takes reopened pull requests too.
TYPES = {"pull_request_target": {"opened", "synchronize"}, "issue_comment": {"created"}}
MESSAGES = ("create-file-commit-message", "signed-commit-message", "signed-empty-commit-message")
# An entry no login or git name can equal: neither holds '<'.
ALLOWLIST = "<nobody>"
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..")
DEFAULT = os.path.join(ROOT, ".github", "workflows", "cla.yml")
DEFAULT_CLA = os.path.join(ROOT, "CLA.md")

KEY = re.compile(r"^(?P<indent> *)(?:- +)?(?P<key>[A-Za-z_][\w-]*)\s*:(?:\s+(?P<value>.*?))?\s*$")
BLOCK = re.compile(r"^[|>][-+]?$")
# What may come before a quote that opens a quoted value: nothing, or a key's colon, a list's dash, '[' or ','.
VALUE_START = re.compile(r"^(?:.*[:\[,]|\s*-)?\s*$")


def strip_comment(line):
    """The line without its comment: a '#' at the start or after a space, outside a quoted value."""
    quote = None
    for i, ch in enumerate(line):
        if quote:
            if ch == quote:
                quote = None
        elif ch in "'\"" and VALUE_START.match(line[:i]):
            quote = ch
        elif ch == "#" and (i == 0 or line[i - 1] in " \t"):
            return line[:i].rstrip()
    return line.rstrip()


def key_of(line):
    """The key a line sets (after an optional '- '), or None."""
    m = KEY.match(strip_comment(line))
    return m["key"] if m else None


def value_of(line):
    """The value on a line's key, comment stripped ('' for none)."""
    m = KEY.match(strip_comment(line))
    return (m["value"] or "") if m else ""


def unquote(value):
    value = value.strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in "'\"":
        inner = value[1:-1]
        return inner.replace("''", "'") if value[0] == "'" else inner.replace('\\"', '"')
    return value


def children(lines, start):
    """The (index, stripped line) entries of the block under lines[start], one level down."""
    indent = len(lines[start]) - len(lines[start].lstrip(" "))
    out, level = [], None
    for i in range(start + 1, len(lines)):
        text = strip_comment(lines[i])
        if not text.strip():
            continue
        own = len(text) - len(text.lstrip(" "))
        if own <= indent:
            break
        if level is None:
            level = own
        if own == level:
            out.append((i, text))
    return out


def types_of(lines, trigger):
    """The set a trigger's types: key lists, inline ([a, b]) or as a block list; None without one."""
    found = [i for i, text in children(lines, trigger) if key_of(text) == "types"]
    if len(found) != 1:
        return None
    inline = value_of(lines[found[0]])
    if inline:
        return {unquote(v) for v in inline.strip("[]").split(",") if v.strip()}
    items = set()
    for _, text in children(lines, found[0]):
        m = re.match(r"^\s*-\s+(.*)$", text)
        if not m:
            return None
        items.add(unquote(m[1]))
    return items


def refusals(lines):
    found = []
    for n, raw in enumerate(lines, 1):
        if "\t" in raw:
            found.append(f"line {n}: a tab; indent with spaces")
        text = strip_comment(raw)
        if n > 1 and re.match(r"^(---|\.\.\.)(\s|$)", text):
            found.append(f"line {n}: a second document")
        bare = re.sub(r"\$\{\{.*?\}\}", "", text)
        if "{" in bare or "}" in bare:
            found.append(f"line {n}: a flow mapping")
        if re.search(r"(^|:\s|-\s)\s*[&*][\w-]", bare) or "<<:" in bare:
            found.append(f"line {n}: an anchor, alias or merge key")
        if re.match(r"^\s*(-\s+)?[\"']", text) and re.match(r"^\s*(-\s+)?([\"']).*?\2\s*:", text):
            found.append(f"line {n}: a quoted key")
        if re.match(r"^\s*(-\s+)?\?(\s|$)", text):
            found.append(f"line {n}: a complex key")
    return found


def signatures_folder(cla):
    """The folder CLA.md's version line names ('v1' for Version 1.0), or None with the problem."""
    versions = re.findall(r"^Version ([0-9]+(?:\.[0-9]+)*)\.?(?=\s|$)", cla, re.MULTILINE)
    if len(versions) != 1:
        return None, f"CLA.md: one 'Version N.N' line is needed ({len(versions)} found)"
    parts = versions[0].split(".")
    while len(parts) > 1 and parts[-1] == "0":
        parts.pop()
    return "v" + ".".join(parts), None


def check(text, cla=None):
    """The workflow's problems; with CLA.md's text (cla), also whether its signatures go in that version's folder."""
    lines = text.splitlines()
    problems = refusals(lines)
    code = [strip_comment(line) for line in lines]
    body = "\n".join(code)

    if "actions/checkout" in body:
        problems.append("it uses actions/checkout: it must never check out a pull request's code")
    for n, line in enumerate(code, 1):
        if key_of(line) == "run":
            problems.append(f"line {n}: a run key: it must run no command")

    # Permissions: one top-level block, exactly PERMISSIONS; none per job.
    top = [i for i, line in enumerate(code) if re.match(r"^permissions\s*:", line)]
    if len(top) != 1:
        problems.append("permissions: set them once at the top, exactly " + ", ".join(sorted(PERMISSIONS)) + " at write")
    else:
        inline = value_of(code[top[0]])
        given = {key_of(child): unquote(value_of(child)) for _, child in children(code, top[0]) if key_of(child)}
        if inline:
            problems.append(f"permissions: '{inline}' instead of the four scopes at write")
        elif given != PERMISSIONS:
            problems.append(f"permissions: {given} instead of {PERMISSIONS}")
    for i, line in enumerate(code):
        if re.match(r"^\s+(-\s+)?permissions\s*:", line):
            problems.append(f"line {i + 1}: permissions set inside a job; the top level's are the only ones")

    # Actions: the CLA action only, pinned by commit.
    uses = [(i, unquote(value_of(line))) for i, line in enumerate(code) if key_of(line) == "uses"]
    if not uses:
        problems.append(f"no step uses the CLA action pinned as {PIN}")
    for i, ref in uses:
        if ref != PIN:
            problems.append(f"line {i + 1}: '{ref}' is not the CLA action pinned as {PIN}")

    # Secrets: the workflow's own token only. Each mention of secrets must be secrets.GITHUB_TOKEN, so no index and no
    # toJSON(secrets) can hand over the others.
    for m in re.finditer(r"(?<![\w-])secrets(?![\w-])", body, re.IGNORECASE):
        name = re.match(r"\s*\.\s*([A-Za-z0-9_-]+)", body[m.end():])
        if name and name[1] == "GITHUB_TOKEN" and m[0] == "secrets":
            continue
        what = f"the secret {name[1]}" if name else "secrets other than secrets.GITHUB_TOKEN"
        problems.append(f"line {body.count(chr(10), 0, m.start()) + 1}: {what}: GITHUB_TOKEN is the only one it may use")
    if "PERSONAL_ACCESS_TOKEN" in body:
        problems.append("PERSONAL_ACCESS_TOKEN is set: it must use GITHUB_TOKEN only")

    # The allowlist matches no one: an entry also matches an unlinked commit's git name, which anyone can set.
    allow = [unquote(value_of(line)) for line in code if key_of(line) == "allowlist"]
    if allow != [ALLOWLIST]:
        problems.append(f"allowlist: set it once to {ALLOWLIST}, which matches no one; the action matches an entry, "
                        "or with no list an empty name, against an unlinked commit's git name")

    # Triggers.
    on = [i for i, line in enumerate(code) if re.match(r"^on\s*:", line)]
    if len(on) != 1:
        problems.append("triggers: one top-level 'on' block is needed")
    else:
        inline = value_of(code[on[0]])
        if inline:
            names = {unquote(v) for v in inline.strip("[]").split(",") if v.strip()}
            triggers = {name: None for name in names}
        else:
            triggers = {key_of(child): i for i, child in children(code, on[0]) if key_of(child)}
            names = set(triggers)
        if names != TRIGGERS:
            problems.append(f"triggers: {sorted(names)} instead of {sorted(TRIGGERS)}")
        for name, want in sorted(TYPES.items()):
            given = types_of(code, triggers[name]) if triggers.get(name) is not None else None
            if given != want:
                shown = "no types list" if given is None else sorted(given)
                problems.append(f"types: {name} runs on {shown} instead of {sorted(want)}")

    # The three commit messages: one line each, no '#', not even in a comment after them.
    for key in MESSAGES:
        hits = [(i, raw) for i, raw in enumerate(lines) if key_of(raw) == key]
        if len(hits) != 1:
            problems.append(f"{key}: set it once ({len(hits)} found)")
            continue
        i, raw = hits[0]
        value = value_of(raw)
        if not value or BLOCK.match(value):
            problems.append(f"line {i + 1}: {key} must be one line")
        elif not unquote(value).strip():
            # The action trims it and, given nothing, uses its own message, which may hold '#' and a number.
            problems.append(f"line {i + 1}: {key} is empty: the action would use its own message")
        if "#" in raw:
            problems.append(f"line {i + 1}: {key} holds a '#'")

    # The condition lets through recheck and the sign sentence the action matches.
    sign = [unquote(value_of(line)) for line in code if key_of(line) == "custom-pr-sign-comment"]
    conds = [i for i, line in enumerate(code) if re.match(r"^\s+if\s*:", line)]
    cond = ""
    for i in conds:
        inline = value_of(code[i])
        if inline and not BLOCK.match(inline):
            cond += " " + inline
        else:
            indent = len(code[i]) - len(code[i].lstrip(" "))
            for line in code[i + 1:]:
                if line.strip() and len(line) - len(line.lstrip(" ")) <= indent:
                    break
                cond += " " + line.strip()
    def lets_through(words):
        # contains(), not ==: the action trims a comment before it compares it, and an expression can't trim.
        return re.search(r"contains\(\s*github\.event\.comment\.body\s*,\s*'" + re.escape(words) + r"'\s*\)", cond)

    if len(sign) != 1 or not sign[0]:
        problems.append("custom-pr-sign-comment: set the sign sentence once")
    elif not lets_through(sign[0]):
        problems.append("the job's condition doesn't let a comment holding the sign sentence through (contains())")
    if not lets_through("recheck"):
        problems.append("the job's condition doesn't let a comment holding recheck through (contains())")

    # Each version of CLA.md keeps its own record of signatures.
    if cla is not None:
        folder, problem = signatures_folder(cla)
        paths = [unquote(value_of(line)) for line in code if key_of(line) == "path-to-signatures"]
        if problem:
            problems.append(problem)
        elif len(paths) != 1 or not paths[0].startswith(f"signatures/{folder}/"):
            problems.append(f"path-to-signatures: CLA.md's version keeps its signatures in signatures/{folder}/")
    return problems


def main(argv):
    path = argv[1] if len(argv) > 1 else DEFAULT
    cla_path = argv[2] if len(argv) > 2 else DEFAULT_CLA
    with open(path, encoding="utf-8") as f, open(cla_path, encoding="utf-8") as c:
        found = check(f.read(), c.read())
    for p in found:
        print(f"{os.path.basename(path)}: {p}")
    if not found:
        print(f"{os.path.basename(path)}: ok")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
