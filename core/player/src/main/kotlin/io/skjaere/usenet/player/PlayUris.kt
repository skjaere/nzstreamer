package io.skjaere.usenet.player

import io.skjaere.usenet.core.UsenetUris

/**
 * The URIs a play's file is opened by, whatever its source: the scheme the player's and the probe's data source route
 * by ([SourceDataSource]), and the file it names. `usenet://nzb/<id>/file/<index>?name&size` is a file of an NZB the store
 * keeps ([UsenetUris]); `debrid://<listingId>/file/<index>` a file of a listing a debrid service gave, which the app
 * keeps by that opaque id. A service's URI holds no name, hash, account, key or link: the URI reaches the media session,
 * and the player reads the file's name and size from its launch.
 */
object PlayUris {
    /** A Usenet play's scheme. */
    const val USENET = UsenetUris.SCHEME

    /** A debrid service's play's scheme. */
    const val SERVICE = "debrid"

    /** What a play's URI names: the file at [fileIndex] of its release, by its [name] and [size] where the URI says them. */
    sealed interface Target {
        /** The scheme its URI routes by. */
        val scheme: String
        val fileIndex: Int
        val name: String?
        val size: Long?

        /** A file of the NZB [nzb] names, as [UsenetUris] reads it. */
        data class Usenet(val nzb: UsenetUris.Target) : Target {
            override val scheme: String get() = USENET
            override val fileIndex: Int get() = nzb.fileIndex
            override val name: String? get() = nzb.name
            override val size: Long? get() = nzb.size
        }

        /** The file at [fileIndex] of the listing kept as [listingId]. */
        data class Service(val listingId: String, override val fileIndex: Int) : Target {
            override val scheme: String get() = SERVICE
            override val name: String? get() = null
            override val size: Long? get() = null
        }
    }

    /** The URI of the file at [fileIndex] of the listing kept as [listingId], which must need no escaping. */
    fun service(listingId: String, fileIndex: Int): String {
        require(LISTING_ID.matches(listingId)) { "Not a listing id" }
        require(fileIndex >= 0) { "No file at $fileIndex" }
        return "$SERVICE://$listingId/file/$fileIndex"
    }

    /** [uri]'s scheme as it is written, which its source is chosen by; null for none. */
    fun scheme(uri: String): String? = SCHEME.find(uri)?.groupValues?.get(1)

    /** The file [uri] names, null for a URI no source reads. */
    fun parse(uri: String): Target? = when (scheme(uri)) {
        USENET -> UsenetUris.parse(uri)?.let(Target::Usenet)
        SERVICE -> SERVICE_URI.matchEntire(uri)?.let { match ->
            match.groupValues[2].toIntOrNull()?.let { Target.Service(match.groupValues[1], it) }
        }
        else -> null
    }

    /** RFC 3986's scheme, before its colon. */
    private val SCHEME = Regex("""^([A-Za-z][A-Za-z0-9+.-]*):""")

    /** A listing id: RFC 3986's unreserved characters, which a URI holds as they are. */
    private val LISTING_ID = Regex("""[A-Za-z0-9._~-]+""")

    private val SERVICE_URI = Regex("""^$SERVICE://(${LISTING_ID.pattern})/file/(\d+)$""")
}
