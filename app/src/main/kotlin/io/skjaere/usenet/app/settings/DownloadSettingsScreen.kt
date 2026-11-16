package io.skjaere.usenet.app.settings

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.download.DownloadHostRules
import io.skjaere.usenet.app.download.DownloadHostRules.ScheduleCause
import io.skjaere.usenet.app.download.DownloadNotice
import io.skjaere.usenet.app.download.DownloadSpace
import io.skjaere.usenet.app.download.DownloadsPage
import io.skjaere.usenet.app.download.TitleDownloads
import io.skjaere.usenet.app.download.logged
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.Footnote
import io.skjaere.usenet.app.ui.GroupCard
import io.skjaere.usenet.app.ui.InsetDivider
import io.skjaere.usenet.app.ui.NavRow
import io.skjaere.usenet.app.ui.SecondaryButton
import io.skjaere.usenet.app.ui.SectionHeader
import io.skjaere.usenet.app.ui.SwitchRow
import io.skjaere.usenet.app.ui.TopBar
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.core.formatSize
import io.skjaere.usenet.data.Download
import io.skjaere.usenet.data.DownloadKind
import io.skjaere.usenet.data.DownloadPrefs
import io.skjaere.usenet.data.DownloadState
import io.skjaere.usenet.releases.DownloadSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Settings › Downloads' words and rules (item 20; W110, L365, L371), DN1's rows: the hub row's line, Download size's cells
 * and its explanation, the SD card's cell, On this phone's lines, Delete all's confirm and the Notifications row's line.
 * Pure, so the JVM reads every case; the screen only draws them. "After watching" is DN2's.
 */
object DownloadSettings {
    const val TITLE = "Downloads"
    const val UNMETERED = "Only on Wi-Fi or Ethernet"
    const val UNMETERED_DETAIL = "On mobile data or a metered hotspot, downloads wait."
    const val CHARGING = "Only while charging"
    const val CHARGING_DETAIL = "Downloads wait until this phone is plugged in."
    const val ONE_AT_A_TIME = "One download at a time, in the order you added them. While something plays, downloads go on more slowly."
    const val SIZE = "Download size"

    /** Download size's cells: Not set (null), then the sizes Settings offers, in GB an hour of video (L371). */
    val CELLS: List<Int?> = listOf<Int?>(null) + DownloadPrefs.DOWNLOAD_SIZES_GB_PER_HOUR
    const val UNIT = "GB an hour"

    /** Under the cells, in two paragraphs: how downloads rank, and what a size means for an episode. */
    val SIZE_EXPLANATION = listOf(
        "Downloads rank releases as plays do, by your quality and languages, but of the same quality the smaller go first. " +
            "Releases above this size are tried last.",
        example(gbPerHour = 2, minutes = 45),
    )
    const val SAVE_TO = "Save downloads to"
    const val THIS_PHONE = "This phone"
    const val SD_CARD = "SD card"

    /** The SD card's cell, greyed: other volumes are DN2's (L348). */
    const val LATER = "Later"
    const val STAYS = "Downloads stay on this device, in NZStreamer’s own storage. They aren’t backed up, and uninstalling NZStreamer deletes them."
    const val ON_THIS_PHONE = "On this phone"
    const val DELETE_ALL = "Delete all"
    const val CONFIRM_TITLE = "Delete all downloads?"
    const val DELETE = "Delete"
    const val NOTIFICATIONS = "Notifications"

    /** On this phone with nothing in it: before the first download, or after Delete all. */
    const val NO_DOWNLOADS = "No downloads"

    /** On this phone while what there is still has no file: queued, or being found. */
    const val NOTHING_YET = "Nothing downloaded yet"

    /** The hub row's line before the first download. */
    private const val NONE_YET = "None yet"
    private const val SEP = " · "

    /** How TalkBack reads [gbPerHour]'s cell: "Download size 2 GB an hour", "No download size". */
    fun label(gbPerHour: Int?): String = if (gbPerHour == null) "No download size" else "$SIZE $gbPerHour $UNIT"

