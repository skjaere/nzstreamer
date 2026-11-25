package app.nzstreamer.plugin.sportarr.source

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalQuery

/**
 * Sportarr's answers as the plugin reads them (L641, L653): the few fields it declares here and nothing else. Every event
 * Sportarr sends carries its scores, status, images, venue, files and more, and every league its description and images;
 * none is declared, and unknown keys are skipped, so none of them ever reaches an object, a file, a screen or a log.
 *
 * A guaranteed field that is missing or of another type makes the body unreadable: a read failure. An unguaranteed one
 * (Sportarr sends it today, but its API doesn't promise it) that can't be read is none, which every reader takes as "the
 * route's answer as it is" (L642), the title, or Generic.
 */
object Wire : WireDecoder {
    /** Unknown keys skipped; a nullable field may be missing, or come as null, as Sportarr writes every empty one. */
    internal val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /** `GET /api/system/status`, or null when the body isn't such an answer. */
    override fun status(body: ByteArray): WireStatus? = read(body) { json.decodeFromString(WireStatus.serializer(), it) }

    /** `GET /api/leagues`: a plain array of leagues, or null when the body isn't one. */
    override fun leagues(body: ByteArray): List<WireLeague>? = read(body) { json.decodeFromString(LEAGUES, it) }

    /**
     * `GET /api/leagues/{id}/events`: one page of an envelope when paging was asked of a Sportarr that pages (4.1.10 on), or
     * the whole league as a plain array from one that ignores it; null when the body is neither.
     */
    override fun events(body: ByteArray): WireEvents? = read(body) { text ->
        when (text.firstOrNull { !it.isWhitespace() && it != BOM }) {
            '{' -> WireEvents.Paged(json.decodeFromString(WirePage.serializer(), text.trimStart(BOM)))
            '[' -> WireEvents.Whole(json.decodeFromString(EVENTS, text.trimStart(BOM)))
            else -> null
        }
    }

    /** A byte-order mark, which some servers write before a body. */
    private const val BOM = '\uFEFF'

    private val LEAGUES = ListSerializer(WireLeague.serializer())
    private val EVENTS = ListSerializer(WireEvent.serializer())

    /** [decode] over [body] as text; null where the text isn't what it reads (a SerializationException is one). */
    private inline fun <T> read(body: ByteArray, decode: (String) -> T?): T? = try {
        decode(body.decodeToString())
    } catch (e: IllegalArgumentException) {
        null
    }
}

/** Reads Sportarr's answers: [Wire], or a test's stand-in that notes where it ran (the decode is off the main thread, L641). */
interface WireDecoder {
    fun status(body: ByteArray): WireStatus?

    fun leagues(body: ByteArray): List<WireLeague>?

    fun events(body: ByteArray): WireEvents?
}

/** Who answers: Sportarr says its [appName], and its [version] in four parts ("4.1.12.1122"). */
@Serializable
data class WireStatus(
    val appName: String? = null,
    @Serializable(with = LooseText::class) val version: String? = null,
)

/**
 * A league in the user's Sportarr. Guaranteed: [id], [externalId], [name], [sport] and [monitored]. Sent today but not
 * promised: [monitorType] ("Future", "None", ...), [monitoredEventCount], [keepAllEvents] and [alternateName] (several
 * names, separated by commas, pipes or slashes).
 */
@Serializable
data class WireLeague(
    val id: Int,
    @Serializable(with = LooseText::class) val externalId: String? = null,
    val name: String,
    // Guaranteed, but a league may have none: the family then reads the event's (L643).
    val sport: String? = null,
    val monitored: Boolean,
    @Serializable(with = LooseText::class) val monitorType: String? = null,
    @Serializable(with = LooseInt::class) val monitoredEventCount: Int? = null,
    @Serializable(with = LooseBoolean::class) val keepAllEvents: Boolean? = null,
    @Serializable(with = LooseText::class) val alternateName: String? = null,
)

/**
 * An event as a league's route lists it. [eventDate] is its start in UTC; [broadcastDate] the date the broadcaster gives
 * it, as written (the day can differ from the UTC date's for an evening game abroad), or Sportarr's fallback, the UTC
 * date, which looks the same on the wire. [round] and [season] are text ("Week 10", "2025-2026"). Sent today but not
 * promised: [round], [homeTeamName], [awayTeamName] and [eventTypePartNames], the last on fighting sports alone.
 */
