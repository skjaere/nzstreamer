package io.skjaere.usenet.app

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.compressionutils.generation.ArchiveFile
import io.skjaere.compressionutils.generation.ArchiveGenerator
import io.skjaere.compressionutils.generation.ContainerType
import io.skjaere.usenet.app.collection.model.shortDate
import io.skjaere.usenet.app.play.PlayRequest
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.app.search.ReleaseList
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.core.AttemptFailure
import io.skjaere.usenet.core.DiscKind
import io.skjaere.usenet.core.FilePicker
import io.skjaere.usenet.core.PipelineStep
import io.skjaere.usenet.core.PrepareOutcome
import io.skjaere.usenet.core.StepStatus
import io.skjaere.usenet.core.discCopy
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.ProviderRecord
import io.skjaere.usenet.data.TriedRelease
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.logging.LogBuffer
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.newznab.NzbDownload
import io.skjaere.usenet.releases.RankedRelease
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.search.GroupKind
import io.skjaere.usenet.search.RankedItem
import io.skjaere.usenet.search.ResultGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A copy of a Blu-ray disc and a file no extractor reads (W103, W104), over the real coordinator, engine, prepare, probe and
 * library: three invented releases of Copper Crown in one request, posted on a server in the test's process ([LocalNntp])
 * and handed over as their indexer would ([PlaybackCoordinator.fetchNzb]), so no shared server and no indexer is asked.
 * - LONE: one .m2ts of a disc's packets posted on its own, with no navigation file beside it: no disc copy (L310), so it is
 *   opened and probed, and no extractor takes it. Its probe's row says so in plain words; Media3's go to the log alone.
 * - DRIFT: a UHD disc's BDMV folder in a RAR5 set (kotlin-compression-utils' ArchiveGenerator), its folders kept (L326):
 *   its Opening fails as a disc copy, with no probe, and it is remembered (L314). A second play skips it without a grab,
 *   and the release list marks it with its chip.
 * - TIDE: the device tests' chapters.mkv, which plays: the launch is taken here, and no player opens.
 *
 * The saved providers are hidden for the test (tombstoned, their secrets untouched), and the app's own provider sync puts
 * the engine on the test's server, saved as a provider of its own; one indexer, the test's, at an address nothing
 * answers, the others switched off; Releases to try is 3. The settings and the releases remembered before are put back as
 * they were, the test's provider and indexer deleted with their secrets.
 */
