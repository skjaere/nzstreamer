package io.skjaere.usenet.app

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Debug
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.micrometer.core.instrument.Metrics
import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.skjaere.usenet.core.ConnectionMeter
import io.skjaere.usenet.core.EngineSettings
import io.skjaere.usenet.core.EngineState
import io.skjaere.usenet.core.FileCandidate
import io.skjaere.usenet.core.PrepareOutcome
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.core.ProviderConfig
import io.skjaere.usenet.core.StreamCost
import io.skjaere.usenet.core.StreamCostSample
import io.skjaere.usenet.core.StreamCostSampler
import io.skjaere.usenet.core.ThreadCpuReader
import io.skjaere.usenet.core.ThreadKind
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.logging.AndroidLogConfig
import io.skjaere.usenet.player.androidKeptFiles
import io.skjaere.usenet.player.artGcStats
import io.skjaere.usenet.player.clockTicksPerSecond
import io.skjaere.usenet.player.tv.DeviceMemory
import io.skjaere.usenet.player.tv.PlaybackBudgets
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Provider
import java.security.Security
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.ManagerFactoryParameters
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactorySpi
import javax.net.ssl.X509TrustManager

/**
 * The stream bench: what streaming a high-bitrate release costs the app, for before and after numbers on the emulator
 * (the Shield's figures come from the stream-cost line). Not a regression test: it asserts nothing of the figures, and
 * the normal suites leave it out. tools/stream-bench says how it is run: a synthetic 90 s, 78 Mbps MKV in 3.84 MB
 * segments, served from disk by tools/stream-bench/nntp_disk_server.py on the emulator's host, plain or over TLS, its
 * NZB packaged as the asset stream-bench.nzb. Skipped without them.
 *
 * Each test streams the release through the app's own stream path (the engine, UsenetStreamReader, UsenetDataSource),
 * with one provider of 8 connections, each stream's read-ahead and the player's buffer sized for the Shield's 512 MB
 * heap, as a memory profile had them; the video cache is cleared first, so every segment comes from the
 * server.
 * - [stream]: the data source alone, read in 64 KB reads as fast as it goes, on a thread named as Media3's loader is;
 *   a window of [mb] MB after [warmupMb] MB.
 * - [play]: PlayerActivity plays it; a window of [seconds] s after [warmupSeconds] s of play.
 * A window's figures come from the stream-cost line's sampler ([StreamCost]): the CPU per MB by thread group, what was
 * allocated per MB, ART's GCs, the rate. They go to logcat under [TAG] as one "result {json}" line, which
 * tools/stream-bench/summarize.py reads, and the stream-cost line itself. Detailed logging is off for the run, as a
 * measuring run on the Shield has it. A window that doesn't measure steady streaming fails the test, its result logged
 * "valid": false with why: a play that rebuffered, stopped, or streamed under nine tenths of the release's rate (its
 * buffer draining), whose CPU goes on over fewer MB; a stream that reached the release's end, its last MB read with no
 * fetching left to do; a run that used an article whose CRC didn't match (nzb-streamer uses a damaged copy rather than
 * none, logging only a warning), as its bytes went wrong somewhere on the way.
 *
 * Instrumentation arguments (-Pandroid.testInstrumentationRunnerArguments.streambench_x=): tls (false), port (11119
 * plain, 11563 TLS), cert_sha256 (the server certificate's SHA-256, which TLS trusts: nntp_disk_server.py prints it),
 * warmup_mb (100), mb (700), warmup_s (15), seconds (50), ballast_mb (64: the emulator's 576 MB heap less the Shield's
 * 512, held for the run), heap_mb (512).
 */
