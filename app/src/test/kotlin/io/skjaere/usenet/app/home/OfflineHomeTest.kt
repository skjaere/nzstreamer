package io.skjaere.usenet.app.home

import io.skjaere.usenet.app.casting.CastLink
import io.skjaere.usenet.app.download.Tone
import io.skjaere.usenet.app.link.PlayTarget
import io.skjaere.usenet.app.link.TvState
import io.skjaere.usenet.data.CollectedShow
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.data.WatchTitle
import io.skjaere.usenet.link.KeyId
import io.skjaere.usenet.metadata.EpisodeRef
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * L410, L450: Home offline, with no network, on a phone or tablet with a finished download. The line "Offline ·
 * showing downloads", Downloads before Up next, and Up next's rows greyed but for those downloaded, unless a TV chosen in
 * Play on plays them there; a Cast device chosen greys them as This phone does, as the phone would stream what isn't
 * downloaded for it; a greyed row's tap says why in a bar. Otherwise (online, on a TV, or with nothing downloaded) Home is
 * as it was. Invented titles.
 */
class OfflineHomeTest {
    private fun episode(show: Int, name: String, s: Int, e: Int) = WatchState(
        WatchKey.episode(show, s, e), WatchKind.Episode, showTmdbId = show, season = s, episode = e, title = WatchTitle.episode(name, s, e),
        positionMs = 600_000, durationMs = 2_700_000, updatedAt = 0,
    )

    private fun resume(show: Int, name: String, s: Int, e: Int) = UpNextRow(
        name, "S0${s}E0$e · 30 min left", 0.2f, false, null, UpNextPlay.Resume(episode(show, name, s, e)), "Resume $name S0${s}E0$e",
    )

    private val meridian = resume(QM, "Quiet Meridian", 1, 3)
    private val harbor = UpNextRow(
        "Harbor Lights", "S02E05 · The Long Tide", null, false, null,
        UpNextPlay.NextEpisode(CollectedShow(CollectedShow.idFor(HL), HL, name = "Harbor Lights", addedAt = 0, updatedAt = 0), EpisodeRef(2, 5)),
        "Play Harbor Lights S02E05",
    )
    private val crown = UpNextRow(
        "Copper Crown", "2022 · 1 h 12 min left", 0.4f, false, null,
        UpNextPlay.Resume(WatchState(WatchKey.movie(CC), WatchKind.Movie, movieTmdbId = CC, title = "Copper Crown", positionMs = 900_000, durationMs = 6_000_000, updatedAt = 0)),
        "Resume Copper Crown",
    )

    /** A release played without TMDB, which a TV can't search for: it plays on this phone whatever is chosen. */
    private val lantern = UpNextRow(
        "Lantern Field", "S01E02 · 12 min left", 0.6f, false, null,
        UpNextPlay.Resume(
            WatchState(WatchKey.release("n-lantern", 0), WatchKind.Release, title = "Lantern.Field.S01E02.720p.WEB.h264-GRP", positionMs = 900_000, durationMs = 3_000_000, updatedAt = 0),
        ),
        "Resume Lantern Field S01E02",
    )

    private val upNext = listOf(meridian, harbor, crown, lantern)

    private val downloads = HomeDownloads(
        3_000_000_000, listOf(HomeDownloadRow("show-$HL", "Harbor Lights", "2 episodes · 3.0 GB", Tone.Muted, null, HomeDownloadOpen.Show(HL))),
    )

    /** Harbor Lights' next episode is downloaded, and so is the film. */
    private val downloaded = setOf(WatchKey.episode(HL, 2, 5), WatchKey.movie(CC))

    private val tv = PlayTarget.Tv(KeyId.of(Random.nextBytes(91)), "Living room TV", TvState.Ready)
    private val cast = PlayTarget.Cast("route-1", "Kitchen display", CastLink.Connected)

    @Test
    fun `as it was online, on a TV, and with nothing downloaded`() {
        assertNull(OfflineHome.of(upNext, downloads, downloaded, offline = false, playOn = PlayTarget.Phone), "online")
        // Where downloads don't show (a TV, or a phone's layout on it) Home is given no keys and no section.
        assertNull(OfflineHome.of(upNext, null, emptySet(), offline = true, playOn = null), "a TV")
        // Something queued, nothing finished: nothing plays offline, so Home is as it was.
        assertNull(OfflineHome.of(upNext, downloads, emptySet(), offline = true, playOn = PlayTarget.Phone), "nothing finished")
    }

    @Test
    fun `offline with a finished download, the line, Downloads first and what isn't downloaded greyed`() {
        val home = assertNotNull(OfflineHome.of(upNext, downloads, downloaded, offline = true, playOn = PlayTarget.Phone))
        assertEquals("Offline · showing downloads", OfflineHome.LINE)
        assertEquals(setOf(meridian.key, lantern.key), home.greyed)
        // On a phone without Play on's choice (Home's own null), as on This phone.
        assertEquals(home, OfflineHome.of(upNext, downloads, downloaded, offline = true, playOn = null))
    }

    /**
     * L458, L472: a release played without TMDB and downloaded from Import plays its file offline, so its Up next row isn't
     * greyed, whatever is chosen: here, cast from the phone, or here with a TV chosen.
     */
    @Test
    fun `an Up next row of a file downloaded from Import is never greyed offline`() {
        val imported = downloaded + WatchKey.release("n-lantern", 0)
        listOf(PlayTarget.Phone, cast, tv, null).forEach { playOn ->
            val home = assertNotNull(OfflineHome.of(upNext, downloads, imported, offline = true, playOn = playOn))
            assertFalse(lantern.key in home.greyed, "greyed with $playOn")
        }
    }

    @Test
    fun `a TV chosen greys only what plays here, and a Cast device chosen greys what isn't downloaded as This phone does`() {
        // A TV chosen plays what it can search for itself; a release without TMDB still plays here, so it greys.
        assertEquals(setOf(lantern.key), assertNotNull(OfflineHome.of(upNext, downloads, downloaded, offline = true, playOn = tv)).greyed)
        // A Cast device plays what the phone serves: what isn't downloaded the phone would stream, so it greys; the
        // downloaded ones cast their files (L450). Connecting, the same.
        listOf(cast, cast.copy(link = CastLink.Connecting)).forEach { device ->
            assertEquals(setOf(meridian.key, lantern.key), assertNotNull(OfflineHome.of(upNext, downloads, downloaded, offline = true, playOn = device)).greyed)
        }
        // Online, nothing of it, whatever is chosen.
        listOf(PlayTarget.Phone, tv, cast).forEach { assertNull(OfflineHome.of(upNext, downloads, downloaded, offline = false, playOn = it), "online with $it") }
    }

    @Test
    fun `a greyed row's tap says what isn't downloaded and that it plays once online`() {
        assertEquals("Quiet Meridian S01E03 isn't downloaded. It plays once you're online.", OfflineHome.bar(meridian))
        assertEquals("Harbor Lights S02E05 isn't downloaded. It plays once you're online.", OfflineHome.bar(harbor))
        assertEquals("Copper Crown isn't downloaded. It plays once you're online.", OfflineHome.bar(crown))
        // A release by what its name says, as its Play's words name it.
        assertEquals("Lantern Field S01E02 isn't downloaded. It plays once you're online.", OfflineHome.bar(lantern))
    }

    private companion object {
        /** Invented titles; nothing real has these ids. */
        const val HL = 990_801
        const val QM = 990_802
        const val CC = 990_803
    }
}
