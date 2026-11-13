package io.skjaere.usenet.app.play

/**
 * What the player shows in picture-in-picture or full screen, kept apart from the player so it can be tested (L3, L4,
 * L12): [pinned] while it is in the window (or going in), [panel] while a side panel is open, [failed] while "Playback
 * stopped" is up.
 *
 * In the window only the picture shows (PipWindowShapes, PipWindowStates): in Fit, its subtitles larger, and media3's
 * spinner over it while it waits for the stream; no controls, top bar, overlays or words, and nothing for TalkBack, which
 * reaches the window only through Android's own menu. A failure there is one line on black, "Playback stopped", the panel
 * waiting for the window to be expanded. The overlays that wait meanwhile hold their clocks: the next-episode card's
 * countdown (W1), the missing-parts prompt's time to answer, the slow-stream banner's count. Expanded, the player is as it
 * was left (PipPlayerExpand): the size chosen, the user's subtitles, the controls down until a tap, the panel or the card
 * back where due.
 */
data class PipScene(val pinned: Boolean, val panel: Boolean = false, val failed: Boolean = false) {
    /** Whether media3's controls may show (its useController): not in the window, nor under a side panel or "Playback stopped" (M1). */
    val controls: Boolean get() = !pinned && !panel && !failed

    /** "Playback stopped" as its panel, full screen. */
    val failurePanel: Boolean get() = failed && !pinned

    /** "Playback stopped" as one line on black, in the window. */
    val stoppedLine: Boolean get() = failed && pinned

    /**
     * Whether the overlays show: the next-episode card, the skip button, the missing-parts prompt, the slow-stream banner,
     * the moment label under the top bar and the side mark. Not in the window.
     */
    val overlays: Boolean get() = !pinned

    /** Whether the overlays can't be seen, and their clocks hold: in the window, or under a side panel. */
    val unseen: Boolean get() = pinned || panel

    /** The picture's size: the one [chosen], or Fit in the window, which has the picture's shape (Fill or a zoom would cut it). */
    fun zoom(chosen: VideoZoom): VideoZoom = if (pinned) VideoZoom() else chosen

    /** The subtitles' size: the user's caption size (null), or in the window [WINDOW_SUBTITLES]. */
    val subtitles: Subtitles? get() = WINDOW_SUBTITLES.takeIf { pinned }

    /** Whether TalkBack and the other accessibility services see the player's views: not in the window (M11). */
    val accessible: Boolean get() = !pinned

    /** Subtitles [textFraction] of the picture's height, [bottomFraction] of it up from its bottom (media3's SubtitleView). */
    data class Subtitles(val textFraction: Float, val bottomFraction: Float)

    companion object {
        /** PipWindowShapes: 10.9 dp in the default 16:9 window, where Android's 5.33 % and 8 % would give 6.4 dp. */
        val WINDOW_SUBTITLES = Subtitles(textFraction = 0.09f, bottomFraction = 0.04f)
    }
}
