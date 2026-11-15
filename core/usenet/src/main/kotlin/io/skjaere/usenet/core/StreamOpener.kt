package io.skjaere.usenet.core

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.WriterJob
import io.skjaere.nzbstreamer.NzbStreamer
import io.skjaere.nzbstreamer.config.MissingArticlePolicy
import io.skjaere.nzbstreamer.stream.CacheUse
import io.skjaere.nzbstreamer.stream.Gap
import io.skjaere.nzbstreamer.stream.StreamOptions
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
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
 * whole stream, so it is launched from [scope], the engine's session scope for a play's and a
 * cast's streams, and the [WriterJob] is handed back through a deferred. Cancelling the returned
 * job cancels the writer. A caller cancelled meanwhile gets its cancellation back, and the session
 * is cancelled with it (#123). A session in the engine's session scope counts as a use of its
 * streamer until the stream ends; one in its download scope doesn't ([download]).
 *
 * [options] are how each stream fetches where it differs from a play's; null, as a play's and a
 * cast's: the streamer's own. [streamName] is what nzb-streamer's log lines call a stream: by default the
 * file's name, as a play's have always said it.
 */
class NzbStreamOpener(
    private val engine: UsenetEngine,
    private val scope: CoroutineScope = engine.sessionScope,
    private val options: StreamOptions? = null,
    private val streamName: (FileCandidate) -> String = { it.name },
) : StreamOpener {
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
        val session = scope.launch {
            try {
                val onGap = { gap: Gap -> gaps.add(gap.fileRange) }
                handoff.complete(
                    if (options == null) {
                        streamer.launchStreamFile(nzb.metadata, candidate.file, range, streamName(candidate), onGap = onGap)
                    } else {
                        streamer.launchStreamFile(nzb.metadata, candidate.file, range, streamName(candidate), options, onGap)
                    },
                )
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

    companion object {
        /**
         * How a download's streams fetch (L343): below every play's step, its stream, prepare and checks
         * ([StreamOptions.BACKGROUND_PRIORITY]), so a play started during a download never waits behind its fetches, and
         * a wait for a connection has no limit of its own and never counts against a provider; the video cache read but
         * never written, so a download of many gigabytes doesn't push out what plays keep there; and an article no provider
         * has filled with zeros and said ([OpenedStream.gaps]), the download recording where. A play's concurrency and
         * read-ahead, the streamer's own (L344).
         */
        val DOWNLOAD_OPTIONS = StreamOptions(
            priority = StreamOptions.BACKGROUND_PRIORITY,
            cache = CacheUse.READ_ONLY,
            missingArticles = MissingArticlePolicy.ZERO_FILL,
        )

        /**
         * A download's opener: its streams in the engine's download scope ([UsenetEngine.downloadScope]), fetching as
         * [DOWNLOAD_OPTIONS] say, and named in nzb-streamer's log lines by the file's place alone ("download file 0"). Its
         * name is the release's as posted, and a download opens a stream at each reopen and each gap asked for again, for
         * hours: the app's own lines never name a release, so neither do these.
         */
        fun download(engine: UsenetEngine) = NzbStreamOpener(engine, engine.downloadScope, DOWNLOAD_OPTIONS) { "download file ${it.index}" }
    }
}
