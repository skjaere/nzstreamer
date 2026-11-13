package io.skjaere.usenet.link

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * What a phone and a TV say to each other, protocol v1 (L25), one message a frame, its type in `t` ([LinkCodec]). The
 * type names and field names are the wire's and never change within v1 (G7): a later version only adds, and what it
 * adds always has a default here, so an older reader ignores a field it doesn't know, reads a type it doesn't know as
 * [Unknown] and a value it doesn't know in any enum as that enum's `Unknown` (L26).
 *
 * Times cross as ages, never as dates: `ageMs` is how long ago, by the sender's clock, as the message is sent, since a
 * TV box's clock may be wrong. Nothing here holds a key or an id of one: the keys come from TLS (L23). Nor anything of a
 * TV's debrid accounts: what crosses of them is booleans, an enum, step names and a count (L223), never a service, an
 * account, a hash or a link.
 *
 * A message prints as its type alone: its fields may hold a title or a name, and none may reach a log by a slip (L13).
 */
@Serializable
sealed class LinkMessage {
    final override fun toString(): String = javaClass.simpleName

    /** A phone's command to a TV: its [seq], which the TV's [Ack] or [Rejected] answers. */
    interface Command {
        val seq: Int
    }

    /**
     * Both ways, first: after TLS the TV sends its hello, and the phone answers with its own. [pv] is the newest protocol
     * version this end speaks and [minPv] the oldest; the highest both speak is used (L26). [name] is the device's own
     * (L28): the receiver treats it as untrusted. To a phone it hasn't paired, the TV says its name and versions alone;
     * to a paired one it adds [app], [ready] and [session].
     */
    @Serializable
    @SerialName("hello")
    data class Hello(
        val pv: Int,
        val minPv: Int,
        val role: Role,
        val name: String,
        /** The app's version. */
        val app: String? = null,
        /** The phone's: what it connects for. */
        val purpose: Purpose? = null,
        /** The TV's, to a paired phone: whether it can play. */
        val ready: Ready? = null,
        /** The TV's, to a paired phone: the play now, if any. */
        val session: Session? = null,
    ) : LinkMessage() {
        /**
         * Whether the TV has a usable Usenet provider ([provider]), a Newznab indexer switched on ([indexer]) and a TMDB
         * key. [debrid] (D2, L223): it plays from its debrid service, an account on and a torrent indexer switched on.
         * [needs]: nothing plays there and only the debrid way is under way, so what that way lacks first; null when
         * [provider] and [indexer] say what is missing. Both left at their defaults add nothing to the hello's bytes,
         * and a phone from before D2 ignores them (L26).
         */
        @Serializable
        data class Ready(
            val provider: Boolean,
            val indexer: Boolean,
            val tmdb: Boolean,
            val debrid: Boolean = false,
            val needs: Need? = null,
        )

        /** The play's session [id], [mine] when this phone started it. */
        @Serializable
        data class Session(val id: String, val mine: Boolean = false)
    }

    /** Why this end won't go on with the peer; the connection closes. */
    @Serializable
    @SerialName("refused")
    data class Refused(val reason: Refusal) : LinkMessage()

    /**
     * Pairing (L22), the TV's word to a phone that came to pair while another phone pairs: it waits its turn, and its
     * ceremony starts with a [PairCommit] once the other's ends.
     */
    @Serializable
    @SerialName("pair-wait")
    data object PairWait : LinkMessage()

    /** Pairing (L21), the TV's commitment to its nonce: 64 hex digits of SHA-256. */
    @Serializable
    @SerialName("pair-commit")
    data class PairCommit(val c: String) : LinkMessage()

    /** Pairing, the phone's nonce: 64 hex digits, 32 bytes. */
    @Serializable
    @SerialName("pair-nonce")
    data class PairNonce(val n: String) : LinkMessage()

    /** Pairing, the TV's nonce, revealed: 64 hex digits, 32 bytes. */
    @Serializable
    @SerialName("pair-reveal")
    data class PairReveal(val n: String) : LinkMessage()

    /** Pairing, the phone's answer to the numbers: [yes], they match. */
    @Serializable
    @SerialName("pair-confirm")
    data class PairConfirm(val yes: Boolean) : LinkMessage()