@RunWith(AndroidJUnit4::class)
@androidx.annotation.OptIn(UnstableApi::class)
class StreamBenchDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val args = InstrumentationRegistry.getArguments()
    private fun arg(name: String): String? = args.getString("streambench_$name")?.takeIf { it.isNotBlank() }

    private val tls = arg("tls")?.toBoolean() ?: false
    private val port = arg("port")?.toInt() ?: if (tls) 11563 else 11119
    private val warmupMb = arg("warmup_mb")?.toLong() ?: 100
    private val mb = arg("mb")?.toLong() ?: 700
    private val warmupSeconds = arg("warmup_s")?.toLong() ?: 15
    private val seconds = arg("seconds")?.toLong() ?: 50
    private val ballastMb = arg("ballast_mb")?.toInt() ?: 64
    private val heapMb = arg("heap_mb")?.toLong() ?: 512

    private val realMemory = PlayerActivity.readMemory
    private val realLookUp = PlayerActivity.lookUpSegments

    /** Settings › Logs' saved choice before the run ([logsChanged]: the run turned Detailed logging off). */
    private var detailedWas: Boolean? = null
    private var logsChanged = false

    /** Holds the heap the emulator has over the Shield's for the run. */
    private var ballast: ByteArray? = null

    /** The Shield's memory as the budgets read it: this device's, with the Shield's 512 MB large heap. */
    private val shield by lazy { DeviceMemory.of(context).copy(heapLimitBytes = heapMb * MIB) }

    private var settings: EngineSettings? = null
    private val meter = ConnectionMeter()
    /** The stream-cost line's sampler, as the app makes it, on the app's own count of what its streams hand their readers. */
    private val samplerMade = lazy {
        StreamCostSampler(
            container.streamTraffic, ThreadCpuReader(ticksPerSecond = clockTicksPerSecond(), files = androidKeptFiles), meter, ::artGcStats,
            threadCpuNanos = Debug::threadCpuTimeNanos,
        )
    }
    private val sampler by samplerMade

    /**
     * nzb-streamer's count of damaged articles used, for the run: the global registry's own meters count nothing. Its one
     * meter, so the run's other meters (a timer a command, a few a segment) record no more than the app's do.
     */
    private val meters = SimpleMeterRegistry().apply {
        config().meterFilter(MeterFilter.denyUnless { it.name == CRC_MISMATCH_USED })
    }

    @Before
    fun setUp() = runBlocking<Unit> {
        assumeTrue("the asset $NZB is not packaged (tools/stream-bench)", NZB in instrumentation.context.assets.list("").orEmpty())
        assumeTrue(
            "the bench's NNTP server is not reachable at 10.0.2.2:$port (tools/stream-bench/nntp_disk_server.py)",
            runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", port), 2_000) } }.isSuccess,
        )
        if (tls) StreamBenchTrust.install(checkNotNull(arg("cert_sha256")) { "TLS needs streambench_cert_sha256, the server certificate's fingerprint" })
        // The app's own provider sync goes first, or it could replace these settings halfway: a debug build's saved
        // provider. A bench build saves none, so this waits the 10 s out, by when the sync has been.
        withTimeoutOrNull(10_000) { container.engine.state.first { it != EngineState.NotConfigured } }
        detailedWas = container.settings.logs.first().detailed
        container.settings.updateLogs { it.copy(detailed = false) }
        logsChanged = true
        waitFor("Detailed logging off", 5_000) { (!AndroidLogConfig.detailed).takeIf { it } }
        val bench = EngineSettings(
            listOf(ProviderConfig(PROVIDER, PROVIDER, "10.0.2.2", port, "", "", useTls = tls, maxConnections = 8)),
            readAheadBytes = PlaybackBudgets.streamBytes(shield),
        )
        engineSetup("the engine on the bench's server", timeoutMs = BENCH_SETUP_MS) { container.engine.configure(bench) }
        settings = bench
        // Every segment comes from the server, as on a first play: none from an earlier run's cache.
        container.videoCache.clear()
        Metrics.addRegistry(meters)
        if (ballastMb > 0) ballast = ByteArray(ballastMb * MIB.toInt())
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        PlayerActivity.readMemory = realMemory
        PlayerActivity.lookUpSegments = realLookUp
        ballast = null
        if (samplerMade.isInitialized()) sampler.close()
        meter.close()
        Metrics.removeRegistry(meters)
        StreamBenchTrust.uninstall()
        if (logsChanged) container.settings.updateLogs { it.copy(detailed = detailedWas) }
    }

    @Test
    fun stream() = runBlocking<Unit> {
        val (nzb, file) = prepared()
        val uri = Uri.parse(UsenetUris.build(nzb.id, file.index, file.name, file.size))
        var before: StreamCostSample? = null
        var after: StreamCostSample? = null
        var endedAt: Long? = null
        // Read on a thread named as Media3's loader, which reads the data source in a play: its CPU is the loader's.
        Executors.newSingleThreadExecutor { Thread(it, LOADER) }.asCoroutineDispatcher().use { loader ->
            withContext(loader) {
                val source = container.dataSourceFactory.createDataSource()
                val buffer = ByteArray(64 * 1024)
                var read = 0L
                source.open(DataSpec.Builder().setUri(uri).build())
                try {
                    // Attaches the connection meter and opens the threads' files before the window.
                    sampler.sample()
                    while (true) {
                        if (before == null && read >= warmupMb * MB) before = sampler.sample()
                        if (read >= (warmupMb + mb) * MB) break
                        val n = source.read(buffer, 0, buffer.size)
                        // A release shorter than the window: the window ends with it.
                        if (n == C.RESULT_END_OF_INPUT) {
                            endedAt = read
                            break
                        }
                        read += n
                    }
                    after = sampler.sample()
                } finally {
                    source.close()
                }
            }
        }
        val invalid = listOfNotNull(endedAt?.let { "the release ended ${(it - warmupMb * MB) / MB} MB into the $mb MB window" })
        report("stream", StreamCost.between(checkNotNull(before) { "the release ended within the warm-up" }, checkNotNull(after)), invalid)
    }

    @Test
    fun play() = runBlocking<Unit> {
        val (nzb, file) = prepared()
        PlayerActivity.readMemory = { shield }
        PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
        val dropped = AtomicInteger()
        val rebuffers = AtomicInteger()
        val intent = Intent(context, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_URI, UsenetUris.build(nzb.id, file.index, file.name, file.size))
        ActivityScenario.launch<PlayerActivity>(intent).use { scenario ->
            val player = waitFor("the player", 30_000) { scenario.read { it.player } }
            scenario.onActivity {
                player.addAnalyticsListener(object : AnalyticsListener {
                    var ready = false

                    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                        dropped.addAndGet(droppedFrames)
                    }

                    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
                        if (state == Player.STATE_READY) ready = true
                        if (state == Player.STATE_BUFFERING && ready) rebuffers.incrementAndGet()
                    }
                })
            }
            waitFor("playback", 90_000) { scenario.read { a -> a.player?.takeIf { it.isPlaying } } }
            sampler.sample()
            delay(warmupSeconds * 1_000)
            val droppedBefore = dropped.get()
            val rebuffersBefore = rebuffers.get()
            val before = sampler.sample()
            delay(seconds * 1_000)
            val after = sampler.sample()
            val playing = scenario.read { a -> a.player?.isPlaying == true }
            val windowRebuffers = rebuffers.get() - rebuffersBefore
            val durationMs = scenario.read { a -> a.player?.duration ?: C.TIME_UNSET }
            // The release's bytes a second, played through: a play keeping up streams at about that.
            val releaseRate = if (durationMs > 0) file.size / (durationMs / 1e3) else null
            val cost = StreamCost.between(before, after)
            val invalid = buildList {
                if (windowRebuffers > 0) add("it rebuffered $windowRebuffers ${if (windowRebuffers == 1) "time" else "times"}")
                if (!playing) add("it wasn't playing at the end")
                if (releaseRate != null && cost.streamedPerSecond < 0.9 * releaseRate) {
                    add("it streamed %.2f MB/s, under nine tenths of the release's %.2f MB/s".format(Locale.ROOT, cost.streamedPerSecond / 1e6, releaseRate / 1e6))
                }
            }
            report("play", cost, invalid) {
                put("dropped_frames", dropped.get() - droppedBefore)
                put("rebuffers", windowRebuffers)
                put("playing_at_end", playing)
                putOpt("release_mb_per_s", releaseRate?.div(1e6))
            }
        }
    }

    /**
     * The window's [cost] as one "result {json}" line in logcat, with the stream-cost line. A window [invalid] says why
     * (a stall, a stop, the release's end) is logged "valid": false, and fails the test: its figures don't measure steady
     * streaming, and the summary leaves them out.
     */
    private fun report(mode: String, cost: StreamCost, windowInvalid: List<String>, more: JSONObject.() -> Unit = {}) {
        Log.i(TAG, cost.line())
        val damaged = meters.find(CRC_MISMATCH_USED).counter()?.count()?.toLong() ?: 0
        val invalid = windowInvalid + listOfNotNull(
            "it used $damaged ${if (damaged == 1L) "article" else "articles"} whose CRC didn't match".takeIf { damaged > 0 },
        )
        val result = JSONObject().apply {
            put("mode", mode)
            put("build", BuildConfig.BUILD_TYPE)
            put("debuggable", context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            // A debuggable app's runtime checks every JNI call (CheckJNI); these would have every app's checked.
            put("checkjni_props", CHECKJNI_PROPS.joinToString(" ") { "$it=${getprop(it) ?: ""}" })
            put("tls", tls)
            put("device", getprop("ro.boot.qemu.avd_name") ?: Build.MODEL)
            put("sdk", Build.VERSION.SDK_INT)
            put("cpus", Runtime.getRuntime().availableProcessors())
            put("concurrency", settings?.streamConcurrency)
            put("read_ahead_bytes", settings?.readAheadBytes)
            put("seconds", cost.seconds)
            put("mb", cost.streamedBytes / 1e6)
            put("mb_per_s", cost.streamedPerSecond / 1e6)
            putOpt("cpu_ms_per_mb", cost.msPerMb)
            putOpt("cores", cost.cores)
            for (kind in ThreadKind.entries) {
                putOpt("${kind.name.lowercase()}_ms_per_mb", cost.msPerMb(kind))
                put("${kind.name.lowercase()}_threads", cost.threads(kind))
            }
            putOpt("alloc_mb_per_mb", cost.allocatedPerMb)
            putOpt("gc_count", cost.gcCount)
            putOpt("gc_ms", cost.gcTimeMs)
            putOpt("blocking_gc_count", cost.blockingGcCount)
            putOpt("blocking_gc_ms", cost.blockingGcTimeMs)
            putOpt("fetched_mb_per_s", cost.fetchedPerSecond?.div(1e6))
            putOpt("busy_connections", cost.busyConnections)
            putOpt("connection_mb_per_s", cost.perConnection?.div(1e6))
            more()
            put("crc_mismatches_used", damaged)
            put("valid", invalid.isEmpty())
            if (invalid.isNotEmpty()) put("invalid", invalid.joinToString("; "))
        }
        Log.i(TAG, "result $result")
        assertTrue("the window doesn't measure steady streaming: ${invalid.joinToString("; ")}", invalid.isEmpty())
    }

    private suspend fun prepared(): Pair<PreparedNzb, FileCandidate> {
        val nzbBytes = instrumentation.context.assets.open(NZB).use { it.readBytes() }
        val outcome = engineSetup("the bench's NZB prepared", timeoutMs = BENCH_SETUP_MS) { container.preparer.prepare(nzbBytes, "stream-bench") }
        val nzb = (outcome as? PrepareOutcome.Ready)?.nzb ?: error("prepare failed: $outcome")
        container.store.put(nzb, nzbBytes)
        val file = nzb.defaultPick ?: error("no video in ${nzb.candidates}")
        return nzb to file
    }

    private fun getprop(name: String): String? = try {
        ProcessBuilder("getprop", name).start().inputStream.bufferedReader().use { it.readText().trim() }.ifEmpty { null }
    } catch (e: java.io.IOException) {
        null
    }

    private fun <T> ActivityScenario<PlayerActivity>.read(probe: (PlayerActivity) -> T): T {
        var result: Result<T>? = null
        onActivity { result = runCatching { probe(it) } }
        return result!!.getOrThrow()
    }

    private suspend fun <T : Any> waitFor(what: String, timeoutMs: Long, probe: () -> T?): T {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            probe()?.let { return it }
            if (System.nanoTime() > deadline) throw AssertionError("timed out waiting for $what")
            delay(100)
        }
    }

    private companion object {
        const val TAG = "StreamBench"

        /** nzb-streamer's count of articles used though their CRC didn't match (its CrcMismatchPolicy.TOLERATE). */
        const val CRC_MISMATCH_USED = "nzb.segments.crc_mismatch_used"
        const val NZB = "stream-bench.nzb"
        const val PROVIDER = "stream-bench"
        const val LOADER = "ExoPlayer:Loader:StreamBench"
        val CHECKJNI_PROPS = listOf("debug.checkjni", "dalvik.vm.checkjni", "ro.kernel.android.checkjni")

        /** A MB as the stream-cost line counts it. */
        const val MB = 1_000_000L
        const val MIB = 1024L * 1024

        /**
         * How long the bench's engine setups may take: longer than a test fixture's ([engineSetup]'s 30 s), as its release's
         * articles are 3.84 MB each, served from disk by a Python server, over TLS too.
         */
        const val BENCH_SETUP_MS = 120_000L
    }
}

