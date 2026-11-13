package io.skjaere.usenet.app.link

import io.skjaere.usenet.app.play.AskedPlay
import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.app.play.Launching
import io.skjaere.usenet.app.play.MovieTarget
import io.skjaere.usenet.app.play.NotReady
import io.skjaere.usenet.app.play.PlayOrigin
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlayerHandle
import io.skjaere.usenet.app.play.RemotePlay
import io.skjaere.usenet.data.IndexerRecord
import io.skjaere.usenet.data.ProviderRecord
import io.skjaere.usenet.data.ServiceAccount
import io.skjaere.usenet.data.ServiceWithKey
import io.skjaere.usenet.data.TorrentIndexerRecord
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.link.KeyId
import io.skjaere.usenet.link.LinkMessage
import io.skjaere.usenet.link.LinkMessage.Ack
import io.skjaere.usenet.link.LinkMessage.Cancel
import io.skjaere.usenet.link.LinkMessage.Pause
import io.skjaere.usenet.link.LinkMessage.Play
import io.skjaere.usenet.link.LinkMessage.Rejected
import io.skjaere.usenet.link.LinkMessage.Resume
import io.skjaere.usenet.link.LinkMessage.SeekBy
import io.skjaere.usenet.link.LinkMessage.SeekTo
import io.skjaere.usenet.link.LinkMessage.Stop
import io.skjaere.usenet.link.Rejection
import io.skjaere.usenet.newznab.TvIds
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * L8, L18, L32: what the TV does with a paired phone's commands, by what it shows. A Play starts as from the TV's own
 * pages, replaces a play the sheet is starting, and is handed over to a player that is open, "Playback stopped" or not;
 * the last to ask wins. Busy with setup, not ready, a field outside the checks or too fast are refused, each in its own
 * words. Cancel and the transport commands act only on their own session, clamped.
 */
class TvCommandsTest {
    /** The coordinator as the commands reach it. */
    private class Starts : TvStarts {
        val plays = mutableListOf<Pair<AskedPlay, PlayOrigin>>()
        val told = mutableListOf<PlayState.Failed>()
        var cancels = 0
        override var starting = false
        override var session: RemotePlay? = null

        override fun play(play: AskedPlay, origin: PlayOrigin) {
            plays += play to origin
            session = origin.remote
            starting = true
        }

        override fun cancel() {
            cancels++
            session = null
            starting = false
        }

        override fun tell(failure: PlayState.Failed) {
            told += failure
        }
    }

    /** The player in front: whose play, and what it was asked to do. */
    private class Player(override val remote: RemotePlay?, override val durationMs: Long? = 3_120_000) : PlayerHandle {
        val did = mutableListOf<String>()
        val handedOver = mutableListOf<Pair<AskedPlay, PlayOrigin>>()
        override fun pause() { did += "pause" }
        override fun resume() { did += "resume" }
        override fun seekTo(positionMs: Long) { did += "seekTo $positionMs" }
        override fun seekBy(deltaMs: Long) { did += "seekBy $deltaMs" }
        override fun stop() { did += "stop" }
        override fun handOver(play: AskedPlay, origin: PlayOrigin) { handedOver += play to origin }
    }

    private var clock = 0L
    private var now = 1_800_000_000_000L
    private var busy = false
    private val provider = ProviderRecord("p1", "Quay News", "news.quay.invalid")
    private val newznab = IndexerRecord("i1", "Quay Indexer", "https://indexer.quay.invalid/api")
    private val torbox = ServiceWithKey(ServiceAccount("tb", "TorBox"), "TB-KEY")
    private val prowlarr = TorrentIndexerRecord("t1", "Home Prowlarr", "http://192.168.1.20:9696/1/api")

    /** What the TV has set up, read by the readiness rule at each Play: a provider and an indexer, unless a test says otherwise. */
    private var setup = TvSetup(listOf(provider), 1, listOf(newznab))
    private val rows = mutableMapOf<String, WatchState>()
    private var rowsBroken = false
    private var player: Player? = null
    private val starts = Starts()
    private var sessions = 0

