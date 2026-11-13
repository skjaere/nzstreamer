package io.skjaere.usenet.debrid

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * One debrid account, whatever its service (L195, W80): check which torrents it has cached, prepare one release into a
 * listing of its files and a picked file, and give that file's link. The steps a service needs before a link (TorBox adds
 * the torrent to the account, Premiumize adds nothing) stay inside [prepare], so the app runs one Preparing step for every
 * service. A torrent is only ever named by its v1 [InfoHash] (L159).
 *
 * Every call fails only with [ServiceException], in neutral buckets ([ServiceError]); a coroutine's cancellation passes
 * through as it is. A new service is one more client and one more [ServiceKind].
 */
interface DebridService {
    val kind: ServiceKind

    /**
     * Signs in with the account's key and says whose account it is. [ServiceError.KeyRefused] when the service refuses
     * the key; [ServiceError.Refused] when it takes the key but won't serve the account (Premiumize's
     * `permission_denied`: restricted or unconfirmed). Never held by a closed gate: the user asked.
     */
    suspend fun test(): Account

    /** Whether the service has each of [hashes] cached, every one of them in the answer; asked in batches as it allows. */
    suspend fun check(hashes: List<InfoHash>): Map<InfoHash, Boolean>

    /**
     * Makes the release of [hash] ready to play: its files as the service lists them, handed to [pick] before anything is
     * added to the account (L179), then whatever the service needs for a link. When [pick] picks none, nothing is added
     * and the answer's [Prepared.picked] is null. [ServiceError.NotCached] when the service doesn't have it. An exception
     * [pick] throws passes through as it is.
     */
    suspend fun prepare(hash: InfoHash, pick: suspend (ServiceListing) -> ServiceFile?): Prepared

    /**
     * A link to [file] of [prepared], fresh from the service or given with its listing and not used yet. Where the account
     * no longer has the torrent (L234: removed after a play, by this device or another), a service that adds to the
     * account adds it again under [prepare]'s rules and hands the changed release out with the link
     * ([ServiceLink.prepared]).
     */
    suspend fun link(prepared: Prepared, file: ServiceFile): ServiceLink

    /**
     * Removes from the account the torrent [handle] names ([Prepared.handle]), one the app proved this device added
     * ([AddWatch]); it returns once the service has taken it out, or, for a client whose [AddWatch] no longer wants the
     * account's records as the delete's turn comes, with no call: nothing of the account's is this device's to remove then.
     * Only a service that adds to the account ([ServiceKind.addsToAccount]) has anything to remove: by default no call is
     * made. A handle that can't name one torrent is refused before any call (IllegalArgumentException).
     */
    suspend fun remove(handle: String) {}
}

/**
 * What a client tells the app of the torrents it adds to the account (#318, L232), so the app removes only what this device
 * added. TorBox keeps one entry per torrent per account, shared with every app using the key, so an add is this device's
 * only when the account's whole list, read just before it, didn't hold the torrent and doesn't hold the id the add
 * answered with: then [added] is told, once, as the add answers and before anything else is asked of it. Unsure means
 * not ours: when the list can't be read whole, nothing is told. While [wanted] says no (Remove after playing off),
 * no list is read, nothing is told and nothing is removed.
 */
interface AddWatch {
    /** Whether the app keeps records of this account's adds now. */
    suspend fun wanted(): Boolean

    /** The torrent of [hash] was added to the account as [handle], which this device's add alone made. */
    suspend fun added(hash: InfoHash, handle: String)
}

/**
 * The services the app can sign in to (W69), A to Z: what the app shows of one is data here, so no screen names a
 * service in its own words (W80). [keyPlace] says where the service keeps the API key the user pastes (W70);
 * [addsToAccount], whether a play adds the torrent to the account's list, which the app then removes (W83: TorBox's
 * `createtorrent`; Premiumize's `directdl` adds nothing, as D2's live check found).
 */
enum class ServiceKind(val displayName: String, val keyPlace: String, val addsToAccount: Boolean) {
    Premiumize("Premiumize", "In Premiumize: Account › API key", addsToAccount = false),
    TorBox("TorBox", "In TorBox: Settings › API key", addsToAccount = true),
}

/**
 * Whose account a key opens, as the service names it ([name]: TorBox an e-mail address, Premiumize "customer" and its
 * number), its [plan] where the service names one, and when the plan ends ([planUntil]) where the answer says.
 */
class Account(val name: String, val plan: String? = null, val planUntil: Instant? = null) {
    override fun equals(other: Any?) = other is Account && other.name == name && other.plan == plan && other.planUntil == planUntil
    override fun hashCode() = listOf(name, plan, planUntil).hashCode()

    // The account's name is the user's own (an e-mail address): never in a log line (L208).
    override fun toString() = "Account(plan=$plan, planUntil=$planUntil)"
}

/** One file of a release as a service lists it: its place in the listing ([index]), its [path] with any folders, its [size] in bytes. */
@Serializable
data class ServiceFile(
    val index: Int,
    val path: String,
    val size: Long,
    /** The service's own id for the file (TorBox's, once the torrent is in the account); null where it has none. */
    val id: String? = null,
) {
    /** Its name without its folders. */
    val name: String get() = path.substringAfterLast('/')
}

/** A release's files as a service lists them, each at its [ServiceFile.index]. */
@Serializable
data class ServiceListing(val files: List<ServiceFile>)

/**
 * What [DebridService.prepare] made ready: the release's [listing] (with the service's ids where it gave them), the file
 * [picked] from it (null when the pick took none, and then nothing was added), and the service's [handle] for the
 * release in the account (TorBox's torrent id; null for a service that adds nothing).
 *
 * Its serialised form is what the app's listing store keeps (L181): never a link. Links a service gave with the
 * listing (Premiumize's `directdl`) stay in memory, each handed out once by [DebridService.link].
 */
@Serializable
class Prepared(
    val hash: InfoHash,
    val listing: ServiceListing,
    val picked: ServiceFile?,
    val handle: String? = null,
) {
    @Transient
    private val links: MutableMap<String, ServiceLink> = ConcurrentHashMap()

    /** The link the service gave with the listing for the file at [path], given once: after it, a link is asked afresh. */
    internal fun takeLink(path: String): ServiceLink? = links.remove(path)

    internal fun holdLinks(byPath: Map<String, ServiceLink>) = links.putAll(byPath)

    override fun equals(other: Any?) =
        other is Prepared && other.hash == hash && other.listing == listing && other.picked == picked && other.handle == handle

    override fun hashCode() = listOf(hash, listing, picked, handle).hashCode()

    // No hash, name or handle in a log line (L208).
    override fun toString() = "Prepared(${listing.files.size} files, picked=${picked?.index}, handle=${if (handle != null) "set" else "none"})"
}

/**
 * A file's link, made at [madeAt]: held in memory only, never stored, logged, put in a URI or shown (L171). [prepared] is
 * the release as it is now when the link came from adding the torrent again (L234: a new handle and new file ids, for
 * the app to keep in place of the old); null otherwise.
 */
class ServiceLink(val url: String, val madeAt: Instant, val prepared: Prepared? = null) {
    override fun toString() = "ServiceLink(made at $madeAt${if (prepared != null) ", added again" else ""})"
}
