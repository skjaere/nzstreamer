package io.skjaere.usenet.app.play

import io.skjaere.usenet.core.FilePicker
import io.skjaere.usenet.core.ProviderConfig
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.releases.Availability
import io.skjaere.usenet.releases.RankedRelease
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.releases.ReleaseReason
import io.skjaere.usenet.search.RankedItem
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * "Try another release" after a release failed mid-play: the coordinator's bookkeeping of the
 * request behind each launch and of the releases that failed during the play.
 */
class PlaySessionsTest {
    private var ids = 0
    private val sessions = PlaySessions(keep = 3) { "play-${++ids}" }

    private fun item(indexer: String, guid: String, title: String = "Harbor.Lights.S02E05.1080p.WEB.h264-$guid") = RankedItem(
        NewznabItem(
            indexerId = indexer, guid = guid, title = title, sizeBytes = 1_000_000_000, publishedAt = null, postedAt = null,
            categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null,
            tvmazeId = null, season = null, episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
        ),
        RankedRelease(ReleaseFacts("$indexer/$guid", title, 1_000_000_000), ReleaseParser.parse(title), playable = true, score = 1.0, reasons = emptyList(), estimatedBitrate = null),
    )

    private val episode = WatchTarget(WatchKind.Episode, "Harbor Lights S02E05", showTmdbId = 900002, season = 2, episode = 5)
    private val film = WatchTarget(WatchKind.Movie, "Copper Crown", movieTmdbId = 900010)
    private val releases = listOf(item("a", "1"), item("b", "2"), item("c", "3"))

    /** A Cast device, a first Chromecast by its model. */
    private val kitchen = CastTo("test-route:Kitchen display", "Kitchen display", "test-device:Kitchen display", "Chromecast")

    private fun request(watch: WatchTarget, candidates: List<RankedItem> = releases) =
        PlayRequest(if (watch == episode) "Harbor Lights" else watch.title, if (watch == episode) "S02E05" else null, candidates, FilePicker.Largest, watch)

    /** The launch the coordinator made of [candidate] for [request] under [requestId]. */
    private fun launch(request: PlayRequest, candidate: RankedItem, requestId: String?): PlayerLaunch {
        val release = SavedRelease(candidate.item.indexerId, candidate.item.guid, candidate.item.title, null, fileIndex = 0, nzbId = "nzb-${candidate.item.guid}")
        return usenetLaunch(request, release, requestId ?: "unknown", retry = null).copy(requestId = requestId)
    }

    @Test
    fun `another release plays the same request without the failed one, from where it stopped`() {
        val request = request(film)
        val id = sessions.start(request, failed = emptySet())
        assertEquals(RetryPlan.Again(request, Retry(setOf("a/1"), 1_234_000, failedKeys = listOf(WatchKey.movie(900010)))), sessions.retry(launch(request, releases[0], id), 1_234_000))
    }

    /** A torrent cached on the service [accounts], by their ids. */
    private fun torrent(n: Int, vararg accounts: String): RankedItem {
        val hash = "%040x".format(n)
        val title = "Harbor.Lights.S02E05.1080p.WEB.h264-T$n"
        return RankedItem(
            item("p", "btih:$hash", title).item.copy(infoHash = hash, availability = Availability.Cached(accounts.toList())),
            RankedRelease(ReleaseFacts("btih:$hash", title, 1_000_000_000, source = ReleaseFacts.TORRENT), ReleaseParser.parse(title), playable = true, score = 1.0, reasons = emptyList(), estimatedBitrate = null),
        )
    }

