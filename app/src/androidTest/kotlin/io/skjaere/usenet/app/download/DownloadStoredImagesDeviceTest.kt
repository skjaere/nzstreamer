package io.skjaere.usenet.app.download

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.SystemClock
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.Uri
import coil3.annotation.DelicateCoilApi
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.SuccessResult
import io.skjaere.usenet.app.appImageLoader
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.download.DownloadImages.Kind
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.HL
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.episode
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.show
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.waitTill
import io.skjaere.usenet.app.home.HomeContent
import io.skjaere.usenet.app.home.HomeState
import io.skjaere.usenet.app.home.UpNextPlay
import io.skjaere.usenet.app.home.UpNextRow
import io.skjaere.usenet.app.play.AutoAdvance
import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.app.play.NextEpisodeCard
import io.skjaere.usenet.app.play.Prefetch
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadCandidate
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.data.WatchTitle
import io.skjaere.usenet.metadata.TmdbImages
import io.skjaere.usenet.newznab.TvIds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * L411: each download's poster and still kept with it, and drawn from there, with no TMDB: the pictures come through the
 * app's fetch seam (a JPEG made here), and the screens' image loader refuses every network address, as an image host out
 * of reach would.
 * - Queued from the sheets' own deps (an episode's Download and a season's), each download's copies are written into its
 *   bookkeeping folder at once, from the addresses at w342 and w300, never into its video folder; the Downloads page,
 *   offline, draws each one's stored poster; reconcile and the NZB's drop keep them, and a delete takes them with the row.
 * - With the host out of reach: a show's row on the Downloads page draws its latest episode's stored poster, Up next's
 *   row of a downloaded episode its download's, and the player's next-episode card for a downloaded next episode its
 *   download's stored still, as the player picks it.
 * - The app's own fetch asks the image host each time, never Coil's caches: a host on the device's loopback, the
 *   screens' loader the app's own for that case.
 *
 * The downloads queued here list a release of an indexer that isn't set up, so nothing could be grabbed for them, and no
 * host is asked to run. Every row is the test's own, deleted with its folders after; the fetch seam, the screens' image
 * loader, the offline say and the settings are put back. The show, its episodes and their pictures are invented.
 */