    /** Pairing, the TV's last word: saved ([ok]), or why not. */
    @Serializable
    @SerialName("pair-result")
    data class PairResult(val ok: Boolean, val reason: PairEnd? = null) : LinkMessage()

    /** Phone to TV: play [episode] or [movie], from where the phone left it ([from], when it has a place for it). */
    @Serializable
    @SerialName("play")
    data class Play(
        override val seq: Int,
        val episode: Episode? = null,
        val movie: Movie? = null,
        val from: From? = null,
    ) : LinkMessage(), Command {
        /** An episode, with what the TV's search needs without a TMDB key of its own (L6). Dates are ISO 8601. */
        @Serializable
        data class Episode(
            val showTmdbId: Int,
            val showName: String,
            val showYear: Int? = null,
            val aliases: List<String> = emptyList(),
            val tvdb: Int? = null,
            val tmdb: Int? = null,
            val imdb: String? = null,
            val tvmaze: Int? = null,
            val season: Int,
            val episode: Int,
            val episodeName: String? = null,
            val airDate: String? = null,
            val runtimeMinutes: Int? = null,
            val stillPath: String? = null,
            val aired: Boolean = true,
            val originalLanguage: String? = null,
            val seasonEpisodes: Int? = null,
        ) {
            override fun toString() = "Episode"
        }

        /** A movie, as its page knows it. Dates are ISO 8601. */
        @Serializable
        data class Movie(
            val tmdbId: Int,
            val title: String,
            val year: Int? = null,
            val imdb: String? = null,
            val runtimeMinutes: Int? = null,
            val releaseDate: String? = null,
            val released: Boolean = true,
            val originalLanguage: String? = null,
        ) {
            override fun toString() = "Movie"
        }

        /** The phone's own place for it: where it stopped, whether it was watched, and how long ago that was saved. */
        @Serializable
        data class From(val positionMs: Long, val watched: Boolean, val ageMs: Long)
    }

    /** Phone to TV: stop the start of [session] (its play sheet). */
    @Serializable
    @SerialName("cancel")
    data class Cancel(override val seq: Int, val session: String) : LinkMessage(), Command

    @Serializable
    @SerialName("pause")
    data class Pause(override val seq: Int, val session: String) : LinkMessage(), Command

    /** Play on after a pause (the transport's, not the app's Resume). */
    @Serializable
    @SerialName("resume")
    data class Resume(override val seq: Int, val session: String) : LinkMessage(), Command

    @Serializable
    @SerialName("seek-to")
    data class SeekTo(override val seq: Int, val session: String, val positionMs: Long) : LinkMessage(), Command

    @Serializable
    @SerialName("seek-by")
    data class SeekBy(override val seq: Int, val session: String, val deltaMs: Long) : LinkMessage(), Command

    /** Phone to TV: leave the player. */
    @Serializable
    @SerialName("stop")
    data class Stop(override val seq: Int, val session: String) : LinkMessage(), Command

    /** Phone to TV: this phone forgot the TV; the TV removes it too (L30). */
    @Serializable
    @SerialName("unpair")
    data object Unpair : LinkMessage()

    /** Phone to TV: these [Final]s are applied (or dropped); the TV lets them go. */
    @Serializable
    @SerialName("final-ack")
    data class FinalAck(val ids: List<String>) : LinkMessage()

    @Serializable
    @SerialName("ping")
    data object Ping : LinkMessage()

    /** TV to phone: the command [seq] is under way, in [session]. */
    @Serializable
    @SerialName("ack")
    data class Ack(val seq: Int, val session: String? = null) : LinkMessage()

    /** TV to phone: the command [seq] won't be done, and why. */
    @Serializable
    @SerialName("rejected")
    data class Rejected(val seq: Int, val reason: Rejection) : LinkMessage()

