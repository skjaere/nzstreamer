package io.skjaere.usenet.app.link

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.saveOrLog
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.Footnote
import io.skjaere.usenet.app.ui.GroupCard
import io.skjaere.usenet.app.ui.InsetDivider
import io.skjaere.usenet.app.ui.PlexMono
import io.skjaere.usenet.app.ui.PrimaryButton
import io.skjaere.usenet.app.ui.TopBar
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.focusOutline
import io.skjaere.usenet.data.PairedTv
import io.skjaere.usenet.link.KeyId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId

const val TVS_TITLE = "TVs"

/** The Settings hub's line under TVs with none paired. */
const val TVS_NONE = "None yet"
const val TVS_INTRO = "What you play on a TV goes to NZStreamer there. It must be open on the TV."
const val TVS_FOOT = "Forget removes the TV from this phone, and this phone from the TV when it can be reached. Otherwise remove the phone on the TV, under Settings › Phones."
const val TVS_EMPTY = "Play on NZStreamer on your Android TV or Fire TV, with this phone as the remote. Open NZStreamer on the TV first."
const val FORGET_TEXT = "To play on it again, you’ll pair it again. Plays already counted on this phone stay."
const val SET_UP_AGAIN_TEXT = "NZStreamer on the TV was reinstalled or reset, so it no longer matches this phone. Pair again to play there."
const val REMOVED_TEXT = "Pair again to play there."
const val PAIR_AGAIN = "Pair again"

/** "Forget Bedroom TV?" */
fun forgetAsk(name: String) = "Forget $name?"

/** PtPhonePairFail's dialog: "Living room TV was set up again". */
fun setUpAgainTitle(name: String) = "$name was set up again"

/** L30, L41: "Living room TV removed this phone". */
fun removedHere(name: String) = "$name removed this phone"

/** The hub's line under TVs (PtPhoneSettings): how many and their names, the first three of them, or "None yet". */
fun tvsLine(tvs: List<PairedTv>): String {
    if (tvs.isEmpty()) return TVS_NONE
    val names = tvs.take(NAMED).joinToString(", ") { it.name }
    val more = tvs.size - NAMED
    return "${tvs.size} · $names" + if (more > 0) " and $more more" else ""
}

/** A TV's line: "Paired 1 Nov · Seen now" while its link is up ([linked]), else when this phone last reached it. */
fun tvLine(tv: PairedTv, linked: Boolean, now: Long, zone: ZoneId): String = seenLine(tv.pairedAt, tv.lastSeen, linked, now, zone)

/** Where the TV was last reached, as its Pair a phone page shows its address: "ip", or "ip:port" off the default port (L12). */
fun tvAddressLine(tv: PairedTv): String? {
    val address = tv.address ?: return null
    val port = tv.port
    return address + if (port == null || port == PortChoice.DEFAULT) "" else ":$port"
}

/** A TV's state in words (PtPhoneSettings, L41). */
fun tvStateLine(state: TvState, name: String): String = when (state) {
    TvState.Ready -> "Ready"
    TvState.NotOpen -> "NZStreamer isn’t open there"
    TvState.NeedsProvider -> "Needs a Usenet provider on the TV"
    TvState.NeedsIndexer -> "Needs an indexer on the TV"
    // D2PhoneTvStates: what the TV's debrid way lacks, naming no service (L223).
    TvState.NeedsService -> "Needs a debrid service on the TV"
    TvState.ServiceOff -> "The TV’s debrid service is off"
    TvState.NeedsTorrentIndexer -> "Needs a torrent indexer on the TV"
    TvState.SetUpAgain -> "Set up again: pair again"
    TvState.RemovedHere -> removedHere(name)
    TvState.UpdateTv -> "Update NZStreamer on the TV"
    TvState.UpdatePhone -> "Update NZStreamer on this phone"
}

private const val NAMED = 3

