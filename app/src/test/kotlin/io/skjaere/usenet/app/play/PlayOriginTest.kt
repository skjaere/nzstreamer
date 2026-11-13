package io.skjaere.usenet.app.play

import io.skjaere.usenet.app.search.IndexerStatus
import io.skjaere.usenet.app.search.MovieFind
import io.skjaere.usenet.core.FilePicker
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.releases.RankedRelease
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.search.GroupKind
import io.skjaere.usenet.search.RankedItem
import io.skjaere.usenet.search.ResultGroup
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * L33, L39: a play a phone asked for keeps its origin with the coordinator's job, so every launch the job makes carries
 * the phone's session ([PlayerLaunch.remote]) and starts where the phone said, unless a mid-stream retry has its own
 * place. A play the player hands on (auto-advance, Previous and Next, Try another, Try a smaller release) stays in the
 * session, from its own place.
 */
class PlayOriginTest {
    private val session = RemotePlay(session = "s-81", phone = "19a98ff81a663651", name = "Galaxy phone")
    private val phone = PlayOrigin(start = 1_930_000, remote = session)
    private val episode = WatchTarget(WatchKind.Episode, "Harbor Lights S02E04", showTmdbId = 900002, season = 2, episode = 4)
    private val film = WatchTarget(WatchKind.Movie, "Copper Crown", movieTmdbId = 900011)

    private fun item(indexer: String, guid: String, title: String = "Harbor.Lights.S02E04.1080p.WEB.h264-$guid", bitrate: Long = 8_000_000) = RankedItem(
        NewznabItem(
            indexerId = indexer, guid = guid, title = title, sizeBytes = 1_000_000_000, publishedAt = null, postedAt = null,
            categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null,
            tvmazeId = null, season = null, episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
        ),
        RankedRelease(ReleaseFacts("$indexer/$guid", title, 1_000_000_000), ReleaseParser.parse(title), playable = true, score = 1.0, reasons = emptyList(), estimatedBitrate = bitrate),
    )

    private val releases = listOf(item("a", "1", bitrate = 20_000_000), item("b", "2"), item("c", "3", bitrate = 4_000_000))
    private val request = PlayRequest("Harbor Lights", "S02E04 · Low Water", releases, FilePicker.Episode(2, 4), episode)

    private fun saved(candidate: RankedItem) = SavedRelease(candidate.item.indexerId, candidate.item.guid, candidate.item.title, null, 0, "nzb-${candidate.item.guid}")

    @Test
    fun `a phone's play starts where the phone said, in its session`() {
        val launch = usenetLaunch(request, saved(releases[0]), "play-1", retry = null, origin = phone)
        assertEquals(1_930_000, launch.startPositionMs)
        assertEquals(session, launch.remote)
    }

    @Test
    fun `a play asked for here has no session and starts at the place saved`() {
        val launch = usenetLaunch(request, saved(releases[0]), "play-1", retry = null)
        assertNull(launch.startPositionMs)
        assertNull(launch.remote)
    }

    @Test
    fun `a mid-stream retry's own place wins over the phone's`() {
        val retry = Retry(setOf("a/1"), startPositionMs = 2_400_000)
        val launch = usenetLaunch(request, saved(releases[1]), "play-2", retry, origin = phone)
        assertEquals(2_400_000, launch.startPositionMs)
        assertEquals(session, launch.remote)
    }

    @Test
    fun `the session survives the launch's encoding, as the player's intent keeps it`() {
        val launch = usenetLaunch(request, saved(releases[0]), "play-1", retry = null, origin = phone)
        assertEquals(launch, PlayerLaunch.decode(launch.encode()))
        assertEquals(session, PlayerLaunch.decode(launch.encode()).remote)
    }

    @Test
    fun `a launch made inside the job has the job's origin, also in a coroutine of its own`() = runBlocking<Unit> {
        val direct = runBlocking(phone) { usenetLaunchInJob(request, saved(releases[0]), "play-1", retry = null) }
        assertEquals(session, direct.remote)
        assertEquals(1_930_000, direct.startPositionMs)
        var nested: PlayerLaunch? = null
        runBlocking(phone) { launch { nested = usenetLaunchInJob(request, saved(releases[0]), "play-1", retry = null) }.join() }
        assertEquals(session, nested?.remote)
        assertNull(runBlocking { usenetLaunchInJob(request, saved(releases[0]), "play-1", retry = null) }.remote, "a job with no origin is this device's")
    }

