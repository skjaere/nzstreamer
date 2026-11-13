package io.skjaere.usenet.app.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PlatformImeOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.skjaere.usenet.app.CollectionJump
import io.skjaere.usenet.app.collection.model.TitleKey
import io.skjaere.usenet.app.collection.rememberDpad
import io.skjaere.usenet.app.collection.runCatchingFocus
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.link.CastButton
import io.skjaere.usenet.app.link.SheetPlace
import io.skjaere.usenet.app.play.PlayOutcome
import io.skjaere.usenet.app.play.PlayRequest
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.app.tv.LocalRailFocus
import io.skjaere.usenet.app.tv.tvFullWidth
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.FieldGate
import io.skjaere.usenet.app.ui.Footnote
import io.skjaere.usenet.app.ui.GroupCard
import io.skjaere.usenet.app.ui.HiddenLine
import io.skjaere.usenet.app.ui.InsetDivider
import io.skjaere.usenet.app.ui.PrimaryButton
import io.skjaere.usenet.app.ui.SecondaryButton
import io.skjaere.usenet.app.ui.SectionHeader
import io.skjaere.usenet.app.ui.StatusPill
import io.skjaere.usenet.app.ui.TvFieldGate
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.focusOutline
import io.skjaere.usenet.app.ui.rememberFieldGate
import io.skjaere.usenet.app.ui.rememberInitialFocus
import io.skjaere.usenet.app.ui.tryFocus
import io.skjaere.usenet.core.FilePicker
import io.skjaere.usenet.data.RecentSearch
import io.skjaere.usenet.data.TitleType
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchTitle
import io.skjaere.usenet.metadata.MediaType
import io.skjaere.usenet.metadata.TMDB_ATTRIBUTION
import io.skjaere.usenet.metadata.TitleSuggestion
import io.skjaere.usenet.search.GroupKind
import io.skjaere.usenet.search.RankedItem
import io.skjaere.usenet.search.ResultGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Search tab, running [initial] as it opens (else its field takes the focus). [onPlay] starts the
 * play pipeline with a group's ranked releases (best first), or with one release the user chose,
 * and says what came of it: a release list marks a release chosen on it that failed to start.
 * "+ Collection" goes to the title in the collection once it is stored ([onJumpToCollection]), as
 * "In collection ›" does for one there already. A phone shows it in one column, the field on top; a
 * TV in two panes ([TvSearchContent]), the same state and the same lists in them, and a mic beside the field where a
 * speech recognizer resolves; [voiceRequest] is the remote's search key asking for it.
 */
