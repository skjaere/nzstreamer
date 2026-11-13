package io.skjaere.usenet.app.link

import android.os.SystemClock
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isNotSelected
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.link.KeyId
import io.skjaere.usenet.link.LinkMessage.Hello
import io.skjaere.usenet.link.LinkVersions
import io.skjaere.usenet.link.Need
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.BindException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The Play on sheet (item 23, PtPhoneSheet, PtPhonePairFail, W11, W12) on a real device: a TV in the test's process behind
 * each state's row, on this device's own address, reached through the app's own links. A tap on a ready TV, or one whose
 * NZStreamer isn't open, chooses it and closes the sheet; one that needs a provider or an indexer can't be chosen; one set
 * up again, or that removed this phone, asks to pair again; an update on either end says which; This phone chooses
 * This phone. With no TV paired the sheet offers Add a TV, and Done from Add a TV opened here chooses the new TV. The
 * store and the links are the test's own; the names are invented.
 */
@RunWith(AndroidJUnit4::class)
class PlayOnSheetDeviceTest {
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

    /** A TV this phone paired with, kept in the store, behind which [tv] answers. */
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

    /** NZStreamer on [old] set up again: another key, the same name, address and port. */
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

    /** The app's Play on host over [links], its sheet open. */
    private fun show(links: TvLinks): PlayOn {
        links.start()
        val on = PlayOn(scope, store.tvs, links.states, hold = links::holdTv)
        on.openSheet()
        compose.setContent {
            AppTheme { PlayOnHost(onAddTv = { added = true }, onPairAgain = { again = it }, onPrivacy = {}, deps = PlayOnDeps(on, store.tvs, links.states, links::hold)) }
        }
        return on
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, millis: Long = 15_000) = compose.waitUntil(millis) { exists(matcher) }

    private fun tap(matcher: SemanticsMatcher) {
        waitFor(matcher)
        compose.onNode(matcher).performClick()
    }

    private fun line(state: TvState, name: String) = hasText(sheetStateLine(state, name))

    @Test
    fun aTapOnEachState() {
        val ready = tv("Living room TV")
        val closed = tv("Bedroom TV").also { it.close() }
        val noProvider = tv("Guest room TV", ready = Hello.Ready(provider = false, indexer = true, tmdb = true))
        val noIndexer = tv("Kitchen TV", ready = Hello.Ready(provider = true, indexer = false, tmdb = true))
        val removed = tv("Study TV", knowsPhone = false)
        val newer = tv("Attic TV", versions = LinkVersions(pv = 3, minPv = 2))
        val on = show(links())
        waitFor(hasText(SHEET_TITLE))
        assertTrue("This phone ticked", exists(hasText(THIS_PHONE) and isSelected()))
        assertTrue(exists(hasText(TVS_WITH_NZSTREAMER.uppercase())))
        assertTrue(exists(hasText(PLAY_ON_FOOT)))
        waitFor(line(TvState.Ready, ready.name))
        waitFor(line(TvState.NotOpen, closed.name))
        waitFor(line(TvState.NeedsProvider, noProvider.name))
        waitFor(line(TvState.NeedsIndexer, noIndexer.name))
        waitFor(line(TvState.RemovedHere, removed.name))
        waitFor(line(TvState.UpdatePhone, newer.name))

        // Ready: chosen, and the sheet closes.
        tap(hasText(ready.name) and hasClickAction())
        compose.waitUntil(5_000) { on.chosen.value == PlayChoice.Tv(ready.key.id) && on.sheet.value == null }
        // Not open: chosen all the same (Play waits greyed until it opens).
        compose.runOnUiThread { on.openSheet() }
        waitFor(hasText(ready.name) and isSelected())
        tap(hasText(closed.name) and hasClickAction())
        compose.waitUntil(5_000) { on.chosen.value == PlayChoice.Tv(closed.key.id) && on.sheet.value == null }
        // A TV that needs setting up can't be chosen: the sheet stays, the choice too.
        compose.runOnUiThread { on.openSheet() }
        waitFor(hasText(noProvider.name) and isNotSelected())
        compose.onNode(hasText(noProvider.name) and hasClickAction()).performClick()
        compose.onNode(hasText(noIndexer.name) and hasClickAction()).performClick()
        compose.waitForIdle()
        assertEquals(PlayChoice.Tv(closed.key.id), on.chosen.value)
        assertTrue(on.sheet.value != null)
        // Removed this phone: Pair again's question; Not now leaves it.
        tap(hasText(removed.name) and hasClickAction())
        waitFor(hasText(removedHere(removed.name)) and hasAnyAncestor(isDialog()))
        assertTrue(exists(hasText(REMOVED_TEXT)))
        tap(hasText("Not now") and hasClickAction())
        compose.waitUntil(5_000) { !exists(hasText(REMOVED_TEXT)) }
        assertNull(again)
        compose.runOnUiThread { on.openSheet() }
        tap(hasText(removed.name) and hasClickAction())
        tap(hasText(PAIR_AGAIN) and hasClickAction())
        compose.waitUntil(5_000) { again != null }
        assertEquals(removed.key.id, again)
        // A newer NZStreamer on the TV: this phone needs the update.
        compose.runOnUiThread { on.openSheet() }
        tap(hasText(newer.name) and hasClickAction())
        waitFor(hasText(failureTitle(AddTvFailure.UpdatePhone, newer.name)) and hasAnyAncestor(isDialog()))
        tap(hasText("Close") and hasClickAction())
        // This phone, chosen again.
        compose.runOnUiThread { on.openSheet() }
        tap(hasText(THIS_PHONE) and hasClickAction())
        compose.waitUntil(5_000) { on.chosen.value == PlayChoice.Phone && on.sheet.value == null }
    }