    @Test
    fun `MoviePlay's search after its remembered release failed keeps the phone's place`() {
        val remembered = RememberedRelease("a", "cc-good", "Copper.Crown.2019.1080p.BluRay.x264-GOOD", null, 0, "nzb-cc", "Copper.Crown.mkv", 8_000_000_000)
        val target = MovieTarget(900011, "Copper Crown", 2019, "tt9000012", 118, LocalDate.of(2019, 3, 1))
        val other = item("b", "cc-web", "Copper.Crown.2019.1080p.WEB-DL.H.264-WEB")
        val launches = mutableListOf<PlayerLaunch>()
        val outcomes = mutableListOf<PlayState.Failed?>(PlayState.Failed("Copper Crown", "2019", listOf("Copper.Crown" to "Release incomplete"), notTried = 0), null)
        runBlocking {
            MoviePlay.run(
                target, remembered, stored = true, today = LocalDate.of(2026, 11, 2),
                play = { req, retry ->
                    launches += usenetLaunch(req, saved(req.candidates.first()), "play-${launches.size}", retry, origin = phone)
                    outcomes.removeAt(0)
                },
                searching = {},
                find = {
                    MovieFind(
                        ResultGroup("known|movie:known", GroupKind.Movie, null, emptyList(), null, 2019, listOf(other), emptyList()),
                        listOf(IndexerStatus("b", "Indexer B", IndexerStatus.State.Answered(1))),
                    )
                },
            )
        }
        assertEquals(2, launches.size)
        assertEquals(listOf<Long?>(1_930_000, 1_930_000), launches.map { it.startPositionMs }, "the remembered release's and the search's")
        assertEquals(film, launches[1].watch)
    }

    private fun playing(origin: PlayOrigin = phone): PlayerLaunch = usenetLaunch(request, saved(releases[0]), "unknown", retry = null, origin = origin)

    @Test
    fun `auto-advance and Previous and Next hand on the session, from the next episode's own place`() {
        val next = PlayOrigin.after(playing())
        assertEquals(PlayOrigin(remote = session), next)
        val nextRequest = PlayRequest("Harbor Lights", "S02E05", listOf(item("a", "5")), FilePicker.Episode(2, 5), episode.copy(title = "Harbor Lights S02E05", episode = 5))
        val launch = runBlocking(next) { usenetLaunchInJob(nextRequest, saved(nextRequest.candidates[0]), "play-9", retry = null) }
        assertEquals(session, launch.remote)
        assertNull(launch.startPositionMs, "the next episode starts at its own place, not the phone's for the one before")
        assertEquals(PlayOrigin.Here, PlayOrigin.after(playing(PlayOrigin.Here)))
        assertEquals(PlayOrigin.Here, PlayOrigin.after(null), "an opened file's player hands on no session")
    }

    @Test
    fun `Try another keeps the session, playing the request again or searching for the episode`() {
        val sessions = PlaySessions(keep = 3) { "play-1" }
        val id = sessions.start(request, failed = emptySet())
        val again = assertIs<RetryPlan.Again>(sessions.retry(playing().copy(requestId = id), 1_200_000))
        assertEquals(session, again.origin.remote)
        val launch = runBlocking(again.origin) { usenetLaunchInJob(again.request, saved(releases[1]), "play-2", again.retry) }
        assertEquals(session, launch.remote)
        assertEquals(1_200_000, launch.startPositionMs, "from where the release before stopped")

        // The request gone (the process died since): the episode is searched for again, in the session all the same.
        val search = assertIs<RetryPlan.SearchEpisode>(PlaySessions().retry(playing().copy(requestId = "gone"), 1_200_000))
        assertEquals(session, search.origin.remote)
        val found = PlayRequest("Harbor Lights", "S02E04", listOf(item("d", "4")), FilePicker.Episode(2, 4), episode)
        assertEquals(session, runBlocking(search.origin) { usenetLaunchInJob(found, saved(found.candidates[0]), "play-3", search.retry) }.remote)
    }

    @Test
    fun `Try another for missing parts and Try a smaller release keep the session`() {
        val sessions = PlaySessions(keep = 3) { "play-1" }
        val id = sessions.start(request, failed = emptySet())
        val missing = assertIs<RetryPlan.Again>(sessions.retry(playing().copy(requestId = id), 1_200_000, Left(saved(releases[0]), "k", emptyList())))
        assertEquals(session, missing.origin.remote)
        val smaller = assertIs<RetryPlan.Again>(sessions.smaller(playing().copy(requestId = id), 1_200_000, measuredBps = 1_000_000, playingBps = 20_000_000))
        assertEquals(session, smaller.origin.remote)
        assertEquals(session, runBlocking(smaller.origin) { usenetLaunchInJob(smaller.request, saved(smaller.request.candidates[0]), "play-4", smaller.retry) }.remote)
    }

