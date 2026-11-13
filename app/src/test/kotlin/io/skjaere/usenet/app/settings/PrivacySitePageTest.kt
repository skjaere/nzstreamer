package io.skjaere.usenet.app.settings

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.skjaere.usenet.app.ui.AppColors
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Item 53, L137, W6: the site's privacy page holds the policy as the app's privacy.txt has it, in its hidden form (L211),
 * and nothing else; it runs no script, points nowhere but nzstreamer.app and e-mail, and shows the policy in the Privacy
 * page's colours. The page is kept private with the site. The site's publish script names it with -PprivacyPage=<file>,
 * which the build passes on as the system property privacyPage, and runs this class before each deploy, wanting all 3
 * tests run and passed. Every other run skips it.
 */
class PrivacySitePageTest {
    private val file = File("src/main/assets/privacy.txt")
    private val text by lazy { file.readText() }

    /** The policy as a build with debrid hidden shows it, which the site's page holds (L211). */
    private val hidden by lazy { privacySections(text, shown = false) }

    /** The site's page (L137), the file -PprivacyPage names. */
    private val page by lazy { File(System.getProperty(PAGE_PROPERTY)) }
    private val html by lazy { page.readText() }

    /** Without -PprivacyPage there is no page to check: skipped. */
    @BeforeEach
    fun `the site's page is named`() {
        assumeTrue(System.getProperty(PAGE_PROPERTY) != null, "no -PprivacyPage=<file>: the site's publish script names the page")
    }

    /**
     * Item 53, L137: nzstreamer.app/privacy shows the policy as the app does. Each heading of the asset is an <h2> and each
     * paragraph a <p>, in the asset's order, and the page shows no other paragraph, heading or text but its own title, the
     * <h1> before them, which is no part of the policy: not in its body, nor after it or in its head, where a browser shows
     * them too. Read as a browser shows the text: tags inside an element dropped, the entities the page uses decoded, white
     * space run together.
     */
    @Test
    fun `the site's page holds every heading and paragraph in order, and nothing else`() {
        assertTrue(page.isFile, "${page.canonicalPath} is missing")
        val shown = pageBody(html)
        val title = shown.blocks.firstOrNull() ?: fail("the page shows no paragraph or heading")
        assertTrue(title.startsWith("h1: "), "the page's title, an <h1>, comes first: $title")
        assertEquals(listOf(title), shown.blocks.filter { it.startsWith("h1: ") }, "the page has one <h1>")
        // The hidden form (L211): the site's page goes live with every beta, whichever way the beta was built.
        val policy = hidden.flatMap { section ->
            listOfNotNull(section.heading?.let { "h2: ${spaced(it)}" }) + section.paragraphs.map { "p: ${spaced(it)}" }
        }
        assertFalse(policy.any { it.substringAfter(": ") == title.removePrefix("h1: ") }, "the title is a part of the policy: $title")
        assertEquals(policy, shown.blocks.drop(1))
        assertEquals("", shown.rest, "the page shows text outside its paragraphs and headings")
        assertFalse(DEBRID_HEADING in html || "torrent indexers and debrid services" in html, "the debrid part or the opening's words")
    }

