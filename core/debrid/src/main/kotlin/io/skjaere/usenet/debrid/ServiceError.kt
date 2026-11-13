package io.skjaere.usenet.debrid

import java.time.Duration

/**
 * How a service call failed, in buckets that name no service (L195, W80): debridav's five, ported as ideas (L185), plus
 * [Refused] for one release the service won't take. Each client maps its own codes to these; the app says what each means
 * for a play.
 */
sealed interface ServiceError {
    /** The service doesn't have this release cached, or no longer has it in the account. */
    data object NotCached : ServiceError

    /**
     * The service took this release down. No code of either service is known to mean it yet (L198); its words, memory
     * and panel are the app's.
     */
    data object Removed : ServiceError

    /**
     * The account reached a limit of the service: nothing more is asked of it for [retryAfter], the answer's wait, else
     * for an hour ([ServiceGate]).
     */
    data class Limit(val retryAfter: Duration? = null) : ServiceError

    /** The service refused the account's key: nothing more is asked with it until a new key is saved. */
    data object KeyRefused : ServiceError

    /** The service didn't answer, or answered with a fault of its own: worth asking again later. */
    data object Down : ServiceError

    /**
     * The service won't take this one release ([tooLarge] when its size is over the plan's); the account's other releases
     * still play.
     */
    data class Refused(val tooLarge: Boolean = false) : ServiceError
}

/**
 * A service call's failure. Its message is [error]'s bucket alone, and it carries no cause: a client's own exceptions
 * can hold a request's address, and TorBox's link request carries the key in its query (L186).
 */
class ServiceException(val error: ServiceError) : Exception(error.toString())
