package io.skjaere.usenet.app.plugins

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * The plugin list (L589), format 1, as CI writes it from the published plugin prereleases (tools/release): when it was
 * [generated], and its [plugins], each with the versions CI keeps (the newest for each API and set of shared libraries,
 * 20 at most) and the versionCodes it [ListedPlugin.withdrawn]. Read with this build's rules ([read]): keys it doesn't
 * know are passed over, the format and the caps hold for the whole list, and an entry that breaks a rule is dropped
 * alone ([dropped], by kind). Its text is plain text, drawn as text; it never decides what loads: an installed plugin is
 * checked again at every load, and only a withdrawal turns one off.
 */
class PluginIndex(val generated: Instant, val plugins: List<ListedPlugin>, val dropped: List<ListDrop>) {
    /**
     * What the list offers for plugin [id] (L621), with [installed] its installed versionCode (null for none), on a device
     * of [sdk], by [compat]'s rule. Offered: the highest version that passes everything (not withdrawn, its API and
     * libraries accepted, its minSdk not above [sdk]) and is above [installed]. Null where there is nothing to show: a
     * plugin the list doesn't name, or one not installed with no version offered, unless every version listed fails on its
     * API or libraries alone, which is listed as needing a newer NZStreamer.
     */
    fun offer(id: String, installed: Long?, sdk: Int, compat: PluginCompat): PluginOffer? {
        val plugin = plugins.firstOrNull { it.id == id } ?: return null
        fun runsHere(version: ListedVersion) = version.versionCode !in plugin.withdrawn && version.minSdk <= sdk
        fun accepted(version: ListedVersion) = compat.apiAccepted(version.apiMajor, version.apiMinor) && compat.libsAccepted(version.libs)
        val offered = plugin.versions.filter { runsHere(it) && accepted(it) && it.versionCode > (installed ?: 0) }.maxByOrNull { it.versionCode }
        val newest = plugin.versions.maxByOrNull { it.versionCode }
        val needsNewer = newest?.takeIf { it.versionCode > (installed ?: 0) && runsHere(it) && !accepted(it) }
        if (installed == null && offered == null) {
            val allTooNew = plugin.versions.isNotEmpty() && plugin.versions.all { runsHere(it) && !accepted(it) }
            if (!allTooNew) return null
        }
        return PluginOffer(plugin, offered, needsNewer, withdrawn = installed != null && installed in plugin.withdrawn)
    }

