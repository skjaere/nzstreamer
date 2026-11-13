package io.skjaere.usenet.releases

import kotlin.math.log10

/** A search result as the ranker needs it. [id] lets the caller map results back. */
data class ReleaseFacts(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val passworded: Boolean = false,
    /** The indexer's count: a torrent's snatches are on a scale of their own, so they count only among torrents (L176). */
    val grabs: Int? = null,
    val ageDays: Int? = null,
    /** From the indexer's MediaInfo scan; overrides what the name claims. */
    val mediaInfo: MediaInfo? = null,
    /** Where it plays from: [USENET], or [TORRENT] through the user's debrid service. */
    val source: String = USENET,
    /** A torrent's cache check, as it settled (L199); null for a Usenet release. */
    val availability: Availability? = null,
) {
    companion object {
        const val USENET = "usenet"
        const val TORRENT = "torrent"
    }
}

enum class QualityPreference(val height: Int?) { Auto(null), P2160(2160), P1080(1080), P720(720) }

data class RankingPrefs(
    val quality: QualityPreference = QualityPreference.Auto,
    /** ISO 639-1 codes, most preferred first. */
    val audioLanguages: List<String> = listOf("en"),
    val retentionDays: Int? = null,
    /**
     * The speed this device can stream at, in bits per second (Settings' "Maximum bitrate"); null when not set, for none on
     * a phone or for Cast. A release estimated above it is tried last, and among those that fit a higher bitrate ranks a
     * little higher. On a TV the larger releases of a standing go first with it or without it (W101), and it only holds
     * back those above it.
     */
    val maxBitrate: Long? = null,
    /**
     * Settings' "Maximum bitrate when casting" (W52, L132), in bits per second; null when not set. Only a ranking for a Cast
     * device ([RankingTarget.Cast]) reads it, what the phone's Wi-Fi carries to the device: a release estimated above it is
     * tried last there, as one above [maxBitrate] is, and the lower of the two set is the bitrate a higher one ranks up to.
     */
    val castMaxBitrate: Long? = null,
)

data class RankingContext(
    val device: DeviceCapabilities,
    val prefs: RankingPrefs = RankingPrefs(),
    /** Measured provider throughput; null until something has streamed. */
    val throughputBitsPerSecond: Long? = null,
    /** Runtime of one episode or the movie, from metadata when known; zero or less counts as unknown. */
    val runtimeMinutes: Int? = null,
    /**
     * The title's original language (ISO 639-1, from TMDB), which a release without an audio language
     * tag is taken to speak; null when not known (a search by name alone, no TMDB key): English then.
     */
    val originalLanguage: String? = null,
    /**
     * How many episodes the season has (TMDB's count), which a pack of that one season is taken to hold: its size over
     * this is an episode's, for its bitrate. Null when not known; a pack then gets no bitrate estimate.
     */
    val seasonEpisodes: Int? = null,
    /**
     * Whether the device is a TV (the app's form factor). There a release above 1080p plays only where a hardware decoder
     * plays its size, one whose HDR the screen doesn't show ranks lower, and the larger releases of a resolution, language
     * and HDR go first, a maximum set or not (W101), for this device ([target]) alone. False, a phone's or tablet's rules,
     * unless the app says so.
     */
    val tv: Boolean = false,
    /**
     * Whom the ranking is for: this device ([RankingTarget.Device]), or a Google Cast device a play goes to
     * ([RankingTarget.Cast]), whose profile [device] then is; [tv] stays the app's form factor.
     */
    val target: RankingTarget = RankingTarget.Device,
)

/** Whom releases are ranked for (L126). */
sealed interface RankingTarget {
    /** The phone, tablet or TV the app runs on, by its own decoders, screen and rules. */
    data object Device : RankingTarget

    /**
     * A Google Cast device, [name]d as Play on lists it. The phone can't check its player, so what its profile says it
     * can't play warns rather than hides: such a release is tried after every one that fits and stays castable by hand
     * (Terms 3.4.5). It prints without its name: a device's name reaches no log by a slip.
     */
    data class Cast(val name: String) : RankingTarget {
        override fun toString() = "Cast"
    }
}

enum class Severity { Blocking, Warning, Neutral, Positive }

/** Why a release ranked where it did. [text] is shown as a chip and written to the log. */
sealed class ReleaseReason(val severity: Severity, val text: String) {
    /**
     * The indexer says its archive needs a password: often wrong, and the password may come with the release (its NZB,
     * the indexer's download), so it is tried where its quality puts it, and fails as passworded if it needs one it lacks.
     */
    data object Passworded : ReleaseReason(Severity.Warning, "Passworded")
    data object DiscImage : ReleaseReason(Severity.Blocking, "Disc image")
    data class VideoUnsupported(val label: String) : ReleaseReason(Severity.Blocking, "$label: no decoder")
    data object DolbyVisionOnly : ReleaseReason(Severity.Blocking, "Dolby Vision only: no decoder")

