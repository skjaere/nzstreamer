package io.skjaere.usenet.newznab

import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.decodeURLPart
import java.nio.charset.Charset

/**
 * An NZB as its indexer sent it ([NewznabClient.download]), with the archive password the indexer sent beside it, if any:
 * the `X-DNZB-Password` header, else the one in the NZB's file name, as SABnzbd reads them. The NZB's own password (its
 * `<meta type="password">`) is in [bytes].
 */
class NzbDownload(val bytes: ByteArray, val password: String? = null) {
    companion object {
        /** The password in [headers]: `X-DNZB-Password`, else the file name Content-Disposition gives ([passwordInName]). */
        internal fun passwordOf(headers: Headers): String? =
            headers[PASSWORD_HEADER]?.trim()?.takeIf { it.isNotEmpty() }
                ?: headers[HttpHeaders.ContentDisposition]?.let(::fileNameOf)?.let(::passwordInName)

        /**
         * The password in an NZB's file name as SABnzbd reads it: "name{{password}}.nzb" or "name password=password.nzb";
         * null in any other name.
         */
        internal fun passwordInName(fileName: String): String? {
            val name = fileName.trim().replace(NZB_EXTENSION, "")
            val found = BRACES.find(name) ?: PASSWORD_WORD.find(name) ?: return null
            return found.groupValues[1].trim().takeIf { it.isNotEmpty() }
        }

        /** The file name in a Content-Disposition value: its `filename*` (RFC 5987, percent-encoded) over its `filename`. */
        private fun fileNameOf(disposition: String): String? {
            EXTENDED_NAME.find(disposition)?.let { m ->
                val charset = runCatching { Charset.forName(m.groupValues[1]) }.getOrDefault(Charsets.UTF_8)
                return runCatching { m.groupValues[2].trim().decodeURLPart(charset = charset) }.getOrNull()
            }
            val m = PLAIN_NAME.find(disposition) ?: return null
            return m.groups[1]?.value?.replace(Regex("\\\\(.)"), "$1") ?: m.groupValues[2].trim()
        }

        private const val PASSWORD_HEADER = "X-DNZB-Password"
        private val NZB_EXTENSION = Regex("\\.nzb$", RegexOption.IGNORE_CASE)
        // Every brace escaped: Android's regex engine (ICU) rejects a bare one.
        private val BRACES = Regex("\\{\\{(.+)\\}\\}$")
        private val PASSWORD_WORD = Regex("\\spassword=(.+)$", RegexOption.IGNORE_CASE)
        private val EXTENDED_NAME = Regex("(?i)filename\\*\\s*=\\s*([^']*)'[^']*'([^;]+)")
        private val PLAIN_NAME = Regex("(?i)(?:^|[;\\s])filename\\s*=\\s*(?:\"((?:[^\"\\\\]|\\\\.)*)\"|([^;]+))")
    }
}
