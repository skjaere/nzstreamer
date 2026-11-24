package io.skjaere.usenet.app.plugins

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * L622: the device tests put a plugin of their own in a slot through installForTest, which a debug build alone honours.
 * This runs in the debug and the release unit tests, and each answers its own way (UnitTestBuild); no code that ships
 * calls it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PluginTestSeamTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `installForTest puts a plugin in a slot in a debug build, and does nothing in any other`() = runTest {
        val h = RegistryHarness(dir, this)
        val registry = h.registry().apply { start() }
        val plugin = FakePlugin(label = "Kestrel")
        registry.installForTest(plugin, "kestrel")
        advanceUntilIdle()
        if (UnitTestBuild.debug) {
            val installed = registry.installed("kestrel")
            assertEquals(1, installed.slot)
            assertEquals("Kestrel", installed.label)
            assertEquals(plugin, assertNotNull(registry.running("kestrel")).plugin)
            assertEquals(emptyList(), h.store.readRegistry().plugins, "never written to the registry's file")
            registry.remove("kestrel")
            assertEquals(listOf("tab", "close"), plugin.calls)
        } else {
            assertEquals(emptyList(), registry.plugins.value, "a ${UnitTestBuild.type} build")
            assertEquals(emptyList(), plugin.calls)
        }
        assertEquals(emptyList(), registry.plugins.value)
    }

    @Test
    fun `no code that ships calls installForTest`() {
        val called = Regex("""(?<!fun )\binstallForTest\(""")
        val built = File("src").listFiles().orEmpty().filter { !it.name.startsWith("test") && it.name != "androidTest" }
        assertTrue(built.any { it.name == "main" }, "the app's source sets: ${built.map { it.name }}")
        val found = built.flatMap { set ->
            set.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line ->
                    "${file.relativeTo(File("src")).path}:${i + 1}".takeIf { called.containsMatchIn(line) && !line.trimStart().startsWith("*") }
                }
            }.toList()
        }
        assertEquals(emptyList(), found)
        assertTrue(called.containsMatchIn("registry.installForTest(plugin, \"kestrel\")"))
        assertTrue(!called.containsMatchIn("suspend fun installForTest(plugin: NzPlugin, id: String) {"))
    }
}
