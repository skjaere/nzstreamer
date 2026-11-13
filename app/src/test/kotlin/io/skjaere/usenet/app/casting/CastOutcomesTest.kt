package io.skjaere.usenet.app.casting

import io.skjaere.usenet.serve.ServeCounters
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the phone says of a cast play's failure on its device (L60, L88, L102), from the receiver's code and what the
 * phone's server saw: the kind (and with it the sheet's buttons) and the card's words. The device is invented.
 */
class CastOutcomesTest {
    private val name = "Kitchen display"
    private val mkv = CastFile("mkv", video = true)
    private val none = ServeCounters()
    private val fetched = ServeCounters(requests = 2, partials = 2, firstRequestAt = 10)
    private val asked = ServeCounters(requests = 1, firstRequestAt = 10)
    private val upstream = fetched.copy(upstreamFailures = 1)

    private fun error(code: Int?, served: ServeCounters, file: CastFile = mkv) = CastOutcomes.error(code, served, name, file)

    @Test
    fun `a decoding error is the device's, with the note that Cast devices play fewer kinds`() {
        assertEquals(CastLoadFailure(CastFailKind.CouldNotPlay, "The device's player stopped with a decoding error", format = true), error(102, fetched))
    }

    /** 104 and 905 alike: the device couldn't open the file it fetched; with no request at all, it couldn't fetch it. */
    @Test
    fun `can't open after 206s were sent, can't reach with no request`() {
        val cantOpen = CastLoadFailure(CastFailKind.CouldNotPlay, "The device's player can't open this file", format = true)
        assertEquals(cantOpen, error(104, fetched))
        assertEquals(cantOpen, error(905, fetched))
        assertEquals(CastLoadFailure(CastFailKind.CannotReach), error(104, none))
        assertEquals(CastLoadFailure(CastFailKind.CannotReach), error(905, none))
        // Asked, and nothing sent: not the file's fault the phone can tell.
        assertEquals(CastLoadFailure(CastFailKind.CouldNotPlay, "The device's player stopped (error 104)"), error(104, asked))
    }

    /**
     * L76, L101: the providers failing inside the first answer cut it, which the device can only say as a file it couldn't
     * open (Chromium's 104 for anything before the metadata). The phone knows better: the release's fault, never the
     * device's formats, and Try another release forgets it.
     */
    @Test
    fun `can't open after the head's upstream failed is the providers'`() {
        val providers = CastLoadFailure(CastFailKind.CouldNotPlay, "Couldn't read the release from your providers", providers = true)
        val head = upstream.copy(headFailures = 1)
        assertEquals(providers, error(104, head))
        assertEquals(providers, error(905, head))
        assertEquals(CastLoadFailure(CastFailKind.CannotReach), error(104, none.copy(upstreamFailures = 1, headFailures = 1)), "nothing asked: nothing to read")
    }

    /**
     * L88 (CSL-6, as the lead confirmed it): only the providers failing the head, the answer from byte 0 the device reads
     * before it plays, makes a 104 or 905 theirs. A failure in a later read (the tail's index, say), then the device's
     * own refusal of the file, is the device's formats, and the release isn't forgotten for it.
     */
    @Test
    fun `can't open after only a later read failed is the device's formats`() {
        val cantOpen = CastLoadFailure(CastFailKind.CouldNotPlay, "The device's player can't open this file", format = true)
        val tail = fetched.copy(upstreamFailures = 1)
        assertEquals(cantOpen, error(104, tail))
        assertEquals(cantOpen, error(905, tail))
    }

    /**
     * An MP4, M4V or MOV may keep its index (its moov) at the file's end, which the device reads before it can open the
     * file: a failed read there is the head's, so a 104 or 905 after it is the providers', and the release
     * is forgotten for it. A Matroska file's later read is its Cues, which it opens without: still the device's formats.
     */
    @Test
    fun `can't open after any read of an MP4 failed is the providers', its index may be at the end`() {
        val providers = CastLoadFailure(CastFailKind.CouldNotPlay, "Couldn't read the release from your providers", providers = true)
        val cantOpen = CastLoadFailure(CastFailKind.CouldNotPlay, "The device's player can't open this file", format = true)
        val tail = fetched.copy(upstreamFailures = 1)
        for (extension in listOf("mp4", "m4v", "mov")) {
            assertEquals(providers, error(104, tail, CastFile(extension, video = true)), extension)
            assertEquals(providers, error(905, tail, CastFile(extension, video = true)), extension)
            assertEquals(cantOpen, error(104, fetched, CastFile(extension, video = true)), "$extension, nothing failed")
        }
        assertEquals(cantOpen, error(104, tail, CastFile("webm", video = true)))
    }

