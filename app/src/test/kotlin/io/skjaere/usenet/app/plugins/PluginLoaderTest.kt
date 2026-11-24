package io.skjaere.usenet.app.plugins

import io.skjaere.usenet.plugin.NzPlugin
import io.skjaere.usenet.plugin.PluginApi
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URLClassLoader
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Counts the static initializer of app.nzstreamer.plugin.fake.Shadow, kept apart so reading it doesn't run it. */
object ShadowInits {
    @JvmField
    var count = 0
}

/**
 * L579, L583: a plugin's classes load only after its file passed the checks, through a class loader over the file whose
 * parent is the app's own, so every shared class is the app's. Its entry is found without being initialized, and must
 * come from the plugin's own loader before it is: a name that resolves in the app through the parent never runs that
 * class's static initializer. A URLClassLoader over a jar of the invented plugin "fake"'s classes stands for Android's
 * PathClassLoader; the test's own copies of them are hidden from it, as a plugin's classes aren't in the app.
 */
class PluginLoaderTest {
    @TempDir
    lateinit var dir: File

    /** The app's class loader as the plugin's loader sees it: everything but the invented plugin's classes, Shadow apart. */
    private class AppLoader(parent: ClassLoader) : ClassLoader(parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name.startsWith(FAKE) && !name.startsWith("${FAKE}Shadow")) throw ClassNotFoundException(name)
            return super.loadClass(name, resolve)
        }
    }

    private val appLoader = AppLoader(PluginLoaderTest::class.java.classLoader!!)

    /** The invented plugin's classes but Shadow and Gone, copied from the test's classes into a jar, read-only as stored. */
    private val jar: File by lazy {
        val folder = File(PluginLoaderTest::class.java.classLoader!!.getResource(FAKE.replace('.', '/'))!!.toURI())
        val classes = folder.listFiles().orEmpty().filter { it.name.endsWith(".class") && !it.name.startsWith("Shadow") && !it.name.startsWith("Gone") }
        assertTrue(classes.any { it.name == "GoodPlugin.class" }, "the fake's classes: ${classes.map { it.name }}")
        File(dir, "plugin.apk").apply {
            JarOutputStream(outputStream()).use { out ->
                classes.forEach { file ->
                    out.putNextEntry(JarEntry(FAKE.replace('.', '/') + file.name))
                    out.write(file.readBytes())
                    out.closeEntry()
                }
            }
            setReadOnly()
        }
    }

    private var loadersMade = 0

    private fun loader(entry: String): Pair<PluginLoader, Expectation> {
        val meta = mapOf<String, Any?>(
            PluginApi.META_ID to "fake", PluginApi.META_ENTRY to entry, PluginApi.META_API_MAJOR to 1, PluginApi.META_API_MINOR to 0,
            PluginApi.META_LIBS to HOST_LIBS,
        )
        val reader = ArchiveReader {
            ArchiveFacts(
                "app.nzstreamer.plugin.fake", 1, "1", ArchiveSigners(SigningFacts(false, listOf(TEST_PIN)), emptyList()),
                object : ArchiveMeta {
                    override fun int(name: String): Int? = meta[name] as? Int
                    override fun string(name: String): String? = meta[name] as? String
                },
                hasResources = false,
            )
        }
        val verifier = PluginVerifier(reader, PluginCompat(1, 0, HOST_LIBS), trusted = { setOf(TEST_PIN) })
        val loader = PluginLoader(verifier, appLoader) { apk, parent ->
            loadersMade++
            URLClassLoader(arrayOf(apk.toURI().toURL()), parent)
        }
        return loader to Expectation("fake", Sha256.of(jar), 1, highestLoaded = 0, stored = true)
    }

    private fun load(entry: String): LoadResult = loader(entry).let { (loader, expected) -> loader.load(jar, expected) }

    @Test
    fun `an entry that passes is made from the plugin's own loader, whose parent is the app's`() {
        val loaded = assertIs<LoadResult.Loaded>(load("${FAKE}GoodPlugin"))
        val plugin: NzPlugin = loaded.plugin
        val pluginLoader = plugin.javaClass.classLoader
        assertIs<URLClassLoader>(pluginLoader)
        assertSame(appLoader, pluginLoader.parent)
        assertNotSame<Any>(app.nzstreamer.plugin.fake.GoodPlugin::class.java, plugin.javaClass, "not the test's own copy")
        assertSame<Any>(NzPlugin::class.java, pluginLoader.loadClass(NzPlugin::class.java.name), "the API is the app's")
        assertEquals("Fake", plugin.tab().label)
        assertEquals("${FAKE}GoodPlugin", loaded.facts.entry)
        assertEquals(1, loadersMade)
    }

    @Test
    fun `an entry that isn't a plugin, a missing class, a constructor that throws and a missing class it needs are each a reason`() {
        mapOf(
            "${FAKE}NotAPlugin" to LoadFailure.NotAPlugin,
            "${FAKE}Nowhere" to LoadFailure.Missing,
            "${FAKE}ThrowingPlugin" to LoadFailure.Constructor,
            "${FAKE}LinkagePlugin" to LoadFailure.Linkage,
        ).forEach { (entry, failure) ->
            val failed = assertIs<LoadResult.Failed>(load(entry), entry)
            assertEquals(failure, failed.failure, entry)
            assertEquals("its code didn't load", failed.failure.reason)
        }
    }

    @Test
    fun `an Error from the entry's static initializer or its constructor is a reason too, but the process's own goes up`() {
        listOf("${FAKE}UnwrittenPlugin", "${FAKE}AssertingPlugin").forEach { entry ->
            val failed = assertIs<LoadResult.Failed>(load(entry), entry)
            assertEquals("its code didn't load", failed.failure.reason)
        }
        assertIs<OutOfMemoryError>(runCatching { load("${FAKE}HungryPlugin") }.exceptionOrNull())
    }

    @Test
    fun `an entry outside the plugin's package is refused before any class loads`() {
        listOf("io.skjaere.usenet.app.plugins.FakePlugin", "app.nzstreamer.plugin.other.GoodPlugin").forEach { entry ->
            assertEquals(Refusal.Entry, assertIs<LoadResult.Refused>(load(entry), entry).refusal)
        }
        assertEquals(0, loadersMade)
    }

    @Test
    fun `a file that fails the checks makes no class loader`() {
        val (loader, expected) = loader("${FAKE}GoodPlugin")
        val refused = loader.load(jar, Expectation(expected.id, "0".repeat(64), 1, 0, stored = true))
        assertEquals(Refusal.Hash, assertIs<LoadResult.Refused>(refused).refusal)
        assertEquals(0, loadersMade)
        assertEquals(0, loader.classLoadersMade)
    }

    @Test
    fun `an entry the app's loader resolves is refused before its static initializer runs`() {
        val before = ShadowInits.count
        val failed = assertIs<LoadResult.Failed>(load("${FAKE}Shadow"))
        assertEquals(LoadFailure.NotItsOwn, failed.failure)
        assertEquals(before, ShadowInits.count, "Shadow's static initializer never ran")
    }

    private companion object {
        const val FAKE = "app.nzstreamer.plugin.fake."
    }
}
