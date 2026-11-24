package io.skjaere.usenet.app.plugins

import androidx.annotation.VisibleForTesting
import io.skjaere.usenet.app.BuildConfig
import java.net.URI
import java.net.URISyntaxException

/**
 * Where plugins come from (L620): the plugin list's address, and the rule an APK's address must keep, which pins it to one
 * repository's plugin releases. NZStreamer's own source is GitHub's ([GITHUB]), the same in every build. The list and the
 * downloads go over https; http only to this device's loopback address, and only from a source that allows it: a test's
 * own server ([loopback]). The clients take their source as they are made, so a JVM test gives its own directly; the app
 * reads [current] at each read, where a device test's source counts in a debug build alone (L622).
 */
class PluginSource(
    /** The list's address. */
    val list: String,
    /** The scheme, host and port the APKs are downloaded from, as "https://github.com": [RELEASES] and the rest follow. */
    releases: String,
    /** Whether http to this device's loopback address is let through: a test's own server, never GitHub. */
    val loopbackHttp: Boolean = false,
) {
    private val origin: URI = URI(releases)

    /** Whether a request may go to [address]: https, or http to the loopback address where [loopbackHttp] allows it. */
    fun allows(address: String): Boolean {
        val uri = parse(address) ?: return false
        val host = uri.host?.lowercase() ?: return false
        return when (uri.scheme?.lowercase()) {
            "https" -> true
            "http" -> loopbackHttp && host in LOOPBACK
            else -> false
        }
    }

    /**
     * Whether [address] is plugin [id]'s APK among the repository's plugin releases, checked whole, never by a prefix: a
     * prefix would pass ".../plugin-x-v1/../../../../other/repo/...", which the HTTP client resolves as it builds the
     * request. Parsed, it must have this source's scheme, host and port (none for GitHub), no user, query or fragment,
     * and exactly the path /skjaere/nzstreamer/releases/download/plugin-<id>-v<major.minor.patch>/<id>.apk: no "." or
     * ".." segment, nothing percent-encoded. An id outside the rule (PluginIds) never makes an address.
     */
    fun apkAddress(id: String, address: String): Boolean {
        if (!PluginIds.valid(id)) return false
        val uri = parse(address) ?: return false
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return false
        if (!uri.scheme.equals(origin.scheme, ignoreCase = true) || !uri.rawAuthority.equals(origin.rawAuthority, ignoreCase = true)) return false
        val path = uri.rawPath ?: return false
        if ('%' in path || path.split('/').any { it == "." || it == ".." }) return false
        val match = APK_PATH.matchEntire(path) ?: return false
        return match.groupValues[1] == id && match.groupValues[2] == id
    }

    companion object {
        /** The list's address in every build (L620), on the branch only CI writes (W156). CI's step reads it from here. */
        const val LIST = "https://raw.githubusercontent.com/skjaere/nzstreamer/refs/heads/plugin-index/index.json"

        /** Where the repository's release downloads are, under the releases' host. */
        const val RELEASES = "/skjaere/nzstreamer/releases/download/"

        /** The User-Agent of every request for the list or a plugin: the app's name, and no version or device. */
        const val USER_AGENT = "NZStreamer"

        val GITHUB = PluginSource(LIST, releases = "https://github.com")

        /** A test's own server on this device's loopback address at [port]: the list at /index.json, the APKs as GitHub's. */
        fun loopback(port: Int): PluginSource =
            PluginSource("http://127.0.0.1:$port/index.json", releases = "http://127.0.0.1:$port", loopbackHttp = true)

        /** An address nothing answers at (the discard port on this device): a read there fails at once, as GitHub down. */
        @VisibleForTesting
        internal val CLOSED = loopback(port = 9)

        /**
         * Test seam: the device tests' source in place of GitHub's, which their runner points at [CLOSED] for the whole run
         * and the plugins' own classes at a list of theirs. Honoured only where BuildConfig.DEBUG.
         */
        @VisibleForTesting
        @Volatile
        internal var override: PluginSource? = null

        /** The source the app reads now: GitHub's, but for a device test's in a debug build. */
        fun current(): PluginSource = override?.takeIf { BuildConfig.DEBUG } ?: GITHUB

        private val LOOPBACK = setOf("127.0.0.1", "localhost", "[::1]")

        // The id twice, so a release's file is the plugin's own; one id can't hold "-v" (lower-case letters and digits alone).
        private val APK_PATH = Regex(
            Regex.escape(RELEASES) + """plugin-([a-z0-9]+)-v(?:0|[1-9][0-9]{0,8})\.(?:0|[1-9][0-9]{0,8})\.(?:0|[1-9][0-9]{0,8})/([a-z0-9]+)\.apk""",
        )

        /** [address] as an absolute, hierarchical address; null for one that doesn't parse (a space or a backslash in it). */
        private fun parse(address: String): URI? = try {
            URI(address).takeIf { it.isAbsolute && !it.isOpaque }
        } catch (e: URISyntaxException) {
            null
        }
    }
}