    companion object {
        /** The list's format this build reads; a later one says to update NZStreamer. */
        const val FORMAT = 1L

        /** The list's caps (L589): its size in bytes, its plugins, each one's versions. Over one, it is refused whole. */
        const val MAX_BYTES = 256 * 1024
        const val MAX_PLUGINS = 50
        const val MAX_VERSIONS = 20

        /** A plugin's name and summary, and a version's name, in characters. */
        const val MAX_NAME = 40
        const val MAX_SUMMARY = 200
        const val MAX_VERSION_NAME = 20

        /** A list that holds no plugin: GitHub answered 404, so the branch isn't there yet (L620). */
        val EMPTY = PluginIndex(Instant.EPOCH, emptyList(), emptyList())

        /**
         * [body], as GitHub gave it, read with [source]'s address rule. Refused whole when it is over its caps, doesn't
         * read, or gives no format or time it was made; a later format is [IndexReading.TooNew]; one made before [accepted]
         * (the copy's) is [IndexReading.Older], ignored, so an older list served again never takes a newer one's place.
         */
        fun read(body: ByteArray, source: PluginSource, accepted: Instant?): IndexReading {
            if (body.size > MAX_BYTES) return IndexReading.Refused(ListFault.TooLarge)
            val root = try {
                json.parseToJsonElement(body.decodeToString()) as? JsonObject
            } catch (e: IllegalArgumentException) {
                null
            } ?: return IndexReading.Refused(ListFault.Unreadable)
            val format = root.integer("format") ?: return IndexReading.Refused(ListFault.Format)
            if (format > FORMAT) return IndexReading.TooNew
            if (format != FORMAT) return IndexReading.Refused(ListFault.Format)
            val generated = root.string("generated")?.let(::instant) ?: return IndexReading.Refused(ListFault.Generated)
            if (accepted != null && generated < accepted) return IndexReading.Older
            val entries = root["plugins"] as? JsonArray ?: return IndexReading.Refused(ListFault.Unreadable)
            if (entries.size > MAX_PLUGINS || entries.any { ((it as? JsonObject)?.get("versions") as? JsonArray).orEmpty().size > MAX_VERSIONS }) {
                return IndexReading.Refused(ListFault.TooMany)
            }
            val dropped = mutableListOf<ListDrop>()
            val plugins = mutableListOf<ListedPlugin>()
            entries.forEach { entry ->
                when (val read = plugin(entry, plugins.map { it.id }, source, dropped)) {
                    is Entry.Kept -> plugins += read.value
                    is Entry.Gone -> dropped += read.kind
                }
            }
            return IndexReading.Listed(PluginIndex(generated, plugins, dropped))
        }

        /**
         * One plugin's entry. Its id first, before it goes into anything (L620), and only once in the list ([taken] the ids
         * before it); then its package, its words and its withdrawn versions; then each version on its own, into [dropped]
         * where it breaks a rule.
         */
        private fun plugin(entry: JsonElement, taken: List<String>, source: PluginSource, dropped: MutableList<ListDrop>): Entry<ListedPlugin> {
            val fields = entry as? JsonObject ?: return Entry.Gone(ListDrop.Malformed)
            val id = fields.string("id")?.takeIf(PluginIds::valid) ?: return Entry.Gone(ListDrop.Id)
            if (id in taken) return Entry.Gone(ListDrop.Repeated)
            if (fields.string("package") != PluginIds.packageOf(id)) return Entry.Gone(ListDrop.Package)
            val name = fields.string("name") ?: return Entry.Gone(ListDrop.Malformed)
            val summary = fields.string("summary") ?: return Entry.Gone(ListDrop.Malformed)
            if (!plainText(name, MAX_NAME, blank = false) || !plainText(summary, MAX_SUMMARY, blank = true)) return Entry.Gone(ListDrop.Text)
            val withdrawn = when (val listed = fields["withdrawn"]) {
                null -> emptySet()
                is JsonArray -> listed.map { (it as? JsonPrimitive)?.integerOrNull()?.takeIf { code -> code > 0 } ?: return Entry.Gone(ListDrop.Malformed) }.toSet()
                else -> return Entry.Gone(ListDrop.Malformed)
            }
            val versions = mutableListOf<ListedVersion>()
            (fields["versions"] as? JsonArray ?: return Entry.Gone(ListDrop.Malformed)).forEach { element ->
                when (val read = version(id, element, source)) {
                    is Entry.Kept -> if (versions.any { it.versionCode == read.value.versionCode }) dropped += ListDrop.Repeated else versions += read.value
                    is Entry.Gone -> dropped += read.kind
                }
            }
            return Entry.Kept(ListedPlugin(id, name, summary, withdrawn, versions.sortedByDescending { it.versionCode }))
        }

        /** One version of plugin [id]: its APK's address checked whole first, then its hash, its name and its numbers. */
        private fun version(id: String, element: JsonElement, source: PluginSource): Entry<ListedVersion> {
            val fields = element as? JsonObject ?: return Entry.Gone(ListDrop.Malformed)
            val url = fields.string("url")?.takeIf { source.apkAddress(id, it) } ?: return Entry.Gone(ListDrop.Address)
            val sha256 = fields.string("sha256")?.takeIf { HASH.matches(it) } ?: return Entry.Gone(ListDrop.Hash)
            val versionName = fields.string("versionName") ?: return Entry.Gone(ListDrop.Malformed)
            if (!plainText(versionName, MAX_VERSION_NAME, blank = false)) return Entry.Gone(ListDrop.Text)
            val versionCode = fields.integer("versionCode")?.takeIf { it in 1..Int.MAX_VALUE } ?: return Entry.Gone(ListDrop.Malformed)
            val api = fields.string("api")?.let(API::matchEntire) ?: return Entry.Gone(ListDrop.Malformed)
            val libs = when (val listed = fields["libs"]) {
                null -> emptyMap()
                is JsonObject -> listed.mapValues { (_, value) -> (value as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return Entry.Gone(ListDrop.Malformed) }
                else -> return Entry.Gone(ListDrop.Malformed)
            }
            val minSdk = fields.integer("minSdk")?.takeIf { it in 1..MAX_SDK } ?: return Entry.Gone(ListDrop.Malformed)
            val size = fields.integer("size")?.takeIf { it in 1..Int.MAX_VALUE } ?: return Entry.Gone(ListDrop.Malformed)
            val (major, minor) = api.destructured
            return Entry.Kept(ListedVersion(versionCode, versionName, major.toInt(), minor.toInt(), libs, minSdk.toInt(), url, size, sha256))
        }

        /**
         * Plain text (L589): [max] characters at most, none of them a control character, a line break or one that turns
         * the text's direction, which could make a name read as another; not blank unless [blank].
         */
        private fun plainText(text: String, max: Int, blank: Boolean): Boolean =
            text.codePointCount(0, text.length) <= max && (blank || text.isNotBlank()) && text.none { it.isISOControl() || it in DIRECTION }

        private sealed interface Entry<out T> {
            class Kept<T>(val value: T) : Entry<T>

            class Gone(val kind: ListDrop) : Entry<Nothing>
        }

        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

        private fun JsonObject.integer(key: String): Long? = (this[key] as? JsonPrimitive)?.integerOrNull()

        // A number written as one, never a string: "1" isn't format 1.
        private fun JsonPrimitive.integerOrNull(): Long? = takeIf { !it.isString }?.longOrNull

        private fun instant(text: String): Instant? = try {
            Instant.parse(text)
        } catch (e: DateTimeParseException) {
            null
        }

        // The API a version was built against, "major.minor" as two numbers: "1.10" is minor 10, never 1.1.
        private val API = Regex("""(0|[1-9][0-9]{0,8})\.(0|[1-9][0-9]{0,8})""")
        private val HASH = Regex("[0-9a-f]{64}")
        private const val MAX_SDK = 1000L
        private val DIRECTION = setOf('؜', '‎', '‏', ' ', ' ') + ('‪'..'‮') + ('⁦'..'⁩')
        private val json = Json
    }
}

/** A plugin the list names: its [id], its [name] and [summary] as the list gives them, the versionCodes it withdrew, and its versions, newest first. */
class ListedPlugin(val id: String, val name: String, val summary: String, val withdrawn: Set<Long>, val versions: List<ListedVersion>)

/**
 * A version the list gives: its [versionCode] and [versionName], the plugin API ([apiMajor].[apiMinor]) and shared
 * [libs] it was built against, its [minSdk], and its APK: [url], [size] in bytes and [sha256], which the download must
 * match before anything reads it.
 */
class ListedVersion(
    val versionCode: Long,
    val versionName: String,
    val apiMajor: Int,
    val apiMinor: Int,
    val libs: Map<String, String>,
    val minSdk: Int,
    val url: String,
    val size: Long,
    val sha256: String,
)

/**
 * What the list offers a plugin (L621): the version to install, or to update to ([offered]); the newest version listed,
 * above the installed one, where this NZStreamer is too old for it ([needsNewer]: "Version 1.1 · 1.2 needs a newer
 * NZStreamer", or for one not installed, "Needs a newer NZStreamer"); and whether the installed version is [withdrawn].
 */
class PluginOffer(val plugin: ListedPlugin, val offered: ListedVersion?, val needsNewer: ListedVersion?, val withdrawn: Boolean)

/** What reading the list came to. */
sealed interface IndexReading {
    class Listed(val index: PluginIndex) : IndexReading

    /** A later format than this build reads: the installed plugins stay as they are. */
    data object TooNew : IndexReading {
        /** The Plugins page's line above the cards (L589). */
        fun line(television: Boolean): String = "Update NZStreamer on this ${if (television) "TV" else "phone"} to see new plugins."
    }

    /** Made before the list already accepted: ignored, the copy kept. */
    data object Older : IndexReading

    /** Not read at all, for [fault]: the copy is kept. */
    class Refused(val fault: ListFault) : IndexReading
}

/** Why a whole list was refused. */
enum class ListFault { TooLarge, Unreadable, Format, Generated, TooMany }

/** Why an entry, a plugin or one of its versions, was dropped from the list. */
enum class ListDrop { Id, Repeated, Package, Text, Malformed, Address, Hash }
