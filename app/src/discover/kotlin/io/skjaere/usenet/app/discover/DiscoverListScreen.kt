package io.skjaere.usenet.app.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.skjaere.usenet.app.collection.TRY_AGAIN
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.ui.LocalFormFactor
import io.skjaere.usenet.app.ui.TopBar
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.focusOutline
import io.skjaere.usenet.discover.DiscoverQuery
import io.skjaere.usenet.discover.Filter
import io.skjaere.usenet.metadata.MediaType
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * A list's See all page ([query], with the tab's genre): its filters as pills, what it shows in a line, its titles as
 * a grid of posters paged as the user nears the end, and under them how the paging stands. A card opens its title's
 * page ([onShow], [onMovie]); Back goes back to the tab ([onBack]). A phone's and a TV's ([TvDiscoverList]). [deps]
 * stands in for the app's in a test.
 */
@Composable
fun DiscoverListScreen(
    query: DiscoverQuery,
    onBack: () -> Unit,
    onShow: (Int) -> Unit,
    onMovie: (Int) -> Unit,
    onMetadata: () -> Unit,
    deps: (() -> DiscoverDeps)? = null,
) {
    val container = LocalContext.current.container
    val vm: DiscoverListViewModel = viewModel { DiscoverListViewModel(deps?.invoke() ?: DiscoverDeps.of(container), query, createSavedStateHandle()) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val open = { card: DiscoverCard -> if (card.title.type == MediaType.Tv) onShow(card.title.tmdbId) else onMovie(card.title.tmdbId) }
    // A TV's page has no back arrow: the remote's Back goes back.
    if (LocalFormFactor.current == FormFactor.Tv) TvDiscoverList(ui, open, vm::nearEnd, vm::retry, vm::pick, vm::clearFilters, onMetadata)
    else PhoneDiscoverList(ui, onBack, open, vm::nearEnd, vm::retry, vm::pick, vm::clearFilters, onMetadata)
}

/**
 * See all on a phone (the DcPhoneSeeAll mockup): the top bar with the Cast button (CsPhonePlaces), the pills, the line, a
 * grid three posters wide at 360 to 467 dp.
 */
@Composable
internal fun PhoneDiscoverList(
    ui: DiscoverListUi,
    onBack: () -> Unit,
    onOpen: (DiscoverCard) -> Unit,
    onNearEnd: () -> Unit,
    onRetry: () -> Unit,
    onPick: (Filter, String) -> Unit,
    onClear: () -> Unit,
    onMetadata: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TopBar(ui.title, onBack) { DiscoverCastButton() }
        when (ui.key) {
            null -> return@Column
            KeyState.Missing -> {
                PhoneStateCard(NO_KEY_TITLE, NO_KEY_LINE, ADD_KEY, primary = true, onMetadata)
                return@Column
            }
            KeyState.Refused -> {
                PhoneStateCard(REFUSED_TITLE, REFUSED_LINE, CHECK_KEY, primary = true, onMetadata)
                return@Column
            }
            KeyState.Ready -> Unit
        }
        val grid = rememberLazyGridState()
        val latest by rememberUpdatedState(ui)
        // Within a page of the end (scrolled or flung there), the next step; asked again as each lands while still there.
        LaunchedEffect(grid) {
            snapshotFlow {
                val count = latest.cards.size
                val last = grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.minus(HEADER_ITEMS)?.coerceAtMost(count - 1)
                count.takeIf { nearEnd(last, count) }
            }.distinctUntilChanged().collect { if (it != null) onNearEnd() }
        }
        LazyVerticalGrid(
            GridCells.Adaptive(MIN_CELL), Modifier.fillMaxSize(), state = grid,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "pills", span = { GridItemSpan(maxLineSpan) }) { PhonePills(ui.pills, onPick) }
            item(key = "summary", span = { GridItemSpan(maxLineSpan) }) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(ui.summary, style = AppType.secondary, color = colors.muted)
                    ui.offline?.let { Text(offlineLine(it), style = AppType.small, color = colors.muted) }
                    if (ui.justWatch) Text(JUSTWATCH, style = AppType.small, color = colors.muted)
                }
            }
            when (val view = ui.view) {
                ListView.Loading -> items(BLANKS, key = { "blank-$it" }) {
                    Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(colors.surface))
                }
                is ListView.Failed -> item(key = "failed", span = { GridItemSpan(maxLineSpan) }) { PhoneStateCard(view.message, null, TRY_AGAIN, primary = false, onRetry) }
                is ListView.Empty -> item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                    if (view.canClear) PhoneStateCard(NOTHING_MATCHES, null, CLEAR_FILTERS, primary = false, onClear)
                    else Text(NOTHING_MATCHES, style = AppType.secondary, color = colors.textSecondary)
                }
                ListView.Shown -> items(ui.cards, key = { it.key }) { card -> DiscoverPoster(card, ui.artwork, { onOpen(card) }) }
            }
            item(key = "footer", span = { GridItemSpan(maxLineSpan) }) { PhoneFooter(ui.footer, onRetry) }
        }
    }
}

/** The filters' pills, sideways: each says what is chosen, and opens its choices. */
@Composable
private fun PhonePills(pills: List<FilterPill>, onPick: (Filter, String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(pills, key = { it.filter.name }) { pill ->
            var open by remember { mutableStateOf(false) }
            Box {
                Box(
                    Modifier.minimumInteractiveComponentSize().focusOutline(RoundedCornerShape(16.dp), fill = false)
                        .clickable(role = Role.Button) { open = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "${pill.label} ▾", style = AppType.secondary, color = colors.text, maxLines = 1,
                        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(colors.chip).heightIn(min = 32.dp).padding(horizontal = 14.dp, vertical = 7.dp),
                    )
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = colors.surface) {
                    pill.options.forEach { option ->
                        DropdownMenuItem(
                            text = {
                                Text(option.label, style = if (option.selected) AppType.bodyStrong else AppType.body, color = if (option.selected) colors.accent else colors.text)
                            },
                            onClick = {
                                open = false
                                onPick(pill.filter, option.id)
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Under the posters: the next step coming, its failure with Try again, or the end at the cap. */
@Composable
private fun PhoneFooter(footer: Footer, onRetry: () -> Unit) {
    when (footer) {
        Footer.None -> Unit
        Footer.Loading -> Text(LOADING_MORE, style = AppType.secondary, color = colors.muted, modifier = Modifier.padding(vertical = 8.dp))
        is Footer.Failed -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(footer.message, style = AppType.secondary, color = colors.textSecondary, modifier = Modifier.weight(1f))
            TextButton(onClick = onRetry, modifier = Modifier.focusOutline(RoundedCornerShape(20.dp), fill = false)) {
                Text(TRY_AGAIN, style = AppType.bodyStrong, color = colors.accent)
            }
        }
        Footer.Capped -> Text(SHOWING_FIRST, style = AppType.secondary, color = colors.muted, modifier = Modifier.padding(vertical = 8.dp))
        Footer.Searched -> Text(SEARCHED_FIRST, style = AppType.secondary, color = colors.muted, modifier = Modifier.padding(vertical = 8.dp))
    }
}

/** The pills' line and the summary's, before the posters. */
private const val HEADER_ITEMS = 2

/**
 * Three posters to a line from 356 dp wide to 467 (101 dp at 360, the mockup's 111 at 390), so the phones most have, and
 * a larger display size, keep the mockup's grid; more on a wider screen.
 */
private val MIN_CELL = 100.dp
private const val BLANKS = 6
