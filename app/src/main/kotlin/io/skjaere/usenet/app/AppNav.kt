package io.skjaere.usenet.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.skjaere.usenet.app.debrid.DebridGate
import io.skjaere.usenet.app.debrid.DebridPages
import io.skjaere.usenet.app.home.HomeScreen
import io.skjaere.usenet.app.home.SetupStep
import io.skjaere.usenet.app.link.AddTvScreen
import io.skjaere.usenet.link.KeyId
import io.skjaere.usenet.app.link.PairPhoneScreen
import io.skjaere.usenet.app.link.PlayOnHost
import io.skjaere.usenet.app.link.TvRemoteScreen
import io.skjaere.usenet.app.link.TvsScreen
import io.skjaere.usenet.app.link.PhonesScreen
import io.skjaere.usenet.app.play.PlaySheetHost
import io.skjaere.usenet.app.search.SearchScreen
import io.skjaere.usenet.app.search.VoiceRequest
import io.skjaere.usenet.app.search.openedTitle
import io.skjaere.usenet.app.settings.CacheScreen
import io.skjaere.usenet.app.settings.CrashLogScreen
import io.skjaere.usenet.app.settings.GoogleCastPage
import io.skjaere.usenet.app.settings.GoogleCastScreen
import io.skjaere.usenet.app.settings.IndexerEditScreen
import io.skjaere.usenet.app.settings.IndexersScreen
import io.skjaere.usenet.app.settings.LogsScreen
import io.skjaere.usenet.app.settings.MetadataScreen
import io.skjaere.usenet.app.settings.PlaybackScreen
import io.skjaere.usenet.app.settings.PrivacyScreen
import io.skjaere.usenet.app.settings.ProviderScreen
import io.skjaere.usenet.app.settings.ProvidersScreen
import io.skjaere.usenet.app.settings.SettingsScreen
import io.skjaere.usenet.app.settings.ThisDeviceScreen
import io.skjaere.usenet.app.setup.PhoneSetupScreen
import io.skjaere.usenet.app.tv.LocalSearchKey
import io.skjaere.usenet.app.tv.TopLevelBack
import io.skjaere.usenet.app.tv.TvShell
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.ui.LocalFormFactor
import io.skjaere.usenet.app.ui.colors
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlin.reflect.KClass

/** A top-level tab: what the bottom bar (or a TV's rail) shows, the route it opens and its type. [pinned]: at the rail's bottom. */
data class Tab(val label: String, val icon: ImageVector, val route: Any, val type: KClass<*>, val pinned: Boolean = false)

private val home = Tab("Home", Icons.Filled.Home, HomeRoute, HomeRoute::class)
private val collection = Tab("Collection", Icons.AutoMirrored.Filled.List, CollectionRoute, CollectionRoute::class)
private val search = Tab("Search", Icons.Filled.Search, SearchRoute(), SearchRoute::class)
private val settings = Tab("Settings", Icons.Filled.Settings, SettingsRoute, SettingsRoute::class, pinned = true)

/**
 * The tabs on [formFactor]. A TV's rail starts with Search, as TVs' side menus do, and has Settings
 * as a tab of its own at the bottom: Home's gear isn't one press away there. The rail is entered at
 * the current tab, so the order costs nothing on the way in. The build's [extras] go in where each says.
 */
fun tabsFor(formFactor: FormFactor, extras: AppExtras? = appExtras): List<Tab> {
    val main = when (formFactor) {
        FormFactor.Phone -> listOf(home, collection, search)
        FormFactor.Tv -> listOf(search, home, collection, settings)
    }
    return extras?.tabs(formFactor).orEmpty().fold(main) { tabs, extra ->
        val at = tabs.indexOfFirst { it.type == extra.after }.takeIf { it >= 0 } ?: tabs.indexOfLast { !it.pinned }
        tabs.take(at + 1) + extra.tab + tabs.drop(at + 1)
    }
}

/** Whether [destination] is [tab]'s own screen (not a page opened from it). */
fun NavDestination?.isTab(tab: Tab): Boolean = this?.hierarchy?.any { it.hasRoute(tab.type) } == true

/**
 * Goes to a top-level tab the way the bottom bar does, so each tab keeps its own history.
 * Navigating to a tab's route with a plain navigate() would push it onto the *current* tab's
 * stack, and that tab would then reopen on the wrong screen. [restore] false starts the tab
 * fresh (e.g. a search with a new query) and drops the history it had saved, so coming back to
 * the tab later shows the new start.
 */