    /**
     * After a release whose account's route stopped as it played (its key refused, its limit reached, its service down),
     * Try another release leaves out the releases that play from that account alone (L164, L204): one from another
     * account, or from Usenet, is next. The routes the play itself stopped are kept with its session, and left out too.
     */
    @Test
    fun `try another leaves out the releases of an account whose route stopped`() {
        val onA = torrent(1, "acct-a")
        val onA2 = torrent(2, "acct-a")
        val onBoth = torrent(3, "acct-a", "acct-b")
        val usenet = item("u", "4")
        val request = request(film, listOf(onA, onA2, onBoth, usenet))
        val id = sessions.start(request, failed = emptySet())
        val plan = assertIs<RetryPlan.Again>(sessions.retry(launch(request, onA, id), 60_000, stopped = setOf("service:acct-a")))
        assertEquals(listOf(onBoth, usenet), plan.retry.leaveOut(plan.request.candidates))

        val stopping = sessions.start(request, failed = setOf(onA.key), stopped = setOf("service:acct-a"))
        val again = assertIs<RetryPlan.Again>(sessions.retry(launch(request, usenet, stopping), 60_000))
        assertEquals(listOf(onBoth), again.retry.leaveOut(again.request.candidates))
        // With nothing left but the stopped account's, an episode is searched for again.
        val alone = request(episode, listOf(onA, onA2))
        val left = sessions.start(alone, failed = emptySet())
        assertIs<RetryPlan.SearchEpisode>(sessions.retry(launch(alone, onA, left), 60_000, stopped = setOf("service:acct-a")))
    }

    @Test
    fun `every release that failed during the play is left out`() {
        val request = request(film)
        // c/3 failed the pipeline's checks before a/1 played.
        val first = sessions.start(request, failed = setOf("c/3"))
        val again = sessions.retry(launch(request, releases[0], first), 600_000) as RetryPlan.Again
        assertEquals(setOf("c/3", "a/1"), again.retry.failed)
        // The coordinator starts the next launch with what failed so far; then that release fails too.
        val second = sessions.start(request, again.retry.failed)
        val third = sessions.retry(launch(request, releases[1], second), 900_000)
        assertEquals(RetryPlan.None, third, "nothing of the film's request is left")
        val withMore = request(film, releases + item("d", "4"))
        val fourth = sessions.start(withMore, again.retry.failed)
        assertEquals(RetryPlan.Again(withMore, Retry(setOf("c/3", "a/1", "b/2"), 900_000, listOf(WatchKey.movie(900010)))), sessions.retry(launch(withMore, releases[1], fourth), 900_000))
    }

    @Test
    fun `an episode whose request has nothing left is searched for again without the failed releases`() {
        val request = request(episode, listOf(releases[0]))
        val id = sessions.start(request, failed = emptySet())
        assertEquals(
            RetryPlan.SearchEpisode(EpisodeTarget(900002, "Harbor Lights", 2, 5), Retry(setOf("a/1"), 300_000, listOf(WatchKey.episode(900002, 2, 5)))),
            sessions.retry(launch(request, releases[0], id), 300_000),
        )
    }

    @Test
    fun `without the request an episode is searched for again without the failed release`() {
        // The process died since the launch: its request is gone.
        val launch = launch(request(episode), releases[1], "play-from-an-earlier-process")
        assertEquals(RetryPlan.SearchEpisode(EpisodeTarget(900002, "Harbor Lights", 2, 5), Retry(setOf("b/2"), 42_000, listOf(WatchKey.episode(900002, 2, 5)))), sessions.retry(launch, 42_000))
    }

    @Test
    fun `without the request a film or a release has nothing to try`() {
        assertEquals(RetryPlan.None, sessions.retry(launch(request(film), releases[0], "play-from-an-earlier-process"), 42_000))
        assertEquals(RetryPlan.None, sessions.retry(launch(request(film), releases[0], requestId = null), 42_000))
        val release = WatchTarget(WatchKind.Release, "Harbor.Lights.S02E05.1080p.WEB.h264-1")
        assertEquals(RetryPlan.None, sessions.retry(launch(request(release), releases[0], requestId = null), 42_000))
    }

    @Test
    fun `only the latest plays are kept`() {
        val request = request(film)
        val oldest = sessions.start(request, emptySet())
        repeat(3) { sessions.start(request, emptySet()) }
        assertEquals(RetryPlan.None, sessions.retry(launch(request, releases[0], oldest), 0))
    }