    /** What [minutes] of video at [gbPerHour] come to, in bytes, by the bits a second the ranker reads (DownloadSize). */
    fun bytes(gbPerHour: Int, minutes: Int): Long = DownloadSize.bitsPerSecond(gbPerHour) * minutes * 60 / 8

    /** "At 2 GB an hour, a 45-minute episode is about 1.5 GB.": the explanation's example, worked out. */
    fun example(gbPerHour: Int, minutes: Int): String =
        "At $gbPerHour GB an hour, a $minutes-minute episode is about ${formatSize(bytes(gbPerHour, minutes))}."

    /** Downloads still to go, besides the one under way: queued, waiting for what they need, or held (L380). */
    private val STILL_TO_GO = setOf(DownloadState.Queued, DownloadState.Waiting, DownloadState.Paused, DownloadState.Stopped)

    /**
     * The hub row's line, from the downloads' [rows] and their settings ([prefs]): with one under way, "Downloading", how
     * many are still to go and what downloads hold ("Downloading · 2 queued · 12.4 GB"); else what they hold, or how many
     * wait (or "None yet"), and when they run ("12.4 GB · Wi-Fi or Ethernet only", "… · Wi-Fi or Ethernet, while
     * charging", "… · any network"). What they hold counts each file made at its whole size, as it is allocated whole
     * (C8); those removed and waiting for their Undo's end count gone (L379).
     */
    fun hubLine(rows: List<Download>, prefs: DownloadPrefs): String {
        val kept = rows.filter { it.state != DownloadState.Deleting }
        val held = kept.sumOf(DownloadsPage::held).takeIf { it > 0 }?.let(::formatSize)
        val queued = kept.count { it.state in STILL_TO_GO }.takeIf { it > 0 }?.let { "$it queued" }
        if (kept.any { it.state == DownloadState.Running || it.state == DownloadState.Resolving }) {
            return listOfNotNull(TitleDownloads.DOWNLOADING, queued, held).joinToString(SEP)
        }
        return (listOfNotNull(held, queued).ifEmpty { listOf(NONE_YET) } + rule(prefs)).joinToString(SEP)
    }

    /** When downloads run, as the two switches say. */
    private fun rule(prefs: DownloadPrefs): String {
        val network = if (prefs.unmeteredOnly) "Wi-Fi or Ethernet" else "any network"
        return when {
            prefs.whileCharging -> "$network, while charging"
            prefs.unmeteredOnly -> "$network only"
            else -> network
        }
    }

    /** A storage volume as Android lists it: the [primary] one (the app's downloads' own, L348), [removable], [mounted]. */
    data class Volume(val primary: Boolean, val removable: Boolean, val mounted: Boolean)

    /** Whether the SD card's cell shows: only while a removable volume other than the primary one is mounted. */
    fun sdCardShown(volumes: List<Volume>): Boolean = volumes.any { !it.primary && it.removable && it.mounted }

    /** On this phone's row: its [title] and [line], and whether Delete all has anything to delete ([deletable]). */
    data class Usage(val title: String, val line: String, val deletable: Boolean)

    /**
     * On this phone: what downloads hold ("12.4 GB in downloads"), how many there are and what is free ("6 episodes and 1
     * film · 48.0 GB free"); those removed and waiting for their Undo's end count gone.
     */
    fun usage(rows: List<Download>, space: DownloadSpace?): Usage {
        val kept = rows.filter { it.state != DownloadState.Deleting }
        if (kept.isEmpty()) return Usage(NO_DOWNLOADS, free(space), deletable = false)
        val held = kept.sumOf(DownloadsPage::held)
        return Usage(if (held > 0) "${formatSize(held)} in downloads" else NOTHING_YET, "${count(kept)}$SEP${free(space)}", deletable = true)
    }

    /** What is free for downloads, as the Downloads page's strip says it; that storage isn't there while it isn't. */
    fun free(space: DownloadSpace?): String = space?.let { "${formatSize(DownloadsPage.strip(it).free)} free" } ?: DownloadHostRules.STORAGE_UNAVAILABLE

