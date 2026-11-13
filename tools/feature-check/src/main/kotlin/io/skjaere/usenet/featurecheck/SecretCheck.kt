package io.skjaere.usenet.featurecheck

import java.io.File
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.system.exitProcess

/**
 * Usage: --apk <.apk> --settings <file> --control <text> [--report <file>] [--check-name <name>] [--advice <text>]
 *        [--settings-if-not-carried <file> --carried <file>] [--extra <file>]
 *
 * Fails (1) when the APK carries any of the dev settings in [--settings] (lines of "name<TAB>value", local.properties'
 * provider, indexers and TMDB key, or the debrid keys, written by the build for this check only), or when no dex in it
 * holds [--control] (the applicationId, which BuildConfig carries), so a scan that can't read the dex can't pass blind.
 * Looks in every entry, inflated, and in the file's own bytes (stored entries, the signing block), each value as UTF-8 and
 * as UTF-16. Names the settings and entries, never a value: the report goes to the build's output. The report ends with
 * the SHA-256 of the bytes scanned (read before and after the scan, which fails if they differ), so the site's
 * publish script publishes only them.
 *
 * [--check-name]: the task the report is for, its every line's name (checkBetaHasNoDevSettings without it, whose report
 * the publish script reads); [--advice]: what may carry them, said under a failure. [--settings-if-not-carried]: settings
 * found in the APK that pass when their value is one of [--carried]'s, the dev settings that build carries on purpose
 * (L182: a private build's devIndexer, as one Prowlarr or Jackett has one host and key for all its indexers), each
 * reported as such. [--extra]: more strings it must never carry (L6), lines of "label<TAB>value" kept apart from the
 * build (a canary list, or the release workflow's secret): the report counts them and gives the line of each one found,
 * never naming one, as a label can say what it guards. They follow the canary file's rules for a binary, as the tree
 * scans read one: in any case (ASCII and Latin-1 letters); a value under a w: or w2: label only as a whole word, with
 * no letter, digit or underscore beside it; a w2: value under 5 characters never, as two letters turn up by chance; a
 * value under a wt: label never, as it is a common word that library data holds too (a word of a title, a city): the
 * report counts those as not checked in binaries. The dev settings are looked for exactly as they are. In every file
 * blank lines and lines starting with # are skipped, as the canary file's comments are, and any other line with no TAB
 * fails the check, naming its number alone.
 */
fun main(args: Array<String>) {
    exitProcess(runSecretCheck(args))
}

/** Values shorter than this are left out (and named): any APK holds a short string somewhere. */
internal const val SHORTEST_CHECKED = 5