    @Test
    fun `the next launch carries the request's id and starts where the failed release stopped`() {
        val request = request(episode)
        val release = SavedRelease("b", "2", releases[1].item.title, null, fileIndex = 3, nzbId = "nzb-2")
        val launch = usenetLaunch(request, release, "play-7", Retry(setOf("a/1"), 1_234_000))
        assertEquals("play-7", launch.requestId)
        assertEquals(1_234_000L, launch.startPositionMs)
        assertEquals(WatchKey.episode(900002, 2, 5), launch.watchKey)
        assertEquals(null, usenetLaunch(request, release, "play-8", retry = null).startPositionMs)
        // The episode's progress is the episode's, whichever release plays it.
        assertEquals(emptyList(), usenetLaunch(request, release, "play-9", Retry(setOf("a/1"), 1_234_000, listOf(WatchKey.episode(900002, 2, 5)))).replaces)
        assertEquals(episode, launch.watch)
    }

    /**
     * A release played without TMDB (a search without a pick, a season pack) saves its progress
     * under itself: the release played in its place continues that progress under its own key,
     * named after itself, and the failed one's leaves Up next ([PlayerLaunch.replaces]).
     */
    @Test
    fun `another release of a release-only play takes over the failed one's progress`() {
        val watch = WatchTarget(WatchKind.Release, releases[0].item.title)
        val request = request(watch)
        val failed = launch(request, releases[0], sessions.start(request, emptySet()))
        assertEquals(WatchKey.release("nzb-1", 0), failed.watchKey)
        val again = sessions.retry(failed, 1_234_000) as RetryPlan.Again
        assertEquals(listOf(failed.watchKey), again.retry.failedKeys)

        val next = usenetLaunch(again.request, SavedRelease("b", "2", releases[1].item.title, null, fileIndex = 0, nzbId = "nzb-2"), "play-2", again.retry)
        assertEquals(WatchKey.release("nzb-2", 0), next.watchKey)
        assertEquals(listOf(failed.watchKey), next.replaces)
        assertEquals(WatchTarget(WatchKind.Release, releases[1].item.title), next.watch)
        assertEquals(1_234_000L, next.startPositionMs)
        // A first play replaces nothing.
        assertEquals(emptyList(), failed.replaces)

        // That one fails too, perhaps before it saved anything: the third takes over both.
        val third = sessions.retry(next.copy(requestId = sessions.start(again.request, again.retry.failed)), 1_240_000) as RetryPlan.Again
        val last = usenetLaunch(third.request, SavedRelease("c", "3", releases[2].item.title, null, fileIndex = 0, nzbId = "nzb-3"), "play-3", third.retry)
        assertEquals(listOf(failed.watchKey, next.watchKey), last.replaces)
    }

    @Test
    fun `the failed releases are left out of the candidates, also one the store would add back`() {
        val failed = RememberedRelease("a", "1", releases[0].item.title, null, 0, "nzb-1")
        val ordered = Replay.order(listOf(releases[1], releases[2]), failed, stored = true)
        assertEquals(listOf("a/1", "b/2", "c/3"), ordered.map { it.key })
        assertEquals(listOf("c/3"), Retry(setOf("a/1", "b/2"), null).leaveOut(ordered).map { it.key })
    }

    private val provider = ProviderConfig("p", "Provider", "news.example.invalid", 563, "", "", useTls = true)

    /**
     * Try another release from the player's prompt (L10): the release still plays, so it is left out of the request as a
     * failed one is, and kept as the way back (L9), with the providers it was fetched from, for when another plays.
     */
    @Test
    fun `try another from the missing-parts prompt leaves the release out and keeps it as the way back`() {
        val request = request(film)
        val playing = launch(request, releases[0], sessions.start(request, emptySet()))
        val left = Left(playing.release, playing.watchKey, listOf(provider))
        val plan = sessions.retry(playing, 1_234_000, left) as RetryPlan.Again
        assertEquals(Retry(setOf("a/1"), 1_234_000, listOf(WatchKey.movie(900010)), left = left), plan.retry)
        assertEquals(listOf("b/2", "c/3"), plan.retry.leaveOut(request.candidates).map { it.key })
        // An episode with nothing left in its request is searched for again, the same way back kept.
        val alone = request(episode, listOf(releases[0]))
        val one = launch(alone, releases[0], sessions.start(alone, emptySet()))
        val searched = sessions.retry(one, 300_000, Left(one.release, one.watchKey, listOf(provider))) as RetryPlan.SearchEpisode
        assertEquals(one.release, searched.retry.left?.release)
    }

