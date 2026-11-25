package app.nzstreamer.plugin.sportarr.match

import app.nzstreamer.plugin.sportarr.model.Family
import app.nzstreamer.plugin.sportarr.model.Part
import app.nzstreamer.plugin.sportarr.model.Parts
import app.nzstreamer.plugin.sportarr.model.SessionKind
import app.nzstreamer.plugin.sportarr.model.SportsEvent
import app.nzstreamer.plugin.sportarr.ui.Words
import io.skjaere.usenet.plugin.FileInfo
import io.skjaere.usenet.plugin.PluginFilePicker

/**
 * Which file of a release plays a part (L647). Of its videos, samples aside, the one whose file name carries the part's
 * or the session's words ([wanted]) and no non-event, pre-show or post-show word, the largest should several; else the
 * release's only video, where its name says it is nothing else (no such word, nor another part's or session's); else
 * none, and the play fails with [noMatch]. Never the largest alone: a weekend's release holds build-ups and analyses
 * beside its sessions. File names are read as release names are, against the event's own words ([context]).
 *
 * A release is taken whole ([takesWhole]) where the judge ([whole]) accepted its name as the part itself; a full event or
 * a weekend's release holds others besides.
 */
class PartPicker(
    label: String,
    private val wanted: Wanted,
    private val context: NameContext,
    private val whole: (releaseName: String) -> Boolean,
) : PluginFilePicker {
    /** "No file for the Main Card in this release". */
    override val noMatch: String = Words.noFile(label)

    override fun pick(files: List<FileInfo>): Int? {
        val videos = files.withIndex().filter { (_, file) -> file.isVideo && !sample(file.path) }
            .map { (index, file) -> Video(index, file.size, SportsName.read(fileName(file.path), context)) }
        val named = videos.filter { !nothingOfIt(it.name) && carries(it.name) }
        named.maxByOrNull { it.size }?.let { return it.index }
        val only = videos.singleOrNull() ?: return null
        return only.index.takeIf { !nothingOfIt(only.name) && !othersWords(only.name) }
    }

    override fun takesWhole(releaseName: String): Boolean = whole(releaseName)

    /** What names the part's file. */
    sealed interface Wanted {
        /** A motorsport session, by its kind and number ("Practice 2" or "FP2"; "Race" has none). */
        data class Session(val kind: SessionKind, val number: Int?) : Wanted

        /** A card's part, by its id. */
        data class CardPart(val id: String) : Wanted

        /** An event of one part, which no word names: only the release's only video is it. */
        data object Whole : Wanted
    }

    /** A video of the release: where it is in the list, its size, and its name as read. */
    private class Video(val index: Int, val size: Long, val name: SportsName)

    /** A name that says it is no part: a programme about the event, a part of a game, a show around a card's. */
    private fun nothingOfIt(name: SportsName): Boolean =
        name.nonEvent.isNotEmpty() || name.partial.isNotEmpty() ||
            name.parts.any { it == PartWord.PostShow || (it == PartWord.PreShow && wanted != Wanted.CardPart(Parts.PRE_SHOW)) }

    /** Whether [name] carries the part's words: its session's kind and number, or its card part's word. */
    private fun carries(name: SportsName): Boolean = when (wanted) {
        is Wanted.Session -> name.sessions.any { it.kind == wanted.kind && it.number == wanted.number }
        is Wanted.CardPart -> name.partNamed.any { it.names(wanted.id) }
        Wanted.Whole -> false
    }

    /**
     * Whether [name] carries another part's or session's words, which a full event's "Full Event" is not. An event of one
     * part has no other.
     */
    private fun othersWords(name: SportsName): Boolean = when (wanted) {
        is Wanted.Session -> name.sessions.any { it.kind != wanted.kind || it.number != wanted.number }
        is Wanted.CardPart -> name.partNamed.any { it != PartWord.FullEvent && !it.names(wanted.id) }
        Wanted.Whole -> false
    }

    /** Whether this part word names the part [id]: its own, or "PPV" for a card's main card or a show's main show. */
    private fun PartWord.names(id: String): Boolean = this.id == id || (this == PartWord.Ppv && id in MAIN_PARTS)

    companion object {
        /**
         * [part] of [event]'s picker, reading file names with [matcher]'s words and taking a release whole where [matcher]'s
         * judge, after the shared rules, accepts its name as the part itself. Building it searches nothing.
         */
        fun of(matcher: FamilyMatcher, event: SportsEvent, part: Part): PartPicker {
            val session = event.session
            val wanted = when {
                event.family == Family.Motorsport && session != null ->
                    // The session's number, from its label as the parser reads one ("Practice 2"); the race has none.
                    Wanted.Session(session.kind, SportsName.read(session.label).sessions.firstOrNull { it.kind == session.kind }?.number)
                part.id in CARD_PARTS -> Wanted.CardPart(part.id)
                else -> Wanted.Whole
            }
            return PartPicker(part.label, wanted, matcher.context(event)) { name ->
                (SharedRules.judge(matcher, event, part, name, posted = null, size = null) as? Verdict.Accept)?.whole == true
            }
        }

        private val CARD_PARTS = setOf(Parts.EARLY_PRELIMS, Parts.PRELIMS, Parts.MAIN_CARD, Parts.PRE_SHOW, Parts.MAIN_SHOW)
        private val MAIN_PARTS = setOf(Parts.MAIN_CARD, Parts.MAIN_SHOW)

        /** A sample: "sample" a word of the file's name, or the name of a folder it is in. */
        private fun sample(path: String): Boolean {
            val segments = path.split('/', '\\')
            return Tokens.of(segments.last()).any { it.word == SAMPLE } || segments.dropLast(1).any { Tokens.fold(it.trim()) == SAMPLE }
        }

        /** The file's name without its folders and its extension ("mkv"). */
        private fun fileName(path: String): String {
            val name = path.substringAfterLast('/').substringAfterLast('\\')
            val extension = name.substringAfterLast('.', missingDelimiterValue = "")
            val isExtension = extension.length in 2..4 && extension.all(Char::isLetterOrDigit) && extension.any(Char::isLetter)
            return if (isExtension && name.length > extension.length + 1) name.dropLast(extension.length + 1) else name
        }

        private const val SAMPLE = "sample"
    }
}