    /** A launch the sheet sent, on its way to a player; and the player that starts for it, if one does. */
    private var launching: Launching? = null
    private var coming: Player? = null
    private var waited = 0
    private val plays = TvPlays(
        TvPlaysDeps(
            starts = starts, player = { player }, busy = { busy }, ready = { TvReadiness.of(setup, shown = true) }, tvRow = { if (rowsBroken) throw java.io.IOException("the library can't be read") else rows[it] }, now = { now }, clock = { clock },
            newSession = { "s-${++sessions}" },
            launching = { launching },
            arriving = {
                waited++
                // The launch's player starts: it is the one in front, and the launch has arrived.
                coming?.also {
                    player = it
                    launching = null
                }
            },
        ),
    )

    private val galaxy = AskingPhone(KeyId.of(byteArrayOf(1, 2, 3)), "Galaxy phone")
    private val tablet = AskingPhone(KeyId.of(byteArrayOf(4, 5, 6)), "Invented tablet")

    private fun session(id: String, phone: AskingPhone = galaxy) = RemotePlay(id, phone.key.hex, phone.name)

    private fun answer(command: LinkMessage.Command, phone: AskingPhone = galaxy): LinkMessage = runBlocking { plays.answer(phone, command) }

    private val episode = Play.Episode(
        showTmdbId = 900002, showName = "Harbor Lights", showYear = 2024, aliases = listOf("Harbour Lights"), tvdb = 9000021, tmdb = 900002,
        imdb = "tt9000022", tvmaze = 90021, season = 2, episode = 4, episodeName = "Low Water", airDate = "2026-10-01", runtimeMinutes = 52,
        stillPath = "/hl-s02e04.jpg", aired = true, originalLanguage = "en", seasonEpisodes = 8,
    )
    private val movie = Play.Movie(900031, "Harbor Bell", 2026, "tt9000031", 104, "2026-06-01", released = true, originalLanguage = "en")

    private val asEpisode = EpisodePlay(
        900002, "Harbor Lights", 2024, listOf("Harbour Lights"), TvIds(tvdb = 9000021, tmdb = 900002, imdb = "tt9000022", tvmaze = 90021), 2, 4, "Low Water",
        LocalDate.of(2026, 10, 1), 52, "/hl-s02e04.jpg", aired = true, originalLanguage = "en", seasonEpisodes = 8,
    )
    private val asMovie = MovieTarget(900031, "Harbor Bell", 2026, "tt9000031", 104, LocalDate.of(2026, 6, 1), released = true, originalLanguage = "en")

    /** Waits out the Play limit before the next Play: a test of something else isn't too fast. */
    private fun later() {
        clock += 600_001
    }

    @Test
    fun `a Play on Home or any page starts the episode as from the TV's own Show page, in a session of its own`() {
        assertEquals(Ack(1, "s-1"), answer(Play(1, episode = episode)))
        assertEquals(listOf<Pair<AskedPlay, PlayOrigin>>(AskedPlay.Episode(asEpisode) to PlayOrigin(remote = session("s-1"))), starts.plays)
    }

    @Test
    fun `a movie starts as from the TV's own Movie page`() {
        assertEquals(Ack(7, "s-1"), answer(Play(7, movie = movie)))
        assertEquals(listOf<Pair<AskedPlay, PlayOrigin>>(AskedPlay.Movie(asMovie) to PlayOrigin(remote = session("s-1"))), starts.plays)
    }

    @Test
    fun `a Play while the sheet starts a play, or asks which file it is, replaces it`() {
        starts.starting = true
        starts.session = null
        assertEquals(Ack(1, "s-1"), answer(Play(1, episode = episode)))
        later()
        assertEquals(Ack(2, "s-2"), answer(Play(2, movie = movie)))
        assertEquals(listOf(session("s-1"), session("s-2")), starts.plays.map { it.second.remote })
    }