/** A TV as Settings › TVs lists it: its [line] of when it was paired and seen, its [address], and its [state] once known. */
data class TvRow(val key: KeyId, val name: String, val line: String, val address: String?, val state: TvState?)

/** What Settings › TVs runs on: the app's ([of]), or a test's own links and store. */
class TvsDeps(
    val tvs: Flow<List<PairedTv>>,
    val states: Flow<Map<KeyId, TvNow>>,
    /** The TVs' links are held while the page is open, covered or not (L36). */
    val hold: () -> AutoCloseable,
    /** Forget (L30): Unpair when the TV can hear it, then the TV goes here. */
    val forget: suspend (KeyId) -> Unit,
    /** Wall-clock milliseconds, and the zone the dates are in. */
    val now: () -> Long,
    val zone: () -> ZoneId,
) {
    companion object {
        fun of(c: AppContainer) = of(c.tvLinks, c.data.link.tvs)

        fun of(links: TvLinks, tvs: Flow<List<PairedTv>>) = TvsDeps(tvs, links.states, links::hold, links::forget, System::currentTimeMillis, ZoneId::systemDefault)
    }
}

class TvsViewModel(private val deps: TvsDeps) : ViewModel() {
    /** A tick a minute, for "Last seen 5 min ago" to move on. */
    private val minutes = flow {
        while (true) {
            emit(Unit)
            delay(60_000)
        }
    }

    /** Null until the TVs kept are read. */
    val rows: StateFlow<List<TvRow>?> = combine(deps.tvs, deps.states, minutes) { tvs, states, _ ->
        val now = deps.now()
        val zone = deps.zone()
        tvs.mapNotNull { tv -> tv.key?.let { key -> states[key].let { state -> TvRow(key, tv.name, tvLine(tv, state?.linked == true, now, zone), tvAddressLine(tv), state?.state) } } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private var holding: AutoCloseable? = null

    /**
     * The page shows: its TVs' links are held, so each says how it is, until the page is gone ([stop], as it is cleared).
     * Covered or stopped for a while (Home, a rotation) it keeps them: the app leaving the front closes them a while
     * after (L36), so a recreation or a quick look elsewhere doesn't make them again.
     */
    fun start() {
        if (holding == null) holding = deps.hold()
    }

    fun stop() {
        holding?.close()
        holding = null
    }

    fun forget(key: KeyId) {
        viewModelScope.launch { saveOrLog("forget a TV") { deps.forget(key) } }
    }

    override fun onCleared() = stop()
}

/**
 * Settings › TVs on a phone (PtPhoneSettings, L30): the TVs paired, each with when it was paired and last seen, the
 * address it was last reached at, how it is now, and Forget, which asks first (Keep / Forget); Add in the bar, and with
 * none paired, what the page is for and Add a TV. A TV set up again, or one that removed this phone, opens its Pair
 * again question when its row is tapped. The TVs' links are held while the page is open (L36). [deps]: for tests.
 */
@Composable
fun TvsScreen(onBack: () -> Unit, onAdd: () -> Unit, onPairAgain: (KeyId) -> Unit, deps: TvsDeps? = null) {
    val context = LocalContext.current
    val vm: TvsViewModel = viewModel { TvsViewModel(deps ?: TvsDeps.of(context.container)) }
    LaunchedEffect(vm) { vm.start() }
    val rows by vm.rows.collectAsStateWithLifecycle()
    var forgetting by remember { mutableStateOf<TvRow?>(null) }
    var again by remember { mutableStateOf<TvRow?>(null) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar(TVS_TITLE, onBack) {
            TextButton(
                onClick = onAdd, modifier = Modifier.padding(end = 4.dp).focusOutline(CircleShape, fill = false),
                colors = ButtonDefaults.textButtonColors(containerColor = colors.chip, contentColor = colors.text),
            ) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("Add", style = AppType.body, modifier = Modifier.padding(start = 6.dp))
            }
        }
        val list = rows
        when {
            list == null -> Unit
            list.isEmpty() -> {
                Footnote(TVS_EMPTY)
                PrimaryButton(ADD_TV_TITLE, onAdd, Modifier.padding(horizontal = 16.dp).fillMaxWidth())
            }
            else -> {
                Text(TVS_INTRO, style = AppType.secondary, color = colors.muted, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp))
                GroupCard {
                    list.forEachIndexed { i, row ->
                        if (i > 0) InsetDivider()
                        key(row.key) {
                            TvRowView(row, onForget = { forgetting = row }, onAgain = { again = row }.takeIf { row.state == TvState.SetUpAgain || row.state == TvState.RemovedHere })
                        }
                    }
                }
                Footnote(TVS_FOOT)
            }
        }
    }
    forgetting?.let { row ->
        val dismiss = { forgetting = null }
        AlertDialog(
            onDismissRequest = dismiss,
            containerColor = colors.surface,
            title = { Text(forgetAsk(row.name), style = AppType.cardTitle, color = colors.text) },
            text = { Text(FORGET_TEXT, style = AppType.body, color = colors.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    forgetting = null
                    vm.forget(row.key)
                }) { Text("Forget", style = AppType.bodyStrong, color = colors.warnStrong) }
            },
            dismissButton = { TextButton(onClick = dismiss) { Text("Keep", style = AppType.body, color = colors.text) } },
        )
    }
    again?.let { row ->
        val dismiss = { again = null }
        val setUpAgain = row.state == TvState.SetUpAgain
        AlertDialog(
            onDismissRequest = dismiss,
            containerColor = colors.surface,
            title = { Text(if (setUpAgain) setUpAgainTitle(row.name) else removedHere(row.name), style = AppType.cardTitle, color = colors.text) },
            text = { Text(if (setUpAgain) SET_UP_AGAIN_TEXT else REMOVED_TEXT, style = AppType.body, color = colors.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    again = null
                    onPairAgain(row.key)
                }) { Text(PAIR_AGAIN, style = AppType.bodyStrong, color = colors.accent) }
            },
            dismissButton = { TextButton(onClick = dismiss) { Text("Not now", style = AppType.body, color = colors.text) } },
        )
    }
}

