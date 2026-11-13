package io.skjaere.usenet.app.debrid

import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

/** Settings › Torrent indexers (L194): the user's own Torznab indexers, searched beside the Indexers while a service is on. */
@Serializable data object TorrentIndexersRoute

/** A saved torrent indexer's form; [id] null adds a new one. */
@Serializable data class TorrentIndexerRoute(val id: String? = null)

/** Settings › Debrid services (L196): the user's own service accounts. */
@Serializable data object DebridServicesRoute

/** Add a service: the services not signed in to yet. */
@Serializable data object AddServiceRoute

/** A service's API key form, by its kind's name: signing in, or [replacing] that account's key. */
@Serializable data class ServiceKeyRoute(val kind: String, val replacing: String? = null)

/** A signed-in account's page. */
@Serializable data class ServiceRoute(val id: String)

/**
 * The ways into debrid's screens from the app's others, as AppNav's `debridScreens` gives them: each null while debrid is
 * hidden (L192), when none of its screens is in the graph. [torrentIndexers]: Settings' Torrent indexers row, and First
 * run's step once one is saved; [addTorrentIndexer]: First run's step before. [services]: Settings' Debrid services row,
 * and First run's step once one is signed in; [addService]: First run's step before (Add a service). [setupForms]: the
 * forms Set up with your phone can be opened from whose Done closes them too once it saved something (L209), as it does
 * the Usenet forms: a torrent indexer's would write its old values back on Save, and a key form, once its service is
 * signed in from the phone, is done with (DbTvSignIn 6). [setupPickers]: what Done closes under such a form, as the
 * form's own Save goes back past it: Add a service, under a key form signing in.
 */
data class DebridPages(
    val torrentIndexers: (() -> Unit)? = null,
    val addTorrentIndexer: (() -> Unit)? = null,
    val services: (() -> Unit)? = null,
    val addService: (() -> Unit)? = null,
    val setupForms: List<KClass<*>> = emptyList(),
    val setupPickers: List<KClass<*>> = emptyList(),
)

/**
 * Debrid's part of the app's graph (L192): while [shown], [register] adds its screens and the ways into them open their
 * routes by [open]; hidden, neither runs, so no screen of debrid's is in the graph and nothing in the app opens one.
 * AppNav's `debridScreens` is this over its graph and navigation, so DebridHiddenTest can drive it with the gate closed.
 */
fun debridPages(shown: Boolean, register: () -> Unit, open: (route: Any) -> Unit): DebridPages {
    if (!shown) return DebridPages()
    register()
    return DebridPages(
        torrentIndexers = { open(TorrentIndexersRoute) },
        addTorrentIndexer = { open(TorrentIndexerRoute()) },
        services = { open(DebridServicesRoute) },
        addService = { open(AddServiceRoute) },
        setupForms = listOf(TorrentIndexerRoute::class, ServiceKeyRoute::class),
        setupPickers = listOf(AddServiceRoute::class),
    )
}
