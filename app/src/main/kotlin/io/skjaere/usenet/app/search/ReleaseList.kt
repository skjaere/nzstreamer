package io.skjaere.usenet.app.search

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.link.CastButton
import io.skjaere.usenet.app.link.CastConnectedIcon
import io.skjaere.usenet.app.link.SheetPlace
import io.skjaere.usenet.app.play.missingChip
import io.skjaere.usenet.app.play.playCandidate
import io.skjaere.usenet.app.tv.LocalRailFocus
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.ChipKind
import io.skjaere.usenet.app.ui.DownloadIcon
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.ui.HiddenLine
import io.skjaere.usenet.app.ui.InfoChip
import io.skjaere.usenet.app.ui.LocalFormFactor
import io.skjaere.usenet.app.ui.SectionHeader
import io.skjaere.usenet.app.ui.TopBar
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.focusOutline
import io.skjaere.usenet.app.ui.rememberInitialFocus
import io.skjaere.usenet.app.ui.tryFocus
import io.skjaere.usenet.core.formatSize
import io.skjaere.usenet.data.KnownMissing
import io.skjaere.usenet.search.RankedItem
import io.skjaere.usenet.search.ResultGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.flow.catch
import java.time.LocalDate
import java.time.ZoneId

/** The list's orders: as ranked, largest first, newest first. */
internal enum class ReleaseSort(val label: String) {
    Best("Best match"),
    Size("Size"),
    Age("Age"),
    ;

    /** [items] in this order. */
    fun of(items: List<RankedItem>): List<RankedItem> = when (this) {
        Best -> items
        Size -> items.sortedByDescending { it.item.sizeBytes }
        Age -> items.sortedByDescending { it.item.postedAt }
    }
}

/**
 * The releases the list shows in [sort]'s order: [group]'s playable ones, then a plugin's [possible] matches', each
 * section ordered on its own, so the possible matches stay under the others (L628). With none, the list as it always was.
 */
internal fun releaseSections(group: ResultGroup, possible: ResultGroup?, sort: ReleaseSort): Pair<List<RankedItem>, List<RankedItem>> =
    sort.of(group.playable) to sort.of(possible?.playable.orEmpty())

/**
 * The list's bar: [heading] (a plugin's item's title) or [group]'s label, over [title] and how many releases it shows from
 * how many indexers, a plugin's [possible] matches counted; with no [title], the count alone.
 */
internal fun releaseListBar(group: ResultGroup, title: String, heading: String? = null, possible: ResultGroup? = null): Pair<String, String> =
    (heading ?: group.label) to listOf(title, releasesFrom(group, possible)).filter(String::isNotBlank).joinToString(" · ")

/**
 * A plugin's possible matches on its item's Choose release (L628; SpPhoneChoose, SpTvChoose): [group]'s releases, each
 * with "Possible" in its rank's place and its reason ([reasons], by [RankedItem.key]) under its name, below the heading
 * and a line naming the item by its [noun]; [leftOut], the plugin's one line of what it left out, ends the list.
 */
class PossibleMatches(val group: ResultGroup, val reasons: Map<String, String>, val noun: String?, val leftOut: String?) {
    /**
     * The line under the heading, naming the item by its noun ("Tap one if it's the right game."), or "the right release"
     * with none; [only] while nothing else is listed ("Nothing names this game for sure yet. …"); on a [tv] "Press OK on
     * one" in place of "Tap one".
     */
    fun line(only: Boolean, tv: Boolean): String {
        val pick = if (tv) "press OK on one" else "tap one"
        val right = "it's the right ${noun ?: "release"}"
        return if (only) {
            "Nothing names ${noun?.let { "this $it" } ?: "it"} for sure yet. Play never picks these; $pick if $right."
        } else {
            "Play never picks these. ${pick.replaceFirstChar(Char::uppercase)} if $right."
        }
    }

    companion object {
        const val HEADING = "Possible matches"

        /** What a possible match reads in its rank's place. */
        const val RANK = "Possible"
    }
}

