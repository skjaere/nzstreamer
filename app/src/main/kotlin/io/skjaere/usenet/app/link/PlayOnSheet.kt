package io.skjaere.usenet.app.link

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.cast.CastRoute
import io.skjaere.usenet.app.castExtras
import io.skjaere.usenet.app.casting.CastLink
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.play.SidePanelTitle
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.data.PairedTv
import io.skjaere.usenet.link.KeyId
import kotlinx.coroutines.flow.Flow

/** A question Play on asks before a TV can be played on (PtPhonePairFail, L30, L41), or a Cast speaker (W50, L122). */
sealed interface PlayOnAsk {
    /** NZStreamer on the TV was set up again: pair again to play there. */
    data class SetUpAgain(val key: KeyId, val name: String) : PlayOnAsk {
        override fun toString() = "SetUpAgain(${key.short})"
    }

    /** The TV removed this phone (Remove there): pair again to play there. */
    data class Removed(val key: KeyId, val name: String) : PlayOnAsk {
        override fun toString() = "Removed(${key.short})"
    }

    /** NZStreamer on the TV ([tv]) or on this phone is too old for the other. */
    data class Update(val tv: Boolean, val name: String) : PlayOnAsk {
        override fun toString() = "Update(${if (tv) "tv" else "phone"})"
    }

    /**
     * A Play to [name], the Cast speaker or speaker group whose session is at [route] (W50, L122): it plays only the
     * sound, though the whole video still goes there from this phone. Asked at the Play's tap, before anything is
     * searched; [answered] runs the answer: [castAnyway] plays it there, the session not asked again; [playHere] chooses
     * This phone (ending the session, L85) and plays it here; [cancel] plays nothing (Back too). It prints without its
     * name.
     */
    class SoundOnly(
        val route: String,
        val name: String,
        private val castAnyway: () -> Unit,
        private val playHere: () -> Unit,
        private val cancel: () -> Unit,
    ) : PlayOnAsk {
        /** Its buttons, in their order (L149's words). */
        enum class Answer(val words: String) {
            CastAnyway("Cast anyway"),
            PlayHere("Play on this phone"),
            Cancel("Cancel"),
        }

        fun answered(answer: Answer) = when (answer) {
            Answer.CastAnyway -> castAnyway()
            Answer.PlayHere -> playHere()
            Answer.Cancel -> cancel()
        }

        override fun toString() = "SoundOnly"
    }
}

/** The TV a question names; null for an update's, which is the chosen TV's. */
val PlayOnAsk.key: KeyId?
    get() = when (this) {
        is PlayOnAsk.SetUpAgain -> key
        is PlayOnAsk.Removed -> key
        is PlayOnAsk.Update, is PlayOnAsk.SoundOnly -> null
    }

/** What a tap on a row of the Play on sheet does. */
sealed interface SheetTap {
    /** Plays go there from now (null: This phone), and the sheet closes. */
    data class Choose(val key: KeyId?) : SheetTap

    /** Nothing: the TV can't be chosen until it is set up. */
    data object Nothing : SheetTap

    data class Ask(val ask: PlayOnAsk) : SheetTap
}

/**
 * A TV's row on the Play on sheet (PtPhoneSheet): its [name] as kept, its [state] as its link last read it (null until
 * tried), whether it is the one [chosen], and where the list was opened from ([place]). Opened from a screen whose Play
 * plays on this phone with a TV chosen (Search, a release list, Import, the player), every TV is greyed and says it
 * plays from a title's page, as Play on TV can't play from there yet (L116), and none can be chosen there.
 */
data class PlayOnRow(val key: KeyId, val name: String, val state: TvState?, val chosen: Boolean, val place: SheetPlace = SheetPlace.Title) {
    /** Whether its TV can play from where the list was opened: from a title's page alone. */
    private val playsFromHere: Boolean get() = place == SheetPlace.Title

