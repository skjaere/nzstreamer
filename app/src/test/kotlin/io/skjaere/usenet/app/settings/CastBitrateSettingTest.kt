package io.skjaere.usenet.app.settings

import io.skjaere.usenet.data.PlaybackPrefs
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * W52, L132 (CsPhoneCastBitrate): Quality and languages' "Maximum bitrate when casting", under Maximum bitrate, on phones
 * and tablets alone. Its twelve choices are Maximum bitrate's, 150 and 200 among them (W102, L309), each read by TalkBack
 * in the section's words; a pick saves the casting maximum alone; under them, the explanation in two paragraphs, as
 * the board writes it (its ’). A TV has no casting maximum (W28, L110).
 */
class CastBitrateSettingTest {
    @Test
    fun `its twelve choices are Maximum bitrate's, Not set first`() {
        assertEquals("Maximum bitrate when casting", CastBitrateSetting.TITLE)
        assertEquals(listOf(null, 5, 10, 15, 20, 30, 40, 60, 80, 100, 150, 200), CastBitrateSetting.CHOICES)
        assertEquals(listOf<Int?>(null) + PlaybackPrefs.MAX_BITRATES_MBPS, CastBitrateSetting.CHOICES)
    }

    @Test
    fun `TalkBack reads each choice in the section's words`() {
        assertEquals("Maximum bitrate when casting 40 Mbps", CastBitrateSetting.label(40))
        assertEquals("Maximum bitrate when casting 5 Mbps", CastBitrateSetting.label(5))
        assertEquals("Maximum bitrate when casting 200 Mbps", CastBitrateSetting.label(200))
        assertEquals("No maximum bitrate when casting", CastBitrateSetting.label(null))
    }

    @Test
    fun `a pick saves the casting maximum alone, and the cells show what is saved`() {
        val prefs = PlaybackPrefs(maxBitrateMbps = 30, updatedAt = 7)
        assertNull(CastBitrateSetting.chosen(prefs))
        val forty = CastBitrateSetting.picked(prefs, 40)
        assertEquals(PlaybackPrefs(maxBitrateMbps = 30, castMaxBitrateMbps = 40, updatedAt = 7), forty)
        assertEquals(40, CastBitrateSetting.chosen(forty))
        assertEquals(prefs, CastBitrateSetting.picked(forty, null))
        // What can't be a speed shows as Not set, as the ranking reads it.
        assertNull(CastBitrateSetting.chosen(PlaybackPrefs(castMaxBitrateMbps = 0)))
    }

    @Test
    fun `the explanation is in two paragraphs, as the design writes it`() {
        assertEquals(
            listOf(
                "When you cast, the video comes to this phone first, and the phone sends it on to the Cast device. So over Wi-Fi it " +
                    "travels three times: from the router to the phone, from the phone back to the router, and from the router to the " +
                    "Cast device. That needs about three times the video’s bitrate.",
                "A Cast device or TV box connected by network cable takes one of those trips off the Wi-Fi, so use cables where you " +
                    "can. Releases above this limit are tried last when you cast.",
            ),
            CastBitrateSetting.EXPLANATION,
        )
    }

    @Test
    fun `a TV has no casting maximum`() {
        assertFalse(CastBitrateSetting.shows(television = true))
        assertTrue(CastBitrateSetting.shows(television = false))
    }
}
