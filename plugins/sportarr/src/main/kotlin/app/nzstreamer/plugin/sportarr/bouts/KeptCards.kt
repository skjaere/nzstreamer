package app.nzstreamer.plugin.sportarr.bouts

import app.nzstreamer.plugin.sportarr.source.UtcTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlin.coroutines.CoroutineContext

/** A read kept for an event: when it was read ([readAt]) and what it said ([read]: found, not out yet, or no card). */
internal data class KeptCard(val readAt: Instant, val read: CardRead)

/**
 * The kept cards, `cards.json` in the plugin's own folder [dir] (L-B5): format 1, one entry per event key with when it was
 * read and what it said, the card where one was found; a failed read is never kept. At most [MOST] entries, the oldest
 * read dropped first. Written whole to cards.json.tmp, then renamed over the last; read tolerantly, so a torn, unknown or
 * unreadable file is none. Both on [io], off the main thread, as the kept list is.
 *
 * It is also a record of which events the user opened and when, so the switch turned off deletes it (L-B8).
 */
internal class KeptCards(private val dir: File, private val io: CoroutineContext = Dispatchers.IO) {
    /** The cards kept, by event key; none where there is no file to read. */
    suspend fun read(): Map<String, KeptCard> = withContext(io) {
        try {
            val kept = json.decodeFromString(CardsFile.serializer(), File(dir, NAME).readText())
            if (kept.format != FORMAT) return@withContext emptyMap()
            kept.cards.mapNotNull { entry -> read(entry)?.let { entry.event to KeptCard(entry.readAt, it) } }.toMap()
        } catch (e: IOException) {
            // No file yet, or one that can't be read: none kept.
            emptyMap()
        } catch (e: IllegalArgumentException) {
            log.info("The kept fight cards are unreadable: read as none")
            emptyMap()
        }
    }

    /** Keeps [cards] in place of the last, the newest [MOST] by when they were read; false where they can't be written. */
    suspend fun write(cards: Map<String, KeptCard>): Boolean = withContext(io) {
        val temporary = File(dir, "$NAME.tmp")
        try {
            dir.mkdirs()
            val entries = capped(cards).mapNotNull { (event, card) -> entry(event, card) }
            temporary.writeText(json.encodeToString(CardsFile.serializer(), CardsFile(FORMAT, entries)))
            if (!temporary.renameTo(File(dir, NAME))) throw IOException("Not renamed")
            true
        } catch (e: IOException) {
            log.warn("The fight cards weren't kept: {}", e.javaClass.simpleName)
            false
        }
    }

    /** Deletes the kept cards, and the temporary file a write may have left. */
    suspend fun delete() = withContext(io) {
        File(dir, NAME).delete()
        File(dir, "$NAME.tmp").delete()
        Unit
    }

    private fun read(entry: Entry): CardRead? = when (entry.state) {
        FOUND -> entry.card?.let { CardRead.Found(it) }
        NOT_YET -> CardRead.NotYet
        NO_CARD -> CardRead.NoCard
        else -> null
    }

    private fun entry(event: String, card: KeptCard): Entry? = when (val read = card.read) {
        is CardRead.Found -> Entry(event, card.readAt, FOUND, read.card)
        CardRead.NotYet -> Entry(event, card.readAt, NOT_YET)
        CardRead.NoCard -> Entry(event, card.readAt, NO_CARD)
        is CardRead.Failed -> null
    }

    /** cards.json as written: its format and its entries, oldest read first. */
    @Serializable
    private class CardsFile(val format: Int, val cards: List<Entry> = emptyList())

    /** One event's read: its key, when it was read, what it said ("found", "not-yet", "no-card") and the card found. */
    @Serializable
    private class Entry(
        val event: String,
        @Serializable(with = UtcTime::class) val readAt: Instant,
        val state: String,
        val card: BoutCard? = null,
    )

    companion object {
        const val NAME = "cards.json"

        /** The most cards kept: about the events of a few months, as a user opens them. */
        const val MOST = 100

        private const val FORMAT = 1
        private const val FOUND = "found"
        private const val NOT_YET = "not-yet"
        private const val NO_CARD = "no-card"

        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
        private val log = LoggerFactory.getLogger(KeptCards::class.java)

        /** [cards] without the oldest reads past the [MOST] newest, oldest read first (ties by event key). */
        fun capped(cards: Map<String, KeptCard>): List<Pair<String, KeptCard>> =
            cards.toList().sortedWith(compareBy({ it.second.readAt }, { it.first })).takeLast(MOST)
    }
}
