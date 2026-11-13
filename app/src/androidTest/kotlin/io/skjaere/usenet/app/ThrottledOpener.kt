package io.skjaere.usenet.app

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.close
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import io.skjaere.usenet.core.OpenedStream
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.core.StreamOpener
import io.skjaere.usenet.player.UsenetDataSource
import io.skjaere.usenet.player.UsenetStreamReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A slow stream on the device: what [real] opens, handed on no faster than [bytesPerSecond] (null: as fast as it
 * comes), as a slow line or provider would. It sits under the player's reader, so the app counts what the player gets
 * ([io.skjaere.usenet.core.StreamTraffic]) as it would; what the stream says of its gaps, and how it failed, carry over.
 * Each stream the player opens (a seek opens another) is held to the rate on its own.
 */
class ThrottledOpener(private val real: StreamOpener) : StreamOpener {
    @Volatile
    var bytesPerSecond: Long? = null

    override suspend fun open(nzb: PreparedNzb, fileIndex: Int, range: LongRange): OpenedStream {
        val opened = real.open(nzb, fileIndex, range)
        val out = ByteChannel()
        val job = CoroutineScope(Dispatchers.IO).launch {
            val chunk = ByteArray(CHUNK)
            try {
                while (true) {
                    val rate = bytesPerSecond
                    // About a twentieth of a second's worth at a time, so the bytes come steadily.
                    val n = opened.channel.readAvailable(chunk, 0, rate?.let { (it / 20).coerceIn(1, CHUNK.toLong()).toInt() } ?: CHUNK)
                    if (n == -1) break
                    out.writeFully(chunk, 0, n)
                    out.flush()
                    if (rate != null) delay(n * 1_000L / rate)
                }
                opened.channel.closedCause?.let { out.close(it) } ?: out.flushAndClose()
            } catch (e: CancellationException) {
                out.cancel(e)
                throw e
            } catch (e: Exception) {
                out.close(e)
            }
        }
        // The reader closing this stream stops the real one too.
        job.invokeOnCompletion { opened.job.cancel() }
        return OpenedStream(out, job, opened.gaps)
    }

    private companion object {
        const val CHUNK = 16 * 1024
    }
}

/** The app's own data source, its streams opened through [opener]: what the player's [PlayerActivity.dataSources] seam takes. */
fun dataSourcesThrough(opener: StreamOpener): (AppContainer) -> UsenetDataSource.Factory = { c ->
    UsenetDataSource.Factory { UsenetStreamReader(c.store, c.preparer, opener, c.streamTraffic, c.gapReports) }
}