    @Test
    fun `with a player open, a Play is handed over, whether it plays the TV's own, a phone's, or shows Playback stopped`() {
        val own = Player(remote = null).also { player = it }
        assertEquals(Ack(1, "s-1"), answer(Play(1, episode = episode)))
        assertEquals(listOf<Pair<AskedPlay, PlayOrigin>>(AskedPlay.Episode(asEpisode) to PlayOrigin(remote = session("s-1"))), own.handedOver)
        // A movie over a phone's episode playing (its player stopped or not: the player hands over either way).
        val phones = Player(remote = session("s-1")).also { player = it }
        later()
        assertEquals(Ack(2, "s-2"), answer(Play(2, movie = movie), tablet))
        assertEquals(listOf<Pair<AskedPlay, PlayOrigin>>(AskedPlay.Movie(asMovie) to PlayOrigin(remote = session("s-2", tablet))), phones.handedOver)
        assertTrue(starts.plays.isEmpty(), "nothing started past the player")
    }

    @Test
    fun `the phone's place starts the play when it is newer than the TV's own, else the TV's own`() {
        val key = WatchKey.episode(900002, 2, 4)
        rows[key] = WatchState(key, WatchKind.Episode, showTmdbId = 900002, season = 2, episode = 4, title = "Harbor Lights S02E04", positionMs = 720_000, updatedAt = now - 86_400_000)
        answer(Play(1, episode = episode, from = Play.From(1_930_000, watched = false, ageMs = 300_000)))
        assertEquals(1_930_000, starts.plays.last().second.start)
        later()
        answer(Play(2, episode = episode, from = Play.From(1_930_000, watched = false, ageMs = 2 * 86_400_000)))
        assertEquals(null, starts.plays.last().second.start, "the TV's own, newer")
        later()
        answer(Play(3, movie = movie, from = Play.From(5_000_000, watched = true, ageMs = 1_000)))
        assertEquals(0, starts.plays.last().second.start, "watched on the phone, with no row here: from the beginning")
    }

    @Test
    fun `a TV row that can't be read leaves the phone's place, and the play starts`() {
        rowsBroken = true
        assertEquals(Ack(1, "s-1"), answer(Play(1, episode = episode, from = Play.From(1_930_000, watched = false, ageMs = 300_000))))
        assertEquals(1_930_000, starts.plays.single().second.start)
    }

    @Test
    fun `the last to ask wins, and the phone it replaced is told its commands are no longer current`() {
        answer(Play(1, episode = episode))
        player = Player(remote = session("s-1"))
        later()
        assertEquals(Ack(1, "s-2"), answer(Play(1, movie = movie), tablet))
        player = Player(remote = session("s-2", tablet))
        assertEquals(Rejected(5, Rejection.NotCurrent), answer(Pause(5, "s-1")))
        assertEquals(Ack(6, "s-2"), answer(Pause(6, "s-2"), tablet))
        assertEquals(listOf("pause"), player!!.did)
    }

    @Test
    fun `Cancel and the transport commands act only on their own session`() {
        val playing = Player(remote = session("s-4")).also { player = it }
        assertEquals(Ack(1, "s-4"), answer(Pause(1, "s-4")))
        assertEquals(Ack(2, "s-4"), answer(Resume(2, "s-4")))
        assertEquals(Ack(3, "s-4"), answer(SeekTo(3, "s-4", 600_000)))
        assertEquals(Ack(4, "s-4"), answer(SeekBy(4, "s-4", -10_000)))
        assertEquals(Ack(5, "s-4"), answer(Stop(5, "s-4")))
        assertEquals(listOf("pause", "resume", "seekTo 600000", "seekBy -10000", "stop"), playing.did)

        val wrong = listOf(Pause(6, "s-3"), Resume(7, "s-3"), SeekTo(8, "s-3", 1), SeekBy(9, "s-3", 1), Stop(10, "s-3"), Cancel(11, "s-3"))
        wrong.forEach { assertEquals(Rejected(it.seq, Rejection.NotCurrent), answer(it), "$it") }
        assertEquals(5, playing.did.size, "nothing done for another session")

        // The TV's own play: no phone's session is current.
        player = Player(remote = null)
        assertEquals(Rejected(12, Rejection.NotCurrent), answer(Pause(12, "s-4")))
        // Cancel on a play the sheet starts: stopped there. Transport commands wait for its player.
        player = null
        answer(Play(13, episode = episode))
        assertEquals(Rejected(14, Rejection.NotCurrent), answer(Pause(14, "s-1")))
        assertEquals(Rejected(15, Rejection.NotCurrent), answer(Cancel(15, "s-4")))
        assertEquals(0, starts.cancels)
        assertEquals(Ack(16, "s-1"), answer(Cancel(16, "s-1")))
        assertEquals(1, starts.cancels)
        // Cancel once its player plays: the player leaves.
        val started = Player(remote = session("s-1")).also { player = it }
        assertEquals(Ack(17, "s-1"), answer(Cancel(17, "s-1")))
        assertEquals(listOf("stop"), started.did)
    }

