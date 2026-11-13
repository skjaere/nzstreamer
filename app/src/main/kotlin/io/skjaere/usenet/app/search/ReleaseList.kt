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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
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
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.ui.HiddenLine
import io.skjaere.usenet.app.ui.InfoChip
import io.skjaere.usenet.app.ui.LocalFormFactor
import io.skjaere.usenet.app.ui.TopBar
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.focusOutline
import io.skjaere.usenet.core.formatSize
import io.skjaere.usenet.data.KnownMissing
import io.skjaere.usenet.search.RankedItem
import io.skjaere.usenet.search.ResultGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.flow.catch
import java.time.LocalDate
import java.time.ZoneId

private enum class Sort(val label: String) { Best("Best match"), Size("Size"), Age("Age") }

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
    onPlay: (RankedItem) -> Unit,
) {
    // Saved with the list's place, which belongs to the order it was read in (L322). Both under the group's key: a page's
    // saved state outlives a list the page doesn't save (a chooser's, once the process has gone), and another group's list
    // shown there later must open as any list does, not in this one's order and at its place (RFO-1). Search, whose picked
    // titles' groups are keyed alike, puts its list under the title's key too ([ForPickedTitle]).
    val (order, rows) = key(group.key) { rememberSaveable { mutableStateOf(Sort.Best) } to rememberLazyListState() }
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
    val known by remember(group) {
        missing.known((group.playable + group.hidden).map { it.key })
            // Unread, nothing is marked: each is tried as a play tries it.
            .catch { emit(emptyMap()) }
    }.collectAsState(emptyMap(), context = RELEASE_LIST_MARKS)
    // A torrent a play would skip, as every account it plays from failed it before, is marked as the failure says (L174):
    // "✕ No longer cached". Read as the list shows; one this list's own play fails is marked by [failed].
    val serviceMarks by produceState(emptyMap<String, String>(), group, accounts) {
        value = try {
            container.serviceMisses.marks(group.playable.filter { it.item.infoHash != null }.map { it.playCandidate })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Unread, nothing is marked: each is tried as a play tries it.
            emptyMap()
        }
    }
    val indexers = (group.playable + group.hidden).map { it.item.indexerId }.distinct().size
    val sorted = when (sort) {
        Sort.Best -> group.playable
        Sort.Size -> group.playable.sortedByDescending { it.item.sizeBytes }
        Sort.Age -> group.playable.sortedByDescending { it.item.postedAt }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar(group.label, onBack, subtitle = "$title · ${group.size} release${if (group.size == 1) "" else "s"} from $indexers indexer${if (indexers == 1) "" else "s"}") {
            // A release played from a list plays here with a TV chosen (W11): the list greys the TVs, the face says this phone (W59).
            CastButton(playOn, { container.playOn.openSheet(SheetPlace.PhoneOnly) }, place = SheetPlace.PhoneOnly)
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Sort.entries.forEach { s ->
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
        rankedFor?.let { RankedForLine(it) }
        LazyColumn(listModifier.fillMaxSize(), state = rows, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
            itemsIndexed(sorted, key = { _, it -> it.key }) { i, item ->
                ReleaseCard(
                    item, if (sort == Sort.Best) (if (i == 0) "Best match" else "#${i + 1}") else null, known[item.key], failed[item.key] ?: serviceMarks[item.key],
                    onChooseFile = onChooseFile?.takeIf { item.offersFileChoice }?.let { choose -> { choose(item) } },
                    tag = sourceTag(item, accounts),
                ) { onPlay(item) }
            }
            if (group.hidden.isNotEmpty()) item {
                Column {
                    HiddenLine(hiddenLine(group.hidden, withReasons = accounts.isNotEmpty()), showHidden) { showHidden = !showHidden }
                    if (showHidden) group.hidden.forEach { ReleaseCard(it, "Hidden", known[it.key], failure = null, tag = sourceTag(it, accounts), onClick = null) }
                }
            }
        }
    }
}

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
 * [known]: what is known of its being missing on the providers, which a muted chip says; [failure]: why it didn't start
 * when it was chosen here, in the play sheet's words, or why a play would skip a torrent its debrid service failed before
 * ("No longer cached"). [onChooseFile]: its menu's Choose file, opened from its ⋮ or by a
 * long press (a remote's long OK). [tag]: where it plays from, after its rank (L201; none while no debrid service is on).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReleaseCard(
    item: RankedItem,
    rank: String?,
    known: KnownMissing?,
    failure: String?,
    onChooseFile: (() -> Unit)? = null,
    tag: SourceTag? = null,
    onClick: (() -> Unit)?,
) {
    val best = rank == "Best match"
    var menu by remember { mutableStateOf(false) }
    // On a TV an accent border is the focus's (its 3 dp ring): there the best is marked by its words, in a quiet border,
    // so it doesn't look focused while the focus is on another release.
    val bestBorder = if (LocalFormFactor.current == FormFactor.Tv) colors.outline else colors.accent
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 5.dp).fillMaxWidth().clip(shape)
            .background(if (onClick == null) colors.surfaceDim else colors.surface)
            // The focus's ring over Best match's border, not under it.
            .then(if (onClick != null) Modifier.focusOutline(shape) else Modifier)
            .then(if (best) Modifier.border(2.dp, bestBorder, shape) else Modifier)
            .then(
                when {
                    // A hidden one plays nothing: read as one, its tag and reasons with its name.
                    onClick == null -> Modifier.semantics(mergeDescendants = true) {}
                    onChooseFile != null -> Modifier.combinedClickable(onClickLabel = "Play", onClick = onClick, onLongClick = { menu = true })
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
                    DropdownMenuItem(text = { Text("Choose file", style = AppType.body) }, onClick = { menu = false; onChooseFile() })
                }
            }
        }
        Text(item.item.title, style = AppType.release, color = if (onClick == null) colors.muted else colors.chipText)
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
