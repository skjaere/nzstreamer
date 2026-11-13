package io.skjaere.usenet.app

import android.content.Intent
import java.io.FileNotFoundException
import java.io.IOException

/**
 * The .nzb files other apps open with this one (ACTION_VIEW) or share to it (ACTION_SEND): which
 * intents are taken, and the title a file gets. Local files only, by content: or file: URI; never
 * a http(s) link. Plain strings, so it is tested without Android; [OpenNzbActivity] reads the file.
 */
internal object NzbIntents {
    const val NZB_TYPE = "application/x-nzb"

    /** The URI of the .nzb [action] hands over: [data] for a view, [stream] (EXTRA_STREAM) for a send. Null for anything else. */
    fun uri(action: String?, data: String?, stream: String?): String? {
        val uri = when (action) {
            Intent.ACTION_VIEW -> data
            Intent.ACTION_SEND -> stream
            else -> null
        } ?: return null
        return uri.takeIf { it.substringBefore(':', "").lowercase() in setOf("content", "file") }
    }

    /**
     * The title of a file named [displayName] (the provider's name for it, else the URI's
     * [lastPathSegment]): the name without ".nzb". Null when it isn't a .nzb: a name not ending in
     * .nzb, unless the sender's [type] says it is one (file managers send most files as
     * application/octet-stream, and some URIs carry no name).
     */
    fun title(displayName: String?, lastPathSegment: String?, type: String?): String? {
        val name = displayName?.takeIf { it.isNotBlank() } ?: lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        val named = name?.endsWith(".nzb", ignoreCase = true) == true
        if (!named && type?.substringBefore(';')?.trim()?.equals(NZB_TYPE, ignoreCase = true) != true) return null
        return name?.let { if (named) it.dropLast(".nzb".length) else it }?.trim()?.takeIf { it.isNotEmpty() } ?: "NZB"
    }

    /**
     * What a failed read of a file: URI ([scheme]) is reported as. Reading a path in shared storage
     * needs a storage permission, which this app doesn't ask for, so a sender handing over a path
     * instead of access is told to share the file instead. Any other failure stays as it is.
     */
    fun readFailure(e: Exception, scheme: String?): Exception {
        val denied = e is SecurityException || e is FileNotFoundException && e.message.orEmpty().let { "EACCES" in it || "denied" in it.lowercase() }
        return if (scheme.equals("file", ignoreCase = true) && denied) {
            UnreadableNzbException(
                "This app can't read that file: the app that opened it gave its place in storage, which needs a storage permission " +
                    "this app doesn't ask for. Share the .nzb to this app instead, or open it from a file manager that shares it.",
            )
        } else {
            e
        }
    }
}

/** A .nzb another app handed over that can't be opened; [message] is what Import shows. */
class UnreadableNzbException(message: String) : IOException(message)
