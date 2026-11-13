package io.skjaere.usenet.serve

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.net.URLEncoder
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The workstation's probes of the server (U2, item 5): FFmpeg, whose Matroska demuxer a Cast receiver's Chromium runs, and
 * headless Google Chrome, whose media loader it runs, against files served from 127.0.0.1. Run it with a folder holding
 * `probe.mkv` and `probe.mp4`, synthetic files ffmpeg makes (never release content):
 *
 *     ffmpeg -f lavfi -i testsrc2=size=1280x720:rate=30 -f lavfi -i sine=frequency=440:sample_rate=48000 -t 90 \
 *       -c:v libx264 -preset veryfast -b:v 3M -maxrate 3M -bufsize 6M -g 60 -pix_fmt yuv420p -c:a aac -b:a 128k \
 *       -metadata title="Harbor Lights" -movflags +faststart <folder>/probe.mp4
 *     ffmpeg -i <folder>/probe.mp4 -c copy -metadata title="Harbor Lights" <folder>/probe.mkv
 *     ./gradlew :core:serve:test --tests 'io.skjaere.usenet.serve.ServeProbeTest' -PserveProbe=<folder>
 *
 * It prints what FFmpeg and Chrome did and every answer the server gave, by file name, never a URL. It fails when a served
 * file probes or decodes differently from the same file on disk, or when Chrome doesn't play one through a seek and a 60 s
 * stall; what FFmpeg makes of zeros is reported, not judged. Chrome runs headless with a profile of its own, its media
 * router (Cast) off, and loads nothing but 127.0.0.1. Without serveProbe it is skipped.
 */
@EnabledIfSystemProperty(named = "serveProbe", matches = ".+")
class ServeProbeTest : ServeTestBase() {
    private val folder = File(System.getProperty("serveProbe", "."))
    private val mkv = File(folder, "probe.mkv")
    private val mp4 = File(folder, "probe.mp4")
    private val started = System.nanoTime()

    private fun serve(server: ServeServer, file: File, source: FileSource = FileSource(file)): Served {
        val (type, extension) = if (file.extension == "mkv") "video/x-matroska" to "mkv" else "video/mp4" to "mp4"
        return server.serve(ServedFile(file.length(), type, extension, source), loopback)
    }

    private fun at(nanos: Long) = "%7.2f s".format((nanos - started) / 1e9)

    /** A tool's run: its exit, its output and its error lines, the served file's URL never among them. */
    private class Run(val exit: Int, val out: String, val errors: List<String>)

    private fun run(vararg command: String, url: String? = null, seconds: Long = 300): Run {
        val process = ProcessBuilder(*command).start()
        val out = StringBuilder()
        val errors = mutableListOf<String>()
        val reader = Thread { process.inputStream.bufferedReader().forEachLine { out.appendLine(it) } }.apply { start() }
        val errorReader = Thread { process.errorStream.bufferedReader().forEachLine { errors += it } }.apply { start() }
        assertTrue(process.waitFor(seconds, TimeUnit.SECONDS), "${command.first()} ran over $seconds s")
        reader.join(5000)
        errorReader.join(5000)
        val redacted = errors.map { line -> url?.let { line.replace(it, "<url>").replace(it.substringAfter("://").substringAfter('/'), "<path>") } ?: line }
        return Run(process.exitValue(), out.toString(), redacted)
    }

    /** What ffprobe says of a file's format and streams, its name left out. */
    private fun probe(input: String) =
        run("ffprobe", "-v", "error", "-show_entries", "format=duration,size,format_name:stream=index,codec_type,codec_name,width,height,channels,sample_rate,duration", "-of", "compact", input, url = input).out

    /** The MD5 of every frame FFmpeg decodes from [input], video and sound. */
    private fun decoded(input: String): String {
        val run = run("ffmpeg", "-nostdin", "-hide_banner", "-v", "error", "-i", input, "-map", "0", "-f", "md5", "-", url = input)
        assertEquals(0, run.exit, "ffmpeg failed on ${if (input.startsWith("http")) "the served file" else input}: ${run.errors.take(3)}")
        return run.out.trim()
    }

    /** FFmpeg decoding [input]'s video to nothing: the frames it made, how far it got, and its error lines. */
    private fun decodeVideo(input: String): Triple<Int, Double, Run> {
        val run = run("ffmpeg", "-nostdin", "-hide_banner", "-v", "error", "-progress", "pipe:1", "-i", input, "-map", "0:v", "-f", "null", "-", url = input)
        val values = run.out.lines().filter { '=' in it }.map { it.substringBefore('=') to it.substringAfter('=') }
        val frames = values.lastOrNull { it.first == "frame" }?.second?.toIntOrNull() ?: 0
        val seconds = (values.lastOrNull { it.first == "out_time_us" }?.second?.toLongOrNull() ?: 0) / 1e6
        return Triple(frames, seconds, run)
    }

