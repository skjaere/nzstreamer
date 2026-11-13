package io.skjaere.usenet.app.tv

import android.app.Activity
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.skjaere.usenet.app.ImmersiveHintSeen
import io.skjaere.usenet.app.PlayerActivity
import io.skjaere.usenet.app.TvDevice
import io.skjaere.usenet.app.assumeTv
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.debrid.DebridFakes
import io.skjaere.usenet.app.debrid.DebridGate
import io.skjaere.usenet.app.debrid.DebridSearchFixture
import io.skjaere.usenet.app.everyStep
import io.skjaere.usenet.app.link.PairedPhones
import io.skjaere.usenet.app.link.TvReach
import io.skjaere.usenet.app.link.mirroredRows
import io.skjaere.usenet.app.link.mirroredSteps
import io.skjaere.usenet.app.link.rowsAfter
import io.skjaere.usenet.app.link.rowsBefore
import io.skjaere.usenet.app.onMainThread
import io.skjaere.usenet.app.play.NowPlaying
import io.skjaere.usenet.app.waitFor
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.ServiceSignIn
import io.skjaere.usenet.core.PipelineStep
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.debrid.ServiceKind
import io.skjaere.usenet.link.FinalKind
import io.skjaere.usenet.link.LinkCodec
import io.skjaere.usenet.link.LinkMessage
import io.skjaere.usenet.link.LinkMessage.Pause
import io.skjaere.usenet.link.LinkMessage.Play
import io.skjaere.usenet.link.LinkMessage.SeekBy
import io.skjaere.usenet.link.LinkMessage.Status
import io.skjaere.usenet.link.LinkMessage.Stop
import io.skjaere.usenet.link.Playback
import io.skjaere.usenet.link.SheetKind
import io.skjaere.usenet.link.SheetStep
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A phone's Play on a TV set up only for its debrid service (tv36, L224, L227), end to end over the fakes, without the
 * mock: TorBox signed in on the debrid fake and Home Prowlarr on the Torznab fake, no Usenet provider and no Newznab
 * indexer. The phone linked to it hears it can play from its service; its Play is acked; the TV's sheet goes Searching
 * (listing Preparing, Opening and Checking audio and video), then the release's Preparing and Opening, then Playing, and
 * a phone's sheet lists the service's rows throughout; Pause and SeekBy act on the TV's player; Stop leaves it, and the
 * phone gets a Final with the film's key. Nothing the phone hears names the TV's service, its key, a hash, a magnet or
 * the CDN's address, and the fake saw no magnet with more than its hash. What the TV had saved is taken away for the test
 * and put back after, keys included, as DebridOnlyDeviceTest does. The phone, the film and the accounts are invented.
 */