    /** Delete all's question, under its title: what goes, and that one under way stops. */
    fun confirm(rows: List<Download>): String {
        val kept = rows.filter { it.state != DownloadState.Deleting }
        val held = kept.sumOf(DownloadsPage::held)
        val what = if (held > 0) "the ${formatSize(held)} of downloads" else "the downloads"
        return "Deletes $what on this phone: ${count(kept)}. A download running now stops."
    }

    /** "6 episodes and 1 film", "1 episode", "2 films". */
    private fun count(rows: List<Download>): String {
        val episodes = rows.count { it.kind == DownloadKind.Episode }
        val films = rows.count { it.kind == DownloadKind.Movie }
        return listOfNotNull(
            episodes.takeIf { it > 0 }?.let { "$it episode${if (it == 1) "" else "s"}" },
            films.takeIf { it > 0 }?.let { "$it film${if (it == 1) "" else "s"}" },
        ).joinToString(" and ")
    }

    /** The Notifications row's line: whether Android shows the downloads' notifications, and where to allow them. */
    fun notificationsLine(on: Boolean): String = if (on) "On: progress, Pause and Cancel" else "Off: allow them in Android’s settings"
}

/**
 * What Settings › Downloads reads and asks: the app's ([of]), or a test's seams. [rows]: the runner's downloads, those
 * removed left out; [space]: what downloads hold and may take (null while the primary volume isn't there); the settings'
 * setters; the host's schedule from the visible app (L335); the runner's Delete all; and Android's answers, read as the
 * page comes to the front: whether the downloads' notifications show, and whether a removable volume is mounted.
 */
data class DownloadSettingsDeps(
    val rows: Flow<List<Download>>,
    val prefs: Flow<DownloadPrefs>,
    val space: suspend () -> DownloadSpace?,
    val setUnmetered: suspend (Boolean) -> Unit,
    val setCharging: suspend (Boolean) -> Unit,
    val setSizeCap: suspend (Int?) -> Unit,
    val schedule: suspend (ScheduleCause) -> Unit,
    val deleteAll: suspend () -> Unit,
    val notificationsOn: () -> Boolean,
    val sdCard: () -> Boolean,
) {
    companion object {
        /** The app's: the runner's rows and Delete all, the downloads' storage, the settings, the hosts, and Android's answers. */
        fun of(container: AppContainer, context: Context) = DownloadSettingsDeps(
            rows = container.downloads.state.map { items -> items.map { it.row } }.distinctUntilChanged(),
            prefs = container.settings.downloads,
            space = { container.downloadStorage.space() },
            setUnmetered = container.settings::setDownloadsUnmeteredOnly,
            setCharging = container.settings::setDownloadsWhileCharging,
            setSizeCap = container.settings::setDownloadSizeCap,
            schedule = { cause -> container.downloadHosts.schedule(cause) },
            deleteAll = container.downloads::deleteAll,
            notificationsOn = { notificationsOn(context) },
            sdCard = { DownloadSettings.sdCardShown(volumes(context)) },
        )

        /** Whether Android shows the app's notifications, and the Downloads channel's, once it is made, isn't turned off. */
        private fun notificationsOn(context: Context): Boolean {
            val manager = NotificationManagerCompat.from(context)
            if (!manager.areNotificationsEnabled()) return false
            val channel = manager.getNotificationChannelCompat(DownloadNotice.DOWNLOADS_CHANNEL) ?: return true
            return channel.importance != NotificationManagerCompat.IMPORTANCE_NONE
        }

        /** The device's storage volumes as Android lists them now. */
        private fun volumes(context: Context): List<DownloadSettings.Volume> =
            context.getSystemService(StorageManager::class.java).storageVolumes.map {
                DownloadSettings.Volume(primary = it.isPrimary, removable = it.isRemovable, mounted = it.state == Environment.MEDIA_MOUNTED)
            }
    }
}

