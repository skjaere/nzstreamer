package io.skjaere.usenet.app

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * W168 (a), L656, L182: the build's secret checks look for the plugins' dev settings, local.properties' devSportarr.url
 * and devSportarr.key, in every build but debug. SecretCheck names no setting of its own (it reads the files the build
 * writes for it), so this holds the names, reading app/build.gradle.kts as text (the app's unit tests declare it an
 * input): each name pattern as the build spells it, and which of them each check hands SecretCheck.
 * - checkNoDebridDevKeys in release, bench and the published beta: both, as every debrid name.
 * - The same in a private beta with preset settings (carriesDevSettings): the key as strictly as a debrid service's key;
 *   the address, with its host, may pass where a setting that build carries on purpose has the same value, as a torrent
 *   indexer's may (one ingress for both).
 * - checkBetaHasNoDevSettings: both.
 * - A failure's advice names both.
 * Settings are named here, never given a value.
 */
class DevSettingsChecksTest {
    private val build = File("build.gradle.kts").readText()

    /** The build file's name patterns, `val <NAME>_NAMES = ...` at its top level, each read as Kotlin reads it. */
    private val patterns: Map<String, String> = buildMap {
        Regex("""^val ([A-Z_]+_NAMES) = (.+)$""", RegexOption.MULTILINE).findAll(build).forEach { found ->
            val (name, expression) = found.destructured
            put(name, read(expression, this))
        }
    }

    /** checkNoDebridDevKeys' body: its two branches' names and its advice. */
    private val noDebridDevKeys = build.substringAfter("fun checkNoDebridDevKeys(", "").substringBefore("\nval checkNoDebridDevKeysRelease")

    @Test
    fun `the plugins' dev settings are named as local properties spells them`() {
        val names = pattern("PLUGIN_DEV_NAMES")
        PLUGIN.forEach { assertTrue(names.matches(it), "PLUGIN_DEV_NAMES takes $it") }
        listOf("devSportarrXurl", "devSportarr.apikey", "devSportarr.url2", "xdevSportarr.key", "devSportarr.").forEach {
            assertFalse(names.matches(it), "PLUGIN_DEV_NAMES leaves out $it")
        }
    }

    @Test
    fun `release, bench and the published beta look for both, as for every debrid name`() {
        val strict = strict(carriesDevSettings = false)
        (PLUGIN + DEBRID).forEach { assertTrue(strict.matches(it), "checkNoDebridDevKeys looks for $it") }
    }

    @Test
    fun `a private beta with preset settings looks for the key as strictly as a service's, and lets the address pass as carried`() {
        val strict = strict(carriesDevSettings = true)
        listOf("devSportarr.key", "devTorBoxKey", "devPremiumizeKey").forEach { assertTrue(strict.matches(it), "never carried: $it") }
        listOf("devSportarr.url", "devTorrentIndexer.1.url").forEach { assertFalse(strict.matches(it), "not looked for as strictly: $it") }
        val carriable = carriable()
        listOf("devSportarr.url", "devTorrentIndexer.1.url", "devTorrentIndexer.1.apikey").forEach {
            assertTrue(carriable.matches(it), "passes where a carried setting has its value: $it")
        }
        listOf("devSportarr.key", "devTorBoxKey").forEach { assertFalse(carriable.matches(it), "never passes as carried: $it") }
        // An address's host is a setting of its own there (devCredentials), so a shared ingress passes by its host too.
        val credentials = build.substringAfter("fun devCredentials(", "").substringBefore("\n}")
        assertTrue("name.endsWith(\".url\")" in credentials, "devCredentials gives each address's host as a setting of its own")
    }