    @Test
    fun `a lost stream is the providers' when an upstream failed, else the connection to this phone's`() {
        assertEquals(CastLoadFailure(CastFailKind.CouldNotPlay, "Couldn't read the release from your providers", providers = true), error(103, upstream))
        assertEquals(CastLoadFailure(CastFailKind.CouldNotPlay, "Couldn't read the release from your providers", providers = true), error(300, upstream))
        assertEquals(CastLoadFailure(CastFailKind.CouldNotPlay, "Kitchen display lost the stream from this phone"), error(103, fetched))
        assertEquals(CastLoadFailure(CastFailKind.CouldNotPlay, "Kitchen display lost the stream from this phone"), error(300, none))
    }

    /**
     * L220 (D2PhoneCastWords 3): a debrid release is read from the user's debrid service, not from providers, so each path
     * that blames the reading of the file says so, in words that name no service; Try another release still leaves it
     * out. What isn't the reading's fault keeps its words.
     */
    @Test
    fun `a debrid release that couldn't be read names the debrid service, never the providers`() {
        val service = CastLoadFailure(CastFailKind.CouldNotPlay, "Couldn't read the release from your debrid service", providers = true)
        val mkv = CastFile("mkv", video = true, fromService = true)
        val mp4 = CastFile("mp4", video = true, fromService = true)
        val head = upstream.copy(headFailures = 1)
        // The head's read failed, or any of an MP4's.
        assertEquals(service, error(104, head, mkv))
        assertEquals(service, error(905, head, mkv))
        assertEquals(service, error(104, upstream, mp4))
        // The media gone with no code after a read failed.
        assertEquals(service, CastOutcomes.vanished(upstream, name, mkv))
        assertEquals(service, error(null, upstream, mkv))
        // The stream lost after a read failed.
        assertEquals(service, error(103, upstream, mkv))
        assertEquals(service, error(300, upstream, mkv))
        assertFalse("TorBox" in service.reason.orEmpty() || "Premiumize" in service.reason.orEmpty())

        assertEquals(CastLoadFailure(CastFailKind.CouldNotPlay, "Kitchen display lost the stream from this phone"), error(103, fetched, mkv))
        assertEquals(CastLoadFailure(CastFailKind.CouldNotPlay, "The device's player can't open this file", format = true), error(104, fetched, mkv))
        assertEquals(CastLoadFailure(CastFailKind.CannotReach), error(104, none, mkv))
    }

    @Test
    fun `an unknown code is said by its number`() {
        assertEquals(CastLoadFailure(CastFailKind.CouldNotPlay, "The device's player stopped (error 110)"), error(110, fetched))
        assertEquals(CastLoadFailure(CastFailKind.CouldNotPlay, "The device's player stopped (error 100)"), error(100, none))
    }

    /** L102: the media gone with no code, read from what the server saw and what the file is. */
    @Test
    fun `a media session that vanished is read from the server and the file`() {
        assertEquals(CastLoadFailure(CastFailKind.CannotReach), CastOutcomes.vanished(none, name, mkv))
        assertEquals(CastLoadFailure(CastFailKind.CouldNotPlay, "Couldn't read the release from your providers", providers = true), CastOutcomes.vanished(upstream, name, mkv))
        assertEquals(CastLoadFailure(CastFailKind.CouldNotPlay, "The device's player closed the file without an error"), CastOutcomes.vanished(fetched, name, mkv))
        // No code at all reads the same way.
        assertEquals(CastOutcomes.vanished(fetched, name, mkv), error(null, fetched))
    }

    /** A castable type the receiver may still refuse as it opens it: the card says it refused it, never that its type can't play. */
    @Test
    fun `a borderline file refused as it opened is a file type's failure that never calls its type unplayable`() {
        val mov = CastOutcomes.vanished(fetched, name, CastFile("mov", video = true))
        assertEquals(CastLoadFailure(CastFailKind.FileType, "MOV file. Kitchen display refused it as it opened."), mov)
        assertFalse("Cast devices play" in mov.reason.orEmpty())
        assertEquals("M4V file. Kitchen display refused it as it opened.", CastOutcomes.vanished(fetched, name, CastFile("m4v", video = true)).reason)
        val audio = CastOutcomes.vanished(fetched, name, CastFile("mkv", video = false))
        assertEquals(CastLoadFailure(CastFailKind.FileType, "Audio-only file. Kitchen display refused it as it opened."), audio)
        assertEquals("Audio-only file. Kitchen display refused it as it opened.", CastOutcomes.vanished(fetched, name, CastFile("mov", video = false)).reason)
        assertTrue(CastFile("mov", video = true).borderline)
        assertFalse(mkv.borderline)
    }

    /** L60: the session ended before the device played: nothing came, or it closed; either way the device is no longer chosen. */
    @Test
    fun `a session that ended before it played`() {
        assertEquals(CastLoadFailure(CastFailKind.CannotReach, sessionEnded = true), CastOutcomes.closedBeforePlaying(none, name))
        assertEquals(CastLoadFailure(CastFailKind.CouldNotPlay, "Kitchen display closed before it played", sessionEnded = true), CastOutcomes.closedBeforePlaying(fetched, name))
    }

    @Test
    fun `nothing came in time`() {
        assertEquals(CastLoadFailure(CastFailKind.CannotReach), CastOutcomes.noRequest())
    }
}
