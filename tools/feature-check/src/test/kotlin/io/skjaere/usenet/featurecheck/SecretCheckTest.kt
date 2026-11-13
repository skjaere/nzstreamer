package io.skjaere.usenet.featurecheck

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * checkBetaHasNoDevSettings over synthetic APKs: the beta published for anyone must carry none of local.properties' dev
 * settings, in any entry and any encoding the build writes strings in. The report names the settings and the entries,
 * never a value.
 */
class SecretCheckTest {
    @TempDir
    lateinit var dir: File

    private val control = "app.nzstreamer.beta"
    private val settings = mapOf(
        "devProvider.password" to "hunter2-SECRET",
        "devTmdbKey" to "0123456789abcdef0123456789abcdef",
        "devIndexer.1.apikey" to "idx-key-SECRET-42",
    )

    private fun bytes(vararg strings: String) = strings.joinToString("\u0000").toByteArray(Charsets.UTF_8)

    /** An APK as the check reads it: a zip, its entries deflated unless named in [stored]. */
    private fun apk(entries: Map<String, ByteArray>, stored: Set<String> = emptySet()): File = File(dir, "app-beta.apk").also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (name, content) ->
                val entry = ZipEntry(name)
                if (name in stored) {
                    entry.method = ZipEntry.STORED
                    entry.size = content.size.toLong()
                    entry.crc = CRC32().apply { update(content) }.value
                }
                zip.putNextEntry(entry)
                zip.write(content)
                zip.closeEntry()
            }
        }
    }

    private val cleanApk = mapOf(
        "AndroidManifest.xml" to bytes("manifest"),
        "classes.dex" to bytes("dex\n035", control, "Lio/skjaere/usenet/app/BuildConfig;", "SEED_DEV_SETTINGS"),
        "resources.arsc" to "NZStreamer Beta".toByteArray(Charsets.UTF_16LE),
        "lib/arm64-v8a/librapidyenc.so" to bytes("ELF"),
    )

    private fun check(apk: File, settings: Map<String, String> = this.settings, control: String = this.control): Pair<Int, String> {
        val needles = File(dir, "needles.tsv").apply { writeText(settings.entries.joinToString("\n") { "${it.key}\t${it.value}" }) }
        val report = File(dir, "report.txt").apply { delete() }
        val code = runSecretCheck(arrayOf("--apk", apk.path, "--settings", needles.path, "--control", control, "--report", report.path))
        return code to report.readText()
    }

    private fun assertNoValues(report: String, settings: Map<String, String> = this.settings) =
        settings.values.forEach { assertFalse(it in report, "the report shows a value") }

    @Test
    fun `a beta without the dev settings passes and says which settings it looked for`() {
        val (code, report) = check(apk(cleanApk))
        assertEquals(0, code, report)
        settings.keys.forEach { assertTrue(it in report, "$it named") }
        assertNoValues(report)
    }

    @Test
    fun `a dev setting in a compressed dex fails and is named with its entry`() {
        val (code, report) = check(apk(cleanApk + ("classes2.dex" to bytes("dex\n035", "x", "hunter2-SECRET", "y"))))
        assertEquals(1, code, report)
        assertTrue("devProvider.password" in report && "classes2.dex" in report, report)
        assertFalse("devTmdbKey: " in report, report)
        assertTrue("Only a beta built with -PbetaDevSettings=true may carry them (a private build with preset settings, never published)." in report, report)
        assertNoValues(report)
    }

    @Test
    fun `a dev setting written as UTF-16 in the resources fails`() {
        val (code, report) = check(apk(cleanApk + ("resources.arsc" to "idx-key-SECRET-42".toByteArray(Charsets.UTF_16LE))))
        assertEquals(1, code, report)
        assertTrue("devIndexer.1.apikey" in report && "resources.arsc" in report, report)
        assertNoValues(report)
    }

    @Test
    fun `a dev setting in a stored entry fails`() {
        val (code, report) = check(apk(cleanApk + ("assets/seed.txt" to bytes("0123456789abcdef0123456789abcdef")), stored = setOf("assets/seed.txt")))
        assertEquals(1, code, report)
        assertTrue("devTmdbKey" in report && "assets/seed.txt" in report, report)
        assertNoValues(report)
    }

    @Test
    fun `an APK whose dex lacks the control can't be trusted and fails`() {
        val (code, report) = check(apk(cleanApk), control = "app.nzstreamer.elsewhere")
        assertEquals(1, code, report)
        assertTrue("control" in report, report)
    }

    @Test
    fun `values too short to look for are named as not checked, and don't fail the check`() {
        // Not hex: the report prints the APK's SHA-256, which a hex value like "abc" turns up in now and then.
        val short = settings + ("devProvider.username" to "xyz")
        val (code, report) = check(apk(cleanApk + ("classes2.dex" to bytes("xyz"))), short)
        assertEquals(0, code, report)
        assertTrue("devProvider.username" in report && "Not checked" in report, report)
        assertNoValues(report, short)
    }

    @Test
    fun `no dev settings at all passes`() {
        val (code, report) = check(apk(cleanApk), emptyMap())
        assertEquals(0, code, report)
    }

    @Test
    fun `the report gives the SHA-256 of the APK it scanned, so what is published can be matched to it`() {
        val file = apk(cleanApk)
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
        val (code, report) = check(file)
        assertEquals(0, code, report)
        assertTrue(report.lines().contains("  SHA-256: $sha"), report)
    }

    /** checkNoDebridDevKeys (L182): the same scan, under its own name, for the debrid dev keys, release, bench and both betas. */
    private val debridKeys = mapOf(
        "devTorBoxKey" to "tb-dev-key-SECRET-7f31",
        "devPremiumizeKey" to "pm-dev-key-SECRET-k7rq",
        "devTorrentIndexer.1.url" to "http://192.0.2.44:9696/3",
        "devTorrentIndexer.1.apikey" to "torznab-SECRET-b2e9",
    )

    private fun file(name: String, settings: Map<String, String>) = File(dir, name).apply { writeText(settings.entries.joinToString("\n") { "${it.key}\t${it.value}" }) }

    /** The check as the build runs it for the debrid keys: [strict] fail wherever found, [carriable] unless the build carries the value on purpose. */
    private fun debridCheck(apk: File, strict: Map<String, String>, carriable: Map<String, String> = emptyMap(), carried: Map<String, String> = emptyMap()): Pair<Int, String> {
        val report = File(dir, "debrid-report.txt").apply { delete() }
        val args = mutableListOf(
            "--check-name", "checkNoDebridDevKeys", "--apk", apk.path, "--settings", file("strict.tsv", strict).path, "--control", control,
            "--advice", "Only debug builds may carry them.", "--report", report.path,
        )
        if (carriable.isNotEmpty() || carried.isNotEmpty()) {
            args += listOf("--settings-if-not-carried", file("carriable.tsv", carriable).path, "--carried", file("carried.tsv", carried).path)
        }
        val code = runSecretCheck(args.toTypedArray())
        return code to report.readText()
    }

    @Test
    fun `the check's name is its report's own`() {
        val (code, report) = debridCheck(apk(cleanApk), debridKeys)
        assertEquals(0, code, report)
        assertTrue(report.startsWith("checkNoDebridDevKeys: app-beta.apk carries none of local.properties' dev settings."), report)
        assertFalse("checkBetaHasNoDevSettings" in report, report)
        debridKeys.keys.forEach { assertTrue(it in report, "$it named") }
        assertNoValues(report, debridKeys)
        // Without a name it is the published beta's check, as its publish script reads it.
        assertTrue(check(apk(cleanApk)).second.startsWith("checkBetaHasNoDevSettings: app-beta.apk carries none of local.properties' dev settings."))
    }

    @Test
    fun `a debrid dev key in an APK fails, naming the setting and the check, never the value`() {
        val (code, report) = debridCheck(apk(cleanApk + ("classes2.dex" to bytes("dex\n035", "tb-dev-key-SECRET-7f31"))), debridKeys)
        assertEquals(1, code, report)
        assertTrue("checkNoDebridDevKeys: app-beta.apk carries dev settings from local.properties." in report, report)
        assertTrue("classes2.dex: devTorBoxKey" in report, report)
        assertTrue("Only debug builds may carry them." in report, report)
        assertFalse("preset settings" in report, "another check's advice: $report")
        assertNoValues(report, debridKeys)
    }

    /**
     * A private build with preset settings carries its devIndexer settings on purpose: one Prowlarr has one host and one key
     * for all its indexers.
     */
    @Test
    fun `a torrent indexer's value the build carries on purpose is reported as such and passes`() {
        val carried = mapOf("devIndexer.1.apikey" to "torznab-SECRET-b2e9", "devProvider.host" to "news.example.net")
        val apk = apk(cleanApk + ("classes2.dex" to bytes("dex\n035", "torznab-SECRET-b2e9")))
        val strict = debridKeys.filterKeys { !it.startsWith("devTorrentIndexer") }
        val carriable = debridKeys.filterKeys { it.startsWith("devTorrentIndexer") }
        val (code, report) = debridCheck(apk, strict, carriable, carried)
        assertEquals(0, code, report)
        assertTrue("devTorrentIndexer.1.apikey: the same value as devIndexer.1.apikey, which this build carries on purpose" in report, report)
        assertNoValues(report, debridKeys + carried)
        // One it doesn't carry fails all the same.
        val url = apk(cleanApk + ("classes2.dex" to bytes("dex\n035", "torznab-SECRET-b2e9", "http://192.0.2.44:9696/3")))
        val (urlCode, urlReport) = debridCheck(url, strict, carriable, carried)
        assertEquals(1, urlCode, urlReport)
        assertTrue("classes2.dex: devTorrentIndexer.1.url" in urlReport, urlReport)
    }

    @Test
    fun `a service key fails even when the build carries the same value on purpose`() {
        val carried = mapOf("devIndexer.1.apikey" to "tb-dev-key-SECRET-7f31")
        val apk = apk(cleanApk + ("classes2.dex" to bytes("dex\n035", "tb-dev-key-SECRET-7f31")))
        val (code, report) = debridCheck(apk, debridKeys.filterKeys { !it.startsWith("devTorrentIndexer") }, debridKeys.filterKeys { it.startsWith("devTorrentIndexer") }, carried)
        assertEquals(1, code, report)
        assertTrue("classes2.dex: devTorBoxKey" in report, report)
        assertNoValues(report, debridKeys + carried)
    }

    @Test
    fun `with none of the settings set there is nothing to look for, and it passes`() {
        val (code, report) = debridCheck(apk(cleanApk), emptyMap())
        assertEquals(0, code, report)
        assertTrue("local.properties sets none of them: nothing to look for." in report, report)
        assertTrue("the scan reads the dex" in report, report)
    }

    @Test
    fun `a missing APK or settings file is a usage error`() {
        assertEquals(2, runSecretCheck(arrayOf("--apk", File(dir, "none.apk").path, "--settings", File(dir, "none.tsv").path, "--control", control)))
        assertEquals(2, runSecretCheck(arrayOf("--apk")))
    }

    /**
     * --extra (L6, L25): more strings the published beta must never carry, label<TAB>value lines kept apart from the build
     * (a canary list, or the release workflow's secret). The report counts them and never shows a label or a value, as a
     * label can say what it guards. The samples are invented and built from pieces at run time.
     */
    private fun joined(vararg parts: String) = parts.joinToString("")
    private val extraFound = joined("guard", "-a") to joined("cnry", "-", "k4p", "-fennel")
    private val extraAbsent = joined("guard", "-b") to joined("cnry", "-", "r8m", "-sorrel")
    private val extraShort = joined("guard", "-c") to joined("q", "7z")

    private fun line(entry: Pair<String, String>) = "${entry.first}\t${entry.second}"

    /** What [block] returns, and what it printed to stderr meanwhile. */
    private fun stderrOf(block: () -> Int): Pair<Int, String> {
        val before = System.err
        val buffer = ByteArrayOutputStream()
        System.setErr(PrintStream(buffer, true, Charsets.UTF_8))
        return try {
            block() to buffer.toString(Charsets.UTF_8)
        } finally {
            System.setErr(before)
        }
    }

    /** The check with an --extra file of [lines]: its exit code, its report (empty without one) and its stderr. */
    private fun extraCheck(apk: File, lines: List<String>, settings: Map<String, String> = this.settings): Triple<Int, String, String> {
        val needles = File(dir, "needles.tsv").apply { writeText(settings.entries.joinToString("\n") { "${it.key}\t${it.value}" }) }
        val extra = File(dir, "extra.tsv").apply { writeText(lines.joinToString("\n", postfix = "\n")) }
        val report = File(dir, "report.txt").apply { delete() }
        val (code, err) = stderrOf {
            runSecretCheck(arrayOf("--apk", apk.path, "--settings", needles.path, "--control", control, "--extra", extra.path, "--report", report.path))
        }
        return Triple(code, if (report.isFile) report.readText() else "", err)
    }

    private fun assertNoExtras(text: String, vararg entries: Pair<String, String>) = entries.forEach { (label, value) ->
        assertFalse(label in text, "an extra entry's label shows: $text")
        assertFalse(value in text, "an extra entry's value shows: $text")
    }

    @Test
    fun `an extra value inside an entry fails, reported as a count with no label`() {
        val apk = apk(cleanApk + ("classes2.dex" to bytes("dex\n035", "x", extraFound.second, "y")))
        val (code, report, err) = extraCheck(apk, listOf(line(extraFound), line(extraAbsent)))
        assertEquals(1, code, report + err)
        assertFalse("carries none" in report, report)
        assertTrue("app-beta.apk!classes2.dex: 1 extra entry" in report, report)
        assertNoValues(report)
        assertNoExtras(report, extraFound, extraAbsent)
    }

    @Test
    fun `extra values absent from the APK pass, and are counted`() {
        val (code, report, err) = extraCheck(apk(cleanApk), listOf(line(extraFound), line(extraAbsent)))
        assertEquals(0, code, report + err)
        assertTrue(report.startsWith("checkBetaHasNoDevSettings: app-beta.apk carries none of local.properties' dev settings."), report)
        assertTrue("2 extra entries" in report.lines().single { "Looked in" in it }, report)
        settings.keys.forEach { assertTrue(it in report, "$it named") }
        assertNoExtras(report, extraFound, extraAbsent)
    }

    @Test
    fun `an extra value under 5 characters is skipped and counted`() {
        val apk = apk(cleanApk + ("classes2.dex" to bytes("dex\n035", extraShort.second)))
        val (code, report, err) = extraCheck(apk, listOf(line(extraShort), line(extraAbsent)))
        assertEquals(0, code, report + err)
        assertTrue("1 extra entry" in report.lines().single { "Not checked" in it }, report)
        assertNoExtras(report, extraShort, extraAbsent)
    }

    @Test
    fun `a line with no TAB fails, naming its line number only`() {
        val bare = joined("guard", "-d ", "cnry", "-", "t2w", "-basil")
        val (code, report, err) = extraCheck(apk(cleanApk), listOf(line(extraAbsent), bare))
        assertNotEquals(0, code, report + err)
        assertTrue("line 2" in err, err)
        assertFalse(bare in err || "t2w" in err || extraAbsent.first in err, err)
        assertFalse("carries none" in report, report)
        // The build's own settings file the same: such a line was dropped, and its value never looked for.
        val needles = File(dir, "bare.tsv").apply { writeText("devTmdbKey\t0123456789abcdef0123456789abcdef\n$bare\n") }
        val (settingsCode, settingsErr) = stderrOf {
            runSecretCheck(arrayOf("--apk", apk(cleanApk).path, "--settings", needles.path, "--control", control))
        }
        assertNotEquals(0, settingsCode, settingsErr)
        assertTrue("line 2" in settingsErr, settingsErr)
        assertFalse(bare in settingsErr || "t2w" in settingsErr, settingsErr)
        // The build writes that file: such a line is a setting's value broken in two, and the setting above it is named.
        assertTrue("devTmdbKey" in settingsErr && "line break" in settingsErr, settingsErr)
        // An --extra file's label may say what it guards: it is never named.
        assertFalse("line break" in err, err)
    }

    @Test
    fun `lines starting with a hash are comments, skipped as the canary file's rules say, a TAB in them or not`() {
        // A comment's text is never looked for, though the APK holds it.
        val note = joined("cnry", "-", "n0t", "-chervil")
        val apk = apk(cleanApk + ("classes2.dex" to bytes("dex\n035", note)))
        val lines = listOf(joined("# ", "canaries kept by hand"), line(extraAbsent), "", joined("#", "guard-n"), "#\t$note")
        val (code, report, err) = extraCheck(apk, lines)
        assertEquals(0, code, report + err)
        assertTrue("1 extra entry" in report.lines().single { "Looked in" in it }, report)
        assertNoExtras(report + err, extraAbsent, joined("#", "guard-n") to note)
    }

    /*
     * The extra entries follow the canary file's rules for a binary, as the tree scans read one: any case; a w: or w2:
     * label's value as a whole word only; a w2: value under 5 characters never, as two letters turn up by chance.
     */

    @Test
    fun `an extra value is found in any case`() {
        val upper = extraFound.second.uppercase()
        val apk = apk(cleanApk + ("classes2.dex" to bytes("dex\n035", upper)))
        val (code, report, err) = extraCheck(apk, listOf(line(extraFound)))
        assertEquals(1, code, report + err)
        assertTrue("app-beta.apk!classes2.dex: 1 extra entry" in report, report)
        assertFalse(upper in report || upper in err, report + err)
        assertNoExtras(report + err, extraFound)
    }

    @Test
    fun `a whole-word entry is found as a word only, in UTF-8 and in UTF-16`() {
        val bird = joined("w:", "bird1") to joined("Kest", "rel")
        val brand = joined("w2:", "brand2") to joined("Zen", "vo")
        val entries = listOf(line(bird), line(brand))
        // Inside other words, before an underscore or a digit: not these words.
        val inside = bytes("dex\n035", joined("kest", "relsong"), joined("my", "kestrel"), joined("Kestrel", "_x"), joined("zenvo", "9"))
        val insideWide = joined("Kestrel", "Song ", "ZENVO", "s").toByteArray(Charsets.UTF_16LE)
        val (code, report, err) = extraCheck(apk(cleanApk + ("classes2.dex" to inside) + ("resources.arsc" to insideWide)), entries)
        assertEquals(0, code, report + err)
        assertTrue("2 extra entries" in report.lines().single { "Looked in" in it }, report)
        // Each a word of its own, in any case and encoding: found.
        for ((where, content) in listOf(
            "classes2.dex" to bytes("dex\n035", joined("the ", "kestrel", " flies")),
            "resources.arsc" to joined("a ", "KESTREL", ".").toByteArray(Charsets.UTF_16LE),
            "assets/fonts.txt" to joined("by ", "Zenvo", "\n").toByteArray(Charsets.UTF_16BE),
        )) {
            val (foundCode, foundReport, foundErr) = extraCheck(apk(cleanApk + (where to content)), entries)
            assertEquals(1, foundCode, "$where: $foundReport$foundErr")
            assertTrue("app-beta.apk!$where: 1 extra entry" in foundReport, foundReport)
            assertNoExtras(foundReport + foundErr, bird, brand)
        }
    }

    @Test
    fun `a short-word entry under 5 characters is never looked for in the APK, and is counted`() {
        val brand = joined("w2:", "brand1") to joined("Q", "x")
        val apk = apk(cleanApk + ("classes2.dex" to bytes("dex\n035", joined("q", "x"), joined(" Q", "x "))))
        val (code, report, err) = extraCheck(apk, listOf(line(brand), line(extraAbsent)))
        assertEquals(0, code, report + err)
        assertTrue("1 extra entry" in report.lines().single { "Not checked" in it }, report)
        assertTrue("1 extra entry" in report.lines().single { "Looked in" in it }, report)
        assertNoExtras(report + err, brand, extraAbsent)
    }

    /*
     * A wt: entry is a common word that library data holds too (a word of a title, a city): the text scans look for it,
     * the APK check never does. It is counted as not checked there, never found, and never fails the check.
     */

    @Test
    fun `a text-only entry is never looked for in the APK, and is counted as not checked there`() {
        val city = joined("Vel", "dramor")
        val textOnly = joined("wt:", "place1") to city
        val word = joined("w:", "place2") to city
        val apk = apk(
            cleanApk + ("classes2.dex" to bytes("dex\n035", joined("the ", city, " zone"))) +
                ("assets/zones.txt" to joined(city, "\n").toByteArray(Charsets.UTF_8)),
        )
        val (code, report, err) = extraCheck(apk, listOf(line(textOnly), line(extraAbsent)))
        assertEquals(0, code, report + err)
        assertTrue(report.startsWith("checkBetaHasNoDevSettings: app-beta.apk carries none of local.properties' dev settings."), report)
        assertTrue("1 extra entry" in report.lines().single { "Looked in" in it }, report)
        assertEquals("  Not checked in binaries (text only): 1 extra entry.", report.lines().single { "(text only)" in it }, report)
        assertFalse(report.lines().any { "shorter than" in it }, report)
        assertNoExtras(report + err, textOnly, extraAbsent)
        // The same value under a w: label is looked for, and found in both entries; the wt: line is still not checked.
        val (wordCode, wordReport, wordErr) = extraCheck(apk, listOf(line(textOnly), line(word)))
        assertEquals(1, wordCode, wordReport + wordErr)
        assertTrue("carries 1 extra entry of the 1 looked for, on line 2 of the --extra file. Don't publish it:" in wordReport, wordReport)
        assertTrue("app-beta.apk!classes2.dex: 1 extra entry" in wordReport, wordReport)
        assertTrue("app-beta.apk!assets/zones.txt: 1 extra entry" in wordReport, wordReport)
        assertTrue("1 extra entry" in wordReport.lines().single { "(text only)" in it }, wordReport)
        assertNoExtras(wordReport + wordErr, textOnly, word)
    }

    @Test
    fun `text-only entries alone leave nothing to look for in the APK, and pass`() {
        val textOnly = joined("wt:", "place1") to joined("Vel", "dramor")
        val shortTextOnly = joined("wt:", "place3") to joined("V", "el")
        val apk = apk(cleanApk + ("classes2.dex" to bytes("dex\n035", textOnly.second, shortTextOnly.second)))
        val (code, report, err) = extraCheck(apk, listOf(line(textOnly), line(shortTextOnly)), emptyMap())
        assertEquals(0, code, report + err)
        assertFalse(report.lines().any { "Looked in" in it || "long enough" in it }, report)
        assertTrue("1 extra entry" in report.lines().single { "(text only)" in it }, report)
        // Under 5 characters it is skipped as any value is, and counted with them.
        assertTrue("1 extra entry" in report.lines().single { "shorter than" in it }, report)
        assertNoExtras(report + err, textOnly, shortTextOnly)
    }

    @Test
    fun `a found extra entry is told by its line in the file, never by its label`() {
        val apk = apk(cleanApk + ("classes2.dex" to bytes("dex\n035", extraFound.second)))
        val lines = listOf(joined("# ", "kept by hand"), line(extraAbsent), "", line(extraFound), line(extraShort))
        val (code, report, err) = extraCheck(apk, lines)
        assertEquals(1, code, report + err)
        assertTrue("carries 1 extra entry of the 2 looked for, on line 4 of the --extra file. Don't publish it:" in report, report)
        assertNoExtras(report + err, extraFound, extraAbsent, extraShort)
    }

    @Test
    fun `no report line holds a value or an extra entry's label`() {
        // One of each found, absent and too short, for the dev settings and the extra entries alike.
        val withShort = settings + ("devProvider.username" to joined("u", "qx"))
        val extras = listOf(extraFound, extraAbsent, extraShort)
        val hit = apk(cleanApk + ("classes2.dex" to bytes("dex\n035", "hunter2-SECRET", extraFound.second, extraShort.second, "uqx")))
        val (code, report, err) = extraCheck(hit, extras.map(::line), withShort)
        assertEquals(1, code, report + err)
        assertTrue("classes2.dex: devProvider.password" in report && "classes2.dex: 1 extra entry" in report, report)
        val (passCode, passReport, passErr) = extraCheck(apk(cleanApk), extras.map(::line), withShort)
        assertEquals(0, passCode, passReport + passErr)
        listOf(report, err, passReport, passErr).forEach { text ->
            assertNoValues(text, withShort)
            assertNoExtras(text, *extras.toTypedArray())
        }
    }
}
