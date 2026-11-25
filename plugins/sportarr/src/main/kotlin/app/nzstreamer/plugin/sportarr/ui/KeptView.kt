package app.nzstreamer.plugin.sportarr.ui

import app.nzstreamer.plugin.sportarr.source.Wire
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext

/** A league picked in the league chips: Sportarr's [id], and its [name], which its chip shows while it isn't listed. */
internal data class LeagueChoice(val id: Int, val name: String)

/**
 * The view's choices kept per device (L645): the [sport] picked as Sportarr names it (null: All), the [league] picked in
 * it (null: All leagues), and whether watched events show. They narrow this view alone: what is followed stays
 * Sportarr's (W163 a).
 */
internal data class ViewChoices(val sport: String? = null, val league: LeagueChoice? = null, val showWatched: Boolean = false)

/**
 * view.json in the plugin's own folder [dir] (L645): the view's choices and nothing else, no title, row or list. Written
 * whole to view.json.tmp, then renamed over the last; read tolerantly, so a torn or unknown file is no choice. Both on
 * [io], off the main thread (L641).
 */
internal class KeptView(private val dir: File, private val io: CoroutineContext = Dispatchers.IO) {
    private val file get() = File(dir, NAME)

    /** The choices kept, or null when there are none to read. */
    suspend fun read(): ViewChoices? = withContext(io) {
        try {
            val kept = Wire.json.decodeFromString(ViewFile.serializer(), file.readText())
            if (kept.format != FORMAT) return@withContext null
            val league = kept.league?.let { id -> kept.leagueName?.let { LeagueChoice(id, it) } }
            ViewChoices(kept.sport?.takeIf { it.isNotBlank() }, league, kept.showWatched)
        } catch (e: IOException) {
            // No file yet, or one that can't be read: no choice.
            null
        } catch (e: IllegalArgumentException) {
            log.info("Sports' kept view is unreadable: read as none")
            null
        }
    }

    /** Keeps [choices] in place of the last; false, the last kept as it was, when they can't be written. */
    suspend fun write(choices: ViewChoices): Boolean = withContext(io) {
        val temporary = File(dir, "$NAME.tmp")
        try {
            dir.mkdirs()
            val written = ViewFile(FORMAT, choices.sport, choices.league?.id, choices.league?.name, choices.showWatched)
            temporary.writeText(Wire.json.encodeToString(ViewFile.serializer(), written))
            if (!temporary.renameTo(file)) throw IOException("Not renamed")
            true
        } catch (e: IOException) {
            log.warn("Sports' view wasn't kept: {}", e.javaClass.simpleName)
            false
        }
    }

    /** view.json as written, format 1: a field missing reads as none chosen. */
    @Serializable
    private class ViewFile(
        val format: Int,
        val sport: String? = null,
        val league: Int? = null,
        val leagueName: String? = null,
        val showWatched: Boolean = false,
    )

    private companion object {
        const val NAME = "view.json"
        const val FORMAT = 1
        val log = LoggerFactory.getLogger(KeptView::class.java)
    }
}