@Composable
fun SearchScreen(
    initial: SearchRequest?,
    onIndexers: () -> Unit,
    onPlay: (PlayRequest) -> Deferred<PlayOutcome>,
    onOpenShow: (Int) -> Unit = {},
    onOpenMovie: (Int) -> Unit = {},
    onJumpToCollection: (CollectionJump) -> Unit = {},
    voiceRequest: VoiceRequest? = null,
) {
    val container = LocalContext.current.container
    val vm: SearchViewModel = viewModel { SearchViewModel(SearchDeps.of(container), initial) }
    val query by vm.query.collectAsStateWithLifecycle()
    val editing by vm.editing.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val fallback by vm.fallback.collectAsStateWithLifecycle()
    val strip by vm.strip.collectAsStateWithLifecycle()
    val recent by vm.recent.collectAsStateWithLifecycle()
    val hasKey by vm.hasTmdbKey.collectAsStateWithLifecycle()
    val hasIndexers by vm.hasIndexers.collectAsStateWithLifecycle()
    val showArtwork by vm.showArtwork.collectAsStateWithLifecycle()
    val collected by vm.collected.collectAsStateWithLifecycle()
    val adding by vm.adding.collectAsStateWithLifecycle()
    val bannerDismissed by vm.noKeyBannerDismissed.collectAsStateWithLifecycle()
    val chosenFailed by vm.chosenFailed.collectAsStateWithLifecycle()
    val playOn by container.playOn.target.collectAsStateWithLifecycle()
    // The service accounts on: a row's and a movie card's source tag show only while one is (L201).
    val accounts by remember(container) { container.serviceNames }.collectAsStateWithLifecycle(emptyList())
    // L247: a row's or the movie card's play isn't chosen, so it passes over an account remembered as having failed its
    // torrent; the tag passes over it too. Read as the results show, and again as plays remember a failure or forget one:
    // a row's play fails in the player, Search under it (COT-1).
    val missesChanged by container.serviceMisses.changes.collectAsStateWithLifecycle()
    val passedOver by produceState(emptyMap<String, Set<String>>(), results, fallback, accounts, missesChanged) {
        value = try {
            if (accounts.isEmpty()) emptyMap() else passedOverShown(listOfNotNull(results, fallback).map { it.grouped }, container.serviceMisses)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Unread, nothing is passed over: each tag names what a chosen play would try.
            emptyMap()
        }
    }
    var openGroup by rememberSaveable { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    // Leaving the field (search, pick, recent) closes the keyboard too.
    val focus = object { fun clearFocus() { focusManager.clearFocus(); keyboard?.hide() } }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val dpad by rememberUpdatedState(rememberDpad())
    val rail = LocalRailFocus.current
    val tv = rail != null
    val gate = rememberFieldGate()
    // A remote's focus, back on Search's results: on what left them. Back from the collection, on the title
    // whose "+ Collection" or "In collection ›" went there; from a title's page, on its Open; from a release
    // list, on the group (or the movie's releases button) that opened it. Else, dropped with what had it,
    // it would land on the field, whose keyboard opens and turns Search to suggestions, or on the first row.
    var returnTo by rememberSaveable { mutableStateOf<String?>(null) }
    // Search left for a page or list it opened (a title, a recent title, the collection): Back finds it as it was, the
    // field not taken again with its keyboard. Until then (a tab switch back to it, the activity recreated) the field
    // takes the focus as Search shows, as it opens empty to type in.
    var cameBack by rememberSaveable { mutableStateOf(false) }
    val actionFocus = remember { HashMap<String, FocusRequester>() }
    fun focusOf(key: String): FocusRequester = actionFocus.getOrPut(key) { FocusRequester() }
    fun collectionFocus(key: TitleKey): FocusRequester = focusOf(key.value)
    fun leaving(key: String) {
        cameBack = true
        if (dpad) returnTo = key
    }
    fun jumpToCollection(jump: CollectionJump) {
        leaving(jump.key)
        onJumpToCollection(jump)
    }
    // A title from TMDB opens its page, Show or Movie, whose Play searches the indexers by the title's ids; Back from
    // there comes back to the element that opened it ([focusKey]).
    fun openTitle(s: TitleSuggestion, focusKey: String) {
        keyboard?.hide()
        leaving(focusKey)
        vm.openTitle(s)
        if (s.type == MediaType.Tv) onOpenShow(s.tmdbId) else onOpenMovie(s.tmdbId)
    }
    // A focus request Search makes on purpose (the field, back to what opened a page, where it was in the left pane):
    // not taken over by the left pane's way in below, which a focus request into that pane goes through too.
    val onPurpose = remember { BooleanArray(1) }
    fun <T> onPurpose(request: () -> T): T {
        onPurpose[0] = true
        try {
            return request()
        } finally {
            onPurpose[0] = false
        }
    }
    fun leftOnPurpose(requester: FocusRequester?): Boolean = onPurpose { requester?.tryFocus() == true }
    fun focusField(): Boolean = leftOnPurpose(gate.requester)
    // As Search shows again (Back from a page) and as its release list closes. Not while the user holds the rail
    // (Back pressed there closes the list too): once they come back into the content.
    val initialFocus = rememberInitialFocus()
    LaunchedEffect(openGroup == null) {
        if (openGroup != null) return@LaunchedEffect
        val key = returnTo ?: return@LaunchedEffect
        initialFocus.awaitTurn()
        returnTo = null
        withFrameNanos {}
        onPurpose { runCatchingFocus { actionFocus[key]?.requestFocus() } }
    }
    // The field takes the focus as Search opens empty to type in, and again after a tab switch.
    val autoFocus = initial == null && results == null && !cameBack
    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            initialFocus.awaitTurn()
            focusField()
        }
    }
    // On a TV the results keep their place while a release list covers them, so the group that opened it is
    // there again on Back, for the focus. A phone's results start at the top again, as before.
    val tvResults = rememberLazyListState()
    // (A TV's results have no focus memory of their own, unlike the shared screens' lists: the rail is reached only
    // from the left pane, Right enters at the first row, and a remembered row would take over the focus asked for.)

    /**
     * Jumps to the title once [add] has stored it. Search left meanwhile cancels the scope: the write
     * still completes (the app's scope), nothing navigates later.
     */
    fun addAndJump(name: String, add: () -> Deferred<CollectionJump>) {
        scope.launch {
            val jump = try {
                add().await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbar.showSnackbar("Couldn't add $name to the collection")
                return@launch
            }
            jumpToCollection(jump)
        }
    }

    val r = results
    val group = openGroup?.let { key -> r?.grouped?.group(key) }
    fun closeReleases() {
        openGroup = null
        vm.clearMarks()
    }
    // On a TV the release list sits beside the field: the field opened, or words typed or said, start another search:
    // the list goes and what the words find shows instead. The focus stays where the user is, not sent back to the
    // group that opened the list.
    fun closeReleasesForWords() {
        if (openGroup == null) return
        returnTo = null
        closeReleases()
    }
    // Whether a TV's left pane (the field, the recent searches) has the focus.
    var inputFocused by remember { mutableStateOf(false) }
    // A group's releases, played one by one: over the whole screen on a phone, in the right pane on a TV.
    val releaseList: (@Composable (Modifier) -> Unit)? = if (group != null && r != null) {
        { listModifier ->
            // Its Back closes it, from the list or the rail; on a TV not from the left pane, whose Back goes to the rail,
            // the list staying beside it.
            BackHandler(enabled = !inputFocused) { closeReleases() }
            ForPickedTitle(r.title) {
                ReleaseList(
                    group, r.title?.name ?: r.grouped.sections.firstOrNull { group in it.groups }?.title.orEmpty(), onBack = ::closeReleases,
                    failed = chosenFailed, listModifier = listModifier, rankedFor = r.rankedFor,
                ) { item ->
                    vm.playChosen(item) { onPlay(chosenRequest(r, group, item)) }
                }
            }
        }
    } else {
        null
    }
    if (!tv && releaseList != null) {
        releaseList(Modifier)
        return
    }

    // On a TV the way in from the rail (OK on Search, or Right with nothing to go back to) lands where the focus was
    // in the left pane, else on the field's gate: while results show (or a release list) it goes to them instead, as
    // the right pane is what the user was looking at. A picked title's card takes it on its Open (a movie's too, not
    // its Play: the same place on either card): Compose's way in would pick the wider "+ Collection", which
    // writes and leaves Search. The shell's own way in (the focus gone with what had it: a search sent, results
    // arriving) goes to the right pane's first element, else to the gate, which opens no keyboard.
    val showingResults = r != null && !editing
    val list = remember { FocusRequester() }
    val releases = remember { FocusRequester() }
    val hadFocus = remember { BooleanArray(1) }
    // The right pane's first row: the first title, a picked title's Open, the first playable group,
    // else the first that takes the focus in its list (the state's primary button, or the words holding the focus).
    val firstRows = when {
        releaseList != null -> emptyList()
        editing && query.isNotBlank() -> listOfNotNull(fallback?.firstPlayable()?.playKey, suggestions.firstOrNull()?.focusKey)
        showingResults -> listOfNotNull(strip.firstOrNull()?.takeIf { r.byWords }?.stripKey, r.title?.openKey, r.firstPlayable()?.playKey)
        else -> emptyList()
    }
    fun enterResults(): Boolean =
        if (releaseList != null) releases.tryFocus() else firstRows.any { actionFocus[it]?.tryFocus() == true } || list.tryFocus()
    // Where the focus was last in the left pane, for Left back into it from the right pane.
    val leftLast = remember { arrayOfNulls<FocusRequester>(1) }
    fun Modifier.leftMemory(requester: FocusRequester) = onFocusChanged { if (it.hasFocus) leftLast[0] = requester }
    val wayIn = if (rail != null) {
        Modifier.focusProperties {
            onEnter = {
                when (requestedFocusDirection) {
                    FocusDirection.Enter -> when {
                        // Only the shell's way in (from the rail, or settling the focus): a request for an element here
                        // gets that element.
                        onPurpose[0] || !(rail.hasFocus || rail.settling) -> Unit
                        (showingResults || releaseList != null || (rail.settling && hadFocus[0])) && enterResults() -> Unit
                        // Where the focus was in this pane, else the field's gate: not the mic left of it, the first element.
                        else -> leftOnPurpose(leftLast[0]) || focusField()
                    }
                    FocusDirection.Left -> leftOnPurpose(leftLast[0])
                    else -> Unit
                }
            }
        }.focusGroup()
    } else {
        Modifier
    }
    // Words said into a TV's mic (a phone's keyboard has its own): in the field, then TMDB's titles for them on the right
    // or the indexers searched for them, and the focus on the first of what came (the results' words hold it until then).
    val enterNow by rememberUpdatedState(::enterResults)
    val voice = if (tv) {
        rememberVoiceSearch { words ->
            scope.launch {
                closeReleasesForWords()
                vm.spoken(words)
                withFrameNanos {}
                enterNow()
            }
        }
    } else {
        null
    }
    // The remote's search key asked for voice: once, as Search shows, where a recognizer resolves. Only by the Search that
    // is the screen now (resumed): the key pressed while Search shows opens a new one, and the one it replaces is still
    // composed while it fades out, its recognizer's answer going with it.
    var askVoice by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(voiceRequest?.pending) {
        val request = voiceRequest?.takeIf { it.pending } ?: return@LaunchedEffect
        lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
        if (request.consume()) askVoice = true
    }
    LaunchedEffect(askVoice, voice != null) {
        if (askVoice && voice != null) {
            askVoice = false
            voice()
        }
    }
    val mic = remember { FocusRequester() }
    // Right from the left pane: the right pane's first row, wherever the focus left from.
    val resultsWayIn = Modifier.focusProperties { onEnter = { if (requestedFocusDirection == FocusDirection.Right) enterResults() } }
    // Back from the right pane: the field's gate, the words kept and no keyboard. A release list's own Back, composed
    // later, closes it first. Back from the left pane goes to the rail (TopLevelBack).
    var resultsFocused by remember { mutableStateOf(false) }
    BackHandler(enabled = tv && resultsFocused) { focusField() }
    // Results arriving (an indexer's answer, the title's card; the preview's own search's too, after Enter left nothing
    // focused), which the shell can't see: it checks the focus is in the content, as it does when the screen changes.
    val releasesShown = showingResults || fallback != null
    LaunchedEffect(r, releasesShown) { if (releasesShown) rail?.contentChanged() }

    // A search's releases: what failed, nothing found, or its groups. Played or opened from the preview's own
    // search, a release says the user settled on it (settle).
    fun LazyListScope.releases(r: SearchResults, settle: () -> Unit = {}) = when {
        r.allFailed -> failedState(r, onRetry = vm::retry, onIndexers = onIndexers)
        r.nothingPlayable && r.grouped.hidden.isEmpty() -> nothingState(r, tv = tv, onWithoutEpisode = vm::searchWithoutEpisode)
        else -> resultsState(
            r, showArtwork, hasKey, bannerDismissed, collected, adding, ::collectionFocus, ::focusOf, tv = tv, accounts = accounts,
            passedOver = passedOver,
            onDismissBanner = vm::dismissNoKeyBanner,
            onAddToCollection = { t -> addAndJump(t.name) { vm.addToCollection(t) } },
            onJumpToCollection = { t -> jumpToCollection(CollectionJump(t.key.value, t.name, added = false)) },
            onOpenShow = { id -> r.title?.let { leaving(it.openKey) }; onOpenShow(id) },
            onOpenMovie = { id -> r.title?.let { leaving(it.openKey) }; onOpenMovie(id) },
            onOpen = { settle(); leaving(it.focusKey); openGroup = it.key; vm.clearMarks(); vm.listOpened() },
            onPlay = { g -> settle(); onPlay(playRequest(r, g, g.playable)) },
        )
    }
    // What the list shows: the preview while typing, else [start] with nothing searched yet, else a search's releases.
    // On a TV the Titles' header is the right pane's, beside Search indexers ([titlesHeader] false).
    fun LazyListScope.shown(titlesHeader: Boolean, start: LazyListScope.() -> Unit) = when {
        editing && query.isNotBlank() -> {
            // TMDB had no titles for the words after a pause: the preview's own search of the indexers, under a
            // line that says so (not a section header, whose capitals would change the words typed).
            fallback?.let { f ->
                item {
                    Text(
                        "No titles on TMDB for “${f.query}” · releases from your indexers", style = AppType.secondary, color = colors.textSecondary,
                        modifier = Modifier.padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 12.dp),
                    )
                }
                releases(f, settle = vm::keep)
            }
            suggestionsList(
                suggestions, hasKey, collected, adding, ::collectionFocus, ::focusOf, header = titlesHeader,
                // On a TV with no releases of the preview's own above: the line about TMDB holds the remote's focus
                // until titles come (Enter pressed before them, or for words that have none).
                holdsFocus = tv && fallback == null,
                onOpen = { s -> openTitle(s, s.focusKey) },
                // The keyboard closes; the focus stays (cleared on a remote, it would go to the field and open the keyboard).
                onAdd = { s -> keyboard?.hide(); addAndJump(s.name) { vm.addSuggestionToCollection(s) } },
                onJump = { s -> keyboard?.hide(); jumpToCollection(CollectionJump(s.key.value, s.name, added = false)) },
            )
        }
        r == null || editing -> start()
        else -> {
            // A search of the words sent on purpose (Search indexers, a recent search): what it is, and TMDB's
            // titles for the words on top, each opening its page.
            if (r.byWords) wordsHeader(r.query, strip, ::focusOf, onOpen = { s -> openTitle(s, s.stripKey) })
            releases(r)
        }
    }
    val onRecent: (RecentSearch) -> Unit = { recent ->
        if (recent.openedTitle == null) focus.clearFocus()
        // A title opened before opens its page again; Back from there comes back to its row.
        vm.openRecent(recent)?.let { title ->
            leaving(recent.focusKey)
            if (title.type == TitleType.Tv) onOpenShow(title.tmdbId) else onOpenMovie(title.tmdbId)
        }
    }
    val field = @Composable { modifier: Modifier ->
        SearchTextField(
            gate, query, tv,
            onChange = { closeReleasesForWords(); vm.onQueryChange(it) },
            // Back in the field: what Search left for is behind the user.
            onFocus = { cameBack = false; closeReleasesForWords(); vm.startEditing() },
            onSubmit = { focus.clearFocus(); vm.keep() },
            modifier = modifier,
        )
    }
    val busy = r != null && !r.done && (!editing || r.fallback)
    // The field first, to type again (on a TV its gate, OK to type): Clear and Search indexers go with the
    // words, and the focus would drop into the list under them.
    val onClear: () -> Unit = { focusField(); vm.clear() }
    // On a TV a recent search removed hands the focus on before it goes, to the next one's ✕ (else the one before's,
    // else the field's gate): nothing focusable leaves from under the focus with nothing after it.
    val onRemove: (RecentSearch) -> Unit = { gone ->
        if (tv) {
            val at = recent.indexOfFirst { it.key == gone.key }
            val next = recent.getOrNull(at + 1) ?: recent.getOrNull(at - 1)
            if (next?.let { focusOf(it.removeKey).tryFocus() } != true) focusField()
        }
        vm.removeRecent(gone)
    }
    val onClearRecent: () -> Unit = {
        if (tv) focusField()
        vm.clearRecent()
    }
    val onSearchIndexers: () -> Unit = { focus.clearFocus(); vm.searchIndexers() }
    Box(Modifier.fillMaxSize().onFocusChanged { if (it.hasFocus) hadFocus[0] = true }) {
        if (tv) {
            TvSearchContent(
                wayIn = Modifier.onFocusChanged { inputFocused = it.hasFocus }.then(wayIn),
                resultsWayIn = resultsWayIn.onFocusChanged { resultsFocused = it.hasFocus },
                input = {
                    TvSearchField(field, busy, Modifier.leftMemory(gate.requester), voice, Modifier.focusRequester(mic).leftMemory(mic))
                    LazyColumn(Modifier.fillMaxSize()) {
                        recentSearches(recent, ::focusOf, onRecent, onRemove, onClearRecent, memory = { Modifier.leftMemory(it) })
                    }
                },
                results = {
                    if (releaseList != null) {
                        releaseList(Modifier.focusRequester(releases))
                    } else {
                        TvSearchBar(
                            header = "Titles".takeIf { editing && query.isNotBlank() && suggestions.isNotEmpty() && fallback == null },
                            query, onSearchIndexers, onClear,
                        )
                        LazyColumn(Modifier.fillMaxSize().focusRequester(list), state = tvResults) {
                            shown(titlesHeader = false) { tvStart(hasIndexers, onIndexers) }
                        }
                    }
                },
            )
        } else {
            Column(Modifier.fillMaxSize()) {
                SearchField(field, query, busy, onClear = onClear, onSearchIndexers = onSearchIndexers) {
                    // Search's Play plays here with a TV chosen (W11): the list greys the TVs, and the face says this phone (W59).
                    CastButton(playOn, { container.playOn.openSheet(SheetPlace.PhoneOnly) }, round = true, place = SheetPlace.PhoneOnly)
                }
                LazyColumn(Modifier.fillMaxSize().focusRequester(list), state = rememberLazyListState()) {
                    shown(titlesHeader = true) {
                        startState(recent, hasIndexers, onIndexers, ::focusOf, onRecent = onRecent, onRemove = onRemove, onClear = onClearRecent)
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

/**
 * Search on a TV: the field's gate at the top left with the recent searches
 * under it ([input], about a third of the width), what was found on the right ([results]). The keyboard floats left,
 * over the recent searches, never over the results. Its two panes take the shell's whole width beside the rail, not the
 * phone screens' column. [wayIn] goes on the left pane, where the shell's way in lands first, [resultsWayIn] on the right.
 */
@Composable
private fun TvSearchContent(wayIn: Modifier, resultsWayIn: Modifier, input: @Composable ColumnScope.() -> Unit, results: @Composable ColumnScope.() -> Unit) {
    Row(
        Modifier.fillMaxHeight().tvFullWidth(),
        horizontalArrangement = Arrangement.spacedBy(PANE_GAP),
    ) {
        // TalkBack reads each pane whole, the field and its history first.
        Column(Modifier.weight(1f).fillMaxHeight().semantics { searchPane = "input"; isTraversalGroup = true }.then(wayIn), content = input)
        Column(Modifier.weight(2f).fillMaxHeight().semantics { searchPane = "results"; isTraversalGroup = true }.then(resultsWayIn).focusGroup(), content = results)
    }
}

private val PANE_GAP = 24.dp

/**
 * Search's release list ([content]), kept under the TMDB title its search was picked for ([title]) as well as its group's
 * key (RFO-1). Every picked title's groups are keyed alike ("known|movie:known", "known|ep:1:5"), and Search's results
 * aren't saved: a list's order and place, saved under its group's key and left unread once the process has gone, mustn't
 * open another title's list, a film's after another film's or a show's after another of its name. Told by the title's
 * TMDB id, not its name; a search of typed words has none, its groups' keys naming their title. The title's own list
 * shown again (torrents joining, a re-rank for a Cast device, a mark) keeps the key, and with it the order and the place
 * (L322).
 */
@Composable
internal fun ForPickedTitle(title: TitleInfo?, content: @Composable () -> Unit) = key(title?.type, title?.tmdbId) { content() }

/** Which of Search's two panes on a TV a node is ("input" on the left, "results" on the right): the tests know them by it. */
val SearchPaneKey = SemanticsPropertyKey<String>("SearchPane")
private var SemanticsPropertyReceiver.searchPane by SearchPaneKey

/** The collection's key of a title from TMDB. */
private val TitleSuggestion.key: TitleKey get() = if (type == MediaType.Tv) TitleKey.show(tmdbId) else TitleKey.movie(tmdbId)

private val TitleInfo.key: TitleKey get() = if (type == MediaType.Tv) TitleKey.show(tmdbId) else TitleKey.movie(tmdbId)

/** Which of Search's elements a title's Open is, for the focus on the way back from its page. */
private val TitleInfo.openKey: String get() = "open:${key.value}"

/** Which of Search's elements a title in the Titles is, for the focus on the way back from its page. */
private val TitleSuggestion.focusKey: String get() = "title:${key.value}"

/** Which of Search's elements a title in a search's Titles strip is, likewise. */
private val TitleSuggestion.stripKey: String get() = "strip:${key.value}"

/** What a title is, as its row says under its name: "TV series · 2024". */
private val TitleSuggestion.kindAndYear: String get() = listOfNotNull(if (type == MediaType.Tv) "TV series" else "Movie", year?.toString()).joinToString(" · ")

/** Which of Search's elements a recent search's row is, for the focus on the way back from the page its title opened. */
private val RecentSearch.focusKey: String get() = "recent:$key"

/** Which of Search's elements a recent search's ✕ is. */
private val RecentSearch.removeKey: String get() = "remove:$key"

/** The recent searches' Clear. */
private const val CLEAR_RECENT = "clear-recent"

/** Which of Search's elements a group's row is, the part of it that plays. */
private val ResultGroup.playKey: String get() = "play:$key"

/** The first group a search's results list that plays here; none for a picked movie (its card plays it). */
private fun SearchResults.firstPlayable(): ResultGroup? =
    if (title?.type == MediaType.Movie) null else grouped.sections.firstNotNullOfOrNull { s -> s.groups.firstOrNull { it.best != null } }

/** Which of Search's elements a group's releases (or a movie's releases button) are, for the focus on the way back from its list. */
private val ResultGroup.focusKey: String get() = "group:$key"

/** Labels the request and decides where progress is saved: the TMDB episode/movie when known. */
internal fun playRequest(r: SearchResults, group: ResultGroup, candidates: List<RankedItem>): PlayRequest {
    val t = r.title
    val best = candidates.first().parsed
    val name = t?.name ?: best.title.ifBlank { candidates.first().item.title }
    val season = group.season
    val episode = group.episodes.firstOrNull()
    val watch = when {
        t?.type == MediaType.Tv && group.kind == GroupKind.Episode && season != null && episode != null ->
            WatchTarget(WatchKind.Episode, WatchTitle.episode(name, season, episode), showTmdbId = t.tmdbId, season = season, episode = episode)
        t?.type == MediaType.Movie -> WatchTarget(WatchKind.Movie, name, movieTmdbId = t.tmdbId)
        else -> WatchTarget(WatchKind.Release, candidates.first().item.title)
    }
    val subtitle = when (group.kind) {
        GroupKind.Episode, GroupKind.SeasonPack -> group.label
        GroupKind.Movie -> t?.meta ?: group.year?.toString()
        GroupKind.Other -> null
    }
    // Play on a season pack starts the season's first episode.
    val picker = if (group.kind == GroupKind.SeasonPack && season != null) FilePicker.Episode(season, 1) else FilePicker.Largest
    return PlayRequest(name, if (group.kind == GroupKind.SeasonPack) "${group.label} · episode 1" else subtitle, candidates, picker, watch)
}

/**
 * A release list's Play of [item]: chosen by hand, so tried alone, whatever is known of it (found missing on the
 * providers in use before included, which its row says): the user picked it on purpose. The list's others go with it,
 * for the pre-check's Play a compatible release (L152).
 */
internal fun chosenRequest(r: SearchResults, group: ResultGroup, item: RankedItem): PlayRequest =
    playRequest(r, group, listOf(item)).copy(rememberedFirst = false, chosen = true, alternatives = group.alternativesTo(item))

/**
 * The words to search, behind [gate]: on a TV the field is a button until OK ([TvFieldGate]), so the remote's focus,
 * and Search's first focus, land on its gate with no keyboard; there the keyboard is asked to float left ([tv]), over
 * the recent searches, not over the results.
 */
@Composable
private fun SearchTextField(
    gate: FieldGate,
    query: String,
    tv: Boolean,
    onChange: (String) -> Unit,
    onFocus: () -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pill = RoundedCornerShape(24.dp)
    TvFieldGate(gate, "Search", query, modifier, shape = pill) { field ->
        TextField(
            value = query,
            onValueChange = onChange,
            singleLine = true,
            // A TV's hint for what to type is the right pane's.
            placeholder = { Text(if (tv) "Search" else "Shows, movies or release names", style = AppType.body, color = colors.muted) },
            leadingIcon = { Icon(Icons.Filled.Search, null, tint = colors.muted) },
            textStyle = AppType.body.copy(fontSize = AppType.body.fontSize, color = colors.text),
            keyboardOptions = if (tv) TV_KEYBOARD else KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
            shape = pill,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                cursorColor = colors.accent,
            ),
            modifier = field.onFocusChanged { if (it.isFocused) onFocus() },
        )
    }
}

/** Gboard on a TV floats left when asked (the TV keyboard page's option), leaving the right of the screen to the results. */
private val TV_KEYBOARD = KeyboardOptions(imeAction = ImeAction.Search, platformImeOptions = PlatformImeOptions("horizontalAlignment=left"))

/**
 * A phone's field ([field]), then Clear and Search indexers at its end ([onSearchIndexers]), then the Cast button ([cast],
 * round as Home's, CsPhonePlaces). They sit side by side in one pill, not inside the text field, so a remote reaches
 * them: Right from the field goes to Search indexers, Left from there to Clear.
 */
@Composable
private fun SearchField(
    field: @Composable (Modifier) -> Unit,
    query: String,
    busy: Boolean,
    onClear: () -> Unit,
    onSearchIndexers: () -> Unit,
    cast: @Composable () -> Unit,
) {
    val indexers = remember { FocusRequester() }
    val pill = RoundedCornerShape(24.dp)
    val searchable = query.isNotBlank()
    Column {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth().background(colors.surface, pill).padding(end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            field(Modifier.weight(1f).focusProperties { if (searchable) right = indexers })
            if (query.isNotEmpty()) ClearButton(onClear)
            if (searchable) IndexersButton(query, "Indexers", onSearchIndexers, Modifier.focusRequester(indexers))
            Spacer(Modifier.width(6.dp))
            cast()
        }
        if (busy) LinearProgressIndicator(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(2.dp), color = colors.accent, trackColor = colors.divider)
    }
}

/**
 * A TV's field ([field], [modifier] on its gate) at the top of the left pane, alone in its pill (what acts on the words is
 * the right pane's, [TvSearchBar]), and the mic ([voice], [micModifier]) beside it where a recognizer resolves: left of
 * it, as Android TV's search orb is, so Right from the field still goes straight to the results.
 */
@Composable
private fun TvSearchField(field: @Composable (Modifier) -> Unit, busy: Boolean, modifier: Modifier, voice: (() -> Unit)?, micModifier: Modifier) {
    Column {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (voice != null) {
                IconButton(onClick = voice, modifier = micModifier.focusOutline(CircleShape, color = colors.text, fill = false)) {
                    Icon(MicIcon, "Search by voice", tint = colors.accent)
                }
            }
            Box(Modifier.weight(1f).background(colors.surface, RoundedCornerShape(24.dp))) {
                field(modifier.fillMaxWidth())
            }
        }
        if (busy) LinearProgressIndicator(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(2.dp), color = colors.accent, trackColor = colors.divider)
    }
}

/**
 * The top of a TV's right pane, while there are words: what the list under it starts with ([header], the Titles), then
 * "⌕ Search indexers" and Clear, the two things done with the words typed. Those two sit at its end, right of the
 * rows' first elements: Left from a row goes to the left pane, never up to them, and Up from the first title meets
 * Search indexers.
 */
@Composable
private fun TvSearchBar(header: String?, query: String, onSearchIndexers: () -> Unit, onClear: () -> Unit) {
    if (query.isEmpty()) return
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(header?.uppercase().orEmpty(), style = AppType.section, color = colors.muted, modifier = Modifier.weight(1f))
        if (query.isNotBlank()) IndexersButton(query, "Search indexers", onSearchIndexers)
        ClearButton(onClear)
    }
}

@Composable
private fun ClearButton(onClear: () -> Unit) {
    IconButton(onClick = onClear, modifier = Modifier.focusOutline(CircleShape, color = colors.text, fill = false)) {
        Icon(Icons.Filled.Close, "Clear search", tint = colors.muted)
    }
}

/**
 * "⌕ Indexers" ([label]): the words as typed, searched on every indexer. Named for what it does, so it isn't taken for
 * Enter, which keeps the preview; TalkBack hears the words it searches for.
 */
@Composable
private fun IndexersButton(query: String, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier.heightIn(min = 48.dp).focusOutline(shape, color = colors.text, fill = false).background(colors.accentTint, shape)
            .clickable(role = Role.Button, onClick = onClick).semantics(mergeDescendants = true) { contentDescription = "Search indexers for “$query”" }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.Filled.Search, null, Modifier.size(18.dp), tint = colors.accent)
        Text(label, style = AppType.secondary.copy(fontWeight = FontWeight.SemiBold), color = colors.accent, maxLines = 1)
    }
}

