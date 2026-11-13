package io.skjaere.usenet.mockseed

/** Ids of the invented show and movie; real indexers would carry TheTVDB/TMDB/IMDb ids. */
data class FakeIds(val tvdb: Int? = null, val tmdb: Int? = null, val imdb: String? = null, val tvmaze: Int? = null)

/**
 * One release the fake indexer lists. Names claim codecs that the content (always the same
 * H.264 test clip) doesn't have; that is fine, ranking only reads the claims.
 */
data class FakeRelease(
    val guid: String,
    val title: String,
    val category: Int,
    val ids: FakeIds,
    val season: Int? = null,
    val episode: Int? = null,
    val passworded: Boolean = false,
    val grabs: Int = 0,
    val ageDays: Int = 1,
    /** File names inside the release; a season pack has several. */
    val fileNames: List<String>,
    /** Segments listed in the NZB but absent from the server. */
    val missingParts: Set<Int> = emptySet(),
    /** Newznab `audio`/`language` attributes, as indexers with MediaInfo scans send them. */
    val audio: String? = null,
    val language: String? = null,
    /** Which clip the files contain. */
    val clip: Clip = Clip.Default,
)

enum class Clip {
    /** H.264 + AAC: plays everywhere. */
    Default,
    /** H.264 + E-AC-3: no sound on devices without an E-AC-3 decoder (the emulator, many phones). */
    Eac3,
}

object FakeCatalog {
    const val TV_HD = 5040
    const val TV_UHD = 5045
    const val MOVIES_HD = 2040

    fun releases(show: FakeIds = HARBOR_LIGHTS, movie: FakeIds = COPPER_CROWN): List<FakeRelease> = listOf(
        // S01E01: four releases, so ranking and fallback have something to do.
        // BROKEN ranks first (PROPER, most grabs) but has an article missing mid-file.
        episode("hl-s01e01-broken", "Harbor.Lights.S01E01.PROPER.1080p.WEB-DL.AAC2.0.H.264-BROKEN", show, 1, 1, grabs = 300, missingParts = setOf(7)),
        episode("hl-s01e01-good", "Harbor.Lights.S01E01.1080p.WEB-DL.AAC2.0.H.264-GOOD", show, 1, 1, grabs = 120, audio = "AAC", language = "English"),
        episode("hl-s01e01-passwd", "Harbor.Lights.S01E01.1080p.BluRay.x264-PASSWD", show, 1, 1, grabs = 500, passworded = true),
        episode("hl-s01e01-tenbit", "Harbor.Lights.S01E01.2160p.WEB.H265.10bit.HDR.DDP5.1-TENBIT", show, 1, 1, grabs = 80, category = TV_UHD),
        // S01E02: one plain release and a German dual-language one.
        episode("hl-s01e02-good", "Harbor.Lights.S01E02.1080p.WEB-DL.AAC2.0.H.264-GOOD", show, 1, 2, grabs = 90),
        episode("hl-s01e02-german", "Harbor.Lights.S01E02.German.DL.720p.WEB.h264-GERGRP", show, 1, 2, grabs = 40),
        // S01E03 exists only inside the season pack.
        FakeRelease(
            guid = "hl-s01-pack",
            title = "Harbor.Lights.S01.1080p.WEB-DL.AAC2.0.H.264-PACK",
            category = TV_HD,
            ids = show,
            season = 1,
            grabs = 60,
            ageDays = 3,
            fileNames = (1..3).map { "Harbor.Lights.S01E%02d.1080p.WEB-DL.AAC2.0.H.264-PACK.mkv".format(it) },
        ),
        // Mislabelled: the name says AAC, the file has E-AC-3. Ranks first (PROPER, grabs);
        // only the track probe can tell, and playback falls back to GOOD.
        FakeRelease(
            guid = "cc-2019-eac3",
            title = "Copper.Crown.2019.PROPER.1080p.WEB-DL.AAC2.0.H.264-MISLABEL",
            category = MOVIES_HD,
            ids = movie,
            grabs = 900,
            ageDays = 150,
            fileNames = listOf("Copper.Crown.2019.PROPER.1080p.WEB-DL.AAC2.0.H.264-MISLABEL.mkv"),
            clip = Clip.Eac3,
        ),
        FakeRelease(
            guid = "cc-2019-good",
            title = "Copper.Crown.2019.1080p.BluRay.x264-GOOD",
            category = MOVIES_HD,
            ids = movie,
            grabs = 700,
            ageDays = 200,
            fileNames = listOf("Copper.Crown.2019.1080p.BluRay.x264-GOOD.mkv"),
        ),
    )

    private fun episode(
        guid: String,
        title: String,
        ids: FakeIds,
        season: Int,
        episode: Int,
        grabs: Int,
        passworded: Boolean = false,
        missingParts: Set<Int> = emptySet(),
        category: Int = TV_HD,
        audio: String? = null,
        language: String? = null,
    ) = FakeRelease(
        guid = guid, title = title, category = category, ids = ids, season = season, episode = episode,
        passworded = passworded, grabs = grabs, fileNames = listOf("$title.mkv"), missingParts = missingParts,
        audio = audio, language = language,
    )

    val HARBOR_LIGHTS = FakeIds(tvdb = 900001, tmdb = 900002, imdb = "tt9000003", tvmaze = 900004)
    val COPPER_CROWN = FakeIds(tmdb = 900011, imdb = "tt9000012")
}
