package io.skjaere.usenet.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.SeekMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What a player's extractors tell it of the missing stretches of its file ([GapSkippingExtractor]): the file's index, to
 * place a stretch in the film ([timeMsAt]), and each jump past one as it is made ([onJump], called on the loader's thread,
 * so it hands the jump on). One per player.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlayGaps(private val onJump: (Jump) -> Unit = {}) {
    /** A jump past [bytes], from [fromMs] in the film (the last sample in front of it; null when none was) to [toMs] (null when the container can't say: MPEG-TS). */
    data class Jump(val bytes: LongRange, val fromMs: Long?, val toMs: Long?)

    /** The file's index, once the extractor has it: read here on the main thread, its tables built and kept as they are. */
    @Volatile
    private var map: SeekMap? = null

    internal fun indexed(map: SeekMap) {
        this.map = map
    }

    internal fun jumped(jump: Jump) = onJump(jump)

    /** Where in the film byte [position] of the file is: the point of its index at or in front of it; null until the index is read, or with one that can't say. */
    fun timeMsAt(position: Long): Long? = map?.let { SeekMaps.timeAt(it, position) }?.let { it / 1000 }
}

/**
 * The missing stretches the player's streams find, as they find them (the read-ahead's too), by file, for the player of
 * that file to count: the reader reports each ([UsenetStreamReader]). One for the app. Safe from any thread; a listener is
 * called on the thread that found the stretch, so it hands it on.
 */
class GapReports {
    /** A stretch of [fileName] (at [fileIndex] of the NZB [nzbId]) that no provider has, as offsets in the file. */
    data class Found(val nzbId: String, val fileIndex: Int, val fileName: String?, val range: LongRange)

    private class Listening(val nzbId: String, val listener: (Found) -> Unit)

    private val listening = CopyOnWriteArrayList<Listening>()

    fun found(gap: Found) = listening.forEach { if (it.nzbId == gap.nzbId) it.listener(gap) }

    /** Calls [listener] for each stretch found in a file of the NZB [nzbId], until closed. */
    fun listen(nzbId: String, listener: (Found) -> Unit): AutoCloseable {
        val listening = Listening(nzbId, listener)
        this.listening += listening
        return AutoCloseable { this.listening -= listening }
    }
}
