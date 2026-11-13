package io.skjaere.usenet.app.play

import io.skjaere.nzbstreamer.metadata.ExtractedMetadata
import io.skjaere.nzbstreamer.metadata.PrepareResult
import io.skjaere.nzbstreamer.stream.StreamableFile
import io.skjaere.usenet.core.EngineAvailabilityBackend
import io.skjaere.usenet.core.EngineSettings
import io.skjaere.usenet.core.MissingOn
import io.skjaere.usenet.core.NzbPreparer
import io.skjaere.usenet.core.NzbSource
import io.skjaere.usenet.core.PipelineEvent
import io.skjaere.usenet.core.PlayCandidate
import io.skjaere.usenet.core.PlayPipeline
import io.skjaere.usenet.core.PrepareBackend
import io.skjaere.usenet.core.PreparedNzbStore
import io.skjaere.usenet.core.ProviderConfig
import io.skjaere.usenet.core.TriedReleases
import io.skjaere.usenet.core.UsenetEngine
import io.skjaere.usenet.core.UsenetError
import io.skjaere.usenet.core.UsenetSource
import io.skjaere.usenet.data.KnownMissing
import io.skjaere.usenet.data.ReleaseChecks
import io.skjaere.usenet.data.TriedRelease
import io.skjaere.usenet.newznab.NewznabItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.TestTimeSource

/**
 * The play's side of the releases found missing: what the pipeline is told to skip, what it records
 * (the providers the engine uses at that moment, by settings id and server), and how the play sheet,
 * the release list and the log say it.
 */
class MissingReleasesTest {
    /** Where a play keeps what plays: the coordinator does once it plays, which these plays don't reach. */
    @TempDir
    lateinit var nzbDir: File

    private val day = 24 * 60 * 60 * 1000L
    private var now = 100 * day
    private val a = ProviderConfig("p-a", "News A", "news.example.com", 563, "", "", useTls = true)
    private val b = ProviderConfig("p-b", "", "eu.example.net", 443, "", "", useTls = true)
    private val keyA = ReleaseChecks.provider("p-a", "news.example.com", 563)
    private val keyB = ReleaseChecks.provider("p-b", "eu.example.net", 443)
    private var inUse = listOf(a, b)

    /** The library's table, as the repository keeps it; [observe] comes again after each write, as Room's does. */
    private inner class FakeStore : MissingReleases.Store {
        val entries = mutableMapOf<String, TriedRelease>()
        var reads = 0
        private val writes = MutableStateFlow(0)

        override suspend fun get(ids: Collection<String>): Map<String, TriedRelease> {
            reads++
            return ids.mapNotNull { entries[it] }.associateBy { it.id }
        }

        override fun observe(ids: Collection<String>): Flow<Map<String, TriedRelease>> = writes.map { ids.mapNotNull { entries[it] }.associateBy { it.id } }

        override suspend fun record(indexerId: String, guid: String, providers: Collection<String>, incomplete: Boolean) {
            val id = "$indexerId/$guid"
            entries[id] = ReleaseChecks.record(entries[id], indexerId, guid, providers, incomplete, now)
            writes.value++
        }

        override suspend fun found(indexerId: String, guid: String, providers: Collection<String>) {
            val id = "$indexerId/$guid"
            val kept = entries[id]?.let { ReleaseChecks.found(it, providers, now) }
            if (kept == null) entries -= id else entries[id] = kept
            writes.value++
        }

        override suspend fun recordPasswordNeeded(indexerId: String, guid: String) {
            val id = "$indexerId/$guid"
            entries[id] = ReleaseChecks.record(entries[id], indexerId, guid, emptyList(), incomplete = false, now, needsPassword = true)
            writes.value++
        }

        override suspend fun recordDiscCopy(indexerId: String, guid: String) {
            val id = "$indexerId/$guid"
            entries[id] = ReleaseChecks.record(entries[id], indexerId, guid, emptyList(), incomplete = false, now, discCopy = true)
            writes.value++
        }
    }

    private val store = FakeStore()
    private val missing = MissingReleases(store, inUse = { inUse }, checkedInUse = { inUse }, now = { now }, zone = { ZoneOffset.UTC })

    private fun item(guid: String, indexer: String = "fake", title: String = "Harbor.Lights.S01E01.PROPER.1080p.WEB-DL.AAC2.0.H.264-BROKEN") = NewznabItem(
        indexerId = indexer, guid = guid, title = title, sizeBytes = 1_000_000_000, publishedAt = null, postedAt = null,
        categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null,
        tvmazeId = null, season = null, episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
    )

