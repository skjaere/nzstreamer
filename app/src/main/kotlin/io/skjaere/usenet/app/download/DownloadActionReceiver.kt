package io.skjaere.usenet.app.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.skjaere.usenet.app.container
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * The download notifications' Pause and Cancel (L358, L380): explicit, immutable broadcasts from the app's own
 * PendingIntents ([DownloadNotifications]). The receiver isn't exported, so nothing outside the app can send them. Pause
 * holds the whole queue (the running download stops at its safe offset, and the host ends); Cancel cancels the download
 * the notification is of, with no Undo. Neither needs the app in front: the host isn't scheduled from here (C4), Resume is
 * the activity's. Nothing on a TV (L369).
 */
class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = context.container
        if (!DownloadHostRules.runs(container.television)) return
        val action = intent.action
        val id = intent.getStringExtra(EXTRA_ID)
        if (action != ACTION_PAUSE && (action != ACTION_CANCEL || id == null)) return
        // The runner stops the item at its safe offset, which can take a moment: the broadcast is held open until then.
        val pending = goAsync()
        container.appScope.launch {
            try {
                if (action == ACTION_PAUSE) container.downloads.pause() else container.downloads.cancel(checkNotNull(id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Couldn't {} from the notification: {}", if (action == ACTION_PAUSE) "pause the downloads" else "cancel download $id", e::class.simpleName)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val ACTION_PAUSE = "io.skjaere.usenet.download.PAUSE"
        private const val ACTION_CANCEL = "io.skjaere.usenet.download.CANCEL"
        private const val EXTRA_ID = "id"

        /** The queue's Pause (L380), to this receiver alone. */
        fun pause(context: Context): Intent = Intent(context, DownloadActionReceiver::class.java).setAction(ACTION_PAUSE)

        /** Cancel the download [id], to this receiver alone. */
        fun cancel(context: Context, id: String): Intent = Intent(context, DownloadActionReceiver::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, id)
    }
}

private val log = LoggerFactory.getLogger(DownloadActionReceiver::class.java)
