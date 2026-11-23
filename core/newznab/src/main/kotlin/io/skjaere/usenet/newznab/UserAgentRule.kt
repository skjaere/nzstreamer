package io.skjaere.usenet.newznab

/**
 * What an indexer's own User-Agent may be (L548), the forms', the phone setup page's and the client's guard's one rule.
 * Trimmed with Kotlin's trim(), it is blank (the client's own is sent), or printable ASCII (' '..'~') of at most
 * [MAX_LENGTH] chars. OkHttp refuses any other char but tab, and names the whole value in its error; tab, which both
 * stacks would send, is refused too, as the field never needs one. The setup page keeps its own copy of the rule, trimmed
 * the same way: neither may change its trim alone.
 */
object UserAgentRule {
    /** Room for any browser's string, which is about 150 chars. */
    const val MAX_LENGTH = 256

    enum class Problem { Characters, TooLong }

    /** What keeps [value] from being sent, a char it can't hold first; null when it may be, blank included. */
    fun problem(value: String): Problem? {
        val trimmed = value.trim()
        return when {
            trimmed.any { it !in ' '..'~' } -> Problem.Characters
            trimmed.length > MAX_LENGTH -> Problem.TooLong
            else -> null
        }
    }
}
