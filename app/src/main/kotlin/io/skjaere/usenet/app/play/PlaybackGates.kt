package io.skjaere.usenet.app.play

import io.skjaere.usenet.app.settings.NoUsableProvider
import io.skjaere.usenet.core.PlayCandidate
import io.skjaere.usenet.data.ProviderRecord
import io.skjaere.usenet.data.ServiceAccount
import io.skjaere.usenet.releases.Availability
import io.skjaere.usenet.search.RankedItem

/**
 * The [routes] a play may take now (L164, L165): Usenet's ([PlayCandidate.USENET]) while a provider is usable, and the
 * route of each service account that is on ([serviceRoute]). A release plays only by a route of its own that is usable,
 * so a play starts while [any] is, its releases are cut to those ([cut]), and the engine's provider check is waited on
 * only while a Usenet release is left ([waitsForProviders]).
 */
data class UsableRoutes(val routes: Set<String>) {
    /** Whether anything can play now: else the play stops at once, with [noUsableSource]'s words. */
    val any: Boolean get() = routes.isNotEmpty()

    /** Whether one of [candidate]'s routes is usable. */
    fun allows(candidate: PlayCandidate): Boolean = candidate.routes.any { it in routes }

    /**
     * Whether a play tries [candidate]: while a route of its own is usable ([allows]). With none usable now (a provider
     * switched off since the play's gate let it start), every release is: the play goes on as it did before there were
     * routes, and its attempts say what failed.
     */
    fun keeps(candidate: PlayCandidate): Boolean = !any || allows(candidate)

    /** Of [releases], those a play tries ([keeps]), in their order. */
    fun <T> cut(releases: List<T>, candidate: (T) -> PlayCandidate): List<T> = releases.filter { keeps(candidate(it)) }

    /**
     * [item] as a play tries it now: a torrent from those of the accounts its check found it on whose routes are usable, in
     * the routes' order, the user's as it is now (L228), so a list shown before a move, or a Next found ahead, plays in the
     * new order. A list older than a switch (Choose release kept up, a Next found ahead) names an account switched off
     * since, which is never called (DEP-4); one cached there alone is then left with no route, and [cut] leaves it out. As
     * it is while nothing is usable, as [keeps] tries everything then, and an NZB, which plays by Usenet's route.
     */
    fun narrow(item: RankedItem): RankedItem {
        val cached = item.item.availability as? Availability.Cached ?: return item
        if (!any) return item
        val usable = routes.mapNotNull(::accountOf).filter { it in cached.accounts }
        return if (usable == cached.accounts) item else item.copy(item = item.item.copy(availability = Availability.Cached(usable)))
    }

    companion object {
        /** Usenet's route while a provider is [providerUsable], and one for each of the [accountsOn] (their ids), in their order. */
        fun of(providerUsable: Boolean, accountsOn: List<String>): UsableRoutes =
            UsableRoutes(buildSet {
                if (providerUsable) add(PlayCandidate.USENET)
                accountsOn.forEach { add(serviceRoute(it)) }
            })

        /** The route of the service account [accountId]: what its limit, refused key or outage stops for the rest of a play (L164). */
        fun serviceRoute(accountId: String): String = SERVICE + accountId

        /** The service account whose route [route] is ([serviceRoute]); null for Usenet's. */
        fun accountOf(route: String): String? = route.takeIf { it.startsWith(SERVICE) }?.removePrefix(SERVICE)

        private const val SERVICE = "service:"
    }
}

/**
 * Why nothing can be streamed now, said at once rather than after a search (L165): null while some route is usable
 * ([UsableRoutes.any]), a provider or a service account; for a user with no provider set up and service accounts saved
 * ([servicesSaved], while debrid shows), all off, that the service is off ([NoUsableProvider.ServiceOff], L213); else why
 * no provider is, from the [providers] saved and how many of them are [usableProviders], in the words the play sheet has
 * always used.
 */
fun noUsableSource(routes: UsableRoutes, providers: List<ProviderRecord>, usableProviders: Int, servicesSaved: Boolean = false): NoUsableProvider? = when {
    routes.any -> null
    providers.isEmpty() && servicesSaved -> NoUsableProvider.ServiceOff
    else -> NoUsableProvider.of(providers, usableProviders)
}

/** Whether service accounts are [saved] for [noUsableSource]'s words: never while debrid is hidden (L192), whatever a shown build saved. */
fun servicesSaved(saved: List<ServiceAccount>, shown: Boolean): Boolean = shown && saved.isNotEmpty()

/**
 * Whether a play of [candidates] waits for the engine's check of the providers first: only while one may play from
 * Usenet. A play of a service's releases alone has no provider to wait for (L165).
 */
fun waitsForProviders(candidates: List<PlayCandidate>): Boolean = candidates.any { PlayCandidate.USENET in it.routes }
