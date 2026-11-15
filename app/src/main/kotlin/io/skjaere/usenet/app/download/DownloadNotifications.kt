package io.skjaere.usenet.app.download

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.skjaere.usenet.app.MainActivity
import io.skjaere.usenet.app.R
import io.skjaere.usenet.app.download.DownloadNotice.Action
import io.skjaere.usenet.app.download.DownloadNotice.Kind
import io.skjaere.usenet.app.download.DownloadNotice.Progress
import io.skjaere.usenet.app.download.DownloadNotice.Tap
import io.skjaere.usenet.app.ui.AppColors
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.slf4j.LoggerFactory

/**
 * One of the downloads' notifications, as plain data (L358, L380, L387, C4; DlPhoneNotification): what
 * [DownloadNotifications] builds and posts, and what a JVM test reads. [rowId] is the download it is of, which its Cancel,
 * Play and Choose release act on; [title] the show and episode or the film, never the release; [text] its state's words
 * ([DownloadHostRules.words]); [subText] what else is queued; [progress] its bar, if it has one; [actions] in their order;
 * [tap] what a tap on it does.
 */
data class DownloadNotice(
    val kind: Kind,
    val rowId: String,
    val title: String,
    val text: String,
    val subText: String? = null,
    val progress: Progress? = null,
    val actions: List<Action> = emptyList(),
    val tap: Tap = Tap.Downloads,
) {
    /** Which of the notifications it is: its channel, how it behaves, its category and its small icon. */
    enum class Kind(val channel: String, val ongoing: Boolean, val autoCancel: Boolean, val category: String?, val icon: Icon) {
        /** The queue's, while a host runs it: one, updated at most once a second. */
        Ongoing(DOWNLOADS_CHANNEL, ongoing = true, autoCancel = false, NotificationCompat.CATEGORY_PROGRESS, Icon.Download),

        /** What a host's end leaves while work is left, under its own id: it can be swiped away, and goes as a host starts. */
        Status(DOWNLOADS_CHANNEL, ongoing = false, autoCancel = false, NotificationCompat.CATEGORY_STATUS, Icon.Download),

        /** One for each download finished. */
        Finished(FINISHED_CHANNEL, ongoing = false, autoCancel = true, null, Icon.Downloaded),

        /** One for each that couldn't be downloaded. */
        Failed(FINISHED_CHANNEL, ongoing = false, autoCancel = true, NotificationCompat.CATEGORY_ERROR, Icon.Download),
    }

    /** The bar: [permille] of the file written, or [indeterminate] while there is no telling. */
    data class Progress(val permille: Int, val indeterminate: Boolean)

    /** An action's button, by its word. */
    enum class Action(val label: String) {
        Pause("Pause"),
        Resume("Resume"),
        Cancel("Cancel"),
        Play("Play"),
        ChooseRelease("Choose release"),
    }

    /** What a tap on the notification does: open the Downloads page, or open it and resume (the service's ended wait). */
    enum class Tap { Downloads, Resume }

    /** The drawn small icons (D7): the arrow into the tray, or the tick over it. */
    enum class Icon { Download, Downloaded }

    val channel: String get() = kind.channel
    val ongoing: Boolean get() = kind.ongoing
    val autoCancel: Boolean get() = kind.autoCancel
    val category: String? get() = kind.category
    val icon: Icon get() = kind.icon

    /** Only the first post of a notification sounds or buzzes, never its updates. */
    val onlyAlertOnce: Boolean get() = true

    /**
     * Nothing of any of them on a secure lock screen, whatever the phone's setting (L387): a private one with a public
     * version hides its title only where the user chose to hide sensitive content, and an app can't set its channels'.
     */
    val visibility: Int get() = NotificationCompat.VISIBILITY_SECRET

    companion object {
        /** "Downloads": the ongoing notification and the status one, low, for progress (L358). */
        const val DOWNLOADS_CHANNEL = "downloads"

        /** "Finished downloads": a finished one and a failed one, each with its own. */
        const val FINISHED_CHANNEL = "downloads_finished"
    }
}

/**
 * What each download notification says and offers (L358, L380, C4; DlPhoneNotification), from the runner's state
 * ([DownloadRunner.state]) and its rows, as plain rules: the ongoing one, the status one a host's end leaves, the
 * service's ended wait, Finished and Failed; the ongoing one's pace ([paced]); and which finished and failed ones to post
 * or take back as the rows change ([Watch]).
 */