    /**
     * Item 53, L137: the page needs nothing but itself, as its Content-Security-Policy (the site's headers) allows its
     * inline style alone: no script, no link or resource on another host than nzstreamer.app but an e-mail address, and
     * its language named for screen readers and translators. It is made of [PAGE_TAGS] alone, so no other way to load
     * something or send the visitor elsewhere gets in: not an <img srcset> or a <video poster>, which the CSP blocks, nor a
     * <meta http-equiv="refresh">, which no CSP stops.
     */
    @Test
    fun `the site's page is in English, runs no script and points nowhere but this site and e-mail`() {
        assertTrue(page.isFile, "${page.canonicalPath} is missing")
        assertNull(Regex("(?i)<script").find(html)?.value, "a script")
        assertNull(Regex("""(?i)<[^>]*\son[a-z]+\s*=""").find(html)?.value, "an event handler, which is script")
        val links = Regex("""(?i)\b(?:src|href)\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)""").findAll(html)
            .map { decoded(it.groupValues[1].trim('"', '\'')).replace(Regex("[\t\n\r]"), "").trim() }.toList()
        assertEquals(emptyList(), links.filterNot(::onThisSite), "links or resources on another host")
        assertNull(Regex("""(?i)url\s*\(|@import""").find(html)?.value, "a resource from the style")
        val tag = Regex("""</?([A-Za-z][A-Za-z0-9-]*)((?:"[^"]*"|'[^']*'|[^'">])*)>""")
        val attribute = Regex("""([^\s"'>/=]+)(?:\s*=\s*(?:"[^"]*"|'[^']*'|[^\s"'>]+))?""")
        val uncommented = html.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        assertNull(Regex("</?[A-Za-z]").find(tag.replace(uncommented, ""))?.value, "a tag this test can't read, which it can't check")
        val unknown = tag.findAll(uncommented).flatMap { found ->
            val name = found.groupValues[1].lowercase()
            val allowed = PAGE_TAGS[name] ?: return@flatMap sequenceOf("<$name>")
            attribute.findAll(found.groupValues[2]).map { it.groupValues[1].lowercase() }.filterNot { it in allowed }.map { "<$name $it>" }
        }.distinct().toList()
        assertEquals(emptyList(), unknown, "tags or attributes the page isn't made of")
        assertTrue(Regex("""(?i)<html\b[^>]*\slang\s*=\s*"en"""").containsMatchIn(html), "no <html lang=\"en\">")
    }

    /**
     * Item 53, L137: the page shows the policy in the colours Settings › Privacy does (PrivacyScreen's PolicyParagraph, from
     * ui/Theme.kt): on the app's background, the title and headings in the text colour, and every paragraph, the opening
     * among them, in the secondary grey. Links, which the policy has none of in the app, are left to the page.
     */
    @Test
    fun `the site's page shows the policy in the colours of the app's Privacy page`() {
        assertTrue(page.isFile, "${page.canonicalPath} is missing")
        val app = AppColors()
        val rules = styleRules(html)
        val vars = rules.filter { it.selector == ":root" }.flatMap { it.declarations.entries }.associate { it.key to it.value }
        val backgrounds = rules.filter { it.selector == "html" || it.selector == "body" }
            .flatMap { rule -> listOf("background", "background-color").mapNotNull { rule.declarations[it] } }
        assertTrue(backgrounds.isNotEmpty(), "the page sets no background")
        backgrounds.forEach { assertEquals(hex(app.background), colourOf(it, vars), "the page's background") }
        assertEquals(hex(app.text), rules.lastOrNull { it.selector == "body" }?.declarations?.get("color")?.let { colourOf(it, vars) }, "the body's text")
        // What each rule draws its element's text in, and what the app draws it in: paragraphs in the secondary grey, the
        // rest (the title, the headings and what holds them) in the text colour.
        val drawn = rules.filter { it.element != "a" }.mapNotNull { rule -> rule.declarations["color"]?.let { rule to colourOf(it, vars) } }
        assertTrue(drawn.any { (rule, _) -> rule.element == "p" }, "the page sets no colour for its paragraphs")
        assertEquals(
            drawn.map { (rule, _) -> rule.selector to hex(if (rule.element == "p") app.textSecondary else app.text) },
            drawn.map { (rule, colour) -> rule.selector to colour },
            "the colours the page draws its text in",
        )
    }

    /** What a page shows: its paragraphs and headings in order ("p: …", "h2: …"), and its text outside them. */
    private class Shown(val blocks: List<String>, val rest: String)

    /**
     * What [html] shows, as [Shown]. A browser shows all that follows the head as the body, what comes before <body> and
     * after </body> or </html> too, so all of it is read; and a head holding more than its <meta>s, <title> and <style>
     * fails, as a browser would show the rest. A paragraph or heading left open, which this can't read, fails rather than
     * going unseen: one with no end, and one inside another (a lost </p>), where a browser ends the outer one, so that it
     * shows as two.
     */
    private fun pageBody(html: String): Shown {
        val any = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        val uncommented = html.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        val page = Regex("""\s*(?:<!doctype\s+html\s*>)?\s*(?:<html\b[^>]*>)?\s*<head\b[^>]*>(.*?)</head\s*>(.*)""", any).matchEntire(uncommented)
            ?: fail("the page doesn't open with its <head>…</head>, after only its doctype and <html>")
        val head = page.groupValues[1].replace(Regex("""<meta\b[^>]*>|<title\b[^>]*>.*?</title\s*>|<style\b[^>]*>.*?</style\s*>""", any), "")
        assertEquals("", head.trim(), "the page's <head> holds more than its <meta>s, <title> and <style>, which a browser may show")
        val body = page.groupValues[2].replace(Regex("""</?(?:body|html)\b[^>]*>""", RegexOption.IGNORE_CASE), " ")
        val element = Regex("""<(p|h[1-6])\b[^>]*>(.*?)</\1\s*>""", any)
        val blocks = element.findAll(body).map { found ->
            val (kind, inner) = found.groupValues[1].lowercase() to found.groupValues[2]
            Regex("</?([A-Za-z][A-Za-z0-9-]*)").findAll(inner).map { it.groupValues[1].lowercase() }.firstOrNull { it !in INLINE_TAGS }
                ?.let { fail("the page has a <$it> inside a <$kind>, where a browser may end the <$kind>: is a </$kind> missing before it?") }
            "$kind: ${textOf(inner)}"
        }.toList()
        val outside = element.replace(body, " ")
        Regex("""<(p|h[1-6])\b""", RegexOption.IGNORE_CASE).find(outside)?.let { fail("the page has a ${it.value}> this test can't read: is it closed?") }
        return Shown(blocks, spaced(decoded(outside.replace(Regex("<[^>]*>"), " "))))
    }

    /** [html]'s text as a browser shows it: tags dropped, entities decoded, white space run together. */
    private fun textOf(html: String): String = spaced(decoded(html.replace(Regex("<[^>]*>"), "")))

    private fun spaced(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    /** [text] with its HTML entities decoded. One this doesn't know fails, rather than comparing as the entity's name. */
    private fun decoded(text: String): String = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[A-Za-z][A-Za-z0-9]*);").replace(text) { entity ->
        val name = entity.groupValues[1]
        when {
            name.startsWith("#x", ignoreCase = true) -> String(Character.toChars(name.drop(2).toInt(16)))
            name.startsWith("#") -> String(Character.toChars(name.drop(1).toInt()))
            else -> NAMED_ENTITIES[name] ?: fail("the page uses &$name;, which this test doesn't decode")
        }
    }