@Serializable
data class WireEvent(
    val id: Int,
    @Serializable(with = LooseText::class) val externalId: String? = null,
    val title: String,
    val sport: String? = null,
    val leagueId: Int? = null,
    val leagueName: String? = null,
    @Serializable(with = LooseText::class) val season: String? = null,
    @Serializable(with = UtcTime::class) val eventDate: Instant,
    @Serializable(with = WrittenDate::class) val broadcastDate: LocalDate? = null,
    val broadcastTimezone: String? = null,
    val monitored: Boolean,
    val hasFile: Boolean,
    @Serializable(with = LooseText::class) val round: String? = null,
    @Serializable(with = LooseText::class) val homeTeamName: String? = null,
    @Serializable(with = LooseText::class) val awayTeamName: String? = null,
    @Serializable(with = LooseTextList::class) val eventTypePartNames: List<String>? = null,
)

/** One page of a paged read: newest first by start, then by id, as Sportarr orders them. */
@Serializable
data class WirePage(val page: Int, val pageSize: Int, val totalRecords: Int, val totalPages: Int, val records: List<WireEvent>)

/** A league's events as its route answered: one page of an envelope, or the whole league as a plain array. */
sealed interface WireEvents {
    data class Paged(val page: WirePage) : WireEvents

    data class Whole(val events: List<WireEvent>) : WireEvents
}

/** A start in UTC, with or without an offset (none is UTC, as Sportarr writes its dates); written back with "Z". */
internal object UtcTime : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("app.nzstreamer.plugin.sportarr.UtcTime", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): Instant = decoder.decodeString().let { written ->
        try {
            when (val read = DateTimeFormatter.ISO_DATE_TIME.parseBest(written.trim(), OFFSET, LOCAL)) {
                is OffsetDateTime -> read.toInstant()
                is LocalDateTime -> read.toInstant(ZoneOffset.UTC)
                else -> throw SerializationException("Not a time")
            }
        } catch (e: DateTimeException) {
            throw SerializationException("Not a time")
        }
    }

    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())

    private val OFFSET = TemporalQuery { OffsetDateTime.from(it) }
    private val LOCAL = TemporalQuery { LocalDateTime.from(it) }
}

/**
 * The yyyy-mm-dd a date or time starts with, as written: never moved into any zone, so "2026-11-21T00:00:00Z" is the 21st
 * wherever the device is. Anything else is none.
 */
internal object WrittenDate : KSerializer<LocalDate?> {
    private val written = String.serializer().nullable

    override val descriptor: SerialDescriptor = written.descriptor

    override fun deserialize(decoder: Decoder): LocalDate? {
        val (year, month, day) = decoder.text()?.let { DATE.find(it) }?.destructured ?: return null
        return try {
            LocalDate.of(year.toInt(), month.toInt(), day.toInt())
        } catch (e: DateTimeException) {
            null
        }
    }

    override fun serialize(encoder: Encoder, value: LocalDate?) = written.serialize(encoder, value?.toString())

    private val DATE = Regex("""^\s*(\d{4})-(\d{2})-(\d{2})""")
}

// The loose readers below write as the plain nullable types do, through the library's own serializers.

/** Text, a number read as its digits; anything else (an object, an array) is none. */
internal object LooseText : KSerializer<String?> by String.serializer().nullable {
    override fun deserialize(decoder: Decoder): String? = decoder.text()
}

/** A whole number, or none. */
internal object LooseInt : KSerializer<Int?> by Int.serializer().nullable {
    override fun deserialize(decoder: Decoder): Int? = decoder.primitive()?.intOrNull
}

/** true or false, or none. */
internal object LooseBoolean : KSerializer<Boolean?> by Boolean.serializer().nullable {
    override fun deserialize(decoder: Decoder): Boolean? = decoder.primitive()?.booleanOrNull
}

/** A list of texts in their order, anything in it that isn't text left out; none when it isn't a list. */
internal object LooseTextList : KSerializer<List<String>?> by ListSerializer(String.serializer()).nullable {
    override fun deserialize(decoder: Decoder): List<String>? = (decoder.element() as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.takeUnless { item -> item is JsonNull }?.content }
}

/** The value at hand as JSON: these serializers read the plugin's JSON alone. */
private fun Decoder.element(): JsonElement =
    (this as? JsonDecoder)?.decodeJsonElement() ?: throw SerializationException("The plugin reads its wire types as JSON alone")

/** The value at hand when it is a string, a number or a boolean; null for null, an object or an array. */
private fun Decoder.primitive(): JsonPrimitive? = (element() as? JsonPrimitive)?.takeUnless { it is JsonNull }

/** The value at hand as text: a string, or a number or boolean as written; null for anything else. */
private fun Decoder.text(): String? = primitive()?.content
