package io.skjaere.usenet.app.link

import io.skjaere.usenet.link.LinkMessage.Status

/**
 * L37: the TV's lines as a phone shows them. The TV's play sheet speaks of its own indexers and providers ("your
 * indexers", "Searching 2 indexers", "this device"), which on the phone are the TV's, not the phone's: "the TV's
 * indexers", "the TV's providers", "the TV". Only the lines are rewritten: titles, release names and chips stay as the
 * TV sent them, whatever they hold ("Your Lighthouse" stays itself). Each rule is a phrase the app's own line builders write,
 * so a line holding none stays as it is.
 */
object TvWords {
    /** [text], one of the TV's lines, said of the TV. */
    fun line(text: String): String = RULES.fold(text) { line, (phrase, said) -> phrase.replace(line, said) }

    /** [sheet] with its lines said of the TV; everything else as sent. */
    fun sheet(sheet: Status.Sheet): Status.Sheet = sheet.copy(
        line = sheet.line?.let(::line),
        notice = sheet.notice?.let(::line),
        stages = sheet.stages.map { Status.Line(line(it.what), line(it.why)) },
        note = sheet.note?.let(::line),
        hidden = sheet.hidden.map { it.copy(reasons = it.reasons.map(::line)) },
        skippedBefore = sheet.skippedBefore.map { it.copy(why = line(it.why)) },
        noProvider = sheet.noProvider?.let(::line),
    )

    /**
     * The phrases, as the app's builders write them (SearchProgress, the coordinator, MissingReleases, NoUsableProvider,
     * ReleaseReason, AttemptFailure).
     */
    private val RULES = listOf(
        // "Searching 2 indexers for…", "Searched 2 indexers by TVDB id", "Searched 1 indexer".
        Regex("\\b(Searching|Searched) (\\d+) indexer") to "$1 the TV's $2 indexer",
        Regex("\\byour indexers\\b") to "the TV's indexers",
        Regex("\\byour indexer's\\b") to "the TV's indexer's",
        Regex("\\byour providers\\b") to "the TV's providers",
        Regex("\\bYour Usenet provider") to "The TV's Usenet provider",
        Regex("\\bcan't be read here\\b") to "can't be read on the TV",
        Regex("\\bAbove your (\\d+) Mbps") to "Above the TV's $1 Mbps",
        Regex("\\bis above this screen\\b") to "is above the TV's screen",
        Regex("\\bPlays on this device\\b") to "Plays on the TV",
        // The TV's debrid service's (L226): ReleaseReason's, NoUsableProvider's, the failures' own words as a skipped
        // release's line ("won’t" keeps its curly apostrophe), and MissingReleases' notice. None names the service.
        Regex("\\byour debrid service\\b") to "the TV's debrid service",
        Regex("\\bYour debrid service\\b") to "The TV's debrid service",
        Regex("\\byour debrid plan\\b") to "the TV's debrid plan",
        Regex("\\bthe debrid service\\b") to "the TV's debrid service",
        Regex("\\bThe debrid service\\b") to "The TV's debrid service",
        Regex("\\bfrom the service\\b") to "from the TV's debrid service",
    )
}