    @Test
    fun `another paired phone naming a phone's session is told it isn't current, and nothing is done`() {
        // Any paired phone hears the session's id (L34: its state, untitled; the hello names it): only its own phone acts on it.
        val playing = Player(remote = session("s-1")).also { player = it }
        val asked = listOf(Pause(1, "s-1"), Resume(2, "s-1"), SeekTo(3, "s-1", 0), SeekBy(4, "s-1", -10_000), Stop(5, "s-1"), Cancel(6, "s-1"))
        asked.forEach { assertEquals(Rejected(it.seq, Rejection.NotCurrent), answer(it, tablet), "$it") }
        assertEquals(emptyList(), playing.did)
        // The same while the sheet starts the phone's play.
        player = null
        val starting = assertIs<Ack>(answer(Play(7, episode = episode))).session!!
        assertEquals(Rejected(8, Rejection.NotCurrent), answer(Cancel(8, starting), tablet))
        assertEquals(0, starts.cancels)
        assertEquals(Ack(9, starting), answer(Cancel(9, starting)))
        assertEquals(1, starts.cancels)
    }

    @Test
    fun `a Play while the sheet's launch is on its way to a player waits for that player, and is handed over to it`() {
        // Phone A's play: the sheet sent its launch, and its player is yet to start (nothing starts, nothing is in front).
        launching = Launching(session("s-a"))
        val arrives = Player(remote = session("s-a")).also { coming = it }
        assertEquals(Ack(1, "s-1"), answer(Play(1, movie = movie), tablet))
        assertEquals(listOf<Pair<AskedPlay, PlayOrigin>>(AskedPlay.Movie(asMovie) to PlayOrigin(remote = session("s-1", tablet))), arrives.handedOver)
        assertTrue(starts.plays.isEmpty(), "not started on the sheet under the player that comes: the last to ask wins (L18)")

        // A launch whose player never starts: the Play starts on the sheet, as with nothing on its way.
        player = null
        launching = Launching(null)
        coming = null
        later()
        assertEquals(Ack(2, "s-2"), answer(Play(2, movie = movie)))
        assertEquals(listOf(session("s-2")), starts.plays.map { it.second.remote })
    }

    @Test
    fun `not ready while a launch is on its way is refused without the sheet, which the coming player would hide`() {
        setup = TvSetup(listOf(provider), 1)
        launching = Launching(null)
        assertEquals(Rejected(1, Rejection.NotReadyIndexer), answer(Play(1, movie = movie)))
        assertTrue(starts.told.isEmpty())
        assertEquals(0, waited, "nothing to wait for: nothing starts")
    }

