package io.skjaere.usenet.app.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.skjaere.usenet.app.collection.TRY_AGAIN
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.link.CastButton
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.ui.LocalFormFactor
import io.skjaere.usenet.app.ui.Footnote
import io.skjaere.usenet.app.ui.SecondaryButton
import io.skjaere.usenet.app.ui.SectionHeader
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.focusOutline
import io.skjaere.usenet.discover.DiscoverFilters
import io.skjaere.usenet.discover.DiscoverList
import io.skjaere.usenet.discover.DiscoverQuery
import io.skjaere.usenet.metadata.MediaType
import io.skjaere.usenet.metadata.TMDB_ATTRIBUTION

/**
 * The Discover tab: TMDB's lists as rows, a side at a time (Shows | Movies), the side's genres as chips that filter
 * every row. A card opens its title's page ([onShow], [onMovie]), where Play searches the indexers; a row's See all
 * opens its whole list ([onList]) with the genre picked. Without a TMDB key, or with one TMDB refused, a card that says
 * so, its button to Settings › Metadata ([onMetadata]). A phone's and a TV's ([TvDiscover]). [deps] stands in for the
 * app's in a test.
 */
@Composable
fun DiscoverScreen(
    onShow: (Int) -> Unit,
    onMovie: (Int) -> Unit,
    onList: (DiscoverQuery) -> Unit,
    onMetadata: () -> Unit,
    deps: (() -> DiscoverDeps)? = null,
) {
    val container = LocalContext.current.container
    val vm: DiscoverViewModel = viewModel { DiscoverViewModel(deps?.invoke() ?: DiscoverDeps.of(container), createSavedStateHandle()) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val open = { card: DiscoverCard -> if (card.title.type == MediaType.Tv) onShow(card.title.tmdbId) else onMovie(card.title.tmdbId) }
    val list = { of: DiscoverList -> onList(DiscoverQuery(ui.side, of, DiscoverFilters(genre = ui.genre).on(ui.side, of))) }
    val retry = { row: DiscoverList? -> if (row == null) vm.retry() else vm.retry(row) }
    if (LocalFormFactor.current == FormFactor.Tv) TvDiscover(ui, vm::switchSide, vm::pickGenre, retry, open, list, onMetadata)
    else PhoneDiscover(ui, vm::switchSide, vm::pickGenre, retry, open, list, onMetadata)
}

/**
 * The tab on a phone (the DcPhone mockup): the top line with the Cast button (CsPhonePlaces), the genre chips, the rows,
 * the footnote.
 */
@Composable
internal fun PhoneDiscover(
    ui: DiscoverUi,
    onSide: (MediaType) -> Unit,
    onGenre: (Int?) -> Unit,
    onRetry: (DiscoverList?) -> Unit,
    onOpen: (DiscoverCard) -> Unit,
    onList: (DiscoverList) -> Unit,
    onMetadata: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
        item(key = "top") {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 18.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(DISCOVER, style = AppType.screenTitle, color = colors.text, modifier = Modifier.weight(1f))
                if (ui.key == KeyState.Ready) PhoneSideSwitch(ui.side, onSide)
                DiscoverCastButton()
            }
        }
        when (ui.key) {
            // Not known yet: nothing, rather than the no-key card for a moment.
            null -> return@LazyColumn
            KeyState.Missing -> {
                item(key = "no-key") { PhoneStateCard(NO_KEY_TITLE, NO_KEY_LINE, ADD_KEY, primary = true, onMetadata) }
                return@LazyColumn
            }
            KeyState.Refused -> {
                item(key = "refused") { PhoneStateCard(REFUSED_TITLE, REFUSED_LINE, CHECK_KEY, primary = true, onMetadata) }
                return@LazyColumn
            }
            KeyState.Ready -> Unit
        }
        item(key = "genres") { PhoneGenreChips(ui.genres, ui.genre, onGenre) }
        ui.offline?.let { age -> item(key = "offline") { OfflineLine(age) } }
        when {
            ui.allFailed -> item(key = "failed") { PhoneStateCard(ui.failure.orEmpty(), null, TRY_AGAIN, primary = false) { onRetry(null) } }
            ui.nothingMatches -> item(key = "nothing") { PhoneStateCard(NOTHING_MATCHES, null, CLEAR_FILTERS, primary = false) { onGenre(null) } }
            else -> items(ui.shownRows, key = { it.list.name }) { row -> PhoneRow(row, ui.artwork, onOpen, { onList(row.list) }, { onRetry(row.list) }) }
        }
        item(key = "footnote") { Footnote("$FOOTNOTE $TMDB_ATTRIBUTION", Modifier.padding(top = 8.dp)) }
    }
}

/** The Cast button (L115): where Play goes from a title found here, which plays from its own page, so a title page's place. */
@Composable
internal fun DiscoverCastButton() {
    val container = LocalContext.current.container
    val playOn by container.playOn.target.collectAsStateWithLifecycle()
    CastButton(playOn, { container.playOn.openSheet() })
}

/** A list's row: its header with See all, then its posters sideways; blank outlines while TMDB is asked; why not, with Try again. */
@Composable
private fun PhoneRow(row: DiscoverRow, artwork: Boolean, onOpen: (DiscoverCard) -> Unit, onList: () -> Unit, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        SectionHeader(row.heading) {
            if (row.view is RowView.Cards) {
                TextButton(onClick = onList, modifier = Modifier.focusOutline(RoundedCornerShape(20.dp), fill = false).semantics { contentDescription = seeAllSaid(row.heading) }) {
                    Text(SEE_ALL, style = AppType.bodyStrong.copy(fontSize = 14.sp), color = colors.accent)
                }
            }
        }
        when (val view = row.view) {
            RowView.Loading -> Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(ROW_GAP)) {
                repeat(BLANKS) { Box(Modifier.size(ROW_POSTER, ROW_POSTER * 1.5f).clip(RoundedCornerShape(8.dp)).background(colors.surface)) }
            }
            is RowView.Cards -> LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(ROW_GAP)) {
                items(view.cards, key = { it.key }) { card -> DiscoverPoster(card, artwork, { onOpen(card) }, Modifier.width(ROW_POSTER)) }
            }
            is RowView.Failed -> Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(view.message, style = AppType.secondary, color = colors.textSecondary)
                SecondaryButton(TRY_AGAIN, onRetry)
            }
        }
    }
}

/** The mockup's 104 dp posters, four blank ones while a row loads. */
private val ROW_POSTER = 104.dp
private val ROW_GAP = 12.dp
private const val BLANKS = 4