object DownloadNotices {
    /** The least time between two posts of the ongoing notification: Android drops an app's updates past a few a second. */
    const val MIN_GAP_MILLIS = 1_000L

    /** What is still to download: neither finished, failed nor removed. */
    private val LEFT = setOf(
        DownloadState.Queued, DownloadState.Resolving, DownloadState.Running, DownloadState.Paused, DownloadState.Waiting, DownloadState.Stopped,
    )

    /** What holds the queue until Resume (L380). */
    private val HOLDS = setOf(DownloadState.Paused, DownloadState.Stopped)

    private val TURNING = Progress(0, indeterminate = true)

    /**
     * The queue's ongoing notification for [items] (L358): the download under way (Running or Resolving), else the next
     * one, with the others still to download as "2 more queued"; null with nothing left to download.
     */
    fun ongoing(items: List<DownloadRunner.Item>): DownloadNotice? {
        val left = items.filter { it.row.state in LEFT }
        val item = left.firstOrNull { it.row.state == DownloadState.Running || it.row.state == DownloadState.Resolving } ?: left.firstOrNull() ?: return null
        return notice(Kind.Ongoing, item, more = left.size - 1)
    }

    /**
     * What a host's end leaves while work is left (L380, L388), under its own id: the download that holds the queue (Paused,
     * Stopped), else one that waits, else the next, with the others as "2 more queued". One a stopped host left under way
     * is said as queued again, for the next host; a status notification's bar never turns. Null with nothing left.
     */
    fun status(items: List<DownloadRunner.Item>): DownloadNotice? {
        val left = items.filter { it.row.state in LEFT }
        val item = left.firstOrNull { it.row.state in HOLDS } ?: left.firstOrNull { it.row.state == DownloadState.Waiting } ?: left.firstOrNull() ?: return null
        val row = item.row.takeUnless { it.state == DownloadState.Running || it.state == DownloadState.Resolving } ?: item.row.copy(state = DownloadState.Queued, reason = null)
        val notice = notice(Kind.Status, item.copy(row = row, bytesPerSecond = null), more = left.size - 1)
        return notice.copy(progress = notice.progress?.takeUnless { it.indeterminate })
    }

    /**
     * The service's wait for its need ended after an hour (Android 8 to 13, item 10): [row]'s title, "Paused: no
     * connection · Tap to resume", and a tap that opens Downloads and resumes, a notification's tap being a start Android
     * allows.
     */
    fun tapToResume(row: Download): DownloadNotice {
        val words = DownloadHostRules.tapToResume(row)
        return DownloadNotice(Kind.Status, row.id, words.title, words.text, tap = Tap.Resume)
    }

    /** [row] finished: "Downloaded · 1.2 GB", or with its parts missing, and Play, in "Finished downloads". */
    fun finished(row: Download): DownloadNotice {
        val words = checkNotNull(DownloadHostRules.words(row.copy(state = DownloadState.Done)))
        return DownloadNotice(Kind.Finished, row.id, words.title, words.text, actions = listOf(Action.Play))
    }

    /** [row] failed: why, in technical words, and Choose release, which opens Downloads at its list. */
    fun failed(row: Download): DownloadNotice {
        val words = checkNotNull(DownloadHostRules.words(row.copy(state = DownloadState.Failed)))
        return DownloadNotice(Kind.Failed, row.id, words.title, words.text, actions = listOf(Action.ChooseRelease))
    }

    /**
     * Gives [post] the ongoing notification for each of [state]'s values, at most once every [gapMillis] (L358): the
     * latest value each time, the first at once, and the same notice never twice in a row.
     */
    suspend fun paced(state: Flow<List<DownloadRunner.Item>>, gapMillis: Long = MIN_GAP_MILLIS, post: suspend (DownloadNotice?) -> Unit) {
        state.map(::ongoing).distinctUntilChanged().conflate().collect { notice ->
            post(notice)
            delay(gapMillis)
        }
    }

    /** What [Watch] asks of the notifications as the rows change. */
    sealed interface Change {
        /** Post [notice]: a download finished or failed just now. */
        data class Post(val notice: DownloadNotice) : Change

