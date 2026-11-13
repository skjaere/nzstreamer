package io.skjaere.usenet.app.link

import android.os.SystemClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.settings.SettingsScreen
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.link.KeyId
import io.skjaere.usenet.link.LinkMessage
import io.skjaere.usenet.link.LinkMessage.Hello
import io.skjaere.usenet.link.LinkVersions
import io.skjaere.usenet.link.Need
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.BindException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Settings › TVs on a phone (item 15, PtPhoneSettings, L30, L41) on a real device: the hub's row last in Playback; each
 * state's row, with a TV in the test's process behind each one on this device's own address (Ready and Seen now, what a
 * TV needs to play, removed this phone, set up again, an update on either end, NZStreamer not open there); Forget asking
 * first and telling the TV; each Pair again question; the page with no TV. The page's store is the test's own, in
 * memory; the names are invented.
 */
@RunWith(AndroidJUnit4::class)
class TvsScreenDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val phone = softwareKey()
    private val store = memoryLinkStore()
    private val tvs = CopyOnWriteArrayList<FakeTv>()
    private val links = CopyOnWriteArrayList<TvLinks>()
    @Volatile private var added = false
    @Volatile private var again: KeyId? = null

    @After
    fun stop() {
        tvs.forEach { it.close() }
        links.forEach { it.close() }
        scope.cancel()
    }

    private fun links(versions: LinkVersions = LinkVersions.V1, setUpAgainMillis: Long = TvLinksDeps.SET_UP_AGAIN_MS) = TvLinks(
        scope,
        TvLinksDeps(
            store = store, identity = { phone }, finder = LinkNsdFinder(context), inFront = MutableStateFlow(true), name = "Test phone", app = "0.1.0",
            versions = versions, setUpAgainMillis = setUpAgainMillis,
        ),
    ).also { links += it }

    /** A TV this phone paired with, kept in the store, behind which [tv] (or nothing, once it closes) answers. */
    private fun tv(
        base: String,
        knowsPhone: Boolean = true,
        ready: Hello.Ready = Hello.Ready(provider = true, indexer = true, tmdb = true),
        versions: LinkVersions = LinkVersions.V1,
    ): FakeTv {
        val tv = FakeTv(context, inventedTvName(base), paired = if (knowsPhone) setOf(phone.id) else emptySet(), ready = ready, versions = versions, advertised = false)
        tvs += tv
        runBlocking { assertTrue(store.addTv(tv.key.id, tv.name, tv.address.hostAddress!!, tv.port)) }
        return tv
    }

    /**
     * NZStreamer on [old] set up again: another key, the same name, address and port. The port is bound again a moment
     * later should another socket on this busy device hold it just then (a test run once met EADDRINUSE there).
     */
    private fun setUpAgain(old: FakeTv): FakeTv {
        old.close()
        val until = SystemClock.uptimeMillis() + 3_000
        while (true) {
            try {
                return FakeTv(context, old.name, port = old.port, paired = setOf(phone.id), advertised = false).also { tvs += it }
            } catch (e: BindException) {
                if (SystemClock.uptimeMillis() > until) throw e
                Thread.sleep(100)
            }
        }
    }

    private fun show(links: TvLinks) {
        links.start()
        compose.setContent {
            AppTheme { TvsScreen(onBack = {}, onAdd = { added = true }, onPairAgain = { again = it }, deps = TvsDeps.of(links, store.tvs)) }
        }
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, millis: Long = 15_000) = compose.waitUntil(millis) { exists(matcher) }

    private fun tap(matcher: SemanticsMatcher) {
        compose.waitUntil(15_000) { compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty() }
        val node = compose.onNode(matcher)
        // A dialog's buttons have no list to scroll in.
        runCatching { node.performScrollTo() }
        node.performClick()
    }

    /** The page's own lifecycle, which a test moves as Home, a rotation or another page over it would. */
    private class PageLifecycle : LifecycleOwner {
        private val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry

        fun move(to: Lifecycle.State) {
            registry.currentState = to
        }
    }

    /**
     * L36: the page stopped for a moment (a rotation recreates it; Home and back within the linger) keeps its TVs' links:
     * the app leaving the front closes them, a while after, not the page's stop.
     */
    @Test
    fun aPageStoppedForAMomentKeepsItsTvsLinks() {
        val living = tv("Living room TV")
        val links = links().also { it.start() }
        val page = PageLifecycle()
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides page) {
                AppTheme { TvsScreen(onBack = {}, onAdd = {}, onPairAgain = {}, deps = TvsDeps.of(links, store.tvs)) }
            }
        }
        waitFor(hasText(tvStateLine(TvState.Ready, living.name)))
        compose.waitUntil(5_000) { living.links.size == 1 }
        val first = living.links.single()
        compose.runOnUiThread { page.move(Lifecycle.State.CREATED) }
        Thread.sleep(1_000)
        compose.runOnUiThread { page.move(Lifecycle.State.RESUMED) }
        Thread.sleep(500)
        assertTrue("the TV's link closed as the page stopped", living.links.singleOrNull() === first)
    }

    @Test
    fun eachStatesRow() {
        val ready = tv("Living room TV")
        val noProvider = tv("Guest room TV", ready = Hello.Ready(provider = false, indexer = true, tmdb = true))
        val noIndexer = tv("Kitchen TV", ready = Hello.Ready(provider = true, indexer = false, tmdb = true))
        val removed = tv("Study TV", knowsPhone = false)
        val newer = tv("Attic TV", versions = LinkVersions(pv = 3, minPv = 2))
        val closed = tv("Bedroom TV").also { it.close() }
        val setUp = tv("Den TV").also(::setUpAgain)
        show(links())
        waitFor(hasText(tvStateLine(TvState.Ready, ready.name)))
        waitFor(hasText(tvStateLine(TvState.NeedsProvider, noProvider.name)))
        waitFor(hasText(tvStateLine(TvState.NeedsIndexer, noIndexer.name)))
        waitFor(hasText(tvStateLine(TvState.RemovedHere, removed.name)))
        waitFor(hasText(tvStateLine(TvState.UpdatePhone, newer.name)))
        waitFor(hasText(tvStateLine(TvState.NotOpen, closed.name)))
        // Only once mDNS has run 10 s without the pinned id (L12).
        waitFor(hasText(tvStateLine(TvState.SetUpAgain, setUp.name)), millis = 40_000)
        assertTrue(exists(hasText("Seen now", substring = true)))
        assertTrue(exists(hasText(ready.address.hostAddress!! + ":" + ready.port)))
        assertTrue(exists(hasText(TVS_INTRO)))
        assertTrue(exists(hasText(TVS_FOOT)))
    }

    /**
     * D2PhoneTvStates 1 (L223): a TV that plays from its debrid service alone reads Ready; one whose debrid way lacks a
     * part says which, naming no service, in the warning colour as a Usenet TV's lack.
     */
    @Test
    fun aDebridTvsRowReadsReadyOrWhatItsDebridWayLacks() {
        val debrid = tv("Living room TV", ready = Hello.Ready(provider = false, indexer = false, tmdb = true, debrid = true))
        val noService = tv("Bedroom TV", ready = Hello.Ready(provider = false, indexer = false, tmdb = true, needs = Need.Service))
        val off = tv("Guest room TV", ready = Hello.Ready(provider = false, indexer = false, tmdb = true, needs = Need.ServiceOff))
        val noTorrents = tv("Office TV", ready = Hello.Ready(provider = false, indexer = false, tmdb = true, needs = Need.TorrentIndexer))
        show(links())
        waitFor(hasText(tvStateLine(TvState.Ready, debrid.name)))
        waitFor(hasText("Needs a debrid service on the TV"))
        waitFor(hasText("The TV’s debrid service is off"))
        waitFor(hasText("Needs a torrent indexer on the TV"))
        assertFalse("a Usenet lack for a debrid TV", exists(hasText(tvStateLine(TvState.NeedsProvider, noService.name))))
        assertTrue(listOf(noService, off, noTorrents).all { exists(hasText(it.name)) })
    }

    @Test
    fun anOlderTvReadsUpdateTheTv() {
        val older = tv("Living room TV")
        show(links(versions = LinkVersions(pv = 2, minPv = 2)))
        waitFor(hasText(tvStateLine(TvState.UpdateTv, older.name)))
    }

    @Test
    fun forgetAsksFirstThenTellsTheTvAndTheRowGoes() {
        val living = tv("Living room TV")
        show(links())
        waitFor(hasText(tvStateLine(TvState.Ready, living.name)))
        tap(hasText("Forget") and hasClickAction() and hasAnyAncestor(isDialog()).not())
        waitFor(hasText(forgetAsk(living.name)))
        assertTrue(exists(hasText(FORGET_TEXT)))
        tap(hasText("Keep") and hasClickAction())
        compose.waitUntil(5_000) { !exists(hasText(forgetAsk(living.name))) }
        assertTrue(exists(hasText(living.name)))
        tap(hasText("Forget") and hasClickAction() and hasAnyAncestor(isDialog()).not())
        tap(hasText("Forget") and hasClickAction() and hasAnyAncestor(isDialog()))
        waitFor(hasText(TVS_EMPTY))
        compose.waitUntil(5_000) { LinkMessage.Unpair in living.received }
        assertTrue(runBlocking { store.tvs.first() }.isEmpty())
    }

    @Test
    fun aTvSetUpAgainOrThatRemovedThisPhoneAsksToPairAgain() {
        val removed = tv("Study TV", knowsPhone = false)
        show(links())
        waitFor(hasText(tvStateLine(TvState.RemovedHere, removed.name)))
        tap(hasText(removed.name) and hasClickAction())
        waitFor(hasText(removedHere(removed.name)) and hasAnyAncestor(isDialog()))
        assertTrue(exists(hasText(REMOVED_TEXT)))
        tap(hasText("Not now") and hasClickAction())
        assertEquals(null, again)
        tap(hasText(removed.name) and hasClickAction())
        tap(hasText(PAIR_AGAIN) and hasClickAction())
        compose.waitUntil(5_000) { again != null }
        assertEquals(removed.key.id, again)
    }

    /** PtPhonePairFail: a TV set up again (another key, its name) asks in its own words, and Pair again names its row's TV. */
    @Test
    fun aTvSetUpAgainAsksInItsOwnWords() {
        val setUp = tv("Den TV").also(::setUpAgain)
        // mDNS's time without the pinned id, shrunk (L12).
        show(links(setUpAgainMillis = 1_000))
        waitFor(hasText(tvStateLine(TvState.SetUpAgain, setUp.name)))
        tap(hasText(setUp.name) and hasClickAction())
        waitFor(hasText(setUpAgainTitle(setUp.name)) and hasAnyAncestor(isDialog()))
        assertTrue(exists(hasText(SET_UP_AGAIN_TEXT)))
        assertEquals("${setUp.name} was set up again", setUpAgainTitle(setUp.name))
        tap(hasText(PAIR_AGAIN) and hasClickAction())
        compose.waitUntil(5_000) { again != null }
        assertEquals(setUp.key.id, again)
    }

    @Test
    fun withNoTvThePageSaysWhatItIsForAndAddsOne() {
        show(links())
        waitFor(hasText(TVS_EMPTY))
        tap(hasText(ADD_TV_TITLE) and hasClickAction())
        assertTrue(added)
        added = false
        tap(hasText("Add") and hasClickAction())
        assertTrue(added)
    }

    @Test
    fun theHubsRowIsLastInPlaybackWithItsLine() {
        val line = tvsLine(runBlocking { context.container.data.link.tvs.first() })
        var opened = false
        compose.setContent { AppTheme { SettingsScreen({}, {}, {}, {}, {}, {}, onLogs = {}, onTvs = { opened = true }) } }
        val row = hasText(TVS_TITLE) and hasText(line) and hasClickAction()
        compose.waitUntil(15_000) { compose.onAllNodes(row).fetchSemanticsNodes().isNotEmpty() }
        fun bounds(matcher: SemanticsMatcher) = compose.onNode(matcher).fetchSemanticsNode().boundsInRoot
        // After This device, the group's third row, and before the App group's header.
        assertTrue("TVs before This device", bounds(row).top >= bounds(hasText("This device") and hasClickAction()).bottom)
        assertTrue("TVs after Playback", bounds(row).bottom <= bounds(hasText("App".uppercase())).top)
        tap(row)
        assertTrue(opened)
    }
}