    /**
     * Above 1080p only a hardware decoder keeps up, and none plays this release's size. On a TV it isn't played
     * ([blocking]); on a phone or tablet it is tried after every release that fits, as a
     * hidden release can't be played at all and their hardware marks are less sure.
     */
    data class NoHardwareDecoder(val label: String, val blocking: Boolean = true) :
        ReleaseReason(if (blocking) Severity.Blocking else Severity.Warning, "$label: no hardware decoder")
    data class AudioUnsupported(val label: String) : ReleaseReason(Severity.Blocking, "$label audio: no decoder")
    data object BeyondRetention : ReleaseReason(Severity.Blocking, "Older than provider retention")
    data class MayBuffer(val megabitsPerSecond: Int) : ReleaseReason(Severity.Warning, "May buffer: ~$megabitsPerSecond Mbps")
    data class AboveMaxBitrate(val megabitsPerSecond: Int) : ReleaseReason(Severity.Warning, "Above your $megabitsPerSecond Mbps")

    /** Above the maximum when casting (W52, L132), for a Cast device: tried last there, as one [AboveMaxBitrate] is, and castable by hand. */
    data class AboveCastMax(val megabitsPerSecond: Int) : ReleaseReason(Severity.Warning, "Above your $megabitsPerSecond Mbps for casting")
    data class PoorSource(val source: Source) : ReleaseReason(Severity.Warning, "Low quality source (${source.name})")
    data class AboveScreen(val resolution: Int) : ReleaseReason(Severity.Neutral, "${resolution}p is above this screen")

    /**
     * HDR this TV's screen doesn't show, so the picture may look washed out: the release ranks lower, still playable.
     * [missing] names the format when the screen shows some other HDR ("Dolby Vision on a screen without it"); null on
     * a screen without any.
     */
    data class HdrOnSdrScreen(val missing: Hdr? = null) :
        ReleaseReason(Severity.Warning, missing?.let { "${it.label} on a screen without it" } ?: "HDR on a screen without HDR")

    // A Cast device's reasons (L126): each names the device, and prints without it, as RankingTarget.Cast does.

    /** None of the audio the release's name gives ([label], as [AudioUnsupported] names it) plays on the Cast device. */
    data class NoSoundOn(val label: String, val device: String) : ReleaseReason(Severity.Warning, "$label audio: no sound on $device") {
        override fun toString() = "NoSoundOn($label)"
    }

    /** Its video ([label], the codec and bit depth) shows no picture on the Cast device. */
    data class NoPictureOn(val label: String, val device: String) : ReleaseReason(Severity.Warning, "$label: no picture on $device") {
        override fun toString() = "NoPictureOn($label)"
    }

    /**
     * Its size ([resolution]) is over the largest the Cast device shows of its video ([maxHeight]): "4K: Kitchen display
     * shows HD".
     */
    data class TooBigFor(val resolution: Int, val maxHeight: Int, val device: String) :
        ReleaseReason(Severity.Warning, "${sizeWord(resolution)}: ${shows(device, maxHeight)}") {
        override fun toString() = "TooBigFor($resolution, $maxHeight)"

        companion object {
            /** The largest the Cast [device] shows ([maxHeight]), as its chip and the play sheet say it: "Kitchen display shows HD". */
            fun shows(device: String, maxHeight: Int) = "$device shows ${shownWord(maxHeight)}"
        }
    }

    /** Dolby Vision without an HDR10 base, on a Cast device not seen showing Dolby Vision: played as plain HEVC, its colours are wrong. */
    data class DolbyVisionOnlyOn(val device: String) : ReleaseReason(Severity.Warning, "Dolby Vision only: wrong colours on $device") {
        override fun toString() = "DolbyVisionOnlyOn"
    }

    /**
     * Above 1080p on a Cast device not yet seen showing 4K (W53): it aims at 1080p until then, so the release ranks after
     * the 1080p ones, as one above this screen does, but nothing says it won't play there.
     */
    data class NotYet4k(val resolution: Int, val device: String) : ReleaseReason(Severity.Neutral, "${resolution}p: $device hasn’t played 4K yet") {
        override fun toString() = "NotYet4k($resolution)"
    }

    // A torrent's, from its cache check (L177): none names a service (W80).

    /** One of the user's debrid services has it cached: it plays from there. */
    data object Cached : ReleaseReason(Severity.Positive, "Cached")

    /** No service asked has it cached: never tried (W71). */
    data object NotCached : ReleaseReason(Severity.Blocking, "Not cached")

    /** No service said it has it, and one couldn't be asked: never tried, and checked again by the next search. */
    data object NotChecked : ReleaseReason(Severity.Blocking, "Not checked")

    /** From a torrent indexer whose "Use with your debrid service" is off: nothing of it went to a service. */
    data object NotForService : ReleaseReason(Severity.Blocking, "Not used with your debrid service")