@TvDevice
@RunWith(AndroidJUnit4::class)
class TvDebridIncomingPlayTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    /** The player hides the system bars: Android's hint about that would take its window. */
    @get:Rule
    val immersiveHint = ImmersiveHintSeen()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val container = instrumentation.targetContext.container
    private val settings = container.settings
    private val pages = LinkPages(compose)
    private val phones = PairedPhones()
    private val linked = mutableListOf<LinkedPhone>()
    private val realLookUp = PlayerActivity.lookUpSegments
    private val realRead = PlayerActivity.readPlayback
    private lateinit var saved: AppSettings

    /** Passwords and API keys by secret name, as they were. */
    private var secrets = emptyMap<String, String>()
    private var fakeBack: (() -> Unit)? = null
    private var endpointsBack: (() -> Unit)? = null

    /** Past the class's assumptions: on a phone, or without the fakes, it is skipped, and there is nothing to put back. */
    private var set = false

    @Before
    fun setUp() = runBlocking<Unit> {
        assumeTv()
        assumeTrue("debrid is hidden in this build", DebridGate.shown)
        DebridFakes.assumeDebrid()
        DebridFakes.assumeTorznab()
        set = true
        // The debug build's own settings saved first: else they could land after this test took the settings away.
        container.devSettingsSaved.await()
        // The film plays from a torrent whatever an earlier class in the run played it from (DFX-1).
        DebridSearchFixture.forgetPlays(container.library)
        saved = settings.settings.first()
        secrets = saved.activeProviders.mapNotNull { p -> settings.provider(p.id)?.password?.let { p.passwordSecret to it } }.toMap() +
            saved.activeIndexers.mapNotNull { i -> settings.indexer(i.id)?.apiKey?.let { i.apiKeySecret to it } }.toMap()
        saved.activeProviders.forEach { settings.deleteProvider(it.id) }
        saved.activeIndexers.forEach { settings.deleteIndexer(it.id) }
        // Hidden, not removed: removing would take their keys from the secret store.
        settings.restore(settings.settings.first().let { s -> s.copy(services = s.services.map { it.copy(deleted = true) }, torrentIndexers = s.torrentIndexers.map { it.copy(deleted = true) }) })
        fakeBack = DebridFakes.holdTheFake()
        DebridFakes.control("reset")
        endpointsBack = DebridFakes.pointServicesAtTheFake()
        // The TV's debrid way, set up: the fake's key (its sign-in facts invented, as nothing needs asking) and its feed.
        settings.saveService(ServiceKind.TorBox.name, DebridFakes.TORBOX_KEY) { ServiceSignIn(DebridFakes.TORBOX_ACCOUNT) }
        settings.saveTorrentIndexer(null, "Home Prowlarr", DebridFakes.PROWLARR_URL, DebridFakes.TORZNAB_KEY)
        // Home shows its content: the TV's torrent pair is its setup done (First run's other way).
        phones.clear()
        instrumentation.setInTouchMode(false)
        PlayerActivity.lookUpSegments = { _, _, _ -> emptyList() }
        // No switch of the TV's frame rate to wait on.
        PlayerActivity.readPlayback = { realRead(it).copy(matchFrameRate = false) }
        onMainThread { container.playback.withdraw() }
    }

    /** Each step whatever the one before it did: a fake that stopped answering mustn't leave the TV's sources away. */
    @After
    fun tearDown() = runBlocking<Unit> {
        if (!set) return@runBlocking
        everyStep(
            {
                linked.forEach { it.close() }
                // Touch mode outlasts the test's process.
                instrumentation.setInTouchMode(true)
                PlayerActivity.lookUpSegments = realLookUp
                PlayerActivity.readPlayback = realRead
                onMainThread { container.playback.cancel() }
                live<PlayerActivity>(Stage.RESUMED).forEach { onMainThread { it.finish() } }
            },
            { phones.restore() },
            { if (fakeBack != null) DebridFakes.control("reset") },
            { fakeBack?.invoke() },
            { endpointsBack?.invoke() },
            { putBack() },
        )
    }

    /** The TV's sources as they were, keys included, and nothing of this class's play remembered. */
    private suspend fun putBack() {
        if (!::saved.isInitialized) return
        settings.providers.first().filter { p -> saved.providers.none { it.id == p.id } }.forEach { settings.deleteProvider(it.id) }
        settings.indexers.first().filter { i -> saved.indexers.none { it.id == i.id } }.forEach { settings.deleteIndexer(it.id) }
        settings.services.first().filter { s -> saved.services.none { it.id == s.id } }.forEach { settings.signOut(it.id) }
        settings.torrentIndexers.first().filter { t -> saved.torrentIndexers.none { it.id == t.id } }.forEach { settings.deleteTorrentIndexer(it.id) }
        settings.restore(saved)
        secrets.forEach { (name, value) -> container.data.secrets.put(name, value) }
        assertEquals(saved, settings.settings.first())
        DebridSearchFixture.forgetPlays(container.library)
        // The app's own provider sync puts the engine back on the restored providers; let it before the next test.
        delay(1_000)
    }

    private inline fun <reified A : Activity> live(stage: Stage): List<A> =
        onMainThread { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage).filterIsInstance<A>() }

    /** What the player in front says it plays, once [done] holds. */
    private suspend fun now(what: String, timeoutMs: Long = 45_000, done: (NowPlaying) -> Boolean): NowPlaying =
        waitFor(what, timeoutMs) { container.playerInFront.now.value?.takeIf(done) }

    /** The player in front's position, as the test reads it on the main thread. */
    private fun position(): Long = live<PlayerActivity>(Stage.RESUMED).single().let { player -> onMainThread { player.player!!.currentPosition } }

    @Test
    fun aPhonesPlayStartsFromTheTvsDebridServiceAndStops() = runBlocking<Unit> {
        pages.launch().use {
            val galaxy = LinkedPhone("Invented phone", pages, phones).also { linked += it }
            // It can play, from its service: no Usenet provider or indexer, and nothing lacking.
            val ready = galaxy.hello.ready!!
            assertTrue("the hello's readiness: $ready", ready.debrid && !ready.provider && !ready.indexer && ready.needs == null)
            // The service slow to answer (1.5 s a call), so Preparing and Opening each outlast the pace a phone hears statuses
            // at (LinkLimits.statusPerSecond): a step shorter than that is folded into the status after it (StatusPump, L24),
            // and the phone would never hear it.
            DebridFakes.control("slow?ms=1500")

            val session = galaxy.session(galaxy.ask(Play(1, movie = Play.Movie(DebridSearchFixture.COPPER_CROWN, "Copper Crown", 2019, "tt9000012", 104))))
            val playing = waitFor("the TV playing the phone's play", 120_000) { galaxy.statuses().firstOrNull { it.session == session && it.state == Playback.Playing } }
            val said = galaxy.statuses().filter { it.session == session }
            fun firstSaid(what: (Status) -> Boolean): Int = said.indexOfFirst(what).also { assertTrue("never said: ${said.map { it.state to it.sheet }}", it >= 0) }
            val searching = firstSaid { it.sheet?.kind == SheetKind.Searching }
            assertEquals("the rows its own sheet lists", listOf(SheetStep.Prepare, SheetStep.Open, SheetStep.ProbeTracks), said[searching].sheet!!.rows)
            val preparing = firstSaid { s -> s.sheet?.kind == SheetKind.Running && s.sheet!!.steps.any { it.step == SheetStep.Prepare } }
            val opening = firstSaid { s -> s.sheet?.kind == SheetKind.Running && s.sheet!!.steps.any { it.step == SheetStep.Open } }
            assertTrue("in order: Searching $searching, Preparing $preparing, Opening $opening, Playing", searching < preparing && preparing <= opening && opening < said.indexOf(playing))
            assertTrue("a Usenet step on a debrid play", said.none { s -> s.sheet?.steps.orEmpty().any { it.step == SheetStep.FetchNzb || it.step == SheetStep.CheckAvailability } })
            // What a phone's sheet lists of it (CWR-1, CWD-1): the service's rows before the TV says anything, as its hello
            // says it plays from its service alone, and on every Running sheet, one that names no step yet included.
            val service = listOf(PipelineStep.Prepare, PipelineStep.Open, PipelineStep.ProbeTracks)
            assertTrue("plays from its service alone, by its hello", TvReach.fromService(ready))
            var kept = rowsBefore(TvReach.fromService(ready))
            assertEquals("before the TV said anything", service, mirroredRows(emptyMap(), kept))
            for (status in said.filter { it.state == Playback.Starting }) {
                kept = rowsAfter(kept, status.sheet)
                val sheet = status.sheet?.takeIf { it.kind == SheetKind.Running } ?: continue
                assertEquals("the rows beside ${sheet.steps.map { it.step }}", service, mirroredRows(mirroredSteps(sheet), kept))
            }

            // Pause and SeekBy act on the TV's player.
            now("a second in") { it.remote?.session == session && it.positionMs >= 1_000 }
            assertEquals(LinkMessage.Ack(2, session), galaxy.ask(Pause(2, session)))
            now("paused") { it.remote?.session == session && it.state == Playback.Paused }
            val before = position()
            assertEquals(LinkMessage.Ack(3, session), galaxy.ask(SeekBy(3, session, 30_000)))
            waitFor("30 s on") { position().takeIf { it >= before + 25_000 } }

            // Stop leaves the player, and the phone gets where it stopped, for the film.
            val player = live<PlayerActivity>(Stage.RESUMED).single()
            assertEquals(LinkMessage.Ack(4, session), galaxy.ask(Stop(4, session)))
            waitFor("the player gone") { player.isDestroyed.takeIf { it } }
            assertNull(container.playerInFront.now.value)
            val final = waitFor("the Final on the phone's link") { galaxy.heard.filterIsInstance<LinkMessage.Final>().firstOrNull() }
            assertEquals(session, final.session)
            assertEquals(WatchKey.movie(DebridSearchFixture.COPPER_CROWN), final.watchKey)
            assertEquals(FinalKind.Movie to DebridSearchFixture.COPPER_CROWN, final.kind to final.movieTmdbId)
            galaxy.link.send(LinkMessage.FinalAck(listOf(final.id)))
            waitFor("the outbox empty") { runBlocking { container.data.link.owed(galaxy.key.id) }.takeIf { it.isEmpty() } }

            // Nothing the phone heard names the TV's service, its key, a hash, a magnet or the CDN, in the bytes it read.
            val heard = (listOf<LinkMessage>(galaxy.hello) + galaxy.heard).map { String(LinkCodec.encode(it)!!) }
            for (secret in listOf(DebridFakes.TORBOX_KEY, DebridFakes.TORBOX_ACCOUNT, "TorBox", "Premiumize", "magnet:", "btih", "/cdn/", "${DebridFakes.HOST}:${DebridFakes.DEBRID_PORT}", "/torbox/")) {
                assertFalse("the phone heard \"$secret\"", heard.any { secret in it })
            }
            assertFalse("a hash crossed", heard.any { HASH.containsMatchIn(it) })
            // And the fake saw nothing the app must never send: no magnet with more than its hash (no tr= or dn=).
            val problems = JSONObject(DebridFakes.control("counts")).getJSONObject("problems")
            problems.keys().forEach { assertEquals("the fake saw: $it", 0, problems.getInt(it)) }
        }
    }

    private companion object {
        /** A v1 info hash as the app and the fakes write one. */
        val HASH = Regex("\\b[0-9a-fA-F]{40}\\b")
    }
}