/**
 * Settings › Downloads' state and actions: the settings, the rows, the space read again as the rows change (a Delete all
 * among them), and Android's answers read again as the page comes to the front ([shown]). A changed switch is saved,
 * then the host is scheduled with the new needs, whether it waits or runs (L335's exception): a waiting job takes them,
 * and a running download stops at its safe offset and goes on under them; the service reads them itself. Download size
 * is the ranking's alone, and schedules nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadSettingsModel(private val deps: DownloadSettingsDeps, private val scope: CoroutineScope) {
    /** Null until read, so no switch or size shows that isn't saved. */
    val prefs: StateFlow<DownloadPrefs?> = deps.prefs.stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    /** Null until the runner has said. */
    val rows: StateFlow<List<Download>?> = deps.rows.stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    /** What downloads hold and what is free, read again as the rows change; null until first read. */
    val space: StateFlow<Measured?> = deps.rows.distinctUntilChanged().mapLatest { Measured(deps.space()) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    /** The space as read: [space] null while the primary volume isn't there. */
    data class Measured(val space: DownloadSpace?)

    private val _notificationsOn = MutableStateFlow(deps.notificationsOn())
    val notificationsOn: StateFlow<Boolean> = _notificationsOn.asStateFlow()

    private val _sdCard = MutableStateFlow(deps.sdCard())
    val sdCard: StateFlow<Boolean> = _sdCard.asStateFlow()

    /** The page is in front again, maybe from Android's notification settings, maybe with a card put in. */
    fun shown() {
        _notificationsOn.value = deps.notificationsOn()
        _sdCard.value = deps.sdCard()
    }

    fun setUnmetered(on: Boolean): Job = switched("save Only on Wi-Fi or Ethernet") { deps.setUnmetered(on) }

    fun setCharging(on: Boolean): Job = switched("save Only while charging") { deps.setCharging(on) }

    fun setSizeCap(gbPerHour: Int?): Job = scope.launch { logged("save the download size") { deps.setSizeCap(gbPerHour) } }

    /** Delete all, once its confirm is answered (L379): every download, the one under way stopped. */
    fun deleteAll(): Job = scope.launch { logged("delete all downloads") { deps.deleteAll() } }

    /** Saves a switch, then schedules the host with its new needs, read as it is scheduled. */
    private fun switched(what: String, save: suspend () -> Unit): Job = scope.launch {
        logged(what) {
            save()
            deps.schedule(ScheduleCause.SwitchChanged)
        }
    }
}

class DownloadSettingsViewModel(deps: DownloadSettingsDeps) : ViewModel() {
    val model = DownloadSettingsModel(deps, viewModelScope)
}

/**
 * Settings › Downloads (item 20; DN1's rows), on phones and tablets alone: its route is in the phone's graph only while
 * downloads show (L369). The two switches and the one-at-a-time line; Download size in Maximum bitrate's grid with its
 * explanation; Save downloads to (this phone; the SD card greyed, only while one is in); what stays where; On this phone
 * with Delete all, asked first as Cache's clears are; and Notifications, which opens Android's page for them.
 */
@Composable
fun DownloadSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    DownloadSettingsScreen(onBack, remember { DownloadSettingsDeps.of(container, context.applicationContext) })
}

