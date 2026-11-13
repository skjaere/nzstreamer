package io.skjaere.usenet.app.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.skjaere.usenet.app.collection.rememberDpad
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.link.CastButton
import io.skjaere.usenet.app.link.PlayTarget
import io.skjaere.usenet.app.link.deviceName
import io.skjaere.usenet.app.link.goesToTv
import io.skjaere.usenet.app.link.greyed
import io.skjaere.usenet.app.link.onTvLine
import io.skjaere.usenet.app.search.PlayCircle
import io.skjaere.usenet.app.search.Poster
import io.skjaere.usenet.app.search.kindLabel
import io.skjaere.usenet.app.search.openedTitle
import io.skjaere.usenet.app.tv.TvMenu
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.GroupCard
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.ui.InsetDivider
import io.skjaere.usenet.app.ui.LocalFormFactor
import io.skjaere.usenet.app.ui.SecondaryButton
import io.skjaere.usenet.app.ui.SectionHeader
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.focusOutline
import io.skjaere.usenet.app.ui.rememberInitialFocus
import io.skjaere.usenet.app.ui.tryFocus
import io.skjaere.usenet.app.ui.tvFocusMemory
import io.skjaere.usenet.data.RecentSearch

/**
 * Home: the first-run screen until a provider and an indexer (or "Not now") are saved, then the
 * search field, recent searches, Up next and the collection (on a TV, [TvHomeContent]'s rows of
 * cards). All of it is the user's own history and collection.
 */
@Composable
fun HomeScreen(
    onSearch: () -> Unit,
    onRecent: (RecentSearch) -> Unit,
    onSettings: () -> Unit,
    onSetup: (SetupStep, done: Boolean) -> Unit,
    onShow: (Int) -> Unit,
    onCollection: () -> Unit,
    onImport: () -> Unit,
    onMovie: (Int) -> Unit = {},
    onPhoneSetup: (() -> Unit)? = null,
) {
    val container = LocalContext.current.container
    val tv = LocalFormFactor.current == FormFactor.Tv
    // A TV's row of posters scrolls sideways, so it shows more of the collection than a phone's rows.
    val vm: HomeViewModel = viewModel { HomeViewModel(container, if (tv) HomeCollection.TV_ROWS else HomeCollection.PHONE_ROWS) }
    val setup by vm.setup.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val artwork by vm.showArtwork.collectAsStateWithLifecycle()
    val offer by vm.undoOffer.collectAsStateWithLifecycle()
    val focusAfter by vm.focusAfter.collectAsStateWithLifecycle()
    val playOn by container.playOn.target.collectAsStateWithLifecycle()
    val edits = UpNextEdits(offer, focusAfter, vm::remove, vm::markWatched, vm::undo, vm::undoShown, vm::dismissUndo, vm::focusTaken)
    val s = setup ?: return
    if (!s.complete) {
        FirstRun(s, onStep = { row -> onSetup(row.step, row.done) }, onSkip = vm::skipIndexer, onImport = onImport, onPhone = onPhoneSetup)
    } else if (tv) {
        // Settings is a tab of the rail's on a TV: no gear here.
        TvHomeContent(
            state, artwork, onSearch = onSearch, onRecent = onRecent, onPlay = vm::play, onShow = onShow, onMovie = onMovie,
            onCollection = onCollection, onMetadata = { onSetup(SetupStep.Metadata, false) }, onImport = onImport, edits = edits,
        )
    } else {
        HomeContent(
            state, artwork, onSearch = onSearch, onSettings = onSettings, onRecent = onRecent, onPlay = vm::play,
            onShow = onShow, onCollection = onCollection, onMetadata = { onSetup(SetupStep.Metadata, false) }, onImport = onImport, onMovie = onMovie,
            edits = edits,
            // The Cast button on phones and tablets alone (L36, L115).
            playOn = playOn.takeUnless { container.television }, onPlayOn = container.playOn::openSheet,
        )
    }
}