    private fun NewznabItem.candidate() = PlayCandidate("$indexerId/$guid", title, indexerId)

    private val broken = item("hl-s01e01-broken")

    private fun play(vararg items: NewznabItem, chosen: Boolean = false) =
        missing.forPlay(items.associateBy { "${it.indexerId}/${it.guid}" }, chosen) { id -> if (id == "fake") "Fake" else null }

    /**
     * Recorded for the providers in use at that moment, by settings id and server; skipped while they are the ones in use.
     * One left out then wasn't asked, so it is tried once it is back, and the result recorded for it too.
     */
    @Test
    fun `a release found missing is remembered for the providers in use, and skipped while they are the ones in use`() = runTest {
        // b left out: a alone answered.
        inUse = listOf(a)
        play(broken).missing(broken.candidate(), MissingOn.ProvidersInUse)
        assertEquals(listOf(keyA), store.entries.values.single().missingOn.map { it.provider })
        now += 3 * day
        assertEquals("Skipped: missing on your providers (checked 3 days ago)", play(broken).knownMissing(broken.candidate()))
        // b is back: the release is tried, and found missing on both.
        inUse = listOf(a, b)
        assertNull(play(broken).knownMissing(broken.candidate()))
        play(broken).missing(broken.candidate(), MissingOn.ProvidersInUse)
        assertEquals(listOf(keyA, keyB), store.entries.values.single().missingOn.map { it.provider })
        assertEquals("Skipped: missing on your providers (checked today)", play(broken).knownMissing(broken.candidate()))
        // a edited to another server is another provider.
        inUse = listOf(a.copy(host = "eu.example.com"), b)
        assertNull(play(broken).knownMissing(broken.candidate()))
    }

    /**
     * A gap found fetching articles (opening the release, probing or playing it) is on the providers fetched from, which
     * nzb-streamer doesn't name: with several in use it passes over one whose circuit breaker is open without asking it.
     * So it is remembered only while one provider is in use, which was asked; with several, nothing is.
     */
    @Test
    fun `a gap found fetching is remembered while one provider is in use, not while several are`() = runTest {
        play(broken).missing(broken.candidate(), MissingOn.ProvidersFetchedFrom)
        missing.record(broken.indexerId, broken.guid, broken.title, "Fake", MissingOn.ProvidersFetchedFrom, asked = listOf(a, b))
        assertEquals(emptyMap(), store.entries)
        inUse = listOf(a)
        play(broken).missing(broken.candidate(), MissingOn.ProvidersFetchedFrom)
        assertEquals(listOf(keyA), store.entries.values.single().missingOn.map { it.provider })
        assertEquals("Skipped: missing on your providers (checked today)", play(broken).knownMissing(broken.candidate()))
        // b in use again: it may have them.
        inUse = listOf(a, b)
        assertNull(play(broken).knownMissing(broken.candidate()))
    }

    /**
     * What is remembered is what was asked: the providers in use when the release was looked up before its attempt (or, for
     * a gap found playing, as the player started), while they are still the ones in use. With others in use since (one
     * added or edited in Settings while the check ran on the streamer before, one left out or back), which were asked isn't
     * known, so nothing is. One renamed is the same provider.
     */
    @Test
    fun `a release found missing while the providers in use changed isn't remembered`() = runTest {
        val c = ProviderConfig("p-c", "News C", "news.example.org", 563, "", "", useTls = true)
        listOf(listOf(a, c), listOf(a.copy(host = "eu.example.com")), emptyList(), listOf(b)).forEach { since ->
            inUse = listOf(a)
            val play = play(broken)
            assertNull(play.knownMissing(broken.candidate()))
            inUse = since
            play.missing(broken.candidate(), MissingOn.ProvidersInUse)
            assertEquals(emptyMap(), store.entries, "in use since: $since")
        }
        inUse = listOf(a, b)
        missing.record(broken.indexerId, broken.guid, broken.title, "Fake", MissingOn.ProvidersFetchedFrom, asked = listOf(a))
        assertEquals(emptyMap(), store.entries)

        inUse = listOf(a)
        val play = play(broken)
        assertNull(play.knownMissing(broken.candidate()))
        inUse = listOf(a.copy(name = "Renamed"))
        play.missing(broken.candidate(), MissingOn.ProvidersInUse)
        assertEquals(listOf(keyA), store.entries.values.single().missingOn.map { it.provider })
    }