@Composable
internal fun DownloadSettingsScreen(onBack: () -> Unit, deps: DownloadSettingsDeps) {
    val vm: DownloadSettingsViewModel = viewModel { DownloadSettingsViewModel(deps) }
    val model = vm.model
    val prefs by model.prefs.collectAsStateWithLifecycle()
    val rows by model.rows.collectAsStateWithLifecycle()
    val space by model.space.collectAsStateWithLifecycle()
    val notificationsOn by model.notificationsOn.collectAsStateWithLifecycle()
    val sdCard by model.sdCard.collectAsStateWithLifecycle()
    var confirm by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.shown() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar(DownloadSettings.TITLE, onBack)
        val p = prefs
        if (p != null) {
            GroupCard(Modifier.padding(top = 4.dp)) {
                SwitchRow(DownloadSettings.UNMETERED, p.unmeteredOnly, model::setUnmetered, detail = DownloadSettings.UNMETERED_DETAIL)
                InsetDivider()
                SwitchRow(DownloadSettings.CHARGING, p.whileCharging, model::setCharging, detail = DownloadSettings.CHARGING_DETAIL)
            }
            Footnote(DownloadSettings.ONE_AT_A_TIME)
            SectionHeader(DownloadSettings.SIZE, top = 6.dp)
            BitrateChoices(
                cells = DownloadSettings.CELLS, unit = DownloadSettings.UNIT, chosen = p.sizeCapGbPerHour,
                label = DownloadSettings::label, onPick = model::setSizeCap,
            )
            Footnote(DownloadSettings.SIZE_EXPLANATION)
        }

        val measured = space
        val free = measured?.let { DownloadSettings.free(it.space) }
        SectionHeader(DownloadSettings.SAVE_TO)
        Row(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(IntrinsicSize.Min).selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PlaceCell(DownloadSettings.THIS_PHONE, free ?: "…", chosen = true)
            if (sdCard) PlaceCell(DownloadSettings.SD_CARD, DownloadSettings.LATER, chosen = false, greyed = true) else Spacer(Modifier.weight(1f))
        }
        Footnote(DownloadSettings.STAYS)

        SectionHeader(DownloadSettings.ON_THIS_PHONE, top = 6.dp)
        val shown = rows
        val usage = if (shown != null && measured != null) DownloadSettings.usage(shown, measured.space) else null
        Row(
            Modifier.padding(horizontal = 16.dp).padding(top = 14.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(usage?.title ?: "…", style = AppType.bodyStrong, color = colors.text)
                Text(usage?.line ?: "…", style = AppType.secondary, color = colors.muted)
            }
            SecondaryButton(DownloadSettings.DELETE_ALL, { confirm = true }, enabled = usage?.deletable == true)
        }

        GroupCard(Modifier.padding(top = 16.dp)) {
            NavRow(
                DownloadSettings.NOTIFICATIONS, DownloadSettings.notificationsLine(notificationsOn), warn = !notificationsOn,
                onClick = { openNotificationSettings(context) },
            )
        }
        Spacer(Modifier.height(24.dp))
    }

    if (confirm) {
        ConfirmDialog(
            title = DownloadSettings.CONFIRM_TITLE,
            text = DownloadSettings.confirm(rows.orEmpty()),
            action = DownloadSettings.DELETE,
            onConfirm = {
                confirm = false
                model.deleteAll()
            },
            onDismiss = { confirm = false },
        )
    }
}

/**
 * Save downloads to's cell, as the grid's: [top] over [under], the [chosen] one ticked in the accent; a [greyed] one, not
 * yet offered, dashed and faint, and not to be picked.
 */
@Composable
private fun RowScope.PlaceCell(top: String, under: String, chosen: Boolean, greyed: Boolean = false) {
    val shape = RoundedCornerShape(12.dp)
    val outline = colors.outline
    val look = when {
        greyed -> Modifier.drawBehind {
            val stroke = 1.dp.toPx()
            drawRoundRect(
                outline, topLeft = Offset(stroke / 2, stroke / 2), size = Size(size.width - stroke, size.height - stroke),
                cornerRadius = CornerRadius(12.dp.toPx()), style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
            )
        }
        chosen -> Modifier.background(colors.accentTint).border(2.dp, colors.accent, shape)
        else -> Modifier.background(colors.surface).border(1.dp, colors.chip, shape)
    }
    Column(
        Modifier.weight(1f).heightIn(min = 60.dp).fillMaxHeight().clip(shape).then(look)
            .selectable(chosen, enabled = !greyed, role = Role.RadioButton) {}
            .padding(horizontal = 2.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            top, style = if (chosen) AppType.bodyStrong else AppType.body, textAlign = TextAlign.Center,
            color = if (greyed) colors.text.copy(alpha = GREYED_TEXT) else colors.text,
        )
        Text(under, style = AppType.small, textAlign = TextAlign.Center, color = if (greyed) colors.muted.copy(alpha = GREYED_UNDER) else colors.muted)
    }
}

/** A greyed cell's words: Material's disabled alpha, and its line a little stronger, as small as it is. */
private const val GREYED_TEXT = 0.38f
private const val GREYED_UNDER = 0.6f

/** Android's page for the app's notifications, where they are allowed again; the app's own settings page where there is none. */
private fun openNotificationSettings(context: Context) {
    val pages = listOf(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
    )
    for (page in pages) {
        if (context !is Activity) page.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(page)
            return
        } catch (e: ActivityNotFoundException) {
            // The next one.
        }
    }
}
