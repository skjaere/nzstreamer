package app.nzstreamer.plugin.sportarr.source

import app.nzstreamer.plugin.sportarr.model.CardType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlin.coroutines.CoroutineContext

/**
 * The list as one read gave it (L641): the time it was read, the leagues that count, their events in the window (the
 * declared fields alone, L653), newest first, and whether a page budget or the cap of 5,000 cut anything. [cardTypes]
 * are the fight cards' types the model decided before, by event key: kept with the list, as a card's type names its
 * parts and so its parts' keys, which never change (L644), while a weekly show's type depends on what else is listed.
 */
data class SportsList(
    val readAt: Instant,
    val leagues: List<KeptLeague>,
    val events: List<WireEvent>,
    val cut: Boolean,
    val cardTypes: Map<String, CardType> = emptyMap(),
)

/**
 * A league the list keeps: its id, name and sport, and its other names, which the motorsport rules read as the series'
 * forms (L649) when the list comes from the file rather than a read.
 */
@Serializable
data class KeptLeague(val id: Int, val name: String, val sport: String? = null, val alternateName: String? = null)

/**
 * The kept list, `list.json` in the plugin's own folder [dir] (L645): format 1, never a body as it came. It is written
 * whole to list.json.tmp, then renamed over the last, so a death mid-write leaves the last list; it is read tolerantly,
 * so a torn, unknown or unreadable file is no list. Both run on [io], off the main thread (L641).
 */
class KeptList(private val dir: File, private val io: CoroutineContext = Dispatchers.IO) {
    private val file get() = File(dir, NAME)

    /** The list kept, or null when there is none to read. */
    suspend fun read(): SportsList? = withContext(io) {
        try {
            val kept = Wire.json.decodeFromString(ListFile.serializer(), file.readText())
            if (kept.format != FORMAT) return@withContext null
            // A type this version doesn't know is left out: the card is typed again, rather than the list lost.
            val cardTypes = kept.cardTypes.mapNotNull { (key, name) -> CardType.entries.firstOrNull { it.name == name }?.let { key to it } }.toMap()
            SportsList(kept.readAt, kept.leagues, kept.events, kept.cut, cardTypes)
        } catch (e: IOException) {
            // No file yet, or one that can't be read: no list.
            null
        } catch (e: IllegalArgumentException) {
            log.info("Sportarr's kept list is unreadable: read as none")
            null
        }
    }

    /** Keeps [list] in place of the last; false, the last kept as it was, when it can't be written. */
    suspend fun write(list: SportsList): Boolean = withContext(io) {
        val temporary = File(dir, "$NAME.tmp")
        try {
            dir.mkdirs()
            val written = ListFile(FORMAT, list.readAt, list.cut, list.leagues, list.events, list.cardTypes.mapValues { it.value.name })
            temporary.writeText(Wire.json.encodeToString(ListFile.serializer(), written))
            if (!temporary.renameTo(file)) throw IOException("Not renamed")
            true
        } catch (e: IOException) {
            log.warn("Sportarr's list wasn't kept: {}", e.javaClass.simpleName)
            false
        }
    }

    /**
     * list.json as written: what L645's format 1 holds, and the fight cards' types by event key, by name; a file without
     * them reads as one with none.
     */
    @Serializable
    private class ListFile(
        val format: Int,
        @Serializable(with = UtcTime::class) val readAt: Instant,
        val cut: Boolean = false,
        val leagues: List<KeptLeague> = emptyList(),
        val events: List<WireEvent> = emptyList(),
        val cardTypes: Map<String, String> = emptyMap(),
    )

    private companion object {
        const val NAME = "list.json"
        const val FORMAT = 1
        val log = LoggerFactory.getLogger(KeptList::class.java)
    }
}