    data class Language(val label: String) : ReleaseReason(Severity.Positive, label)
    data object Proper : ReleaseReason(Severity.Positive, "Proper/repack")
    data object PlaysOnDevice : ReleaseReason(Severity.Positive, "Plays on this device")
}

/** A release's size in a Cast device's chip: "4K" for 2160p, else its lines ("1440p"). */
private fun sizeWord(height: Int) = if (height == 2160) "4K" else "${height}p"

/** The largest a Cast device shows, as its chip says it: "4K", "HD" for 1080p, else its lines. */
private fun shownWord(height: Int) = when (height) {
    2160 -> "4K"
    1080 -> "HD"
    else -> "${height}p"
}

data class RankedRelease(
    val facts: ReleaseFacts,
    val parsed: ParsedRelease,
    /** False when a blocking reason applies; such releases are never picked automatically. */
    val playable: Boolean,
    val score: Double,
    val reasons: List<ReleaseReason>,
    val estimatedBitrate: Long?,
)

/**
 * Orders releases of one title: playable first, then by preference score. Usenet releases and torrents rank in one list
 * (W72): grabs count within a source ([popularityScale]), and at equal standing Usenet goes first ([usenetFirst]); a list
 * of one source ranks as it always has. On a TV the larger releases of a standing go first ([withTvBonuses]).
 */
class ReleaseRanker(private val parse: (String) -> ParsedRelease = ReleaseParser::parse) {

    fun rank(releases: List<ReleaseFacts>, context: RankingContext): List<RankedRelease> {
        val scale = popularityScale(releases)
        val scored = releases.map { evaluate(it, parse(it.name).withMediaInfo(it.mediaInfo), context, scale) }
        // TVs only (W101, L301): a Cast device ranks on the phone, with tv false; the target guards it all the same.
        val tv = context.tv && context.target == RankingTarget.Device
        val sorted = usenetFirst(withBitrateBonus(if (tv) withTvBonuses(scored) else scored))
            .sortedWith(
                compareByDescending<Held> { it.ranked.playable }
                    .thenByDescending { it.ranked.score }
                    .thenBy { it.ranked.facts.source != ReleaseFacts.USENET }
                    // Torrents held under one Usenet release share its score less BELOW: they keep their own order (DDR-2).
                    .thenByDescending { it.before }
                    .thenByDescending { it.ranked.facts.grabs ?: 0 }
                    .thenBy { it.ranked.facts.name },
            )
        return tiesInsideStandings(sorted).map { it.ranked }
    }

    /**
     * A release as [usenetFirst] leaves it, and its score [before] the hold: a held torrent's place among those held with it.
     * [uncapped] and [standing] are its [Scored]'s, for [tiesInsideStandings].
     */
    private class Held(val ranked: RankedRelease, val before: Double, val uncapped: Double, val standing: Triple<Double, Double, Double>)

    /**
     * A release as scored but for its bitrate's bonus ([bitrateBonus], or on a TV [withTvBonuses]), and its [standing]: its
     * resolution's and language's scores, and its HDR's penalty ([hdrFit]). [base]: its score before the bonus and its
     * grabs' points. [uncapped]: its score with the whole bonus, however [withBitrateBonus] caps it.
     */
    private class Scored(
        val ranked: RankedRelease,
        val bonus: Double,
        val standing: Triple<Double, Double, Double>,
        val base: Double,
        val uncapped: Double = ranked.score + bonus,
    )

    /**
     * On a TV (W101, L302), a maximum set or not: each release's bonus is [TV_BITRATE_BONUS] x its bitrate over the top
     * estimate among the releases of its standing and source that play and fit the maximum; none for a release with no
     * estimate, one that doesn't play or one above the maximum (L305). Relative to the list, as one ranking reads one
     * runtime: a runtime guessed wrong cancels out, and a maximum raised to let remuxes in doesn't weaken them, as x / max
     * would. Per standing, so a 2160p remux doesn't shrink a 1080p TV's 1080p bonuses; per source, so torrents joining
     * later move no Usenet release's bonus (W71), and a list of torrents alone ranks as the same releases from Usenet do.
     */
    private fun withTvBonuses(scored: List<Scored>): List<Scored> {
        fun counted(s: Scored) = s.ranked.estimatedBitrate?.takeIf { s.ranked.playable && s.ranked.reasons.none { it is ReleaseReason.AboveMaxBitrate } }
        val tops = scored.groupBy { it.standing to it.ranked.facts.source }.mapValues { (_, group) -> group.mapNotNull(::counted).maxOrNull() }
        return scored.map { s ->
            val bitrate = counted(s)
            val top = tops[s.standing to s.ranked.facts.source]
            Scored(s.ranked, if (bitrate == null || top == null || top <= 0) 0.0 else TV_BITRATE_BONUS * bitrate / top, s.standing, s.base)
        }
    }

