package io.skjaere.usenet.app.download

import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

/** What MainActivity's download extras ask of the app ([io.skjaere.usenet.app.MainActivity.openDownloads]): the page, and what to do there. */
sealed interface DownloadAsk {
    /** The Downloads page: a notification's tap, and any ask another sender makes. */
    data object Open : DownloadAsk

    /** The page, and the queue going on (L380, C4): the notification's Resume, from the visible app. */
    data object Resume : DownloadAsk

    /** The download [id] played: a finished one's Play. */
    data class Play(val id: String) : DownloadAsk

    /** The page at the download [id]'s list of releases: a failed one's Choose release. */
    data class Choose(val id: String) : DownloadAsk
}

/** An intent's download extras as they came: the page's own ([open]), what to do, and the [token] the app's own intents carry. */
data class DownloadExtras(
    val open: Boolean = false,
    val resume: Boolean = false,
    val play: String? = null,
    val choose: String? = null,
    val token: String? = null,
)

/**
 * What MainActivity does with an intent's download extras (item 11, L369, C4). The activity is exported, so anything may
 * send them: they are read only where downloads show (DownloadPlaces.shown, as Add a TV's ask is read on phones alone) and
 * never when Recents starts the app with the intent that first asked. Resume and Play act only with the app's own token
 * ([DownloadToken]), which only its own notifications' PendingIntents carry; any other sender's ask only opens the page.
 */
object DownloadIntents {
    /** A download's id as the app makes one (Download.newId): RFC 3986's unreserved characters, as its URI and folders hold it. */
    private val ID = Regex("[A-Za-z0-9._~-]{1,64}")

    /** The ask [extras] make, [matches] saying whether a token is the app's; null when they ask nothing, or aren't read here. */
    fun ask(extras: DownloadExtras, shown: Boolean, fromRecents: Boolean, matches: (String?) -> Boolean): DownloadAsk? {
        if (!shown || fromRecents) return null
        if (!extras.open && !extras.resume && extras.play == null && extras.choose == null) return null
        val trusted = matches(extras.token)
        val play = extras.play?.takeIf(ID::matches)
        val choose = extras.choose?.takeIf(ID::matches)
        return when {
            extras.play != null -> if (trusted && play != null) DownloadAsk.Play(play) else DownloadAsk.Open
            extras.resume -> if (trusted) DownloadAsk.Resume else DownloadAsk.Open
            extras.choose != null -> choose?.let { DownloadAsk.Choose(it) } ?: DownloadAsk.Open
            else -> DownloadAsk.Open
        }
    }
}

/**
 * The ways into the Downloads page (L369): null where downloads don't show, as debrid's are while it is hidden.
 * [downloads]: the page; [chooseRelease]: the page at a download's list of releases; [play]: a finished download played,
 * from the page; [settings]: Settings › Downloads, for the Settings hub's row (item 20).
 */
data class DownloadPages(
    val downloads: (() -> Unit)? = null,
    val chooseRelease: ((id: String) -> Unit)? = null,
    val play: ((id: String) -> Unit)? = null,
    val settings: (() -> Unit)? = null,
) {
    /**
     * Takes [ask] where it leads: the page; Resume opens it and [resume]s the queue, from the visible app (C4); a play or a
     * choice of release opens the page while its own way in isn't there.
     */
    fun take(ask: DownloadAsk, resume: () -> Unit) {
        when (ask) {
            DownloadAsk.Open -> downloads?.invoke()
            DownloadAsk.Resume -> {
                downloads?.invoke()
                resume()
            }
            is DownloadAsk.Play -> play?.invoke(ask.id) ?: downloads?.invoke()
            is DownloadAsk.Choose -> chooseRelease?.invoke(ask.id) ?: downloads?.invoke()
        }
    }
}

/**
 * The downloads' part of the phone's graph (L369): while [shown], [register] adds their screens, and the ways into them
 * open their routes by [open]: the Downloads page, at a download's releases or playing a finished one there, and
 * Settings › Downloads. Where downloads don't show, neither runs. AppNav's `downloadScreens` is this over its graph and
 * navigation.
 */
fun downloadPages(shown: Boolean, register: () -> Unit, open: (route: Any) -> Unit): DownloadPages {
    if (!shown) return DownloadPages()
    register()
    return DownloadPages(
        downloads = { open(DownloadsRoute()) },
        chooseRelease = { id -> open(DownloadsRoute(choose = id)) },
        play = { id -> open(DownloadsRoute(play = id)) },
        settings = { open(DownloadSettingsRoute) },
    )
}

/**
 * The app's own token for the download asks that act (Resume and Play, [DownloadIntents]): a random value, made once and
 * kept in [folder] (filesDir's `downloads/`, private to the app and out of backups and transfers), put in the app's own
 * immutable PendingIntents alone, which no other app reads. One that can't be kept lasts the process.
 */
class DownloadToken(private val folder: File) {
    /** The token, read or made on first use. */
    val value: String by lazy { read() ?: make() }

    /** Whether [token] is the app's, compared in constant time. */
    fun matches(token: String?): Boolean = token != null && MessageDigest.isEqual(token.toByteArray(), value.toByteArray())

    private fun read(): String? = try {
        File(folder, FILE).takeIf { it.isFile }?.readText()?.takeIf(SHAPE::matches)
    } catch (e: IOException) {
        log.warn("Couldn't read the downloads' token: {}", e::class.simpleName)
        null
    }

    private fun make(): String {
        val token = ByteArray(BYTES).also(SecureRandom()::nextBytes).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        try {
            folder.mkdirs()
            val made = File(folder, "$FILE.tmp")
            made.writeText(token)
            if (!made.renameTo(File(folder, FILE))) throw IOException("Couldn't keep the token")
        } catch (e: IOException) {
            // The notifications this process posts still carry it; a later process's makes its own.
            log.warn("Couldn't keep the downloads' token: {}", e::class.simpleName)
        }
        return token
    }

    companion object {
        /** Its file in the downloads' bookkeeping folder, beside the debug seams; reconcile leaves files there alone. */
        const val FILE = ".token"

        private const val BYTES = 32
        private val SHAPE = Regex("[0-9a-f]{64}")
    }
}

private val log = LoggerFactory.getLogger(DownloadToken::class.java)