        /** Take back what was posted of the download [rowId]: it was tried again, removed or deleted. */
        data class Withdraw(val rowId: String) : Change
    }

    /**
     * Which Finished and Failed notifications the rows' changes ask for, one look at a time ([next]): a download seen
     * coming to Done or Failed is posted once; one that leaves either (tried again, removed, deleted) has what was said
     * of it taken back. What a look finds first is what was there before, said already or not by an earlier process: it
     * is never said again.
     */
    class Watch {
        private var seen: Map<String, DownloadState>? = null

        fun next(rows: List<Download>): List<Change> {
            // A removed one is hidden at once (L379), as gone.
            val now = rows.filter { it.state != DownloadState.Deleting }.associateBy { it.id }
            val before = seen
            seen = now.mapValues { it.value.state }
            if (before == null) return emptyList()
            val came = now.values.mapNotNull { row ->
                val was = before[row.id]
                when {
                    was == null || was == row.state -> null
                    row.state == DownloadState.Done -> Change.Post(finished(row))
                    row.state == DownloadState.Failed -> Change.Post(failed(row))
                    was.said() -> Change.Withdraw(row.id)
                    else -> null
                }
            }
            val gone = before.filter { (id, was) -> id !in now && was.said() }.map { (id, _) -> Change.Withdraw(id) }
            return came + gone
        }

        private fun DownloadState.said() = this == DownloadState.Done || this == DownloadState.Failed
    }

    /** [item]'s notice of [kind], with [more] others still to download. */
    private fun notice(kind: Kind, item: DownloadRunner.Item, more: Int): DownloadNotice {
        val row = item.row
        val words = checkNotNull(DownloadHostRules.words(row, item.written, item.bytesPerSecond, more)) { "a download to say" }
        return DownloadNotice(kind, row.id, words.title, words.text, words.subText, bar(item), actions(row))
    }

    /**
     * The bar, as drawn: how far it is while it is written, paused or waits for its need; turning while a release is
     * found or the size isn't known; none while the queue is stopped or waits for the provider or storage.
     */
    private fun bar(item: DownloadRunner.Item): Progress? {
        val row = item.row
        val total = row.size ?: row.indexerSize
        val far = if (total > 0) Progress(((item.written.coerceIn(0, total) * 1000 + total / 2) / total).toInt(), indeterminate = false) else TURNING
        return when (row.state) {
            DownloadState.Running, DownloadState.Paused -> far
            DownloadState.Resolving, DownloadState.Queued -> TURNING
            DownloadState.Waiting -> far.takeIf { row.reason == null || DownloadHostRules.waitsForNeed(row.reason) }
            else -> null
        }
    }

    /**
     * The actions, as drawn: the queue's Pause (L380) and Cancel while it downloads or tries another release; Cancel alone
     * while it finds releases, is queued or waits for its need; Resume and Cancel paused; Resume stopped by Android; none
     * while it waits for the provider or storage, where a tap opens Downloads.
     */
    private fun actions(row: Download): List<Action> = when (row.state) {
        DownloadState.Running -> listOf(Action.Pause, Action.Cancel)
        DownloadState.Resolving -> if (row.reason == null || row.reason == DownloadHostRules.FINDING_RELEASES) listOf(Action.Cancel) else listOf(Action.Pause, Action.Cancel)
        DownloadState.Queued -> listOf(Action.Cancel)
        DownloadState.Paused -> listOf(Action.Resume, Action.Cancel)
        DownloadState.Stopped -> listOf(Action.Resume)
        DownloadState.Waiting -> if (row.reason == null || DownloadHostRules.waitsForNeed(row.reason)) listOf(Action.Cancel) else emptyList()
        else -> emptyList()
    }
}

/**
 * The download notifications on the device (L358, L380, L387, C4): the two channels, "Downloads" (low) and "Finished
 * downloads", made as the cast's channel is; each notice built ([DownloadNotices]) with the drawn small icons and posted
 * while Android allows it, never on a secure lock screen. Pause and Cancel are explicit, immutable broadcasts to
 * [DownloadActionReceiver], which isn't exported; Resume, Play and Choose release, and a tap, are activity intents to
 * MainActivity ([MainActivity.openDownloads]), as only the visible app can schedule the host again (C4).
 *
 * The hosts use [ongoing] for their own notification and [keepOngoing] while they live, [hostStarted] and [hostEnded]
 * around their lives; [watch] posts Finished and Failed for the process's life. Refused (Android 13 on), nothing posts,
 * and downloads still run: the page and Home's section show them. Nothing on a TV (L369). Its log lines name a download
 * by its id alone.
 */
