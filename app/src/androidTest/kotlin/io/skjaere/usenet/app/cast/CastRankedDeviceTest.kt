package io.skjaere.usenet.app.cast

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.casting.CastLink
import io.skjaere.usenet.app.collection.ChooseState
import io.skjaere.usenet.app.collection.MovieViewModel
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.link.PlayChoice
import io.skjaere.usenet.app.link.PlayOnHost
import io.skjaere.usenet.app.link.PlayTarget
import io.skjaere.usenet.app.link.THIS_PHONE
import io.skjaere.usenet.app.search.ReleaseList
import io.skjaere.usenet.app.search.bitrateChip
import io.skjaere.usenet.app.search.rankedForLine
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.MovieDetails
import io.skjaere.usenet.releases.ReleaseReason
import io.skjaere.usenet.releases.Severity
import io.skjaere.usenet.search.RankedItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Releases ranked for the Cast device chosen in Play on (L126, L127; CsPhoneRanked), on a real device over a fake Cast
 * session (Google's software never started, L73): the Movie page's Choose release for Lantern Field, from an indexer this
 * test runs on the device itself, with Kitchen display (a first Chromecast: H.264 up to 1080p) chosen. The list says
 * "Ranked for Kitchen display" under its sorts, its chips name the device, nothing says it plays on this device, and the
 * releases it can't fully play come after every one that fits, each still tappable. This phone chosen on the Play on list
 * ranks the same releases again at once, with no new search, and the line goes. The session ending by itself while the
 * list shows leaves its order and its line (risk 10); Choose release again ranks for this phone. With a maximum set for
 * casting, a release above it is tried after every one that fits, and says so (W52). TMDB is the fake; the film, the
 * releases and the device are invented; the settings are put back.
 */
@RunWith(AndroidJUnit4::class)
class CastRankedDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val container get() = compose.activity.container
    private val routes = FakeRoutes()
    private val connector = FakeConnector()
    private val kitchen = castRoute("Kitchen display", model = "Chromecast")
    private var tmdb: FakeTmdb? = null
    private var indexer: Lantern? = null
    private var keyAdded = false
    private lateinit var saved: AppSettings
    private lateinit var vm: MovieViewModel

    private val lanternField = MovieDetails(
        MOVIE, "Lantern Field", null, 2008, "A lighthouse keeper's daughter maps the fields the tide leaves behind.", null, null, 118,
        ExternalIds(imdb = IMDB), releaseDate = LocalDate.of(2008, 5, 2), status = "Released",
    )

    @Before
    fun setUp() {
        assumeFalse("a TV never casts (tv.TvNoCastTest)", container.television)
        val listing = Lantern(RELEASES).also { indexer = it }
        runBlocking {
            saved = container.settings.settings.first()
            if (container.settings.tmdbKey() == null) {
                container.settings.setTmdbKey("cast-ranked-device-test")
                keyAdded = true
            }
            // This test's indexer alone: the saved ones switched off until the settings are put back.
            container.settings.indexers.first().filter { it.enabled }.forEach { container.settings.setIndexerEnabled(it.id, false) }
            container.settings.saveIndexer(INDEXER, "Listing (cast ranking test)", listing.url, "cast-ranked-key")
        }
        tmdb = FakeTmdb(movies = mapOf(MOVIE to lanternField))
        CastFakes.started(routes, connector, CopyOnWriteArrayList())
        choose()
        vm = MovieViewModel(container, MOVIE)
        waitUntil("the film's details") { vm.header.value != null }
    }

    @After
    fun restore() {
        if (!::saved.isInitialized) return
        // The list's watch of Play on's choices goes with it.
        if (::vm.isInitialized) compose.runOnUiThread { vm.closeChooser() }
        container.playOn.choose(PlayChoice.Phone)
        CastFakes.reset()
        tmdb?.close()
        indexer?.close()
        runBlocking {
            if (container.settings.indexer(INDEXER) != null) container.settings.deleteIndexer(INDEXER)
            container.settings.restore(saved)
            if (keyAdded) container.settings.setTmdbKey(null)
        }
    }

    /** Kitchen display connected over the fake session, and chosen in Play on, as its row's tap does. */
    private fun choose() {
        CastFakes.connected(kitchen, connector)
        container.playOn.choose(PlayChoice.Cast(kitchen.id, kitchen.name))
        waitUntil("Kitchen display connected") { container.playOn.target.value == PlayTarget.Cast(kitchen.id, kitchen.name, CastLink.Connected, kitchen.facts) }
    }

    /** The Movie page's list as its screen shows it, with Play on's list over it, as the app's shell has it. */
    private fun show() = compose.setContent {
        AppTheme {
            Box(Modifier.fillMaxSize()) {
                val choose by vm.choose.collectAsStateWithLifecycle()
                (choose as? ChooseState.Ready)?.let { c -> ReleaseList(c.group, c.target.title, onBack = {}, rankedFor = c.rankedFor, onPlay = {}) }
                PlayOnHost(onAddTv = {}, onPairAgain = {}, onPrivacy = {})
            }
        }
    }

    /** Choose release, as the page's button runs it: the list once found. */
    private fun chooseRelease(): ChooseState.Ready<*> {
        compose.runOnUiThread { vm.chooseRelease() }
        compose.waitUntil(30_000) { vm.choose.value is ChooseState.Ready }
        return vm.choose.value as ChooseState.Ready<*>
    }

    private fun shown(): ChooseState.Ready<*> = vm.choose.value as ChooseState.Ready<*>

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, millis: Long = 10_000) = compose.waitUntil(millis) { exists(matcher) }

    /** Down the list to the card holding [text]. */
    private fun scrollTo(text: String) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text, substring = true))
    }

    private fun waitUntil(what: String, timeoutMs: Long = 15_000, probe: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + timeoutMs
        while (!probe()) {
            if (SystemClock.uptimeMillis() > until) throw AssertionError("timed out waiting for $what")
            Thread.sleep(50)
        }
    }

    private val RankedItem.warned: Boolean
        get() = ranked.reasons.any { it is ReleaseReason.NoSoundOn || it is ReleaseReason.NoPictureOn || it is ReleaseReason.TooBigFor }

    /**
     * CsPhoneRanked 1-2: ranked for Kitchen display, its line under the sorts and the chips naming it; no "Plays on this
     * device"; the DTS-only remux, which this phone may not even decode, tried after every release that fits and tappable.
     * Then This phone chosen on the Play on list: the same releases ranked again at once, no search, and the line gone.
     */
    @Test
    fun aListRankedForKitchenDisplaySaysSoAndRanksAgainForThisPhone() {
        show()
        val list = chooseRelease()
        assertEquals("Kitchen display", list.rankedFor)
        val line = rankedForLine("Kitchen display")
        waitFor(hasText(line))
        // Read before the releases: above the first card, and a stop of its own, not a live region.
        val lineNode = compose.onNode(hasText(line)).fetchSemanticsNode()
        assertNull(lineNode.config.getOrNull(SemanticsProperties.LiveRegion))
        val first = compose.onNode(hasText(list.group.playable.first().item.title, substring = true) and hasClickAction()).fetchSemanticsNode()
        assertTrue("the line before the releases", lineNode.boundsInRoot.bottom <= first.boundsInRoot.top)

        // Every release that fits first, then those Kitchen display can't fully play, in their usual order.
        val playable = list.group.playable
        val firstWarned = playable.indexOfFirst { it.warned }
        assertTrue("some fit: $playable", firstWarned > 0)
        assertTrue("one that fits after a warned one", playable.drop(firstWarned).all { it.warned })
        assertTrue("the DTS remux among the last", playable.drop(firstWarned).any { it.item.title == DTS })
        assertEquals(listOf(DISC), list.group.hidden.map { it.item.title })
        // CsPhoneRanked's chips, each naming the device, and nothing saying it plays here.
        for (chip in CHIPS) {
            scrollTo(chip)
            assertTrue(chip, exists(hasText(chip, substring = true)))
        }
        assertFalse(exists(hasText("Plays on this device", substring = true)))
        scrollTo(DTS)
        assertTrue("the DTS remux tappable", exists(hasText(DTS, substring = true) and hasClickAction()))
        val searches = indexer!!.searches

        // This phone, on the Play on list over the release list: ranked again at once, with no new search.
        container.playOn.openSheet()
        waitFor(hasText(THIS_PHONE) and hasClickAction())
        compose.onNode(hasText(THIS_PHONE) and hasClickAction()).performClick()
        compose.waitUntil(10_000) { shown().rankedFor == null }
        compose.waitUntil(10_000) { !exists(hasText(line)) }
        assertTrue("no chip names the device", shown().group.playable.none { it.warned })
        assertEquals("an indexer asked again", searches, indexer!!.searches)
    }

    /**
     * L127, risk 10: the session ending by itself while the list shows (Play goes back to this phone, W36) leaves its order,
     * its chips and its line; Choose release again ranks for this phone.
     */
    @Test
    fun theSessionEndingLeavesTheListAsItIsAndChooseReleaseAgainRanksForThisPhone() {
        show()
        val list = chooseRelease()
        val line = rankedForLine("Kitchen display")
        waitFor(hasText(line))

        connector.ended(kitchen)
        waitUntil("This phone chosen by itself") { container.playOn.chosen.value == PlayChoice.Phone }
        compose.waitForIdle()
        Thread.sleep(1_000)
        compose.waitForIdle()
        assertEquals("its order and its line", list, shown())
        assertTrue(exists(hasText(line)))

        compose.runOnUiThread { vm.closeChooser() }
        val again = chooseRelease()
        assertNull(again.rankedFor)
        compose.waitUntil(10_000) { !exists(hasText(line)) }
        assertTrue("no chip names the device", again.group.playable.none { it.warned })
    }

    /**
     * W52, L132 (CsPhoneRanked 2): with 40 Mbps set for casting, and none for this phone, the 44 Mbps FLAC remux, which
     * Kitchen display plays, is tried after every release that fits, still tappable, "⚠ Above your 40 Mbps for casting"
     * beside its "≈ 44 Mbps". With This phone chosen, the maximum when casting counts for nothing.
     */
    @Test
    fun aReleaseAboveTheMaximumWhenCastingSaysSoAndGoesAfterEveryOneThatFits() {
        runBlocking { container.settings.updatePlayback { it.copy(maxBitrateMbps = null, castMaxBitrateMbps = 40) } }
        show()
        val list = chooseRelease()
        assertEquals("Kitchen display", list.rankedFor)
        val playable = list.group.playable
        val flac = playable.single { it.item.title == FLAC }
        assertEquals(listOf<ReleaseReason>(ReleaseReason.AboveCastMax(40)), flac.ranked.reasons.filter { it.severity == Severity.Warning })
        assertEquals("≈ 44 Mbps", bitrateChip(flac.ranked))
        val fits = playable.filter { item -> item.ranked.reasons.none { it.severity == Severity.Warning } }
        assertTrue("some fit: $playable", fits.isNotEmpty())
        assertTrue("tried after every one that fits", playable.indexOf(flac) > playable.indexOf(fits.last()))
        scrollTo(FLAC)
        assertTrue("the FLAC remux tappable", exists(hasText(FLAC, substring = true) and hasClickAction()))
        assertTrue(exists(hasText(ABOVE_CAST_MAX)))
        assertTrue(exists(hasText("≈ 44 Mbps")))

        // This phone, on the Play on list over the release list: ranked again, and nothing above a maximum.
        container.playOn.openSheet()
        waitFor(hasText(THIS_PHONE) and hasClickAction())
        compose.onNode(hasText(THIS_PHONE) and hasClickAction()).performClick()
        compose.waitUntil(10_000) { shown().rankedFor == null }
        assertTrue(shown().group.playable.none { item -> item.ranked.reasons.any { it is ReleaseReason.AboveCastMax } })
        compose.waitUntil(10_000) { !exists(hasText(ABOVE_CAST_MAX)) }
    }

    /**
     * An indexer on this device that lists [releases] (a name and a size each) for any search, as Lantern Field's by its
     * IMDb id; until closed. Just enough of Newznab: its caps, and its results. [searches]: how many it answered.
     */
    private class Lantern(releases: List<Pair<String, Long>>) : AutoCloseable {
        private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        val url = "http://127.0.0.1:${server.localPort}"

        @Volatile
        var searches = 0
            private set

        private val results = buildString {
            val date = ZonedDateTime.now().minusYears(1).format(DateTimeFormatter.RFC_1123_DATE_TIME)
            append("""<?xml version="1.0" encoding="UTF-8"?><rss version="2.0" xmlns:newznab="http://www.newznab.com/DTD/2010/feeds/attributes/"><channel>""")
            append("""<newznab:response offset="0" total="${releases.size}"/>""")
            releases.forEachIndexed { i, (title, size) ->
                val guid = "lantern-$i"
                append("<item><title>$title</title><guid isPermaLink=\"false\">$guid</guid><pubDate>$date</pubDate>")
                append("""<enclosure url="$url/api?t=get&amp;id=$guid" length="$size" type="application/x-nzb"/>""")
                listOf("category" to "2040", "size" to "$size", "guid" to guid, "grabs" to "${100 - i}", "files" to "1", "password" to "0", "imdb" to IMDB.removePrefix("tt"))
                    .forEach { (attr, value) -> append("""<newznab:attr name="$attr" value="$value"/>""") }
                append("</item>")
            }
            append("</channel></rss>")
        }

        init {
            thread(isDaemon = true, name = "lantern-indexer") {
                while (!server.isClosed) runCatching { server.accept().use(::answer) }
            }
        }

        private fun answer(socket: Socket) {
            val request = socket.getInputStream().bufferedReader()
            val line = request.readLine().orEmpty()
            while (!request.readLine().isNullOrEmpty()) Unit
            val caps = "t=caps" in line
            if (!caps) searches++
            val body = (if (caps) CAPS else results).toByteArray()
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Type: application/xml; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(body)
                flush()
            }
        }

        override fun close() = server.close()

        private companion object {
            const val CAPS = """<?xml version="1.0" encoding="UTF-8"?><caps><server version="1.0" title="Listing"/><limits max="100" default="100"/>""" +
                """<searching><search available="yes" supportedParams="q"/><tv-search available="no"/><movie-search available="yes" supportedParams="q,imdbid"/></searching>""" +
                """<categories><category id="2000" name="Movies"><subcat id="2040" name="HD"/></category></categories></caps>"""
        }
    }

    private companion object {
        /** An invented film's TMDB and IMDb ids; nothing real has them. */
        const val MOVIE = 991_731
        const val IMDB = "tt9917310"
        const val INDEXER = "cast-ranked-device-test"

        const val DTS = "Lantern.Field.2008.1080p.BluRay.REMUX.AVC.DTS-HD.MA.5.1-FERRY"
        const val FLAC = "Lantern.Field.2008.1080p.BluRay.REMUX.AVC.FLAC.2.0-HALYARD"
        const val DISC = "Lantern.Field.2008.COMPLETE.BLURAY-DRIFTWOOD"

        /** CsPhoneRanked's releases as a first Chromecast takes them: its 4K is H.264, which it shows only up to HD. */
        val RELEASES = listOf(
            "Lantern.Field.2008.1080p.BluRay.DD5.1.x264-ORCHID" to 10_400_000_000,
            "Lantern.Field.2008.1080p.WEB-DL.DDP5.1.H.264-KESTREL" to 6_200_000_000,
            "Lantern.Field.2008.720p.BluRay.x264-DRIFTWOOD" to 4_400_000_000,
            DTS to 31_800_000_000,
            "Lantern.Field.2008.1080p.BluRay.REMUX.VC-1.DD5.1-LUMEN" to 24_700_000_000,
            FLAC to 38_600_000_000,
            "Lantern.Field.2008.German.DL.2160p.UHD.BluRay.DDP5.1.x264-WATTWURM" to 18_900_000_000,
            "Lantern.Field.2008.1080p.BluRay.REMUX.AVC.TrueHD.7.1.Atmos-FERRY" to 38_300_000_000,
            DISC to 44_100_000_000,
        )

        /** CsPhoneRanked's device chips, as the list draws them. */
        val CHIPS = listOf(
            "⚠ DTS-HD MA audio: no sound on Kitchen display",
            "⚠ VC-1: no picture on Kitchen display",
            "⚠ 4K: Kitchen display shows HD",
            "⚠ TrueHD audio: no sound on Kitchen display",
        )

        /** CsPhoneRanked's chip for a release above the maximum when casting (W52). */
        const val ABOVE_CAST_MAX = "⚠ Above your 40 Mbps for casting"
    }
}
