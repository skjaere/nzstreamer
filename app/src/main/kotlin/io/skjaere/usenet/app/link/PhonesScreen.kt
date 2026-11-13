package io.skjaere.usenet.app.link

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.saveOrLog
import io.skjaere.usenet.app.tv.searchKeyOpensSearch
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.GroupCard
import io.skjaere.usenet.app.ui.InsetDivider
import io.skjaere.usenet.app.ui.PrimaryButton
import io.skjaere.usenet.app.ui.SecondaryButton
import io.skjaere.usenet.app.ui.TopBar
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.rememberInitialFocus
import io.skjaere.usenet.app.ui.tryFocus
import io.skjaere.usenet.data.PairedPhone
import io.skjaere.usenet.link.KeyId
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

const val PHONES_TITLE = "Phones"

/** The Settings hub's line under Phones with none paired. */
const val PHONES_NONE = "Play on this TV from your phone"
const val PHONES_INTRO = "Phones paired here can play on this TV, and pause, seek or stop what plays. This TV hears them only while NZStreamer is open on it."
const val PHONES_EMPTY = "Pair your phone to play on this TV from NZStreamer on the phone. This TV hears it only while NZStreamer is open here."
const val PHONES_FOOT = "A removed phone can’t play here until it’s paired again."
const val PHONES_REMOVE = "Remove"
const val PHONES_REMOVE_TEXT = "It can no longer play on this TV. To use it again, pair it again."

/** "Remove Galaxy phone?" */
fun removeAsk(name: String) = "Remove $name?"

/** The hub's line under Phones (PtTvSettings): how many and their names, the first three of them, or what the page is for. */
fun phonesLine(phones: List<PairedPhone>): String {
    if (phones.isEmpty()) return PHONES_NONE
    val count = if (phones.size == 1) "1 phone" else "${phones.size} phones"
    val names = phones.take(NAMED).joinToString(", ") { it.name }
    val more = phones.size - NAMED
    return "$count · $names" + if (more > 0) " and $more more" else ""
}

/**
 * A paired phone's line: "Paired 1 Nov · Seen now" while its link is up ([connected]), else when it was last seen, as
 * the phone's TVs page says it of a TV ("Last seen 5 min ago"); nothing after the date when it never was. [now] is the
 * wall clock's, the dates in [zone]; a date in another year says its year.
 */
fun phoneLine(phone: PairedPhone, connected: Boolean, now: Long, zone: ZoneId): String = seenLine(phone.pairedAt, phone.lastSeen, connected, now, zone)

/** "Paired 1 Nov · Seen now", "Paired 1 Nov · Last seen 5 min ago", or "Paired 1 Nov": a peer's line, phone or TV. */
internal fun seenLine(pairedAt: Long, lastSeen: Long?, connected: Boolean, now: Long, zone: ZoneId): String {
    val paired = "Paired ${day(pairedAt, now, zone)}"
    val seen = when {
        connected -> "Seen now"
        lastSeen == null -> return paired
        else -> "Last seen " + ago(lastSeen, now, zone)
    }
    return "$paired · $seen"
}

private fun ago(at: Long, now: Long, zone: ZoneId): String {
    val minutes = (now - at).coerceAtLeast(0) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        else -> day(at, now, zone)
    }
}

/** "1 Nov", or "24 Dec 2025" in another year than [now]'s. */
private fun day(at: Long, now: Long, zone: ZoneId): String {
    val date = Instant.ofEpochMilli(at).atZone(zone)
    val sameYear = date.year == Instant.ofEpochMilli(now).atZone(zone).year
    return date.format(if (sameYear) DAY else DAY_YEAR)
}

private const val NAMED = 3
private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
private val DAY_YEAR = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

/** What Settings › Phones runs on: the app's ([of]), or fakes, for its screen tests. */
class PhonesDeps(
    val phones: Flow<List<PairedPhone>>,
    /** The phones with a link up now. */
    val connected: Flow<Set<KeyId>>,
    /** Remove (L30): the phone, its outbox and its links go. */
    val remove: suspend (KeyId) -> Unit,
    /** Wall-clock milliseconds, and the zone the dates are in. */
    val now: () -> Long,
    val zone: () -> ZoneId,
) {
    companion object {
        fun of(c: AppContainer) = PhonesDeps(c.data.link.phones, c.tvLink.connected, c.tvLink::remove, System::currentTimeMillis, ZoneId::systemDefault)
    }
}