    /**
     * [scored] with their bitrates' bonuses, each no more than keeps a release under every one of another resolution,
     * language or HDR penalty that stands at least as high without it: bitrate orders the releases of a resolution and
     * language among themselves, never those of others, however close source, Proper, grabs and a pack's cost bring the
     * two, and never an HDR release over the SDR one its penalty put it under. A release so held scores its ceiling, the
     * lowest of those scores, less [BELOW] (L327), so the releases of a standing squeezed under one release score the same,
     * for [tiesInsideStandings] to order by their whole bonuses; one with no room keeps its own score.
     */
    private fun withBitrateBonus(scored: List<Scored>): List<Scored> = scored.map { s ->
        val score = s.ranked.score
        val ceiling = scored.filter { o -> o !== s && o.ranked.playable == s.ranked.playable && o.standing != s.standing && o.ranked.score >= score }
            .minOfOrNull { it.ranked.score }
        val capped = if (ceiling == null) score + s.bonus else minOf(score + s.bonus, maxOf(score, ceiling - BELOW))
        if (capped != score) Scored(s.ranked.copy(score = capped), s.bonus, s.standing, s.base, s.uncapped) else s
    }

    /**
     * L333: the sort leaves runs of releases tied but for grabs and name. In each, the releases of one standing that lie
     * together go by their whole bonuses ([Held.uncapped]), then grabs, then name: releases of one standing squeezed under
     * the same release go by bitrate, and a tie across standings goes by grabs and name, as it did before bonuses, never
     * by the bonus [withBitrateBonus] keeps from crossing standings. Only those lying together: a standing's releases on
     * both sides of another's tied with them keep their places, as one taking the other's would pass that release by its
     * bonus alone (RFR-1). So not a sort key, which would order those ties by it, nor a comparator reading it within a
     * standing alone, which isn't transitive. With no bonus, as on a phone with no maximum, nothing moves.
     */
    private fun tiesInsideStandings(sorted: List<Held>): List<Held> {
        // The sort's own first keys, and the standing: a block's releases lie together.
        fun block(held: Held) = listOf(held.ranked.playable, held.ranked.score, held.ranked.facts.source != ReleaseFacts.USENET, held.before, held.standing)
        val starts = sorted.indices.filter { it == 0 || block(sorted[it]) != block(sorted[it - 1]) }
        return (starts + sorted.size).zipWithNext { from, to ->
            sorted.subList(from, to).sortedWith(compareByDescending<Held> { it.uncapped }.thenByDescending { it.ranked.facts.grabs ?: 0 }.thenBy { it.ranked.facts.name })
        }.flatten()
    }

    /**
     * L176 ("prefer usenet", W72): each playable torrent held just under every playable Usenet release of its standing
     * (resolution, language and HDR, as [withBitrateBonus] reads it) that scores at least as much before the bitrate's
     * bonus and the grabs' points. So neither a higher bitrate nor more grabs lift a torrent over a Usenet release it
     * equals in source, Proper and pack; a step of quality, a better source or a Proper above it still puts it first. A
     * flat cost wouldn't do: one under those spreads leaves them, one over them would pass a step of quality. Each keeps its
     * score before the hold ([Held.before]), which orders the torrents held under one Usenet release as they would rank alone.
     */
    private fun usenetFirst(scored: List<Scored>): List<Held> {
        val usenet = scored.filter { it.ranked.facts.source == ReleaseFacts.USENET && it.ranked.playable }
        return scored.map { s ->
            val before = s.ranked.score
            if (s.ranked.facts.source == ReleaseFacts.USENET || !s.ranked.playable) return@map Held(s.ranked, before, s.uncapped, s.standing)
            val cap = usenet.filter { u -> u.standing == s.standing && u.base >= s.base }.minOfOrNull { it.ranked.score - BELOW }
            Held(if (cap != null && cap < before) s.ranked.copy(score = cap) else s.ranked, before, s.uncapped, s.standing)
        }
    }

    /**
     * What a torrent's grabs' points are multiplied by in [releases] (L176, C7): a tracker's snatches and an indexer's
     * grabs are on scales of their own, so the most grabbed torrent gets what the most grabbed Usenet release gets, and
     * torrents rank among themselves by their own. 1 without a Usenet release, or a torrent, with grabs.
     */
    private fun popularityScale(releases: List<ReleaseFacts>): Double {
        val (usenet, torrents) = releases.partition { it.source == ReleaseFacts.USENET }
        val usenetBest = usenet.maxOfOrNull { popularity(it.grabs) } ?: 0.0
        val torrentBest = torrents.maxOfOrNull { popularity(it.grabs) } ?: 0.0
        return if (usenetBest > 0 && torrentBest > 0) usenetBest / torrentBest else 1.0
    }

    private fun popularity(grabs: Int?): Double = log10((grabs ?: 0).toDouble() + 1) * 5

