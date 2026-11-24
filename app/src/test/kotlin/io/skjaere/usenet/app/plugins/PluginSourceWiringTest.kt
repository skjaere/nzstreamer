package io.skjaere.usenet.app.plugins

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * L620, L622: every build reads the plugin list at one address, GitHub's, and takes APKs from the repository's plugin
 * releases alone. A device test's own list takes its place only where the app wires the current source, and only in a
 * debug build. This runs in the debug and the release unit tests, and each answers its own way: which build runs comes
 * from the test task (UnitTestBuild), never from BuildConfig.
 */
class PluginSourceWiringTest {
    @AfterEach
    fun putBack() {
        PluginSource.override = null
    }

    @Test
    fun `NZStreamer's own source is the list on the plugin-index branch, https alone`() {
        val github = PluginSource.GITHUB
        assertEquals("https://raw.githubusercontent.com/skjaere/nzstreamer/refs/heads/plugin-index/index.json", github.list)
        assertFalse(github.loopbackHttp)
        assertTrue(github.allows(github.list))
        assertFalse(github.allows("http://127.0.0.1:8123/index.json"))
        assertFalse(github.allows("http://raw.githubusercontent.com/skjaere/nzstreamer/refs/heads/plugin-index/index.json"))
        assertTrue(github.apkAddress("harbor", "https://github.com/skjaere/nzstreamer/releases/download/plugin-harbor-v1.0.0/harbor.apk"))
    }

    @Test
    fun `with no test source the app reads GitHub's list, in every build`() {
        assertSame(PluginSource.GITHUB, PluginSource.current())
    }

    @Test
    fun `a test source is the app's in a debug build alone`() {
        val loopback = PluginSource.loopback(port = 8123)
        PluginSource.override = loopback
        val expected = if (UnitTestBuild.debug) loopback else PluginSource.GITHUB
        assertSame(expected, PluginSource.current(), "a ${UnitTestBuild.type} build")
    }

    @Test
    fun `the device tests' closed source answers nothing, on this device's discard port`() {
        assertEquals("http://127.0.0.1:9/index.json", PluginSource.CLOSED.list)
        assertTrue(PluginSource.CLOSED.allows(PluginSource.CLOSED.list))
    }

    @Test
    fun `nothing but the device tests sets the test source`() {
        val sets = Regex("""\bPluginSource\.override\s*=(?!=)""")
        val built = File("src").listFiles().orEmpty().filter { !it.name.startsWith("test") && it.name != "androidTest" }
        assertTrue(built.any { it.name == "main" }, "the app's source sets: ${built.map { it.name }}")
        val found = built.flatMap { set ->
            set.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { file ->
                file.readLines().mapIndexedNotNull { i, line -> "${file.relativeTo(File("src")).path}:${i + 1}".takeIf { sets.containsMatchIn(line) } }
            }.toList()
        }
        assertEquals(emptyList(), found, "set outside the device tests")
        val own = File("src/main/kotlin/io/skjaere/usenet/app/plugins/PluginSource.kt").readLines()
        assertEquals(emptyList(), own.filter { Regex("""\boverride\s*=(?!=)""").containsMatchIn(it) }, "PluginSource's own writes")
        val runner = File("src/androidTest/kotlin/io/skjaere/usenet/app/AppTestRunner.kt").readText()
        assertEquals(listOf("PluginSource.override = PluginSource.CLOSED"), sets.findAll(runner).map { runner.substring(it.range.first).lineSequence().first().trim() }.toList())
    }

    /**
     * The app's own list, as the container makes it, asks the current source at each read: a test's in a debug build, and
     * GitHub's in any other. Its client here is ktor's mock, which keeps the address asked and sends nothing anywhere.
     */
    @Test
    fun `the app's list reads from the test source in a debug build alone, chosen at each read`(@TempDir dir: File) = runBlocking<Unit> {
        val asked = mutableListOf<String>()
        val http = HttpClient(MockEngine) {
            engine { addHandler { request -> asked += request.url.toString(); respond("", HttpStatusCode.NotFound) } }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val list = PluginList.of(dir, installed = { false }, http = { http }, scope = scope)
            PluginSource.override = PluginSource.loopback(port = 8123)
            list.checkAgain().join()
            PluginSource.override = PluginSource.loopback(port = 8124)
            list.checkAgain().join()
            val expected = if (UnitTestBuild.debug) listOf("http://127.0.0.1:8123/index.json", "http://127.0.0.1:8124/index.json") else List(2) { PluginSource.LIST }
            assertEquals(expected, asked, "a ${UnitTestBuild.type} build")
        } finally {
            scope.cancel()
            http.close()
        }
    }
}
