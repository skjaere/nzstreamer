package io.skjaere.usenet.app.casting

/** The introduction's title (W48, L123; CsPhoneIntro). */
const val CAST_INTRO_TITLE = "Play on your TV from here"

/** What it says, naming both kinds of device: Cast devices, which the phone streams to, and your TVs with NZStreamer. */
const val CAST_INTRO_TEXT = "Tap the Cast button to play on Cast devices, such as a Chromecast or a TV with Google Cast, and on your TVs with " +
    "NZStreamer. They show up when they’re on this Wi-Fi."

/** The button that closes it. */
const val CAST_INTRO_GOT_IT = "Got it"

/**
 * When the introduction to the Cast button shows (W24, W48, L110, L123; CsPhoneIntro): once, the first time the app opens
 * on Wi-Fi after the update, on the first screen with the button, and never over anything else. It starts nothing of
 * Google's: it points at the button, and is kept shown in the app's own settings. Pure.
 */
object CastIntroRule {
    /** What may be over the screen, each of which keeps it away: never over a list, a prompt or the player (W48). */
    enum class Over {
        /** Play on's list. */
        List,

        /** One of Play on's questions (a TV to pair again, a speaker's). */
        Question,

        /** The play sheet, expanded, or a TV's start shown in its place. */
        PlaySheet,

        /** A cast's missing-parts prompt. */
        CastPrompt,

        /** A cast's slow-stream banner. */
        Banner,

        /** A line said as a play on a TV or a Cast device ended. */
        Said,

        /** The player in front. */
        Player,
    }

    /**
     * Whether it shows now: on a phone or a tablet, never a [television]; with Google Play services there ([playServices],
     * null while not yet read); on Wi-Fi or Ethernet ([onLan]), where Cast devices show up; with setup done ([setupDone]),
     * so never over the first-run screen; once a screen showing the button has [placed] it; with nothing [over] the
     * screen; and only while it has never been [shown] (null while the flag is unread).
     */
    fun show(television: Boolean, playServices: Boolean?, onLan: Boolean, setupDone: Boolean, placed: Boolean, over: Set<Over>, shown: Boolean?): Boolean =
        !television && playServices == true && onLan && setupDone && placed && over.isEmpty() && shown == false

    /**
     * What TalkBack reads once, as it appears (L123, CsPhoneTalkBackC2): its words, with where the button is in place of
     * the circle it can't see, beside Settings on [home], else at the top right, where every other screen has it.
     */
    fun spoken(home: Boolean): String {
        val where = if (home) "at the top beside Settings" else "at the top right"
        return "$CAST_INTRO_TITLE. Tap the Cast button, $where, to play on Cast devices, such as a Chromecast or a TV with Google Cast, and on " +
            "your TVs with NZStreamer. They show up when they’re on this Wi-Fi."
    }
}
