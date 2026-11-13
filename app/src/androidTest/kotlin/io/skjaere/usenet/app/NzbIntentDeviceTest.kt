package io.skjaere.usenet.app

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.ProviderRecord
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket

/**
 * .nzb files other apps open with this one (ACTION_VIEW) or share to it (ACTION_SEND), as a file
 * manager does: by a content URI with a read grant. The file is the chapters.nzb fixture (see
 * SkipSegmentsDeviceTest) saved to Downloads through MediaStore under an invented name, which the
 * tests delete again. Each ends on Import with the file's list, or its error, over Home. The app
 * is started as the launcher starts it, in its own task, which the file is brought into.
 */
@RunWith(AndroidJUnit4::class)
class NzbIntentDeviceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val home = HomeContentShown()
    private val downloads = mutableListOf<Uri>()
    private lateinit var nzb: ByteArray

    @Before
    fun setUp() {
        assumeTrue("MediaStore Downloads needs Android 10", Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        fun reachable(port: Int) = runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", port), 2_000) } }.isSuccess
        assumeTrue("mock NNTP server not reachable at 10.0.2.2:1119", reachable(1119))
        nzb = instrumentation.context.assets.open("chapters.nzb").use { it.readBytes() }
        home.show()
        runBlocking {
            engineSetup("the mock on the engine") { mockOnEngine() }
        }
        context.startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClass(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        compose.waitUntil(10_000) { exists(hasText("Search your indexers")) }
    }

    @After
    fun cleanUp() {
        // Out of the app, as the user would leave it, so the next test starts it again.
        instrumentation.runOnMainSync {
            val monitor = ActivityLifecycleMonitorRegistry.getInstance()
            listOf(Stage.RESUMED, Stage.PAUSED, Stage.STOPPED, Stage.STARTED, Stage.CREATED).flatMap(monitor::getActivitiesInStage).forEach { it.finish() }
        }
        instrumentation.waitForIdleSync()
        downloads.forEach { context.contentResolver.delete(it, null, null) }
        home.restore()
    }

    /** The app's screen in front. */
    private fun main(): MainActivity {
        var found: MainActivity? = null
        instrumentation.runOnMainSync { found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull() }
        return found ?: throw AssertionError("the app isn't in front")
    }

    /**
     * Whether a node matches; not yet while no compose hierarchy is up, as while Import's activity replaces the one
     * before (#238), which the rule reports by an IllegalStateException of its own words. Any other failure stays one.
     */
    private fun exists(matcher: SemanticsMatcher): Boolean = try {
        compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()
    } catch (e: IllegalStateException) {
        if (e.message?.startsWith(NO_HIERARCHY) != true) throw e
        false
    }

    /** Saves [bytes] to Downloads as [name]; returns its content URI and the name MediaStore gave it. */
    private fun download(name: String, bytes: ByteArray, type: String): Pair<Uri, String> {
        val resolver = context.contentResolver
        val uri = resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, type)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            },
        )!!
        downloads += uri
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        val named = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)!!.use { it.moveToFirst(); it.getString(0) }
        return uri to named
    }

    /** Another app's intent for this one, with a read grant for [uri], as a file manager sends it. */
    private fun send(intent: Intent) {
        context.startActivity(intent.setPackage(context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    private fun awaitFileList(title: String) {
        compose.waitUntil(30_000) { exists(hasText("chapters.mkv")) }
        assertEquals(1, compose.onAllNodes(hasText(title)).fetchSemanticsNodes().size)
    }

    /** Back, as the system's gesture or key gives it to the app. */
    /** Plays the file listed, as a tap on it does, then closes the player: Import shows again. */
    private fun playTheFile() {
        compose.onNode(hasText("chapters.mkv") and hasClickAction()).performClick()
        val player = runBlocking {
            var found: PlayerActivity? = null
            withTimeoutOrNull(30_000) {
                while (found == null) {
                    instrumentation.runOnMainSync {
                        found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PlayerActivity>()
                            .firstOrNull { it.player?.playbackState == Player.STATE_READY }
                    }
                    if (found == null) delay(200)
                }
            }
            found
        }
        assertEquals("the file didn't play", true, player != null)
        instrumentation.runOnMainSync { player!!.finish() }
        compose.waitUntil(10_000) { exists(hasText("chapters.mkv")) && runCatching { main() }.isSuccess }
    }

    private fun back() {
        val activity = main()
        instrumentation.runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    /**
     * Between the app's screen going and the next one coming (Import's activity replacing the one before, say) no
     * compose hierarchy is up, for longer than the test rule waits for one (two seconds) on a loaded device: the
     * helpers wait it out as "not yet" (#238). Here the gap is made that long.
     */
    @Test
    fun theHelpersWaitWhileNoScreenIsUp() {
        val open = main()
        instrumentation.runOnMainSync { open.finish() }
        runBlocking { withTimeoutOrNull(5_000) { while (!open.isDestroyed) delay(100) } }
        val starter = Thread {
            Thread.sleep(3_000)
            context.startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClass(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.apply { start() }
        compose.waitUntil(15_000) { exists(hasText("Search your indexers")) }
        starter.join()
    }

    @Test
    fun aViewedNzbOpensInImportAndPlays() {
        val (uri, name) = download("Lantern Field.nzb", nzb, NzbIntents.NZB_TYPE)
        send(Intent(Intent.ACTION_VIEW).setDataAndType(uri, NzbIntents.NZB_TYPE))
        awaitFileList(name.removeSuffix(".nzb"))
        playTheFile()
        // Back from Import goes to Home, not out of the app.
        back()
        compose.waitUntil(10_000) { exists(hasText("Search your indexers")) }
    }

    /**
     * With the app's screen not open (closed, or never started since the process began), the file starts it on Import
     * over Home; its splash goes once the settings are read, though Home leaves the screen for Import at once.
     */
    @Test
    fun aViewedNzbStartsTheAppOnImportWhenItIsNotOpen() {
        val (uri, name) = download("Lantern Field.nzb", nzb, NzbIntents.NZB_TYPE)
        val open = main()
        instrumentation.runOnMainSync { open.finish() }
        runBlocking { withTimeoutOrNull(5_000) { while (!open.isDestroyed) delay(100) } }

        send(Intent(Intent.ACTION_VIEW).setDataAndType(uri, NzbIntents.NZB_TYPE))
        awaitFileList(name.removeSuffix(".nzb"))
        assertNotSame("the app's screen wasn't started again", open, main())
        assertFalse("the splash waited out its timeout over Import", main().splashGate.timedOut)
        back()
        compose.waitUntil(10_000) { exists(hasText("Search your indexers")) }
    }

    /**
     * Android knows no type for .nzb, so a .nzb downloaded or copied to the device is typed
     * application/octet-stream, and the Files app's Downloads and Recent views open it by a URI
     * that doesn't name it (content://media/external/downloads/<id> here): it is taken all the
     * same, and its name read from the provider.
     */
    @Test
    fun aViewedNzbTypedOctetStreamByAUriWithoutItsNameOpensInImport() {
        val (uri, name) = download("Lantern Field.nzb", nzb, "application/octet-stream")
        assertFalse(uri.toString(), uri.toString().contains(".nzb"))
        send(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/octet-stream"))
        awaitFileList(name.removeSuffix(".nzb"))
    }

    /** Any other file opened that way is turned down by its name. */
    @Test
    fun aViewedFileThatIsNotAnNzbSaysSo() {
        val (uri, name) = download("Lantern Field.mkv", ByteArray(4_096) { it.toByte() }, "application/octet-stream")
        send(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/octet-stream"))
        compose.waitUntil(10_000) { exists(hasText("“$name” isn't a .nzb file.")) }
    }

    @Test
    fun aSharedNzbOpensInImportOverHomeAndPlays() {
        val (uri, name) = download("Lantern Field.nzb", nzb, "application/octet-stream")
        // Somewhere else in the app first: Import still opens over Home.
        compose.onNode(hasText("Search your indexers") and hasClickAction()).performClick()
        compose.waitUntil(10_000) { !exists(hasText("Search your indexers")) }
        send(Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, uri))
        awaitFileList(name.removeSuffix(".nzb"))
        playTheFile()
        back()
        compose.waitUntil(10_000) { exists(hasText("Search your indexers")) }
    }

    @Test
    fun aSharedFileThatIsNotAnNzbSaysSo() {
        val (uri, name) = download("Lantern Field.mkv", ByteArray(4_096) { it.toByte() }, "application/octet-stream")
        send(Intent(Intent.ACTION_SEND).setType("application/octet-stream").putExtra(Intent.EXTRA_STREAM, uri))
        compose.waitUntil(10_000) { exists(hasText("“$name” isn't a .nzb file.")) }
    }

    /**
     * Opened before any provider is set up: Import says so and offers the providers list, and opens
     * the file once there is one. The saved providers are taken away and put back as they were,
     * passwords included.
     */
    @Test
    fun withoutAProviderImportOffersToAddOneAndOpensTheFileOnceThereIsOne() {
        val settings = container.settings
        val saved: AppSettings = runBlocking { settings.settings.first() }
        val passwords = runBlocking { saved.activeProviders.mapNotNull { p -> settings.provider(p.id)?.password?.let { p.passwordSecret to it } }.toMap() }
        try {
            runBlocking { saved.activeProviders.forEach { settings.deleteProvider(it.id) } }
            val (uri, name) = download("Lantern Field.nzb", nzb, NzbIntents.NZB_TYPE)
            send(Intent(Intent.ACTION_VIEW).setDataAndType(uri, NzbIntents.NZB_TYPE))
            val title = name.removeSuffix(".nzb")
            compose.waitUntil(10_000) { exists(hasText("No Usenet provider is set up yet. $title opens as soon as one is added.")) }
            compose.onNode(hasText("Open Usenet providers") and hasClickAction()).performClick()
            compose.waitUntil(10_000) { exists(hasText("Add a provider")) }

            // As the provider screen saves it.
            runBlocking { settings.saveProvider(ProviderRecord("", "Mock (import test)", "10.0.2.2", 1119, useTls = false, connections = 4), "") }
            back()
            awaitFileList(title)
        } finally {
            runBlocking {
                settings.providers.first().filter { p -> saved.providers.none { it.id == p.id } }.forEach { settings.deleteProvider(it.id) }
                settings.restore(saved)
                passwords.forEach { (secret, value) -> container.data.secrets.put(secret, value) }
                assertEquals(saved, settings.settings.first())
                // The app's own provider sync puts the engine back on the restored providers; let it before the next test.
                delay(1_000)
            }
        }
    }

    private companion object {
        /** How the compose test rule starts saying it found no compose hierarchy up (ui-test's TestOwner). */
        const val NO_HIERARCHY = "No compose hierarchies found in the app"
    }
}