    /**
     * TR-11: the engine names every configured provider in use until it has checked them, which it does as it builds its
     * streamer: after the process starts, after the providers change. The release list marks a release by the providers
     * that check leaves in use, and a play looks it up by them: the coordinator has the engine check them as the play
     * begins, before its first attempt has used the streamer. One back since it was left out is seen once its recheck is
     * due, before a play that only skips has used the streamer. A play's lookups don't check them themselves (the next test
     * says why): one under way as the recheck comes due goes on with the providers it began with. Over the engine itself,
     * as the app wires it; the streamers it builds open no connection.
     */
    @Test
    fun `a release is looked up against the providers the engine has checked, before anything has used it`() = runTest {
        val time = TestTimeSource()
        var bRejected = true
        val rejected = UsenetError.AuthFailed("AUTHINFO PASS failed: 481 Authentication failed")
        UsenetEngine(probe = { p -> rejected.takeIf { p.id == b.id && bRejected } }, timeSource = time).use { engine ->
            val missing = MissingReleases(store, inUse = { engine.inUse }, checkedInUse = engine::checkedInUse, now = { now }, zone = { ZoneOffset.UTC })
            val other = item("hl-s01e01-other")

            /** A play of [broken] and [other], begun as the coordinator begins one: once the engine has checked its providers. */
            suspend fun play(): TriedReleases {
                engine.checkedInUse()
                return missing.forPlay(listOf(broken, other).associateBy { it.key }, chosen = false) { "Fake" }
            }
            // Both found missing on a while b was left out.
            listOf(broken, other).forEach { store.record(it.indexerId, it.guid, listOf(keyA), incomplete = false) }
            // A new process: b is configured, and not checked yet.
            engine.configure(EngineSettings(listOf(a, b.copy(priority = 1))))
            assertEquals(setOf(broken.key), missing.known(listOf(broken.key)).first().keys)
            val begun = play()
            assertEquals("Skipped: missing on your providers (checked today)", begun.knownMissing(broken.candidate()))
            // b's account renewed: a play begun once b's recheck is due tries them, with nothing else having used the streamer.
            bRejected = false
            time += UsenetEngine.RECHECK_INTERVAL
            assertEquals("Skipped: missing on your providers (checked today)", begun.knownMissing(other.candidate()))
            assertNull(play().knownMissing(broken.candidate()))
            assertEquals(emptyMap(), missing.known(listOf(broken.key)).first())
        }
    }

    /**
     * TR-11: the pipeline looks releases up to count them too, before each attempt and once the attempts run out, and the
     * sheet waits on those lookups: on the last release's steps, with no failure and no buttons. A use that failed, or took as
     * long as one does while a provider holds it up, has the engine check its providers again ([UsenetEngine.recheckProviders]),
     * logging in to each (seconds for one out of reach): at its next use, not at a lookup, so the failure shows at once.
     * Here the check takes longer than one does unless a provider holds it up, and opening the release finds an article
     * gone. Over the engine, as the app wires it: the streamers it builds open no connection, and the release's NZB holds
     * only a recovery file, which the check leaves out.
     */
    @Test
    fun `the lookups that count the releases left leave the providers' recheck to the next use`() = runTest {
        val time = TestTimeSource()
        val probes = AtomicInteger()
        val builds = AtomicInteger()
        val engine = UsenetEngine(
            factory = { s -> builds.incrementAndGet(); UsenetEngine.defaultStreamer(s) },
            probe = { probes.incrementAndGet(); null },
            timeSource = time,
        )
        engine.use {
            val missing = MissingReleases(store, inUse = { engine.inUse }, checkedInUse = engine::checkedInUse, now = { now }, zone = { ZoneOffset.UTC })
            val releases = (1..3).map { item("hl-s01e01-$it") }
            val availability = EngineAvailabilityBackend(engine, checkAll = { time += EngineAvailabilityBackend.SLOW_CHECK * 2; false }, timeSource = time)
            val preparer = NzbPreparer(
                object : PrepareBackend {
                    override suspend fun prepare(nzbBytes: ByteArray, password: String?) = PrepareResult.MissingArticles("Article <a8f3c1@fake> not found", IOException("430"))
                    override suspend fun streamableFiles(metadata: ExtractedMetadata) = emptyList<StreamableFile>()
                },
                dispatcher = Dispatchers.Unconfined,
            )
            val usenet = UsenetSource(NzbSource { recoveryOnly }, availability, preparer, PreparedNzbStore(nzbDir))
            val pipeline = PlayPipeline(
                { usenet }, maxAttempts = 1, dispatcher = Dispatchers.Unconfined,
                tried = missing.forPlay(releases.associateBy { it.key }, chosen = false) { "Fake" },
            )
            engine.configure(EngineSettings(listOf(a, b.copy(priority = 1))))
            // As the coordinator begins a play: the providers checked first.
            assertEquals(listOf(a, b).map { it.id }, engine.checkedInUse().map { it.id })
            val end = assertIs<PipelineEvent.Exhausted>(pipeline.play(releases.map { it.candidate() }).last())
            assertEquals(2, end.notTried)
            assertEquals(1 to 2, builds.get() to probes.get(), "builds to probes")
            // The next use checks them.
            engine.streamer()
            assertEquals(2 to 4, builds.get() to probes.get(), "builds to probes")
        }
    }