fun runSecretCheck(args: Array<String>): Int {
    val options = args.toList().chunked(2).groupBy({ it.first().removePrefix("--") }, { it.getOrNull(1) })
    if (options.values.any { null in it }) return usage()
    val apk = options["apk"]?.singleOrNull()?.let(::File) ?: return usage()
    val settingsFile = options["settings"]?.singleOrNull()?.let(::File) ?: return usage()
    val control = options["control"]?.singleOrNull()?.takeIf { it!!.isNotBlank() } ?: return usage()
    val name = options["check-name"]?.singleOrNull() ?: DEFAULT_CHECK
    val advice = options["advice"]?.singleOrNull() ?: DEFAULT_ADVICE
    val carriableFile = options["settings-if-not-carried"]?.singleOrNull()?.let(::File)
    val carriedFile = options["carried"]?.singleOrNull()?.let(::File)
    val extraFile = options["extra"]?.singleOrNull()?.let(::File)
    if ((carriableFile == null) != (carriedFile == null)) return usage()
    if (!apk.isFile) return fail("no such APK: $apk")
    if (!settingsFile.isFile) return fail("no such settings file: $settingsFile")
    listOfNotNull(carriableFile, carriedFile).firstOrNull { !it.isFile }?.let { return fail("no such settings file: $it") }
    // The extra file's path is not shown: it may say where its owner keeps it.
    if (extraFile != null && !extraFile.isFile) return fail("no such --extra file")

    val strict: List<Pair<String, String>>
    val carriable: List<Pair<String, String>>
    val carried: List<Pair<String, String>>
    val extra: List<Entry>
    try {
        strict = readEntries(settingsFile, "--settings").pairs().withValues()
        carriable = carriableFile?.let { readEntries(it, "--settings-if-not-carried").pairs().withValues() }.orEmpty()
        carried = carriedFile?.let { readEntries(it, "--carried").pairs().withValues() }.orEmpty()
        extra = extraFile?.let { readEntries(it, "--extra") }.orEmpty()
    } catch (e: NoTab) {
        val split = e.after?.let {
            // The build writes the settings files: such a line is the rest of a value with a line break in it.
            " It follows $it's line: a value with a line break in it (local.properties can hold one as \\n) splits its line. " +
                "Take the line break out of $it."
        }.orEmpty()
        return fail("${e.option}: line ${e.line} has no TAB, so it can't be read (each line is a name, a TAB and a value).$split")
    }
    val settings = strict + carriable
    val (checked, tooShort) = settings.partition { it.second.length >= SHORTEST_CHECKED }
    val (longExtra, shortExtra) = extra.partition { it.value.length >= SHORTEST_CHECKED }
    // A wt: entry is for the text scans alone: never looked for here, only counted.
    val (textOnly, checkedExtra) = longExtra.partition { it.label.startsWith(TEXT_ONLY_LABEL) }

    val before = sha256(apk)
    // One pass for both: an extra entry goes in under a name no setting can have, and comes out as a count.
    val needles = checked.map { (name, value) -> Needle(name, value) } + checkedExtra.mapIndexed { index, entry ->
        Needle("$EXTRA$index", entry.value, anyCase = true, word = WORD_LABELS.any { entry.label.startsWith(it) })
    }
    val scanned = SecretScan.apk(apk, needles)
    val (extraHits, found) = scanned.partition { it.what.startsWith(EXTRA) }
    val controlSeen = SecretScan.dexHolds(apk, control!!)
    val after = sha256(apk)
    // A setting that may be carried, found with the value of one this build carries on purpose: said, and passed.
    val carriableNames = carriable.map { it.first }.toSet()
    val excused = found.map { it.what }.distinct().filter { it in carriableNames }.mapNotNull { what ->
        val value = carriable.first { it.first == what }.second
        carried.firstOrNull { it.second == value }?.let { what to it.first }
    }.toMap()
    val hits = found.filter { it.what !in excused }

    val report = buildString {
        if (hits.isEmpty() && extraHits.isEmpty()) {
            appendLine("$name: ${apk.name} carries none of local.properties' dev settings.")
            val lookedFor = checked.map { it.first } + listOfNotNull(extraCount(checkedExtra.size))
            when {
                settings.isEmpty() && extra.isEmpty() -> appendLine("  local.properties sets none of them: nothing to look for.")
                lookedFor.isEmpty() && textOnly.isEmpty() ->
                    appendLine("  None of the dev settings or extra entries is long enough to look for.")
                lookedFor.isEmpty() ->
                    appendLine("  None of the dev settings or extra entries is looked for in an APK: each is too short or text only.")
                else -> appendLine("  Looked in every entry and the file itself, as UTF-8 and UTF-16, for: ${lookedFor.joinToString()}.")
            }
        }
        if (hits.isNotEmpty()) {
            appendLine("$name: ${apk.name} carries dev settings from local.properties. Don't publish it:")
            hits.forEach { appendLine("  ${it.where}: ${it.what}") }
            appendLine(advice)
        }
        if (extraHits.isNotEmpty()) {
            // The lines of the file it was given, numbers only: whoever keeps the file can look there.
            val lines = extraHits.map { checkedExtra[it.what.removePrefix(EXTRA).toInt()].line }.distinct().sorted()
            val on = (if (lines.size == 1) "line " else "lines ") +
                (if (lines.size == 1) "${lines[0]}" else lines.dropLast(1).joinToString() + " and " + lines.last())
            appendLine("$name: ${apk.name} carries ${extraCount(lines.size)} of the ${checkedExtra.size} looked for, on $on of the --extra file. Don't publish it:")
            extraHits.groupBy { it.where }.forEach { (where, inIt) -> appendLine("  $where: ${extraCount(inIt.map { it.what }.distinct().size)}") }
        }
        excused.forEach { (what, carriedAs) -> appendLine("  $what: the same value as $carriedAs, which this build carries on purpose.") }
        val notChecked = tooShort.map { it.first } + listOfNotNull(extraCount(shortExtra.size))
        if (notChecked.isNotEmpty()) {
            appendLine("  Not checked (shorter than $SHORTEST_CHECKED characters): ${notChecked.joinToString()}.")
        }
        extraCount(textOnly.size)?.let { appendLine("  Not checked in binaries (text only): $it.") }
        if (controlSeen) {
            appendLine("  Its dex holds the applicationId (the check's control): the scan reads the dex.")
        } else {
            appendLine("$name: no dex in ${apk.name} holds the applicationId (the check's control), so this scan can't be trusted.")
        }
        if (before == after) {
            appendLine("  SHA-256: $after")
        } else {
            appendLine("$name: ${apk.name} changed while it was scanned: run it again.")
        }
    }
    options["report"]?.singleOrNull()?.let { File(it!!).apply { parentFile?.mkdirs() }.writeText(report) }
    print(report)
    return if (hits.isEmpty() && extraHits.isEmpty() && controlSeen && before == after) 0 else 1
}

