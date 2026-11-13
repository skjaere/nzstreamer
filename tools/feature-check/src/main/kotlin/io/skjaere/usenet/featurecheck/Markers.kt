package io.skjaere.usenet.featurecheck

/** A sign of a feature in a build: [text] in its bytes; [what] it is, for the report. */
data class Marker(val what: String, val text: String)

/**
 * Discover's signs. The app's debug, bench and beta builds have Discover; the release (Play) build must carry none of it: its
 * classes' packages, its canaries, TMDB's list paths or JustWatch's name in any dex or packaged file; a const-string
 * with its name in the app's own classes; "discover" in a resource, an asset or the manifest.
 */
object Discover {
    val packages = listOf("io/skjaere/usenet/discover/", "io/skjaere/usenet/app/discover/")

    /** Every canary starts so; [canaries] are those the debug build has, :core:discover's and the app's. */
    const val CANARY = "nzs.discover.canary"
    val canaries = listOf("$CANARY.core", "$CANARY.app")

    /** TMDB's list paths. :core:discover asks for each as a whole string, and its tests check each holds one of these. */
    val listPaths = listOf(
        "trending/", "discover/movie", "discover/tv", "movie/popular", "movie/top_rated", "movie/now_playing", "movie/upcoming",
        "tv/popular", "tv/top_rated", "tv/airing_today", "tv/on_the_air", "watch/providers", "genre/movie/list", "genre/tv/list",
    )

    const val JUSTWATCH = "JustWatch"

    /** In a const-string of the app's own classes ([APP_CLASSES]). */
    const val NAME = "Discover"

    /** The app's and its core modules' classes, not the libraries': one of them logs "Discovered 3 volume(s)". */
    const val APP_CLASSES = "Lio/skjaere/usenet/"

    /** In a resource's or an asset's name, a resource table or the manifest (but a library's names there), in any case. */
    const val WORD = "discover"

    val forbidden: List<Marker> = packages.map { Marker("a Discover class", it) } + Marker("a Discover canary", CANARY) +
        listPaths.map { Marker("a TMDB list path", it) } + Marker("JustWatch's name", JUSTWATCH)

    /** What the debug build must have, or the release check could pass without seeing what it looks for. */
    val required: List<Marker> = packages.map { Marker("Discover's classes", it) } + canaries.map { Marker("a Discover canary", it) } +
        listPaths.map { Marker("a TMDB list path", it) }
}