    @Test
    fun `the published beta's check looks for both`() {
        val check = build.substringAfter("val checkBetaHasNoDevSettings by tasks.registering", "")
        val expression = Regex("""devCredentials\(Regex\((.+)\)\)$""", RegexOption.MULTILINE).find(check)?.groupValues?.get(1)
            ?: fail("checkBetaHasNoDevSettings' names aren't `devCredentials(Regex(...))`")
        val names = Regex(read(expression, patterns))
        (PLUGIN + DEBRID + listOf("devProvider.host", "devTmdbKey", "devIndexer.1.apikey")).forEach {
            assertTrue(names.matches(it), "checkBetaHasNoDevSettings looks for $it")
        }
    }

    @Test
    fun `a failure's advice names both`() {
        val advice = Regex("\"--advice\", \"([^\"]*)\"").find(noDebridDevKeys)?.groupValues?.get(1)
            ?: fail("checkNoDebridDevKeys gives SecretCheck no --advice")
        PLUGIN.forEach { assertTrue(it in advice, "the advice names $it: $advice") }
    }

    private fun pattern(name: String) = Regex(patterns[name] ?: fail("app/build.gradle.kts defines no $name"))

    /** checkNoDebridDevKeys' strict names in a build that does or doesn't carry its dev settings on purpose. */
    private fun strict(carriesDevSettings: Boolean): Regex {
        val (carrying, other) = Regex("""val strict = devCredentials\(Regex\(if \(carriesDevSettings\) (.+?) else (.+)\)\)$""", RegexOption.MULTILINE)
            .find(noDebridDevKeys)?.destructured
            ?: fail("checkNoDebridDevKeys' strict names aren't `devCredentials(Regex(if (carriesDevSettings) A else B))`")
        return Regex(read(if (carriesDevSettings) carrying else other, patterns))
    }

    /** checkNoDebridDevKeys' names that may pass as carried, in a build that carries its dev settings on purpose. */
    private fun carriable(): Regex {
        val expression = Regex("""val carriable = if \(carriesDevSettings\) devCredentials\(Regex\((.+)\)\) else emptyList\(\)$""", RegexOption.MULTILINE)
            .find(noDebridDevKeys)?.groupValues?.get(1)
            ?: fail("checkNoDebridDevKeys' carriable names aren't `if (carriesDevSettings) devCredentials(Regex(...)) else emptyList()`")
        return Regex(read(expression, patterns))
    }

    /**
     * [expression] as Kotlin reads it, in the forms the build's names take: a name defined above it, or a raw string or a
     * string, each with its templates (`$NAME`, `${NAME}`) filled in from [known] and a string's escapes read.
     */
    private fun read(expression: String, known: Map<String, String>): String {
        val text = expression.trim()
        fun named(name: String) = known[name] ?: fail("app/build.gradle.kts uses $name before it defines it, or never does")
        val raw = text.length >= 6 && text.startsWith("\"\"\"") && text.endsWith("\"\"\"")
        if (!raw && !(text.length >= 2 && text.startsWith("\"") && text.endsWith("\""))) {
            return if (Regex("[A-Z_]+").matches(text)) named(text) else fail("an expression this test can't read: $text")
        }
        val body = if (raw) text.substring(3, text.length - 3) else text.substring(1, text.length - 1)
        val out = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            val next = body.getOrNull(i + 1)
            when {
                c == '\\' && !raw && next != null -> {
                    out.append(
                        when (next) {
                            'n' -> '\n'
                            't' -> '\t'
                            else -> next
                        },
                    )
                    i += 2
                }
                c == '$' && next == '{' -> {
                    val end = body.indexOf('}', i)
                    out.append(named(body.substring(i + 2, end)))
                    i = end + 1
                }
                c == '$' && next != null && (next.isLetter() || next == '_') -> {
                    val name = Regex("[A-Za-z_][A-Za-z0-9_]*").find(body, i + 1)!!.value
                    out.append(named(name))
                    i += 1 + name.length
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    private companion object {
        val PLUGIN = listOf("devSportarr.url", "devSportarr.key")
        val DEBRID = listOf("devTorBoxKey", "devPremiumizeKey", "devTorrentIndexer.1.url", "devTorrentIndexer.12.apikey")
    }
}