/**
 * Home below the first run, [state] null until the library has answered. A TV remote's focus
 * starts on the search field; every row and button takes it in turn, outlined. Up next's rows
 * have a menu ([edits]), whose Remove and Mark watched offer Undo in a bar at Home's foot. [playOn]: where Play goes
 * (W11), the Cast button before Settings opening the Play on sheet ([onPlayOn]); null for none (a TV).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeContent(
    state: HomeState?,
    artwork: Boolean,
    onSearch: () -> Unit,
    onSettings: () -> Unit,
    onRecent: (RecentSearch) -> Unit,
    onPlay: (UpNextRow) -> Unit,
    onShow: (Int) -> Unit,
    onCollection: () -> Unit,
    onMetadata: () -> Unit,
    onImport: () -> Unit,
    onMovie: (Int) -> Unit = {},
    edits: UpNextEdits = UpNextEdits(),
    playOn: PlayTarget? = null,
    onPlayOn: () -> Unit = {},
) {
    val searchFocus = remember { FocusRequester() }
    // A touch screen has nothing focused: the request only takes with a D-pad.
    val initial = rememberInitialFocus()
    LaunchedEffect(Unit) { initial.request(searchFocus) }
    val dpad = rememberDpad()
    val undo = remember { UpNextUndoFocus() }
    val keys = state?.upNext?.map { it.key }.orEmpty()
    val tracked = undo.tracking(edits, keys)
    // The rows' playing parts, by key: where a remote's focus goes back to after an edit.
    val rowFocus = remember { mutableMapOf<String, FocusRequester>() }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize().tvFocusMemory()) {
            item(key = "search") {
                Column(Modifier.padding(top = 18.dp, bottom = 10.dp)) {
                    SearchBar(searchFocus, onSearch, onSettings, playOn, onPlayOn)
                    val recent = state?.recent.orEmpty()
                    if (recent.isNotEmpty()) {
                        FlowRow(
                            Modifier.padding(horizontal = 16.dp).padding(top = 12.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            recent.forEach { r -> RecentChip(r) { onRecent(r) } }
                        }
                    }
                }
            }
            if (state == null) return@LazyColumn
            val collection = state.collection
            if (state.upNext.isNotEmpty()) {
                item(key = "up-next-header") { SectionHeader("Up next", Modifier.padding(top = 0.dp)) }
                item(key = "up-next") {
                    GroupCard {
                        state.upNext.forEachIndexed { i, row ->
                            // Keyed, so a remote's focus moves with its row as the rows reorder after a play.
                            key(row.key) {
                                if (i > 0) InsetDivider()
                                val focus = rowFocus.getOrPut(row.key) { FocusRequester() }
                                UpNextRowView(row, artwork, primary = i == 0, tracked, Modifier.focusRequester(focus), playOn) { onPlay(row) }
                            }
                        }
                    }
                }
            } else if (collection == null) {
                item(key = "empty") {
                    Text(
                        "Nothing to resume yet. Search your indexers to find something to watch.",
                        style = AppType.secondary, color = colors.textSecondary, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    )
                }
            }
            if (!state.hasTmdbKey) {
                item(key = "tmdb-hint") {
                    Row(
                        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().heightIn(min = 48.dp).focusOutline()
                            .clickable(role = Role.Button, onClick = onMetadata).padding(horizontal = 4.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Add a TMDB key to keep a collection", style = AppType.secondary, color = colors.accent, modifier = Modifier.weight(1f))
                        Text("›", style = AppType.body, color = colors.accent)
                    }
                }
            }
            if (collection != null) {
                item(key = "collection-header") {
                    SectionHeader(collection.header, Modifier.padding(top = 20.dp)) {
                        TextButton(onClick = onCollection, modifier = Modifier.focusOutline(RoundedCornerShape(20.dp), fill = false)) {
                            Text("See all", style = AppType.bodyStrong.copy(fontSize = 14.sp), color = colors.accent)
                        }
                    }
                }
                item(key = "collection") {
                    GroupCard {
                        collection.rows.forEachIndexed { i, row ->
                            if (i > 0) InsetDivider()
                            CollectionRow(row) { if (row.key.isShow) onShow(row.key.tmdbId) else onMovie(row.key.tmdbId) }
                        }
                    }
                }
            }
            item(key = "import") {
                SecondaryButton(
                    "Open a .nzb file", onImport,
                    Modifier.padding(horizontal = 16.dp, vertical = 24.dp).fillMaxWidth().focusOutline(RoundedCornerShape(24.dp), fill = false),
                )
            }
        }
        // Over the list, above the tab bar. As it goes, a remote's focus goes back to Up next's rows, else to the field.
        UpNextUndo(
            edits, undo, keys, dpad,
            handTo = { key -> if (key == null || rowFocus[key]?.tryFocus() != true) searchFocus.tryFocus() },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * Looks like the Search tab's field; tapping it (or OK on a remote) opens that tab, its field
 * focused for typing. The field and the Settings button are side by side, not one inside the
 * other, so a remote moves between them. Before Settings, the Cast button, round ([playOn], CsPhoneButton).
 */