    /** The play sheet's lines for it (QlPhoneSheet): why it switches, and, as the play goes back, which release it went back to. */
    @Test
    fun `the sheet says the release before was missing parts, and names the one it goes back to`() {
        val remux = SavedRelease("a", "1", "The.Quiet.Orbit.2023.2160p.UHD.BluRay.REMUX.DV.HDR10.HEVC.TrueHD.7.1.Atmos-GRP", null, 0, "nzb-1")
        val retry = Retry(setOf("a/1"), 1_234_000, left = Left(remux, WatchKey.movie(900010), listOf(provider)))
        assertEquals("The release before was missing parts · trying another", retry.notice)
        assertEquals("The release that played it was missing parts", retry.searchingNotice)
        assertEquals("The release before was missing parts · trying another · Checking your providers…", checkingProvidersLine(retry))
        assertEquals("No other release played · back to the 2160p Remux, from where you were", retry.left?.backNotice)
        val unnamed = Left(SavedRelease("a", "1", "Quiet Orbit (2023)", null, 0, "nzb-1"), WatchKey.movie(900010), emptyList())
        assertEquals("No other release played · back to the release that was playing, from where you were", unnamed.backNotice)
        // A release that stopped is as before.
        assertEquals("The release before stopped playing · trying another", Retry(setOf("a/1"), 1_234_000).notice)
    }

    /** [item] with the ranker's estimate of its bitrate, [mbps] (null: none, as for a pack whose season isn't counted). */
    private fun sized(guid: String, mbps: Long?) = item("s", guid).let { it.copy(ranked = it.ranked.copy(estimatedBitrate = mbps?.let { m -> m * 1_000_000 })) }

    private fun mbps(n: Long) = n * 1_000_000

    /**
     * Try a smaller release from the slow-stream banner (L8): the request's releases estimated at 0.8 of the playing one's
     * or less, those the stream carries (0.8 of the rate measured or less) first, each in the request's order; the playing
     * one left out of them and kept as the way back (W3), from where it is.
     */
    @Test
    fun `a smaller release is one estimated well under the playing one, those the stream carries first`() {
        val remux = sized("remux", 80)
        val request = request(film, listOf(remux, sized("web", 24), sized("same", 70), sized("hd", 40), sized("unknown", null), sized("tiny", 8)))
        val playing = launch(request, remux, sessions.start(request, emptySet()))
        // 48 Mbps measured: 38.4 carried; 64 is the most a smaller one may be.
        val plan = sessions.smaller(playing, 1_234_000, measuredBps = mbps(48), playingBps = mbps(76)) as RetryPlan.Again
        assertEquals(listOf("s/web", "s/tiny", "s/hd"), plan.request.candidates.map { it.key })
        // Only those: a release Resume remembers from a play before (this one may not have saved yet) isn't put first.
        assertEquals(request.copy(candidates = plan.request.candidates, rememberedFirst = false), plan.request)
        val left = Left(playing.release, playing.watchKey, emptyList(), Left.Why.Smaller, request)
        assertEquals(Retry(setOf("s/remux"), 1_234_000, listOf(WatchKey.movie(900010)), left = left), plan.retry)
    }

    /** Without its own estimate, the playing release is judged by the file's bitrate (its size over its length). */
    @Test
    fun `a playing release with no estimate is judged by the file's bitrate`() {
        val pack = sized("pack", null)
        val request = request(film, listOf(pack, sized("web", 24), sized("hd", 40)))
        val playing = launch(request, pack, sessions.start(request, emptySet()))
        assertEquals(listOf("s/web"), (sessions.smaller(playing, 0, mbps(20), playingBps = mbps(35)) as RetryPlan.Again).request.candidates.map { it.key })
    }

