package io.skjaere.usenet.core

import io.skjaere.compressionutils.EncryptionInfo
import io.skjaere.nntp.NntpDispatchers
import io.skjaere.nzbstreamer.enrichment.ArchiveVolumeNotInNzbException
import io.skjaere.nzbstreamer.metadata.ExtractedMetadata
import io.skjaere.nzbstreamer.metadata.PrepareResult
import io.skjaere.nzbstreamer.stream.StreamableFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import kotlin.coroutines.cancellation.CancellationException

sealed interface PrepareOutcome {
    data class Ready(val nzb: PreparedNzb) : PrepareOutcome
    data class NeedsPassword(val info: EncryptionInfo, val passwordWasTried: Boolean) : PrepareOutcome
    /**
     * [notInNzb]: what it lacks is a file the NZB doesn't hold (an archive volume its PAR2 index
     * names, or its set's names skip), which no provider can supply, not an article a provider not
     * asked may have (library issue NR-18).
     */
    data class MissingArticles(val message: String, val notInNzb: Boolean = false) : PrepareOutcome
    data class Unsupported(val message: String) : PrepareOutcome
    data class Failed(val error: UsenetError) : PrepareOutcome
}

/** The two streamer calls preparation needs, split out so mapping can be unit-tested. */
interface PrepareBackend {
    suspend fun prepare(nzbBytes: ByteArray, password: String?): PrepareResult
    suspend fun streamableFiles(metadata: ExtractedMetadata): List<StreamableFile>
}

/**
 * A prepare that fails as a provider failing does (no provider could serve an article: one's login
 * rejected, out of reach or not answering, while the others lack it) has the engine check its
 * providers again before the next use (#172), which leaves out one whose login is now rejected or
 * that can't be reached.
 */
class EnginePrepareBackend(private val engine: UsenetEngine) : PrepareBackend {
    override suspend fun prepare(nzbBytes: ByteArray, password: String?): PrepareResult =
        engine.withStreamer { it.prepare(nzbBytes, password) }.also { result ->
            val error = (result as? PrepareResult.Failure)?.cause?.toUsenetError()
            if (error is UsenetError.AuthFailed || error is UsenetError.ConnectionFailed || error is UsenetError.Timeout) engine.recheckProviders()
        }

    override suspend fun streamableFiles(metadata: ExtractedMetadata) = engine.withStreamer { it.resolveStreamableFiles(metadata) }
}

class NzbPreparer(
    private val backend: PrepareBackend,
    private val dispatcher: CoroutineDispatcher = NntpDispatchers.IO,
) {
    private val log = LoggerFactory.getLogger(NzbPreparer::class.java)

    /**
     * Safe to call from any thread: the whole call runs on [dispatcher]. Preparing fetches and
     * decodes articles and parses archive headers, work for a background thread, not Android's
     * main one (where nzb-streamer's prepare used to deadlock too, on a runBlocking it no longer
     * has: library issue N7). By default ktor-nntp-client's own view of Dispatchers.IO, where its
     * reads wait for the server in place (see [UsenetEngine]).
     */
    suspend fun prepare(nzbBytes: ByteArray, title: String, password: String? = null): PrepareOutcome =
        withContext(dispatcher) { prepareBlocking(nzbBytes, title, password) }

    private suspend fun prepareBlocking(nzbBytes: ByteArray, title: String, password: String?): PrepareOutcome {
        val result = try {
            backend.prepare(nzbBytes, password)
        } catch (e: CancellationException) {
            throw e
        } catch (e: UsenetException) {
            return PrepareOutcome.Failed(e.error)
        } catch (e: Exception) {
            // prepare() returns a malformed archive or a failed fetch as a result (library issue N5);
            // what still escapes it is an NZB that doesn't parse, or something neither NNTP nor I/O.
            log.warn("prepare threw", e)
            return PrepareOutcome.Failed(e.toUsenetError())
        }
        return when (result) {
            is PrepareResult.Success -> {
                val files = try {
                    backend.streamableFiles(result.metadata)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return PrepareOutcome.Failed(e.toUsenetError())
                }
                val candidates = files.mapIndexed { index, file -> FileCandidate(index, file) }
                if (candidates.isEmpty()) {
                    PrepareOutcome.Unsupported("No streamable files. Compressed archives cannot be streamed.")
                } else {
                    PrepareOutcome.Ready(PreparedNzb(PreparedNzb.idFor(nzbBytes), title, result.metadata, candidates))
                }
            }
            is PrepareResult.Encrypted -> PrepareOutcome.NeedsPassword(result.info, passwordWasTried = password != null)
            is PrepareResult.MissingArticles ->
                PrepareOutcome.MissingArticles(result.message, notInNzb = result.cause.causeChain().any { it is ArchiveVolumeNotInNzbException })
            is PrepareResult.UnsupportedArchive -> PrepareOutcome.Unsupported(result.message)
            is PrepareResult.Failure -> PrepareOutcome.Failed(result.cause.toUsenetError())
        }
    }
}
