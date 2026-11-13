package io.skjaere.usenet.app.play

/**
 * Where the player's moment label goes beside a full-size overlay in the bottom-end slot (L6, QlPhoneCollisions): the
 * missing-parts prompt, the next-episode card or "Playback stopped". On a short landscape phone the prompt's top comes up
 * level with the label, centred under the top bar; where the two would touch, the label centres on the room beside the
 * overlay instead, left of it, and keeps inside that room: at large text, where its words are wider, they are cut short
 * rather than run off the screen or under the overlay. Elsewhere it stays centred on the screen.
 */
internal object MomentLabelPlace {
    /** A view's place on the screen, in pixels. */
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /** Where the label goes: [shift] sideways, at most [maxWidth] wide (null: as wide as its words). */
    data class Place(val shift: Float, val maxWidth: Int?)

    /**
     * Where [label], as wide as its words and centred on the screen, goes for [overlay] (null: none shows): where they
     * would touch, to the middle of the room left of the overlay, from [safeLeft] (the system bars' and the cutout's
     * inset there) to the overlay, [margin] in from each, and no wider than that room. Elsewhere where it is.
     */
    fun place(label: Box, overlay: Box?, safeLeft: Int, margin: Int): Place {
        if (overlay == null || overlay.top >= label.bottom || overlay.left >= label.right) return Place(0f, null)
        val room = (overlay.left - safeLeft - 2 * margin).coerceAtLeast(0)
        val shift = (safeLeft + overlay.left) / 2f - (label.left + label.right) / 2f
        return Place(shift, room.takeIf { label.right - label.left > it })
    }
}
