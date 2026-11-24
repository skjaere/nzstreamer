#!/usr/bin/env python3
"""The plugin list (L589) and what each plugin release says of itself, as the release workflows write them.

    plugin_index.py describe --id <id> --tag <tag> --repo <owner/name> --apk <file> --listing <listing.json> --out <file>
        Writes a plugin release's plugin.json from its staged APK's own bytes: its package, versions, SDKs and meta-data
        from build-tools' aapt2 (dump badging --include-meta-data), its one signer from apksigner (verify
        --print-certs), its SHA-256 and size; its name and summary from the plugin's listing.json, and its address, the
        release's <id>.apk. The newest build-tools under $ANDROID_HOME, as check-plugin-apk.sh takes them. So the list says
        what the file is, not what the build meant. release-plugin.yml runs it after check-plugin-apk.sh.
    plugin_index.py build --repo <owner/name> --signer <beta-cert.sha256> --out <file> [--current <file>] [--now <time>]
        Rebuilds the whole list from the repository's releases, read with gh api (plugin-index.yml): the published
        prereleases tagged plugin-<id>-v<MAJOR.MINOR.PATCH>, each plugin.json checked against its tag and its APK asset
        (its address, its size, and GitHub's SHA-256 digest of it, which every asset must have) and its signer against
        the beta key's certificate digest. A release titled "Withdrawn: ..." puts its versionCode in its plugin's
        withdrawn list and leaves its version out. Each plugin keeps the newest version of each plugin API and set of
        shared libraries: an app accepts no library newer than its own (L584), so a build made after a library bump,
        before an NZStreamer with it is out, leaves listed the last version that the apps not updated yet can load. A
        plugin with more than 20 such versions keeps its newest 20, with a warning. The list holds L589's other caps (50
        plugins, 256 KiB) and the app's rules for its words, or none is written. Its time, generated, is now (or --now,
        UTC as 2026-12-01T10:00:00Z), never before --current's; where the plugins are the same as --current's, its time
        stays, so the file is the same byte for byte and the workflow writes nothing.
    plugin_index.py builds --list <file> --releases <address> --api-major <n>
        Prints each version the list gives that was built against plugin API major <n>, a line each with id, versionCode,
        address, size and SHA-256, TAB-separated, in the list's order: what CI's step "Published plugins against this
        build" downloads. An entry outside the app's rules fails: CI writes the list, so one is a fault, never a skip.
        <address> is where the app takes plugin releases from (PluginSource's), as https://github.com/<owner>/<name>/
        releases/download/.

The list is JSON with sorted keys, two spaces' indent and a line break at its end, plugins by id, versions newest first,
so the same releases give the same bytes. It never says what signs a plugin: the app trusts its own pin alone (C19).
Exit 0 when done, 1 when a check fails (nothing written), 2 on bad arguments.
"""
import datetime
import hashlib
import json
import os
import re
import subprocess
import sys

FORMAT = 1
MAX_BYTES = 256 * 1024
MAX_PLUGINS = 50
MAX_VERSIONS = 20
MAX_NAME = 40
MAX_SUMMARY = 200
MAX_VERSION_NAME = 20
MIN_SDK_MAX = 1000

