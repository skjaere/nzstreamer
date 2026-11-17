package io.skjaere.usenet.app.download

import kotlinx.serialization.Serializable

/**
 * The Downloads page (item 19), in the phone's graph alone (L369). A notification's ask may take it to a download's list
 * of releases ([choose]) or play a finished one ([play]), each by its id, once.
 */
@Serializable
data class DownloadsRoute(val choose: String? = null, val play: String? = null)

/**
 * A show's downloads (L372): its finished episodes by season. A show's notification's Play (L437) has it [play] the
 * show's next downloaded episode, once.
 */
@Serializable
data class DownloadsShowRoute(val showTmdbId: Int, val play: Boolean = false)

/** Settings › Downloads (item 20), which the Downloads page's gear opens; phones and tablets alone, as the page. */
@Serializable
data object DownloadSettingsRoute