/** A TV's row (PtPhoneSettings 2): its icon, name, lines and state, and Forget at its end. [onAgain]: its Pair again question. */
@Composable
private fun TvRowView(row: TvRow, onForget: () -> Unit, onAgain: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        val tap = if (onAgain != null) {
            Modifier.focusOutline().clickable(remember { MutableInteractionSource() }, LocalIndication.current, role = Role.Button, onClick = onAgain)
        } else {
            Modifier
        }
        Row(Modifier.weight(1f).then(tap).padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(TvIcon, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.padding(top = 1.dp).size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(row.name, style = AppType.bodyStrong, color = colors.text)
                Text(row.line, style = AppType.secondary, color = colors.muted)
                row.address?.let { Text(it, style = ADDRESS, color = colors.muted) }
                row.state?.let { StateLine(it, row.name) }
            }
        }
        TextButton(onClick = onForget) { Text("Forget", style = AppType.secondary, color = colors.accent) }
    }
}

/** Ready with the hub rows' green dot; not open muted; anything that needs doing in the warning colour. */
@Composable
private fun StateLine(state: TvState, name: String) {
    val words = tvStateLine(state, name)
    when (state) {
        TvState.Ready -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(colors.goodDot))
            Text(words, style = AppType.small, color = colors.good)
        }
        TvState.NotOpen -> Text(words, style = AppType.small, color = colors.muted)
        else -> Text(words, style = AppType.small, color = colors.warnStrong)
    }
}

/** A TV's address: monospaced, at the secondary size, as it is typed (PtPhonePair). */
private val ADDRESS = TextStyle(fontFamily = PlexMono, fontSize = 13.sp, lineHeight = 19.sp)