    /**
     * TV to phone: how [session]'s play is going. Titled ([title], [subtitle], [watchKey]) only for the phone that started
     * it ([mine]); other paired phones learn the state alone (L34). [continued]: the session's next play is starting (the
     * next episode, after one that played). [failure] is the TV's own words for a failed play, never a library's text.
     * While the play starts, [sheet] mirrors the TV's play sheet (L37), for the phone that started it alone.
     */
    @Serializable
    @SerialName("status")
    data class Status(
        val session: String,
        val state: Playback,
        val mine: Boolean = false,
        val continued: Boolean = false,
        val title: String? = null,
        val subtitle: String? = null,
        val watchKey: String? = null,
        val positionMs: Long? = null,
        val durationMs: Long? = null,
        val failure: String? = null,
        val sheet: Sheet? = null,
    ) : LinkMessage() {
        /**
         * The TV's play sheet as a phone shows it (L37): only what the sheet shows, as text. Every failure in it is the
         * TV's words, written from the failure's kind and counts, never a library's or a server's own, which can name a
         * provider's host; no indexer's id, name or address crosses, nor a release's link. Which fields are set depends on
         * [kind], as the TV's sheet of that kind shows them.
         */
        @Serializable
        data class Sheet(
            val kind: SheetKind,
            val title: String,
            val subtitle: String? = null,
            /** Searching: what it does now. Running: what the search found ("Searched 2 indexers by TVDB id · 6 found"). */
            val line: String? = null,
            /** Running: the warning above the steps ("Release incomplete · trying release 3 of 3"). */
            val notice: String? = null,
            /** Running: the steps reached, in order; a step not reached yet isn't here. */
            val steps: List<Step> = emptyList(),
            /** Running: which release is tried, of how many. */
            val attempt: Int = 0,
            val of: Int = 0,
            /** Running, and Choosing: the release's name. */
            val release: String? = null,
            /** Running: what the release is ("2160p", "HEVC 10-bit", "7.4 GB"). */
            val chips: List<String> = emptyList(),
            /** Running: the TV's own check says the release plays there (L10). */
            val playsHere: Boolean = false,
            /** Choosing: what the play looks for in a release that names no file for it ("Looking for S02E04 'Low Water'"). */
            val lookingFor: String? = null,
            /** No release: what each search did. */
            val stages: List<Line> = emptyList(),
            /** No release: the note under the searches. */
            val note: String? = null,
            /** No release: releases found that the TV can't play, the first of [hiddenCount], each with why. */
            val hidden: List<Hidden> = emptyList(),
            val hiddenCount: Int = 0,
            /** Failed: each release tried, and why it failed. */
            val failures: List<Line> = emptyList(),
            /** Failed: releases skipped, as found incomplete before, each with its line. */
            val skippedBefore: List<Line> = emptyList(),
            /** Failed: releases left, to save the TV's indexer's downloads. */
            val notTried: Int = 0,
            /** Failed: releases skipped as their indexer refused a download. */
            val skipped: Int = 0,
            /** No provider: why no Usenet provider on the TV can be used. */
            val noProvider: String? = null,
            /** Searching (D2, L223): the rows the TV's own sheet lists while it searches, before a release names its steps. */
            val rows: List<SheetStep> = emptyList(),
            /** Failed (D2, #316): of [notTried], the releases left as what they play from failed on the TV. */
            val noRoute: Int = 0,
        ) {
            override fun toString() = "Sheet($kind)"
        }

        /** A step of the start ([SheetStep]): how far it is, or how long it took, or why it failed (the TV's words). */
        @Serializable
        data class Step(
            val step: SheetStep,
            val state: StepState,
            /** Running: [done] of [total], when the step can tell. */
            val done: Int? = null,
            val total: Int? = null,
            /** Done or failed: how long it took. */
            val millis: Long? = null,
            /** Done: what it found ("2.9 MB"), as the sheet shows it. */
            val detail: String? = null,
            val failure: String? = null,
        ) {
            override fun toString() = "Step($step, $state)"
        }

        /** A line of two parts: a release and why, or a search and what it found. */
        @Serializable
        data class Line(val what: String, val why: String) {
            override fun toString() = "Line"
        }

        /** A release the TV found and can't play: its name, and its reasons as the release list's chips say them. */
        @Serializable
        data class Hidden(val title: String, val reasons: List<String> = emptyList()) {
            override fun toString() = "Hidden"
        }
    }

