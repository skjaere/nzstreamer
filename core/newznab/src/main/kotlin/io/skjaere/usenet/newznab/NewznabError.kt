package io.skjaere.usenet.newznab

/** What went wrong talking to one indexer, in terms the UI can show as a chip. */
sealed class NewznabError(val message: String) {
    class Auth(message: String) : NewznabError(message)
    class ApiLimitReached(message: String, val retryAfter: java.time.Duration? = null) : NewznabError(message)
    class DownloadLimitReached(message: String) : NewznabError(message)
    class Disabled(message: String) : NewznabError(message)
    class Unsupported(message: String) : NewznabError(message)
    class NotFound(message: String) : NewznabError(message)
    class Server(message: String) : NewznabError(message)
    class BadResponse(message: String) : NewznabError(message)
    class Timeout(message: String) : NewznabError(message)
    class Network(message: String) : NewznabError(message)

    override fun toString() = "${this::class.simpleName}($message)"

    companion object {
        /** Error codes from the Newznab API spec, plus 429 which several implementations use. */
        fun fromCode(code: Int, description: String): NewznabError = when (code) {
            100, 101, 102 -> Auth(description)
            429, 500 -> ApiLimitReached(description)
            501 -> DownloadLimitReached(description)
            910 -> Disabled(description)
            202, 203 -> Unsupported(description)
            300 -> NotFound(description)
            200, 201 -> BadResponse(description)
            else -> Server(description)
        }
    }
}

class NewznabException(val error: NewznabError, cause: Throwable? = null) : Exception(error.toString(), cause)
