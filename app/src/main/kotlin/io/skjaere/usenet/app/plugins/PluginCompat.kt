package io.skjaere.usenet.app.plugins

import io.skjaere.usenet.app.BuildConfig
import io.skjaere.usenet.plugin.PluginApi

/**
 * Which plugin builds this NZStreamer runs (L584): one built against the same plugin API major and a minor not above this
 * one's, and against shared libraries (L615) not newer than this build's own. Kotlin and AndroidX promise that compiled
 * code runs on newer libraries, not on older ones; a major may break either way, so for every library but the Compose BOM
 * the major must be the same too (a BOM's first number is its year, not a major). A library the plugin names that this
 * build doesn't know refuses it; one this build has that the plugin leaves out is fine.
 */
class PluginCompat(
    private val hostMajor: Int = PluginApi.MAJOR,
    private val hostMinor: Int = PluginApi.MINOR,
    hostLibs: String = BuildConfig.PLUGIN_HOST_LIBS,
) {
    private val host: Map<String, LibVersion> = parseLibs(hostLibs).orEmpty()
        .mapNotNull { (name, version) -> LibVersion.parse(version)?.let { name to it } }.toMap()

    fun apiAccepted(major: Int, minor: Int): Boolean = major == hostMajor && minor in 0..hostMinor

    fun libsAccepted(libs: Map<String, String>): Boolean = libs.all { (name, given) ->
        val own = host[name] ?: return@all false
        val version = LibVersion.parse(given) ?: return@all false
        (name in YEARLY || version.major == own.major) && version.notAbove(own)
    }

    /** A plugin's stamp and its nzs.plugin.libs as its manifest gives them; a list that is missing or doesn't read refuses. */
    fun accepts(major: Int, minor: Int, libs: String?): Boolean =
        apiAccepted(major, minor) && libs?.let(::parseLibs)?.let(::libsAccepted) == true

    companion object {
        /** Libraries versioned by date, compared by "not newer" alone. */
        private val YEARLY = setOf("compose-bom")

        /**
         * "name=version" pairs separated by commas, as the build writes nzs.plugin.libs and BuildConfig.PLUGIN_HOST_LIBS;
         * null for a list that doesn't read (a pair without its name or version, a name given twice). "" is no library.
         */
        fun parseLibs(text: String): Map<String, String>? {
            if (text.isEmpty()) return emptyMap()
            val pairs = text.split(',').map { pair ->
                val name = pair.substringBefore('=', "").trim()
                val version = pair.substringAfter('=', "").trim()
                if (name.isEmpty() || version.isEmpty()) return null
                name to version
            }
            return pairs.toMap().takeIf { it.size == pairs.size }
        }
    }
}

/**
 * A library's version: its numbers, and a pre-release's qualifier ("2.3.21-RC"), which comes before the release of the same
 * numbers. Two qualifiers of the same numbers can't be ordered here: a plugin's then counts as newer, and is refused.
 */
private class LibVersion(private val numbers: List<Int>, private val qualifier: String?) {
    val major: Int get() = numbers.first()

    fun notAbove(other: LibVersion): Boolean {
        val length = maxOf(numbers.size, other.numbers.size)
        for (i in 0 until length) {
            val mine = numbers.getOrElse(i) { 0 }
            val theirs = other.numbers.getOrElse(i) { 0 }
            if (mine != theirs) return mine < theirs
        }
        return when {
            qualifier == other.qualifier -> true
            other.qualifier == null -> true
            else -> false
        }
    }

    companion object {
        private val FORM = Regex("""(\d{1,9}(?:\.\d{1,9})*)(?:-([0-9A-Za-z.-]+))?""")

        fun parse(text: String): LibVersion? {
            val match = FORM.matchEntire(text) ?: return null
            return LibVersion(match.groupValues[1].split('.').map { it.toInt() }, match.groupValues[2].ifEmpty { null })
        }
    }
}