    @Test
    fun `Cancel and the transport commands for a launch on its way act on its player once it starts`() {
        launching = Launching(session("s-1"))
        val arrives = Player(remote = session("s-1")).also { coming = it }
        assertEquals(Ack(1, "s-1"), answer(Pause(1, "s-1")))
        assertEquals(listOf("pause"), arrives.did)
        launching = Launching(session("s-1"))
        player = null
        assertEquals(Ack(2, "s-1"), answer(Cancel(2, "s-1")))
        assertEquals(listOf("pause", "stop"), arrives.did, "Cancel as its picture starts: its player leaves")
        assertEquals(0, starts.cancels)

        // Another phone's word for it, or for another session, waits for nothing.
        launching = Launching(session("s-1"))
        player = null
        waited = 0
        assertEquals(Rejected(3, Rejection.NotCurrent), answer(Stop(3, "s-1"), tablet))
        assertEquals(Rejected(4, Rejection.NotCurrent), answer(Pause(4, "s-9")))
        assertEquals(0, waited)
        // Its player never starts: nothing to act on.
        coming = null
        assertEquals(Rejected(5, Rejection.NotCurrent), answer(Stop(5, "s-1")))
        assertEquals(Rejected(6, Rejection.NotCurrent), answer(Cancel(6, "s-1")))
    }

    @Test
    fun `SeekTo is clamped to the play's length, and SeekBy to six hours either way`() {
        val playing = Player(remote = session("s-4"), durationMs = 3_120_000).also { player = it }
        answer(SeekTo(1, "s-4", -5))
        answer(SeekTo(2, "s-4", 9_000_000))
        answer(SeekBy(3, "s-4", 30_000_000))
        answer(SeekBy(4, "s-4", -30_000_000))
        assertEquals(listOf("seekTo 0", "seekTo 3120000", "seekBy 21600000", "seekBy -21600000"), playing.did)
        val unknown = Player(remote = session("s-4"), durationMs = null).also { player = it }
        answer(SeekTo(5, "s-4", 9_000_000))
        assertEquals(listOf("seekTo 9000000"), unknown.did, "no length known yet: from 0 on")
    }

    @Test
    fun `busy while the TV's Set up with your phone page is started, or a pairing question shows`() {
        busy = true
        assertEquals(Rejected(1, Rejection.BusySetup), answer(Play(1, episode = episode)))
        assertTrue(starts.plays.isEmpty())
        assertTrue(starts.told.isEmpty())
        busy = false
        later()
        assertIs<Ack>(answer(Play(2, episode = episode)))
    }

    @Test
    fun `no usable provider, or no enabled indexer, shows the TV's not-ready sheet and refuses`() {
        setup = TvSetup(listOf(provider), usableProviders = 0, indexers = listOf(newznab))
        assertEquals(Rejected(1, Rejection.NotReadyProvider), answer(Play(1, episode = episode)))
        assertEquals(
            PlayState.Failed("Harbor Lights", "S02E04 · Low Water", emptyList(), notTried = 0, notReady = NotReady("Galaxy phone", "Harbor Lights S02E04", NotReady.Missing.Provider)),
            starts.told.single(),
        )
        setup = TvSetup(listOf(provider), 1, listOf(newznab.copy(enabled = false)))
        later()
        assertEquals(Rejected(2, Rejection.NotReadyIndexer), answer(Play(2, movie = movie)))
        assertEquals(NotReady("Galaxy phone", "Harbor Bell", NotReady.Missing.Indexer), starts.told.last().notReady)
        assertEquals("Galaxy phone asked to play Harbor Lights S02E04. Set up a Usenet provider first.", starts.told.first().notReady!!.text)
        assertEquals("Galaxy phone asked to play Harbor Bell. Add an indexer first: this TV searches its own.", starts.told.last().notReady!!.text)
        assertTrue(starts.plays.isEmpty(), "the TV doesn't keep the request")

        // While a play starts or plays here, it is refused without the sheet: that play goes on.
        starts.starting = true
        later()
        assertEquals(Rejected(3, Rejection.NotReadyIndexer), answer(Play(3, movie = movie)))
        starts.starting = false
        player = Player(remote = null)
        later()
        assertEquals(Rejected(4, Rejection.NotReadyIndexer), answer(Play(4, movie = movie)))
        assertEquals(2, starts.told.size)
    }

