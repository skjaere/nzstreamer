package io.skjaere.usenet.link

import kotlinx.serialization.Serializable

/** A Final owed to a phone until its FinalAck (L33), and [at], when it was made by the TV's clock: its age is worked out again each time it is sent. */
@Serializable
data class OutboxFinal(val final: LinkMessage.Final, val at: Long)

/**
 * The Finals a TV owes one phone (L33), oldest first: where the phone's plays stopped, for its progress, kept until the
 * phone's [LinkMessage.FinalAck] names them ([ack]), for [MAX_AGE_MS] at most and [MAX] at most, the oldest going first.
 * Each goes out with its age as it is sent ([due]), the TV's clock's now less when it was made, never below 0, so a TV
 * clock wrong by a constant gives the phone the right time (L25). Pure: each change is a new one, which the TV keeps with
 * the phone's row in link.json, so a restart finds it owed as before and the phone's removal takes it.
 */
data class Outbox(val entries: List<OutboxFinal> = emptyList()) {
    /** With [final], made at [now], owed as well; those past their time left out, and the oldest past [MAX]. */
    fun add(final: LinkMessage.Final, now: Long): Outbox = Outbox((kept(now) + OutboxFinal(final, now)).takeLast(MAX))

    /** Without the Finals [ids] names, the phone having applied or dropped them. */
    fun ack(ids: Collection<String>): Outbox {
        if (ids.isEmpty()) return this
        val acked = ids.toSet()
        return Outbox(entries.filterNot { it.final.id in acked })
    }

    /** What is owed at [now], each with its age then, oldest first. */
    fun due(now: Long): List<LinkMessage.Final> = kept(now).map { it.final.copy(ageMs = (now - it.at).coerceAtLeast(0)) }

    /** Those still within their time at [now]: one made "later" by a clock set back since counts as new. */
    private fun kept(now: Long): List<OutboxFinal> = entries.filter { now - it.at < MAX_AGE_MS }

    override fun toString() = "Outbox(${entries.size})"

    companion object {
        /** Finals owed one phone at most. */
        const val MAX = 50

        /** How long a Final is owed: 30 days. */
        const val MAX_AGE_MS = 30 * 24 * 60 * 60 * 1_000L
    }
}