/** What the list's watch of the releases found missing is called, as the engine's lock lines name who holds it. */
private val RELEASE_LIST_MARKS = CoroutineName("release list marks")

/**
 * Every release of one episode/pack/movie, ranked, with reasons; tap one to play it. One found
 * missing on the providers in use before says so: tapped, it is tried all the same. One played
 * from here that [failed] to start says why ([ChosenPlays]), as the play sheet did. The list stays
 * under the sheet meanwhile, so a remote's focus is still on that release as the sheet closes,
 * and the next press picks another. With [onChooseFile], a release named as several episodes has a
 * menu (its ⋮, or a long press) offering Choose file ([offersFileChoice]), which plays it asking
 * which of its videos is the one. The Cast button sits in its bar, saying where Play goes from here (CsPhonePlaces).
 * [listModifier] goes on the list of releases (under its sorts). Ranked for a Cast device ([rankedFor], L127;
 * CsPhoneRanked), it says so under its sorts, fixed with them: Google's filled Cast icon and "Ranked for Kitchen
 * display", which TalkBack reads once, before the releases. While a debrid service is on, each release says where it
 * plays from after its rank ([SourceTagChip]), and the hidden line names its commonest reasons (L201, L199); else the
 * list is as it always was. A press of an order (Best match, Size, Age) starts the list at that order's first release
 * (W105); the order is kept with the list's place as the same list (its group's, on Search its picked title's too:
 * [ForPickedTitle]) shows again.
 *
 * In download [mode] ([ReleaseListMode.Download], L355, L386) the list is for this device: "Download S02E04" in a bar with
 * no Cast button, the download icon's line in the "Ranked for" line's place, each release that won't fit greyed in its
 * place with what it needs, a season pack's share of the episode under its name, and a tap that downloads the release at
 * once ([ReleaseListMode.Download.onDownload]) rather than playing it ([onPlay], a play's). Choose file there is the
 * mode's ([ReleaseListMode.Download.fileChoice], L403): on a pack's card that fits, greyed "Needs Wi-Fi" on mobile data;
 * [onChooseFile] is a play's. One marked [failed] there is a pack whose Choose file couldn't list its files.
 *
 * A plugin's item's list (L628) has its title as the bar's [heading] and its [possible] matches under the releases, each
 * order sorting both sections on its own ([releaseSections]); with nothing else listed, the possible matches alone under
 * their own line. On a TV its first release takes the first focus, else its first possible match. Search's list of a
 * search a plugin opened has the plugin's [note] under its sorts, where the "Ranked for" line goes (L629). Movie and
 * episode lists pass neither.
 */
