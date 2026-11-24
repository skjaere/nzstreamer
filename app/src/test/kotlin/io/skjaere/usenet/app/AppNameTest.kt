package io.skjaere.usenet.app

import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The app is called NZStreamer wherever the user sees its name: the launcher, a shared log, a share's subject. */
class AppNameTest {
    private val main = File("src/main")

    /** What the app shows, writes and speaks: its own sources and resources, and the UI kit's (:core:ui), which it draws with. */
    private val words = listOf(main, File("../core/ui/src/main"))

    private fun parse(file: File): Element =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file).documentElement

    @Test
    fun `the launcher calls the app NZStreamer`() {
        val application = parse(File(main, "AndroidManifest.xml")).getElementsByTagName("application").item(0) as Element
        assertEquals("@string/app_name", application.getAttributeNS(ANDROID_NS, "label"))
        val strings = parse(File(main, "res/values/strings.xml")).getElementsByTagName("string")
        val appName = (0 until strings.length).map { strings.item(it) as Element }.single { it.getAttribute("name") == "app_name" }
        assertEquals("NZStreamer", appName.textContent)
    }

    /** The placeholder name, in any spelling ("Usenet Streamer", "usenet-streamer"), is nowhere in what the app shows or writes. */
    @Test
    fun `the old name is gone from the app's and the UI kit's sources and resources`() {
        val old = Regex("""usenet[ -]?streamer""", RegexOption.IGNORE_CASE)
        words.forEach { assertTrue(it.isDirectory, "${it.path} isn't there to read") }
        val found = words.flatMap { root ->
            root.walk().filter { it.isFile && it.extension in setOf("kt", "xml") }
                .flatMap { file -> file.readLines().mapIndexedNotNull { index, line -> "${root.path}/${file.relativeTo(root).path}:${index + 1}".takeIf { old in line } } }
                .toList()
        }
        assertEquals(emptyList(), found)
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