    /** An NZB of one PAR2 recovery file, which the availability check leaves out: it asks no provider anything. */
    private val recoveryOnly = """<?xml version="1.0" encoding="UTF-8"?>
        |<nzb xmlns="http://www.newzbin.com/DTD/2003/nzb"><file poster="p" date="0" subject="&quot;a8f3c1.vol00+01.par2&quot; yEnc (1/1)">
        |<groups><group>alt.binaries.fake</group></groups><segments><segment bytes="10" number="1">a8f3c1.par2@fake</segment></segments></file></nzb>
    """.trimMargin().encodeToByteArray()

    @Test
    fun `an NZB that lacks files is remembered whatever the providers`() = runTest {
        play(broken).missing(broken.candidate(), MissingOn.AnyProvider)
        now += day
        inUse = listOf(ProviderConfig("p-new", "New", "new.example.org", 563, "", "", useTls = true))
        assertEquals("Skipped: incomplete NZB (checked yesterday)", play(broken).knownMissing(broken.candidate()))
    }

    /**
     * A release that needed a password it didn't come with is remembered, whatever the providers: a later play skips it
     * without a grab, the release list marks it, and one chosen by hand is tried all the same. An imported NZB isn't.
     */
    @Test
    fun `a release that needed a password is remembered and skipped, unless chosen`() = runTest {
        val passwd = item("hl-s01e01-passwd", title = "Harbor.Lights.S01E01.1080p.BluRay.x264-PASSWD")
        play(passwd).passwordNeeded(passwd.candidate())
        now += 3 * day
        inUse = listOf(ProviderConfig("p-new", "New", "new.example.org", 563, "", "", useTls = true))
        assertEquals("Skipped: needs a password (checked 3 days ago)", play(passwd).knownMissing(passwd.candidate()))
        assertNull(play(passwd, chosen = true).knownMissing(passwd.candidate()))
        assertEquals(mapOf(passwd.key to KnownMissing(now - 3 * day, incompleteNzb = false, passwordNeeded = true)), missing.known(listOf(passwd.key)).first())
        // One that plays (a password came with it since) is forgotten.
        play(passwd, chosen = true).found(passwd.candidate())
        assertNull(play(passwd).knownMissing(passwd.candidate()))

        val imported = item(guid = "", indexer = "")
        play(imported).passwordNeeded(imported.candidate())
        assertEquals(emptyMap(), store.entries)
    }

    /**
     * W103, L314: a release found a copy of a disc is remembered by indexer and guid, whatever the providers: a later play
     * skips it without a grab, Try a smaller release leaves it out, the release list marks it with its chip, and one chosen
     * by hand is tried all the same. An imported NZB isn't.
     */
    @Test
    fun `a disc copy is remembered and skipped, unless chosen`() = runTest {
        val disc = item("cc-2019-drift", title = "Copper.Crown.2019.2160p.UHD.BluRay.HEVC.TrueHD.7.1-DRIFT")
        play(disc).discCopy(disc.candidate())
        assertEquals(now, store.entries.getValue(disc.key).discAt)
        assertEquals("Skipped: a disc copy (checked today)", play(disc).knownMissing(disc.candidate()))
        now += 3 * day
        inUse = listOf(ProviderConfig("p-new", "New", "new.example.org", 563, "", "", useTls = true))
        assertEquals("Skipped: a disc copy (checked 3 days ago)", play(disc).knownMissing(disc.candidate()))
        assertNull(play(disc, chosen = true).knownMissing(disc.candidate()))
        assertEquals(setOf(disc.candidate().id), missing.skipped(mapOf(disc.candidate().id to disc), chosen = false))
        val known = missing.known(listOf(disc.key)).first()
        assertEquals(mapOf(disc.key to KnownMissing(now - 3 * day, incompleteNzb = false, discCopy = true)), known)
        assertEquals("Disc copy · 11 Apr", missingChip(known.getValue(disc.key), LocalDate.of(1970, 4, 14), ZoneOffset.UTC))

        val imported = item(guid = "", indexer = "")
        play(imported).discCopy(imported.candidate())
        assertEquals(setOf(disc.key), store.entries.keys)
    }