    /**
     * TV to phone: where a play the phone started stopped, for its progress (W5, L33), kept on the TV until a [FinalAck]
     * names [id]. The ids say what it was, so the phone works its own watch key out again; [ageMs] says when.
     */
    @Serializable
    @SerialName("final")
    data class Final(
        val id: String,
        val session: String,
        val watchKey: String,
        val kind: FinalKind,
        val title: String,
        val showTmdbId: Int? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val movieTmdbId: Int? = null,
        val positionMs: Long,
        val durationMs: Long,
        val watched: Boolean,
        val ageMs: Long,
    ) : LinkMessage()

    @Serializable
    @SerialName("pong")
    data object Pong : LinkMessage()

    /**
     * A type this version doesn't know, from a newer peer: its [seq], if it had one, so a command can be answered with
     * `Rejected{unsupported}`. Never sent.
     */
    @Serializable
    @SerialName("unknown")
    data class Unknown(val seq: Int? = null) : LinkMessage()
}

/** Which end a hello is from. */
@Serializable(with = Role.Wire::class)
enum class Role(val wire: String) {
    Tv("tv"),
    Phone("phone"),
    Unknown("unknown"),
    ;

    internal object Wire : WireEnum<Role>("Role", Role.entries, Unknown, Role::wire)
}

/** What a phone connects for. */
@Serializable(with = Purpose.Wire::class)
enum class Purpose(val wire: String) {
    Use("use"),
    Pair("pair"),
    Unknown("unknown"),
    ;

    internal object Wire : WireEnum<Purpose>("Purpose", Purpose.entries, Unknown, Purpose::wire)
}

/** Why a connection is refused (L23, L26). A phone reads [Unknown] as "Update NZStreamer on this phone". */
@Serializable(with = Refusal.Wire::class)
enum class Refusal(val wire: String) {
    /** The TV doesn't know this phone's key. */
    NotPaired("not-paired"),

    /** The phone came to pair, and the TV isn't taking pairings. */
    NotPairing("not-pairing"),

    /** No room: the TV is pairing with another phone, or holds as many links as it takes. */
    Busy("busy"),

    /** Too many failed pairings from this address since Pair a phone opened (L22). */
    TooMany("too-many"),

    /** The TV's version is too old for the phone. */
    UpdateTv("update-tv"),

    /** The phone's version is too old for the TV. */
    UpdatePhone("update-phone"),
    Unknown("unknown"),
    ;

    internal object Wire : WireEnum<Refusal>("Refusal", Refusal.entries, Unknown, Refusal::wire)
}

/** Why a TV won't do a command (L32). A phone reads [Unknown] as "Couldn't start on <TV>". */
@Serializable(with = Rejection.Wire::class)
enum class Rejection(val wire: String) {
    /** The TV's "Set up with your phone" page is open, or a pairing question shows. */
    BusySetup("busy-setup"),
    NotReadyProvider("not-ready-provider"),
    NotReadyIndexer("not-ready-indexer"),

    /** D2 (L223): only the debrid way is under way on the TV, and no debrid service is set up there. */
    NotReadyService("not-ready-service"),

    /** D2: a debrid service is set up on the TV, and none is on (switched off, or its key refused or unreadable). */
    NotReadyServiceOff("not-ready-service-off"),

    /** D2: the TV's debrid service is on, and no torrent indexer is switched on there. */
    NotReadyTorrentIndexer("not-ready-torrent-indexer"),

    /** The command's session isn't the TV's play now. */
    NotCurrent("not-current"),

    /** Too many commands, or plays, too fast (L24). */
    TooFast("too-fast"),

    /** A command this version doesn't know, or with a field outside the checks. */
    Unsupported("unsupported"),
    Unknown("unknown"),
    ;

    internal object Wire : WireEnum<Rejection>("Rejection", Rejection.entries, Unknown, Rejection::wire)
}

/**
 * What a TV set up only for its debrid service lacks first (D2, L223), in a hello's [LinkMessage.Hello.Ready.needs]. It
 * names no service: a phone never learns which the TV has. A phone reads [Unknown], a newer TV's, by `provider` and
 * `indexer` alone.
 */
@Serializable(with = Need.Wire::class)
enum class Need(val wire: String) {
    Service("service"),
    ServiceOff("service-off"),
    TorrentIndexer("torrent-indexer"),
    Unknown("unknown"),
    ;

