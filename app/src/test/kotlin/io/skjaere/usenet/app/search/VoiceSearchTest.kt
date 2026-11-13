package io.skjaere.usenet.app.search

import android.speech.RecognizerIntent
import androidx.compose.runtime.saveable.SaverScope
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Voice search's pure parts: what the recognizer is asked for, what is taken from its answer, and the search key's one-shot request. */
class VoiceSearchTest {
    @Test
    fun `the recognizer is asked for free-form words in the device's language, one result, with the prompt`() {
        val extras = VoiceSearch.extras(locale = Locale.forLanguageTag("fr-FR"))
        assertEquals(
            mapOf(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL to RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                RecognizerIntent.EXTRA_LANGUAGE to "fr-FR",
                RecognizerIntent.EXTRA_MAX_RESULTS to 1,
                RecognizerIntent.EXTRA_PROMPT to "Say a show, a movie or a release name",
            ),
            extras,
        )
    }

    @Test
    fun `the words said are the first result, trimmed`() {
        assertEquals("glasswater bay", VoiceSearch.first(listOf("  glasswater bay ", "glass water bay")))
    }

    @Test
    fun `nothing said, or nothing heard, is no words`() {
        assertNull(VoiceSearch.first(null))
        assertNull(VoiceSearch.first(emptyList()))
        assertNull(VoiceSearch.first(listOf("   ")))
    }

    @Test
    fun `a cancelled recognizer gives no words`() {
        assertNull(VoiceSearch.result(android.app.Activity.RESULT_CANCELED, null))
    }

    /**
     * Android 11+ shows the app only the apps its manifest asks about: the recognizer is one, or the mic never shows. The
     * recognizer listens, not the app: no RECORD_AUDIO, so a TV's microphone isn't required.
     */
    @Test
    fun `the manifest lets the app see a recognizer and asks for no audio`() {
        val manifest = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml")).documentElement
        fun Element.all(tag: String): List<Element> = getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }
        val asked = manifest.all("queries").flatMap { it.all("intent") }.flatMap { it.all("action") }.map { it.getAttributeNS(ANDROID_NS, "name") }
        assertTrue(RecognizerIntent.ACTION_RECOGNIZE_SPEECH in asked, "no <queries> entry for the recognizer: $asked")
        val permissions = manifest.all("uses-permission").map { it.getAttributeNS(ANDROID_NS, "name") }
        assertFalse("android.permission.RECORD_AUDIO" in permissions, "the app asks to record audio")
    }

    @Test
    fun `the search key's request is taken once`() {
        val request = VoiceRequest()
        assertFalse(request.consume(), "nothing asked yet")
        request.request()
        assertTrue(request.consume())
        assertFalse(request.consume(), "taken twice")
    }

    @Test
    fun `a restore keeps a request still to be taken, and never brings back one that was`() {
        val scope = SaverScope { true }
        val asked = VoiceRequest().apply { request() }
        val restoredAsked = with(VoiceRequest.Saver) { restore(scope.save(asked)!!) }!!
        assertTrue(restoredAsked.consume(), "a request not yet taken, lost in the restore")

        val taken = VoiceRequest().apply { request(); consume() }
        val restoredTaken = with(VoiceRequest.Saver) { restore(scope.save(taken)!!) }!!
        assertFalse(restoredTaken.consume(), "a request already taken came back")
    }
}

private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