    /** A rule of the page's style, one per selector of a list, and the element its selector's last part names ("p" in "h1 + p"). */
    private class Rule(val selector: String, val declarations: Map<String, String>) {
        val element = selector.split(Regex("[\\s>+~]+")).last().takeWhile { it.isLetterOrDigit() }.lowercase()
    }

    /** [html]'s <style> rules in order, comments dropped, with their declarations as property to value. */
    private fun styleRules(html: String): List<Rule> {
        val css = Regex("""<style\b[^>]*>(.*?)</style\s*>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .findAll(html).joinToString("\n") { it.groupValues[1] }.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        return Regex("""([^{}]+)\{([^{}]*)\}""").findAll(css).flatMap { rule ->
            val declarations = rule.groupValues[2].split(';').mapNotNull { d ->
                d.split(':', limit = 2).takeIf { it.size == 2 }?.let { (property, value) -> property.trim().lowercase() to value.trim() }
            }.toMap()
            rule.groupValues[1].split(',').map { Rule(spaced(it), declarations) }
        }.toList()
    }

    /** A colour [value] of the page's style as #RRGGBB, its var(--…) read from [vars]; one in another form stays as it is. */
    private fun colourOf(value: String, vars: Map<String, String>): String {
        val named = Regex("""var\(\s*(--[\w-]+)\s*(?:,[^)]*)?\)""").matchEntire(value)?.groupValues?.get(1)
        val colour = if (named == null) value else vars[named] ?: fail("the page's style uses $named, which :root doesn't set")
        return if (Regex("#[0-9A-Fa-f]{6}").matches(colour)) colour.uppercase() else colour
    }

    private fun hex(colour: Color): String = "#%06X".format(colour.toArgb() and 0xFFFFFF)

    /** Whether [link] stays on this site (a path on it, or https://nzstreamer.app itself) or is an e-mail address. */
    private fun onThisSite(link: String): Boolean {
        // Browsers read a backslash in a web address as a slash: \\example.com is //example.com, another host.
        val url = link.replace('\\', '/')
        return when {
            url.startsWith("mailto:", ignoreCase = true) -> true
            Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(url) || url.startsWith("//") ->
                Regex("""(?i)^(https:)?//nzstreamer\.app([/?#].*)?$""").matches(url)
            else -> true
        }
    }

    private companion object {
        /** The system property the build passes -PprivacyPage on as. */
        const val PAGE_PROPERTY = "privacyPage"

        /**
         * The tags the site's page is made of, each with the attributes it may have: none of them loads, runs or goes
         * anywhere, but an <a>'s href, which must stay on this site. One is added here once it is known to do none of that.
         */
        val PAGE_TAGS = mapOf(
            "html" to setOf("lang"), "head" to emptySet(), "meta" to setOf("charset", "name", "content"), "title" to emptySet(),
            "style" to emptySet(), "body" to emptySet(), "main" to emptySet(), "section" to emptySet(), "h1" to emptySet(),
            "h2" to emptySet(), "p" to emptySet(), "a" to setOf("href"),
        )

        /** Of [PAGE_TAGS], those a paragraph or heading may hold: a browser ends one at a <p>, <h1>-<h6> or <section>. */
        val INLINE_TAGS = setOf("a")

        /** The named entities a page may use for the policy's text; the typographic quotes are the characters themselves. */
        val NAMED_ENTITIES = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00A0")
    }
}