fun NavController.switchTab(route: Any, restore: Boolean = true) {
    if (!restore) {
        // A popped tab's history is only saved if none is saved for it yet: clear the old one first.
        popBackStack(graph.findStartDestination().id, inclusive = false, saveState = true)
        clearBackStack(route::class)
        navigate(route) { launchSingleTop = true }
        return
    }
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** The remote of the play this phone follows on a TV (L38), over whatever shows, once. */
fun NavController.openRemote() = navigate(TvRemoteRoute) { launchSingleTop = true }

/**
 * Runs [action] only while this entry's screen is the resumed one. Screens cross-fade for a moment
 * after a push or pop and the one leaving still takes taps: a second tap on its back arrow would
 * pop the screen under it too (even the start destination, leaving nothing shown), and a second
 * tap on a button would open its screen twice.
 */
internal inline fun NavBackStackEntry.ifResumed(action: () -> Unit) {
    if (lifecycle.currentState == Lifecycle.State.RESUMED) action()
}

/**
 * The app's screens; [openedNzbs] are .nzb files other apps opened ([NzbHandoff] ids), each shown in
 * Import. [searchKeys]: a TV remote's search key, which opens a new search on a TV. [remotes]: a cast's notification's
 * tap, which opens the remote over whatever shows (L86). [privacy]: the privacy page asked for from outside the app's
 * screens (the player's list, L138), opened over whatever shows; [addTv] likewise Add a TV, which chooses the TV it pairs.
 */
@Composable
fun AppNav(
    openedNzbs: Flow<String> = emptyFlow(),
    nav: NavHostController = rememberNavController(),
    searchKeys: Flow<Unit> = emptyFlow(),
    remotes: Flow<Unit> = emptyFlow(),
    privacy: Flow<Unit> = emptyFlow(),
    addTv: Flow<Unit> = emptyFlow(),
) {
    val formFactor = LocalFormFactor.current
    val jumps = rememberCollectionJumps(nav)
    // The search key's ask for voice, taken once by the Search it opens: saved, so a restore never asks again.
    val voice = rememberSaveable(saver = VoiceRequest.Saver) { VoiceRequest() }
    val container = LocalContext.current.container
    // On a TV the search key opens Search from a dialog or a sheet too, through the shell as the
    // activity's presses do: they are windows of their own, whose keys the activity never sees.
    val dialogSearchKeys = remember { Channel<Unit>(Channel.CONFLATED) }
    val allSearchKeys = remember(searchKeys) { merge(searchKeys, dialogSearchKeys.receiveAsFlow()) }
    val searchKey: (() -> Unit)? = if (formFactor == FormFactor.Tv) remember { { dialogSearchKeys.trySend(Unit) } } else null
    CompositionLocalProvider(LocalSearchKey provides searchKey) {
        // The sheets' ways to debrid's pages: none while debrid is hidden (L192).
        val debrid = io.skjaere.usenet.app.debrid.debridPages(DebridGate.shown, register = {}, open = { route -> nav.navigate(route) { launchSingleTop = true } })
        PlaySheetHost(
            container.playback,
            onSearch = { route -> nav.switchTab(route, restore = false) },
            onProviders = { nav.navigate(ProvidersRoute) { launchSingleTop = true } },
            // A phone's Play this TV can't start yet (PtTvNotReady): set up here, from the sofa.
            onPhoneSetup = { nav.navigate(PhoneSetupRoute) { launchSingleTop = true } },
            onAddProvider = { nav.navigate(ProviderRoute()) { launchSingleTop = true } },
            onAddIndexer = { nav.navigate(IndexerRoute()) { launchSingleTop = true } },
            // A play started on a TV, mirrored (L37): phones and tablets alone.
            tv = if (container.television) null else container.tvStart,
            // The sheet that says the debrid service is off opens where it is switched on; a TV's not-ready sheet, the page
            // for what its debrid way lacks (D2TvNotReady).
            onServices = debrid.services,
            onAddService = debrid.addService,
            onAddTorrentIndexer = debrid.addTorrentIndexer,
        )
        // Play on's sheet and questions, over any screen, on phones and tablets alone (L36); a TV it pairs is chosen.
        if (!container.television) {
            PlayOnHost(
                onAddTv = { nav.navigate(AddTvRoute(choose = true)) { launchSingleTop = true } },
                onPairAgain = { key -> nav.navigate(AddTvRoute(again = key.hex, choose = true)) { launchSingleTop = true } },
                onPrivacy = { nav.navigate(PrivacyRoute) { launchSingleTop = true } },
            )
        }
        val graph: @Composable () -> Unit = {
            NavHost(nav, startDestination = HomeRoute) {
                appGraph(nav, container, jumps, formFactor, voice)
                appExtras?.run { screens(nav, container, formFactor) }
            }
        }
        when (formFactor) {
            FormFactor.Phone -> PhoneShell(
                nav, tabsFor(formFactor), container.playback, tvStart = if (container.television) null else container.tvStart,
                playing = if (container.television) null else container.remotePlaying,
                cast = if (container.television) null else container.castPlaying,
                onRemote = { nav.openRemote() }, content = graph,
            )
            FormFactor.Tv -> TvShell(nav, tabsFor(formFactor), container.playback, allSearchKeys, onSearchKey = voice::request, content = graph)
        }
    }
    // A cast's notification tapped: its remote, as the bar's tap opens it.
    LaunchedEffect(nav, remotes) {
        remotes.collect {
            nav.currentBackStackEntryFlow.first()
            nav.openRemote()
        }
    }
    // The privacy page the player's list asked for: over whatever shows, as Settings opens it.
    LaunchedEffect(nav, privacy) {
        privacy.collect {
            nav.currentBackStackEntryFlow.first()
            nav.navigate(PrivacyRoute) { launchSingleTop = true }
        }
    }
    // Add a TV the player's list asked for: over whatever shows, as Play on's sheet opens it, choosing the TV it pairs.
    LaunchedEffect(nav, addTv) {
        addTv.collect {
            nav.currentBackStackEntryFlow.first()
            nav.navigate(AddTvRoute(choose = true)) { launchSingleTop = true }
        }
    }
    // A .nzb another app opened: Import over Home, whatever was showing, so Back goes to Home.
    LaunchedEffect(nav, openedNzbs) {
        openedNzbs.collect { id ->
            // Not before the NavHost has its graph: the shell composes it as it lays out, which on
            // a cold start comes after this runs.
            nav.currentBackStackEntryFlow.first()
            nav.switchTab(HomeRoute, restore = false)
            nav.navigate(ImportRoute(opened = id))
        }
    }
}

/**
 * Every screen, for both shells: the routes and what each screen's actions open. On a TV Settings
 * is a tab of its own (the rail's), so it has no Back of its own and Home's gear goes to that tab,
 * and each tab's screen starts with [TopLevelBack] (nothing on a phone). [voice]: the TV's search
 * key asking Search for voice.
 */
fun NavGraphBuilder.appGraph(nav: NavController, container: AppContainer, jumps: CollectionJumpState, formFactor: FormFactor, voice: VoiceRequest? = null) {
    fun back(screen: NavBackStackEntry): () -> Unit = { screen.ifResumed { nav.popBackStack() } }
    val tv = formFactor == FormFactor.Tv
    // "Set up with your phone" opens from these on a TV only: a phone has its keyboard at hand. Every one opens the same
    // page: what is saved decides the phone's.
    fun phoneSetup(screen: NavBackStackEntry): (() -> Unit)? =
        if (tv) { { screen.ifResumed { nav.navigate(PhoneSetupRoute) } } } else null
    val debrid = debridScreens(nav, DebridGate.shown, ::back, ::phoneSetup)
    composable<HomeRoute> { screen ->
        TopLevelBack(nav, screen)
        HomeScreen(
            // A new search, its field focused for typing.
            onSearch = { screen.ifResumed { nav.switchTab(SearchRoute(), restore = false) } },
            onRecent = { recent ->
                screen.ifResumed {
                    val page = recent.page()
                    if (page == null) {
                        nav.switchTab(recent.route(), restore = false)
                    } else {
                        // A title opened from Search opens its page again, and goes to the top as a search run again does.
                        recent.openedTitle?.let { title -> container.appScope.launch { saveOrLog("remember ${title.name} opened") { container.library.addTitle(title) } } }
                        nav.navigate(page)
                    }
                }
            },
            onSettings = { screen.ifResumed { if (tv) nav.switchTab(SettingsRoute) else nav.navigate(SettingsRoute) } },
            onSetup = { step, done ->
                screen.ifResumed {
                    when (step) {
                        SetupStep.Provider -> nav.navigate(if (done) ProvidersRoute else ProviderRoute())
                        SetupStep.Indexer -> nav.navigate(if (done) IndexersRoute else IndexerRoute())
                        SetupStep.Metadata -> nav.navigate(MetadataRoute)
                        // The torrent way's steps show only while debrid does, when these are there.
                        SetupStep.Service -> (if (done) debrid.services else debrid.addService)?.invoke()
                        SetupStep.TorrentIndexer -> (if (done) debrid.torrentIndexers else debrid.addTorrentIndexer)?.invoke()
                    }
                }
            },
            onShow = { id -> screen.ifResumed { nav.navigate(ShowRoute(id)) } },
            onMovie = { id -> screen.ifResumed { nav.navigate(MovieRoute(id)) } },
            onCollection = { screen.ifResumed { nav.switchTab(CollectionRoute) } },
            onImport = { screen.ifResumed { nav.navigate(ImportRoute()) } },
            onPhoneSetup = phoneSetup(screen),
        )
    }
    composable<CollectionRoute> { screen ->
        TopLevelBack(nav, screen)
        BackToSearch(nav, jumps, screen)
        io.skjaere.usenet.app.collection.CollectionScreen(
            onShow = { id -> screen.ifResumed { nav.navigate(ShowRoute(id)) } },
            onMovie = { id -> screen.ifResumed { nav.navigate(MovieRoute(id)) } },
            onAdd = { screen.ifResumed { nav.switchTab(SearchRoute()) } },
            onMetadata = { screen.ifResumed { nav.navigate(MetadataRoute) } },
            jump = jumps.jump,
            onJumpDone = jumps::jumpDone,
            backToSearch = jumps.backToSearch,
        )
    }
    composable<ShowRoute> { screen ->
        io.skjaere.usenet.app.collection.ShowScreen(screen.toRoute<ShowRoute>().tmdbId, onBack = back(screen), onRemote = { screen.ifResumed { nav.openRemote() } })
    }
    // The remote closes as the play it controls ends, and with its Back: once, whatever the moment (a crossfade's second tap).
    composable<TvRemoteRoute> { TvRemoteScreen(onBack = { if (nav.currentDestination?.hasRoute(TvRemoteRoute::class) == true) nav.popBackStack() }) }
    composable<MovieRoute> { io.skjaere.usenet.app.collection.MovieScreen(it.toRoute<MovieRoute>().tmdbId, onBack = back(it)) }
    composable<SearchRoute> { screen ->
        TopLevelBack(nav, screen)
        SearchScreen(
            initial = screen.toRoute<SearchRoute>().request(),
            onIndexers = { screen.ifResumed { nav.navigate(IndexersRoute) } },
            // Through Play on: here, or on the Cast device chosen (L80); never a TV's (W11).
            onPlay = container.playRouter::chosen,
            onOpenShow = { id -> screen.ifResumed { nav.navigate(ShowRoute(id)) } },
            onOpenMovie = { id -> screen.ifResumed { nav.navigate(MovieRoute(id)) } },
            onJumpToCollection = { target -> screen.ifResumed { nav.jumpToCollection(jumps, target) } },
            voiceRequest = voice,
        )
    }
    composable<SettingsRoute> { screen ->
        // A tab only on a TV.
        if (tv) TopLevelBack(nav, screen)
        SettingsScreen(
            onBack = if (tv) null else back(screen),
            onProviders = { screen.ifResumed { nav.navigate(ProvidersRoute) } },
            onIndexers = { screen.ifResumed { nav.navigate(IndexersRoute) } },
            onMetadata = { screen.ifResumed { nav.navigate(MetadataRoute) } },
            onPlayback = { screen.ifResumed { nav.navigate(PlaybackRoute) } },
            onCache = { screen.ifResumed { nav.navigate(CacheRoute) } },
            onLogs = { screen.ifResumed { nav.navigate(LogsRoute) } },
            onThisDevice = { screen.ifResumed { nav.navigate(ThisDeviceRoute) } },
            onPhoneSetup = phoneSetup(screen),
            // A TV's by what the device is, not its layout (L36): only a TV listens for phones.
            onPhones = if (container.television) { { screen.ifResumed { nav.navigate(PhonesRoute) } } } else null,
            // And a phone's or tablet's alone plays on TVs (L36), and casts (L110).
            onTvs = if (container.television) null else { { screen.ifResumed { nav.navigate(TvsRoute) } } },
            onCast = if (GoogleCastPage.shows(container.television)) { { screen.ifResumed { nav.navigate(GoogleCastRoute) } } } else null,
            onPrivacy = { screen.ifResumed { nav.navigate(PrivacyRoute) } },
            onTorrentIndexers = debrid.torrentIndexers?.let { open -> { screen.ifResumed { open() } } },
            onDebridServices = debrid.services?.let { open -> { screen.ifResumed { open() } } },
        )
    }
    composable<PhonesRoute> { screen ->
        PhonesScreen(onBack = back(screen), onPair = { screen.ifResumed { nav.navigate(PairPhoneRoute) } })
    }
    composable<PairPhoneRoute> { screen -> PairPhoneScreen(onDone = back(screen), onBack = back(screen)) }
    composable<TvsRoute> { screen ->
        TvsScreen(
            onBack = back(screen),
            onAdd = { screen.ifResumed { nav.navigate(AddTvRoute()) } },
            onPairAgain = { key -> screen.ifResumed { nav.navigate(AddTvRoute(again = key.hex)) } },
        )
    }
    // Done goes back where Add a TV opened; from the Play on sheet it also chooses the TV it paired (item 23), from Settings
    // nothing else changes (item 14).
    composable<AddTvRoute> { screen ->
        val route = screen.toRoute<AddTvRoute>()
        AddTvScreen(
            onDone = back(screen), onBack = back(screen), again = route.again?.let(KeyId::parse),
            onPaired = { key -> if (route.choose) container.playOn.choose(key) },
        )
    }
    composable<ProvidersRoute> { screen ->
        ProvidersScreen(onBack = back(screen), onEdit = { id -> screen.ifResumed { nav.navigate(ProviderRoute(id)) } })
    }
    composable<ProviderRoute> { screen ->
        val id = screen.toRoute<ProviderRoute>().id
        ProviderScreen(id, onBack = back(screen), onPhoneSetup = phoneSetup(screen))
    }
    composable<IndexersRoute> { screen ->
        IndexersScreen(onBack = back(screen), onEdit = { id -> screen.ifResumed { nav.navigate(IndexerRoute(id)) } })
    }
    composable<IndexerRoute> { screen ->
        val id = screen.toRoute<IndexerRoute>().id
        IndexerEditScreen(id, onBack = back(screen), onPhoneSetup = phoneSetup(screen))
    }
    composable<MetadataRoute> { screen -> MetadataScreen(onBack = back(screen), onPhoneSetup = phoneSetup(screen)) }
    composable<PhoneSetupRoute> { screen ->
        PhoneSetupScreen(
            // A form under the page read its record once, and would write its old values over the phone's on Save.
            onDone = {
                screen.ifResumed {
                    nav.popBackStack()
                    closeUnderPhoneSetup(
                        atop = { routes -> nav.currentDestination?.let { d -> routes.any { d.hasRoute(it) } } == true },
                        pop = { nav.popBackStack() },
                        forms = SETUP_FORMS + debrid.setupForms, pickers = debrid.setupPickers,
                    )
                }
            },
            onBack = back(screen),
        )
    }
    composable<PlaybackRoute> { PlaybackScreen(onBack = back(it)) }
    composable<CacheRoute> { CacheScreen(onBack = back(it)) }
    composable<ThisDeviceRoute> { ThisDeviceScreen(onBack = back(it)) }
    // No such page on a TV, as no row opens it there (L110).
    if (GoogleCastPage.shows(container.television)) {
        composable<GoogleCastRoute> { screen -> GoogleCastScreen(onBack = back(screen), onPrivacy = { screen.ifResumed { nav.navigate(PrivacyRoute) } }) }
    }
    composable<PrivacyRoute> { PrivacyScreen(onBack = back(it)) }
    composable<LogsRoute> { screen ->
        LogsScreen(onBack = back(screen), onCrashLog = { screen.ifResumed { nav.navigate(CrashLogRoute) } })
    }
    composable<CrashLogRoute> { CrashLogScreen(onBack = back(it)) }
    composable<ImportRoute> { screen ->
        ImportScreen(
            opened = screen.toRoute<ImportRoute>().opened,
            onBack = back(screen),
            onProviders = { screen.ifResumed { nav.navigate(ProvidersRoute) } },
        )
    }
}

/**
 * Debrid's screens (L192), in the graph only while [shown]: hidden, none is there, and the ways into them it gives back
 * are null, so nothing opens one ([io.skjaere.usenet.app.debrid.debridPages], whose case is in DebridHiddenTest). [back]
 * and [phoneSetup] are the graph's own, as its other screens take them.
 */
fun NavGraphBuilder.debridScreens(
    nav: NavController,
    shown: Boolean,
    back: (NavBackStackEntry) -> () -> Unit,
    phoneSetup: (NavBackStackEntry) -> (() -> Unit)?,
): DebridPages = io.skjaere.usenet.app.debrid.debridPages(
    shown,
    register = {
        // By their full names, never imported: this block is the one place outside debrid's package that names them.
        composable<io.skjaere.usenet.app.debrid.TorrentIndexersRoute> { screen ->
            io.skjaere.usenet.app.debrid.TorrentIndexersScreen(
                onBack = back(screen),
                onEdit = { id -> screen.ifResumed { nav.navigate(io.skjaere.usenet.app.debrid.TorrentIndexerRoute(id)) } },
            )
        }
        composable<io.skjaere.usenet.app.debrid.TorrentIndexerRoute> { screen ->
            val id = screen.toRoute<io.skjaere.usenet.app.debrid.TorrentIndexerRoute>().id
            io.skjaere.usenet.app.debrid.TorrentIndexerEditScreen(id, onBack = back(screen), onPhoneSetup = phoneSetup(screen))
        }
        composable<io.skjaere.usenet.app.debrid.DebridServicesRoute> { screen ->
            io.skjaere.usenet.app.debrid.DebridServicesScreen(
                onBack = back(screen),
                onAdd = { screen.ifResumed { nav.navigate(io.skjaere.usenet.app.debrid.AddServiceRoute) } },
                onOpen = { id -> screen.ifResumed { nav.navigate(io.skjaere.usenet.app.debrid.ServiceRoute(id)) } },
            )
        }
        composable<io.skjaere.usenet.app.debrid.AddServiceRoute> { screen ->
            io.skjaere.usenet.app.debrid.AddServiceScreen(
                onBack = back(screen),
                onPick = { kind -> screen.ifResumed { nav.navigate(io.skjaere.usenet.app.debrid.ServiceKeyRoute(kind.name)) } },
            )
        }
        composable<io.skjaere.usenet.app.debrid.ServiceKeyRoute> { screen ->
            val route = screen.toRoute<io.skjaere.usenet.app.debrid.ServiceKeyRoute>()
            io.skjaere.usenet.app.debrid.ServiceKeyScreen(
                route.kind, route.replacing, onBack = back(screen),
                // Signed in: back where Add a service opened (Debrid services, or First run); a replaced key, to its page.
                onSaved = {
                    screen.ifResumed {
                        if (route.replacing == null) nav.popBackStack<io.skjaere.usenet.app.debrid.AddServiceRoute>(inclusive = true) else nav.popBackStack()
                    }
                },
                onPhoneSetup = phoneSetup(screen),
            )
        }
        composable<io.skjaere.usenet.app.debrid.ServiceRoute> { screen ->
            val id = screen.toRoute<io.skjaere.usenet.app.debrid.ServiceRoute>().id
            io.skjaere.usenet.app.debrid.ServiceScreen(
                id, onBack = back(screen),
                onReplaceKey = { kind -> screen.ifResumed { nav.navigate(io.skjaere.usenet.app.debrid.ServiceKeyRoute(kind.name, replacing = id)) } },
            )
        }
    },
    open = { route -> nav.navigate(route) },
)

/** The forms "Set up with your phone" opens from, which its Done closes too; debrid's come from `debridScreens` (DebridPages.setupForms). */
private val SETUP_FORMS = listOf(ProviderRoute::class, IndexerRoute::class, MetadataRoute::class)

/**
 * What Set up with your phone's Done closes under it once the page itself is gone, [atop] saying whether the screen now on
 * top is one of the routes given and [pop] closing it: a form the page was opened from ([forms]), which read its record
 * once and would write its old values over the phone's on Save; then the picker that opened that form ([pickers], debrid's
 * Add a service), as the form's own Save goes back past it, never to a list of what isn't signed in to yet (DbTvSignIn 6).
 */
internal fun closeUnderPhoneSetup(atop: (List<KClass<*>>) -> Boolean, pop: () -> Unit, forms: List<KClass<*>>, pickers: List<KClass<*>>) {
    if (!atop(forms)) return
    pop()
    if (atop(pickers)) pop()
}

@Composable
fun PlaceholderScreen(name: String) {
    Box(Modifier.fillMaxSize().padding(24.dp)) { Text("$name — coming next", style = AppType.body, color = colors.muted) }
}
