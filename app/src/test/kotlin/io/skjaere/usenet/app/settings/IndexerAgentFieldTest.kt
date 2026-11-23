package io.skjaere.usenet.app.settings

import io.skjaere.usenet.app.debrid.newTorrentIndexerForm
import io.skjaere.usenet.newznab.UserAgentRule
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The indexer forms' User-Agent field (L546, L548): blank sends the app's own, and a value the client's rule refuses
 * holds Save and Test, its words in the help line's place.
 */
class IndexerAgentFieldTest {
    private val form = newIndexerForm().copy(url = "https://indexer.example.org/api", apiKey = "KEY")

    @Test
    fun `blank and a padded 256 chars are valid, under the help line`() {
        listOf("", "   ", "ExampleReader/2.1", "QuarryFetch/1.0 (compatible; +https://quarry.example)", " ${"a".repeat(256)} \t").forEach { value ->
            val typed = form.copy(userAgent = value)
            assertNull(typed.agentProblem, "\"$value\"")
            assertTrue(typed.valid, "\"$value\"")
            assertEquals(USER_AGENT_HELP, typed.agentHelp, "\"$value\"")
        }
    }

    /** The keyboard's own letters, smart quotes and dashes, and the controls neither stack would send whole. */
    @Test
    fun `a char it can't send holds save and test, and says which it can`() {
        listOf("Examplé/2.1", "ExampleReader’s/2.1", "Example–Reader/2.1", "Example\tReader/2.1", "Example\r\nReader/2.1").forEach { value ->
            val typed = form.copy(userAgent = value)
            assertEquals(UserAgentRule.Problem.Characters, typed.agentProblem, "\"$value\"")
            assertFalse(typed.valid, "\"$value\"")
            assertEquals("Only letters A–Z, digits, spaces and punctuation.", typed.agentHelp, "\"$value\"")
        }
    }

    @Test
    fun `257 chars hold save and test, and say how many it can`() {
        val typed = form.copy(userAgent = "a".repeat(257))
        assertEquals(UserAgentRule.Problem.TooLong, typed.agentProblem)
        assertFalse(typed.valid)
        assertEquals("At most 256 characters.", typed.agentHelp)
        // A char it can't send is said first, as the rule says it.
        assertEquals("Only letters A–Z, digits, spaces and punctuation.", form.copy(userAgent = "é".repeat(257)).agentHelp)
    }

    /** W143 (b), under the field on both forms. */
    @Test
    fun `the help line says it is optional, what is sent without one, and when to change it`() {
        assertEquals("Optional. Without one, NZStreamer’s own is sent. Change it only if this indexer asks for one.", USER_AGENT_HELP)
    }

    /** The torrent form is the same fields: its Save and Test are held the same way. */
    @Test
    fun `the torrent form is held by the same rule`() {
        val torrent = newTorrentIndexerForm().let { it.copy(fields = it.fields.copy(url = "https://quarry.example/api", apiKey = "KEY")) }
        assertTrue(torrent.copy(fields = torrent.fields.copy(userAgent = "QuarryFetch/1.0")).valid)
        assertFalse(torrent.copy(fields = torrent.fields.copy(userAgent = "QuarryFetch/1.0 ©")).valid)
        assertFalse(torrent.copy(fields = torrent.fields.copy(userAgent = "a".repeat(257))).valid)
    }
}