@RunWith(AndroidJUnit4::class)
class DiscCopyDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val container = instrumentation.targetContext.container
    private val settings = container.settings
    private val library = container.library
    private val local = LocalNntp()
    private val realFetch = PlaybackCoordinator.fetchNzb

    /** The releases the coordinator grabbed, by guid. */
    private val grabbed = CopyOnWriteArrayList<String>()

    /** Every state of the play sheet, in order: collected where it is set, so a step's row between two attempts isn't missed. */
    private val states = CopyOnWriteArrayList<PlayState>()
    private val watching = CoroutineScope(Dispatchers.Unconfined)
    private lateinit var saved: AppSettings
    private lateinit var remembered: List<TriedRelease>

    /** Each release's NZB, by guid, as its indexer would hand it over. */
    private val nzbs = mutableMapOf<String, ByteArray>()
    private lateinit var lone: RankedItem
    private lateinit var drift: RankedItem
    private lateinit var tide: RankedItem

    @Before
    fun setUp() = runBlocking<Unit> {
        saved = settings.settings.first()
        remembered = library.triedReleaseEntries()
        library.clearTriedReleases()
        // Hidden, not deleted: deleting would remove their passwords from the secret store.
        settings.restore(saved.copy(providers = saved.providers.map { it.copy(deleted = true) }, indexers = saved.indexers.map { it.copy(enabled = false) }))
        settings.updatePlayback { it.copy(releasesToTry = 3) }
        val server = settings.saveProvider(ProviderRecord("", "Local (disc copy test)", "127.0.0.1", local.provider.port, useTls = false, connections = 4), "")
        settings.saveIndexer(INDEXER, "Unanswered (disc copy test)", "http://10.0.2.2:9", "disc-copy-test-key")
        engineSetup("the engine on the test's own NNTP server") { container.engine.configuredProviders.first { it == listOf(server) } }

        val stream = local.post("$LONE.m2ts", discStream(1_500), PART, "disc-copy-lone")
        lone = item(LONE_GUID, LONE, stream.data.size.toLong()).also { nzbs[LONE_GUID] = local.nzb(stream) }
        val volumes = ArchiveGenerator.generate(DISC.map { (path, data) -> ArchiveFile(path, data) }, VOLUME, ContainerType.RAR5)
            .mapIndexed { i, volume -> local.post("$DRIFT.part${i + 1}.rar", volume.data, PART, "disc-copy-drift-${i + 1}") }
        drift = item(DRIFT_GUID, DRIFT, volumes.sumOf { it.data.size.toLong() }).also { nzbs[DRIFT_GUID] = local.nzb(*volumes.toTypedArray()) }
        val film = local.post("$TIDE.mkv", instrumentation.context.assets.open("chapters.mkv").use { it.readBytes() }, PART, "disc-copy-tide")
        tide = item(TIDE_GUID, TIDE, film.data.size.toLong()).also { nzbs[TIDE_GUID] = local.nzb(film) }

        PlaybackCoordinator.fetchNzb = { _, _, item -> grabbed += item.guid; NzbDownload(nzbs.getValue(item.guid)) }
        watching.launch { container.playback.state.collect { states += it } }
    }

    @After
    fun restore() = runBlocking<Unit> {
        watching.cancel()
        PlaybackCoordinator.fetchNzb = realFetch
        container.playback.cancel()
        local.close()
        settings.providers.first().filter { p -> saved.providers.none { it.id == p.id } }.forEach { settings.deleteProvider(it.id) }
        settings.settings.first().providers.forEach { container.providerTests.forget(it.id) }
        if (saved.indexers.none { it.id == INDEXER } && settings.indexer(INDEXER) != null) settings.deleteIndexer(INDEXER)
        settings.restore(saved)
        assertEquals(saved, settings.settings.first())
        library.restoreTriedReleases(remembered)
        // The app's own provider sync puts the engine back on the restored providers; let it before the next test.
        engineOnSavedProviders()
    }

    @Test
    fun aDiscCopyFailsAtOnceInPlainWordsIsRememberedAndTheNextPlaySkipsIt() = runBlocking<Unit> {
        // The disc's folders survive the RAR5 set and prepare (L326): the listing the rule reads holds them.
        val outcome = engineSetup("$DRIFT prepared") { container.preparer.prepare(nzbs.getValue(DRIFT_GUID), DRIFT) }
        val listing = (outcome as? PrepareOutcome.Ready)?.nzb ?: throw AssertionError("$DRIFT didn't prepare: $outcome")
        assertEquals(DISC.map { it.first }, listing.candidates.map { it.path })
        assertEquals(AttemptFailure.DiscCopy(DiscKind.BluRay), listing.discCopy())

        val since = LogBuffer.snapshot().lastOrNull()?.seq ?: 0
        container.playback.play(request(lone, drift, tide))
        assertEquals(tide.key, played().release.key)
        assertEquals(listOf(LONE_GUID, DRIFT_GUID, TIDE_GUID), grabbed)
        val running = states.filterIsInstance<PlayState.Running>()
        // LONE was opened and probed, and its row says why it failed in plain words; the notice names its type (W104).
        assertTrue("$running", running.any { it.release == LONE && failed(it, PipelineStep.ProbeTracks) == "The player can't read this M2TS file" })
        assertTrue("$running", running.any { it.release == DRIFT && it.notice == "M2TS file can't be read · trying release 2 of 3" })
        // DRIFT failed at Opening as a disc copy, never probed (W103, L311).
        assertTrue("$running", running.any { it.release == DRIFT && failed(it, PipelineStep.Open) == DISC_WORDS })
        assertTrue("$running", running.none { it.release == DRIFT && PipelineStep.ProbeTracks in it.steps })
        assertTrue("$running", running.any { it.release == TIDE && it.notice == "Blu-ray disc copy · trying release 3 of 3" })
        // Media3's own words: one line in the log, naming no address (L320, L331).
        val lines = LogBuffer.snapshot().filter { it.seq > since && it.message.startsWith("track_probe: can't read the file") }.map { it.message }
        assertEquals("$lines", 1, lines.size)
        assertTrue(lines.single(), lines.single().startsWith("track_probe: can't read the file (ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED): UnrecognizedInputFormatException: "))
        assertFalse(lines.single(), "://" in lines.single())
        // DRIFT is remembered as a disc copy; a file the player can't read isn't remembered (L319).
        val tried = library.triedReleases(listOf(lone.key, drift.key))
        assertEquals(setOf(drift.key), tried.keys)
        val discAt = tried.getValue(drift.key).discAt ?: throw AssertionError("$DRIFT remembered, but not as a disc copy: ${tried.getValue(drift.key)}")

        // The next play skips DRIFT without a grab and without counting it. Listed first, it is skipped before any
        // attempt, where the sheet's notice says so; after one, the next attempt's notice names that one's failure instead.
        settled()
        grabbed.clear()
        states.clear()
        container.playback.play(request(drift, lone, tide))
        assertEquals(tide.key, played().release.key)
        assertEquals("the disc copy was grabbed again", listOf(LONE_GUID, TIDE_GUID), grabbed)
        val again = states.filterIsInstance<PlayState.Running>()
        assertTrue("$again", again.any { it.release == LONE && it.attempt == 1 && it.of == 2 && it.notice == "Skipped 1 release that is a disc copy" })
        assertTrue("$again", again.none { it.release == DRIFT })

        // The release list marks DRIFT alone, with the day it was found one.
        settled()
        compose.setContent { AppTheme { ReleaseList(ResultGroup("copper crown|movie:2019", GroupKind.Movie, null, emptyList(), null, 2019, listOf(lone, drift, tide), emptyList()), "Copper Crown", onBack = {}, onPlay = {}) } }
        val chip = "Disc copy · " + shortDate(Instant.ofEpochMilli(discAt).atZone(ZoneId.systemDefault()).toLocalDate(), LocalDate.now())
        compose.waitUntil(10_000) { exists(hasText(chip)) }
        val marked = compose.onAllNodes(hasText(chip) and hasClickAction()).fetchSemanticsNodes()
        assertEquals(listOf(DRIFT), marked.map { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }.first { it.startsWith("Copper.Crown") } })
        assertEquals(1, compose.onAllNodes(hasText("Disc copy", substring = true)).fetchSemanticsNodes().size)
    }

    /** The words a failed [step] of [state]'s release shows on its row; null while it hasn't failed. */
    private fun failed(state: PlayState.Running, step: PipelineStep): String? = (state.steps[step] as? StepStatus.Failed)?.failure?.text

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    /** Until the play sheet is idle again after a launch, and the states collected here have caught up with it. */
    private suspend fun settled() = withTimeout(10_000) {
        container.playback.state.first { it == PlayState.Idle }
        while (states.lastOrNull() != PlayState.Idle) delay(20)
    }

    /** As a release list's play of the film asks, played as a release (no TMDB), its releases in this order, no ranker's. */
    private fun request(vararg candidates: RankedItem) =
        PlayRequest("Copper Crown", "2019", candidates.toList(), FilePicker.Largest, WatchTarget(WatchKind.Release, candidates.first().item.title))

    /** The coordinator's next launch, or a failure as soon as the play sheet shows one. */
    private suspend fun played(): PlayerLaunch = withTimeout(90_000) {
        merge(
            container.playback.launches,
            container.playback.state.filter { it is PlayState.Failed }.map { throw AssertionError("didn't start: $it") },
        ).first()
    }

    private fun item(guid: String, title: String, size: Long) = RankedItem(
        NewznabItem(
            indexerId = INDEXER, guid = guid, title = title, sizeBytes = size, publishedAt = null, postedAt = null,
            categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null,
            tvmazeId = null, season = null, episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
        ),
        RankedRelease(ReleaseFacts("$INDEXER/$guid", title, size), ReleaseParser.parse(title), playable = true, score = 1.0, reasons = emptyList(), estimatedBitrate = null),
    )

    private companion object {
        const val INDEXER = "disc-copy-device-test"
        const val LONE = "Copper.Crown.2019.2160p.BluRay-LONE"
        const val DRIFT = "Copper.Crown.2019.2160p.UHD.BluRay.HEVC.TrueHD.7.1-DRIFT"
        const val TIDE = "Copper.Crown.2019.1080p.WEB-DL.H.264-TIDE"
        const val LONE_GUID = "cc-2019-lone"
        const val DRIFT_GUID = "cc-2019-drift"
        const val TIDE_GUID = "cc-2019-tide"
        const val DISC_WORDS = "A copy of a Blu-ray disc: NZStreamer plays single video files"

        /** Each file posted in parts of this many bytes. */
        const val PART = 32_000

        /** The RAR set's volumes, of this many bytes at most. */
        const val VOLUME = 100_000L

        /**
         * [packets] of a Blu-ray stream as a disc lays them out: 192 bytes each, a 4-byte header, then a TS packet's sync
         * byte (0x47) and zeros. No extractor takes them: Media3's TS reader looks for 188-byte packets.
         */
        fun discStream(packets: Int) = ByteArray(packets * 192) { i -> (if (i % 192 == 4) 0x47 else 0).toByte() }

        /** A navigation file of [size] bytes, starting with its type's own tag as a disc's does. */
        fun navigation(tag: String, size: Int) = tag.toByteArray() + ByteArray(size - tag.length)

        /** The disc's files in their folders under the release's, as the RAR set holds them: a few KB to navigate by, and its stream. */
        val DISC = listOf(
            "$DRIFT/BDMV/index.bdmv" to navigation("INDX0200", 1_024),
            "$DRIFT/BDMV/PLAYLIST/00800.mpls" to navigation("MPLS0200", 2_048),
            "$DRIFT/BDMV/CLIPINF/00001.clpi" to navigation("HDMV0200", 3_072),
            "$DRIFT/BDMV/STREAM/00001.m2ts" to discStream(1_600),
        )
    }
}
