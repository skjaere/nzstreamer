package io.skjaere.usenet.mockseed

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * The README screenshots' releases (`indexer --catalog screens`), over the invented titles of the screens catalogue,
 * the JSON file the device tests' strict FakeTmdb answers from and tools/screens/art.py draws for: the ids come from
 * there, so a search by any of them finds these releases. The release names are made here, every group one of two
 * invented NZS tags. Each lists a size of its own, as real releases of a 48-minute episode or a film would have.
 *
 * Only [PLAYABLE] are seeded whole, with the clip under their names. The others are seeded with every article missing,
 * so they list, rank and show their reasons, and a play of one fails at once rather than play the clip as something
 * it isn't.
 */
object ScreensCatalog {
    /** The catalogue, from the repository's top folder (where `./gradlew :tools:mock-seed:run` runs). */
    const val FILE = "app/src/androidTest/assets/screens/catalog.json"

    /** Seeded whole: the release the screenshots play, and the episode Up next resumes. */
    val PLAYABLE = setOf("kelpmoor-s01e05-repack", "kelpmoor-s01e04")

    private const val GB = 1_000_000_000L

    /** One title of the catalogue: its name and the ids an indexer knows it by. */
    data class Title(val slug: String, val name: String, val year: Int?, val ids: FakeIds, val episodes: Set<Pair<Int, Int>>)

    fun titles(file: File = File(FILE)): Map<String, Title> {
        val root = Json.parseToJsonElement(file.readText()).jsonObject
        val shows = root.getValue("shows").jsonArray.map { it.jsonObject }.map { show ->
            val ids = show.getValue("ids").jsonObject
            val episodes = show.getValue("seasons").jsonArray.map { it.jsonObject }.flatMap { season ->
                val number = season.getValue("number").jsonPrimitive.int
                val count = (season.getValue("episodes") as? JsonArray)?.size ?: season.getValue("episodes").jsonPrimitive.int
                (1..count).map { number to it }
            }.toSet()
            Title(
                slug = show.string("slug"), name = show.string("name"), year = null,
                ids = FakeIds(tvdb = ids.int("tvdb"), tmdb = ids.int("tmdb"), imdb = ids.string("imdb"), tvmaze = ids.int("tvmaze")),
                episodes = episodes,
            )
        }
        val movies = root.getValue("movies").jsonArray.map { it.jsonObject }.map { movie ->
            val ids = movie.getValue("ids").jsonObject
            Title(
                slug = movie.string("slug"), name = movie.string("title"), year = movie.getValue("year").jsonPrimitive.int,
                ids = FakeIds(tmdb = ids.int("tmdb"), imdb = ids.string("imdb")), episodes = emptySet(),
            )
        }
        return (shows + movies).associateBy { it.slug }
    }

    fun releases(file: File = File(FILE)): List<FakeRelease> {
        val titles = titles(file)
        fun title(slug: String) = titles[slug] ?: error("the screens catalogue has no title $slug")
        val kelpmoor = title("kelpmoor")
        val bollard = title("the-ninth-bollard")
        return listOf(
            // The release list the screenshots show: one episode, five releases a phone ranks apart.
            episode(kelpmoor, 1, 5, "kelpmoor-s01e05-repack", "REPACK.1080p.WEB-DL.AAC2.0.H.264-NZSALT", 1.52, grabs = 410, ageDays = 2, audio = "AAC", language = "English"),
            episode(kelpmoor, 1, 5, "kelpmoor-s01e05-web", "1080p.WEB-DL.AAC2.0.H.264-NZSDEMO", 1.47, grabs = 380, ageDays = 3),
            episode(kelpmoor, 1, 5, "kelpmoor-s01e05-uhd", "2160p.WEB.H265.10bit.HDR.DDP5.1-NZSDEMO", 5.84, grabs = 220, ageDays = 2, category = FakeCatalog.TV_UHD),
            episode(kelpmoor, 1, 5, "kelpmoor-s01e05-hevc", "1080p.WEB.H265.10bit.AAC2.0-NZSALT", 0.86, grabs = 150, ageDays = 3),
            episode(kelpmoor, 1, 5, "kelpmoor-s01e05-720p", "720p.WEB-DL.AAC2.0.H.264-NZSDEMO", 0.74, grabs = 95, ageDays = 3),
            // Up next's three rows: a resume, a next episode and a new one.
            episode(kelpmoor, 1, 4, "kelpmoor-s01e04", "1080p.WEB-DL.AAC2.0.H.264-NZSDEMO", 1.49, grabs = 520, ageDays = 9),
            episode(title("quillon-station"), 1, 3, "quillon-station-s01e03", "1080p.WEB-DL.AAC2.0.H.264-NZSDEMO", 1.38, grabs = 640, ageDays = 30),
            episode(title("the-gullenkiln-archive"), 1, 4, "the-gullenkiln-archive-s01e04", "1080p.WEB-DL.AAC2.0.H.264-NZSALT", 1.61, grabs = 75, ageDays = 1),
            // A film, for a search by film.
            movie(bollard, "the-ninth-bollard-1080p", "1080p.BluRay.x264-NZSDEMO", 9.6, grabs = 700, ageDays = 200),
            movie(bollard, "the-ninth-bollard-2160p", "2160p.UHD.BluRay.x265.10bit.HDR.DTS-HD.MA.5.1-NZSALT", 24.8, grabs = 310, ageDays = 180),
        )
    }

    /** Every part listed and none posted, for a release that isn't [PLAYABLE]; none missing for one that is. */
    fun missingParts(release: FakeRelease, parts: Int): Set<Int> = if (release.guid in PLAYABLE) emptySet() else (1..parts).toSet()

    /** A title's words as release names have them: "The Ninth Bollard" is The.Ninth.Bollard, "A's" is As. */
    fun dotted(name: String): String = name.replace("'", "").split(Regex("[^A-Za-z0-9]+")).filter { it.isNotEmpty() }.joinToString(".")

    private fun episode(
        title: Title, season: Int, episode: Int, guid: String, tail: String, gb: Double, grabs: Int, ageDays: Int,
        category: Int = FakeCatalog.TV_HD, audio: String? = null, language: String? = null,
    ): FakeRelease {
        check(season to episode in title.episodes) { "${title.name} has no S%02dE%02d in the screens catalogue".format(season, episode) }
        val name = "${dotted(title.name)}.S%02dE%02d.$tail".format(season, episode)
        return FakeRelease(
            guid = guid, title = name, category = category, ids = title.ids, season = season, episode = episode, grabs = grabs,
            ageDays = ageDays, fileNames = listOf("$name.mkv"), audio = audio, language = language, sizeBytes = (gb * GB).toLong(),
        )
    }

    private fun movie(title: Title, guid: String, tail: String, gb: Double, grabs: Int, ageDays: Int): FakeRelease {
        val name = "${dotted(title.name)}.${title.year}.$tail"
        return FakeRelease(
            guid = guid, title = name, category = FakeCatalog.MOVIES_HD, ids = title.ids, grabs = grabs, ageDays = ageDays,
            fileNames = listOf("$name.mkv"), sizeBytes = (gb * GB).toLong(),
        )
    }

    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int
}
