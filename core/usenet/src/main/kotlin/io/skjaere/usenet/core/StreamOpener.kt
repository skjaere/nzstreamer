package io.skjaere.usenet.core

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.WriterJob
import io.skjaere.nzbstreamer.NzbStreamer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * A byte range of a file being streamed; cancel [job] to stop the download. A download that fails
 * (a dropped connection, a provider out of reach) closes [channel] with the failure as its cause,
 * once everything in front of it has been written. An article no provider has, the stream fills
 * with zeros and goes on: [gaps] says where, each stretch before its zeros reach [channel].
 */
class OpenedStream(val channel: ByteReadChannel, val job: Job, val gaps: StreamGaps = StreamGaps())

fun interface StreamOpener {
    /** [range] is inclusive and must lie within the file. */
    suspend fun open(nzb: PreparedNzb, fileIndex: Int, range: LongRange): OpenedStream
}

/**
 * nzb-streamer's writer is launched as a child of the coroutine that calls
 * `launchStreamFile`. Calling it from `runBlocking` would make the caller wait for the
 * whole stream, so it is launched from the engine's session scope and the [WriterJob]
 * is handed back through a deferred. Cancelling the returned job cancels the writer.
 * A caller cancelled meanwhile gets its cancellation back, and the session is cancelled
 * with it (#123). The session counts as a use of the engine's streamer until the stream ends.
 */
class NzbStreamOpener(private val engine: UsenetEngine) : StreamOpener {
    override suspend fun open(nzb: PreparedNzb, fileIndex: Int, range: LongRange): OpenedStream {
        val candidate = nzb.candidates.getOrNull(fileIndex)
            ?: throw UsenetException(UsenetError.Unknown("File $fileIndex not in ${nzb.title}"))
        return engine.withStreamer { streamer -> open(streamer, candidate, nzb, range) }
    }

    private suspend fun open(streamer: NzbStreamer, candidate: FileCandidate, nzb: PreparedNzb, range: LongRange): OpenedStream {
        val handoff = CompletableDeferred<WriterJob>()
        // Where the stream fills an article no provider has with zeros, as offsets in the file: recorded on its download
        // threads, each before its zeros are written.
        val gaps = StreamGaps()
        val session = engine.sessionScope.launch {
            try {
                handoff.complete(streamer.launchStreamFile(nzb.metadata, candidate.file, range, candidate.name, onGap = { gaps.add(it.fileRange) }))
            } catch (e: Throwable) {
                handoff.completeExceptionally(e)
                throw e
            }
        }
        // A session that ends before its body runs (the session scope is cancelled: the engine was
        // closed) hands nothing over, and the wait below would never end (#163). Once the writer is
        // handed over, this does nothing.
        session.invokeOnCompletion { cause ->
            handoff.completeExceptionally(UsenetException(UsenetError.Unknown("The stream session ended before the stream started"), cause))
        }
        val writer = try {
            handoff.await()
        } catch (e: Throwable) {
            session.cancel()
            // The caller was cancelled: so is opening. Anything else is a failure to open.
            currentCoroutineContext().ensureActive()
            throw e as? UsenetException ?: UsenetException(e.toUsenetError(), e)
        }
        return OpenedStream(writer.channel, session, gaps)
    }
}