    private fun evaluate(facts: ReleaseFacts, parsed: ParsedRelease, context: RankingContext, scale: Double): Scored {
        val bitrate = estimatedBitrate(facts, parsed, context)
        val noHardware = noHardwareDecoder(parsed, context)
        val blocking = blockingReasons(facts, parsed, context) + listOfNotNull(noHardware?.takeIf { it.blocking }) + sourceReasons(facts)
        val onCast = castReasons(parsed, context)
        val quality = quality(parsed, context)
        val language = language(parsed, context.prefs.audioLanguages, context.originalLanguage)
        val hdr = hdrFit(parsed, context)
        val grabs = (popularity(facts.grabs) * if (facts.source == ReleaseFacts.USENET) 1.0 else scale) to emptyList<ReleaseReason>()
        val scored = listOf(
            // For a Cast device: tried after every release that fits, once however many it has (L126).
            (if (onCast.isEmpty()) 0.0 else -ABOVE_MAX_BITRATE) to onCast,
            quality,
            language,
            hdr,
            // On a phone or tablet: tried after every release that fits, as one above the maximum bitrate is.
            noHardware?.takeUnless { it.blocking }.let { (if (it == null) 0.0 else -ABOVE_MAX_BITRATE) to listOfNotNull(it) },
            bitrateFit(bitrate, context.throughputBitsPerSecond),
            aboveMaxBitrate(bitrate, context),
            source(parsed),
            (if (parsed.proper) 25.0 else 0.0) to listOfNotNull(ReleaseReason.Proper.takeIf { parsed.proper }),
            grabs,
            0.0 to listOfNotNull(ReleaseReason.Passworded.takeIf { facts.passworded }),
            0.0 to listOfNotNull(ReleaseReason.Cached.takeIf { facts.availability is Availability.Cached }),
            // A pack is a bigger NZB to fetch and more files to open: a single episode wins only at about equal standing.
            (if (parsed.isSeasonPack) -SEASON_PACK_COST else 0.0) to emptyList(),
        )
        // The phone can't check a Cast device's player: nothing says the release plays there.
        val plays = listOfNotNull(ReleaseReason.PlaysOnDevice.takeIf { context.target == RankingTarget.Device })
        val reasons = blocking.ifEmpty { plays } + scored.flatMap { it.second }
        val ranked = RankedRelease(
            facts = facts,
            parsed = parsed,
            playable = blocking.isEmpty(),
            score = scored.sumOf { it.first },
            reasons = reasons.sortedBy { it.severity },
            estimatedBitrate = bitrate,
        )
        // Summed apart, in the same order, not taken back out of the score: past a power of two the doubles are further
        // apart, and the ulp a subtraction leaves would decide [usenetFirst]'s comparison of two equal releases (DDR-1).
        val base = scored.filter { it !== grabs }.sumOf { it.first }
        return Scored(ranked, bitrateBonus(bitrate, maxBitrate(context)), Triple(quality.first, language.first, hdr.first), base)
    }

    /**
     * What keeps a torrent from playing, whoever it is for (L177): its cache check's answer. A debrid play reaches a Cast
     * device through the phone (W73), so a list for one ranks a cached torrent as the phone's does. Nothing for a Usenet
     * release, nor for a torrent whose check isn't known (one played before).
     */
    private fun sourceReasons(facts: ReleaseFacts): List<ReleaseReason> {
        if (facts.source == ReleaseFacts.USENET) return emptyList()
        return listOfNotNull(
            when (facts.availability) {
                Availability.NotCached -> ReleaseReason.NotCached
                Availability.NotChecked -> ReleaseReason.NotChecked
                Availability.NotForService -> ReleaseReason.NotForService
                is Availability.Cached, null -> null
            },
        )
    }

    /**
     * What keeps the release from playing at all. A disc image and an age past retention, whoever it is for; and for this
     * device what it can't play. A Cast device's warns instead ([castReasons]).
     */
    private fun blockingReasons(facts: ReleaseFacts, parsed: ParsedRelease, context: RankingContext): List<ReleaseReason> {
        val device = context.device
        val codec = parsed.videoCodec
        val retention = context.prefs.retentionDays
        val here = context.target == RankingTarget.Device
        return listOfNotNull(
            ReleaseReason.DiscImage.takeIf { parsed.discImage },
            codec?.takeIf { here }?.let { videoUnsupported(it, parsed, device) },
            ReleaseReason.DolbyVisionOnly.takeIf { here && parsed.dolbyVisionOnly && !device.dolbyVision },
            parsed.audio.takeIf { here && it.isNotEmpty() && it.none(device::canDecode) }
                ?.let { audio -> ReleaseReason.AudioUnsupported(audio.joinToString("/") { it.label }) },
            ReleaseReason.BeyondRetention.takeIf { retention != null && (facts.ageDays ?: 0) > retention },
        )
    }