    /** None smaller in the request, a single release (Resume, Choose release, a pack's next episode), or no request at all: no button (W2). */
    @Test
    fun `with nothing smaller to try there is no plan`() {
        val alone = request(episode, listOf(sized("remux", 80)))
        assertEquals(RetryPlan.None, sessions.smaller(launch(alone, alone.candidates[0], sessions.start(alone, emptySet())), 0, mbps(20), mbps(76)))
        val bigger = request(film, listOf(sized("hd", 40), sized("remux", 80), sized("unknown", null)))
        assertEquals(RetryPlan.None, sessions.smaller(launch(bigger, bigger.candidates[0], sessions.start(bigger, emptySet())), 0, mbps(20), mbps(38)))
        assertEquals(RetryPlan.None, sessions.smaller(launch(bigger, bigger.candidates[0], "play-from-an-earlier-process"), 0, mbps(20), mbps(38)))
        assertEquals(RetryPlan.None, sessions.smaller(launch(bigger, bigger.candidates[0], requestId = null), 0, mbps(20), mbps(38)))
    }

    /**
     * The way back after no smaller release played (W3) keeps the play's whole request, as the coordinator launches it
     * ([Retry.kept]): Try another release from the release played again still has the list's others, those of its size
     * and those without an estimate. A smaller release that plays keeps only the smaller ones.
     */
    @Test
    fun `going back after no smaller release played keeps the play's whole request`() {
        val remux = sized("remux", 80)
        val request = request(film, listOf(remux, sized("remux-b", 76), sized("web", 24), sized("unknown", null)))
        val playing = launch(request, remux, sessions.start(request, emptySet()))
        val plan = sessions.smaller(playing, 1_234_000, mbps(48), mbps(76)) as RetryPlan.Again
        // The web release failed, and the play went back to the remux.
        val back = launch(request, remux, sessions.start(plan.retry.kept(plan.request, wentBack = true), plan.retry.failed + "s/web"))
        val again = assertIs<RetryPlan.Again>(sessions.retry(back, 2_000_000), "Try another from the release played again")
        assertEquals(listOf("s/remux-b", "s/unknown"), again.retry.leaveOut(again.request.candidates).map { it.key })
        assertEquals(plan.request, plan.retry.kept(plan.request, wentBack = false))
    }

    /**
     * One known to be missing on the providers in use, which the play would skip without a grab and go straight back, is
     * no smaller release to offer: with nothing else smaller, no button.
     */
    @Test
    fun `a smaller release known to be missing isn't one to try`() {
        val remux = sized("remux", 80)
        val request = request(film, listOf(remux, sized("web", 24), sized("hd", 40)))
        val playing = launch(request, remux, sessions.start(request, emptySet()))
        assertEquals(RetryPlan.None, sessions.smaller(playing, 0, mbps(48), mbps(76), knownMissing = setOf("s/web", "s/hd")))
        val plan = sessions.smaller(playing, 0, mbps(48), mbps(76), knownMissing = setOf("s/web")) as RetryPlan.Again
        assertEquals(listOf("s/hd"), plan.request.candidates.map { it.key })
    }

    /** A release that failed during the play isn't offered again. */
    @Test
    fun `a release that failed during the play is left out`() {
        val remux = sized("remux", 80)
        val request = request(film, listOf(remux, sized("web", 24), sized("hd", 40)))
        val playing = launch(request, remux, sessions.start(request, failed = setOf("s/web")))
        val plan = sessions.smaller(playing, 0, mbps(48), mbps(76)) as RetryPlan.Again
        assertEquals(listOf("s/hd"), plan.request.candidates.map { it.key })
        assertEquals(setOf("s/web", "s/remux"), plan.retry.failed)
    }