    /**
     * L224: a TV set up only for its debrid service, whose setup isn't done, refuses in the words of what that way lacks
     * first, naming no service (L223). The TV doesn't keep the request.
     */
    @Test
    fun `a TV whose debrid setup isn't done refuses for what it lacks first`() {
        val cases = listOf(
            TvSetup(torrentIndexers = listOf(prowlarr)) to Rejection.NotReadyService,
            TvSetup(services = listOf(torbox.copy(record = torbox.record.copy(enabled = false))), torrentIndexers = listOf(prowlarr)) to Rejection.NotReadyServiceOff,
            TvSetup(services = listOf(torbox.copy(record = torbox.record.copy(keyRefused = true))), torrentIndexers = listOf(prowlarr)) to Rejection.NotReadyServiceOff,
            TvSetup(services = listOf(torbox)) to Rejection.NotReadyTorrentIndexer,
        )
        cases.forEachIndexed { i, (has, rejection) ->
            setup = has
            later()
            assertEquals(Rejected(i + 1, rejection), answer(Play(i + 1, episode = episode)), "$rejection")
        }
        assertTrue(starts.plays.isEmpty(), "the TV doesn't keep the request")
    }

    /**
     * D2TvNotReady (L248, choice 7): the TV's own sheet says what its debrid setup lacks, in the design's words: a key the
     * service refused names that service here, on the TV, though never on the wire.
     */
    @Test
    fun `a TV whose debrid setup isn't done shows its sheet in the words of what it lacks`() {
        val off = torbox.copy(record = torbox.record.copy(enabled = false))
        val refused = torbox.copy(record = torbox.record.copy(keyRefused = true))
        val cases = listOf(
            TvSetup(torrentIndexers = listOf(prowlarr)) to NotReady.Missing.Service,
            TvSetup(services = listOf(off), torrentIndexers = listOf(prowlarr)) to NotReady.Missing.ServiceOff(),
            TvSetup(services = listOf(refused), torrentIndexers = listOf(prowlarr)) to NotReady.Missing.ServiceOff("TorBox"),
            TvSetup(services = listOf(torbox)) to NotReady.Missing.TorrentIndexer,
        )
        cases.forEachIndexed { i, (has, missing) ->
            setup = has
            later()
            answer(Play(i + 1, episode = episode))
            assertEquals(
                PlayState.Failed("Harbor Lights", "S02E04 · Low Water", emptyList(), notTried = 0, notReady = NotReady("Galaxy phone", "Harbor Lights S02E04", missing)),
                starts.told.last(), "$missing",
            )
        }
        assertEquals(
            listOf(
                "Galaxy phone asked to play Harbor Lights S02E04. Set up a debrid service first.",
                "Galaxy phone asked to play Harbor Lights S02E04. Your debrid service is off: switch it on in Settings › Debrid services.",
                "Galaxy phone asked to play Harbor Lights S02E04. TorBox refused this TV's API key: replace it in Settings › Debrid services.",
                "Galaxy phone asked to play Harbor Lights S02E04. Add a torrent indexer first: this TV searches its own.",
            ),
            starts.told.map { it.notReady!!.text },
        )
        assertEquals(
            listOf("Add a debrid service", "Open Debrid services", "Open Debrid services", "Add a torrent indexer"),
            starts.told.map { it.notReady!!.add },
        )
        assertTrue(starts.plays.isEmpty(), "the TV doesn't keep the request")

        // While a play starts here, it is refused without the sheet, as Usenet's are: that play goes on.
        starts.starting = true
        later()
        assertEquals(Rejected(9, Rejection.NotReadyTorrentIndexer), answer(Play(9, movie = movie)))
        assertEquals(4, starts.told.size)
    }

    /** L224: a TV that plays from its debrid service alone takes a phone's play, as a Usenet TV does. */
    @Test
    fun `a TV set up only for its debrid service takes the play`() {
        setup = TvSetup(services = listOf(torbox), torrentIndexers = listOf(prowlarr))
        assertEquals(Ack(1, "s-1"), answer(Play(1, episode = episode)))
        assertEquals(listOf<Pair<AskedPlay, PlayOrigin>>(AskedPlay.Episode(asEpisode) to PlayOrigin(remote = session("s-1"))), starts.plays)
        assertTrue(starts.told.isEmpty())
    }