ID = re.compile(r"[a-z][a-z0-9]{0,30}")
VERSION = r"(0|[1-9][0-9]{0,3})\.(0|[1-9][0-9]?)\.(0|[1-9][0-9]?)"
TAG = re.compile(r"plugin-([a-z][a-z0-9]{0,30})-v(" + VERSION + r")")
REPO = re.compile(r"[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?/[A-Za-z0-9._-]+")
API = re.compile(r"(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})")
HASH = re.compile(r"[0-9a-f]{64}")
LIBRARY = re.compile(r"[a-z0-9][a-z0-9.-]*")
TIME = re.compile(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z")
# The characters the app refuses in a list's words (PluginIndex.plainText): control characters, the line and paragraph
# separators, and those that turn the text's direction, which could make a name read as another.
DIRECTION = {"؜", "‎", "‏", " ", " "} | {chr(c) for c in range(0x202A, 0x202F)} | \
    {chr(c) for c in range(0x2066, 0x206A)}
# apksigner's signer lines: "Signer #1 certificate SHA-256 digest: <hex>" up to build-tools 36, "V2 Signer: certificate
# SHA-256 digest: <hex>" from 37, once for each scheme it verified. A signer is a certificate, so the signers are the
# distinct digests.
SIGNER = re.compile(r"^(?:Signer #[0-9]+ |V[0-9.]+ Signer: )certificate SHA-256 digest: ([0-9a-f]{64})$", re.M)


class Bad(Exception):
    """A check that failed: exit 1, nothing written."""


class Usage(Exception):
    """Arguments the command can't take: exit 2."""


def plugin_id(text):
    return text if ID.fullmatch(text) and text not in ("true", "false") else None


def version_code(version):
    major, minor, patch = (int(part) for part in version.split("."))
    return ((major * 100 + minor) * 100 + patch) * 100


def plain(text, longest, blank):
    """Whether text keeps the app's rule for the list's words: at most longest characters, none of the refused ones."""
    return (isinstance(text, str) and len(text) <= longest and (blank or text.strip() != "")
            and not any(ord(c) < 0x20 or 0x7F <= ord(c) <= 0x9F or c in DIRECTION for c in text))


def libraries(text):
    """name=version,... as a map; None where it isn't one, or names a library twice."""
    found = {}
    for pair in text.split(","):
        name, equals, version = pair.partition("=")
        if not equals or not LIBRARY.fullmatch(name) or not version or "," in version or name in found:
            return None
        found[name] = version
    return found


def utc(text):
    if not isinstance(text, str) or not TIME.fullmatch(text):
        return None
    try:
        return datetime.datetime.strptime(text, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=datetime.timezone.utc)
    except ValueError:
        return None


def dumped(value):
    return json.dumps(value, indent=2, sort_keys=True, ensure_ascii=False) + "\n"


def write_new(path, text):
    """Writes text to path, which must not be there yet."""
    with open(path, "x", encoding="utf-8", newline="\n") as f:
        f.write(text)


# describe

def build_tools():
    home = os.environ.get("ANDROID_HOME", "")
    folder = os.path.join(home, "build-tools")
    if not home or not os.path.isdir(folder):
        raise Usage("ANDROID_HOME is not set to an SDK with build-tools: aapt2 and apksigner are needed")

    def version(name):
        return [int(part) if part.isdigit() else -1 for part in re.split(r"[.-]", name)]

    newest = os.path.join(folder, max(os.listdir(folder), key=version))
    tools = {tool: os.path.join(newest, tool) for tool in ("aapt2", "apksigner")}
    if not all(os.access(path, os.X_OK) for path in tools.values()):
        raise Usage(f"no aapt2 and apksigner in {folder}'s newest build-tools")
    return tools


def tool_output(command, what):
    done = subprocess.run(command, capture_output=True, text=True)
    if done.returncode != 0:
        raise Bad(f"{what} failed (exit {done.returncode})")
    return done.stdout


def one_signer(certs):
    digests = sorted(set(SIGNER.findall(certs)))
    if len(digests) != 1:
        raise Bad(f"the APK has {len(digests)} signers: a plugin release has one, the beta key")
    return digests[0]


def describe(args):
    pid = plugin_id(args["id"])
    tag = TAG.fullmatch(args["tag"])
    if pid is None:
        raise Usage(f"--id takes a plugin's id, a lower-case letter and up to 30 lower-case letters and digits, not "
                    f"'{args['id']}'")
    if tag is None or tag.group(1) != pid or int(tag.group(3)) >= 2100:
        raise Usage(f"--tag takes plugin-{pid}-vMAJOR.MINOR.PATCH, MAJOR under 2100, not '{args['tag']}'")
    if not REPO.fullmatch(args["repo"]):
        raise Usage("--repo takes owner/name")
    if os.path.lexists(args["out"]):
        raise Usage("the output file is there already: give a new path")
    version = tag.group(2)
    tools = build_tools()
    apk = args["apk"]
    with open(apk, "rb") as f:
        data = f.read()
    badging = tool_output([tools["aapt2"], "dump", "badging", "--include-meta-data", apk], "aapt2 dump badging")
    signer = one_signer(tool_output([tools["apksigner"], "verify", "--print-certs", apk], "apksigner verify"))

    package = re.search(r"^package: name='([^']*)' versionCode='([^']*)' versionName='([^']*)'", badging, re.M)
    meta = dict(re.findall(r"^meta-data: name='([^']*)' value='([^']*)'", badging, re.M))
    min_sdk = re.search(r"^(?:minSdkVersion|sdkVersion):'([0-9]+)'", badging, re.M)
    target_sdk = re.search(r"^targetSdkVersion:'([0-9]+)'", badging, re.M)
    expected = (f"app.nzstreamer.plugin.{pid}", str(version_code(version)), version)
    if package is None or package.groups() != expected:
        raise Bad(f"the APK is {package.groups() if package else 'no package'}, not {expected}: package, versionCode and "
                  f"versionName from {args['tag']}")
    if meta.get("nzs.plugin.id") != pid:
        raise Bad(f"the APK's nzs.plugin.id isn't {pid}")
    major, minor = meta.get("nzs.plugin.api.major", ""), meta.get("nzs.plugin.api.minor", "")
    api = f"{major}.{minor}"
    if not API.fullmatch(api):
        raise Bad("the APK's plugin API stamp isn't two numbers, nzs.plugin.api.major and nzs.plugin.api.minor")
    libs = libraries(meta.get("nzs.plugin.libs", ""))
    if libs is None:
        raise Bad("the APK's nzs.plugin.libs isn't a list of name=version, each library once")
    if min_sdk is None or target_sdk is None:
        raise Bad("aapt2 gives the APK no minSdkVersion or targetSdkVersion")
    name, summary = listing(args["listing"])
    described = {
        "format": FORMAT, "id": pid, "package": expected[0], "name": name, "summary": summary,
        "versionCode": int(expected[1]), "versionName": version, "api": api, "libs": libs,
        "minSdk": int(min_sdk.group(1)), "targetSdk": int(target_sdk.group(1)),
        "url": f"https://github.com/{args['repo']}/releases/download/{args['tag']}/{pid}.apk",
        "size": len(data), "sha256": hashlib.sha256(data).hexdigest(), "signer": signer,
    }
    write_new(args["out"], dumped(described))
    print(f"plugin_index: plugin.json describes {pid} {version} ({expected[1]}), API {api}, {len(data)} bytes",
          file=sys.stderr)


def listing(path):
    """The plugin's name and summary in the list, from plugins/<id>/listing.json: those two keys alone."""
    try:
        with open(path, encoding="utf-8") as f:
            words = json.load(f)
    except (OSError, ValueError):
        raise Bad("the plugin's listing.json can't be read as JSON")
    if not isinstance(words, dict) or set(words) != {"name", "summary"}:
        raise Bad("the plugin's listing.json holds its name and summary, and nothing else")
    if not plain(words["name"], MAX_NAME, blank=False):
        raise Bad(f"the plugin's name must be 1 to {MAX_NAME} characters of plain text")
    if not plain(words["summary"], MAX_SUMMARY, blank=True):
        raise Bad(f"the plugin's summary must be at most {MAX_SUMMARY} characters of plain text")
    return words["name"], words["summary"]


# build

def gh(*args):
    done = subprocess.run(["gh", *args], capture_output=True)
    if done.returncode != 0:
        raise Bad(f"gh api {args[-1]} failed: {done.stderr.decode(errors='replace').strip()[:200]}")
    return done.stdout


def json_values(text):
    """The JSON values in text, one after another: gh api --paginate prints each page's array in turn."""
    decoder = json.JSONDecoder()
    values, at = [], 0
    while True:
        while at < len(text) and text[at].isspace():
            at += 1
        if at == len(text):
            return values
        value, at = decoder.raw_decode(text, at)
        values.append(value)


def releases(repo):
    try:
        pages = json_values(gh("api", "--paginate", f"repos/{repo}/releases?per_page=100").decode("utf-8"))
    except ValueError:
        raise Bad("the releases GitHub gave can't be read as JSON")
    found = []
    for page in pages:
        if not isinstance(page, list):
            raise Bad("the releases GitHub gave are no list")
        found += page
    return found


def checked_version(repo, release, signer):
    """What a published plugin prerelease gives the list, its plugin.json checked against its tag and its APK."""
    tag = release["tag_name"]
    match = TAG.fullmatch(tag)
    pid, version = match.group(1), match.group(2)
    assets = {asset.get("name"): asset for asset in release.get("assets") or []}
    apk, described_asset = assets.get(f"{pid}.apk"), assets.get("plugin.json")
    if apk is None or described_asset is None:
        missing = [name for name, asset in ((f"{pid}.apk", apk), ("plugin.json", described_asset)) if asset is None]
        raise Bad(f"{tag} has no {' and no '.join(missing)}")
    try:
        described = json.loads(gh("api", "-H", "Accept: application/octet-stream",
                                  f"repos/{repo}/releases/assets/{described_asset['id']}").decode("utf-8"))
    except ValueError:
        raise Bad(f"{tag}'s plugin.json can't be read as JSON")
    if not isinstance(described, dict):
        raise Bad(f"{tag}'s plugin.json is no object")

    url = f"https://github.com/{repo}/releases/download/{tag}/{pid}.apk"
    digest = apk.get("digest")
    if not isinstance(digest, str) or not re.fullmatch(r"sha256:[0-9a-f]{64}", digest):
        raise Bad(f"{pid}.apk of {tag} has no SHA-256 digest on GitHub: its bytes can't be checked against plugin.json")
    expected = {"format": FORMAT, "id": pid, "package": f"app.nzstreamer.plugin.{pid}", "versionName": version,
                "versionCode": version_code(version), "url": url}
    for field, value in expected.items():
        if described.get(field) != value:
            raise Bad(f"{tag}'s plugin.json gives {field} {described.get(field)!r}, not {value!r}")
    if apk.get("browser_download_url") != url:
        raise Bad(f"{tag}'s {pid}.apk is downloaded from another address than plugin.json gives")
    if described.get("size") != apk.get("size") or not isinstance(apk.get("size"), int) or apk["size"] < 1:
        raise Bad(f"{tag}'s plugin.json gives another size than its {pid}.apk has")
    if described.get("sha256") != digest[len("sha256:"):]:
        raise Bad(f"{tag}'s plugin.json gives another SHA-256 than GitHub's digest of its {pid}.apk")
    if described.get("signer") != signer:
        raise Bad(f"{tag}'s plugin.json names another signer than the beta key, tools/beta-cert.sha256's")
    if not isinstance(described.get("api"), str) or not API.fullmatch(described["api"]):
        raise Bad(f"{tag}'s plugin.json gives no plugin API as MAJOR.MINOR")
    libs = described.get("libs")
    if not isinstance(libs, dict) or not all(isinstance(k, str) and isinstance(v, str) for k, v in libs.items()):
        raise Bad(f"{tag}'s plugin.json gives no libraries as a map of names to versions")
    for field in ("minSdk", "targetSdk"):
        value = described.get(field)
        if not isinstance(value, int) or isinstance(value, bool) or not 1 <= value <= MIN_SDK_MAX:
            raise Bad(f"{tag}'s plugin.json gives no {field} as a number")
    if not plain(described.get("name"), MAX_NAME, blank=False) or not plain(described.get("summary"), MAX_SUMMARY, True):
        raise Bad(f"{tag}'s plugin.json gives a name of 1 to {MAX_NAME} or a summary of at most {MAX_SUMMARY} "
                  "characters of plain text, or neither")
    if not plain(version, MAX_VERSION_NAME, blank=False):
        raise Bad(f"{tag}'s version is longer than {MAX_VERSION_NAME} characters")
    published = release.get("published_at")
    if not isinstance(published, str) or not re.match(r"\d{4}-\d{2}-\d{2}T", published):
        raise Bad(f"{tag} gives no time it was published")
    return pid, described, {
        "versionCode": expected["versionCode"], "versionName": version, "api": described["api"], "libs": libs,
        "minSdk": described["minSdk"], "targetSdk": described["targetSdk"], "url": url, "size": apk["size"],
        "sha256": described["sha256"], "released": published[:10],
        "changes": f"https://github.com/{repo}/releases/tag/{tag}",
    }


def plugins_of(repo, signer):
    """The list's plugins, from every published plugin prerelease of repo."""
    by_id = {}
    for release in releases(repo):
        tag = release.get("tag_name")
        match = TAG.fullmatch(tag) if isinstance(tag, str) else None
        if match is None or plugin_id(match.group(1)) is None or int(match.group(3)) >= 2100:
            continue
        if release.get("draft"):
            continue
        if not release.get("prerelease"):
            print(f"plugin_index: warning: {tag} is published as a full release, not a prerelease: left out of the "
                  "list", file=sys.stderr)
            continue
        pid, described, version = checked_version(repo, release, signer)
        entry = by_id.setdefault(pid, {"releases": [], "withdrawn": [], "versions": []})
        entry["releases"].append((version["versionCode"], described))
        if (release.get("name") or "").startswith("Withdrawn:"):
            entry["withdrawn"].append(version["versionCode"])
        else:
            entry["versions"].append(version)

    plugins = []
    for pid, entry in sorted(by_id.items()):
        # Its words from its newest release, withdrawn or not.
        _, newest = max(entry["releases"], key=lambda item: item[0])
        # The newest of each plugin API and set of libraries: a newer build of the same API can need libraries the
        # apps out there don't have yet, and must not hide the one they load.
        newest_of = {}
        for version in entry["versions"]:
            key = (version["api"], json.dumps(version["libs"], sort_keys=True))
            kept = newest_of.get(key)
            if kept is None or version["versionCode"] > kept["versionCode"]:
                newest_of[key] = version
        versions = sorted(newest_of.values(), key=lambda version: version["versionCode"], reverse=True)
        if len(versions) > MAX_VERSIONS:
            # Failing here would stop the list being written, withdrawals with it: the oldest go instead.
            print(f"plugin_index: warning: {pid} has {len(versions)} versions to list, and the app reads "
                  f"{MAX_VERSIONS} at most: the oldest {len(versions) - MAX_VERSIONS} are left out", file=sys.stderr)
            versions = versions[:MAX_VERSIONS]
        plugins.append({
            "id": pid, "package": f"app.nzstreamer.plugin.{pid}", "name": newest["name"], "summary": newest["summary"],
            "source": f"https://github.com/{repo}/tree/main/plugins/{pid}", "withdrawn": sorted(entry["withdrawn"]),
            "versions": versions,
        })
    if len(plugins) > MAX_PLUGINS:
        raise Bad(f"the list would hold {len(plugins)} plugins: the app reads {MAX_PLUGINS} at most")
    return plugins


def current_list(path):
    """The list on the branch now, as (its plugins, its time), or None where there is none that reads."""
    if not path or not os.path.isfile(path):
        return None
    try:
        with open(path, encoding="utf-8") as f:
            current = json.load(f)
    except (OSError, ValueError):
        return None
    if not isinstance(current, dict) or current.get("format") != FORMAT or utc(current.get("generated")) is None:
        return None
    return current.get("plugins"), current["generated"]


def build(args):
    if not REPO.fullmatch(args["repo"]):
        raise Usage("--repo takes owner/name")
    try:
        with open(args["signer"], encoding="utf-8") as f:
            signer = f.read().strip()
    except OSError:
        raise Usage("--signer names no file")
    if not HASH.fullmatch(signer):
        raise Usage("--signer's file holds no SHA-256 digest (64 lower-case hex digits)")
    now = args.get("now") or datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    if utc(now) is None:
        raise Usage("--now takes a UTC time as 2026-12-01T10:00:00Z")
    if os.path.lexists(args["out"]):
        raise Usage("the output file is there already: give a new path")

    plugins = plugins_of(args["repo"], signer)
    current = current_list(args.get("current"))
    generated = now
    if current is not None:
        current_plugins, current_generated = current
        if current_plugins == plugins or utc(current_generated) > utc(now):
            generated = current_generated
    text = dumped({"format": FORMAT, "generated": generated, "plugins": plugins})
    if len(text.encode("utf-8")) > MAX_BYTES:
        raise Bad(f"the list would be {len(text.encode('utf-8'))} bytes: the app reads 256 KiB at most")
    write_new(args["out"], text)
    versions = sum(len(plugin["versions"]) for plugin in plugins)
    print(f"plugin_index: {len(plugins)} plugins, {versions} versions, generated {generated}", file=sys.stderr)


# builds

def builds(args):
    try:
        major = int(args["api-major"])
    except ValueError:
        raise Usage("--api-major takes a number")
    releases_at = args["releases"]
    if not re.fullmatch(r"https://[A-Za-z0-9.-]+/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+/releases/download/", releases_at):
        raise Usage("--releases takes https://<host>/<owner>/<name>/releases/download/")
    try:
        with open(args["list"], "rb") as f:
            raw = f.read(MAX_BYTES + 1)
        index = json.loads(raw.decode("utf-8"))
    except (OSError, ValueError):
        raise Bad("the plugin list can't be read as JSON")
    if len(raw) > MAX_BYTES:
        raise Bad("the plugin list is over 256 KiB")
    if not isinstance(index, dict) or index.get("format") != FORMAT or not isinstance(index.get("plugins"), list):
        raise Bad(f"the plugin list isn't one of format {FORMAT}")
    lines = []
    for plugin in index["plugins"]:
        pid = plugin_id(plugin.get("id")) if isinstance(plugin, dict) and isinstance(plugin.get("id"), str) else None
        if pid is None:
            raise Bad("the plugin list gives a plugin whose id breaks the app's rule")
        for version in plugin.get("versions") or []:
            if not isinstance(version, dict):
                raise Bad(f"the plugin list gives {pid} a version that is no object")
            api = API.fullmatch(version["api"]) if isinstance(version.get("api"), str) else None
            code, size, sha, url = (version.get(field) for field in ("versionCode", "size", "sha256", "url"))
            if api is None or not all(isinstance(n, int) and not isinstance(n, bool) and n >= 1 for n in (code, size)) \
                    or not isinstance(sha, str) or not HASH.fullmatch(sha) or not isinstance(url, str):
                raise Bad(f"the plugin list gives {pid} a version the app would drop: its API, versionCode, size or "
                          "SHA-256")
            rule = re.escape(releases_at) + rf"plugin-{pid}-v{VERSION}/{pid}\.apk"
            if not re.fullmatch(rule, url):
                raise Bad(f"the plugin list gives {pid} {code} an address outside the app's rule")
            if int(api.group(1)) == major:
                lines.append(f"{pid}\t{code}\t{url}\t{size}\t{sha}")
    sys.stdout.write("".join(line + "\n" for line in lines))


COMMANDS = {
    "describe": (describe, {"id", "tag", "repo", "apk", "listing", "out"}, set()),
    "build": (build, {"repo", "signer", "out"}, {"current", "now"}),
    "builds": (builds, {"list", "releases", "api-major"}, set()),
}
USAGE = ("usage: plugin_index.py describe --id <id> --tag <tag> --repo <owner/name> --apk <file> --listing <file> --out "
         "<file> | build --repo <owner/name> --signer <file> --out <file> [--current <file>] [--now <time>] | builds "
         "--list <file> --releases <address> --api-major <n>")


def main(argv):
    try:
        if not argv or argv[0] not in COMMANDS:
            raise Usage("no such command")
        command, needed, optional = COMMANDS[argv[0]]
        options = argv[1:]
        if len(options) % 2:
            raise Usage("each option takes a value")
        args = {}
        for option, value in zip(options[::2], options[1::2]):
            name = option[2:] if option.startswith("--") else None
            if name not in needed | optional or name in args:
                raise Usage(f"an unknown or repeated option: {option}")
            args[name] = value
        if not needed <= set(args):
            raise Usage(f"{argv[0]} needs --{', --'.join(sorted(needed - set(args)))}")
        command(args)
    except Usage as error:
        print(f"plugin_index: {error}\n{USAGE}", file=sys.stderr)
        return 2
    except (Bad, OSError, UnicodeDecodeError) as error:
        print(f"plugin_index: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
