package io.skjaere.usenet.app.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.core.content.FileProvider
import io.skjaere.usenet.app.ui.FormFactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Copy and Share for Settings › Logs: the clipboard, and a text file in the cache directory's logs/
 * handed, read-only, to the app the user picks through the app's FileProvider. Nothing is sent
 * anywhere unless the user shares it.
 */
internal object LogExport {
    /** What the clipboard takes: a binder transaction holds about 1 MB, and the text travels as UTF-16. */
    const val CLIPBOARD_CHARS = 100_000

    /** A file shared this long ago is deleted at the next share; the app it went to has read it by then. */
    private const val KEEP_SHARED_MS = 60 * 60 * 1000L

    private val fileStamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

    /** Whether any app takes shared text; on many TVs none does. Asks the package manager: off the main thread. */
    suspend fun canShare(context: Context): Boolean = withContext(Dispatchers.IO) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        val pm = context.packageManager
        val takers = if (Build.VERSION.SDK_INT >= 33) {
            pm.queryIntentActivities(send, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(send, 0)
        }
        takers.isNotEmpty()
    }

    fun copy(context: Context, text: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Log", text))
        // The device's own: it is the system UI that confirms a copy or not, whatever layout the app shows.
        if (copySaysCopied(Build.VERSION.SDK_INT, FormFactor.of(context) == FormFactor.Tv)) Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    }

    /**
     * Writes [text] to a file named after [what] ("log", "crash log") and the time, and returns the
     * chooser that shares it with the app picked, which may read that file alone.
     */
    suspend fun shareIntent(context: Context, what: String, text: String): Intent = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "logs").apply { mkdirs() }
        val now = System.currentTimeMillis()
        dir.listFiles()?.filter { now - it.lastModified() > KEEP_SHARED_MS }?.forEach { it.delete() }
        val file = File(dir, "nzstreamer-${what.replace(' ', '-')}-${fileStamp.format(LocalDateTime.now())}.txt").apply { writeText(text) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.logs", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, "NZStreamer $what")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // The chooser passes the grant on to the app picked through the clip.
        send.clipData = ClipData.newRawUri(null, uri)
        Intent.createChooser(send, "Share the $what")
    }
}