/**
 * TMDB's titles for what is typed, each opening its page ([onOpen]). Each row's title and its "+ Collection" (or
 * "In collection ›") are side by side, not one inside the other, so a remote reaches both (#160). With [holdsFocus],
 * the line about TMDB under them holds a TV's focus while there are none. Without [header], their header is elsewhere
 * (a TV's beside Search indexers).
 */
private fun LazyListScope.suggestionsList(
    suggestions: List<TitleSuggestion>,
    hasKey: Boolean,
    collected: Set<TitleKey>,
    adding: Set<TitleKey>,
    collectionFocus: (TitleKey) -> FocusRequester,
    focusOf: (String) -> FocusRequester,
    header: Boolean,
    holdsFocus: Boolean,
    onOpen: (TitleSuggestion) -> Unit,
    onAdd: (TitleSuggestion) -> Unit,
    onJump: (TitleSuggestion) -> Unit,
) {
    if (suggestions.isNotEmpty()) {
        if (header) item { SectionHeader("Titles", Modifier.padding(top = 0.dp)) }
        item {
            GroupCard {
                suggestions.forEachIndexed { i, s ->
                    // Each row its title's, its focus going with it as the titles change.
                    key(s.key) {
                        if (i > 0) InsetDivider()
                        Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Row(
                                Modifier.weight(1f).focusRequester(focusOf(s.focusKey)).focusOutline().clickable(onClickLabel = "Open") { onOpen(s) }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Poster(s.posterPath, true, 36, 54)
                                Column(Modifier.weight(1f)) {
                                    Text(s.name, style = AppType.bodyStrong, color = colors.text, maxLines = 2)
                                    Text(s.kindAndYear, style = AppType.secondary, color = colors.muted)
                                }
                            }
                            SuggestionCollectionAction(s.name, s.key in collected, s.key in adding, onAdd = { onAdd(s) }, onJump = { onJump(s) }, collectionFocus(s.key))
                        }
                    }
                }
            }
        }
    }
    item {
        Footnote(
            if (hasKey) "Title suggestions from TMDB using your API key. $TMDB_ATTRIBUTION" else "Add a TMDB key in Settings › Metadata for title suggestions.",
            Modifier.holdsTvFocus(holdsFocus && suggestions.isEmpty()),
        )
    }
}