/** An extra entry's name in the scan: a NUL first, which no setting's name has. Never shown. */
private const val EXTRA = "\u0000extra "

/** The canary file's whole-word labels: w: and w2:, a short word's. */
private val WORD_LABELS = listOf("w:", "w2:")

/** The canary file's text-only label: a whole word the text scans look for, and a binary's bytes never. */
private const val TEXT_ONLY_LABEL = "wt:"

/** "1 extra entry", "2 extra entries"; null for none. */
private fun extraCount(count: Int): String? = when (count) {
    0 -> null
    1 -> "1 extra entry"
    else -> "$count extra entries"
}

/** The published beta's check, by its name and advice: its publish script reads the report as these words write it. */
private const val DEFAULT_CHECK = "checkBetaHasNoDevSettings"
private const val DEFAULT_ADVICE = "Only a beta built with -PbetaDevSettings=true may carry them (a private build with preset settings, never published)."

/**
 * A line of [option]'s file, by its number, that has no TAB; [after] is the name on the entry line above it, given for a
 * settings file alone, as an --extra file's label may say what it guards.
 */
private class NoTab(val option: String, val line: Int, val after: String?) : Exception()

/** A "label<TAB>value" line of a file, by its number there. */
private class Entry(val line: Int, val label: String, val value: String)

/**
 * A file's "name<TAB>value" lines, blank ones and comments (starting with #) skipped. Any other line with no TAB throws
 * [NoTab]: dropping it would leave its value unchecked without a word.
 */
private fun readEntries(file: File, option: String): List<Entry> {
    val entries = mutableListOf<Entry>()
    file.readLines().forEachIndexed { index, raw ->
        val line = raw.removeSuffix("\r")
        when {
            line.isBlank() || line.startsWith("#") -> Unit
            '\t' !in line -> throw NoTab(option, index + 1, entries.lastOrNull()?.label?.takeIf { option != "--extra" })
            else -> entries += Entry(index + 1, line.substringBefore('\t'), line.substringAfter('\t'))
        }
    }
    return entries
}

private fun List<Entry>.pairs() = map { it.label to it.value }

/** The settings with a value: the build writes none without one. */
private fun List<Pair<String, String>>.withValues() = filter { it.second.isNotEmpty() }

private fun sha256(file: File): String = file.inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(1 shl 16)
    generateSequence { input.read(buffer).takeIf { it >= 0 } }.forEach { digest.update(buffer, 0, it) }
    digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * What a scan looks for, by [name]: [value] as UTF-8 and UTF-16, exactly as it is; with [anyCase], in any case (ASCII and
 * Latin-1 letters); with [word], only where no letter, digit or underscore is beside it.
 */
class Needle(val name: String, value: String, private val anyCase: Boolean = false, private val word: Boolean = false) {
    private val forms: List<Pair<Charset, ByteArray>> = listOf(Charsets.UTF_8, Charsets.UTF_16LE, Charsets.UTF_16BE)
        .map { charset -> charset to value.toByteArray(charset).let { if (anyCase) folded(it) else it } }
        .distinctBy { it.second.toList() }

    /** Whether [bytes] hold it; [folded] is [bytes] through [folded], made once for every needle in any case. */
    fun foundIn(bytes: ByteArray, folded: () -> ByteArray): Boolean {
        val haystack = if (anyCase) folded() else bytes
        return forms.any { (charset, form) -> haystack.anyStart(form) { !word || alone(haystack, it, form.size, charset) } }
    }