    /**
     * For a Cast device (L126), what its profile says it can't play, as [blockingReasons] finds it for this device, each
     * a warning in the device's name: no picture of its video, or none at its size; Dolby Vision without a base it shows;
     * no sound of any audio the name gives. Empty for this device.
     */
    private fun castReasons(parsed: ParsedRelease, context: RankingContext): List<ReleaseReason> {
        val cast = context.target as? RankingTarget.Cast ?: return emptyList()
        val device = context.device
        val video = parsed.videoCodec?.let { codec ->
            when (val miss = videoMiss(codec, parsed, device)) {
                null -> null
                VideoMiss.Codec -> ReleaseReason.NoPictureOn(videoLabel(codec, parsed), cast.name)
                is VideoMiss.Size -> ReleaseReason.TooBigFor(miss.resolution, miss.maxHeight, cast.name)
            }
        }
        return listOfNotNull(
            video,
            ReleaseReason.DolbyVisionOnlyOn(cast.name).takeIf { parsed.dolbyVisionOnly && !device.dolbyVision },
            parsed.audio.takeIf { it.isNotEmpty() && it.none(device::canDecode) }
                ?.let { audio -> ReleaseReason.NoSoundOn(audio.joinToString("/") { it.label }, cast.name) },
        )
    }

    /** Why no decoder plays a release's video: none for its codec at its bit depth, or none at its [Size]. */
    private sealed interface VideoMiss {
        data object Codec : VideoMiss
        data class Size(val resolution: Int, val maxHeight: Int) : VideoMiss
    }

    /** Null when a decoder plays [codec] at the release's bit depth and resolution. */
    private fun videoMiss(codec: VideoCodec, parsed: ParsedRelease, device: DeviceCapabilities): VideoMiss? {
        val resolution = parsed.resolution
        val maxHeight = device.maxHeight(codec, parsed.tenBit)
        return when {
            !device.canDecode(codec, parsed.tenBit) -> VideoMiss.Codec
            resolution != null && maxHeight != null && resolution > maxHeight -> VideoMiss.Size(resolution, maxHeight)
            else -> null
        }
    }

    /** Null when a decoder plays [codec] at the release's bit depth and resolution. */
    private fun videoUnsupported(codec: VideoCodec, parsed: ParsedRelease, device: DeviceCapabilities): ReleaseReason? {
        val label = videoLabel(codec, parsed)
        return when (val miss = videoMiss(codec, parsed, device)) {
            null -> null
            VideoMiss.Codec -> ReleaseReason.VideoUnsupported(label)
            is VideoMiss.Size -> ReleaseReason.VideoUnsupported("${miss.resolution}p $label")
        }
    }

    /**
     * Above 1080p, a release some decoder plays but none in hardware does at its size: a software decoder can't keep up
     * with it. Up to 1080p it can, so the player's fallback to it counts there. Null when [videoMiss] applies, or the
     * hardware limit or the resolution isn't known, and for a Cast device, whose decoders the phone can't know.
     */
    private fun noHardwareDecoder(parsed: ParsedRelease, context: RankingContext): ReleaseReason.NoHardwareDecoder? {
        if (context.target != RankingTarget.Device) return null
        val codec = parsed.videoCodec ?: return null
        val resolution = parsed.resolution?.takeIf { it > SOFTWARE_DECODE_MAX } ?: return null
        if (videoMiss(codec, parsed, context.device) != null) return null
        val hardware = context.device.hardwareMaxHeight(codec, parsed.tenBit) ?: return null
        return if (resolution > hardware) ReleaseReason.NoHardwareDecoder("${resolution}p ${videoLabel(codec, parsed)}", blocking = context.tv) else null
    }

    private fun videoLabel(codec: VideoCodec, parsed: ParsedRelease) = if (parsed.tenBit) "${codec.label} 10-bit" else codec.label

    /**
     * On a TV, and on a Cast device (L126), HDR its screen doesn't show costs [HDR_ON_SDR]. HDR10+ and Dolby Vision with an
     * HDR10 base fit any HDR10 screen; Dolby Vision alone needs a Dolby Vision one; HLG fits any screen, being made to look
     * right on SDR too. Phones and tablets map HDR to their own screens, so nothing there.
     */
    private fun hdrFit(parsed: ParsedRelease, context: RankingContext): Pair<Double, List<ReleaseReason>> {
        val hdr = parsed.hdr - Hdr.Hlg
        if (!(context.tv || context.target is RankingTarget.Cast) || hdr.isEmpty()) return 0.0 to emptyList()
        val screen = context.device.hdrTypes
        val fits = if (parsed.dolbyVisionOnly) setOf(Hdr.DolbyVision) else setOf(Hdr.Hdr10, Hdr.Hdr10Plus) + (hdr intersect setOf(Hdr.DolbyVision))
        if (fits.any { it in screen }) return 0.0 to emptyList()
        val missing = if (screen.isEmpty()) null else if (parsed.dolbyVisionOnly) Hdr.DolbyVision else Hdr.Hdr10
        return -HDR_ON_SDR to listOf(ReleaseReason.HdrOnSdrScreen(missing))
    }