    /** Under the name: how the TV is now, nothing until its link says; or where it plays from, where it can't from here. */
    val line: String? get() = if (playsFromHere) state?.let { sheetStateLine(it, name) } else TITLE_PAGE_LINE

    /** [line] in its tone: Ready in green with the dot, not open muted, what needs doing a warning after ⚠; where it plays from muted. */
    val rowLine: RowLine?
        get() = line?.let { words ->
            RowLine(words, when {
                !playsFromHere -> RowLine.Tone.Muted
                state == TvState.Ready -> RowLine.Tone.Good
                state == TvState.NotOpen -> RowLine.Tone.Muted
                else -> RowLine.Tone.Warning
            })
        }

    /** Greyed: every TV but a ready one, and one not tried yet; every TV where it can't play from here. */
    val greyed: Boolean get() = !playsFromHere || (state != null && state != TvState.Ready)

    /**
     * A ready TV, or one not open (Play waits greyed until it opens), is chosen; one to set up can't be; the others ask.
     * None can be where it can't play from here.
     */
    val tap: SheetTap
        get() = if (!playsFromHere) {
            SheetTap.Nothing
        } else {
            when (state) {
                null, TvState.Ready, TvState.NotOpen -> SheetTap.Choose(key)
                TvState.NeedsProvider, TvState.NeedsIndexer, TvState.NeedsService, TvState.ServiceOff, TvState.NeedsTorrentIndexer -> SheetTap.Nothing
                TvState.SetUpAgain -> SheetTap.Ask(PlayOnAsk.SetUpAgain(key, name))
                TvState.RemovedHere -> SheetTap.Ask(PlayOnAsk.Removed(key, name))
                TvState.UpdateTv -> SheetTap.Ask(PlayOnAsk.Update(tv = true, name = name))
                TvState.UpdatePhone -> SheetTap.Ask(PlayOnAsk.Update(tv = false, name = name))
            }
        }

    /** What TalkBack reads of a row whose line is a warning: its name, then the line with ⚠ as "Warning" (L251); null reads it as shown. */
    val spoken: String? get() = rowLine?.takeIf { it.tone == RowLine.Tone.Warning }?.let { "$name, Warning: ${it.words}" }

    override fun toString() = "PlayOnRow(${key.short}, $state${if (chosen) ", chosen" else ""}${if (playsFromHere) "" else ", $place"})"
}

/** A TV's line where it can't play from (CsPhonePlaces; L116): until Play on TV's S2 and S3, it plays from a title's page. */
const val TITLE_PAGE_LINE = "Plays from the title’s page"

/**
 * Whether This phone's row is ticked: while it is chosen; and, where the list was opened from a screen whose Play plays
 * on this phone with a TV chosen ([place]: Search, a release list, Import, the player), while a TV is chosen too, as Play
 * plays here from there (W59). A Cast device chosen is ticked in its own group, from every place.
 */
fun thisPhoneTicked(chosen: PlayChoice, place: SheetPlace): Boolean = chosen.thisPhone || (place != SheetPlace.Title && chosen is PlayChoice.Tv)

/** What a [tap] on a row of the list does: a choice made for every screen, the list closed; a question asked; or nothing. */
fun PlayOn.tap(tap: SheetTap) {
    when (tap) {
        is SheetTap.Choose -> {
            choose(tap.key)
            closeSheet()
        }
        SheetTap.Nothing -> Unit
        is SheetTap.Ask -> ask(tap.ask)
    }
}

/**
 * The sheet's TV rows: those kept, in their order, each with its state now and the one [chosen] ticked, as the list
 * opened from [place] shows them: where TVs can't play from there, greyed, none ticked.
 */
fun playOnRows(tvs: List<PairedTv>, states: Map<KeyId, TvNow>, chosen: KeyId?, place: SheetPlace = SheetPlace.Title): List<PlayOnRow> =
    tvs.mapNotNull { tv -> tv.key?.let { key -> PlayOnRow(key, tv.name, states[key]?.state, chosen = place == SheetPlace.Title && key == chosen, place) } }

