package io.skjaere.usenet.app.tv

import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.HomeContentShown
import io.skjaere.usenet.app.TvDevice
import io.skjaere.usenet.app.assumeTv
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.debrid.DebridGate
import io.skjaere.usenet.app.engineOnSavedProviders
import io.skjaere.usenet.app.link.PairedPhones
import io.skjaere.usenet.app.play.OkGuard
import io.skjaere.usenet.app.setup.PHONE_SETUP_TITLE
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.ServiceSignIn
import io.skjaere.usenet.data.TorrentIndexerRecord
import io.skjaere.usenet.debrid.ServiceKind
import io.skjaere.usenet.link.LinkCodec
import io.skjaere.usenet.link.LinkMessage.Play
import io.skjaere.usenet.link.LinkMessage.Rejected
import io.skjaere.usenet.link.Rejection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A phone's Play on a TV that can't play yet (tv36, L32, PtTvNotReady, D2TvNotReady): with no usable Usenet provider, or
 * no enabled indexer, the TV shows the request on its sheet, "Set up with your phone" focused, the form for what is
 * missing and Close after it, and the phone is told why. A TV set up only for its debrid service (L224) says what that
 * way lacks: no service, a service off ("Open Debrid services" first and focused, L248), a key the service refused (named
 * on this sheet alone), no torrent indexer; each button opens its page. No service is asked: the accounts and the
 * torrent indexer are saved with invented facts. The settings are put back after each case; the phone is invented.
 */
