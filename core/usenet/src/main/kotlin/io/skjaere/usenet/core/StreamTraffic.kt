package io.skjaere.usenet.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The bytes streams had handed their readers by a moment: [totalBytes] by every stream so far, ended or not;
 * [endedStreams] how many have ended; [streams] each open one's bytes by its id, oldest first.
 */
class TrafficSnapshot(val totalBytes: Long, val endedStreams: Long, val streams: Map<Long, Long>)

/**
 * The bytes each stream hands its reader (the player), for the stream-cost line ([StreamCost]): a [Meter] for each stream
 * from its start to its close. A reader counts on its own thread, a read at a time; the line reads the counts on another.
 * What an ended stream moved stays in the total, so a stream that ends between two samples is counted whole. Whether any
 * is open is [streaming], which a debrid download waits on (L471).
 */
class StreamTraffic {
    /** One stream's count, from its reader's thread only. */
    inner class Meter internal constructor(val id: Long) {
        /** Written by the one reader, read by the line: no lock, the one writer's store seen by the other thread. */
        @Volatile
        var bytes = 0L
            private set

        fun add(n: Int) {
            bytes += n
        }

        /** The stream ended: its bytes stay in the total. Again does nothing. */
        fun close() = ended(this)
    }

    private val open = LinkedHashMap<Long, Meter>()
    private var endedBytes = 0L
    private var endedStreams = 0L
    private var nextId = 1L
    private val _streaming = MutableStateFlow(false)

    /**
     * Whether something streams here: true while any meter is open, a play's stream or the cast relay's (L471), from the
     * first [open] until the last one ends. Set with the meters under this object's lock, so streams opening and ending
     * on their own threads at once leave it as the meters are. A downloaded file played here or cast opens none.
     */
    val streaming: StateFlow<Boolean> = _streaming.asStateFlow()

    @Synchronized
    fun open(): Meter = Meter(nextId++).also {
        open[it.id] = it
        _streaming.value = true
    }

    @Synchronized
    private fun ended(meter: Meter) {
        if (open.remove(meter.id) == null) return
        endedBytes += meter.bytes
        endedStreams++
        _streaming.value = open.isNotEmpty()
    }

    @Synchronized
    fun snapshot(): TrafficSnapshot {
        val streams = LinkedHashMap<Long, Long>(open.size)
        var total = endedBytes
        for (meter in open.values) {
            val bytes = meter.bytes
            streams[meter.id] = bytes
            total += bytes
        }
        return TrafficSnapshot(total, endedStreams, streams)
    }
}