/** A TV's state in the sheet's words (PtPhoneSheet; the rest as Settings › TVs says them, L41). */
fun sheetStateLine(state: TvState, name: String): String = if (state == TvState.NotOpen) "Open NZStreamer on the TV" else tvStateLine(state, name)

/**
 * Whether Play goes now: on This phone, to a TV that answers (one that can't play yet says what it lacks, D2PhoneTvStates
 * 3), or to a Cast device once connected.
 */
val PlayTarget.open: Boolean
    get() = when (this) {
        PlayTarget.Phone -> true
        is PlayTarget.Tv -> state in ANSWERS
        is PlayTarget.Cast -> link == CastLink.Connected
    }

/** The states of a TV that answers a Play: ready, or not ready and saying what it lacks. */
private val ANSWERS = setOf(TvState.Ready, TvState.NeedsProvider, TvState.NeedsIndexer, TvState.NeedsService, TvState.ServiceOff, TvState.NeedsTorrentIndexer)

/**
 * Whether Play is greyed: the chosen TV's NZStreamer isn't open, or it isn't reached yet; the chosen Cast device connects
 * or reconnects (L77). Play on a TV set up again, or that removed this phone, asks to pair again instead
 * (PtPhonePairFail), so it isn't greyed.
 */
val PlayTarget.greyed: Boolean
    get() = when (this) {
        PlayTarget.Phone -> false
        is PlayTarget.Tv -> state == null || state == TvState.NotOpen
        is PlayTarget.Cast -> link != CastLink.Connected
    }

/** The device Play goes to, by its name; null for This phone. */
val PlayTarget.deviceName: String?
    get() = when (this) {
        PlayTarget.Phone -> null
        is PlayTarget.Tv -> name
        is PlayTarget.Cast -> name
    }

/**
 * The Cast button for TalkBack (item 23, L79, L113): "Play on: this phone", "Play on: Living room TV", "Play on: Living room TV,
 * not open", "Play on: Kitchen display", "Play on: Kitchen display, connecting" (or reconnecting: the button looks the same).
 */
fun playOnLabel(target: PlayTarget): String = when (target) {
    PlayTarget.Phone -> "Play on: this phone"
    is PlayTarget.Tv -> "Play on: ${target.name}" + if (target.open) "" else ", not open"
    is PlayTarget.Cast -> "Play on: ${target.name}" + if (target.open) "" else ", connecting"
}

/** Under Play's words while a TV is chosen: "on Living room TV". */
fun onTvLine(name: String) = "on $name"

/** Under Play while the chosen TV's NZStreamer isn't open (W11, PtPhoneWhere B). */
fun openNzStreamerOn(name: String) = "Open NZStreamer on $name"

const val SHEET_TITLE = "Play on"
const val THIS_PHONE = "This phone"
const val TVS_WITH_NZSTREAMER = "TVs with NZStreamer"

/** The speaker question's title and words (W50, L122, L149): "Hallway speaker plays only the sound". */
fun soundOnlyTitle(name: String) = "$name plays only the sound"
const val SOUND_ONLY_TEXT = "It still gets the whole video from this phone, picture included."

/** Add a TV's line on the sheet with no TV paired (W12). */
const val ADD_TV_LINE = "Play on NZStreamer on your TV, with this phone as the remote."
const val PLAY_ON_FOOT = "The TV finds the release on its own indexers and plays it with its own player. NZStreamer must be open on the TV."

/** The Movie page's line under Choose release while a TV is chosen (PtPhoneMovie). */
const val CHOSEN_PLAYS_HERE = "A release you choose plays on this phone."

/** What the Play on sheet and its questions run on: the app's ([of]), or a test's own links and store. */
class PlayOnDeps(
    val playOn: PlayOn,
    val tvs: Flow<List<PairedTv>>,
    val states: Flow<Map<KeyId, TvNow>>,
    /** The TVs' links are held while the sheet shows, so each row says how its TV is (L36). */
    val hold: () -> AutoCloseable,
    /** Whether this is a TV, where nothing of Cast shows or starts (L67); the sheet itself shows on phones and tablets alone. */
    val television: Boolean = false,
) {
    companion object {
        fun of(c: AppContainer) = PlayOnDeps(c.playOn, c.data.link.tvs, c.tvLinks.states, c.tvLinks::hold, c.television)
    }
}

