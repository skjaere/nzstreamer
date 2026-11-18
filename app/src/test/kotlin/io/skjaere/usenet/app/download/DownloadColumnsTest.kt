package io.skjaere.usenet.app.download

import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.newznab.TvIds
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * L392, L403, L405, L434: Room 15's three columns on a download's row as the app reads and writes them ([DownloadColumns]):
 * a season row's search facts ([SeasonSearch]), a file chosen by hand ([DownloadPick]) and an indexer's limit waited out
 * ([LimitWait]). Each comes back as written; a field a later version adds is passed over; a value that can't be read is
 * none, never a failure; and the search facts give the finder the request a play of the same episode asks. Names and ids
 * are invented.
 */
class DownloadColumnsTest {
    private val play = EpisodePlay(
        showTmdbId = 900002, showName = "Harbor Lights", showYear = 2021, aliases = listOf("Hafenlichter"),
        ids = TvIds(tvdb = 700002, tmdb = 900002, imdb = "tt9000002", tvmaze = 50002), season = 2, episode = 5, episodeName = "Low Water",
        airDate = LocalDate.of(2023, 3, 14), runtimeMinutes = 52, originalLanguage = "de", seasonEpisodes = 10,
    )
    private val search = SeasonSearch(
        name = "Harbor Lights", year = 2021, aliases = listOf("Hafenlichter"), tvdb = 700002, tmdb = 900002, imdb = "tt9000002", tvmaze = 50002,
        originalLanguage = "de", airDate = "2023-03-14", runtimeMinutes = 52, seasonEpisodes = 10, searched = true,
    )
    private val pick = DownloadPick(index = 3, name = "Harbor.Lights.S02E05.1080p.WEB.h264-PACK.mkv", size = 1_412_345_678, nzbId = "a1b2c3d4")
    private val wait = LimitWait(indexerId = "fake-indexer", kind = LimitWait.Kind.Search, since = 1_700_000_000_000, until = 1_700_003_600_000)

    @Test
    fun eachFormComesBackAsWritten() {
        val row = row().copy(search = DownloadColumns.text(search), pick = DownloadColumns.text(pick), limitWait = DownloadColumns.text(wait))
        assertEquals(search, DownloadColumns.search(row))
        assertEquals(pick, DownloadColumns.pick(row))
        assertEquals(wait, DownloadColumns.limitWait(row))

        // The facts a season sheet may not have, and a download limit, which comes with no end.
        val bare = SeasonSearch(name = "Quiet Meridian")
        val download = LimitWait(indexerId = "fake-indexer", kind = LimitWait.Kind.Download, since = 1_700_000_000_000)
        val other = row().copy(search = DownloadColumns.text(bare), limitWait = DownloadColumns.text(download))
        assertEquals(bare, DownloadColumns.search(other))
        assertEquals(false, DownloadColumns.search(other)?.searched)
        assertEquals(download, DownloadColumns.limitWait(other))
        assertNull(DownloadColumns.limitWait(other)?.until)
    }

    /**
     * L461: a debrid account's limit a row waits out comes back as written: the Service kind, the account's id in the id's
     * field, when it was met and when its gate opens; as JSON, with the kind by its name.
     */
    @Test
    fun aDebridAccountsLimitComesBackWithItsServiceKind() {
        val service = LimitWait(indexerId = "4c1d9e70-account-a", kind = LimitWait.Kind.Service, since = 1_700_000_000_000, until = 1_700_007_200_000)
        val text = DownloadColumns.text(service)
        assertEquals("""{"indexerId":"4c1d9e70-account-a","kind":"Service","since":1700000000000,"until":1700007200000}""", text)
        assertEquals(service, DownloadColumns.limitWait(row().copy(limitWait = text)))
        assertEquals(1_700_007_200_000, DownloadColumns.limitWait(row().copy(limitWait = text))?.end())
    }

    @Test
    fun aRowWithoutThemHasNone() {
        val row = row()
        assertNull(DownloadColumns.search(row))
        assertNull(DownloadColumns.pick(row))
        assertNull(DownloadColumns.limitWait(row))
    }

    @Test
    fun aFieldALaterVersionAddsIsPassedOver() {
        val row = row().copy(
            search = """{"name":"Harbor Lights","year":2021,"searched":true,"network":"Channel Nine"}""",
            pick = """{"index":3,"name":"Harbor.Lights.S02E05.1080p.WEB.h264-PACK.mkv","size":1412345678,"nzbId":"a1b2c3d4","volume":"sd"}""",
            limitWait = """{"indexerId":"fake-indexer","kind":"Search","since":1700000000000,"until":1700003600000,"note":{"left":0}}""",
        )
        assertEquals(SeasonSearch(name = "Harbor Lights", year = 2021, searched = true), DownloadColumns.search(row))
        assertEquals(pick, DownloadColumns.pick(row))
        assertEquals(wait, DownloadColumns.limitWait(row))
    }

    @Test
    fun aValueThatCantBeReadIsNone() {
        listOf("", "not json", "null", "[]", "{}", """{"name":7,"aliases":"one"}""", """{"index":"third"}""", """{"indexerId":"x","kind":"Grab","since":1}""")
            .forEach { garbage ->
                val row = row().copy(search = garbage, pick = garbage, limitWait = garbage)
                assertNull(DownloadColumns.search(row), garbage)
                assertNull(DownloadColumns.pick(row), garbage)
                assertNull(DownloadColumns.limitWait(row), garbage)
            }
    }

    @Test
    fun theSearchFactsAskTheFinderWhatAPlayOfTheSameEpisodeAsks() {
        val facts = SeasonSearch.of(play.request)
        assertEquals(search.copy(searched = false), facts)
        assertEquals(play.request, facts.request(play.season, play.episode))
        // As the row keeps them: through its column and back.
        val row = row().copy(search = DownloadColumns.text(facts))
        assertEquals(play.request, DownloadColumns.search(row)?.request(play.season, play.episode))

        // An episode with no air date, runtime or season count, of a show with no ids: as its play asks too.
        val plain = play.copy(aliases = emptyList(), ids = TvIds(), airDate = null, runtimeMinutes = null, originalLanguage = null, seasonEpisodes = null)
        assertEquals(plain.request, SeasonSearch.of(plain.request).request(plain.season, plain.episode))
        // A date that can't be read is no date: the search goes on by number and name.
        assertNull(search.copy(airDate = "14 March").request(2, 5).airDate)
    }

    private fun row() = Download(
        id = "5d1e8f3a-2b7c-4c19-9e6a-0f1b2c3d4e5f", watchKey = WatchKey.episode(900002, 2, 5), kind = DownloadKind.Episode, title = "Harbor Lights",
        subtitle = "S02E05 · Low Water", showTmdbId = 900002, season = 2, episode = 5, seasonEpisodes = 10, position = 0, createdAt = 1, updatedAt = 1,
    )
}