/** A paired phone as the page lists it. */
class PhoneRow(val key: KeyId, val name: String, val line: String)

class PhonesViewModel(private val deps: PhonesDeps) : ViewModel() {
    /** A tick a minute, for "Last seen 5 min ago" to move on. */
    private val minutes = flow {
        while (true) {
            emit(Unit)
            delay(60_000)
        }
    }

    /** Null until the paired phones are read. */
    val rows: StateFlow<List<PhoneRow>?> = combine(deps.phones, deps.connected, minutes) { phones, connected, _ ->
        val now = deps.now()
        val zone = deps.zone()
        phones.mapNotNull { phone -> phone.key?.let { PhoneRow(it, phone.name, phoneLine(phone, it in connected, now, zone)) } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun remove(key: KeyId) {
        viewModelScope.launch { saveOrLog("remove a paired phone") { deps.remove(key) } }
    }
}

/**
 * Settings › Phones on a TV (PtTvSettings 2-4, L30): the paired phones, each with when it was paired and last seen and
 * Remove, which asks first (Cancel focused: two OKs in a row don't remove a phone); then Pair a phone, where the focus
 * starts, and Up from it reaches Remove. Removing closes the phone's links at once. [deps]: for tests, fakes in place of
 * the app's.
 */
@Composable
fun PhonesScreen(onBack: () -> Unit, onPair: () -> Unit, deps: PhonesDeps? = null) {
    val context = LocalContext.current
    val vm: PhonesViewModel = viewModel { PhonesViewModel(deps ?: PhonesDeps.of(context.container)) }
    val rows by vm.rows.collectAsStateWithLifecycle()
    var asking by remember { mutableStateOf<PhoneRow?>(null) }
    val pair = remember { FocusRequester() }
    val initial = rememberInitialFocus()
    // On Pair a phone as the list shows, and again as a removed phone's row goes with the focus on its Remove.
    val count = rows?.size
    LaunchedEffect(count) { if (count != null) initial.request(pair) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar(PHONES_TITLE, onBack)
        val list = rows
        when {
            list == null -> Unit
            list.isEmpty() -> {
                Text(PHONES_EMPTY, style = AppType.small, color = colors.muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                PrimaryButton(PAIR_PHONE_TITLE, onPair, Modifier.padding(horizontal = 16.dp).fillMaxWidth().focusRequester(pair))
            }
            else -> {
                Text(PHONES_INTRO, style = AppType.secondary, color = colors.muted, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp))
                GroupCard {
                    list.forEachIndexed { i, row ->
                        if (i > 0) InsetDivider()
                        key(row.key) { PhoneRowView(row) { asking = row } }
                    }
                }
                PrimaryButton(PAIR_PHONE_TITLE, onPair, Modifier.padding(16.dp).fillMaxWidth().focusRequester(pair))
                Text(PHONES_FOOT, style = AppType.small, color = colors.muted, modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
    asking?.let { row ->
        val cancel = remember { FocusRequester() }
        val dismiss = { asking = null }
        AlertDialog(
            onDismissRequest = dismiss,
            modifier = Modifier.searchKeyOpensSearch(dismiss),
            containerColor = colors.surface,
            title = { Text(removeAsk(row.name), style = AppType.cardTitle, color = colors.text) },
            text = { Text(PHONES_REMOVE_TEXT, style = AppType.body, color = colors.textSecondary) },
            confirmButton = {
                TextButton(onClick = {
                    asking = null
                    vm.remove(row.key)
                }) { Text(PHONES_REMOVE, style = AppType.bodyStrong, color = colors.warnStrong) }
            },
            dismissButton = {
                // Cancel first: OK twice in a row must not remove the phone.
                TextButton(onClick = dismiss, modifier = Modifier.focusRequester(cancel)) { Text("Cancel", style = AppType.body, color = colors.text) }
                LaunchedEffect(Unit) { cancel.requestFocusWhenReady() }
            },
        )
    }
}

@Composable
private fun PhoneRowView(row: PhoneRow, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(PhoneIcon, contentDescription = null, tint = colors.muted, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.name, style = AppType.bodyStrong, color = colors.text)
            Text(row.line, style = AppType.secondary, color = colors.muted)
        }
        SecondaryButton(PHONES_REMOVE, onRemove)
    }
}

/** A focus request that waits for the node: a dialog's window may not be laid out on its first frame. */
internal suspend fun FocusRequester.requestFocusWhenReady() {
    repeat(10) {
        if (tryFocus()) return
        delay(16)
    }
}
