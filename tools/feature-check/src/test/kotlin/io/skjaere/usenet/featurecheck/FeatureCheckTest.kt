package io.skjaere.usenet.featurecheck

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * checkNoDiscover over synthetic inputs: dex files as their strings, an APK and a bundle as zips, a merged manifest, and
 * dexdump's listing of the app's classes. The release build passes clean and fails on any marker anywhere; the debug
 * build (the control) must have every marker; the report names where, never what is there.
 */
class FeatureCheckTest {
    @TempDir
    lateinit var dir: File

    /** What the debug build holds besides Discover: a dev credential, which no report may show. */
    private val secret = "devProvider.password=hunter2-SECRET"

    private val releaseDir get() = File(dir, "release-dex").apply { mkdirs() }
    private val debugDir get() = File(dir, "debug-dex").apply { mkdirs() }

    /** A dex file as the check reads it: its strings. */
    private fun dex(parent: File, name: String, vararg strings: String): File =
        File(parent, name).apply { writeBytes(bytes("dex\n035", *strings)) }

    private fun bytes(vararg strings: String) = strings.joinToString("\u0000").toByteArray(Charsets.ISO_8859_1)

    private fun zip(name: String, entries: Map<String, ByteArray>): File = File(dir, name).also { file ->
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (entry, content) ->
                zip.putNextEntry(ZipEntry(entry))
                zip.write(content)
                zip.closeEntry()
            }
        }
    }

    private val appClass = "Lio/skjaere/usenet/app/MainActivity;"
    private val cleanApk = mapOf(
        "AndroidManifest.xml" to bytes("manifest"),
        "classes.dex" to bytes("dex\n035", appClass, "movie/12"),
        "res/drawable/ic_home.xml" to bytes("<vector/>"),
        "resources.arsc" to bytes("app_name", "NZStreamer"),
        "META-INF/services/discovery.version" to bytes("1.0"),
    )
    private val cleanBundle = mapOf(
        "BundleConfig.pb" to bytes("config"),
        "base/dex/classes.dex" to bytes("dex\n035", appClass),
        "base/res/drawable/ic_home.xml" to bytes("<vector/>"),
        "base/resources.pb" to bytes("app_name"),
        "base/manifest/AndroidManifest.xml" to bytes("manifest"),
    )
    private val cleanManifest = """<manifest package="io.skjaere.usenet"><application><activity android:name=".MainActivity"/></application></manifest>"""

    /** dexdump's listing: an app class's string, and a library's that says "Discovered" (kotlin-compression-utils logs so). */
    private val cleanListing = """
        |Class #0            -
        |  Class descriptor  : 'Lio/skjaere/usenet/app/MainActivity;'
        |0001cc: 1a00 0100                              |0000: const-string v0, "Home" // string@0001
        |Class #1            -
        |  Class descriptor  : 'Lio/skjaere/compressionutils/validation/ArchiveValidator;'
        |00017c: 1a00 0200                              |0000: const-string v0, "Discovered " // string@0002
    """.trimMargin()

    private var listing = cleanListing
    private var apk = cleanApk
    private var bundle = cleanBundle
    private var manifest = cleanManifest

    /** The check over the release build ([releaseDir], [apk], [bundle], [manifest]) and the debug dex ([debugDir]): its exit and report. */
    private fun check(): Pair<Int, String> {
        val report = File(dir, "report.txt").apply { delete() }
        val manifestFile = File(dir, "AndroidManifest.xml").apply { writeText(manifest) }
        val args = arrayOf(
            "--feature", "discover",
            "--release-dex", releaseDir.path, "--release-apk", zip("app-release.apk", apk).path, "--release-aab", zip("app-release.aab", bundle).path,
            "--release-manifest", manifestFile.path, "--control-dex", debugDir.path, "--dexdump", "dexdump", "--report", report.path,
        )
        val code = run(args) { _, _ -> ConstStringScan.scan(listing.lineSequence()) }
        return code to (if (report.isFile) report.readText() else "")
    }

    private fun aCleanRelease() {
        dex(releaseDir, "classes.dex", appClass, "Lio/skjaere/usenet/metadata/TmdbClient;", "movie/12", "search/multi")
    }

    private fun aDebugWithEveryMarker() {
        dex(debugDir, "classes.dex", *(Discover.required.map { "L${it.text}Something;" } + secret).toTypedArray())
    }

    @Test
    fun `a clean release build and a debug build with every marker pass`() {
        aCleanRelease()
        aDebugWithEveryMarker()
        val (code, report) = check()
        assertEquals(0, code, report)
        assertTrue(report.startsWith("checkNoDiscover: the release build carries nothing of Discover.\n"), report)
        assertTrue("Read 1 const-strings in the app's own classes: none with \"Discover\"." in report, report)
        assertTrue("The debug build has every one of the ${Discover.required.size} markers" in report, report)
        assertFalse(secret in report, report)
    }

    @Test
    fun `each marker in the release dex fails it, naming the file and the marker, never what is around it`() {
        aCleanRelease()
        aDebugWithEveryMarker()
        Discover.forbidden.forEach { marker ->
            val file = dex(releaseDir, "classes2.dex", "before ${marker.text} after", secret)
            val (code, report) = check()
            assertEquals(1, code, "$marker: $report")
            assertTrue("  classes2.dex: ${marker.what} (\"${marker.text}\")\n" in report, report)
            assertFalse(secret in report || "before" in report, report)
            file.delete()
        }
    }

    @Test
    fun `the APK's and the bundle's entries are looked in, their dex and the rest`() {
        aCleanRelease()
        aDebugWithEveryMarker()
        apk = cleanApk + ("classes2.dex" to bytes("dex\n035", "movie/now_playing", secret))
        bundle = cleanBundle + ("base/dex/classes2.dex" to bytes("dex\n035", "Lio/skjaere/usenet/discover/DiscoverSource;")) +
            ("base/root/META-INF/notes.txt" to bytes("Streaming services from JustWatch"))
        val (code, report) = check()
        assertEquals(1, code, report)
        assertEquals(
            listOf(
                "  app-release.apk!classes2.dex: a TMDB list path (\"movie/now_playing\")",
                "  app-release.aab!base/dex/classes2.dex: a Discover class (\"io/skjaere/usenet/discover/\")",
                "  app-release.aab!base/root/META-INF/notes.txt: JustWatch's name (\"JustWatch\")",
            ),
            report.lines().filter { it.startsWith("  app-release") },
        )
        assertFalse(secret in report, report)
    }

    @Test
    fun `discover in a resource, an asset, a resource table or the manifest fails the release build`() {
        aCleanRelease()
        aDebugWithEveryMarker()
        apk = cleanApk + mapOf(
            "res/drawable/ic_discover.xml" to bytes("<vector/>"),
            "assets/DiscoverHelp.json" to bytes("{}"),
            "resources.arsc" to bytes("app_name", "discover_title"),
        )
        bundle = cleanBundle + mapOf("base/res/layout/discover_row.xml" to bytes("<x/>"), "base/resources.pb" to bytes("Discover"))
        manifest = cleanManifest.replace(".MainActivity", ".DiscoverActivity")
        val (code, report) = check()
        assertEquals(1, code, report)
        assertEquals(
            setOf(
                "  app-release.apk!res/drawable/ic_discover.xml: \"discover\" in a resource or an asset",
                "  app-release.apk!assets/DiscoverHelp.json: \"discover\" in a resource or an asset",
                "  app-release.apk!resources.arsc: \"discover\" in a resource or an asset",
                "  app-release.aab!base/res/layout/discover_row.xml: \"discover\" in a resource or an asset",
                "  app-release.aab!base/resources.pb: \"discover\" in a resource or an asset",
                "  AndroidManifest.xml: \"discover\" in the manifest",
            ),
            report.lines().filter { "\"discover\" in" in it }.toSet(),
        )
        // A library's file named so, outside the resources, is not one: only the markers in it count.
        assertFalse("discovery.version" in report, report)
    }

    /**
     * L106: Google's data transport, which cast-framework brings to every build, declares a service whose class has the word
     * (TransportBackendDiscovery, in transport-runtime's manifest). A library's class or key named by an android:name (one
     * starting with neither io.skjaere. nor .) is no Discover of ours: the word check skips it.
     */
    @Test
    fun `a library's class with the word in the release manifest passes`() {
        aCleanRelease()
        aDebugWithEveryMarker()
        manifest = cleanManifest.replace("</application>", "$TRANSPORT</application>")
        val (code, report) = check()
        assertEquals(0, code, report)
        assertTrue(report.startsWith("checkNoDiscover: the release build carries nothing of Discover.\n"), report)
    }

    /**
     * L106: beside a library's name the word check skipped, the app's own names (as the merge writes them, in full, or as
     * the app's manifest writes them, from its package), a label, any other attribute and the text are still read.
     */
    @Test
    fun `the word in the app's own name, a label, another attribute or the text still fails the release manifest`() {
        aCleanRelease()
        aDebugWithEveryMarker()
        listOf(
            "<activity android:name=\".DiscoverActivity\"/>",
            "<activity android:name=\"io.skjaere.usenet.app.discover.DiscoverActivity\"/>",
            "<activity android:name=\"com.example.Lists\" android:label=\"Discover\"/>",
            "<meta-data android:name=\"com.example.lists\" android:value=\"discover\"/>",
            "<provider android:name=\"com.example.Lists\" android:authorities=\"io.skjaere.usenet.discover\"/>",
            "<!-- What to discover next -->",
        ).forEach { entry ->
            manifest = cleanManifest.replace("</application>", "$TRANSPORT$entry</application>")
            val (code, report) = check()
            assertEquals(1, code, "$entry: $report")
            assertEquals(listOf("  AndroidManifest.xml: \"discover\" in the manifest"), report.lines().filter { it.startsWith("  AndroidManifest.xml") }, "$entry: $report")
        }
    }

    /** L106: Discover's markers are still looked for over the whole manifest, a library's names included. */
    @Test
    fun `a Discover marker in a library's name still fails the release manifest`() {
        aCleanRelease()
        aDebugWithEveryMarker()
        manifest = cleanManifest.replace("</application>", "$TRANSPORT<meta-data android:name=\"com.example.JustWatchKey\" android:value=\"x\"/></application>")
        val (code, report) = check()
        assertEquals(1, code, report)
        assertEquals(listOf("  AndroidManifest.xml: JustWatch's name (\"JustWatch\")"), report.lines().filter { it.startsWith("  AndroidManifest.xml") }, report)
    }

    @Test
    fun `a const-string with Discover in the app's own classes fails the release build, the libraries' don't count`() {
        aCleanRelease()
        aDebugWithEveryMarker()
        listing = cleanListing + "\n" + """
            |Class #2            -
            |  Class descriptor  : 'Lio/skjaere/usenet/app/home/HomeScreen;'
            |0002a0: 1a01 0300                              |0000: const-string v1, "Discover more \"titles\"" // string@0003
            |0002a4: 1a02 0400                              |0002: const-string v2, "$secret" // string@0004
        """.trimMargin()
        val (code, report) = check()
        assertEquals(1, code, report)
        assertTrue("  the release dex: 1 const-string(s) with \"Discover\" in the app's own classes\n" in report, report)
        assertFalse("Discover more" in report || secret in report || "HomeScreen" in report, report)
    }

    @Test
    fun `a debug build without every marker fails the check, saying which`() {
        aCleanRelease()
        dex(debugDir, "classes.dex", *(Discover.required.map { it.text } - setOf("tv/popular", "nzs.discover.canary.app") + secret).toTypedArray())
        val (code, report) = check()
        assertEquals(1, code, report)
        assertTrue(report.startsWith("checkNoDiscover: the release build carries nothing of Discover.\n"), report)
        assertEquals(
            listOf("  a Discover canary (\"nzs.discover.canary.app\")", "  a TMDB list path (\"tv/popular\")"),
            report.substringAfter("so it can't be trusted:\n").lines().takeWhile { it.startsWith("  ") },
        )
        assertFalse(secret in report, report)
    }

    @Test
    fun `the check never passes blind`() {
        aDebugWithEveryMarker()
        assertEquals(2, check().first, "no release dex")
        aCleanRelease()
        File(debugDir, "classes.dex").delete()
        assertEquals(2, check().first, "no debug dex")
        aDebugWithEveryMarker()
        // dexdump listing none of the app's strings: the scan can't see them.
        listing = cleanListing.lines().drop(3).joinToString("\n")
        assertEquals(2, check().first, "no app const-strings")
        listing = cleanListing
        assertEquals(0, check().first)
        // Google Cast is in every build since C2 (L106): no longer a feature it looks for, nor is a run without one.
        assertEquals(2, run(arrayOf("--feature", "cast", "--release-dex", releaseDir.path, "--control-dex", debugDir.path)), "cast")
        assertEquals(2, run(arrayOf("--release-dex", releaseDir.path, "--control-dex", debugDir.path)), "no feature named")
    }

    @Test
    fun `dexdump's listing is read for the app's own const-strings`() {
        val lines = """
            |  Class descriptor  : 'Lio/skjaere/usenet/probe/Probe;'
            |00017c: 1a00 0100                              |0000: const-string v0, "Discover \"quoted\" here" // string@0001
            |00019e: 1a01 0f00                              |0005: const-string v1, "plain " // string@000f
            |0001a2: 1b02 0010 0000                         |0007: const-string/jumbo p2, "More to Discover" // string@00010000
            |0001aa: 6e20 0600 1000                         |000b: invoke-virtual {v0, v1}, Ljava/lang/StringBuilder;.append:(Ljava/lang/String;)Ljava/lang/StringBuilder; // method@0006
            |  Class descriptor  : 'Lio/skjaere/nzbstreamer/Other;'
            |00017c: 1a00 0100                              |0000: const-string v0, "Discover" // string@0005
        """.trimMargin()
        assertEquals(ConstStrings(read = 3, named = 2), ConstStringScan.scan(lines.lineSequence()))
    }

    private companion object {
        /** What transport-runtime 3.1.3 and transport-backend-cct 3.1.3 declare in a merged manifest, as the release's has it. */
        const val TRANSPORT = "<service android:name=\"com.google.android.datatransport.runtime.backends.TransportBackendDiscovery\" " +
            "android:exported=\"false\"><meta-data android:name=\"backend:com.google.android.datatransport.cct.CctBackendFactory\" " +
            "android:value=\"cct\"/></service>"
    }
}