    /** Prints the server's answers so far, with when each came, its files by [names]. */
    private fun printAnswers(names: Map<Int, String>) {
        Thread.sleep(200)
        while (true) {
            val (at, event) = events.poll() ?: break
            val line = when (event) {
                is ServeEvent.Answered -> "answered ${event.status} to a ${event.method} of ${names[event.file] ?: "no file"}"
                is ServeEvent.Ended -> "${names[event.file]}: from byte ${event.from}, ${event.sent} bytes, ${event.why}"
                is ServeEvent.Dropped -> "dropped: ${event.why}"
                is ServeEvent.Pinned -> "${names[event.file]} pinned"
                ServeEvent.ListenerLost -> "stopped listening"
            }
            println("  ${at(at)}  server: $line")
        }
    }

    @Test
    fun `ffprobe reads the served files as it reads them on disk, and FFmpeg decodes them to the same frames`() {
        val server = server()
        for (file in listOf(mp4, mkv)) {
            assertTrue(file.isFile, "no ${file.name} in the probe folder")
            val served = serve(server, file)
            println("${file.name}, ${file.length()} bytes")
            val local = probe(file.path)
            val remote = probe(served.url)
            println(remote.trim().prependIndent("  ffprobe: "))
            assertEquals(local, remote, "ffprobe read the served ${file.name} differently")
            val localFrames = decoded(file.path)
            val remoteFrames = decoded(served.url)
            println("  decoded: $remoteFrames (the file on disk: $localFrames)")
            assertEquals(localFrames, remoteFrames, "the served ${file.name} decodes differently")
            printAnswers(mapOf(served.id to file.name))
            val answered = served.counters()
            println("  ${answered.requests} requests, ${answered.bytesSent} bytes sent")
        }
    }

    @Test
    fun `FFmpeg through a zero stretch inside a cluster and one over a cluster header`() {
        assertTrue(mkv.isFile, "no probe.mkv in the probe folder")
        val layout = MatroskaLayout(mkv)
        val stretch = 768_000L
        val (clean, cleanSeconds, cleanRun) = decodeVideo(mkv.path)
        println("probe.mkv: ${layout.clusters.size} clusters, Cues at ${layout.cues}; on disk $clean frames, ${"%.2f".format(cleanSeconds)} s, ${cleanRun.errors.size} error lines")
        // Inside: a cluster past 40 % of the file whose data holds the stretch with room on both sides.
        val inside = assertNotNull(
            layout.clusters.firstOrNull { it.first > mkv.length() * 4 / 10 && it.last - it.first + 1 >= stretch + 16_384 },
            "no cluster big enough for the stretch",
        )
        // Over a header: from 4 KiB before a later cluster's ID, through its header and most of its data.
        val header = layout.clusters.first { it.first > inside.last + 4 * stretch }
        val server = server()
        for ((name, zeros) in listOf(
            "inside the cluster at byte ${inside.first}" to (inside.first + 8192 until inside.first + 8192 + stretch),
            "over the header of the cluster at byte ${header.first}" to (header.first - 4096 until header.first - 4096 + stretch),
        )) {
            val source = FileSource(mkv).apply { this.zeros = listOf(zeros) }
            val served = serve(server, mkv, source)
            val (frames, seconds, run) = decodeVideo(served.url)
            println("zeros $name (bytes ${zeros.first}-${zeros.last}):")
            println("  ffmpeg exit ${run.exit}: $frames of $clean frames (${clean - frames} lost), to ${"%.2f".format(seconds)} s of ${"%.2f".format(cleanSeconds)} s, ${run.errors.size} error lines")
            run.errors.take(5).forEach { println("    $it") }
            printAnswers(mapOf(served.id to "probe.mkv"))
            val counters = served.counters()
            assertEquals(1, counters.gaps, "the stretch wasn't counted once")
            assertEquals(0, counters.upstreamFailures)
        }
    }