/**
 * Play on, over any screen on a phone or tablet (W11, W12): its sheet while open, with the list as it was opened from
 * where it was ([PlayOn.sheet], L148), and the question Play or a row asks ([PlayOn.asking]). [onAddTv] opens Add a TV, which chooses the TV it pairs; [onPairAgain] opens it for a
 * TV set up again or that removed this phone; [onPrivacy] the privacy page, from the Cast group's Privacy (L138), the
 * sheet closed for each. [deps]: for tests.
 */
@Composable
fun PlayOnHost(onAddTv: () -> Unit, onPairAgain: (KeyId) -> Unit, onPrivacy: () -> Unit, deps: PlayOnDeps? = null) {
    val context = LocalContext.current
    val d = remember { deps ?: PlayOnDeps.of(context.container) }
    val place by d.playOn.sheet.collectAsStateWithLifecycle()
    val asking by d.playOn.asking.collectAsStateWithLifecycle()
    place?.let { from ->
        PlayOnSheet(
            d, from,
            onAddTv = {
                d.playOn.closeSheet()
                onAddTv()
            },
            onPrivacy = {
                d.playOn.closeSheet()
                onPrivacy()
            },
        )
    }
    asking?.let { ask ->
        PlayOnQuestion(ask, onDismiss = d.playOn::answered, onPairAgain = { key ->
            d.playOn.answered()
            d.playOn.closeSheet()
            onPairAgain(key)
        })
    }
}

/** The Play on sheet (PtPhoneSheet): the play sheet's look, holding Play on's list as it was opened from [place]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayOnSheet(d: PlayOnDeps, place: SheetPlace, onAddTv: () -> Unit, onPrivacy: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = d.playOn::closeSheet, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.background, dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        PlayOnList(d, place, onAddTv, onPrivacy, Modifier.navigationBarsPadding().padding(bottom = 16.dp).verticalScroll(rememberScrollState()))
    }
}

/**
 * Play on's list (PtPhoneSheet, CsPhonePlaces, CsPhoneSheetConnected; L151), the same wherever it shows: on a phone with a
 * Cast session connected or reconnecting, the device's block first (L117); "Play on", This phone, then "TVs with
 * NZStreamer", each with its state and ✓ on the choice; in a build with Cast, its group (L78); Add a TV; and the
 * footnote. With no TV paired, This phone and Add a TV with what it is for (W12). Opened from a screen whose Play plays on
 * this phone with a TV chosen ([place]: Search, a release list, Import), the TVs are greyed with where they play from
 * (L116), and with a TV chosen This phone is the ticked row (W59): a tap on it chooses This phone everywhere. Opened
 * from the player, it is the player's side panel's (PlayOnPanel), its title the panel's heading. Its host scrolls it
 * ([modifier]: the sheet's own, the panel's by itself), and the TVs' links are held while it shows. A row's choice
 * closes the sheet as it is made ([PlayOn.tap]); a host of its own, the player's panel, hears of it just before it is
 * made ([onChoosing]), as the choice may end a Cast session at once (L85), and closes. A Cast row's tap goes first to
 * [onCastTapped], and the group connects nothing when it answers true (L151).
 */
