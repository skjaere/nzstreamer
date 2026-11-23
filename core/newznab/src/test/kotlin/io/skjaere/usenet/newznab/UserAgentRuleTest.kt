package io.skjaere.usenet.newznab

import io.ktor.http.HttpHeaders
import okhttp3.Headers
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * L548: one rule for an indexer's own User-Agent, which the forms, the phone setup page and the client's guard keep alike.
 * What it takes, both HTTP stacks take, so a value never fails in the engine with itself in the error; what it refuses, one
 * of them refuses too, but for tab, which it refuses by choice.
 */
class UserAgentRuleTest {

    private val chars = (0..0xFFFF).map { it.toChar() }

    /** The 27 chars outside ' '..'~' that Kotlin's trim() drops (isWhitespace on the JVM: Java's whitespace or space chars). */
    private val trimmed = listOf('\t', '\n', '\u000B', '\u000C', '\r', '\u001C', '\u001D', '\u001E', '\u001F', ' ', ' ') +
        (' '..' ') + listOf(' ', ' ', ' ', ' ', '　')

    private fun okHttpTakes(value: String) = runCatching { Headers.Builder().add("User-Agent", value) }.isSuccess

    private fun ktorTakes(value: String) = runCatching { HttpHeaders.checkHeaderValue(value) }.isSuccess

    private fun shown(c: Char) = "U+%04X".format(c.code)

    @Test
    fun `every printable ascii char is taken, and blank is the client's own`() {
        assertNull(UserAgentRule.problem("a${(' '..'~').joinToString("")}b"))
        assertNull(UserAgentRule.problem("ExampleReader/2.1 (compatible; QuarryFetch/1.0)"))
        listOf("", " ", "   ", "\t", " \r\n ", " 　").forEach { blank ->
            assertNull(UserAgentRule.problem(blank), blank.map(::shown).toString())
        }
    }

    @Test
    fun `256 chars after trimming are taken and 257 are too long`() {
        val most = "x".repeat(UserAgentRule.MAX_LENGTH)
        assertEquals(256, UserAgentRule.MAX_LENGTH)
        assertNull(UserAgentRule.problem(most))
        assertNull(UserAgentRule.problem("  $most\t\n"))
        assertEquals(UserAgentRule.Problem.TooLong, UserAgentRule.problem(most + "x"))
        assertEquals(UserAgentRule.Problem.TooLong, UserAgentRule.problem(" ${"x".repeat(128)} ${"x".repeat(128)} "))
        // A char it can't send is said first, however long the value.
        assertEquals(UserAgentRule.Problem.Characters, UserAgentRule.problem("é$most"))
    }

    @Test
    fun `each char inside a value is taken exactly when it is printable ascii`() {
        val wrong = chars.filter { c -> UserAgentRule.problem("a${c}b") != (if (c in ' '..'~') null else UserAgentRule.Problem.Characters) }
        assertEquals(emptyList(), wrong.map(::shown))
    }

    @Test
    fun `chars it refuses between others are its characters problem`() {
        listOf("a\tb", "a\r\nb", "a\u0000b", "a\u007Fb", "ExampléReader/2.1", "QuarryFetch’s/1.0", "Example😀Reader/2.1").forEach { value ->
            assertEquals(UserAgentRule.Problem.Characters, UserAgentRule.problem(value), value.map(::shown).toString())
        }
    }

    @Test
    fun `each char trim drops is dropped at either end, and the value is taken`() {
        assertEquals(trimmed.map(::shown), chars.filter { it.isWhitespace() && it !in ' '..'~' }.map(::shown))
        trimmed.forEach { c ->
            listOf("${c}ExampleReader/2.1", "ExampleReader/2.1$c", "$c$c ExampleReader/2.1 $c").forEach { value ->
                assertNull(UserAgentRule.problem(value), value.map(::shown).toString())
                assertEquals("ExampleReader/2.1", value.trim())
            }
        }
    }

    /**
     * What request() sends is the value trimmed: each one the rule takes passes both stacks' checks. OkHttp takes tab and
     * ' '..'~' only, and throws with the whole value in its message; Ktor refuses only controls other than tab.
     */
    @Test
    fun `what the rule takes both http stacks take, and what it refuses one of them refuses, but for tab`() {
        val (taken, refused) = chars.partition { UserAgentRule.problem("a${it}b") == null }
        val takenValues = taken.map { "a${it}b" } + trimmed.flatMap { listOf("${it}QuarryFetch/1.0", "QuarryFetch/1.0$it") } +
            listOf(" ${"y".repeat(256)} ", "a${(' '..'~').joinToString("")}b")
        takenValues.forEach { value ->
            assertNull(UserAgentRule.problem(value))
            assertTrue(okHttpTakes(value.trim()) && ktorTakes(value.trim()), value.map(::shown).toString())
        }
        assertEquals(emptyList(), refused.filter { it != '\t' && okHttpTakes("a${it}b") && ktorTakes("a${it}b") }.map(::shown))
        // Tab passes both stacks: the rule alone refuses it.
        assertTrue('\t' in refused)
        assertTrue(okHttpTakes("a\tb") && ktorTakes("a\tb"))
        assertFalse(UserAgentRule.problem("a\tb") == null)
    }
}
