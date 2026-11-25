package app.nzstreamer.plugin.sportarr.model

/**
 * How an event's releases are found and played (L643): slice A's families. Every sport not ruled yet is Generic, which
 * never plays a release by itself: the user picks one.
 */
enum class Family { TeamGame, Motorsport, FightCard, Generic }

/** A fight card's type (L643), which names its parts (L644). */
enum class CardType {
    /** "UFC 999: Quessel vs. Halde": early prelims, prelims and the main card. */
    Numbered,

    /** "UFC Fight Night: Brisk vs. Loam": prelims and the main card. */
    FightNight,

    /** A show that airs every week under one title: one part. */
    WeeklyShow,

    /** A bare "A vs. B" (boxing): one part. */
    Matchup,

    /** Any other named event (wrestling's): a pre-show and the main show. */
    Premium,
}