    /**
     * What Try a smaller release may offer (L8): of the releases given (by candidate id), those a play would skip before
     * grabbing them, as found missing on the providers in use; none of those chosen by hand.
     */
    @Test
    fun `the releases a play would skip are told apart before it starts`() = runTest {
        val whole = item("hl-s01e01-whole", title = "Harbor.Lights.S01E01.720p.WEB-DL.AAC2.0.H.264-WHOLE")
        play(broken).missing(broken.candidate(), MissingOn.ProvidersInUse)
        val items = listOf(broken, whole).associateBy { "${it.indexerId}/${it.guid}" }
        assertEquals(setOf(broken.candidate().id), missing.skipped(items, chosen = false))
        assertEquals(emptySet(), missing.skipped(items, chosen = true))
        // Another provider in use since may have it.
        inUse = listOf(a.copy(host = "eu.example.com"), b)
        assertEquals(emptySet(), missing.skipped(items, chosen = false))
    }

    /** Chosen by hand, a release is tried, and remembered all the same when it is found missing. */
    @Test
    fun `a release the user chose is tried, and remembered when it fails`() = runTest {
        play(broken).missing(broken.candidate(), MissingOn.ProvidersInUse)
        assertNull(play(broken, chosen = true).knownMissing(broken.candidate()))
        now += day
        play(broken, chosen = true).missing(broken.candidate(), MissingOn.ProvidersInUse)
        assertEquals(now, store.entries.values.single().updatedAt)
    }

    /**
     * Chosen by hand, a release marked missing played: it isn't missing on the providers in use, so a later play that isn't
     * chosen (Resume, the movie page's Play of the release that played) tries it, and the release list no longer marks it.
     */
    @Test
    fun `a release that plays is no longer known missing on the providers in use`() = runTest {
        play(broken).missing(broken.candidate(), MissingOn.ProvidersInUse)
        play(broken, chosen = true).found(broken.candidate())
        assertNull(play(broken).knownMissing(broken.candidate()))
        assertEquals(emptyMap(), missing.known(listOf("fake/hl-s01e01-broken")).first())
        assertEquals(emptyMap(), store.entries)
        // Found missing on a alone, then played with b alone: a may still lack it.
        inUse = listOf(a)
        play(broken).missing(broken.candidate(), MissingOn.ProvidersInUse)
        inUse = listOf(b)
        play(broken).found(broken.candidate())
        inUse = listOf(a)
        assertEquals("Skipped: missing on your providers (checked today)", play(broken).knownMissing(broken.candidate()))
    }

    /** Nothing tells an imported NZB apart from another: it has no indexer and guid, so it is neither looked up nor remembered. */
    @Test
    fun `an imported NZB is neither looked up nor remembered`() = runTest {
        val imported = item(guid = "", indexer = "")
        play(imported).missing(imported.candidate(), MissingOn.ProvidersInUse)
        missing.record("", "", "Imported", indexerName = null, on = MissingOn.AnyProvider)
        assertEquals(emptyMap(), store.entries)
        assertNull(play(imported).knownMissing(imported.candidate()))
        assertEquals(0, store.reads)
    }

    @Test
    fun `without a provider in use nothing is remembered`() = runTest {
        inUse = emptyList()
        play(broken).missing(broken.candidate(), MissingOn.ProvidersInUse)
        assertEquals(emptyMap(), store.entries)
    }

