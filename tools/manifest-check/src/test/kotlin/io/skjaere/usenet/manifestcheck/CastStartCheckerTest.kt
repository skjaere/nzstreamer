package io.skjaere.usenet.manifestcheck

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * checkCastStartsLate (L107) over merged manifests: what C1's debug build merges for Google Cast passes; each entry that
 * would start Google's Cast software, or let another app or the system start one of its components, without the app's
 * gate fails, as does a manifest that isn't the app's.
 */
class CastStartCheckerTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.getResource("/fixtures/$name")) { "missing fixture $name" }.readText()

    private val merged = fixture("cast-merged.xml")

    /** The merged fixture with [entry] added inside its application. */
    private fun withEntry(entry: String): String = merged.replace("</application>", "$entry\n    </application>")

    /** The merged fixture with [old] replaced by [new]; [old] must be in it once. */
    private fun merged(old: String, new: String): String {
        require(merged.split(old).size == 2) { "\"$old\" is not in the fixture once" }
        return merged.replace(old, new)
    }

    private fun check(manifest: String) = CastStartChecker.check(manifest)

    @Test
    fun `what Google Cast's libraries and the app's cast package merge passes`() {
        assertEquals(emptyList(), check(merged))
    }

    @Test
    fun `the Cast framework's options provider fails`() {
        val manifest = withEntry(
            """<meta-data android:name="com.google.android.gms.cast.framework.OPTIONS_PROVIDER_CLASS_NAME" android:value="io.skjaere.usenet.app.cast.Options" />""",
        )
        assertEquals(
            listOf("meta-data com.google.android.gms.cast.framework.OPTIONS_PROVIDER_CLASS_NAME: Google's Cast software could start without the app's gate, as a Cast button draws or a library asks for its context"),
            check(manifest),
        )
    }

    @Test
    fun `MediaRouter's MediaTransferReceiver fails`() {
        val manifest = withEntry("""<receiver android:name="androidx.mediarouter.media.MediaTransferReceiver" android:exported="false" />""")
        assertEquals(
            listOf("receiver androidx.mediarouter.media.MediaTransferReceiver: the system's output switcher could move the app's media to a Cast device without the app"),
            check(manifest),
        )
    }

    @Test
    fun `a provider of Google's, MediaRouter's or Media3 Cast's fails`() {
        listOf("com.google.android.gms.cast.CastProvider", "androidx.mediarouter.media.RouteProvider", "androidx.media3.cast.CastInitProvider").forEach { name ->
            val manifest = withEntry("""<provider android:name="$name" android:authorities="io.skjaere.usenet.x" android:exported="false" />""")
            assertEquals(listOf("provider $name: it runs as the app's process starts, before the app's gate"), check(manifest), name)
        }
    }

    @Test
    fun `an initializer of Google's, MediaRouter's or Media3 Cast's under androidx startup fails, the others pass`() {
        listOf("com.google.android.gms.cast.CastInitializer", "androidx.mediarouter.media.RouterInitializer", "androidx.media3.cast.CastInitializer").forEach { name ->
            val manifest = merged(
                """                android:name="androidx.profileinstaller.ProfileInstallerInitializer"
                android:value="androidx.startup" />""",
                """                android:name="androidx.profileinstaller.ProfileInstallerInitializer"
                android:value="androidx.startup" />
            <meta-data
                android:name="$name"
                android:value="androidx.startup" />""",
            )
            assertEquals(listOf("meta-data $name under androidx.startup.InitializationProvider: it runs as the app's process starts, before the app's gate"), check(manifest), name)
        }
        // emoji2's, lifecycle's, okhttp's and profileinstaller's initializers are in the fixture, and pass.
        listOf("androidx.emoji2.", "androidx.lifecycle.", "okhttp3.", "androidx.profileinstaller.").forEach { assertTrue(it in merged, it) }
    }

    @Test
    fun `the advertising ID's permission fails`() {
        val manifest = merged(
            "<uses-permission android:name=\"android.permission.INTERNET\" />",
            "<uses-permission android:name=\"android.permission.INTERNET\" />\n    <uses-permission android:name=\"com.google.android.gms.permission.AD_ID\" />",
        )
        assertEquals(listOf("uses-permission com.google.android.gms.permission.AD_ID: the advertising ID, which the app never reads"), check(manifest))
    }

    @Test
    fun `a component of Google's exported or with an intent filter fails`() {
        val exported = merged(
            "android:name=\"com.google.android.gms.cast.framework.media.MediaIntentReceiver\"\n            android:exported=\"false\"",
            "android:name=\"com.google.android.gms.cast.framework.media.MediaIntentReceiver\"\n            android:exported=\"true\"",
        )
        assertEquals(
            listOf("receiver com.google.android.gms.cast.framework.media.MediaIntentReceiver is exported: another app could start it"),
            check(exported),
        )
        val boot = merged(
            """        <service
            android:name="com.google.android.gms.cast.framework.ReconnectionService"
            android:exported="false" />""",
            """        <receiver
            android:name="com.google.android.gms.cast.framework.ReconnectionReceiver"
            android:exported="false" >
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
            </intent-filter>
        </receiver>""",
        )
        assertEquals(
            listOf("receiver com.google.android.gms.cast.framework.ReconnectionReceiver has an intent filter: the system or another app could start it"),
            check(boot),
        )
        val activity = withEntry(
            """<activity android:name="androidx.mediarouter.app.RouteChooser" android:exported="false"><intent-filter><action android:name="android.intent.action.VIEW" /></intent-filter></activity>""",
        )
        assertEquals(listOf("activity androidx.mediarouter.app.RouteChooser has an intent filter: the system or another app could start it"), check(activity))
        val media3 = withEntry("""<service android:name="androidx.media3.cast.CastSessionService" android:exported="true" />""")
        assertEquals(listOf("service androidx.media3.cast.CastSessionService is exported: another app could start it"), check(media3))
    }

    @Test
    fun `Google's expanded controller declared at all fails`() {
        val manifest = withEntry(
            """<activity android:name="com.google.android.gms.cast.framework.media.widget.ExpandedControllerActivity" android:exported="false" />""",
        )
        assertEquals(
            listOf("activity com.google.android.gms.cast.framework.media.widget.ExpandedControllerActivity: Google's expanded controller, which starts Google's Cast software as it opens"),
            check(manifest),
        )
    }

    @Test
    fun `a manifest without the app's cast service or its type is not the app's merged manifest`() {
        val noService = merged("android:name=\"io.skjaere.usenet.app.cast.CastService\"", "android:name=\"io.skjaere.usenet.app.cast.OtherService\"")
        assertEquals(
            listOf("not the app's merged manifest: no .cast.CastService with foregroundServiceType mediaPlayback"),
            check(noService),
        )
        val noType = merged("android:foregroundServiceType=\"mediaPlayback\"", "android:foregroundServiceType=\"dataSync\"")
        assertEquals(listOf("not the app's merged manifest: no .cast.CastService with foregroundServiceType mediaPlayback"), check(noType))
        val noPermission = merged("<uses-permission android:name=\"android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK\" />", "")
        assertEquals(listOf("not the app's merged manifest: no uses-permission android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"), check(noPermission))
        // The app's own manifest names its service from the package; a type among others still counts.
        val relative = merged("android:name=\"io.skjaere.usenet.app.cast.CastService\"", "android:name=\".cast.CastService\"")
            .replace("android:foregroundServiceType=\"mediaPlayback\"", "android:foregroundServiceType=\"connectedDevice|mediaPlayback\"")
        assertEquals(emptyList(), check(relative))
    }

    @Test
    fun `the tool reads each manifest it is given and writes one report`(@TempDir dir: File) {
        val good = File(dir, "release/AndroidManifest.xml").apply { parentFile.mkdirs(); writeText(merged) }
        val bad = File(dir, "debug/AndroidManifest.xml").apply {
            parentFile.mkdirs()
            writeText(withEntry("""<receiver android:name="androidx.mediarouter.media.MediaTransferReceiver" android:exported="false" />"""))
        }
        val report = File(dir, "out/report.txt")
        assertEquals(0, run(arrayOf("--check", "cast-starts-late", "--manifest", good.path, "--report", report.path)))
        assertTrue(report.readText().startsWith("${good.path}: Google's Cast software starts only where the app starts it"), report.readText())

        assertEquals(1, run(arrayOf("--check", "cast-starts-late", "--manifest", good.path, "--manifest", bad.path, "--report", report.path)))
        val text = report.readText()
        assertTrue(text.startsWith("${good.path}: Google's Cast software starts only where the app starts it"), text)
        assertTrue("${bad.path}: Google's Cast software could start, or a component of it, without the app's gate:\n  receiver androidx.mediarouter.media.MediaTransferReceiver: " in text, text)

        assertEquals(2, run(arrayOf("--check", "cast-starts-late", "--report", report.path)), "no manifest")
        assertEquals(2, run(arrayOf("--check", "cast-starts-late", "--manifest", File(dir, "none.xml").path)), "a manifest that isn't there")
        assertEquals(2, run(arrayOf("--check", "chromecast", "--manifest", good.path)), "a check it doesn't know")
    }
}