    /** Whether nothing that belongs to a word sits right before [start] or right after the [length] bytes from it. */
    private fun alone(bytes: ByteArray, start: Int, length: Int, charset: Charset): Boolean {
        val end = start + length
        return when (charset) {
            // A byte past ASCII is no word's here, as the tree scans read a binary.
            Charsets.UTF_8 -> (start == 0 || !wordByte(bytes[start - 1])) && (end == bytes.size || !wordByte(bytes[end]))
            Charsets.UTF_16LE -> (start < 2 || !wordChar(bytes[start - 1], bytes[start - 2])) &&
                (end + 1 >= bytes.size || !wordChar(bytes[end + 1], bytes[end]))
            else -> (start < 2 || !wordChar(bytes[start - 2], bytes[start - 1])) &&
                (end + 1 >= bytes.size || !wordChar(bytes[end], bytes[end + 1]))
        }
    }

    private fun wordByte(b: Byte): Boolean = b.toInt().toChar().let { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' }

    private fun wordChar(high: Byte, low: Byte): Boolean = ((high.toInt() and 0xFF shl 8) or (low.toInt() and 0xFF)).toChar()
        .let { it.isLetterOrDigit() || it == '_' }

    companion object {
        /** Each byte with ASCII and Latin-1 capitals lowered, as the tree scans lower a binary read as Latin-1. */
        private val LOWER = ByteArray(256) { b ->
            (if (b in 0x41..0x5A || b in 0xC0..0xDE && b != 0xD7) b + 0x20 else b).toByte()
        }

        fun folded(bytes: ByteArray): ByteArray = ByteArray(bytes.size) { LOWER[bytes[it].toInt() and 0xFF] }

        /** Whether [needle] starts somewhere in this where [accept] takes the place. */
        private inline fun ByteArray.anyStart(needle: ByteArray, accept: (Int) -> Boolean): Boolean {
            if (needle.isEmpty() || needle.size > size) return false
            val first = needle[0]
            for (start in 0..size - needle.size) {
                if (this[start] == first && needle.indices.all { this[start + it] == needle[it] } && accept(start)) return true
            }
            return false
        }
    }
}

/** Where dev settings are in an APK. */
object SecretScan {
    /** Each needle found in [apk]: the entries holding it (or the file itself), by its name, never its value. */
    fun apk(apk: File, needles: List<Needle>): List<Hit> {
        fun scan(where: String, bytes: ByteArray): List<Hit> {
            val folded by lazy { Needle.folded(bytes) }
            return needles.filter { it.foundIn(bytes) { folded } }.map { Hit(where, it.name) }
        }
        val entries = ZipFile(apk).use { zip ->
            zip.entries().asSequence().filterNot { it.isDirectory }.flatMap { entry ->
                scan("${apk.name}!${entry.name}", zip.getInputStream(entry).use { it.readBytes() })
            }.toList()
        }
        val named = entries.map { it.what }.toSet()
        // The file's own bytes: what isn't in an entry (the signing block, the zip's comment) and stored entries again.
        return entries + scan(apk.name, apk.readBytes()).filterNot { it.what in named }
    }

    /** Whether a dex entry of [apk] holds [text] (UTF-8, as dex strings are for ASCII). */
    fun dexHolds(apk: File, text: String): Boolean = ZipFile(apk).use { zip ->
        val needle = text.toByteArray(Charsets.UTF_8)
        zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.matches(Regex("""classes\d*\.dex""")) }
            .any { entry -> zip.getInputStream(entry).use { it.readBytes() }.holds(needle) }
    }

    private fun ByteArray.holds(needle: ByteArray): Boolean {
        if (needle.isEmpty() || needle.size > size) return false
        val first = needle[0]
        return (0..size - needle.size).any { start ->
            this[start] == first && needle.indices.all { this[start + it] == needle[it] }
        }
    }
}

private fun fail(message: String): Int {
    System.err.println("secret check: $message")
    return 2
}

private fun usage(): Int = fail(
    "usage: --apk <file> --settings <file> --control <text> [--report <file>] [--check-name <name>] [--advice <text>] " +
        "[--settings-if-not-carried <file> --carried <file>] [--extra <file>]",
)
