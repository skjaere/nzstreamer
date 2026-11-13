package io.skjaere.usenet.player

import androidx.media3.common.C

/**
 * What a debrid play's source makes of a link and of each answer to an open ([ServiceDataSource], L172, W81), as plain
 * decisions over the answer's status, content type and `Content-Range`, the place read from, and the link's age.
 *
 * A link is replaced before an open once it is over [MAX_AGE_MS] old: TorBox says a link opens downloads for an hour (its
 * shorter figure), less a margin; the same for every service, as Premiumize says nothing. An answer a dead link gives
 * (401, 403, 404, 410, or a page in place of the file) gets one fresh link, and a second such answer is final: no working
 * link can be had. The source asks for the file from where the read starts itself (`Range: bytes=N-`, [range]), so a 200
 * to a read past the start, or a part other than the one asked, would mean reading and dropping every byte before the
 * place: final at once, never a silent download up to a seek (a fresh link wouldn't make a server honour ranges). Any
 * other failure is Media3's to retry (a CDN's 429 or 503); a read at the file's very end opens with nothing to read.
 */
object ServiceLinkRules {
    /** How old a link may be before an open asks for a fresh one. */
    const val MAX_AGE_MS = 50 * 60_000L

    /** What the source does with an open's answer. */
    sealed interface Answer {
        /** The file, from where it was asked: read on. */
        data object Opened : Answer

        /** The read starts at the file's end: nothing to read. */
        data object AtEnd : Answer

        /** The link answered as a dead one does, for [why] (the status, or a page's type): open again with a fresh one. */
        data class Refresh(val why: String) : Answer

        /** Final: no working link can be had ([LinkUnavailable]). */
        data class Fail(val failure: LinkFailure) : Answer

        /** A failure loading again may get past: Media3's to retry. */
        data object Retry : Answer
    }

    /** The statuses a link that no longer works answers with: refused, gone. */
    private val DEAD = setOf(401, 403, 404, 410)

    private val PART = Regex("""^\s*bytes\s+(\d+)-\d+/(?:\d+|\*)\s*$""", RegexOption.IGNORE_CASE)
    private val UNSATISFIED = Regex("""^\s*bytes\s+\*/(\d+)\s*$""", RegexOption.IGNORE_CASE)

    /** Whether [link] is too old to open with: over [MAX_AGE_MS] at [nowMs]. */
    fun isOld(link: HttpLink, nowMs: Long): Boolean = nowMs - link.madeAtMs > MAX_AGE_MS

    /** Whether an answer of [contentType] is a page (a CDN's sign-in or error page), never the file. */
    fun isPage(contentType: String?): Boolean = contentType?.substringBefore(';')?.trim()?.equals("text/html", ignoreCase = true) == true

    /** The Range a read of [length] bytes ([C.LENGTH_UNSET]: to the end) from [position] asks for. */
    fun range(position: Long, length: Long): String =
        if (length == C.LENGTH_UNSET.toLong()) "bytes=$position-" else "bytes=$position-${position + length - 1}"

    /**
     * What an open from [position] makes of an answer of [status], [contentType] and [contentRange]: [refreshed] says
     * whether its link is already the fresh one this open asked for.
     */
    fun answer(position: Long, status: Int, contentType: String?, contentRange: String?, refreshed: Boolean): Answer {
        val dead = when {
            status in DEAD -> "$status"
            isPage(contentType) -> "text/html"
            else -> null
        }
        if (dead != null) return if (refreshed) Answer.Fail(LinkFailure.NoWorkingLink) else Answer.Refresh(dead)
        if (status == 416) return if (contentRange?.let(UNSATISFIED::matchEntire)?.groupValues?.get(1)?.toLongOrNull() == position) Answer.AtEnd else Answer.Retry
        if (status !in 200..299) return Answer.Retry
        val start = contentRange?.let(PART::matchEntire)?.groupValues?.get(1)?.toLongOrNull()
        return when {
            status == 200 && position == 0L -> Answer.Opened
            status == 206 && start == position -> Answer.Opened
            else -> Answer.Fail(LinkFailure.NoWorkingLink)
        }
    }
}
