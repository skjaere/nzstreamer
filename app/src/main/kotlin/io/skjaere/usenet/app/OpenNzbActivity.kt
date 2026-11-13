package io.skjaere.usenet.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import io.skjaere.usenet.core.checkNzbSize
import io.skjaere.usenet.core.readNzbBytes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/**
 * Takes the .nzb files other apps open with this one or share to it ([NzbIntents]). It reads the
 * file at once, off the main thread and capped as Import's picker is, while the permission the
 * sender granted lasts (it ends with this activity); hands it to Import ([NzbHandoff]) by bringing
 * MainActivity forward, which closes the player if it was open; and finishes. It shows nothing.
 */
class OpenNzbActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val stream = IntentCompat.getParcelableExtra(intent, android.content.Intent.EXTRA_STREAM, Uri::class.java)
        val uri = NzbIntents.uri(intent.action, intent.dataString, stream?.toString())?.let(Uri::parse)
        val type = intent.type
        lifecycleScope.launch {
            val file = uri?.let { read(it, type) } ?: Result.failure(UnreadableNzbException("This app opens .nzb files only."))
            val id = container.nzbHandoff.put(file)
            startActivity(MainActivity.openNzb(this@OpenNzbActivity, id))
            finish()
        }
    }

    /** The file's bytes and title, or why it can't be opened: not a .nzb, too large, or unreadable. */
    private suspend fun read(uri: Uri, type: String?): Result<Pair<ByteArray, String>> = withContext(Dispatchers.IO) {
        try {
            val (name, size) = contentResolver.nameAndSize(uri)
            val title = NzbIntents.title(name, uri.lastPathSegment, type)
                ?: throw UnreadableNzbException("“${name ?: uri.lastPathSegment ?: "That file"}” isn't a .nzb file.")
            checkNzbSize(size)
            val bytes = contentResolver.openInputStream(uri)?.use { it.readNzbBytes() } ?: throw IOException("Nothing to read at $uri")
            Result.success(bytes to title)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(NzbIntents.readFailure(e, uri.scheme))
        }
    }
}

/**
 * The .nzb files [OpenNzbActivity] read, until Import takes them ([take]). Only the latest is
 * kept: a file opened after it replaces it. Ids are random, so an Import screen restored after
 * process death never takes another file.
 */
class NzbHandoff {
    private var latest: Pair<String, Result<Pair<ByteArray, String>>>? = null

    /** Keeps [file] (its bytes and title, or why it can't be opened) for Import; returns its id. */
    @Synchronized
    fun put(file: Result<Pair<ByteArray, String>>): String = UUID.randomUUID().toString().also { latest = it to file }

    /** The file handed over as [id], once; null when it was taken already, a later one replaced it, or the process died since. */
    @Synchronized
    fun take(id: String): Result<Pair<ByteArray, String>>? = latest?.takeIf { it.first == id }?.second?.also { latest = null }
}