@Composable
private fun SearchBar(focus: FocusRequester, onSearch: () -> Unit, onSettings: () -> Unit, playOn: PlayTarget?, onPlayOn: () -> Unit) {
    val shape = RoundedCornerShape(26.dp)
    Row(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().heightIn(min = 52.dp).background(colors.surface, shape).padding(end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f).heightIn(min = 52.dp)
                .focusRequester(focus)
                .focusOutline(shape, fill = false)
                .clickable(role = Role.Button, onClickLabel = "Search", onClick = onSearch)
                .padding(start = 16.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = colors.muted)
            Text("Search your indexers", style = AppType.body.copy(fontSize = 16.sp), color = colors.muted, modifier = Modifier.padding(vertical = 10.dp))
        }
        playOn?.let { CastButton(it, onPlayOn, Modifier.padding(end = 6.dp), round = true) }
        IconButton(
            onClick = onSettings, modifier = Modifier.size(40.dp).focusOutline(CircleShape, color = colors.text, fill = false),
            colors = IconButtonDefaults.iconButtonColors(containerColor = colors.chip, contentColor = colors.text),
        ) { Icon(Icons.Filled.Settings, contentDescription = "Settings", modifier = Modifier.size(20.dp)) }
    }
}

/**
 * A recent search, as what it was, so what it does again: a title opened says whether it's a show or a movie (its page
 * opens again), a search of the indexers has the search sign (it searches again).
 */
@Composable
private fun RecentChip(recent: RecentSearch, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    val title = recent.openedTitle
    Row(
        Modifier.heightIn(min = 32.dp).focusOutline(shape).border(1.dp, colors.chip, shape)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (title == null) Icon(Icons.Filled.Search, contentDescription = recent.kindLabel, tint = colors.muted, modifier = Modifier.size(14.dp))
        Text(recent.query, style = AppType.secondary, color = colors.textSecondary)
        if (title != null) Text(recent.kindLabel, style = AppType.secondary, color = colors.muted)
    }
}

/**
 * The row plays when tapped, as its Play button does; they sit side by side so a remote reaches both, and its ⋮ after them
 * (L21): Remove from Up next, and Mark watched for an episode or film in progress ([edits]). A long press on the row opens
 * the same menu, at the ⋮; TalkBack has its items as the row's actions. [modifier] goes on the row's playing part. With a
 * TV chosen ([playOn]) Play says so, and greys while that TV's NZStreamer isn't open (PtPhoneWhere B).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun UpNextRowView(row: UpNextRow, artwork: Boolean, primary: Boolean, edits: UpNextEdits, modifier: Modifier, playOn: PlayTarget?, onPlay: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val items = edits.items(row)
    Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier.weight(1f).focusOutline().combinedClickable(onClick = onPlay, onLongClick = { menu = true })
                .semantics { customActions = upNextActions(items) }.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Poster(row.posterPath, artwork, 44, 66)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(row.title, style = AppType.bodyStrong, color = colors.text)
                Text(row.line, style = AppType.secondary, color = colors.muted)
                row.progress?.let { p ->
                    LinearProgressIndicator(
                        progress = { p }, modifier = Modifier.padding(top = 4.dp).fillMaxWidth().height(4.dp),
                        color = colors.accent, trackColor = colors.chip, drawStopIndicator = {}, gapSize = 0.dp,
                    )
                }
                if (row.new) Text(
                    "NEW", style = AppType.tag, color = colors.accent,
                    modifier = Modifier.padding(top = 2.dp).background(colors.accentTint, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
        }
        val there = row.playsOn(playOn)
        PlayCircle(
            primary, there?.deviceName?.let { "${row.playLabel} ${onTvLine(it)}" } ?: row.playLabel, onPlay,
            Modifier.focusOutline(CircleShape, color = if (primary) colors.text else colors.accent, fill = false),
            enabled = there?.greyed != true,
        )
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.focusOutline(CircleShape, fill = false)) {
                Icon(Icons.Filled.MoreVert, "More for ${row.title}", tint = colors.muted)
            }
            TvMenu(menu, onDismiss = { menu = false }) { first -> UpNextMenuItems(items, close = { menu = false }, modifier = first) }
        }
    }
}

/**
 * The device this row's Play goes to, with [target] chosen in Play on; null where it plays on this phone. A TV gets only
 * what it can search for, so a release played without TMDB plays here with a TV chosen (L6, [goesToTv]): its button says
 * so, and never greys for the TV. A Cast device plays every row, that release too: the phone has it (L80).
 */
internal fun UpNextRow.playsOn(target: PlayTarget?): PlayTarget? = when (target) {
    null, PlayTarget.Phone -> null
    is PlayTarget.Tv -> target.takeIf { play.goesToTv }
    is PlayTarget.Cast -> target
}

@Composable
private fun CollectionRow(row: HomeCollectionRow, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).focusOutline().clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(row.title, style = AppType.body, color = colors.text, modifier = Modifier.weight(1f))
        Text(row.status, style = AppType.secondary, color = colors.muted, modifier = Modifier.weight(1f, fill = false))
    }
}