class DownloadNotifications(private val context: Context, television: Boolean) {
    private val runs = DownloadHostRules.runs(television)
    private val manager = NotificationManagerCompat.from(context)

    @Volatile
    private var channelsMade = false

    /** The ongoing notification for [items], for a host's own (the job's setNotification, the service's foreground); null with nothing left, or on a TV. */
    fun ongoing(items: List<DownloadRunner.Item>): Notification? = if (runs) DownloadNotices.ongoing(items)?.let(::build) else null

    /** Keeps the ongoing notification in step with [state] while the host that calls it lives, at most once a second. */
    suspend fun keepOngoing(state: Flow<List<DownloadRunner.Item>>) {
        if (!runs) return
        DownloadNotices.paced(state) { notice -> notice?.let(::post) }
    }

    /** A host starts: the status notification an earlier host's end left goes. */
    fun hostStarted() {
        if (!runs) return
        manager.cancel(STATUS_ID)
    }

    /** A host ended: its notification goes, and, while work is left in [items], the status notification says what holds it or waits. */
    fun hostEnded(items: List<DownloadRunner.Item>) {
        if (!runs) return
        manager.cancel(ONGOING_ID)
        val status = DownloadNotices.status(items)
        if (status == null) manager.cancel(STATUS_ID) else post(status)
    }

    /** The service's wait for [row]'s need ended (Android 8 to 13): "Paused: no connection · Tap to resume", under the status notification's id. */
    fun tapToResume(row: Download) {
        if (runs) post(DownloadNotices.tapToResume(row))
    }

    /** Posts Finished and Failed as [state]'s downloads come to them, and takes them back as they leave, for as long as it is collected. */
    suspend fun watch(state: Flow<List<DownloadRunner.Item>>) {
        if (!runs) return
        val watch = DownloadNotices.Watch()
        state.collect { items ->
            watch.next(items.map { it.row }).forEach { change ->
                when (change) {
                    is DownloadNotices.Change.Post -> post(change.notice)
                    is DownloadNotices.Change.Withdraw -> manager.cancel(change.rowId, DONE_ID)
                }
            }
        }
    }

