package io.skjaere.usenet.app

import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.search.SearchRequest
import io.skjaere.usenet.app.search.openedTitle
import io.skjaere.usenet.app.search.suggestion
import io.skjaere.usenet.data.RecentSearch
import io.skjaere.usenet.data.SearchPick
import io.skjaere.usenet.data.TitleType
import kotlinx.serialization.Serializable

@Serializable data object HomeRoute
@Serializable data object CollectionRoute

/**
 * The Search tab, running [query] at once when given. A recent search picked from TMDB's
 * suggestions carries the title ([pickType] "Tv" or "Movie", [pickTmdbId], [pickName]), so it is
 * searched by the title's ids again. A search of the words for a known title (the no-release
 * sheet's, for an episode) carries its original [language], which its untagged releases speak.
 */
@Serializable data class SearchRoute(
    val query: String? = null,
    val pickType: String? = null,
    val pickTmdbId: Int? = null,
    val pickName: String? = null,
    val language: String? = null,
)
@Serializable data object SettingsRoute
@Serializable data object ProvidersRoute
/** A saved provider's screen; [id] null adds a new one. */
@Serializable data class ProviderRoute(val id: String? = null)
@Serializable data object IndexersRoute
@Serializable data class IndexerRoute(val id: String? = null)
@Serializable data object MetadataRoute
@Serializable data object PlaybackRoute
@Serializable data object CacheRoute
@Serializable data object ThisDeviceRoute
@Serializable data object LogsRoute
@Serializable data object CrashLogRoute
/** The Import screen; [opened] is the id of a .nzb another app opened ([NzbHandoff]), which it shows instead of the picker. */
@Serializable data class ImportRoute(val opened: String? = null)
/**
 * "Set up with your phone" (TV only): [scope] "provider", "indexer", "tmdb" or null for all three; [replaces] the saved
 * provider or indexer a form opened it for. Never a secret, nor the page's code: a route is kept in saved state.
 */
@Serializable data object PhoneSetupRoute
/** Settings › Phones (TV only): the phones paired for Play on TV. */
@Serializable data object PhonesRoute
/** Settings › Phones › Pair a phone (TV only): the TV takes pairings while it shows. */
@Serializable data object PairPhoneRoute
/** Settings › TVs (phones and tablets only, L36): the TVs paired for Play on TV. */
@Serializable data object TvsRoute
/** Settings › Google Cast (phones and tablets only, L112): "Look for Cast devices" as a switch. */
@Serializable data object GoogleCastRoute
/** Settings › Privacy (phones and TVs, L138): the policy, readable offline; also opened by Cast's Privacy links. */
@Serializable data object PrivacyRoute
/**
 * Add a TV (phones and tablets only, L36): pairing with a TV by matching numbers; [again], a Pair again's TV (its key id);
 * [choose], opened from Play on, so the TV it pairs is chosen.
 */
@Serializable data class AddTvRoute(val again: String? = null, val choose: Boolean = false)
/** The remote of a play this phone started on a TV (phones and tablets only, L38): opened from the Playing on bar or the Show page. */
@Serializable data object TvRemoteRoute
@Serializable data class ShowRoute(val tmdbId: Int)
@Serializable data class MovieRoute(val tmdbId: Int)

/** The search the tab runs as it opens; null opens it to type in. */
fun SearchRoute.request(): SearchRequest? {
    val text = query ?: return null
    val type = TitleType.entries.firstOrNull { it.name == pickType }
    val pick = if (type != null && pickTmdbId != null && pickName != null) SearchPick(type, pickTmdbId, pickName) else null
    return SearchRequest(text, pick?.suggestion(), language = language)
}

/** The Search tab running [query], by the TMDB title it is for ([pick]) when there is one, else ranked in its [language] when known. */
fun searchRoute(query: String, pick: SearchPick?, language: String? = null): SearchRoute =
    SearchRoute(query, pick?.type?.name, pick?.tmdbId, pick?.name, language)

/** The page [this] recent title opened, which it opens again; null for a search, which the Search tab runs again ([route]). */
fun RecentSearch.page(): Any? = openedTitle?.let { if (it.type == TitleType.Tv) ShowRoute(it.tmdbId) else MovieRoute(it.tmdbId) }

/** Opens the Search tab on [this] recent search, picked title and all. */
fun RecentSearch.route(): SearchRoute = searchRoute(query, pick)

/** What the play sheet's Search opens after a failure: its search, by the TMDB title it is for when known; null when it offers none. */
fun PlayState.Failed.searchRoute(): SearchRoute? = search?.let { searchRoute(it, searchPick) }

/** What the no-release sheet's Search opens: its search, by the TMDB title it is for or in the show's language. */
fun PlayState.NoRelease.searchRoute(): SearchRoute = searchRoute(nameQuery, searchPick, searchLanguage)