@Composable
fun PlayOnList(
    d: PlayOnDeps,
    place: SheetPlace,
    onAddTv: () -> Unit,
    onPrivacy: () -> Unit,
    modifier: Modifier = Modifier,
    onCastTapped: ((CastRoute) -> Boolean)? = null,
    onChoosing: () -> Unit = {},
) {
    fun tap(tap: SheetTap) {
        if (tap is SheetTap.Choose) onChoosing()
        d.playOn.tap(tap)
    }
    DisposableEffect(d) {
        val held = d.hold()
        onDispose { held.close() }
    }
    val tvs by d.tvs.collectAsStateWithLifecycle(emptyList())
    val states by d.states.collectAsStateWithLifecycle(emptyMap())
    val chosen by d.playOn.chosen.collectAsStateWithLifecycle()
    val rows = playOnRows(tvs, states, (chosen as? PlayChoice.Tv)?.key, place)
    Column(modifier) {
        if (!d.television) castExtras?.let { CastBlock(it) }
        // A heading wherever it shows, as the block's device and "Cast to" are, so TalkBack's headings reach This phone too.
        if (place == SheetPlace.Player) {
            SidePanelTitle(SHEET_TITLE)
        } else {
            Text(SHEET_TITLE, style = AppType.screenTitle, color = colors.text, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 6.dp).semantics { heading() })
        }
        DeviceRow(PhoneIcon, THIS_PHONE, chosen = thisPhoneTicked(chosen, place)) { tap(SheetTap.Choose(null)) }
        if (rows.isNotEmpty()) {
            Text(TVS_WITH_NZSTREAMER.uppercase(), style = AppType.section, color = colors.muted, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 6.dp))
            rows.forEach { row ->
                DeviceRow(TvIcon, row.name, chosen = row.chosen, greyed = row.greyed, enabled = row.tap != SheetTap.Nothing, line = row.rowLine, description = row.spoken) {
                    tap(row.tap)
                }
            }
        }
        castExtras?.SheetGroup(d.playOn, d.television, tvs, onPrivacy, onCastTapped)
        Box(Modifier.padding(horizontal = 20.dp, vertical = 8.dp).fillMaxWidth().height(1.dp).background(colors.divider))
        AddRow(if (rows.isEmpty()) ADD_TV_LINE else null, onAddTv)
        if (rows.isNotEmpty()) Text(PLAY_ON_FOOT, style = AppType.small, color = colors.muted, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp))
    }
}

/** A device's line under its name (L78): its [words], in its [tone]. */
data class RowLine(val words: String, val tone: Tone) {
    enum class Tone {
        /** In green, after the connected provider's dot: a TV ready, a Cast device connected. */
        Good,
        Muted,

        /** In the warning colour, after ⚠: what needs doing. */
        Warning,
    }
}

/** What sits at a device row's end (L78): the ✓ on the choice, or a spinner while it connects. */
enum class RowEnd { None, Spinner }

/**
 * A device's row, as the track sheet's rows are (PtPhoneSheet): 8 dp in, at least 56 dp, 12 dp corners; its icon ([iconTint]
 * for a live Cast device's), its name (bold while chosen, muted while greyed) and its [line]; at its end [trailing] (a
 * spinner), else ✓ on the choice. One of the sheet's choices, so TalkBack says which is chosen;
 * [description] is what TalkBack reads for its words, where they need more than the name and line (a Cast device's).
 */
@Composable
internal fun DeviceRow(
    icon: ImageVector,
    name: String,
    chosen: Boolean,
    greyed: Boolean = false,
    enabled: Boolean = true,
    line: RowLine? = null,
    iconTint: Color? = null,
    description: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.padding(horizontal = 8.dp).fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp))
            .selectable(selected = chosen, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = iconTint ?: if (greyed) colors.muted else colors.text, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(name, style = if (chosen) AppType.bodyStrong else AppType.body, color = if (greyed) colors.muted else colors.text)
            if (line != null) LineView(line)
        }
        when {
            trailing != null -> trailing()
            chosen -> Icon(Icons.Filled.Check, contentDescription = null, tint = colors.accent, modifier = Modifier.size(22.dp))
        }
    }
}

