package io.skjaere.usenet.app.download

import io.skjaere.usenet.data.Download
import io.skjaere.usenet.newznab.TvIds
import io.skjaere.usenet.search.EpisodeRequest
import io.skjaere.usenet.search.ShowRef
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * A season row's search facts ([Download.search], L392): what the finder is asked for its episode at the row's turn, kept
 * from the season sheet's request so the turn needs no TMDB call. The show's [name], [year], [aliases], ids ([tvdb],
 * [tmdb], [imdb], [tvmaze]) and [originalLanguage]; the episode's [airDate] (ISO 8601, as [LocalDate] writes it) and
 * [runtimeMinutes]; the season's episode count ([seasonEpisodes]); and whether the row's turn has [searched] yet. The
 * episode's numbers are the row's own ([request]). Facts taken from TMDB, so the runner clears them at Done (L442).
 */
@Serializable
data class SeasonSearch(
    val name: String,
    val year: Int? = null,
    val aliases: List<String> = emptyList(),
    val tvdb: Int? = null,
    val tmdb: Int? = null,
    val imdb: String? = null,
    val tvmaze: Int? = null,
    val originalLanguage: String? = null,
    val airDate: String? = null,
    val runtimeMinutes: Int? = null,
    val seasonEpisodes: Int? = null,
    val searched: Boolean = false,
) {
    /** What the finder is asked for episode [episode] of season [season]: as a play of it asks ([EpisodeRequest]). */
    fun request(season: Int, episode: Int) = EpisodeRequest(
        ShowRef(name, year, aliases, TvIds(tvdb, tmdb, imdb, tvmaze), originalLanguage), season, episode, date(), runtimeMinutes, seasonEpisodes,
    )

    /** A date that can't be read is none: the search goes on by the episode's number and name. */
    private fun date(): LocalDate? = airDate?.let {
        try {
            LocalDate.parse(it)
        } catch (e: DateTimeParseException) {
            null
        }
    }

    companion object {
        /** [request]'s facts, not yet searched. */
        fun of(request: EpisodeRequest) = with(request.show) {
            SeasonSearch(
                name, year, aliases, ids.tvdb, ids.tmdb, ids.imdb, ids.tvmaze, originalLanguage, request.airDate?.toString(), request.runtimeMinutes,
                request.seasonEpisodes,
            )
        }
    }
}

/**
 * A file chosen by hand in a pack, Choose file's pick in download mode ([Download.pick], L403): its [index] and [name] in
 * the NZB, its exact [size], and the NZB's id ([nzbId]), whose NZB is the row's kept grab. Kept apart from the row's
 * release, which Resolve's same-release test and the launch read; a new release clears it.
 */
@Serializable
data class DownloadPick(val index: Int, val name: String, val size: Long, val nzbId: String)

/**
 * An indexer's limit a row waits out ([Download.limitWait], L434): the indexer's id ([indexerId]), which limit ([kind]),
 * when it was met ([since], epoch milliseconds) and when it ends where the indexer said ([until]: a search limit's
 * Retry-After; a download limit comes without one). The row's reason is fixed words; the page names the indexer from
 * this at draw time, so no notification or log line does.
 */
@Serializable
data class LimitWait(val indexerId: String, val kind: Kind, val since: Long, val until: Long? = null) {
    enum class Kind { Download, Search }

    /**
     * When it is taken to have ended (L434, L443): where the indexer said, else [GUESS_MILLIS] after it was met, so a switch
     * away from the app and back doesn't ask the indexer again at once.
     */
    fun end(): Long = until ?: (since + GUESS_MILLIS)

    companion object {
        /** How long a limit that came with no end is taken to last: a guess, 30 minutes. */
        const val GUESS_MILLIS = 30 * 60_000L
    }
}

/**
 * Room 15's three columns ([Download.search], [Download.pick], [Download.limitWait], L405) as the app reads and writes
 * them: JSON, as [Download.video] and [Download.tracks] are, since core:data sees none of these types. Read as the
 * player's launch reads its own: what a later version adds is passed over, and a value that can't be read is none, never
 * a failure.
 */
object DownloadColumns {
    private val json = Json { ignoreUnknownKeys = true }

    fun search(row: Download): SeasonSearch? = read(SeasonSearch.serializer(), row.search)

    fun pick(row: Download): DownloadPick? = read(DownloadPick.serializer(), row.pick)

    fun limitWait(row: Download): LimitWait? = read(LimitWait.serializer(), row.limitWait)

    fun text(search: SeasonSearch): String = json.encodeToString(SeasonSearch.serializer(), search)

    fun text(pick: DownloadPick): String = json.encodeToString(DownloadPick.serializer(), pick)

    fun text(wait: LimitWait): String = json.encodeToString(LimitWait.serializer(), wait)

    // SerializationException, which every malformed or mistyped value throws, is an IllegalArgumentException.
    private fun <T> read(serializer: KSerializer<T>, text: String?): T? = text?.let {
        try {
            json.decodeFromString(serializer, it)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
