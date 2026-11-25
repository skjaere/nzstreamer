package io.skjaere.usenet.plugin

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Test
import java.io.File
import java.util.jar.JarFile
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the API promises that its dump can't show: its version, which plugin-api.txt holds and the plugin build stamps
 * (L614); the rules for what a plugin hands the host (L613, L617), checked by the API's own functions; and no data class,
 * whose copy and componentN a minor couldn't grow without breaking a plugin built before it (L613).
 */
class PluginApiTest {
    private val icon = ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).build()
    private val handle = object : ReleaseHandle {}

    @Test
    fun `the version is plugin-api txt's, made of its major and minor`() {
        val file = File("plugin-api.txt").readText().trim()
        assertTrue(Regex("""(0|[1-9]\d*)\.(0|[1-9]\d*)""").matches(file), "plugin-api.txt holds '$file', not MAJOR.MINOR")
        assertEquals(file, PluginApi.VERSION)
        assertEquals("${PluginApi.MAJOR}.${PluginApi.MINOR}", PluginApi.VERSION)
    }

    /**
     * L654: a plugin's pages show NZStreamer's Cast button through the host's play, as the app's pages have it in their top
     * bars. PluginPlay is the host's to implement, so API 1.0 takes the member before the first plugin release without a
     * body, and its version stays (L614).
     */
    @Test
    fun `the host's play draws the Cast button for a plugin's pages, and the API's version stays`() {
        val button = PluginPlay::class.java.methods.filter { it.name == "PlayOnButton" }
        assertEquals(1, button.size, "PluginPlay declares PlayOnButton once: ${PluginPlay::class.java.methods.map { it.name }}")
        assertEquals(Modifier::class.java, button.single().parameterTypes.first(), "its first parameter, the modifier")
        assertTrue(java.lang.reflect.Modifier.isAbstract(button.single().modifiers), "the host's to implement")
        assertEquals("1.0", PluginApi.VERSION)
        assertEquals("1.0", File("plugin-api.txt").readText().trim())
    }

    /** The plugin build writes these and the host reads them: a rename would refuse every plugin built before it. */
    @Test
    fun `the manifest's meta-data keep their names`() {
        assertEquals(
            listOf("nzs.plugin.id", "nzs.plugin.entry", "nzs.plugin.api.major", "nzs.plugin.api.minor", "nzs.plugin.libs"),
            listOf(PluginApi.META_ID, PluginApi.META_ENTRY, PluginApi.META_API_MAJOR, PluginApi.META_API_MINOR, PluginApi.META_LIBS),
        )
    }

    @Test
    fun `an item's key is 1 to 200 letters, digits, dots, underscores, colons and dashes`() {
        listOf("ev-000731:main-card", "local:12:full", "Kestrel_Cup.2", "x", "k".repeat(200)).forEach { assertTrue(PluginKeys.validKey(it), it) }
        listOf("", "k".repeat(201), "main card", "a/b", "ä", "a\nb", "#1").forEach { assertFalse(PluginKeys.validKey(it), it) }
    }

    @Test
    fun `a page is 1 to 40 lower-case letters, digits and dashes, its argument at most 200 characters`() {
        listOf("second", "event-12", "crash", "p".repeat(40)).forEach { assertTrue(PluginKeys.validPage(it), it) }
        listOf("", "Second", "a_b", "a.b", "a/b", "p".repeat(41)).forEach { assertFalse(PluginKeys.validPage(it), it) }
        // Characters as a reader counts them: one outside the BMP is one, though Java keeps it as two chars.
        listOf(null, "", "ev-000731", "x".repeat(200), "ä".repeat(200), "\uD835\uDD38".repeat(200)).forEach { assertTrue(PluginKeys.validArg(it), it) }
        assertFalse(PluginKeys.validArg("x".repeat(201)))
    }

    /** The host stores one as plugin:<id>:<name>, so a name can't reach into another plugin's. */
    @Test
    fun `a secret's name is 1 to 40 lower-case letters, digits, dots, underscores and dashes`() {
        listOf("key", "api.key", "a_b-c", "n".repeat(40)).forEach { assertTrue(PluginKeys.validSecretName(it), it) }
        listOf("", "Key", "a:b", "plugin:other:key", "a b", "a/b", "n".repeat(41)).forEach { assertFalse(PluginKeys.validSecretName(it), it) }
    }

    @Test
    fun `a possible match's reason is 1 to 60 characters, and a tab's label 1 to 16`() {
        listOf("No date in the name", "r".repeat(60), "é".repeat(60)).forEach { assertTrue(PluginKeys.validReason(it), it) }
        listOf("", "   ", "r".repeat(61)).forEach { assertFalse(PluginKeys.validReason(it), it) }
        listOf("Skeleton", "Sports", "l".repeat(16), "\uD835\uDD38".repeat(16)).forEach { assertTrue(PluginKeys.validLabel(it), it) }
        listOf("", " ", "l".repeat(17)).forEach { assertFalse(PluginKeys.validLabel(it), it) }
    }

    /**
     * The item's own word in Choose release's lines ("the right game"): a lower-case letter, then up to 19 lower-case
     * letters, spaces and dashes. One outside the rule is no word at all, and the host's lines say "release" (L613, L628).
     */
    @Test
    fun `an item's noun is 1 to 20 lower-case letters, spaces and dashes, starting with a letter`() {
        listOf("game", "session", "main card", "pre-show", "g", "n".repeat(20)).forEach { assertTrue(PluginKeys.validNoun(it), it) }
        listOf("", "Game", "GAME", "game 2", "1st", " game", "-game", "game.", "n".repeat(21), "jeu é", "a\nb").forEach {
            assertFalse(PluginKeys.validNoun(it), it)
        }
    }

    @Test
    fun `an item keeps a noun inside the rule and reads one outside it as none`() {
        assertEquals("game", PlayItem("ev-1:main-card", "Kestrel Cup", null, 120, noun = "game").noun)
        assertEquals("main card", PlayItem("ev-1:main-card", "Kestrel Cup", null, 120, noun = "main card").noun)
        assertEquals(null, PlayItem("ev-1:main-card", "Kestrel Cup", null, 120).noun)
        listOf("Game", "game 2", "n".repeat(21)).forEach { outside ->
            assertEquals(null, PlayItem("ev-1:main-card", "Kestrel Cup", null, 120, noun = outside).noun, outside)
        }
    }

    /** Made by the plugin, so a bad one fails in its own code; the message names the rule, never the value (crash records keep it). */
    @Test
    fun `a tab, a possible match and an item refuse a value outside the rules, naming no value`() {
        assertEquals("Skeleton", PluginTab("Skeleton", icon).label)
        assertEquals("No date in the name", Offered(handle, "No date in the name").reason)
        assertEquals("ev-1:main-card", PlayItem("ev-1:main-card", "Kestrel Cup", null, 120).key)
        listOf(
            { PluginTab("Seventeen letters", icon) },
            { Offered(handle, "Copper Crown ".repeat(5)) },
            { PlayItem("Copper Crown", "Copper Crown", null, null) },
        ).forEach { make ->
            val refused = assertFailsWith<IllegalArgumentException> { make() }
            assertFalse(refused.message.orEmpty().contains("Seventeen") || refused.message.orEmpty().contains("Copper"), refused.message)
        }
    }

    @Test
    fun `no type in the API is a data class`() {
        val types = apiClasses()
        val values = listOf(
            "PluginTab", "Offered", "Found", "IndexerLine", "ReleaseInfo", "PlayItem", "ItemReleases", "IndexerNote", "SearchOffer",
            "FileInfo", "WatchInfo", "HttpAnswer", "PluginHttpException",
        )
        // The scan sees the value types, so it can't pass by finding nothing.
        assertTrue(types.map { it.simpleName }.containsAll(values), "found ${types.map { it.simpleName }}")
        val data = types.filter { type -> type.declaredMethods.any { it.name == "copy" || it.name == "component1" } }
        assertEquals(emptyList(), data.map { it.name })
    }

    /**
     * Two additions a later minor may make that would break a plugin built before it, unless the API says so from 1.0 on
     * (L614): a value added to an enum the host hands plugins (an exhaustive `when` over it throws on a value it doesn't
     * know), and a member added to an interface a plugin implements (an older plugin's class lacks it). Each one's KDoc
     * says what keeps a plugin safe: an else branch, a body.
     */
    @Test
    fun `each enum the host hands plugins says a minor may add a value, each interface a plugin implements a body`() {
        val sources = File("src/main/kotlin/io/skjaere/usenet/plugin").listFiles().orEmpty().filter { it.extension == "kt" }.map { it.readText() }
        val enums = sources.flatMap { text -> Regex("""enum class (\w+)""").findAll(text).map { it.groupValues[1] to kdocBefore(text, it.range.first) }.toList() }
        assertEquals(setOf("AddressSafety", "State", "Kind"), enums.map { it.first }.toSet(), "the API's enums")
        enums.forEach { (name, doc) ->
            assertTrue(doc.contains("a later minor may add a value", ignoreCase = true) && "else branch" in doc, "$name's KDoc: '$doc'")
        }
        listOf("NzPlugin", "PluginFilePicker").forEach { name ->
            val text = sources.single { "interface $name {" in it }
            val doc = kdocBefore(text, text.indexOf("interface $name {"))
            assertTrue("a later minor adds" in doc && "has a body" in doc, "$name's KDoc: '$doc'")
        }
    }

    /** The KDoc right before [at] in [text], its lines joined; "" where none is. */
    private fun kdocBefore(text: String, at: Int): String {
        val end = text.lastIndexOf("*/", at)
        if (end < 0 || text.substring(end + 2, at).isNotBlank()) return ""
        return text.substring(text.lastIndexOf("/**", end) + 3, end).lines().joinToString(" ") { it.trim().removePrefix("*").trim() }.trim()
    }

    /** Every class compiled into the API's package, nested ones too, from the folder or jar the test's class path has them in. */
    private fun apiClasses(): List<Class<*>> {
        val location = File((PluginApi::class.java.protectionDomain?.codeSource?.location ?: error("The API's classes have no location")).toURI())
        val path = "io/skjaere/usenet/plugin/"
        val files = if (location.isDirectory) {
            location.resolve(path).listFiles().orEmpty().filter { it.isFile }.map { it.name }
        } else {
            JarFile(location).use { jar ->
                jar.entries().asSequence().map { it.name }.filter { it.startsWith(path) }.map { it.removePrefix(path) }.filter { '/' !in it }.toList()
            }
        }
        return files.filter { it.endsWith(".class") }
            .map { Class.forName("io.skjaere.usenet.plugin." + it.removeSuffix(".class"), false, javaClass.classLoader) }
    }
}