    /** Posts [notice] where Android allows the app's notifications. */
    fun post(notice: DownloadNotice) {
        if (!runs) return
        // Refused (Android 13 on): downloads still run, and the page and Home's section show them (L358).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        try {
            manager.notify(tag(notice), id(notice.kind), build(notice))
        } catch (e: SecurityException) {
            // Taken back between the check and the post.
            log.info("download {}: its notification wasn't allowed ({})", notice.rowId, e::class.simpleName)
        }
    }

    /** [notice] as Android's notification: on its channel, with the drawn small icon in the accent, its bar, actions and tap, secret on a lock screen. */
    fun build(notice: DownloadNotice): Notification {
        channels()
        val builder = NotificationCompat.Builder(context, notice.channel)
            .setSmallIcon(if (notice.icon == DownloadNotice.Icon.Downloaded) R.drawable.ic_stat_downloaded else R.drawable.ic_stat_download)
            .setColor(ACCENT)
            .setContentTitle(notice.title)
            .setContentText(notice.text)
            .setSubText(notice.subText)
            .setOngoing(notice.ongoing)
            .setAutoCancel(notice.autoCancel)
            .setOnlyAlertOnce(notice.onlyAlertOnce)
            .setCategory(notice.category)
            .setVisibility(notice.visibility)
            // A wearable or another device has no download to show.
            .setLocalOnly(true)
            .setContentIntent(tap(notice.tap))
        // A failure's words are a sentence or two: whole once expanded.
        if (notice.kind == Kind.Failed) builder.setStyle(NotificationCompat.BigTextStyle().bigText(notice.text))
        notice.progress?.let { builder.setProgress(PROGRESS_MAX, it.permille, it.indeterminate) }
        notice.actions.forEach { action -> builder.addAction(NotificationCompat.Action.Builder(null, action.label, pending(action, notice.rowId)).build()) }
        return builder.build()
    }

    /**
     * A notification with nothing to say, on the Downloads channel, for a start of the service that must pass through the
     * foreground with no download to show (Android ends an app whose service, started for the foreground, stops before it
     * gets there). Never posted otherwise; secret on a lock screen as the others are.
     */
    fun passing(): Notification {
        channels()
        return NotificationCompat.Builder(context, DownloadNotice.DOWNLOADS_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setColor(ACCENT)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setLocalOnly(true)
            .build()
    }

    /** The two channels, made once a process, before anything is posted on them: making one again changes nothing the user set. */
    private fun channels() {
        if (channelsMade) return
        manager.createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(DownloadNotice.DOWNLOADS_CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW).setName(DOWNLOADS_CHANNEL_NAME).build(),
                NotificationChannelCompat.Builder(DownloadNotice.FINISHED_CHANNEL, NotificationManagerCompat.IMPORTANCE_DEFAULT).setName(FINISHED_CHANNEL_NAME).build(),
            ),
        )
        channelsMade = true
    }

    private fun tap(tap: Tap): PendingIntent = when (tap) {
        Tap.Downloads -> activity(REQUEST_OPEN, MainActivity.openDownloads(context))
        Tap.Resume -> activity(REQUEST_RESUME, MainActivity.openDownloads(context, resume = true))
    }

    /** [action]'s intent for the download [id]: the queue's Pause and a Cancel as broadcasts, the rest as the activity's. */
    private fun pending(action: Action, id: String): PendingIntent = when (action) {
        Action.Pause -> broadcast(REQUEST_PAUSE, DownloadActionReceiver.pause(context))
        Action.Cancel -> broadcast(REQUEST_CANCEL, DownloadActionReceiver.cancel(context, id).of(action, id))
        Action.Resume -> activity(REQUEST_RESUME, MainActivity.openDownloads(context, resume = true))
        Action.Play -> activity(REQUEST_PLAY, MainActivity.openDownloads(context, play = id).of(action, id))
        Action.ChooseRelease -> activity(REQUEST_CHOOSE, MainActivity.openDownloads(context, choose = id).of(action, id))
    }

    private fun activity(request: Int, intent: Intent): PendingIntent =
        PendingIntent.getActivity(context, request, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun broadcast(request: Int, intent: Intent): PendingIntent =
        PendingIntent.getBroadcast(context, request, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /**
     * This intent as the one for [action] on the download [id] alone: Android tells PendingIntents apart by their intents
     * less the extras, so two finished downloads' Play would otherwise be one, the later's id in both. The download's id
     * only, never what it is of.
     */
    private fun Intent.of(action: Action, id: String): Intent = setData(Uri.Builder().scheme(SCHEME).authority(action.name.lowercase()).appendPath(id).build())

    /** A finished or a failed one is the download's own, by its id; the ongoing and the status one are the queue's. */
    private fun tag(notice: DownloadNotice): String? = notice.rowId.takeIf { notice.kind == Kind.Finished || notice.kind == Kind.Failed }

    private fun id(kind: Kind): Int = when (kind) {
        Kind.Ongoing -> ONGOING_ID
        Kind.Status -> STATUS_ID
        Kind.Finished, Kind.Failed -> DONE_ID
    }

    companion object {
        /** The ongoing notification's id: the job's (setNotification) and the service's foreground one. */
        const val ONGOING_ID = 21_001

        /** The status notification's, its own: a UIDT job's own goes with the job. */
        const val STATUS_ID = 21_002

        /** A finished or failed download's, tagged with its id. */
        const val DONE_ID = 21_003

        const val DOWNLOADS_CHANNEL_NAME = "Downloads"
        const val FINISHED_CHANNEL_NAME = "Finished downloads"

        /** The scheme that tells one download's PendingIntents from another's ([of]). */
        private const val SCHEME = "nzstreamer-download"

        private const val PROGRESS_MAX = 1_000
        private const val REQUEST_OPEN = 1
        private const val REQUEST_RESUME = 2
        private const val REQUEST_PLAY = 3
        private const val REQUEST_CHOOSE = 4
        private const val REQUEST_PAUSE = 5
        private const val REQUEST_CANCEL = 6

        /** The small icon's circle, the app's accent, as the boards draw it. */
        private val ACCENT = AppColors().accent.toArgb()
    }
}

private val log = LoggerFactory.getLogger(DownloadNotifications::class.java)