/**
 * What a search of the words is ("Releases for … on your indexers"; not a section header, whose capitals would change
 * the words typed), and TMDB's [titles] for them, a small strip on top of the releases.
 */
private fun LazyListScope.wordsHeader(query: String, titles: List<TitleSuggestion>, focusOf: (String) -> FocusRequester, onOpen: (TitleSuggestion) -> Unit) {
    item(key = "words") {
        Text(
            "Releases for “$query” on your indexers", style = AppType.bodyStrong, color = colors.text,
            modifier = Modifier.padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 12.dp),
        )
    }
    if (titles.isNotEmpty()) item(key = "strip") { TitlesStrip(titles, focusOf, onOpen) }
}

/**
 * TMDB's titles for a search's words, side by side: each opens its page. Compact on a TV too: the TV's poster cards
 * (tv/TvCards.kt) take 147 dp, and at 960 x 540 the releases under them would start below the screen's foot.
 */
@Composable
private fun TitlesStrip(titles: List<TitleSuggestion>, focusOf: (String) -> FocusRequester, onOpen: (TitleSuggestion) -> Unit) {
    Column(Modifier.padding(bottom = 12.dp)) {
        SectionHeader("Titles", Modifier.padding(top = 0.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            titles.forEach { s ->
                val shape = RoundedCornerShape(12.dp)
                Row(
                    Modifier.width(220.dp).focusRequester(focusOf(s.stripKey)).background(colors.surface, shape).focusOutline(shape)
                        .clickable(onClickLabel = "Open") { onOpen(s) }.padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Poster(s.posterPath, true, 32, 48)
                    Column(Modifier.weight(1f)) {
                        Text(s.name, style = AppType.bodyStrong, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(s.kindAndYear, style = AppType.secondary, color = colors.muted, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** A phone's start: no indexer yet, the recent searches, the tips. */
private fun LazyListScope.startState(
    recent: List<RecentSearch>,
    hasIndexers: Boolean,
    onIndexers: () -> Unit,
    focusOf: (String) -> FocusRequester,
    onRecent: (RecentSearch) -> Unit,
    onRemove: (RecentSearch) -> Unit,
    onClear: () -> Unit,
) {
    if (!hasIndexers) noIndexer(onIndexers)
    recentSearches(recent, focusOf, onRecent, onRemove, onClear)
    tips()
}

/** A TV's right pane before anything is typed: no indexer yet, a hint, the tips. */
private fun LazyListScope.tvStart(hasIndexers: Boolean, onIndexers: () -> Unit) {
    if (!hasIndexers) noIndexer(onIndexers)
    item { Text("Shows, movies or release names", style = AppType.cardTitle, color = colors.muted, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) }
    tips()
}

private fun LazyListScope.noIndexer(onIndexers: () -> Unit) {
    item {
        Column(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.accentTint).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Add an indexer to search", style = AppType.bodyStrong, color = colors.text)
            Text("Searches go to the Newznab indexers you add, or to your Prowlarr or NZBHydra.", style = AppType.secondary, color = colors.textSecondary)
            PrimaryButton("Add indexer", onIndexers)
        }
    }
}

/**
 * The recent searches, each with its ✕ beside it, not inside it, so a remote reaches both (#160): OK runs one again (a
 * title opened opens its page again), ✕ removes it. [memory] goes on each element, with its focus requester.
 */
private fun LazyListScope.recentSearches(
    recent: List<RecentSearch>,
    focusOf: (String) -> FocusRequester,
    onRecent: (RecentSearch) -> Unit,
    onRemove: (RecentSearch) -> Unit,
    onClear: () -> Unit,
    memory: (FocusRequester) -> Modifier = { Modifier },
) {
    if (recent.isEmpty()) return
    item {
        SectionHeader("Recent searches", Modifier.padding(top = 0.dp)) {
            val clear = focusOf(CLEAR_RECENT)
            TextButton(onClick = onClear, modifier = Modifier.focusRequester(clear).then(memory(clear))) { Text("Clear", style = AppType.secondary, color = colors.accent) }
        }
    }
    item {
        GroupCard {
            recent.forEachIndexed { i, r ->
                // Each row its search's: the one after a search removed moves up with the focus handed to it, rather than
                // the focus staying put on the one after that.
                key(r.key) {
                    if (i > 0) InsetDivider()
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        // What it was, so what it does again: a title opened opens its page, a search searches.
                        Column(
                            Modifier.weight(1f).focusRequester(focusOf(r.focusKey)).then(memory(focusOf(r.focusKey))).focusOutline().clickable { onRecent(r) }
                                .heightIn(min = 52.dp).padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 8.dp),
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(r.query, style = AppType.body, color = colors.text)
                            Text(r.kindLabel, style = AppType.secondary, color = colors.muted)
                        }
                        IconButton(onClick = { onRemove(r) }, modifier = Modifier.focusRequester(focusOf(r.removeKey)).then(memory(focusOf(r.removeKey))).focusOutline(CircleShape, color = colors.text, fill = false)) {
                            Icon(Icons.Filled.Close, "Remove ${r.query} from recent searches", tint = colors.muted)
                        }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.tips() {
    item { SectionHeader("Tips") }
    item {
        Column(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().border(1.dp, colors.divider, RoundedCornerShape(16.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            listOf(
                "Pick a title to open its page. Play there searches by the title's ids, more precise than words.",
                "Add S02E05 or 2x05 to find one episode.",
                "Paste a release name to find that exact release.",
            ).forEach { Text("•  $it", style = AppType.secondary, color = colors.textSecondary) }
        }
    }
}

private fun LazyListScope.pills(r: SearchResults) {
    item {
        androidx.compose.foundation.layout.FlowRow(
            Modifier.padding(horizontal = 16.dp).padding(bottom = 10.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            r.statuses.forEach { s ->
                val pill = pillLook(s)
                StatusPill(pill.text, pill.kind, quiet = pill.quiet, spoken = pill.spoken)
            }
        }
    }
}

private fun LazyListScope.resultsState(
    r: SearchResults,
    showArtwork: Boolean,
    hasKey: Boolean,
    bannerDismissed: Boolean,
    collected: Set<TitleKey>,
    adding: Set<TitleKey>,
    collectionFocus: (TitleKey) -> FocusRequester,
    focusOf: (String) -> FocusRequester,
    tv: Boolean,
    accounts: List<NamedAccount>,
    passedOver: Map<String, Set<String>>,
    onDismissBanner: () -> Unit,
    onAddToCollection: (TitleInfo) -> Unit,
    onJumpToCollection: (TitleInfo) -> Unit,
    onOpenShow: (Int) -> Unit,
    onOpenMovie: (Int) -> Unit,
    onOpen: (ResultGroup) -> Unit,
    onPlay: (ResultGroup) -> Unit,
) {
    pills(r)
    val title = r.title
    val banner = title == null && !hasKey && !bannerDismissed
    if (banner) item {
        Row(Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.accentTint).padding(start = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Grouped by release names only. Add your own TMDB key in Settings › Metadata for posters, episode titles and a collection of shows and movies.", style = AppType.secondary, color = colors.text, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismissBanner) { Icon(Icons.Filled.Close, "Dismiss", tint = colors.text) }
        }
    }
    if (title?.type == MediaType.Tv) item {
        TvTitleCard(
            title, showArtwork, title.key in collected, title.key in adding,
            onAdd = { onAddToCollection(title) }, onOpen = { onOpenShow(title.tmdbId) }, onJump = { onJumpToCollection(title) },
            collectionFocus = collectionFocus(title.key), openFocus = focusOf(title.openKey),
        )
    }
    if (title?.type == MediaType.Movie) {
        val movieGroup = r.grouped.movieGroup
        item {
            MovieHeroCard(
                title, showArtwork, movieGroup, title.key in collected, title.key in adding,
                onPlay = { movieGroup?.let(onPlay) }, onReleases = { movieGroup?.let(onOpen) },
                onAdd = { onAddToCollection(title) }, onOpen = { onOpenMovie(title.tmdbId) }, onJump = { onJumpToCollection(title) },
                collectionFocus = collectionFocus(title.key), openFocus = focusOf(title.openKey), releasesFocus = movieGroup?.let { focusOf(it.focusKey) },
                tag = movieGroup?.best?.let { sourceTag(it, accounts, passedOver[it.key].orEmpty()) },
            )
        }
    } else {
        r.grouped.sections.forEachIndexed { si, section ->
            val playableGroups = section.groups.filter { it.best != null }
            if (playableGroups.isEmpty()) return@forEachIndexed
            if (title == null) item(key = "h-${section.key}") {
                SectionHeader(section.title + (section.year?.let { " ($it)" } ?: ""), Modifier.padding(top = if (si == 0) 0.dp else 16.dp))
            }
            // One block per season (newest first); movies and unnumbered releases form a single block.
            val blocks = playableGroups.groupBy { it.season }.entries.toList()
            blocks.forEachIndexed { bi, (season, groups) ->
                if (season != null && (blocks.size > 1 || title != null || section.groups.any { it.kind == GroupKind.SeasonPack })) {
                    item(key = "sh-${section.key}-$season") {
                        Text(
                            "Season $season", style = AppType.bodyStrong, color = colors.textSecondary,
                            modifier = Modifier.padding(start = 20.dp, end = 16.dp, top = if (bi == 0 && title == null) 0.dp else 14.dp, bottom = 8.dp),
                        )
                    }
                }
                item(key = "s-${section.key}-$season") {
                    GroupCard {
                        groups.forEachIndexed { i, g ->
                            // Each row its group's: one coming in above it (a later indexer's answer) moves it down with
                            // the focus on it, not the focus left on the group taking its place.
                            key(g.key) {
                                if (i > 0) InsetDivider()
                                GroupRow(
                                    g, null, primary = si == 0 && bi == 0 && i == 0,
                                    label = if (g.kind == GroupKind.SeasonPack && season != null) "Full season" else null,
                                    onPlay = { onPlay(g) }, onReleases = { onOpen(g) },
                                    modifier = Modifier.focusRequester(focusOf(g.playKey)), releasesModifier = Modifier.focusRequester(focusOf(g.focusKey)),
                                    tag = g.best?.let { sourceTag(it, accounts, passedOver[it.key].orEmpty()) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    val hidden = r.grouped.hidden
    // Nothing above the footnote takes the focus: no card, banner, group or hidden line yet (the indexers still to answer).
    val nothingElse = title == null && !banner && r.grouped.sections.none { s -> s.groups.any { it.best != null } } && hidden.isEmpty()
    if (hidden.isNotEmpty()) item(key = "hidden") {
        var expanded by remember { mutableStateOf(false) }
        Column {
            HiddenLine(hiddenLine(hidden, withReasons = true), expanded) { expanded = !expanded }
            if (expanded) GroupCard {
                hidden.forEachIndexed { i, h ->
                    if (i > 0) InsetDivider()
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(h.item.title, style = AppType.release, color = colors.muted)
                        ReasonChips(h)
                    }
                }
            }
        }
    }
    item {
        Footnote(
            if (!r.done) "Showing results from ${r.answered} of ${r.statuses.size} indexers. More arrive as the others answer; the order may change."
            else "Sorted by best playable release · ${r.found} result${if (r.found == 1) "" else "s"} from ${r.answered} of ${r.statuses.size} indexer${if (r.statuses.size == 1) "" else "s"}",
            Modifier.holdsTvFocus(tv && nothingElse),
        )
    }
}

/**
 * On a TV, the words that take the focus while nothing else in Search's results can (the indexers still to answer,
 * nothing found, no titles for the words typed): the remote's focus stays in the results. With
 * nothing focused, the next arrow would open the rail. Once something else shows, these let it go, and the shell puts
 * the focus on what came.
 */
private fun Modifier.holdsTvFocus(holds: Boolean): Modifier = if (holds) focusOutline().semantics(mergeDescendants = true) {}.focusable() else this

private fun LazyListScope.nothingState(r: SearchResults, tv: Boolean, onWithoutEpisode: () -> Unit) {
    item {
        Column(Modifier.holdsTvFocus(tv && r.parsed.season == null).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("No releases found", style = AppType.screenTitle, color = colors.text)
            Text("Nothing on your indexers matches “${r.query}”.", style = AppType.secondary, color = colors.textSecondary)
        }
    }
    breakdown(r, "What each indexer found")
    if (r.parsed.season != null) item {
        SecondaryButton("Search “${r.withoutEpisode.text}” without the episode", onWithoutEpisode, Modifier.padding(16.dp).fillMaxWidth())
    }
}

private fun LazyListScope.failedState(r: SearchResults, onRetry: () -> Unit, onIndexers: () -> Unit) {
    item {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Couldn't search", style = AppType.screenTitle, color = colors.text)
            Text(
                if (r.statuses.isEmpty()) "No indexers are switched on." else "None of your ${r.statuses.size} indexers answered, so there are no results to show.",
                style = AppType.secondary, color = colors.textSecondary,
            )
        }
    }
    breakdown(r, null)
    item {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("Try again", onRetry, Modifier.weight(1f))
            SecondaryButton("Check indexers", onIndexers, Modifier.weight(1f))
        }
    }
}

private fun LazyListScope.breakdown(r: SearchResults, header: String?) {
    header?.let { item { SectionHeader(it, Modifier.padding(top = 0.dp)) } }
    item {
        GroupCard {
            r.statuses.forEachIndexed { i, s ->
                if (i > 0) InsetDivider()
                // Worded as the movie page's no-release sheet words it.
                val text = s.state.outcome()
                val warn = s.state is IndexerStatus.State.Failed || s.state == IndexerStatus.State.Searching
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Text(s.name, style = AppType.body, color = colors.text, modifier = Modifier.weight(1f))
                    Text(text, style = AppType.secondary, color = if (warn) colors.warn else colors.muted)
                }
            }
        }
    }
}