/** [line] in its tone: green with the connected provider's dot; muted; or the warning colour after ⚠. */
@Composable
internal fun LineView(line: RowLine, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        val colour: Color = when (line.tone) {
            RowLine.Tone.Good -> {
                Box(Modifier.size(7.dp).clip(CircleShape).background(colors.goodDot))
                colors.good
            }
            RowLine.Tone.Muted -> colors.muted
            RowLine.Tone.Warning -> {
                Text("⚠", style = AppType.small, color = colors.warnStrong)
                colors.warnStrong
            }
        }
        Text(line.words, style = AppType.small, color = colour)
    }
}

/** Add a TV, in the accent; with no TV paired, what it is for under it (W12). */
@Composable
private fun AddRow(line: String?, onClick: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 8.dp).fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Filled.Add, contentDescription = null, tint = colors.accent, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(ADD_TV_TITLE, style = AppType.bodyStrong, color = colors.accent)
            line?.let { Text(it, style = AppType.small, color = colors.muted) }
        }
    }
}

/**
 * Play on's questions (PtPhonePairFail, L41): a TV set up again, or that removed this phone, with Not now and Pair again;
 * an update needed on either end, in Add a TV's words, with Close; a Play to a Cast speaker ([SoundOnlyQuestion]).
 */
@Composable
private fun PlayOnQuestion(ask: PlayOnAsk, onDismiss: () -> Unit, onPairAgain: (KeyId) -> Unit) {
    val (title, text, again) = when (ask) {
        is PlayOnAsk.SetUpAgain -> Triple(setUpAgainTitle(ask.name), SET_UP_AGAIN_TEXT, ask.key)
        is PlayOnAsk.Removed -> Triple(removedHere(ask.name), REMOVED_TEXT, ask.key)
        is PlayOnAsk.Update -> (if (ask.tv) AddTvFailure.UpdateTv else AddTvFailure.UpdatePhone).let { Triple(failureTitle(it, ask.name), failureText(it, ask.name), null) }
        is PlayOnAsk.SoundOnly -> return SoundOnlyQuestion(ask, onDismiss)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = { Text(title, style = AppType.cardTitle, color = colors.text) },
        text = text?.let { { Text(it, style = AppType.body, color = colors.textSecondary) } },
        confirmButton = {
            if (again != null) {
                TextButton(onClick = { onPairAgain(again) }) { Text(PAIR_AGAIN, style = AppType.bodyStrong, color = colors.accent) }
            } else {
                TextButton(onClick = onDismiss) { Text("Close", style = AppType.bodyStrong, color = colors.accent) }
            }
        },
        dismissButton = if (again != null) {
            { TextButton(onClick = onDismiss) { Text("Not now", style = AppType.body, color = colors.text) } }
        } else {
            null
        },
    )
}

/**
 * A Play to a Cast speaker or speaker group (W50, L122, L149): "Hallway speaker plays only the sound" over what still
 * goes there, then Cast anyway (in the accent), Play on this phone and Cancel, one under another, read in that order. Back
 * or a tap outside is Cancel. [onAnswered] puts the question away; the answer then runs its Play. The player asks it
 * too, over the film, before Play & connect connects a speaker (L124).
 */
@Composable
internal fun SoundOnlyQuestion(ask: PlayOnAsk.SoundOnly, onAnswered: () -> Unit) {
    fun answer(with: PlayOnAsk.SoundOnly.Answer) {
        onAnswered()
        ask.answered(with)
    }
    AlertDialog(
        onDismissRequest = { answer(PlayOnAsk.SoundOnly.Answer.Cancel) },
        containerColor = colors.surface,
        title = { Text(soundOnlyTitle(ask.name), style = AppType.cardTitle, color = colors.text) },
        text = { Text(SOUND_ONLY_TEXT, style = AppType.body, color = colors.textSecondary) },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                PlayOnAsk.SoundOnly.Answer.entries.forEach { button ->
                    TextButton(onClick = { answer(button) }) {
                        val main = button == PlayOnAsk.SoundOnly.Answer.CastAnyway
                        Text(button.words, style = if (main) AppType.bodyStrong else AppType.body, color = if (main) colors.accent else colors.text)
                    }
                }
            }
        },
    )
}