    internal object Wire : WireEnum<Need>("Need", Need.entries, Unknown, Need::wire)
}

/** A play's state on the TV (L34). A phone reads [Unknown] as the last state it knew. */
@Serializable(with = Playback.Wire::class)
enum class Playback(val wire: String) {
    Starting("starting"),
    Buffering("buffering"),
    Playing("playing"),
    Paused("paused"),

    /** The player's "Playback stopped". */
    Failed("failed"),
    Stopped("stopped"),

    /** Another play took its place (L18). */
    Replaced("replaced"),
    Unknown("unknown"),
    ;

    internal object Wire : WireEnum<Playback>("Playback", Playback.entries, Unknown, Playback::wire)
}

/** What the TV's play sheet shows (L37). A phone reads [Unknown] as the last kind it knew. */
@Serializable(with = SheetKind.Wire::class)
enum class SheetKind(val wire: String) {
    /** Searching the indexers, or looking the play up. */
    Searching("searching"),

    /** Trying a release, step by step. */
    Running("running"),

    /** Asking which file of a release is the one to play: on the TV, with its remote. */
    Choosing("choosing"),
    NoRelease("no-release"),
    Failed("failed"),

    /** Nothing tried: no Usenet provider on the TV can be used. */
    NoProvider("no-provider"),
    Unknown("unknown"),
    ;

    internal object Wire : WireEnum<SheetKind>("SheetKind", SheetKind.entries, Unknown, SheetKind::wire)
}

/** A step of a release's start, as the play sheet names them. A phone reads one it doesn't know as [Unknown] and leaves it out. */
@Serializable(with = SheetStep.Wire::class)
enum class SheetStep(val wire: String) {
    /** Whatever a debrid service needs before a link (L203, L206): a TV's torrent start. A phone before D1 reads it as [Unknown]. */
    Prepare("prepare"),
    FetchNzb("fetch-nzb"),
    CheckAvailability("check-availability"),
    Open("open"),
    ProbeTracks("probe-tracks"),
    Unknown("unknown"),
    ;

    internal object Wire : WireEnum<SheetStep>("SheetStep", SheetStep.entries, Unknown, SheetStep::wire)
}

/** Where a step of a release's start is. */
@Serializable(with = StepState.Wire::class)
enum class StepState(val wire: String) {
    Running("running"),
    Done("done"),
    Failed("failed"),
    Unknown("unknown"),
    ;

    internal object Wire : WireEnum<StepState>("StepState", StepState.entries, Unknown, StepState::wire)
}

/** What a [LinkMessage.Final] is of. */
@Serializable(with = FinalKind.Wire::class)
enum class FinalKind(val wire: String) {
    Episode("episode"),
    Movie("movie"),
    Unknown("unknown"),
    ;

    internal object Wire : WireEnum<FinalKind>("FinalKind", FinalKind.entries, Unknown, FinalKind::wire)
}

/** Why a pairing ended without one. */
@Serializable(with = PairEnd.Wire::class)
enum class PairEnd(val wire: String) {
    /** No or Cancel on the TV. */
    Declined("declined"),

    /** The TV's question went unanswered. */
    TimedOut("timed-out"),

    /** Pair a phone closed meanwhile. */
    Closed("closed"),

    /** The TV couldn't save the pairing. */
    NotSaved("not-saved"),
    Unknown("unknown"),
    ;

    internal object Wire : WireEnum<PairEnd>("PairEnd", PairEnd.entries, Unknown, PairEnd::wire)
}

/**
 * An enum as the link writes it, by its wire names. A reader takes a name it doesn't know as [unknown]: a newer peer's
 * value, which kotlinx-serialization would otherwise fail the whole message on (L26).
 */
internal abstract class WireEnum<E : Enum<E>>(name: String, private val values: List<E>, private val unknown: E, private val wire: (E) -> String) : KSerializer<E> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("io.skjaere.usenet.link.$name", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: E) = encoder.encodeString(wire(value))

    override fun deserialize(decoder: Decoder): E {
        val text = decoder.decodeString()
        return values.firstOrNull { it != unknown && wire(it) == text } ?: unknown
    }
}