    /**
     * D2PhoneTvStates 2 (L223, L251): a TV that plays from its debrid service alone reads Ready and is chosen; one whose
     * debrid way lacks a part says which after ⚠, greyed, and can't be chosen; TalkBack reads the ⚠ as "Warning".
     */
    @Test
    fun aDebridTvIsChosenAndOneWhoseDebridWayLacksAPartSaysWhich() {
        val debrid = tv("Living room TV", ready = Hello.Ready(provider = false, indexer = false, tmdb = true, debrid = true))
        val lacking = mapOf(
            tv("Bedroom TV", ready = Hello.Ready(provider = false, indexer = false, tmdb = true, needs = Need.Service)) to TvState.NeedsService,
            tv("Guest room TV", ready = Hello.Ready(provider = false, indexer = false, tmdb = true, needs = Need.ServiceOff)) to TvState.ServiceOff,
            tv("Office TV", ready = Hello.Ready(provider = false, indexer = false, tmdb = true, needs = Need.TorrentIndexer)) to TvState.NeedsTorrentIndexer,
        )
        val on = show(links())
        waitFor(line(TvState.Ready, debrid.name))
        for ((tv, state) in lacking) {
            waitFor(line(state, tv.name))
            assertTrue("TalkBack's words for ${tv.name}", exists(hasContentDescription("${tv.name}, Warning: ${sheetStateLine(state, tv.name)}")))
        }
        assertEquals(listOf("Needs a debrid service on the TV", "The TV’s debrid service is off", "Needs a torrent indexer on the TV"), lacking.map { (tv, state) -> sheetStateLine(state, tv.name) })
        // None of them can be chosen: the sheet stays, This phone still the choice.
        for (tv in lacking.keys) compose.onNode(hasText(tv.name) and hasClickAction()).performClick()
        compose.waitForIdle()
        assertEquals(PlayChoice.Phone, on.chosen.value)
        assertTrue(on.sheet.value != null)
        // The debrid TV is chosen, and the sheet closes.
        tap(hasText(debrid.name) and hasClickAction())
        compose.waitUntil(5_000) { on.chosen.value == PlayChoice.Tv(debrid.key.id) && on.sheet.value == null }
    }

    @Test
    fun anOlderTvSaysToUpdateItThere() {
        val older = tv("Living room TV")
        val on = show(links(versions = LinkVersions(pv = 2, minPv = 2)))
        waitFor(line(TvState.UpdateTv, older.name))
        tap(hasText(older.name) and hasClickAction())
        waitFor(hasText(failureTitle(AddTvFailure.UpdateTv, older.name)) and hasAnyAncestor(isDialog()))
        assertTrue(exists(hasText(failureText(AddTvFailure.UpdateTv, older.name)!!)))
        assertEquals(PlayChoice.Phone, on.chosen.value)
    }

    @Test
    fun aTvSetUpAgainAsksToPairAgain() {
        val setUp = tv("Den TV").also(::setUpAgain)
        // mDNS's time without the pinned id, shrunk (L12).
        show(links(setUpAgainMillis = 1_000))
        waitFor(line(TvState.SetUpAgain, setUp.name))
        tap(hasText(setUp.name) and hasClickAction())
        waitFor(hasText(setUpAgainTitle(setUp.name)) and hasAnyAncestor(isDialog()))
        assertTrue(exists(hasText(SET_UP_AGAIN_TEXT)))
        tap(hasText(PAIR_AGAIN) and hasClickAction())
        compose.waitUntil(5_000) { again != null }
        assertEquals(setUp.key.id, again)
    }

    @Test
    fun withNoTvTheSheetOffersAddATv() {
        show(links())
        waitFor(hasText(SHEET_TITLE))
        assertTrue(exists(hasText(THIS_PHONE) and isSelected()))
        assertTrue(exists(hasText(ADD_TV_LINE)))
        assertFalse("no TVs' group without a TV", exists(hasText(TVS_WITH_NZSTREAMER.uppercase())))
        assertFalse("no footnote without a TV", exists(hasText(PLAY_ON_FOOT)))
        tap(hasText(ADD_TV_TITLE) and hasClickAction())
        compose.waitUntil(5_000) { added }
    }

    /** Done from Add a TV opened from the sheet chooses the new TV (PtPhonePair's note); from Settings nothing else changes. */
    @Test
    fun doneFromAddATvOpenedHereChoosesTheNewTv() {
        val links = links().also { it.start() }
        val on = PlayOn(scope, store.tvs, links.states, hold = links::holdTv)
        val tv = FakeTv(context, inventedTvName("Living room TV")).also {
            it.answer = true
            it.pairing()
            tvs += it
        }
        var done = false
        compose.setContent { AppTheme { AddTvScreen(onDone = { done = true }, onBack = {}, deps = AddTvDeps.of(links, store), onPaired = on::choose) } }
        tap(hasText(tv.name) and hasClickAction())
        tap(hasText(YES_IT_MATCHES) and hasClickAction())
        waitFor(hasText(isReady(tv.name)))
        assertTrue(exists(hasText(pairedLine(tv.name))))
        assertEquals("nothing chosen before Done", PlayChoice.Phone, on.chosen.value)
        tap(hasText("Done") and hasClickAction())
        compose.waitUntil(5_000) { done }
        assertEquals(PlayChoice.Tv(tv.key.id), on.chosen.value)
    }
}
