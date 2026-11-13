package io.skjaere.usenet.app.search

import io.skjaere.usenet.app.debrid.ServiceMisses
import io.skjaere.usenet.app.debrid.nameOf
import io.skjaere.usenet.app.play.playCandidate
import io.skjaere.usenet.app.ui.StatusKind
import io.skjaere.usenet.data.ServiceAccount
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.releases.Availability
import io.skjaere.usenet.search.GroupedResults
import io.skjaere.usenet.search.RankedItem
import io.skjaere.usenet.search.hiddenReasons
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Where a release plays from, as the outlined tag after its rank says it (L201; DbPhoneReleases, D2PhoneReleaseTags):
 * [text] on screen, with [more] in a "+n" chip beside it when that many more services have it cached (W84); [spoken] as
 * TalkBack reads the two as one (L214, L251); [dim] on a hidden release, as its name is.
 */
data class SourceTag(val text: String, val spoken: String, val dim: Boolean, val more: Int = 0)

/** A service account that is on, by its [id], with its service's [name]: what a tag names (L201). */
data class NamedAccount(val id: String, val name: String)

/**
 * The accounts on as the settings change ([changes]), in the user's order ([on], L247): a list, not a map, whose equality
 * would ignore the order, so a move of the same accounts reaches the lists and their tags follow it.
 */
fun accountsInOrder(changes: Flow<*>, on: suspend () -> List<ServiceAccount>): Flow<List<NamedAccount>> =
    changes.map { on().map { NamedAccount(it.id, nameOf(it)) } }.distinctUntilChanged()

/**
 * [item]'s tag (L201, L247, W84), by the service accounts on in the user's order ([accounts]): "Usenet"; a cached torrent
 * the service of the first of them that has it cached, the one a play tries first, with [SourceTag.more] the others that
 * have it, which a play tries after it; "Torrent" on one no service will play (W80: no service named but the one it
 * plays from). [passedOver]: the accounts a play of it would pass over, remembered as having failed it (Search's season
 * rows and a movie's card, whose plays aren't chosen), neither named nor counted; remembered on every one, the play passes
 * over the release itself, and its tag names them as a release list's does. Null while no account is on: then every
 * list is as today.
 */
fun sourceTag(item: RankedItem, accounts: List<NamedAccount>, passedOver: Set<String> = emptySet()): SourceTag? {
    if (accounts.isEmpty()) return null
    if (item.item.infoHash == null) return SourceTag(USENET, "From $USENET", dim = !item.ranked.playable)
    val cached = (item.item.availability as? Availability.Cached)?.accounts.orEmpty()
    val on = accounts.filter { it.id in cached }
    val tried = on.filterNot { it.id in passedOver }.ifEmpty { on }
    val first = tried.firstOrNull()
    return if (first != null && item.ranked.playable) tagOf(first.name, tried.size - 1) else SourceTag(TORRENT, "$TORRENT, not playable", dim = true)
}

/**
 * What Search's tags pass over (L247): of the bests [shown] in its result lists (each one's movie card, else its rows),
 * whose plays aren't chosen, the accounts [misses] remembers as having failed each torrent, which a play of it passes
 * over, by the releases' keys, as the tags look them up. A release list's play is chosen and tries every account, so its
 * tags read none of this.
 */
internal suspend fun passedOverShown(shown: List<GroupedResults>, misses: ServiceMisses): Map<String, Set<String>> {
    val torrents = shown.flatMap { it.shownBests() }.filter { it.item.infoHash != null }
    return if (torrents.isEmpty()) emptyMap() else misses.passedOver(torrents.map { it.playCandidate })
}

/** The best releases Search shows of [this], whose plays aren't chosen: the movie card's, else each row's. */
private fun GroupedResults.shownBests(): List<RankedItem> = movieGroup?.best?.let(::listOf) ?: sections.flatMap { s -> s.groups.mapNotNull { it.best } }

/**
 * The play sheet's tag (DEP-10, L247): only the service trying the release now, with no count. Once its Preparing has
 * started on an account ([from], its id), that one's; before, the first in the user's order that has it and that the play
 * won't pass over ([passedOver]: a play not chosen passes over an account remembered as having failed it, COT-2).
 */
fun playTag(item: RankedItem, accounts: List<NamedAccount>, from: String?, passedOver: Set<String> = emptySet()): SourceTag? {
    val tag = sourceTag(item, accounts, passedOver) ?: return null
    if (tag.dim || item.item.infoHash == null) return tag
    return tagOf(from?.let { id -> accounts.firstOrNull { it.id == id }?.name } ?: tag.text, more = 0)
}

/** "Premiumize", and "+1" beside it, read as one: "From Premiumize, also cached on 1 more service" (L251). */
private fun tagOf(service: String, more: Int) =
    SourceTag(service, "From $service" + if (more > 0) ", also cached on $more more service${if (more == 1) "" else "s"}" else "", dim = false, more = more)

/**
 * The line over a list's hidden releases: "5 hidden · not playable", with [withReasons] its commonest reasons after it, as
 * Search's says them (L199): "(Not cached, Not used with your debrid service)".
 */
fun hiddenLine(hidden: List<RankedItem>, withReasons: Boolean): String =
    "${hidden.size} hidden · not playable" + hidden.hiddenReasons().takeIf { withReasons && it.isNotEmpty() }?.joinToString(prefix = " (", postfix = ")").orEmpty()

/**
 * An indexer's pill as Search draws it (StatusPill): its [text] and look, a [quiet] end in the muted colour (a torrent
 * indexer's "· checking 12 torrents", DbPhoneSearch), and what TalkBack reads where the words need more ([spoken], L214).
 */
data class PillLook(val text: String, val kind: StatusKind, val quiet: String? = null, val spoken: String? = null)

/**
 * [status]'s pill: today's for each state, and for a torrent indexer (L199) its count with "· checking N torrents" while
 * they are being checked, no spinner; once they are, "· not checked" as a warning if some couldn't be.
 */
internal fun pillLook(status: IndexerStatus): PillLook = when (val st = status.state) {
    IndexerStatus.State.Searching -> PillLook("${status.name} · searching", StatusKind.Busy)
    is IndexerStatus.State.Answered -> if (st.unchecked > 0) {
        PillLook("${status.name} · ${st.results} · not checked", StatusKind.Warn, spoken = "Warning: ${status.name}, ${st.results}, not checked")
    } else {
        PillLook("${status.name} · ${st.results}", if (st.results > 0) StatusKind.Ok else StatusKind.None)
    }
    is IndexerStatus.State.Failed -> PillLook("${status.name} · ${st.reason}", StatusKind.Warn)
    is IndexerStatus.State.Skipped -> PillLook("${status.name} · skipped", StatusKind.None)
    is IndexerStatus.State.Checking -> {
        val checking = "checking ${st.waiting} torrent${if (st.waiting == 1) "" else "s"}"
        PillLook("${status.name} · ${st.results}", StatusKind.Ok, quiet = "· $checking", spoken = "${status.name}, ${st.results}, $checking")
    }
}

/**
 * A torrent indexer's line on a no-release sheet (L213) when what it found ([found], its own, the ones accepted) was all
 * hidden as not cached: "4 found, none cached", naming no service; null otherwise (an NZB among them, one cached, checked
 * or not, or its torrents not for the service).
 */
internal fun noneCached(found: List<NewznabItem>): String? =
    "${found.size} found, none cached".takeIf { found.isNotEmpty() && found.all { it.infoHash != null && it.availability == Availability.NotCached } }

private const val USENET = "Usenet"
private const val TORRENT = "Torrent"
