package io.skjaere.usenet.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The player's guard against a black screen ([VideoTracks.leftOut]): a file with video the player selected none of (no
 * decoder takes it) is named, so the player can stop and say why instead of playing the sound alone. It catches where
 * the probe's renderers judged otherwise than the player's; video the extractor dropped it can't see, as there is none.
 */
class VideoTracksTest {
    private val aac = TrackFixtures.audio("2", "en")
    private val dolbyVision = TrackFixtures.video("1", MimeTypes.VIDEO_DOLBY_VISION, "dvhe.07.06", 3840, 2160, bitDepth = 10)

    @Test
    fun `video the player selected none of is named`() {
        val tracks = TrackFixtures.tracks(listOf(dolbyVision, aac), selected = setOf("2"), support = mapOf("1" to C.FORMAT_UNSUPPORTED_SUBTYPE))
        assertEquals(listOf("2160p Dolby Vision profile 7"), VideoTracks.leftOut(tracks))
    }

    @Test
    fun `selected video, a file without video and no tracks yet are fine`() {
        assertNull(VideoTracks.leftOut(TrackFixtures.tracks(listOf(dolbyVision, aac), selected = setOf("1", "2"))))
        // One of two video tracks selected.
        val two = TrackFixtures.tracks(listOf(dolbyVision, TrackFixtures.video("3"), aac), selected = setOf("3", "2"))
        assertNull(VideoTracks.leftOut(two))
        assertNull(VideoTracks.leftOut(TrackFixtures.tracks(listOf(aac), selected = setOf("2"))))
        assertNull(VideoTracks.leftOut(TrackFixtures.tracks(emptyList())))
    }
}
