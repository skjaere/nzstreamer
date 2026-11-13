package io.skjaere.usenet.app.casting

import io.skjaere.usenet.app.collection.mmss

// Missing parts during a cast (W37, L94, L104; CsPhoneMissingParts): the player's prompt and notice, with a line for the
// cast, as the remote and the app's other screens show them. The cast play the bar follows (CastPlaying) decides when.

/**
 * The missing-parts prompt for a cast as it shows: [count] parts so far, whether another release can be tried (else the
 * count alone, [tryAnother]), and whether the device is stuck at one ([stuck], L104), on [device].
 */
data class CastPrompt(val count: Int, val tryAnother: Boolean, val stuck: Boolean, val device: String) {
    /** Under the count: what the missing parts do on the device. */
    val line: String get() = if (stuck) stuckAtAMissingPart(device) else mayShowGlitches(device)
}

/** The prompt's line for a cast (CsPhoneMissingParts): the device gets zeros where parts are missing. */
fun mayShowGlitches(name: String) = "$name may show glitches."

/** In its place once the gap watch couldn't send the device past one (L97, L104). */
fun stuckAtAMissingPart(name: String) = "$name is stuck at a missing part."

/** One part, for a moment, in the remote's state line: the player's "A missing part was skipped" for a cast (L94). */
fun aMissingPart(name: String) = "A missing part: $name may glitch"

/**
 * Under "Casting to Kitchen display" while the next release starts from the missing-parts prompt or the slow-stream banner
 * (L84, L94, L135): the one playing goes on there meanwhile, and the next one starts where it was ([positionMs]).
 */
fun goesOnThere(positionMs: Long) = "The release playing goes on there until this one is ready, at ${mmss(positionMs)}."
