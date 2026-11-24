package io.skjaere.usenet.plugin

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