    private fun quality(parsed: ParsedRelease, context: RankingContext): Pair<Double, List<ReleaseReason>> {
        val target = context.prefs.quality.height ?: context.device.displayHeight.coerceAtMost(2160)
        val resolution = parsed.resolution ?: 480
        val steps = RESOLUTIONS.indexOfLast { it <= resolution } - RESOLUTIONS.indexOfLast { it <= target }
        return when {
            steps == 0 -> 100.0 to emptyList()
            steps < 0 -> 100.0 + steps * 30 to emptyList()
            else -> 60.0 - (steps - 1) * 20 to listOfNotNull(aboveScreen(resolution, parsed, context))
        }
    }

    /**
     * A release above the screen: on this device "2160p is above this screen"; on a Cast device not yet seen showing 4K
     * (its display under 2160 lines), "2160p: Den TV hasn’t played 4K yet" (W53), unless a warning says its picture
     * won't show there anyway ([castReasons]). Null within the screen.
     */
    private fun aboveScreen(resolution: Int, parsed: ParsedRelease, context: RankingContext): ReleaseReason? {
        val device = context.device
        if (resolution <= device.displayHeight) return null
        val cast = context.target as? RankingTarget.Cast ?: return ReleaseReason.AboveScreen(resolution)
        val warned = parsed.videoCodec?.let { videoMiss(it, parsed, device) } != null
        return ReleaseReason.NotYet4k(resolution, cast.name).takeIf { device.displayHeight < 2160 && !warned }
    }

    /**
     * First preferred language is worth more than one step of quality; each later one half as much. A release
     * with no audio language tag (a subbed one included: its tag names the subtitles) speaks the title's
     * [original] language, English when that isn't known (scene releases are untagged in English). Codes
     * compare as TMDB and release names write them, so a device's "nb", "iw" or "in" meets "no", "he", "id".
     */
    private fun language(parsed: ParsedRelease, preferred: List<String>, original: String?): Pair<Double, List<ReleaseReason>> {
        val wanted = preferred.map(::canonical)
        val spoken = parsed.languages.ifEmpty { if (parsed.multiLanguage) emptySet() else setOf(original ?: "en") }.map(::canonical)
        val index = wanted.indexOfFirst { it in spoken }.takeIf { it >= 0 }
        val multiIndex = if (parsed.multiLanguage) 0 else null
        val score = listOfNotNull(
            index?.let { 120.0 / (1 shl it) },
            multiIndex?.let { 120.0 / (1 shl it) * 0.5 },
        ).maxOrNull() ?: 0.0
        val label = when {
            parsed.dualLanguage && parsed.languages.size >= 2 ->
                "${languageName(parsed.languages.first { it != "en" })} DL"
            index != null && parsed.languages.isNotEmpty() -> languageName(wanted[index])
            parsed.multiLanguage -> "Multi-language"
            else -> null
        }
        return score to listOfNotNull(label?.takeIf { score > 0 }?.let { ReleaseReason.Language(it) })
    }

    private fun bitrateFit(bitrate: Long?, throughput: Long?): Pair<Double, List<ReleaseReason>> {
        if (bitrate == null || throughput == null || throughput <= 0) return 0.0 to emptyList()
        val load = bitrate.toDouble() / throughput
        val reason = listOf(ReleaseReason.MayBuffer(((bitrate + 500_000) / 1_000_000).toInt()))
        return when {
            load > 1.0 -> -150.0 to reason
            load > 0.7 -> -80.0 to reason
            else -> 0.0 to emptyList()
        }
    }

    /**
     * With a maximum set: a release estimated above it ranks after every one that fits, as [ABOVE_MAX_BITRATE] is more than
     * any score difference among playable releases, and stays playable, so it is tried last rather than never. For a Cast
     * device the maximum when casting holds too (L132): each maximum it is above says so in its own words, and above both
     * it is scored once, apart from the device's own reasons ([castReasons]). A release with no estimate isn't held back.
     */
    private fun aboveMaxBitrate(bitrate: Long?, context: RankingContext): Pair<Double, List<ReleaseReason>> {
        if (bitrate == null) return 0.0 to emptyList()
        fun above(max: Long?) = max?.takeIf { it > 0 && bitrate > it }?.let { ((it + 500_000) / 1_000_000).toInt() }
        val reasons = listOfNotNull(
            above(context.prefs.maxBitrate)?.let { ReleaseReason.AboveMaxBitrate(it) },
            above(castMaxBitrate(context))?.let { ReleaseReason.AboveCastMax(it) },
        )
        return (if (reasons.isEmpty()) 0.0 else -ABOVE_MAX_BITRATE) to reasons
    }

