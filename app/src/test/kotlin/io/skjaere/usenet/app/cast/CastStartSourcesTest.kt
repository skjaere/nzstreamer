package io.skjaere.usenet.app.cast

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * L50, L73: Google's Cast software starts in two places alone. CastGate starts it (Cast's `initialize(`), at the Play on
 * sheet's opening, never on a TV; CastPlayers builds the remote player once a session starts. A Cast context fetched or a
 * remote player built anywhere else would start Google's software (and what it sends Google) where the gate can't see:
 * at the app's start, on a TV. Every source the app and its device tests build from is read, as NoPlatformDiagnosticsTest
 * reads them: main's, Discover's, the release (Play) build's own and the device tests'.
 *
 * L108: nor does the app use Google's Cast widgets, anywhere in its sources, resources or manifest. Each starts Google's
 * software as it draws (Media3's and MediaRouter's buttons, Google's overlay), opens Google's own dialogs, or needs
 * AppCompat, which the app's activities aren't; none of it would pass through the gate (C-1). They are banned as whole
 * names, so an import's alias is caught at its import line, and in whole files, comments included: a comment says
 * "Google's overlay", never a banned name. The app's own button is CastButton.
 */
class CastStartSourcesTest {
    private val roots = listOf("src/main/kotlin", "src/discover/kotlin", "src/androidTest/kotlin", "src/release/kotlin").map(::File)

    private val sources = roots.flatMap { root -> root.walk().filter { it.extension == "kt" || it.extension == "java" }.toList() }

    /** The app's resources, layouts among them, and its manifest: where a widget is named as XML names it. */
    private val resources = File("src/main/res").walk().filter { it.isFile && it.extension == "xml" }.toList()
    private val manifest = File("src/main/AndroidManifest.xml")

    /** The only files that may start Cast. */
    private val allowed = setOf("CastGate.kt", "CastPlayers.kt")

    /** A Cast context's or a remote player's start, wherever it is. */
    private val anywhere = listOf(Regex("""CastContext\s*\.\s*getSharedInstance\s*\("""), Regex("""RemoteCastPlayer\s*\.\s*Builder\s*\("""))

    /**
     * Cast's own start (`Cast.getSingletonInstance(context).initialize(params)`, or the manifest's way, `ensureInitialized`):
     * an `initialize(` in a file that uses Media3's Cast singleton. A key pair generator's `initialize(` elsewhere isn't Cast's.
     */
    private val castStart = Regex("""\b(initialize|ensureInitialized)\s*\(""")

    /**
     * Google's Cast widgets (L108): Media3's and MediaRouter's route buttons and their factories and view providers,
     * Google's button factory, its introductory overlay, its mini and expanded controllers and their UI controller,
     * MediaRouter's chooser and controller dialogs and action provider; and the packages they come from.
     */
    private val widgets: List<Regex> = listOf(
        """MediaRouteButton\w*""", "setMediaRouteButtonViewProvider", "CastButtonFactory", "IntroductoryOverlay", "MiniControllerFragment",
        "ExpandedControllerActivity", """MediaRouteChooserDialog\w*""", """MediaRouteControllerDialog\w*""", "MediaRouteActionProvider", "UIMediaController",
    ).map { Regex("""\b$it\b""") } + listOf(
        "androidx.mediarouter.app.", "com.google.android.gms.cast.framework.media.widget.", "com.google.android.gms.cast.framework.media.uicontroller.",
    ).map { Regex("""\b""" + Regex.escape(it)) }

    private fun usesCast(text: String) = "androidx.media3.cast" in text || "getSingletonInstance(" in text

    private fun lineOf(text: String, at: Int) = text.substring(0, at).count { it == '\n' } + 1

    private val starts: List<String> = sources.flatMap { file ->
        val text = file.readText()
        val found = anywhere.flatMap { it.findAll(text).toList() } + if (usesCast(text)) castStart.findAll(text).toList() else emptyList()
        found.map { "${file.name}:${lineOf(text, it.range.first)}" }
    }

    /** Each widget named, where: the file from the app's directory, its line, the name. */
    private val widgetsNamed: List<String> = (sources + resources + manifest).flatMap { file ->
        val text = file.readText()
        widgets.flatMap { it.findAll(text).toList() }.sortedBy { it.range.first }.map { "${file.path}:${lineOf(text, it.range.first)}: ${it.value}" }
    }

    @Test
    fun `the sources are read`() {
        roots.forEach { assertTrue(it.isDirectory, "no sources under ${it.absolutePath}") }
        assertTrue(sources.size > 100, "found ${sources.size} files from ${File(".").absolutePath}")
        assertTrue(resources.any { it.parentFile.name.startsWith("drawable") } && resources.any { it.parentFile.name == "xml" }, "found ${resources.size} resources")
        assertTrue(manifest.isFile, "no manifest at ${manifest.absolutePath}")
    }

    @Test
    fun `Cast starts in CastGate and CastPlayers alone`() {
        assertEquals(emptyList(), starts.filter { it.substringBefore(':') !in allowed }, "Cast started outside CastGate.kt and CastPlayers.kt")
    }

    @Test
    fun `the gate's start is found`() {
        // The check above would pass on a pattern that finds nothing: the gate's own start is one it must find.
        assertTrue(starts.any { it.startsWith("CastGate.kt:") }, "CastGate's initialize( not found: $starts")
    }

    @Test
    fun `no Google Cast widget is named in the app's sources, resources or manifest`() {
        assertEquals(emptyList(), widgetsNamed, "Google's Cast widgets start its software as they draw, outside the gate (L108)")
    }

    @Test
    fun `the widgets' names are found where they are written`() {
        // The check above would pass on patterns that find nothing: each name, written as code, XML or a comment writes it.
        val written = listOf(
            "import androidx.media3.cast.MediaRouteButton as Button", "MediaRouteButtonFactory.create(context)", "setMediaRouteButtonViewProvider(it)",
            "CastButtonFactory.setUpMediaRouteButton(context, button)", "IntroductoryOverlay.Builder(activity, button)", "// a MiniControllerFragment",
            "<activity android:name=\"com.google.android.gms.cast.framework.media.widget.ExpandedControllerActivity\" />",
            "MediaRouteChooserDialogFragment()", "MediaRouteControllerDialog(context)", "MediaRouteActionProvider(context)", "UIMediaController(activity)",
            "<androidx.mediarouter.app.MediaRouteButton android:id=\"@+id/cast\" />", "import com.google.android.gms.cast.framework.media.uicontroller.UIController",
        )
        written.forEach { line -> assertTrue(widgets.any { it.containsMatchIn(line) }, line) }
        // Whole names alone: the app's own button and casting package are no widget of Google's.
        listOf("CastButton(target, onClick)", "io.skjaere.usenet.app.casting.CastSheet", "PlayOnButton").forEach { line ->
            assertEquals(false, widgets.any { it.containsMatchIn(line) }, line)
        }
    }
}
