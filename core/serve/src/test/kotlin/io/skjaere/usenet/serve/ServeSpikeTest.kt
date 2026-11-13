package io.skjaere.usenet.serve

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import java.io.File
import java.io.PrintWriter
import java.net.Inet4Address
import java.net.InetAddress
import kotlin.test.fail

/**
 * Item 6(b), on the final tree (W21, L69): core:serve's server on the workstation's home-network address, serving a folder
 * of synthetic files (ffmpeg's testsrc2 and sine, never release content) to the Shield's Cast receiver, which W21's
 * external harness (not in the repo) tells to load them; this is only the serving side.
 * The lead runs it, each casting session only once the TV is free:
 *
 *     ./gradlew :core:serve:test --tests 'io.skjaere.usenet.serve.ServeSpikeTest' -PserveSpike=<this-pc-lan-ip> \
 *         -PserveSpikeMedia=<folder> [-PserveSpikePeer=<tv-lan-ip>] [-PserveSpikePage=<the harness's sender.html>] \
 *         [-PserveSpikeMinutes=120] [-PserveSpikeLog=<file>]
 *
 * [SpikeStack] says what it serves and how its control takes the harness's calls: the relay at :8600 logs every request's
 * method, Range, status, bytes, peer, Origin and User-Agent; the direct server at :8602 times the server's own closes;
 * faults (latency, stalls, cuts, 416s, zeros, failures) are armed on 127.0.0.1:8601. It runs until the harness posts
 * `/ctl/stop`, or for serveSpikeMinutes. Without serveSpike it is skipped, so no normal run serves anything on the home
 * network; [SpikeWrapperTest] checks the same stack on 127.0.0.1.
 */
@EnabledIfSystemProperty(named = "serveSpike", matches = ".+")
class ServeSpikeTest {
    @Test
    fun `serves the media folder to the Cast device until the harness stops it`() {
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
        try {
            SpikeStack(address, media, expected = peer, page = page, out = out).use { stack ->
                val stopped = stack.awaitStop(minutes)
                out(Json.write(mapOf("ev" to "server_stop", "stopped_by" to if (stopped) "ctl" else "time", "control" to stack.controlAddress.port)))
            }
        } finally {
            log?.close()
        }
    }
}