/**
 * Has the platform's default trust manager (what ktor-nntp-client's HostnameVerifyingTrustManager wraps, and Ktor's TLS
 * then takes) trust the bench server's self-signed certificate, by its SHA-256, for the run: a provider of the test's
 * own named in the security property TrustManagerFactory reads. The hostname check is the client's own, as for any
 * provider: the certificate names 10.0.2.2.
 */
internal object StreamBenchTrust {
    private const val PROPERTY = "ssl.TrustManagerFactory.algorithm"
    private const val ALGORITHM = "StreamBenchPin"
    private const val NAME = "StreamBenchTrust"
    private var previous: String? = null

    @Volatile
    private var pinned: String? = null

    fun install(sha256: String) {
        pinned = sha256.lowercase()
        if (Security.getProvider(NAME) == null) Security.addProvider(TrustProvider())
        previous = Security.getProperty(PROPERTY)
        Security.setProperty(PROPERTY, ALGORITHM)
    }

    fun uninstall() {
        if (pinned == null) return
        Security.setProperty(PROPERTY, previous ?: "PKIX")
        Security.removeProvider(NAME)
        pinned = null
    }

    @Suppress("DEPRECATION")
    private class TrustProvider : Provider(NAME, 1.0, "trusts the stream bench's server") {
        init {
            put("TrustManagerFactory.$ALGORITHM", Factory::class.java.name)
        }
    }

    class Factory : TrustManagerFactorySpi() {
        override fun engineInit(ks: KeyStore?) = Unit

        override fun engineInit(spec: ManagerFactoryParameters?) = Unit

        override fun engineGetTrustManagers(): Array<TrustManager> = arrayOf(Manager)
    }

    private object Manager : X509TrustManager {
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            val expected = pinned ?: throw CertificateException("no bench certificate pinned")
            val leaf = chain.firstOrNull() ?: throw CertificateException("the server sent no certificate")
            val actual = MessageDigest.getInstance("SHA-256").digest(leaf.encoded).joinToString("") { "%02x".format(it) }
            if (actual != expected) throw CertificateException("not the bench server's certificate")
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = throw CertificateException("no client certificates")

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
