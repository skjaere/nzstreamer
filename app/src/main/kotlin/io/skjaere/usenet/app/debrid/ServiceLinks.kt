package io.skjaere.usenet.app.debrid

import io.skjaere.usenet.debrid.DebridService
import io.skjaere.usenet.debrid.Prepared
import io.skjaere.usenet.debrid.ServiceError
import io.skjaere.usenet.debrid.ServiceException
import io.skjaere.usenet.debrid.ServiceFile
import io.skjaere.usenet.debrid.ServiceKind
import io.skjaere.usenet.debrid.ServiceLink
import io.skjaere.usenet.player.HttpLink
import io.skjaere.usenet.player.LinkFailure
import io.skjaere.usenet.player.LinkResolver
import io.skjaere.usenet.player.LinkUnavailable
import io.skjaere.usenet.player.PlayUris
import io.skjaere.usenet.player.ServiceLinkRules
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * The links of the files debrid plays read (L172, W81), for the player's and the probe's source: a link held per listing
 * and file, in memory only (L171), first the one Opening got ([hold]), and a fresh one from the account that prepared the
 * listing ([DebridListingStore], by the URI's opaque id) once the one held is over 50 minutes old or the player found it
 * dead. A link that isn't HTTPS is never handed out ([httpsOnly]: but at the fakes' address, a device test's seam). The
 * service's refusals reach the player in its own buckets ([failureOf]); a listing no longer kept, or an account since
 * signed out of, has no working link. One file's link is asked at a time; the player's loader thread waits for it.
 */
class ServiceLinks(
    private val listings: DebridListingStore,
    private val accounts: suspend (accountId: String) -> DebridService?,
    private val now: () -> Long = System::currentTimeMillis,
    private val httpsOnly: () -> Boolean = { ServiceEndpoints.override == null },
) : LinkResolver {
    private val held = ConcurrentHashMap<PlayUris.Target.Service, HttpLink>()
    private val locks = ConcurrentHashMap<PlayUris.Target.Service, Any>()

    /** Holds the [link] Opening got for [file] of the listing kept as [listingId]: the one the probe and the player open with first. */
    fun hold(listingId: String, file: ServiceFile, link: ServiceLink) {
        held[PlayUris.Target.Service(listingId, file.index)] = link.of(file)
    }

    override fun link(target: PlayUris.Target.Service, stale: HttpLink?): HttpLink = synchronized(locks.getOrPut(target) { Any() }) {
        val link = held[target]?.takeIf { it != stale && !ServiceLinkRules.isOld(it, now()) } ?: runBlocking { fresh(target) }.also { held[target] = it }
        if (httpsOnly() && !link.url.startsWith("https://", ignoreCase = true)) throw LinkUnavailable(LinkFailure.NoWorkingLink)
        link
    }

    /**
     * A fresh link to [target]'s file from the account that prepared its listing. One that comes with the release added
     * again (L234: the account no longer had it) keeps that release in place of the old, under the same listing id, so the
     * URI and the add's record hold, and the next link is asked by its new handle.
     */
    private suspend fun fresh(target: PlayUris.Target.Service): HttpLink {
        val entry = listings.get(target.listingId) ?: throw LinkUnavailable(LinkFailure.NoWorkingLink)
        val file = entry.prepared.listing.files.firstOrNull { it.index == target.fileIndex } ?: throw LinkUnavailable(LinkFailure.NoWorkingLink)
        val service = accounts(entry.account) ?: throw LinkUnavailable(LinkFailure.NoWorkingLink)
        val link = try {
            service.link(entry.prepared, file)
        } catch (e: ServiceException) {
            throw LinkUnavailable(failureOf(e.error, service.kind))
        }
        link.prepared?.let { again -> keep(entry, again) }
        return link.of(file)
    }

    /** [again], the release [entry] plays added anew, kept as [entry] is: its id is the account's and the torrent's, the same. */
    private suspend fun keep(entry: DebridListingStore.Entry, again: Prepared) {
        val kept = listings.put(entry.account, entry.title, again)
        try {
            listings.keep(kept.id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A full disk: this process plays on from the new handle; a player made after it went asks the old one, and adds it again.
            log.warn("debrid: the listing added again wasn't written ({})", e.javaClass.simpleName)
        }
    }

    private fun ServiceLink.of(file: ServiceFile) = HttpLink(url, madeAt.toEpochMilli(), file.name)

    companion object {
        private val log = LoggerFactory.getLogger(ServiceLinks::class.java)

        /** What the service's [error] for an account of [kind] is to the player (L204): only a refused key names the service. */
        fun failureOf(error: ServiceError, kind: ServiceKind): LinkFailure = when (error) {
            ServiceError.NotCached -> LinkFailure.NotCached
            ServiceError.Removed -> LinkFailure.Removed
            is ServiceError.Limit -> LinkFailure.Limit(error.retryAfter?.toMillis())
            ServiceError.KeyRefused -> LinkFailure.KeyRefused(kind.displayName)
            ServiceError.Down -> LinkFailure.Down
            is ServiceError.Refused -> LinkFailure.Refused(error.tooLarge)
        }
    }
}