    /** The play sheet's lines for it (QlPhoneSheet): switching, from where it was; and, as the play goes back, which release. */
    @Test
    fun `the sheet says it switches to a smaller release, and names the one it goes back to`() {
        val remux = SavedRelease("a", "1", "The.Quiet.Orbit.2023.2160p.UHD.BluRay.REMUX.DV.HDR10.HEVC.TrueHD.7.1.Atmos-GRP", null, 0, "nzb-1")
        val retry = Retry(setOf("a/1"), 1_234_000, left = Left(remux, WatchKey.movie(900010), emptyList(), Left.Why.Smaller))
        assertEquals("Switching to a smaller release · it starts where you were", retry.notice)
        assertEquals("Switching to a smaller release · it starts where you were · Checking your providers…", checkingProvidersLine(retry))
        assertEquals("No smaller release played · back to the 2160p Remux, from where you were", retry.left?.backNotice)
    }

    /**
     * Play a compatible release, from the player (L152, L124 step 2): the launch's request again from where the film is,
     * its releases ranked anew for the Cast device (the list ranked them for this phone), without the one playing, those
     * that failed during the play and those the ranking says the device can't fully play; as an automatic pick, on the
     * device, taking over the playing one's progress and saying nothing of a release before. Without the request, or with
     * none left, no plan: the question offers no such button.
     */
    @Test
    fun `the player's compatible plan is its request ranked for the device, without the playing release, from where it is`() {
        val remux = item("c", "remux")
        val web = item("c", "web")
        val dts = item("c", "dts")
        val broke = item("c", "broke")
        val hd = item("c", "hd")
        val disc = item("c", "disc")
        val request = request(film, listOf(remux, web, dts, broke, hd, disc))
        val playing = launch(request, remux, sessions.start(request, failed = setOf("c/broke"))).copy(cast = kitchen)
        // Kitchen display's order: the HD release first, then the web one, then the DTS remux, which it can't play a sound
        // of, and a disc image, which nothing plays.
        val noSound = dts.copy(ranked = dts.ranked.copy(reasons = listOf(ReleaseReason.NoSoundOn("DTS-HD MA", kitchen.name))))
        val image = disc.copy(ranked = disc.ranked.copy(playable = false))
        var asked: List<String>? = null
        val forKitchen = { items: List<RankedItem> ->
            asked = items.map { it.key }
            listOf(hd, web, noSound, image, remux, broke).filter { r -> items.any { it.key == r.key } }
        }
        val plan = assertIs<RetryPlan.Again>(sessions.compatible(playing, 1_234_000, forKitchen))
        assertEquals(listOf("c/remux", "c/web", "c/dts", "c/broke", "c/hd", "c/disc"), asked, "the request's releases, ranked again")
        assertEquals(listOf("c/hd", "c/web"), plan.request.candidates.map { it.key })
        assertEquals(request.copy(candidates = plan.request.candidates, chosen = false, rememberedFirst = false), plan.request)
        assertEquals(Retry(setOf("c/broke", "c/remux"), 1_234_000, listOf(WatchKey.movie(900010)), midStream = false), plan.retry)
        assertNull(plan.retry.notice, "nothing said of a release before")
        assertEquals(PlayOrigin(cast = kitchen), plan.origin, "on the device")
        // A release picked by hand from a list: the list's others, kept with it, are the ones to play.
        val picked = request(film, listOf(remux)).copy(chosen = true, rememberedFirst = false, alternatives = listOf(web, dts, hd))
        val hand = assertIs<RetryPlan.Again>(sessions.compatible(launch(picked, remux, sessions.start(picked, emptySet())).copy(cast = kitchen), 0, forKitchen))
        assertEquals(listOf("c/hd", "c/web"), hand.request.candidates.map { it.key })
        assertEquals(picked.copy(candidates = hand.request.candidates, chosen = false, alternatives = emptyList()), hand.request)
        // None the device should play, or the request gone: nothing.
        assertEquals(RetryPlan.None, sessions.compatible(playing, 0) { listOf(noSound, remux) })
        assertEquals(RetryPlan.None, sessions.compatible(launch(request, remux, "play-from-an-earlier-process").copy(cast = kitchen), 0, forKitchen))
    }
}
