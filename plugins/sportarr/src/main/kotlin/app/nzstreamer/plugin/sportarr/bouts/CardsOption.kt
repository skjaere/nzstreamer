package app.nzstreamer.plugin.sportarr.bouts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import kotlin.coroutines.CoroutineContext

/**
 * The Fight cards from Wikipedia switch, `options.json` in the plugin's own folder [dir] (L-B8): `{"format":1,
 * "fightCards":true|false}`, written whole to options.json.tmp and renamed. On by default (W3): a missing file reads as
 * on, so an update from a version without the switch starts on. A file that is there but torn, unreadable or of another
 * format reads as off, failing closed: a user who switched it off is never read for against their choice. Both on [io].
 */
internal class CardsOption(private val dir: File, private val io: CoroutineContext = Dispatchers.IO) {
    /** Whether fight cards are read. */
    suspend fun read(): Boolean = withContext(io) {
        val file = File(dir, NAME)
        try {
            if (!file.exists()) return@withContext true
            val options = json.decodeFromString(Options.serializer(), file.readText())
            options.format == FORMAT && options.fightCards
        } catch (e: IOException) {
            log.info("The fight cards switch is unreadable: read as off")
            false
        } catch (e: IllegalArgumentException) {
            log.info("The fight cards switch is unreadable: read as off")
            false
        }
    }

    /** Keeps the switch [on] or off; false where it can't be written. */
    suspend fun write(on: Boolean): Boolean = withContext(io) {
        val temporary = File(dir, "$NAME.tmp")
        try {
            dir.mkdirs()
            temporary.writeText(json.encodeToString(Options.serializer(), Options(FORMAT, on)))
            if (!temporary.renameTo(File(dir, NAME))) throw IOException("Not renamed")
            true
        } catch (e: IOException) {
            log.warn("The fight cards switch wasn't kept: {}", e.javaClass.simpleName)
            false
        }
    }

    @Serializable
    private class Options(val format: Int, val fightCards: Boolean)

    companion object {
        const val NAME = "options.json"
        private const val FORMAT = 1
        private val json = Json { ignoreUnknownKeys = true }
        private val log = LoggerFactory.getLogger(CardsOption::class.java)
    }
}
