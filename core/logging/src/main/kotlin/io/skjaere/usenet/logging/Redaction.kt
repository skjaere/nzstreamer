package io.skjaere.usenet.logging

private const val HIDDEN = "<redacted>"

/**
 * Secrets by name: in `name=value` (a query, a toString) and in JSON. A tracker's passkey and authkey ride in its links
 * (torrent_pass is caught by pass), and a sign-in's device code is as good as a key while it lasts (L186).
 */
private const val NAMES =
    "api[_-]?key|api[_-]?token|access[_-]?token|refresh[_-]?token|auth[_-]?token|token|" +
        "password|passwd|pwd|pass|passkey|authkey|device[_-]?code|secret|client[_-]?secret|username|user|login|auth"

/** Names that are secrets only in a query: an indexer's download links carry the account's id (`i`) and RSS token (`r`). */
private const val QUERY_NAMES = "key|r|i|uid|sig|signature"

/** A value: to the next separator, or a `{placeholder}` whole. */
private const val VALUE = """\{[^}\s]*\}|[^\s&,;)\]}<>"'#]+"""

/** A value in a link nested, percent-encoded, in another's query: to the next encoded `&` or `#`, or a separator. */
private const val ENCODED_VALUE = """(?:(?!%26|%23)[^\s&,;)\]}<>"'#])+"""

/** Where a toString's field ends: at the next field, or at the closing brackets that only text without one follows on the line. */
private const val FIELD_END = """[)}\]]*, [A-Za-z_][A-Za-z0-9_]*=|[)}\]]+[^)}\]\r\n]*$"""

private val rules: List<Pair<Regex, String>> = listOf(
    // A magnet, whole: its hash, the release's name (dn=) and its trackers (tr=, which can hold a passkey) go together.
    Regex("""(?i)\bmagnet:\?[^\s"'<>]+""") to HIDDEN,
    // NNTP logins: what follows AUTHINFO USER, PASS, SASL or GENERIC on the line, but not a refusal's "failed: 481 …".
    Regex("""(?i)\b(AUTHINFO[ \t]+(?:USER|PASS|SASL|GENERIC))[ \t]+(?!failed\b)[^\r\n]+""") to "$1 $HIDDEN",
    // Authorization and Proxy-Authorization values, to the end of the line or a quote, comma or semicolon.
    Regex("""(?i)\b((?:proxy-)?authorization)(["']?[ \t]*[:=][ \t]*["']?)[^\r\n"',;]+""") to "$1$2$HIDDEN",
    // A bearer token without its header.
    Regex("""(?i)\b(bearer)[ \t]+[A-Za-z0-9._~+/-]{8,}=*""") to "$1 $HIDDEN",
    // JWTs (TMDB's read access tokens) wherever they are.
    Regex("""\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*""") to HIDDEN,
    // The login part of an address, to its last @: nntps://user:p@ssword@host.
    Regex("""(?i)\b([a-z][a-z0-9+.-]*://)[^\s/?#<>"']+@""") to "$1$HIDDEN@",
    // And of a host without one (user:password@news.example.com, as a Host field may be given), where
    // a word starts: not a message id's <part1:x@host>. Bounded, so a long line of words with colons
    // in them doesn't take each one to its end.
    Regex("""(?m)(?:^|(?<=[\s(\[="',]))[^\s/:@<>"'(\[=,]{1,128}:[^\s/<>"']{0,256}@(?=[A-Za-z0-9])""") to "$HIDDEN@",
    Regex("""(?i)"($NAMES)"([ \t]*:[ \t]*)"(?:[^"\\]|\\.)*"""") to "\"$1\"$2\"$HIDDEN\"",
    // A toString's field (after "(", "{" or ", "), to the next field or the closing bracket: spaces,
    // commas and brackets in it are its own. Bounded like the host's login.
    Regex("""(?im)(?<=[({]|, )($NAMES)=(?!["'])[^\r\n]{0,512}?(?=$FIELD_END)""") to "$1=$HIDDEN",
    // A quoted value, whole (to the end of its line if never closed): password="correct horse".
    Regex("""(?im)(?<![A-Za-z0-9])($NAMES)=(["'])(?:\\.|[^\\\r\n]|\\)*?(?:\2|$)""") to "$1=$2$HIDDEN$2",
    // In a link nested in another's query, percent-encoded: …%3Fapikey%3D…, …%26r%3D….
    Regex("""(?i)((?:%3F|%26)(?:$NAMES|$QUERY_NAMES)|(?<![A-Za-z0-9])(?:$NAMES))(%3D)$ENCODED_VALUE""") to "$1$2$HIDDEN",
    // Elsewhere to the next space or separator. A name may follow a prefix (jackett_apikey), not a
    // letter or a digit (pwcheck, bypass).
    Regex("""(?i)(?<![A-Za-z0-9])($NAMES)=(?:$VALUE)""") to "$1=$HIDDEN",
    // In a query, or a link written into XML or HTML (&amp;).
    Regex("""(?i)([?&]|&amp;)($QUERY_NAMES)=(?:$VALUE)""") to "$1$2=$HIDDEN",
)

/**
 * [text] with what must never reach a log written over: NNTP login arguments, API keys, tokens,
 * passwords and usernames, trackers' passkeys and device codes (in a query, a link nested in one, a
 * toString's `name=value`, quoted or not, or JSON), Authorization values, bearer tokens and JWTs, the
 * login part of an address (`user:password@host`, with a scheme or without), and magnets whole.
 * A value in other text, without quotes, runs to the next space or separator. The logger runs every
 * line and stack trace through it before logcat or [LogBuffer] sees them; [CrashLog] runs a crash's.
 */
fun redactSecrets(text: String): String = rules.fold(text) { acc, (regex, replacement) -> regex.replace(acc, replacement) }
