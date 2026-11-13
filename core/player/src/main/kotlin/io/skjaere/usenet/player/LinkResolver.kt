package io.skjaere.usenet.player

import java.io.IOException

/**
 * Where a debrid play's file is read from (L172): the app's, which asks the account's service ([ServiceDataSource] reads
 * through it). core:player knows no service: these types are its own, and the app maps a service's answers to them.
 */
fun interface LinkResolver {
    /**
     * A link to the file [target] names: one held, else a fresh one from the service. [stale] is the link the asker has
     * and won't use (it answered as a dead link would, or it is over [ServiceLinkRules.MAX_AGE_MS] old), so it is never
     * the answer; null when the asker has none. Called on a loader's thread, which it may block, one call at a time per
     * file. Throws [LinkUnavailable] when no link can be had.
     */
    fun link(target: PlayUris.Target.Service, stale: HttpLink?): HttpLink
}

/**
 * A link to a file over HTTPS: its [url], made at [madeAtMs] (the wall clock's milliseconds), and the file's [name] where
 * the resolver knows it, for its container type. Held in memory only: never stored, logged, put in a URI or shown (L171),
 * so its text says when it was made and nothing else.
 */
data class HttpLink(val url: String, val madeAtMs: Long, val name: String? = null) {
    override fun toString() = "HttpLink(made at $madeAtMs)"
}

/** Why no link to a file can be had (L172, L204), in buckets that name no service but a refused key's. */
sealed interface LinkFailure {
    /** A fresh link failed as the one before it did, a 200 came to a ranged read, or a refresh brought no link (W81). */
    data object NoWorkingLink : LinkFailure

    /** The service no longer has the release cached. */
    data object NotCached : LinkFailure

    /** The service took the release down. */
    data object Removed : LinkFailure

    /** The account reached a limit of its service, which holds it for [waitMs] where its answer said. */
    data class Limit(val waitMs: Long? = null) : LinkFailure

    /** The service refused the account's key: the one failure that names the service, by [serviceName]. */
    data class KeyRefused(val serviceName: String) : LinkFailure

    /** The service didn't answer. */
    data object Down : LinkFailure

    /** The service won't play this release, [tooLarge] for the account's plan. */
    data class Refused(val tooLarge: Boolean = false) : LinkFailure
}

/**
 * No link to the file can be had ([failure]): final for the load ([UsenetLoadErrorHandlingPolicy]), as loading again would
 * only ask again. Its message is the bucket alone.
 */
class LinkUnavailable(val failure: LinkFailure) : IOException(failure.toString())
