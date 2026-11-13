package io.skjaere.usenet.player.tv

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl

/**
 * What a play may hold in memory: the player's sample buffer ([targetBufferBytes], for the load
 * control), the cap on what a stream holds ahead of the player ([streamBytes], see
 * [PlaybackBudgets.streamBytes]), and the image loader's memory cache (null: Coil's default).
 * [streamBytes] is only logged with the rest: the engine is set up with the cap once, at start, by
 * the app's provider sync, before any play has a budget, and a debrid play's read-ahead asks for it
 * as the player's source is made.
 */
data class PlaybackBudget(val targetBufferBytes: Int, val streamBytes: Long, val imageMemoryBytes: Long?)

/**
 * A play's budget for the device (TV-ME), from Google's TV memory guidance: media buffers of
 * 40-60 MB on a 1 GB low-RAM box at 1080p, 60-80 MB at 1.5 GB, 80-100 MB at 1.5 GB on a 4K screen.
 * Elsewhere a sixth of the heap the process may grow to, no less than 64 MB and no more than
 * Media3's own streaming target (125 MiB of video and 12.5 MiB of audio): at 0.4 of the heap, a TV
 * box's 512 MB large heap gave all of Media3's target, and a 4K remux's collections hitched
 * playback, which 80 MB there didn't (the Shield). A 32-bit process changes
 * nothing. [streamBytes] caps the segments a stream holds ahead of the player.
 */
@OptIn(UnstableApi::class)
object PlaybackBudgets {
    fun of(memory: DeviceMemory, uhdDisplay: Boolean): PlaybackBudget = when {
        !memory.lowRam -> PlaybackBudget(
            minOf(MEDIA3_TARGET, maxOf(64L * MB, memory.heapLimitBytes / 6)).toInt(), streamBytes(memory), imageMemoryBytes = null,
        )
        memory.totalRamBytes <= GB * 11 / 10 -> PlaybackBudget((48 * MB).toInt(), streamBytes(memory), 8 * MB)
        memory.totalRamBytes <= GB * 16 / 10 && uhdDisplay -> PlaybackBudget((80 * MB).toInt(), streamBytes(memory), 12 * MB)
        // At 1.5 GB on a 1080p screen, or an OEM's larger device flagged low RAM.
        else -> PlaybackBudget((64 * MB).toInt(), streamBytes(memory), 12 * MB)
    }

    /**
     * The cap on what a stream holds ahead of the player of segments downloaded or downloading and
     * not yet written to the stream (nzb-streamer's readAheadBytes): 8 MB on a low-RAM box of 1 GB,
     * 12 MB on a larger one; elsewhere a tenth of the heap, 16 to 64 MB. It caps a debrid play's
     * read-ahead too, the bytes its HTTP source holds in front of the player's buffer (L239, W88:
     * the same cushion as a Usenet play). The stream's channel adds
     * up to two segments and 3 MiB (readAheadBytes says why), and with parts of 1 MiB or more two
     * segments at most, as the player's reader waits for a byte at a time: 7.68 MB of a 4K remux's
     * 3.84 MB parts. Each download adds its buffers too. Counted in segments it was twelve, 46 MB of those
     * parts, beside an 80 MB buffer on a TV box's 192 MB heap. The same for every play, so the
     * engine is set up with it at start.
     */
    fun streamBytes(memory: DeviceMemory): Long = when {
        !memory.lowRam -> (memory.heapLimitBytes / 10).coerceIn(16 * MB, 64 * MB)
        memory.totalRamBytes <= GB * 11 / 10 -> 8 * MB
        else -> 12 * MB
    }

    private const val MB = 1024L * 1024
    private const val GB = 1024 * MB
    /** What Media3 aims at for a stream with video and audio (usenet:// isn't one of its local schemes). */
    private const val MEDIA3_TARGET = (DefaultLoadControl.DEFAULT_VIDEO_BUFFER_SIZE + DefaultLoadControl.DEFAULT_AUDIO_BUFFER_SIZE).toLong()
}
