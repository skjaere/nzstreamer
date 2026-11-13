package io.skjaere.usenet.app.link

import io.skjaere.usenet.link.LinkMessage

/** What the TV does with a paired phone's message ([TvLinkPolicy.answer]). */
sealed interface TvAnswer {
    /**
     * The phone unpaired itself: it and what goes with it are removed, and its links closed, as Remove on the TV does
     * (L30); but no Remove is noted, since the phone knows already (L4a).
     */
    data object Unpaired : TvAnswer

    /** A command, for the TV's plays ([TvCommands]): they answer it. */
    data class Command(val command: LinkMessage.Command) : TvAnswer

    /** The phone's FinalAck: the Finals [ids] names are owed it no more (L33). */
    data class Acked(val ids: List<String>) : TvAnswer

    /** Nothing to do. */
    data object Ignore : TvAnswer
}

/** When the TV's side of Play on TV runs, and how: decisions alone, so they are tested on the JVM. */
object TvLinkPolicy {
    /**
     * L4: the TV listens and advertises only on a TV ([television], never by layout), only while one of NZStreamer's
     * activities is in front there ([inFront], L31), and only while a phone is paired or Pair a phone takes pairings.
     * L4a adds a Remove made recently ([removedRecently]): with no phone paired, the TV still listens, so a phone it
     * removed hears so. Otherwise nothing listens on a TV with no phone paired.
     */
    fun listens(television: Boolean, inFront: Boolean, phonesPaired: Boolean, pairing: Boolean, removedRecently: Boolean = false): Boolean =
        television && inFront && (phonesPaired || pairing || removedRecently)

    /**
     * L4a: whether the last Remove on Settings › Phones, at [removedAt] (the TV's clock; null for none), still keeps the
     * TV listening at [now]: for [REMOVED_LISTEN_MS] after it, and from [CLOCK_BACK_MS] before it, so a clock set back a
     * little since still counts. A clock far off either way ends it, as 30 days do.
     */
    fun removedRecently(removedAt: Long?, now: Long): Boolean = removedListenLeft(removedAt, now) != null

    /** How long [removedRecently] stays true from [now], in ms; null when it is false. */
    fun removedListenLeft(removedAt: Long?, now: Long): Long? {
        if (removedAt == null) return null
        // As a difference, which a time far off either way can't overflow unseen.
        val since = try {
            Math.subtractExact(now, removedAt)
        } catch (_: ArithmeticException) {
            return null
        }
        return if (since >= -CLOCK_BACK_MS && since < REMOVED_LISTEN_MS) REMOVED_LISTEN_MS - since else null
    }

    /**
     * L15: whether a multicast lock is held while the record is advertised. Before T extensions 7 (Android 12 and older,
     * and 13 without that update) an app must hold one to receive mDNS, which for a TV means the queries it answers; from
     * extensions 7 the system takes care of an app in front. Google says to check the extension's version, not the SDK,
     * and every Android 14 build has 7 or later, so [tExtension] is asked on 13 alone. A TV without Wi-Fi (Ethernet only)
     * takes none.
     */
    fun multicastLock(sdk: Int, wifi: Boolean, tExtension: () -> Int): Boolean = when {
        !wifi -> false
        sdk < TIRAMISU -> true
        sdk > TIRAMISU -> false
        else -> tExtension() < T_EXTENSION_MANAGES_MULTICAST
    }

    /**
     * What the TV does with [message] from a paired phone's link. An Unpair does what Remove on the TV does (L30); a
     * command goes to the TV's plays (L8), which answer each; a FinalAck lets go the Finals it names (L33).
     */
    fun answer(message: LinkMessage): TvAnswer = when (message) {
        LinkMessage.Unpair -> TvAnswer.Unpaired
        is LinkMessage.Command -> TvAnswer.Command(message)
        is LinkMessage.FinalAck -> TvAnswer.Acked(message.ids)
        else -> TvAnswer.Ignore
    }

    private const val DAY_MS = 24 * 60 * 60 * 1_000L

    /** How long a Remove keeps the TV listening (L4a): 30 days. */
    const val REMOVED_LISTEN_MS = 30 * DAY_MS

    /** How far back the clock may be set after a Remove, and the Remove still count (L4a): a day. */
    const val CLOCK_BACK_MS = DAY_MS
    private const val TIRAMISU = 33
    private const val T_EXTENSION_MANAGES_MULTICAST = 7
}
