package io.skjaere.usenet.app.screens

/**
 * Which build may take the README's screenshots: the bench build with debrid hidden alone, as tools/screens/capture.sh
 * builds it (-PbenchBuild=true -PhideDebrid=true). That is the app as the published beta shows it, with no developer
 * settings.
 */
internal object ScreensRun {
    const val BENCH = "bench"

    /**
     * Why a build of [buildType], with debrid hidden or not ([hideDebrid]), can't take the README's screenshots, or null
     * when it can.
     */
    fun buildProblem(buildType: String, hideDebrid: Boolean): String? = when {
        buildType != BENCH -> "the README screenshots are taken on the $BENCH build, not the $buildType build: run tools/screens/capture.sh"
        !hideDebrid -> "the README screenshots are taken with debrid hidden (-PhideDebrid=true): run tools/screens/capture.sh"
        else -> null
    }
}