@Composable
fun ReleaseList(
    group: ResultGroup,
    title: String,
    onBack: () -> Unit,
    failed: Map<String, String> = emptyMap(),
    onChooseFile: ((RankedItem) -> Unit)? = null,
    listModifier: Modifier = Modifier,
    rankedFor: String? = null,
    mode: ReleaseListMode = ReleaseListMode.Play,
    possible: PossibleMatches? = null,
    heading: String? = null,
    note: String? = null,
    onPlay: (RankedItem) -> Unit = {},
) {
    val download = mode as? ReleaseListMode.Download
    // One tap downloads: a second one before its caller closes the list queues nothing more.
    var downloading by remember(group.key) { mutableStateOf(false) }
    // Saved with the list's place, which belongs to the order it was read in (L322). Both under the group's key: a page's
    // saved state outlives a list the page doesn't save (a chooser's, once the process has gone), and another group's list
    // shown there later must open as any list does, not in this one's order and at its place (RFO-1). Search, whose picked
    // titles' groups are keyed alike, puts its list under the title's key too ([ForPickedTitle]).
    val (order, rows) = key(group.key) { rememberSaveable { mutableStateOf(ReleaseSort.Best) } to rememberLazyListState() }
    var sort by order
    // A press of an order, the order already chosen too, starts the list at its first release (W105, L321). Asked for
    // once the rows are in that order: the rows are keyed, and a measure of the old order between the press and the
    // re-sort would have the list follow the old first release, now mid-list. Not saved: a list shown again keeps its place.
    var toTop by remember { mutableStateOf(false) }
    if (toTop) SideEffect {
        toTop = false
        rows.requestScrollToItem(0)
    }
    var showHidden by remember { mutableStateOf(false) }
    val tv = LocalFormFactor.current == FormFactor.Tv
    val container = LocalContext.current.container
    val missing = container.missingReleases
    val playOn by container.playOn.target.collectAsStateWithLifecycle()
    // The service accounts on: each release's tag and the hidden line's reasons show only while one is (L201).
    val accounts by remember(container) { container.serviceNames }.collectAsStateWithLifecycle(emptyList())
    // A list chosen on a title's page shows seconds after what had the focus went (its finder's search): on a TV
    // the shell then puts the focus in it, as it does when the screen changes.
    val rail = LocalRailFocus.current
    LaunchedEffect(Unit) { rail?.contentChanged() }
    // Watched, not read once: the list stays up under the play sheet, and Search's while the player plays. Named for the
    // engine's lock lines: what is known depends on the providers the engine has checked, under its lock (#273).
    val known by remember(group, possible) {
        missing.known((group.playable + group.hidden + possible?.group?.let { it.playable + it.hidden }.orEmpty()).map { it.key })
            // Unread, nothing is marked: each is tried as a play tries it.
            .catch { emit(emptyMap()) }
    }.collectAsState(emptyMap(), context = RELEASE_LIST_MARKS)
    // A torrent a play would skip, as every account it plays from failed it before, is marked as the failure says (L174):
    // "✕ No longer cached". Read as the list shows; one this list's own play fails is marked by [failed].
    val serviceMarks by produceState(emptyMap<String, String>(), group, possible, accounts) {
        value = try {
            val tapped = group.playable + possible?.group?.playable.orEmpty()
            container.serviceMisses.marks(tapped.filter { it.item.infoHash != null }.map { it.playCandidate })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Unread, nothing is marked: each is tried as a play tries it.
            emptyMap()
        }
    }
    val (sorted, possibleSorted) = releaseSections(group, possible?.group, sort)
    // A plugin's list on a TV: its first release takes the first focus, Best match, else its first possible match.
    val first = remember { FocusRequester() }
    val firstKey = (sorted.firstOrNull() ?: possibleSorted.firstOrNull())?.key?.takeIf { tv && possible != null }
    if (firstKey != null) {
        val initial = rememberInitialFocus()
        LaunchedEffect(group.key) {
            initial.awaitTurn()
            repeat(FIRST_FOCUS_FRAMES) {
                if (first.tryFocus()) return@LaunchedEffect
                withFrameNanos {}
            }
        }
    }
    fun Modifier.firstFocus(item: RankedItem): Modifier = if (item.key == firstKey) focusRequester(first) else this
    Column(Modifier.fillMaxSize()) {
        if (download == null) {
            val (barTitle, subtitle) = releaseListBar(group, title, heading, possible?.group)
            TopBar(barTitle, onBack, subtitle = subtitle) {
                // A release played from a list plays here with a TV chosen (W11): the list greys the TVs, the face says this phone (W59).
                CastButton(playOn, { container.playOn.openSheet(SheetPlace.PhoneOnly) }, place = SheetPlace.PhoneOnly)
            }
        } else {
            // No Cast button: a download is for this device, wherever Play goes.
            val (barTitle, subtitle) = download.bar(group, title)
            TopBar(barTitle, onBack, subtitle = subtitle)
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ReleaseSort.entries.forEach { s ->
                val selected = s == sort
                Text(
                    s.label, style = if (selected) AppType.bodyStrong.copy(fontSize = AppType.secondary.fontSize) else AppType.secondary,
                    color = if (selected) colors.accent else colors.textSecondary,
                    // At least 32 dp: taller with a large font size, so the label isn't cut off.
                    // The focus's ring over the chip's own border, not under it.
                    modifier = Modifier.heightIn(min = 32.dp).clip(RoundedCornerShape(8.dp)).focusOutline(RoundedCornerShape(8.dp))
                        .then(if (selected) Modifier.background(colors.accentTint) else Modifier.border(1.dp, colors.chip, RoundedCornerShape(8.dp)))
                        .clickable { sort = s; toTop = true }.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
        if (download != null) {
            DownloadLine(download.lines(group))
        } else {
            rankedFor?.let { RankedForLine(it) }
            note?.let { SearchNoteLine(it, Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 6.dp)) }
        }
        LazyColumn(listModifier.fillMaxSize(), state = rows, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
            itemsIndexed(sorted, key = { _, it -> it.key }) { i, item ->
                val wontFit = download?.wontFit(item)
                // A download's Choose file, as its mode offers it on this card: none, offered, or greyed for Wi-Fi (L403).
                val offer = download?.fileChoice(item)
                val play = { onPlay(item) }
                val save = {
                    if (!downloading) {
                        downloading = true
                        download?.onDownload(item)
                    }
                }
                ReleaseCard(
                    item, if (sort == ReleaseSort.Best) (if (i == 0) "Best match" else "#${i + 1}") else null, known[item.key], wontFit ?: failed[item.key] ?: serviceMarks[item.key],
                    onChooseFile = when {
                        download == null -> onChooseFile?.takeIf { item.offersFileChoice }?.let { choose -> { choose(item) } }
                        offer != null -> download.onChooseFile?.let { choose -> { choose(item) } }
                        else -> null
                    },
                    chooseFileNote = ReleaseListMode.Download.NEEDS_WIFI.takeIf { offer == ReleaseListMode.Download.FileChoiceOffer.NeedsWifi },
                    tag = sourceTag(item, accounts),
                    note = download?.packLine(item),
                    clickLabel = if (download == null) "Play" else "Download",
                    // One that won't fit is greyed in its place, as a hidden one is: nothing a tap could start.
                    onClick = when {
                        download == null -> play
                        wontFit != null -> null
                        else -> save
                    },
                    modifier = Modifier.firstFocus(item),
                )
            }
            if (group.hidden.isNotEmpty()) item {
                Column {
                    HiddenLine(hiddenLine(group.hidden, withReasons = accounts.isNotEmpty()), showHidden) { showHidden = !showHidden }
                    if (showHidden) group.hidden.forEach { ReleaseCard(it, "Hidden", known[it.key], failure = null, tag = sourceTag(it, accounts), onClick = null) }
                }
            }
            if (possible != null) {
                possibleMatches(possible, possibleSorted, only = group.size == 0, tv = tv, withReasons = accounts.isNotEmpty()) { item, hidden ->
                    // "Possible" in the rank's place and the plugin's reason under its name; tapped as the others.
                    ReleaseCard(
                        item, if (hidden) "Hidden" else PossibleMatches.RANK, known[item.key], if (hidden) null else failed[item.key] ?: serviceMarks[item.key],
                        tag = sourceTag(item, accounts), note = possible.reasons[item.key], onClick = if (hidden) null else ({ onPlay(item) }),
                        modifier = Modifier.firstFocus(item),
                    )
                }
            }
        }
    }
}

/**
 * A plugin's [possible] matches under the list's releases (L628): the heading and its line ([PossibleMatches.line], [only]
 * while nothing else is listed), its playable ones in the list's order ([sorted]), those that don't play here under a
 * hidden line of their own (its commonest reasons [withReasons], as the list's), then the plugin's left-out line, which
 * ends the list. [card] draws one, playable or hidden. Keyed apart from the releases above them.
 */
private fun LazyListScope.possibleMatches(
    possible: PossibleMatches,
    sorted: List<RankedItem>,
    only: Boolean,
    tv: Boolean,
    withReasons: Boolean,
    card: @Composable (RankedItem, hidden: Boolean) -> Unit,
) {
    val group = possible.group
    if (group.size > 0) {
        item(key = "possible:heading") {
            Column {
                // A heading for TalkBack's moves between headings, its words read with it.
                SectionHeader(PossibleMatches.HEADING, Modifier.semantics(mergeDescendants = true) { heading() }, top = if (only) 8.dp else 18.dp)
                Text(possible.line(only, tv), style = AppType.small, color = colors.muted, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp))
            }
        }
        items(sorted, key = { "possible:${it.key}" }) { card(it, false) }
        if (group.hidden.isNotEmpty()) item(key = "possible:hidden") {
            var shown by remember { mutableStateOf(false) }
            Column {
                HiddenLine(hiddenLine(group.hidden, withReasons = withReasons), shown) { shown = !shown }
                if (shown) group.hidden.forEach { card(it, true) }
            }
        }
    }
    possible.leftOut?.let { line ->
        item(key = "possible:left-out") { Text(line, style = AppType.secondary, color = colors.muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
    }
}

/**
 * A plugin's note over a search it opened (L629; SpPhoneSearchWarning): ⚠ and the note in the warning colour, read once
 * by TalkBack. Under Search's "Releases for …" line, and under a release list's sorts.
 */
@Composable
internal fun SearchNoteLine(note: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("⚠", style = AppType.secondary, color = colors.warn)
        Text(note, style = AppType.secondary, color = colors.warn, modifier = Modifier.weight(1f))
    }
}

/** How many frames a plugin's list on a TV waits for its first release to be laid out before focusing it. */
private const val FIRST_FOCUS_FRAMES = 10

/** "Ranked for Kitchen display" under the sorts (L127; CsPhoneRanked): Google's filled Cast icon, 18 dp in the accent. */
@Composable
private fun RankedForLine(name: String) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 6.dp).semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(CastConnectedIcon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
        Text(rankedForLine(name), style = AppType.secondary, color = colors.textSecondary)
    }
}