@TvDevice
@RunWith(AndroidJUnit4::class)
class TvNotReadyTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val container = InstrumentationRegistry.getInstrumentation().targetContext.container
    private val pages = LinkPages(compose)
    private val remote = pages.remote
    private val home = HomeContentShown()
    private val phones = PairedPhones()
    private val linked = mutableListOf<LinkedPhone>()
    private lateinit var saved: AppSettings

    /** The providers were switched off: the app's sync puts the engine on them again as they come back. */
    private var providersOff = false

    /** Past [assumeTv]: on a phone the class is skipped, and there is nothing to put back. */
    private var set = false

    /** A service account a case signed in to: signed out of after it, so its key leaves the secret store too. */
    private var account: String? = null

    private val episode = Play.Episode(
        showTmdbId = 900002, showName = "Harbor Lights", season = 2, episode = 4, episodeName = "Low Water", tvdb = 9000021, tmdb = 900002,
    )

    @Before
    fun setUp() {
        assumeTv()
        set = true
        phones.clear()
        home.show()
        saved = runBlocking { container.settings.settings.first() }
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
    }

    @After
    fun tearDown() {
        if (!set) return
        linked.forEach { it.close() }
        container.playback.dismiss()
        InstrumentationRegistry.getInstrumentation().setInTouchMode(true)
        phones.restore()
        home.restore()
        runBlocking {
            account?.let { container.settings.signOut(it) }
            if (::saved.isInitialized) container.settings.restore(saved)
            // Only as the providers come back does the sync act; with them untouched it never does, whatever the engine is on.
            if (providersOff) engineOnSavedProviders()
        }
    }

    private fun exists(text: String) = pages.exists(hasText(text))

    /** The sheet for [words], its first focus on "Set up with your phone", then [add] and Close below it. */
    private fun sheetSays(words: String, add: String) = sheetSays(words, listOf(PHONE_SETUP_TITLE, add))

    /** The sheet for [words], its buttons [buttons] from the first, focused, down, then Close. */
    private fun sheetSays(words: String, buttons: List<String>) {
        compose.waitUntil(10_000) { exists(words) }
        remote.waitForFocus(buttons.first())
        for (next in buttons.drop(1) + "Close") assertEquals(next, remote.press(KeyEvent.KEYCODE_DPAD_DOWN))
        // Back up to the first, as the sheet opened.
        assertEquals(buttons.first(), remote.pressUntil(KeyEvent.KEYCODE_DPAD_UP, buttons.first(), max = buttons.size + 1))
    }

    /** OK on [button] closes the sheet and opens the page titled [title]. */
    private fun opens(button: String, title: String) {
        assertEquals(button, remote.pressUntil(KeyEvent.KEYCODE_DPAD_DOWN, button, max = 2))
        // Past the sheet's guard against an OK meant for what opened it (OkGuard, from the sheet's showing): the presses
        // before this one can all come inside it, and the OK would be dropped.
        SystemClock.sleep(OkGuard.WINDOW_MS)
        remote.press(KeyEvent.KEYCODE_DPAD_CENTER)
        compose.waitUntil(10_000) { exists(title) }
    }

    /**
     * This TV set up only for its debrid way (L224): no provider and no Newznab indexer, the service accounts gone and the
     * invented torrent indexer saved when [torrentIndexer] says so. Its key isn't needed: the readiness reads the record.
     */
    private suspend fun debridOnly(torrentIndexer: Boolean) {
        providersOff = true
        container.settings.restore(
            saved.copy(
                providers = emptyList(), indexers = emptyList(), services = saved.services.map { it.copy(deleted = true) },
                torrentIndexers = if (torrentIndexer) listOf(TorrentIndexerRecord("tv-not-ready-t1", "Home Prowlarr", "http://192.168.1.20:9696/1/api")) else emptyList(),
            ),
        )
    }

    /** TorBox signed in with an invented key and sign-in facts: nothing asks the service. */
    private suspend fun signIn(): String =
        container.settings.saveService(ServiceKind.TorBox.name, "tv-not-ready-key") { ServiceSignIn("quay.viewer@example.org", "Standard") }.also { account = it }

    @Test
    fun noUsableProviderShowsTheSheetWithSetUpWithYourPhoneFocused() = runBlocking<Unit> {
        pages.launch().use {
            val galaxy = LinkedPhone("Invented phone", pages, phones).also { linked += it }
            providersOff = true
            container.settings.restore(saved.copy(providers = saved.providers.map { it.copy(enabled = false) }))
            assertEquals(Rejected(1, Rejection.NotReadyProvider), galaxy.ask(Play(1, episode = episode)))
            sheetSays("Invented phone asked to play Harbor Lights S02E04. Set up a Usenet provider first.", "Add a Usenet provider")
            // As the sheet of a play that couldn't start: its title and episode.
            assertEquals(true, exists("Harbor Lights"))
            assertEquals(true, exists("S02E04 · Low Water"))
        }
    }

    /** D2TvNotReady 1: a torrent indexer saved, no service signed in: Add a debrid service opens Add a service. */
    @Test
    fun noDebridServiceAsksForOneAndOpensAddAService() = runBlocking<Unit> {
        assumeTrue("debrid is hidden in this build", DebridGate.shown)
        pages.launch().use {
            val galaxy = LinkedPhone("Invented phone", pages, phones).also { linked += it }
            debridOnly(torrentIndexer = true)
            assertEquals(Rejected(1, Rejection.NotReadyService), galaxy.ask(Play(1, episode = episode)))
            sheetSays("Invented phone asked to play Harbor Lights S02E04. Set up a debrid service first.", listOf(PHONE_SETUP_TITLE, "Add a debrid service"))
            opens("Add a debrid service", "Add a service")
        }
    }

    /**
     * D2TvNotReady 2, choice 6: TorBox and a torrent indexer saved, TorBox switched off: "Open Debrid services" first and
     * focused, as the phone's page can't switch a service on, and it opens Debrid services.
     */
    @Test
    fun aServiceSwitchedOffPutsOpenDebridServicesFirstAndOpensIt() = runBlocking<Unit> {
        assumeTrue("debrid is hidden in this build", DebridGate.shown)
        pages.launch().use {
            val galaxy = LinkedPhone("Invented phone", pages, phones).also { linked += it }
            debridOnly(torrentIndexer = true)
            container.settings.setServiceEnabled(signIn(), false)
            assertEquals(Rejected(1, Rejection.NotReadyServiceOff), galaxy.ask(Play(1, episode = episode)))
            sheetSays(
                "Invented phone asked to play Harbor Lights S02E04. Your debrid service is off: switch it on in Settings › Debrid services.",
                listOf("Open Debrid services", PHONE_SETUP_TITLE),
            )
            opens("Open Debrid services", "Debrid services")
        }
    }

    /**
     * Choice 7 (L248): a key the service refused is named on the TV's own sheet, with where to replace it; the phone is
     * told only that the service is off, and nothing it heard names the service.
     */
    @Test
    fun aRefusedKeyNamesTheServiceOnTheTvsSheetAlone() = runBlocking<Unit> {
        assumeTrue("debrid is hidden in this build", DebridGate.shown)
        pages.launch().use {
            val galaxy = LinkedPhone("Invented phone", pages, phones).also { linked += it }
            debridOnly(torrentIndexer = true)
            container.settings.markKeyRefused(signIn())
            assertEquals(Rejected(1, Rejection.NotReadyServiceOff), galaxy.ask(Play(1, episode = episode)))
            sheetSays(
                "Invented phone asked to play Harbor Lights S02E04. TorBox refused this TV's API key: replace it in Settings › Debrid services.",
                listOf("Open Debrid services", PHONE_SETUP_TITLE),
            )
            // Everything the phone was sent, as the bytes it read.
            val sent = galaxy.heard.map { String(LinkCodec.encode(it) ?: ByteArray(0)) }
            assertTrue("the phone heard of the TV's service", sent.none { "TorBox" in it || "quay.viewer" in it })
            opens("Open Debrid services", "Debrid services")
        }
    }

    /** D2TvNotReady 3: TorBox signed in and on, no torrent indexer: Add a torrent indexer opens its form. */
    @Test
    fun noTorrentIndexerAsksForOneAndOpensItsForm() = runBlocking<Unit> {
        assumeTrue("debrid is hidden in this build", DebridGate.shown)
        pages.launch().use {
            val galaxy = LinkedPhone("Invented phone", pages, phones).also { linked += it }
            debridOnly(torrentIndexer = false)
            signIn()
            assertEquals(Rejected(1, Rejection.NotReadyTorrentIndexer), galaxy.ask(Play(1, episode = episode)))
            sheetSays("Invented phone asked to play Harbor Lights S02E04. Add a torrent indexer first: this TV searches its own.", listOf(PHONE_SETUP_TITLE, "Add a torrent indexer"))
            opens("Add a torrent indexer", "Add torrent indexer")
        }
    }

    @Test
    fun noEnabledIndexerShowsTheSheetWithSetUpWithYourPhoneFocused() = runBlocking<Unit> {
        pages.launch().use {
            val galaxy = LinkedPhone("Invented phone", pages, phones).also { linked += it }
            container.settings.restore(saved.copy(indexers = saved.indexers.map { it.copy(enabled = false) }))
            assertEquals(Rejected(1, Rejection.NotReadyIndexer), galaxy.ask(Play(1, movie = Play.Movie(900031, "Harbor Bell", 2026))))
            sheetSays("Invented phone asked to play Harbor Bell. Add an indexer first: this TV searches its own.", "Add an indexer")
        }
    }
}