    /**
     * The release list marks what a play would skip, and what it skipped before though the user may choose it now; again as
     * that changes while it shows (a play from it, a gap found playing).
     */
    @Test
    fun `the release list is told which releases are known to be missing`() = runTest {
        val good = item("hl-s01e01-good", title = "Harbor.Lights.S01E01.1080p.WEB-DL.AAC2.0.H.264-GOOD")
        val gone = item("nzb-lacks-a-volume")
        val keys = listOf(broken, good, gone).map { "${it.indexerId}/${it.guid}" }
        val seen = mutableListOf<Set<String>>()
        val watching = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { missing.known(keys).collect { seen += it.keys } }
        play(broken).missing(broken.candidate(), MissingOn.ProvidersInUse)
        play(gone).missing(gone.candidate(), MissingOn.AnyProvider)
        assertEquals(
            mapOf("fake/hl-s01e01-broken" to KnownMissing(now, incompleteNzb = false), "fake/nzb-lacks-a-volume" to KnownMissing(now, incompleteNzb = true)),
            missing.known(keys).first(),
        )
        play(broken, chosen = true).found(broken.candidate())
        assertEquals(listOf(emptySet(), setOf(broken.key), setOf(broken.key, gone.key), setOf(gone.key)), seen)
        watching.cancel()
        play(broken).missing(broken.candidate(), MissingOn.ProvidersInUse)
        inUse = listOf(a, b, a.copy(id = "p-c"))
        assertEquals(setOf("fake/nzb-lacks-a-volume"), missing.known(keys).first().keys)
    }

    private val NewznabItem.key get() = "$indexerId/$guid"

    @Test
    fun `the play sheet says when the skipped release was checked`() {
        val checked = LocalDate.of(2026, 10, 14).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 20 * 60 * 60 * 1000L
        fun line(known: KnownMissing, daysLater: Long) = skippedLine(known, checked + daysLater * day, ZoneOffset.UTC)
        assertEquals("Skipped: missing on your providers (checked today)", line(KnownMissing(checked, false), 0))
        assertEquals("Skipped: missing on your providers (checked yesterday)", line(KnownMissing(checked, false), 1))
        assertEquals("Skipped: missing on your providers (checked 3 days ago)", line(KnownMissing(checked, false), 3))
        assertEquals("Skipped: incomplete NZB (checked 12 days ago)", line(KnownMissing(checked, true), 12))
        // By the calendar: four hours later is the next day.
        assertEquals("Skipped: missing on your providers (checked yesterday)", skippedLine(KnownMissing(checked, false), checked + 4 * 60 * 60 * 1000L, ZoneOffset.UTC))
        assertEquals("Skipped: needs a password (checked 12 days ago)", line(KnownMissing(checked, false, passwordNeeded = true), 12))
        val missingLine = line(KnownMissing(checked, false), 3)
        val passwordLine = line(KnownMissing(checked, false, passwordNeeded = true), 3)
        assertEquals("Skipped 1 release found incomplete before", skippedNotice(listOf(missingLine)))
        assertEquals("Skipped 2 releases found incomplete before", skippedNotice(listOf(missingLine, line(KnownMissing(checked, true), 3))))
        assertEquals("Skipped 1 release that needed a password before", skippedNotice(listOf(passwordLine)))
        assertEquals("Skipped 2 releases that needed a password before", skippedNotice(listOf(passwordLine, passwordLine)))
        assertEquals("Skipped 2 releases found incomplete or needing a password before", skippedNotice(listOf(missingLine, passwordLine)))
    }

    @Test
    fun `the release list's chip says since when it is missing, or that its NZB is incomplete`() {
        val checked = LocalDate.of(2026, 10, 14).atTime(9, 30).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals("Missing on your providers · 14 Oct", missingChip(KnownMissing(checked, false), LocalDate.of(2026, 10, 17), ZoneOffset.UTC))
        assertEquals("Missing on your providers · 14 Oct 2026", missingChip(KnownMissing(checked, false), LocalDate.of(2027, 1, 2), ZoneOffset.UTC))
        assertEquals("Incomplete NZB", missingChip(KnownMissing(checked, true), LocalDate.of(2026, 10, 17), ZoneOffset.UTC))
        assertEquals("Needs a password · 14 Oct", missingChip(KnownMissing(checked, false, passwordNeeded = true), LocalDate.of(2026, 10, 17), ZoneOffset.UTC))
    }

    /** The log names the release, its indexer and the providers by name (the host for one without), and nothing secret. */
    @Test
    fun `the log says which release from which indexer is missing on which providers`() {
        assertEquals(
            "Harbor.Lights.S01E01.PROPER.1080p.WEB-DL.AAC2.0.H.264-BROKEN from Fake missing on News A, eu.example.net",
            recordedLine(broken.title, "Fake", listOf(a, b)),
        )
        assertEquals("Harbor.Lights.S01E01.PROPER.1080p.WEB-DL.AAC2.0.H.264-BROKEN from Fake has an incomplete NZB", recordedLine(broken.title, "Fake", null))
    }
}
