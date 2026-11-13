package io.skjaere.usenet.app.settings

import io.skjaere.usenet.metadata.KeyCheck
import io.skjaere.usenet.metadata.TmdbError
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * A TMDB key's Check and save, as Settings › Metadata and "Set up with your phone" both run it: the key is saved only
 * when TMDB accepts it, in Metadata's words.
 */
class TmdbKeyCheckTest {
    private val asked = mutableListOf<String>()
    private val saved = mutableListOf<String>()

    private fun check(key: String, answer: KeyCheck) = runBlocking {
        checkAndSaveTmdbKey(key, validate = { asked += it; answer }, save = { saved += it })
    }

    @Test
    fun `a key TMDB accepts is saved, trimmed`() {
        assertEquals(TmdbKeyCheck(ok = true, message = "Key works"), check("  0123abcd  ", KeyCheck.Valid))
        assertEquals(listOf("0123abcd"), asked)
        assertEquals(listOf("0123abcd"), saved)
    }

    @Test
    fun `a key TMDB rejects is not saved`() {
        assertEquals(TmdbKeyCheck(ok = false, message = "TMDB rejected this key."), check("0123abcd", KeyCheck.Invalid))
        assertEquals(emptyList(), saved)
    }

    @Test
    fun `a key that couldn't be checked is not saved, and says why`() {
        val result = check("0123abcd", KeyCheck.Failed(TmdbError.Network("No answer from TMDB")))
        assertEquals(TmdbKeyCheck(ok = false, message = "Couldn't check the key: No answer from TMDB"), result)
        assertEquals(emptyList(), saved)
    }
}
