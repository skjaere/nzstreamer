package io.skjaere.usenet.app.serve

import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.close
import io.ktor.utils.io.writeFully
import io.skjaere.nzbstreamer.metadata.ExtractedMetadata
import io.skjaere.nzbstreamer.metadata.NzbMetadataResponse
import io.skjaere.nzbstreamer.nzb.NzbDocument
import io.skjaere.nzbstreamer.stream.StreamableFile
import io.skjaere.usenet.core.FileCandidate
import io.skjaere.usenet.core.OpenedStream
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.core.StreamGaps
import io.skjaere.usenet.core.StreamOpener
import io.skjaere.usenet.serve.ByteSource
import io.skjaere.usenet.serve.FaultySource
import io.skjaere.usenet.serve.Json
import io.skjaere.usenet.serve.SpikeFaults
import io.skjaere.usenet.serve.SpikeStack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.io.File
import java.io.PrintWriter
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.test.fail

/**
 * Item 6(b) once more, through the app's adapter (W21, L69): the media folder's synthetic files (ffmpeg's testsrc2 and
 * sine, never release content) served by core:serve's spike stack ([SpikeStack]: the relay that logs every request and
 * cuts or answers a 416 when the control says, the direct server, the control) with each file read as the app reads a
 * release's, through [ServedStream] over a stand-in for the Usenet stream ([SpikeOpener]), so the faults the control arms
 * (latency per part, stalls, zeros, failures) come through the adapter's queue as the engine's would. The lead runs it once
 * after ServeSpikeTest, each casting session only once the TV is free, with the same harness and options:
 *
 *     ./gradlew :app:testDebugUnitTest --tests 'io.skjaere.usenet.app.serve.ServedStreamSpikeTest' -PserveSpike=<this-pc-lan-ip> \
 *         -PserveSpikeMedia=<folder> [-PserveSpikePeer=<tv-lan-ip>] [-PserveSpikePage=<the harness's sender.html>] \
 *         [-PserveSpikeMinutes=120] [-PserveSpikeLog=<file>]
 *
 * Without serveSpike it is skipped, so no normal run serves anything on the home network; [SpikeStreamTest] checks the
 * same stack on 127.0.0.1.
 */
@EnabledIfSystemProperty(named = "serveSpike", matches = ".+")
class ServedStreamSpikeTest {
    @Test
    fun `serves the media folder through the app's adapter to the Cast device until the harness stops it`() {
        val address = InetAddress.getByName(System.getProperty("serveSpike")) as? Inet4Address ?: fail("serveSpike isn't an IPv4 address")
        val media = File(System.getProperty("serveSpikeMedia") ?: fail("-PserveSpikeMedia=<a folder of synthetic files> is needed"))
        if (!media.isDirectory) fail("serveSpikeMedia isn't a folder")
        val peer = System.getProperty("serveSpikePeer")?.let { InetAddress.getByName(it) }
        val page = System.getProperty("serveSpikePage")?.let(::File)
        val minutes = System.getProperty("serveSpikeMinutes")?.toLong() ?: 120
        val log = System.getProperty("serveSpikeLog")?.let { PrintWriter(File(it).bufferedWriter(), true) }
        val out: (String) -> Unit = { line ->
            synchronized(this) {
                println(line)
                log?.println(line)
            }
        }
        val scope = CoroutineScope(SupervisorJob())
        try {
            SpikeStack(address, media, expected = peer, page = page, out = out, source = { file, name, faults -> servedStream(file, name, faults, scope) }).use { stack ->
                val stopped = stack.awaitStop(minutes)
                out(Json.write(mapOf("ev" to "server_stop", "stopped_by" to if (stopped) "ctl" else "time", "control" to stack.controlAddress.port, "via" to "ServedStream")))
            }
        } finally {
            scope.cancel()
            log?.close()
        }
    }
}

/**
 * [file], a file of the spike's media folder, as the app serves a release's: [ServedStream] over [SpikeOpener], under the
 * faults armed for [name]. Its NZB is made up around it (one file, no articles): the stand-in reads the file itself.
 * [endsShort] is the stand-in's.
 */
internal fun servedStream(file: File, name: String, faults: SpikeFaults, scope: CoroutineScope, endsShort: Boolean = false): ByteSource {
    val nzb = PreparedNzb(
        "spike-$name", name,
        ExtractedMetadata.Raw(NzbMetadataResponse(volumes = emptyList(), obfuscated = false, entries = emptyList()), NzbDocument(emptyList())),
        listOf(FileCandidate(0, StreamableFile(name, file.length(), 0, 0, 0, 0))),
    )
    return ServedStream(nzb, nzb.candidates.single(), SpikeOpener(FaultySource(file, name, faults), scope, endsShort), scope)
}

/**
 * A stand-in for the engine's stream of a release's file: [source]'s bytes (the spike's file under its armed faults) in the
 * range asked, written on a coroutine of [scope]'s into a channel as the engine's download writes one. A stretch of zeros
 * goes into the stream's gaps before its zeros; a stall writes nothing for its time. Cancelling the job stops it.
 *
 * A failure closes the channel with its cause once the bytes before it are written, as the engine's does: the channel then
 * drops what the reader hasn't taken, so the read fails short of the failure's byte when the reader is behind. With
 * [endsShort] the channel ends there instead, as a stream that ends short does: every byte before it is read first.
 */
internal class SpikeOpener(
    private val source: ByteSource,
    private val scope: CoroutineScope,
    private val endsShort: Boolean = false,
) : StreamOpener {
    override suspend fun open(nzb: PreparedNzb, fileIndex: Int, range: LongRange): OpenedStream {
        val gaps = StreamGaps()
        val upstream = source.open(range.first, gaps::add)
        val channel = ByteChannel()
        val job = scope.launch(Dispatchers.IO) {
            try {
                val buffer = ByteArray(64 * 1024)
                var position = range.first
                while (position <= range.last) {
                    val want = minOf(buffer.size.toLong(), range.last - position + 1).toInt()
                    // A wait each read, as the server's own, so a cancel is seen within it.
                    val n = runInterruptible { upstream.read(buffer, 0, want, 250) }
                    if (n < 0) break
                    if (n > 0) {
                        channel.writeFully(buffer, 0, n)
                        channel.flush()
                        position += n
                    }
                }
                channel.flushAndClose()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (endsShort) channel.flushAndClose() else channel.close(e)
            } finally {
                upstream.close()
            }
        }
        return OpenedStream(channel, job, gaps)
    }
}