    /** The maximum when casting, read for a Cast device alone (L132); null for this device's own ranking. */
    private fun castMaxBitrate(context: RankingContext): Long? = context.prefs.castMaxBitrate.takeIf { context.target is RankingTarget.Cast }

    /**
     * The bitrate a release is held to: Maximum bitrate, and for a Cast device the lower of it and the maximum when casting,
     * of those set (the phone still downloads what it casts); null with none set.
     */
    private fun maxBitrate(context: RankingContext): Long? = listOfNotNull(context.prefs.maxBitrate, castMaxBitrate(context)).filter { it > 0 }.minOrNull()

    /**
     * On a phone or tablet, and for a Cast device, with a maximum set ([maxBitrate]): among the releases that fit it, a
     * higher bitrate scores up to [BITRATE_BONUS]: a remux within the limit beats a WEB-DL of its resolution, never a
     * release of another resolution or language ([withBitrateBonus]). A release with no estimate isn't helped. With none
     * set, nothing (W101: TVs only). A TV's own ranking takes [withTvBonuses]' instead.
     */
    private fun bitrateBonus(bitrate: Long?, max: Long?): Double =
        if (bitrate == null || max == null || max <= 0 || bitrate > max) 0.0 else BITRATE_BONUS * bitrate / max

    private fun source(parsed: ParsedRelease): Pair<Double, List<ReleaseReason>> = when (parsed.source) {
        Source.Cam, Source.Telesync -> -200.0 to listOf(ReleaseReason.PoorSource(parsed.source))
        Source.Dvd -> -10.0 to emptyList()
        Source.Hdtv, null -> 0.0 to emptyList()
        Source.WebRip -> 10.0 to emptyList()
        Source.WebDl, Source.BluRay, Source.Remux -> 20.0 to emptyList()
    }

    /** Size over runtime; a season pack's over its season's episodes ([RankingContext.seasonEpisodes]), when known. */
    private fun estimatedBitrate(facts: ReleaseFacts, parsed: ParsedRelease, context: RankingContext): Long? {
        if (facts.sizeBytes <= 0) return null
        val episodes = if (parsed.isSeasonPack) {
            context.seasonEpisodes?.takeIf { it > 0 && parsed.seasons.size == 1 } ?: return null
        } else {
            parsed.episodes.size.coerceAtLeast(1)
        }
        val runtime = context.runtimeMinutes?.takeIf { it > 0 } ?: if (parsed.isEpisode || parsed.isSeasonPack) EPISODE_MINUTES else MOVIE_MINUTES
        val minutes = (runtime * episodes).takeIf { it > 0 } ?: return null
        return facts.sizeBytes * 8 / (minutes * 60L)
    }

    private fun languageName(code: String): String =
        java.util.Locale.forLanguageTag(code).getDisplayLanguage(java.util.Locale.ENGLISH).ifEmpty { code }

    private fun canonical(code: String): String = OLD_LANGUAGE_CODES[code] ?: code

    private companion object {
        /** Android's Locale still says these (Norwegian Bokmål and Nynorsk, Hebrew, Indonesian, Yiddish). */
        val OLD_LANGUAGE_CODES = mapOf("nb" to "no", "nn" to "no", "iw" to "he", "in" to "id", "ji" to "yi")
        val RESOLUTIONS = listOf(0, 480, 576, 720, 1080, 2160)
        const val EPISODE_MINUTES = 45
        const val MOVIE_MINUTES = 110
        /** Well under a step of quality (30) and the first language (120). */
        const val SEASON_PACK_COST = 10.0
        /** A phone's, and a Cast device's, with a maximum set ([bitrateBonus]): less than a step of quality (30). */
        const val BITRATE_BONUS = 25.0
        /**
         * A TV's ([withTvBonuses], W101, L303): with the top an 80 GB remux, a 24 GB encode of its standing gets 42 less,
         * more than the widest lead grabs and a Proper give one release over another (41.1), while near-equal sizes still go
         * by them. More than a step of quality (30): safe only because [withBitrateBonus] caps it under every release of
         * another standing that stood at least as high.
         */
        const val TV_BITRATE_BONUS = 60.0
        /** How far under a release of another resolution or language its bonus leaves one that stood under it ([withBitrateBonus]). */
        const val BELOW = 0.001
        /**
         * More than the widest gap between two playable releases' scores, about 800 (best quality, language, source, ...
         * against worst, an HDR penalty among them).
         */
        const val ABOVE_MAX_BITRATE = 1_000.0
        /** Up to here a software decoder is a real fallback; above it only hardware plays smoothly. */
        const val SOFTWARE_DECODE_MAX = 1080
        /**
         * HDR on a screen without it: more than a step of resolution (30) and less than two, so an SDR release of the same
         * resolution or the next lower one goes first, and one two lower doesn't; less than the first
         * language's lead over the second (60), so the preferred language still wins.
         */
        const val HDR_ON_SDR = 45.0
    }
}
