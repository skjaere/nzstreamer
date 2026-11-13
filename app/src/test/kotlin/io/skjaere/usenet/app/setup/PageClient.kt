package io.skjaere.usenet.app.setup

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * A phone's browser on a setup page, for JVM tests against a real server on loopback: a GET, and a form posted as a
 * browser posts it, the 303 that answers a form that was read followed back to the page with a GET.
 */
internal class PageClient(private val url: String) {
    class Answer(val status: Int, val headers: Map<String, List<String>>, val body: String) {
        /** Every header's value and the body: what a page or an answer could leak through. */
        val everything: String get() = headers.values.flatten().joinToString("\n") + "\n" + body
    }

    /** What the server answered to each request, the 303s included. */
    val answers = mutableListOf<Answer>()

    fun get(): Answer = exchange(url, null)

    /** [action] pressed on [page], the open card holding [fields] (by default as the page filled it in): the page after it, or Send's own answer. */
    fun press(page: Answer, action: String, fields: List<Pair<String, String>> = openFields(page)): Answer {
        val answer = exchange(url, listOf("v" to version(page), "do" to action) + fields)
        if (answer.status != 303) return answer
        val location = answer.headers["Location"].orEmpty().single()
        check(location.startsWith("/") && !location.startsWith("//")) { "a 303 elsewhere" }
        return exchange(URL(URL(url), location.substringBefore('#')).toString(), null)
    }

    /** The open card's inputs as the page fills them in, as a browser sends them untouched: a box or a radio sent only when ticked. */
    fun openFields(page: Answer): List<Pair<String, String>> = Regex("<input [^>]*>").findAll(page.body).mapNotNull { match ->
        val input = match.value
        val name = Regex("""name="([^"]*)"""").find(input)?.groupValues?.get(1) ?: return@mapNotNull null
        when {
            name == "v" -> null
            "type=\"checkbox\"" in input -> if (" checked" in input) name to "on" else null
            // Of a radio group, the one checked, if any.
            "type=\"radio\"" in input -> if (" checked" in input) name to unescape(Regex("""value="([^"]*)"""").find(input)?.groupValues?.get(1).orEmpty()) else null
            else -> name to unescape(Regex("""value="([^"]*)"""").find(input)?.groupValues?.get(1).orEmpty())
        }
    }.toList()

    private fun version(page: Answer) = Regex("""name="v" value="([0-9]+)"""").find(page.body)?.groupValues?.get(1) ?: error("no form on the page")

    private fun unescape(text: String) =
        text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")

    private fun exchange(to: String, form: List<Pair<String, String>>?): Answer {
        val connection = URL(to).openConnection() as HttpURLConnection
        return try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 2_000
            connection.readTimeout = 40_000
            if (form != null) {
                val body = form.joinToString("&") { (name, value) -> "$name=${URLEncoder.encode(value, "UTF-8")}" }.toByteArray()
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status < 400) connection.inputStream else connection.errorStream
            Answer(status, connection.headerFields.filterKeys { it != null }, stream?.use { it.readBytes().decodeToString() }.orEmpty()).also { answers += it }
        } finally {
            connection.disconnect()
        }
    }
}