/** What a list ranked for a Cast device says of it (L127; CsPhoneRanked): "Ranked for Kitchen display". */
fun rankedForLine(name: String) = "Ranked for $name"

/**
 * A download-mode list's line under the sorts (L355), in [RankedForLine]'s place: the download icon, 18 dp in the accent,
 * by how it is ranked, then the space free and what a tap does, small, and on mobile data that it starts on Wi-Fi, in the
 * warning colour. TalkBack reads it once, before the releases.
 */
@Composable
private fun DownloadLine(lines: List<String>) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 6.dp).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(DownloadIcon, contentDescription = null, tint = colors.accent, modifier = Modifier.padding(top = 1.dp).size(18.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            lines.forEachIndexed { i, line ->
                when {
                    i == 0 -> Text(line, style = AppType.secondary, color = colors.textSecondary)
                    line == ReleaseListMode.Download.STARTS_ON_WIFI -> Text(line, style = AppType.small, color = colors.warn)
                    else -> Text(line, style = AppType.small, color = colors.muted)
                }
            }
        }
    }
}

/**
 * [known]: what is known of its being missing on the providers, which a muted chip says; [failure]: why it didn't start
 * when it was chosen here, in the play sheet's words, or why a play would skip a torrent its debrid service failed before
 * ("No longer cached"), or in download mode that it won't fit. [onChooseFile]: its menu's Choose file, opened from its ⋮
 * or by a long press (a remote's long OK), greyed with [chooseFileNote] under it where it can't open now. [tag]: where it
 * plays from, after its rank (L201; none while no debrid service is on). [note]: a line under its name (a season pack's
 * share of the episode, in download mode; a plugin's possible match's reason). [clickLabel]: what a tap does, as TalkBack
 * says it. With no [onClick] it is greyed, and not marked Best match's way even when it ranks first. [modifier]: before
 * its focus, a TV's first focus.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReleaseCard(
    item: RankedItem,
    rank: String?,
    known: KnownMissing?,
    failure: String?,
    modifier: Modifier = Modifier,
    onChooseFile: (() -> Unit)? = null,
    chooseFileNote: String? = null,
    tag: SourceTag? = null,
    note: String? = null,
    clickLabel: String = "Play",
    onClick: (() -> Unit)?,
) {
    val best = rank == "Best match" && onClick != null
    var menu by remember { mutableStateOf(false) }
    // On a TV an accent border is the focus's (its 3 dp ring): there the best is marked by its words, in a quiet border,
    // so it doesn't look focused while the focus is on another release.
    val bestBorder = if (LocalFormFactor.current == FormFactor.Tv) colors.outline else colors.accent
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth().clip(shape)
            .background(if (onClick == null) colors.surfaceDim else colors.surface)
            // The focus's ring over Best match's border, not under it.
            .then(if (onClick != null) Modifier.focusOutline(shape) else Modifier)
            .then(if (best) Modifier.border(2.dp, bestBorder, shape) else Modifier)
            .then(
                when {
                    // A hidden one plays nothing: read as one, its tag and reasons with its name.
                    onClick == null -> Modifier.semantics(mergeDescendants = true) {}
                    onChooseFile != null -> Modifier.combinedClickable(onClickLabel = clickLabel, onClick = onClick, onLongClick = { menu = true })
                    else -> Modifier.clickable(onClick = onClick)
                },
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val meta = listOfNotNull(formatSize(item.item.sizeBytes), age(item.item.postedAt), item.item.grabs?.let { "$it grabs" }).joinToString(" · ")
            val rankStyle = AppType.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            if (tag == null) {
                Text(rank.orEmpty(), style = rankStyle, color = if (best) colors.accent else colors.muted, modifier = Modifier.weight(1f))
                Text(meta, style = AppType.secondary, color = colors.muted)
            } else {
                // The tag after the rank, 8 dp on; the facts at the end, wrapping rather than pushing the tag out.
                rank?.let { Text(it, style = rankStyle, color = if (best) colors.accent else colors.muted, modifier = Modifier.padding(end = 8.dp)) }
                SourceTagChip(tag)
                Text(meta, style = AppType.secondary, color = colors.muted, textAlign = TextAlign.End, modifier = Modifier.weight(1f).padding(start = 8.dp))
            }
            if (onChooseFile != null) Box {
                // A remote's arrows don't reach a button inside the row: a long OK on the row opens the menu instead.
                IconButton(onClick = { menu = true }, modifier = Modifier.size(32.dp)) { Icon(Icons.Filled.MoreVert, "More for ${item.item.title}", tint = colors.muted) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = colors.surface) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text("Choose file", style = AppType.body)
                                chooseFileNote?.let { Text(it, style = AppType.small, color = colors.muted) }
                            }
                        },
                        onClick = { menu = false; onChooseFile() },
                        modifier = Modifier.semantics { role = Role.Button },
                        // Greyed as Material greys an item, its line read with it; TalkBack says it is dimmed.
                        enabled = chooseFileNote == null,
                        colors = if (chooseFileNote == null) MenuDefaults.itemColors() else MenuDefaults.itemColors(disabledTextColor = colors.text.copy(alpha = 0.38f)),
                    )
                }
            }
        }
        Text(item.item.title, style = AppType.release, color = if (onClick == null) colors.muted else colors.chipText)
        note?.let { Text(it, style = AppType.secondary, color = if (onClick == null) colors.muted else colors.textSecondary) }
        ReasonChips(item, bitrate = true)
        known?.let { InfoChip(missingChip(it, LocalDate.now(), ZoneId.systemDefault()), ChipKind.Muted) }
        failure?.let { Text("✕ $it", style = AppType.secondary, color = colors.warn) }
    }
}

/**
 * Whether the release's name says it holds several episodes (a season pack, several seasons, several episodes), whose row
 * offers Choose file: which of its videos plays is the user's choice ([io.skjaere.usenet.app.play.ChooseFile]).
 */
internal val RankedItem.offersFileChoice: Boolean get() = parsed.isSeasonPack || parsed.episodes.size > 1