    /**
     * L224: a provider, a service on and a torrent indexer, with no Newznab indexer: the TV plays from its service, so it
     * takes the play (before D2, `not-ready-indexer`).
     */
    @Test
    fun `a provider, a service and a torrent indexer with no Newznab indexer take the play`() {
        setup = TvSetup(listOf(provider), 1, services = listOf(torbox), torrentIndexers = listOf(prowlarr))
        assertEquals(Ack(1, "s-1"), answer(Play(1, movie = movie)))
        assertEquals(listOf<Pair<AskedPlay, PlayOrigin>>(AskedPlay.Movie(asMovie) to PlayOrigin(remote = session("s-1"))), starts.plays)
    }

    @Test
    fun `a Play with a field outside the checks is refused unsupported, and nothing starts`() {
        fun bad(e: Play.Episode) = Play(1, episode = e)
        fun bad(m: Play.Movie) = Play(1, movie = m)
        val long = "x".repeat(201)
        val outside = listOf(
            Play(1), Play(1, episode = episode, movie = movie),
            bad(episode.copy(showTmdbId = 0)), bad(episode.copy(tvdb = 0)), bad(episode.copy(tmdb = -1)), bad(episode.copy(tvmaze = 0)),
            bad(episode.copy(imdb = "tt123456")), bad(episode.copy(imdb = "tt1234567890")), bad(episode.copy(imdb = "nm1234567")),
            bad(episode.copy(season = -1)), bad(episode.copy(season = 1000)), bad(episode.copy(episode = 0)), bad(episode.copy(episode = 10_000)),
            bad(episode.copy(showName = long)), bad(episode.copy(episodeName = long)), bad(episode.copy(originalLanguage = long)),
            bad(episode.copy(aliases = List(11) { "Alias $it" })), bad(episode.copy(aliases = listOf(long))),
            bad(episode.copy(stillPath = "hl.jpg")), bad(episode.copy(stillPath = "/../hl.jpg")), bad(episode.copy(stillPath = "/hl s02.jpg")),
            bad(episode.copy(stillPath = "/hl.gif")), bad(episode.copy(airDate = "1 Oct 2026")),
            bad(movie.copy(tmdbId = 0)), bad(movie.copy(title = long)), bad(movie.copy(imdb = "tt12")), bad(movie.copy(releaseDate = "soon")),
        )
        outside.forEach { assertEquals(Rejected(1, Rejection.Unsupported), answer(it), "${it.episode ?: it.movie}") }
        assertTrue(starts.plays.isEmpty())

        val edges = listOf(
            bad(episode.copy(season = 0, episode = 9_999, showName = "x".repeat(200), aliases = List(10) { "Alias $it" }, imdb = "tt123456789", stillPath = "/A-z_09.png")),
            bad(episode.copy(tvdb = null, tmdb = null, imdb = null, tvmaze = null, stillPath = null, airDate = null, episodeName = null)),
            bad(movie.copy(imdb = null, releaseDate = null, year = null)),
        )
        edges.forEach {
            later()
            assertIs<Ack>(answer(it), "${it.episode ?: it.movie}")
        }
    }

    @Test
    fun `a second Play within 3 s, and the 11th in 10 minutes, are too fast, for each phone alone`() {
        assertIs<Ack>(answer(Play(1, episode = episode)))
        clock = 2_999
        assertEquals(Rejected(2, Rejection.TooFast), answer(Play(2, episode = episode)))
        assertIs<Ack>(answer(Play(3, episode = episode), tablet), "another phone's own limit")
        // Every 3 s: ten in all, then the eleventh within ten minutes of the first is too fast.
        (1..9).forEach { i ->
            clock = i * 3_000L
            assertIs<Ack>(answer(Play(10 + i, movie = movie)), "play ${i + 1} at ${clock / 1_000} s")
        }
        clock = 30_000
        assertEquals(Rejected(20, Rejection.TooFast), answer(Play(20, movie = movie)))
        clock = 600_000
        assertIs<Ack>(answer(Play(21, movie = movie)), "the first is ten minutes old")
        assertTrue(starts.plays.size == 12)
    }
}