    @Test
    fun `headless Chrome plays the served MP4 and MKV through a seek and a 60 s stall`(@TempDir profile: Path) {
        val reports = LinkedBlockingQueue<Pair<Long, Map<String, String>>>()
        val page = HttpServer.create(InetSocketAddress(loopback, 0), 0)
        page.createContext("/probe.html") { exchange ->
            val body = PAGE.toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            exchange.responseHeaders.add("Cache-Control", "no-store")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        page.createContext("/report") { exchange ->
            val text = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            reports.put(System.nanoTime() to text.split(' ').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') })
            exchange.sendResponseHeaders(204, -1)
            exchange.close()
        }
        page.start()
        val server = server()
        try {
            for (file in listOf(mp4, mkv)) {
                assertTrue(file.isFile, "no ${file.name} in the probe folder")
                val source = FileSource(file)
                // At 55 % of the bytes: past the seek's target (30 s of 90), reached as Chrome reads ahead from there.
                val stall = source.stallAt(file.length() * 55 / 100, 60_000)
                val served = serve(server, file, source)
                reports.clear()
                val query = "src=${URLEncoder.encode(served.url, Charsets.UTF_8)}&seekAt=3&seekTo=30"
                val chrome = ProcessBuilder(
                    "google-chrome", "--headless", "--no-first-run", "--no-default-browser-check", "--user-data-dir=${profile.resolve(file.name)}",
                    "--disable-gpu", "--mute-audio", "--autoplay-policy=no-user-gesture-required", "--disable-background-networking",
                    "--disable-component-update", "--disable-sync", "--disable-extensions", "--disable-default-apps", "--no-proxy-server",
                    "--disable-features=MediaRouter,DialMediaRouteProvider,CastMediaRouteProvider,GlobalMediaControls,Translate,OptimizationHints",
                    "http://127.0.0.1:${page.address.port}/probe.html?$query",
                ).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
                val seen = mutableListOf<Pair<Long, Map<String, String>>>()
                // Where the video was when the source went on: it is to play 3 s past it.
                var resumedFrom: Double? = null
                try {
                    val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(200)
                    while (System.nanoTime() < until) {
                        val report = reports.poll(500, TimeUnit.MILLISECONDS) ?: continue
                        seen += report
                        val (_, values) = report
                        val event = values["ev"]
                        if (event == "error" || event == "ended") break
                        val time = values["ct"]?.toDoubleOrNull() ?: continue
                        if (stall.endedAt == null) continue
                        val from = resumedFrom ?: time.also { resumedFrom = it }
                        if (time >= from + 3) break
                    }
                } finally {
                    chrome.descendants().forEach { it.destroy() }
                    chrome.destroy()
                    if (!chrome.waitFor(5, TimeUnit.SECONDS)) chrome.destroyForcibly()
                }
                println("${file.name} in headless Chrome, a seek at 3 s to 30 s, a 60 s stall at byte ${stall.at}:")
                stall.startedAt?.let { println("  ${at(it)}  the source stalls") }
                stall.endedAt?.let { println("  ${at(it)}  the source goes on") }
                var ticks = 0
                for ((time, values) in seen) {
                    // A tick each 2 s is enough to follow the time.
                    if (values["ev"] == "tick" && ticks++ % 4 != 0) continue
                    println("  ${at(time)}  chrome: ${values.entries.joinToString(" ") { "${it.key}=${it.value}" }}")
                }
                printAnswers(mapOf(served.id to file.name))
                val events = seen.map { it.second }
                assertTrue(events.none { it["ev"] == "error" }, "${file.name}: Chrome's media element failed: ${events.filter { it["ev"] == "error" }}")
                assertTrue(events.any { it["ev"] == "loadedmetadata" }, "${file.name}: no loadedmetadata")
                assertTrue(events.any { it["ev"] == "seeked" && (it["ct"]?.toDouble() ?: 0.0) >= 29.5 }, "${file.name}: the seek didn't land")
                assertNotNull(stall.endedAt, "${file.name}: the stall never began or never ended")
                val from = assertNotNull(resumedFrom, "${file.name}: no report after the stall")
                assertTrue(events.any { (it["ct"]?.toDouble() ?: 0.0) >= from + 1 }, "${file.name}: Chrome didn't play on after the stall")
            }
        } finally {
            page.stop(0)
        }
    }

    private companion object {
        /** Plays `src` muted, seeks once at `seekAt` to `seekTo`, and reports its media events and a tick each 0.5 s. */
        val PAGE = """
            <!doctype html><meta charset="utf-8"><title>Serve probe</title>
            <video id="v" muted playsinline preload="auto"></video>
            <script>
            const q = new URLSearchParams(location.search), v = document.getElementById('v'), t0 = performance.now();
            let seeked = false;
            function report(ev) {
              const b = [];
              for (let i = 0; i < v.buffered.length; i++) b.push(v.buffered.start(i).toFixed(1) + '-' + v.buffered.end(i).toFixed(1));
              const line = ['ev=' + ev, 'ct=' + v.currentTime.toFixed(2), 'rs=' + v.readyState, 'ns=' + v.networkState,
                'paused=' + v.paused, 'buffered=' + (b.join(',') || 'none'), 'error=' + (v.error ? v.error.code : 'none')].join(' ');
              fetch('/report', {method: 'POST', body: line, keepalive: true}).catch(() => {});
            }
            for (const e of ['loadedmetadata', 'loadeddata', 'canplay', 'playing', 'waiting', 'stalled', 'seeking', 'seeked', 'pause', 'ended', 'error', 'abort', 'emptied'])
              v.addEventListener(e, () => report(e));
            v.src = q.get('src');
            v.play().catch(() => report('playRefused'));
            setInterval(() => {
              report('tick');
              if (!seeked && v.currentTime >= +q.get('seekAt')) { seeked = true; v.currentTime = +q.get('seekTo'); }
            }, 500);
            </script>
        """.trimIndent()
    }
}