    @Test
    fun `a play here keeps Try another's plans this device's`() {
        val sessions = PlaySessions(keep = 3) { "play-1" }
        val id = sessions.start(request, failed = emptySet())
        assertEquals(PlayOrigin.Here, assertIs<RetryPlan.Again>(sessions.retry(playing(PlayOrigin.Here).copy(requestId = id), 1_200_000)).origin)
    }

    @Test
    fun `the sheet says who asked, and after a hand-over what stopped`() {
        assertNull(StartedFrom.of(PlayOrigin.Here), "nothing for a play asked for here")
        val from = StartedFrom.of(PlayOrigin(remote = session, stopped = StoppedPlay("The Glass Orchard", 3_734_000)))!!
        assertEquals("Started from Galaxy phone", from.line)
        assertEquals("Galaxy phone is waiting for you to choose", from.waiting)
        assertEquals("The Glass Orchard stopped at 1:02:14. Resume goes on from there.", from.stoppedLine)
        assertEquals("Harbor Lights stopped at 32:10. Resume goes on from there.", StartedFrom("Galaxy phone", StoppedPlay("Harbor Lights", 1_930_400)).stoppedLine)
        assertNull(StartedFrom.of(phone)!!.stoppedLine)
    }

    @Test
    fun `the sheet promises Resume only for a place that is kept, and resumable`() {
        val orchard = StoppedPlay.of("The Glass Orchard", 3_734_000, durationMs = 6_240_000, kept = true)
        assertEquals("The Glass Orchard stopped at 1:02:14. Resume goes on from there.", StartedFrom("Galaxy phone", orchard).stoppedLine)
        // A file opened in Import keeps no place, nor one past 90 % (it is watched): where it stopped, and no promise.
        assertEquals("Lantern Field stopped at 41:10.", StartedFrom("Galaxy phone", StoppedPlay.of("Lantern Field", 2_470_000, 5_400_000, kept = false)).stoppedLine)
        assertEquals("Copper Crown stopped at 1:52:00.", StartedFrom("Galaxy phone", StoppedPlay.of("Copper Crown", 6_720_000, 7_080_000, kept = true)).stoppedLine)
        assertEquals("Copper Crown stopped at 1:52:00.", StartedFrom("Galaxy phone", StoppedPlay.of("Copper Crown", 6_720_000, null, kept = true)).stoppedLine, "no length known: nothing saved")
        // Under a minute in, or before its player was made (0): nothing to say of it.
        assertNull(StoppedPlay.of("Harbor Bell", 30_000, 6_240_000, kept = true))
        assertNull(StoppedPlay.of("Harbor Bell", 0, null, kept = true))
        assertNull(StartedFrom("Galaxy phone", StoppedPlay.of("Harbor Bell", 0, null, kept = true)).stoppedLine)
    }

    @Test
    fun `a phone's name reaches no log by a slip`() {
        assertEquals("RemotePlay", session.toString())
        assertEquals(false, "Galaxy phone" in phone.toString())
    }

    private val kitchen = CastTo("route:kitchen", "Kitchen display")

    @Test
    fun `a cast play's launches keep the device, and what it hands on stays there`() {
        val launch = usenetLaunch(request, saved(releases[0]), "play-1", retry = null, origin = PlayOrigin(cast = kitchen))
        assertEquals(kitchen, launch.cast)
        // Try another release, Try a smaller release, the missing parts' Try another: on the device still (L81).
        assertEquals(PlayOrigin(cast = kitchen), PlayOrigin.after(launch))
        val both = usenetLaunch(request, saved(releases[0]), "play-2", retry = null, origin = PlayOrigin(remote = session, cast = kitchen))
        assertEquals(PlayOrigin(remote = session, cast = kitchen), PlayOrigin.after(both))
        assertNull(usenetLaunch(request, saved(releases[0]), "play-3", retry = null).cast, "a play here")
        val made = runBlocking(PlayOrigin(cast = kitchen)) { usenetLaunchInJob(request, saved(releases[0]), "play-4", retry = null) }
        assertEquals(kitchen, made.cast)
    }

    @Test
    fun `a device's name reaches no log by a slip`() {
        assertEquals("CastTo", kitchen.toString())
        assertEquals(false, "Kitchen display" in PlayOrigin(cast = kitchen).toString())
    }

    /** L153: the device's Cast id and model go with the play, for its profile, and print no more than its name does. */
    @Test
    fun `a device's id and model reach no log by a slip`() {
        val known = CastTo("route:kitchen", "Kitchen display", device = "device-kitchen", model = "Chromecast Ultra")
        assertEquals("CastTo", known.toString())
        for (fact in listOf("device-kitchen", "Chromecast Ultra", "Kitchen display")) assertEquals(false, fact in PlayOrigin(cast = known).toString(), fact)
    }
}