@RunWith(AndroidJUnit4::class)
class DownloadStoredImagesDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.container
    private val storage = container.downloadStorage
    private val images = container.downloadImages
    private val seeds = DownloadsPageSeeds(context, container)
    private val realFetch = images.fetch
    private val screens = Unreachable(context)

    /** What the fetch seam was asked, and whether it answers (the image host in reach) or not. */
    private val asked = ConcurrentLinkedQueue<String>()

    @Volatile
    private var reachable = true
    private var saved: AppSettings? = null

    @Before
    fun setUp() = runBlocking<Unit> {
        assertFalse("a phone", container.television)
        // The debug build's start saves its dev settings first: what is put back after is what they made.
        withTimeout(SEEDING_MILLIS) { container.devSettingsSaved.await() }
        saved = container.settings.settings.first()
        container.settings.updateMetadata { it.copy(showArtwork = true) }
        seeds.before()
        dropShows()
        images.fetch = ImageFetch { url ->
            asked += url
            if (reachable) JPEG else null
        }
        screens.install()
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        val failures = mutableListOf<Throwable>()
        suspend fun back(what: suspend () -> Unit) {
            try {
                what()
            } catch (e: Throwable) {
                failures += e
            }
        }
        back { screens.restore() }
        images.fetch = realFetch
        container.offlineOverride.value = null
        back { seeds.after() }
        back { dropShows() }
        saved?.let { back { container.settings.restore(it) } }
        failures.firstOrNull()?.let { first -> failures.drop(1).forEach(first::addSuppressed); throw first }
    }

    /** Any download of this test's own show, the sheets' (left by a run cut short), deleted with its folders. */
    private suspend fun dropShows() = container.data.downloads.all().first().filter { it.showTmdbId == SHOW }.forEach { storage.delete(it.id) }

    @Test
    fun copiesAreWrittenAtEnqueueShownOfflineKeptThroughReconcileAndDropSourceAndGoneWithTheRow() {
        val deps = DownloadSheetDeps.of(container, null, context)
        val one = runBlocking { deps.enqueue(target(4), listOf(candidate(4)), GB, null) }?.let(seeds::adopt)
        val season = runBlocking { deps.enqueueAll(listOf(DownloadRunner.QueueItem(target(5), listOf(candidate(5))), DownloadRunner.QueueItem(target(6), listOf(candidate(6))))) }
            .map(seeds::adopt)
        assertNotNull("queued from an episode's sheet", one)
        assertEquals("queued from a season's sheet", 2, season.size)
        val rows = listOf(one!!) + season

        until("each one's poster and still kept") { rows.all { row -> Kind.entries.all { copy(row, it).isFile } } }
        rows.forEach { row ->
            assertArrayEquals(JPEG, copy(row, Kind.Poster).readBytes())
            assertArrayEquals(JPEG, copy(row, Kind.Still).readBytes())
            // Nothing of them in the video folder, which a resume empties.
            val video = storage.videoFolder(row.id)?.list().orEmpty().toList()
            assertTrue("the video folder holds $video", video.none { it.startsWith("poster") || it.startsWith("still") })
        }
        assertEquals(
            "fetched as TMDB's w342 poster and w300 stills",
            setOf(TmdbImages.poster(POSTER, 342)) + (4..6).map { TmdbImages.still(still(it), 300) },
            asked.filter { "harbor-lights" in it }.toSet(),
        )

        // The Downloads page offline, the image host out of reach: each row draws its stored poster.
        reachable = false
        container.offlineOverride.value = true
        seeds.online.value = false
        compose.show(context) { DownloadsScreen(seeds.deps(), onBack = {}, onSettings = {}, onShow = {}, onOpenMovie = {}) }
        compose.waitTill("each row's stored poster drawn") { rows.all { copy(it, Kind.Poster).path in screens.loaded } }
        assertEquals("no network address asked", emptyList<String>(), screens.refused.toList())

        // Reconcile and the NZB's drop leave a download's pictures; its delete takes them with its folder.
        runBlocking {
            storage.reconcile()
            rows.forEach { storage.dropSource(it.id) }
        }
        rows.forEach { row -> Kind.entries.forEach { assertTrue("$it kept", copy(row, it).isFile) } }
        runBlocking { storage.delete(rows.first().id) }
        assertFalse(storage.bookkeeping(rows.first().id).exists())
        assertTrue(copy(rows.last(), Kind.Poster).isFile)
    }

    @Test
    fun withTheImageHostOutOfReachAShowsRowDrawsItsLatestEpisodesStoredPoster() {
        val first = seeds.seed(finished(1).copy(completedAt = 2))
        val latest = seeds.seed(finished(2).copy(completedAt = 3))
        kept(first, latest)
        compose.show(context) { DownloadsScreen(seeds.deps(), onBack = {}, onSettings = {}, onShow = {}, onOpenMovie = {}) }
        compose.waitTill("the show's row draws its latest episode's stored poster") { copy(latest, Kind.Poster).path in screens.loaded }
        assertEquals(emptyList<String>(), screens.refused.toList())
    }

    @Test
    fun withTheImageHostOutOfReachUpNextsDownloadedRowDrawsItsStoredPoster() {
        val row = seeds.seed(finished(1))
        kept(row)
        val upNext = UpNextRow(
            "Harbor Lights", "S02E01 · Brine · 4 min left", 0.33f, false, POSTER,
            UpNextPlay.Resume(
                WatchState(
                    row.watchKey, WatchKind.Episode, showTmdbId = HL, season = 2, episode = 1, title = WatchTitle.episode("Harbor Lights", 2, 1),
                    positionMs = 120_000, durationMs = 360_000, updatedAt = 0,
                ),
            ),
            "Resume Harbor Lights S02E01",
        )
        compose.show(context) {
            HomeContent(
                HomeState(emptyList(), listOf(upNext), collection = null, hasTmdbKey = true), artwork = true, onSearch = {}, onSettings = {}, onRecent = {},
                onPlay = {}, onShow = {}, onCollection = {}, onMetadata = {}, onImport = {},
                downloadedKeys = setOf(row.watchKey), downloadedIds = mapOf(row.watchKey to row.id),
            )
        }
        compose.waitTill("Up next's row draws its download's stored poster") { copy(row, Kind.Poster).path in screens.loaded }
        assertEquals(emptyList<String>(), screens.refused.toList())
    }

    @Test
    fun withTheImageHostOutOfReachTheNextEpisodeCardDrawsADownloadedNextEpisodesStoredStill() {
        // Finished now, so it is the runner's latest of its episode.
        val row = seeds.seed(finished(2).copy(completedAt = System.currentTimeMillis()))
        kept(row)
        val next = EpisodePlay(
            showTmdbId = HL, showName = "Harbor Lights", showYear = 2021, aliases = emptyList(), ids = TvIds(tmdb = HL), season = 2, episode = 2,
            episodeName = "The Breakwater", airDate = null, runtimeMinutes = 50, stillPath = still(2),
        )
        val card = AutoAdvance.Card(secondsLeft = 8, progress = 0.2f, prefetch = Prefetch.Downloaded(partsMissing = 0))
        // The download the player names, as it does: from the runner's finished ones, by the next episode's watch key.
        until("the runner lists the download among the finished ones") { container.downloads.done.value[next.key]?.id == row.id }
        val stored = PlayerBranches.storedStill(card.prefetch, next.key, container.downloads.done.value)
        assertEquals(row.id, stored)
        compose.show(context) {
            NextEpisodeCard(next, card, showStill = true, compact = false, takeFocus = false, onStop = {}, onPlayNow = {}, stored = stored)
        }
        compose.waitTill("the card draws the download's stored still") { copy(row, Kind.Still).path in screens.loaded }
        assertEquals(emptyList<String>(), screens.refused.toList())
    }

    /** [rows]' pictures kept through the app's own path (the seam answering), then the image host out of reach. */
    /**
     * L411: the app's own fetch for the downloads' pictures asks the image host each time, never Coil's caches, so a copy's
     * time is when the host answered: a refresh at 30 days fetches the picture again, and its 180 days count from then. The
     * host here is one on the device's loopback, its picture changed between the two fetches; the screens' loader is the
     * app's own, with its caches, for the case.
     */
    @Test
    @OptIn(DelicateCoilApi::class)
    fun theAppsFetchAsksTheImageHostEachTimeNeverCoilsCaches() = runBlocking<Unit> {
        val loader = appImageLoader(context, container.memory)
        SingletonImageLoader.setUnsafe(loader)
        LoopbackImageHost(listOf(jpeg(Color.RED), jpeg(Color.BLUE))).use { host ->
            try {
                val first = checkNotNull(realFetch.fetch(host.url)) { "no picture came" }
                assertEquals(1, host.asked.get())
                assertEquals(Color.RED, colour(first))
                // A month on, the host has a new picture at the same address.
                val again = checkNotNull(realFetch.fetch(host.url)) { "no picture came again" }
                assertEquals("asked again, not read from Coil's caches", 2, host.asked.get())
                assertEquals(Color.BLUE, colour(again))
            } finally {
                loader.memoryCache?.clear()
                loader.diskCache?.remove(host.url)
                loader.shutdown()
            }
        }
    }

    /** A picture of one [colour], as a JPEG. */
    private fun jpeg(colour: Int): ByteArray = ByteArrayOutputStream().also { out ->
        Bitmap.createBitmap(34, 51, Bitmap.Config.ARGB_8888).apply { eraseColor(colour) }.compress(Bitmap.CompressFormat.JPEG, 90, out)
    }.toByteArray()

    /** [jpeg]'s colour at its centre, red or blue as near as a JPEG keeps it; else what it is. */
    private fun colour(jpeg: ByteArray): Int {
        val bitmap = checkNotNull(BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size))
        val pixel = bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        return when {
            Color.red(pixel) > 200 && Color.blue(pixel) < 60 -> Color.RED
            Color.blue(pixel) > 200 && Color.red(pixel) < 60 -> Color.BLUE
            else -> pixel
        }
    }

    /**
     * An image host on the device's loopback: each request answered with the next of [answers] (the last again once they
     * run out), and counted in [asked].
     */
    private class LoopbackImageHost(private val answers: List<ByteArray>) : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        val asked = AtomicInteger()
        val url: String = "http://127.0.0.1:${socket.localPort}/harbor-lights-poster.jpg"

        init {
            thread(isDaemon = true, name = "loopback-image-host") {
                while (!socket.isClosed) {
                    val client = try {
                        socket.accept()
                    } catch (e: IOException) {
                        break
                    }
                    client.use { c ->
                        val request = c.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        while (request.readLine()?.isNotEmpty() == true) Unit
                        val body = answers[minOf(asked.getAndIncrement(), answers.lastIndex)]
                        val out = c.getOutputStream()
                        out.write("HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                        out.write(body)
                        out.flush()
                    }
                }
            }
        }

        override fun close() = socket.close()
    }

    private fun kept(vararg rows: Download) {
        runBlocking { images.keep(rows.toList()) }
        rows.forEach { row -> Kind.entries.forEach { assertTrue("$it kept", copy(row, it).isFile) } }
        reachable = false
    }

    private fun copy(row: Download, kind: Kind) = File(storage.bookkeeping(row.id), kind.file)

    /** Waits until [condition] holds, ten seconds at most, saying [what] where it never does. */
    private fun until(what: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (!condition()) {
            if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("never: $what")
            SystemClock.sleep(50)
        }
    }

    /** Episode [number] of Harbor Lights' second season, finished, with its poster and still at TMDB. */
    private fun finished(number: Int) = episode(2, number, DownloadState.Done).copy(posterPath = POSTER, stillPath = still(number))

    private fun target(number: Int) = DownloadTarget(
        kind = DownloadKind.Episode, title = "Harbor Lights", subtitle = "S02E0$number", showTmdbId = SHOW, season = 2, episode = number, seasonEpisodes = 8,
        posterPath = POSTER, stillPath = still(number),
    )

    private fun candidate(number: Int) = DownloadCandidate(INDEXER, "hl-s02e0$number", "Harbor.Lights.S02E0$number.1080p.WEB-DL-KESTREL", size = GB)

    /**
     * Coil's loader for the screens with the image host out of reach: every network address refused and kept in
     * [refused], and what each picture loaded from in [loaded] (a stored copy by its path). [install] makes it the
     * screens' loader and [restore] puts the app's back, as later classes check the app's.
     */
    @OptIn(DelicateCoilApi::class)
    private class Unreachable(private val context: Context) {
        val loaded = ConcurrentLinkedQueue<String>()
        val refused = ConcurrentLinkedQueue<String>()
        private var app: ImageLoader? = null
        private var loader: ImageLoader? = null

        fun install() {
            app = SingletonImageLoader.get(context)
            loader = appImageLoader(context, context.container.memory).newBuilder()
                .components { add(Refusing()) }
                .eventListener(Loads())
                .build()
                .also { SingletonImageLoader.setUnsafe(it) }
        }

        fun restore() {
            app?.let { SingletonImageLoader.setUnsafe(it) }
            app = null
            loader?.shutdown()
            loader = null
        }

        private inner class Refusing : Fetcher.Factory<Uri> {
            override fun create(data: Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
                if (data.scheme != "http" && data.scheme != "https") return null
                return object : Fetcher {
                    override suspend fun fetch(): FetchResult {
                        refused += data.toString()
                        throw IOException("the image host is out of reach")
                    }
                }
            }
        }

        private inner class Loads : coil3.EventListener() {
            override fun onSuccess(request: ImageRequest, result: SuccessResult) {
                loaded += request.data.toString()
            }
        }
    }

    private companion object {
        const val GB = 1_000_000_000L
        const val SEEDING_MILLIS = 60_000L
        const val SHOW = 990_941
        const val INDEXER = "dl-images-a"
        const val POSTER = "/harbor-lights-poster.jpg"

        /** A small JPEG made here, the image host's answer. */
        val JPEG: ByteArray = ByteArrayOutputStream().also { out ->
            Bitmap.createBitmap(34, 51, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(46, 74, 92)) }.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }.toByteArray()

        fun still(number: Int) = "/harbor-lights-s02e0$number.jpg"
    }
}
