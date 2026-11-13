package io.skjaere.usenet.app

import androidx.compose.ui.graphics.toArgb
import io.skjaere.usenet.app.ui.AppColors
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MainActivity starts on the splash theme, which installSplashScreen() swaps for the app's own: the
 * splash and the window under the app's screens both on the app's background (ui/Theme.kt), so
 * nothing else shows between them. The player and Import keep their themes.
 */
class SplashThemeTest {
    private val main = File("src/main")
    private val background = AppColors().background.toArgb()

    private fun parse(file: File): Element {
        assertTrue(file.isFile, "$file is missing")
        return DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file).documentElement
    }

    private fun Element.all(tag: String): List<Element> = getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

    private fun style(name: String): Element =
        parse(File(main, "res/values/themes.xml")).all("style").singleOrNull { it.getAttribute("name") == name } ?: throw AssertionError("no style $name")

    private fun Element.item(name: String): String =
        all("item").singleOrNull { it.getAttribute("name") == name }?.textContent?.trim() ?: throw AssertionError("${getAttribute("name")} has no $name")

    /** A colour resource's value, as an ARGB int. */
    private fun color(ref: String): Int {
        assertTrue(ref.startsWith("@color/"), "$ref isn't a colour resource")
        val value = parse(File(main, "res/values/colors.xml")).all("color").single { it.getAttribute("name") == ref.removePrefix("@color/") }.textContent.trim()
        assertTrue(Regex("#[0-9A-Fa-f]{8}").matches(value), "$ref is $value, not #AARRGGBB")
        return value.removePrefix("#").toLong(16).toInt()
    }

    @Test
    fun `MainActivity starts on the splash theme, the player and Import on their own`() {
        val activities = parse(File(main, "AndroidManifest.xml")).all("activity").associateBy { it.getAttributeNS(ANDROID_NS, "name") }
        assertEquals("@style/Theme.NZStreamer.Splash", activities.getValue(".MainActivity").getAttributeNS(ANDROID_NS, "theme"))
        assertEquals("@android:style/Theme.Material.NoActionBar.Fullscreen", activities.getValue(".PlayerActivity").getAttributeNS(ANDROID_NS, "theme"))
        assertEquals("@android:style/Theme.Translucent.NoTitleBar", activities.getValue(".OpenNzbActivity").getAttributeNS(ANDROID_NS, "theme"))
    }

    @Test
    fun `the splash shows the mark on the app's background, for as long as it animates`() {
        val splash = style("Theme.NZStreamer.Splash")
        assertEquals("Theme.SplashScreen", splash.getAttribute("parent"))
        assertEquals(background, color(splash.item("windowSplashScreenBackground")))
        assertEquals("@drawable/splash_mark", splash.item("windowSplashScreenAnimatedIcon"))
        // What BrandAssetsTest holds the animation to.
        assertEquals("@integer/splash_mark_duration", splash.item("windowSplashScreenAnimationDuration"))
        assertEquals("@style/Theme.NZStreamer", splash.item("postSplashScreenTheme"))
    }

    @Test
    fun `the app's theme after the splash is the platform's, on the app's background`() {
        val app = style("Theme.NZStreamer")
        assertEquals("@android:style/Theme.Material.NoActionBar", app.getAttribute("parent"))
        assertEquals(background, color(app.item("android:windowBackground")))
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
