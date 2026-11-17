package io.skjaere.usenet.app.download

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.download.DownloadHostRules.ScheduleCause
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.assertWhole
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.episode
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.exists
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.film
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.show
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.waitFor
import io.skjaere.usenet.app.download.DownloadsPageSeeds.Companion.waitTill
import io.skjaere.usenet.app.settings.DownloadSettings
import io.skjaere.usenet.app.settings.DownloadSettingsDeps
import io.skjaere.usenet.app.settings.DownloadSettingsScreen
import io.skjaere.usenet.app.settings.SettingsScreen
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.DownloadPrefs
import io.skjaere.usenet.data.DownloadState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * Settings › Downloads on a phone (item 20; W110, L365, L371), over the app's settings, runner, storage and host, with seams
 * for what isn't the page's (the space free, and where a case says so Android's answers):
 * - the Settings hub's Downloads row comes right after Cache, says what downloads hold and when they run, and opens it;
 * - each switch is saved, and the host is asked for once with its new needs, whether it waits or runs (L335's exception),
 *   as the debug build's trace records the app's schedules (L391);
 * - Download size's cells are a true grid, two full rows of four, at twice the font size too, and the one picked is saved;
 * - After watching's Delete once watched is off on a new install, and saved with when it was switched on, asking for no
 *   host (it changes no download's needs);
 * - Save downloads to is this phone, the SD card greyed "Later" only while one is in;
 * - Delete all asks first, as Cache's clears do, then deletes every download, file and all, and the page reads empty;
 * - Notifications says whether Android shows them, and opens Android's page for the app's notifications;
 * - at twice the font size, every line whole.
 *
 * The downloads' settings start as a new install's and are put back after; every row seeded is deleted with its folders,
 * and the downloads' jobs are cancelled before and after. Titles invented.
 */
@RunWith(AndroidJUnit4::class)
class DownloadSettingsDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val container = context.container
    private val settings = container.settings
    private val seeds = DownloadsPageSeeds(context, container)
    private lateinit var saved: AppSettings

    /** Whether the settings were taken, to be put back after; and the stand-in for Android's settings put in place. */
    private var taken = false
    private var monitored = false


    /** Stands in for Android's settings: each page asked for kept, none started. */
    private val androidSettings = object : Instrumentation.ActivityMonitor() {
        val asked = CopyOnWriteArrayList<Intent>()

        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
            if (intent.action == Settings.ACTION_APP_NOTIFICATION_SETTINGS || intent.action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS) {
                asked += intent
                Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            } else {
                null
            }
    }

    @Before
    fun setUp() = runBlocking<Unit> {
        assumeFalse("downloads show on phones and tablets alone (L369)", container.television)
        // The debug build's seeding first, so what is put back is what it saved.
        withTimeout(SEEDING_MILLIS) { container.devSettingsSaved.await() }
        saved = settings.settings.first()
        taken = true
        settings.restore(saved.copy(downloads = DownloadPrefs()))
        seeds.before()
        instrumentation.addMonitor(androidSettings)
        monitored = true
    }

    @After
    fun tearDown() = runBlocking<Unit> {
        if (monitored) instrumentation.removeMonitor(androidSettings)
        // A case its setUp stopped early (on a TV, where it is skipped, or the seeding's wait) took nothing to put back.
        if (taken) {
            seeds.after()
            settings.restore(saved)
            assertEquals(saved, settings.settings.first())
        }
    }

    /**
     * The page's deps: the app's, with the test's own rows alone, a fixed [space], and [overrides]. The host's schedules are
     * the app's own, which the debug build's trace records (L391): with no download of the test's to run, each is skipped.
     */
    private fun deps(
        space: DownloadSpace? = DownloadSpace(available = 51 * GB, used = 0, queued = 0),
        overrides: (DownloadSettingsDeps) -> DownloadSettingsDeps = { it },
    ): DownloadSettingsDeps {
        val app = DownloadSettingsDeps.of(container, context)
        return overrides(app.copy(rows = app.rows.map { rows -> rows.filter { seeds.isMine(it.id) } }, space = { space }))
    }

    /** The debug build's trace (L391), its lines in turn. */
    private fun traced(): List<String> = File(context.filesDir, "${DownloadStorage.DIR}/${DownloadDebug.TRACE}").takeIf { it.isFile }?.readLines().orEmpty()

    /** The app's schedules of the host for a changed switch in the trace's lines after the first [from]. */
    private fun switchSchedules(from: Int): Int = traced().drop(from).count { "schedule cause=${ScheduleCause.SwitchChanged} " in it }

    /**
     * Waits for the host to be asked for once for the switch just changed ([what]), the trace's lines counted from [from]:
     * then a moment more, for a second ask, whoever made it, to show.
     */
    private fun askedOnce(what: String, from: Int) {
        compose.waitTill("the host asked for $what") { switchSchedules(from) >= 1 }
        SystemClock.sleep(ANOTHER_ASK_MILLIS)
        assertEquals("the host asked for $what once", 1, switchSchedules(from))
    }

    private fun page(deps: DownloadSettingsDeps, fontScale: Float = 1f) {
        compose.show(context, fontScale) { DownloadSettingsScreen(onBack = {}, deps = deps) }
        compose.waitFor(hasText(DownloadSettings.UNMETERED))
    }

    @Test
    fun theHubsDownloadsRowFollowsCacheSaysWhatDownloadsHoldAndOpensThePage() {
        val made = listOf(seeds.seed(episode(2, 4, DownloadState.Done, size = 1_700_000_000)), seeds.seed(film()))
        compose.waitTill("the runner has the rows") { container.downloads.state.value.map { it.row.id }.containsAll(made.map { it.id }) }
        var opened = 0
        compose.show(context) {
            SettingsScreen(onBack = {}, onProviders = {}, onIndexers = {}, onMetadata = {}, onPlayback = {}, onCache = {}, onLogs = {}, onDownloads = { opened++ })
        }
        val line = runBlocking { DownloadSettings.hubLine(container.downloads.state.value.map { it.row }, settings.downloads.first()) }
        assertTrue("the seeded rows counted: $line", line.endsWith(" · Wi-Fi or Ethernet only") && line != "None yet · Wi-Fi or Ethernet only")
        compose.waitFor(hasText(line))
        val row = hasText(DownloadSettings.TITLE) and hasClickAction()
        compose.waitFor(row)
        // Right after Cache, before This device, in Playback (DlPhoneSettings 1).
        val cache = top(hasText("Cache") and hasClickAction())
        val downloads = top(row)
        val device = top(hasText("This device") and hasClickAction())
        assertTrue("Cache $cache, Downloads $downloads, This device $device", cache < downloads && downloads < device)
        compose.onNode(row).performScrollTo().performClick()
        compose.waitTill("the row opened Settings › Downloads") { opened == 1 }
    }

    @Test
    fun eachSwitchIsSavedAndAsksForTheHostUnderItsNewNeeds() = runBlocking<Unit> {
        page(deps())
        val wifi = hasText(DownloadSettings.UNMETERED) and switch
        val charging = hasText(DownloadSettings.CHARGING) and switch
        assertTrue(exists(hasText(DownloadSettings.UNMETERED_DETAIL)))
        assertTrue(exists(hasText(DownloadSettings.CHARGING_DETAIL)))
        assertTrue(exists(hasText(DownloadSettings.ONE_AT_A_TIME)))
        // A new install's: Wi-Fi or Ethernet only (W110), not only while charging (L365).
        compose.onNode(wifi).assertIsOn()
        compose.onNode(charging).assertIsOff()

        // Each change saved, and the host asked for once under its new needs (L335's exception): two asks would stop a
        // running download twice.
        var from = traced().size
        compose.onNode(wifi).performClick()
        saved("any network") { !it.unmeteredOnly }
        askedOnce("under any network", from)
        compose.onNode(wifi).assertIsOff()

        from = traced().size
        compose.onNode(charging).performClick()
        saved("only while charging") { it.whileCharging }
        askedOnce("while charging", from)
        compose.onNode(charging).assertIsOn()

        from = traced().size
        compose.onNode(wifi).performClick()
        saved("Wi-Fi or Ethernet only") { it.unmeteredOnly }
        askedOnce("on Wi-Fi or Ethernet again", from)
        assertNull("Download size untouched", settings.downloads.first().sizeCapGbPerHour)
    }

    /**
     * W111: After watching comes after Download size's explanation, a heading, with Delete once watched off on a new
     * install and its line. Switched on, it is saved with when it was, and off again with none; the host is never asked
     * for, as it changes no download's needs.
     */
    @Test
    fun afterWatchingsSwitchIsSavedWithWhenItWasSwitchedOnAndAsksForNoHost() = runBlocking<Unit> {
        page(deps())
        val from = traced().size
        val switched = hasText(DownloadSettings.DELETE_ONCE_WATCHED) and switch
        compose.onNode(switched).performScrollTo()
        assertTrue("a heading", exists(hasText(DownloadSettings.AFTER_WATCHING, ignoreCase = true) and isHeading()))
        assertTrue(exists(hasText(DownloadSettings.DELETE_ONCE_WATCHED_DETAIL)))
        // After Download size's explanation, before Save downloads to.
        assertTrue(top(hasText(DownloadSettings.SIZE_EXPLANATION[1])) < top(hasText(DownloadSettings.AFTER_WATCHING, ignoreCase = true)))
        assertTrue(top(switched) < top(hasText(DownloadSettings.SAVE_TO, ignoreCase = true)))
        compose.onNode(switched).assertIsOff()

        val before = System.currentTimeMillis()
        compose.onNode(switched).performClick()
        saved("Delete once watched on") { it.deleteOnceWatched }
        val since = checkNotNull(settings.downloads.first().deleteOnceWatchedSince)
        assertTrue("switched on at $since, the test's click at $before", since >= before && since <= System.currentTimeMillis())
        compose.onNode(switched).assertIsOn()

        compose.onNode(switched).performClick()
        saved("Delete once watched off") { !it.deleteOnceWatched && it.deleteOnceWatchedSince == null }
        compose.onNode(switched).assertIsOff()
        SystemClock.sleep(ANOTHER_ASK_MILLIS)
        assertEquals("the host asked for", 0, switchSchedules(from))
        // The other switches as they were.
        assertEquals(DownloadPrefs(updatedAt = settings.downloads.first().updatedAt), settings.downloads.first())
    }

    @Test
    fun downloadSizeIsATrueGridOfTwoFullRowsAndSavesThePick() = runBlocking<Unit> {
        page(deps())
        val from = traced().size
        assertGrid()
        val notSet = hasContentDescription(DownloadSettings.label(null))
        val two = hasContentDescription(DownloadSettings.label(2))
        compose.onNode(notSet).assertIsSelected()
        compose.onNode(two).performScrollTo().performClick()
        saved("2 GB an hour") { it.sizeCapGbPerHour == 2 }
        compose.waitTill("2 GB an hour ticked") { selected(two) }
        compose.onNode(notSet).assertIsNotSelected()
        assertTrue(exists(hasText(DownloadSettings.SIZE_EXPLANATION[0])))
        assertTrue(exists(hasText("At 2 GB an hour, a 45-minute episode is about 1.5 GB.")))
        compose.onNode(hasContentDescription(DownloadSettings.label(12))).performScrollTo().performClick()
        saved("12 GB an hour") { it.sizeCapGbPerHour == 12 }
        compose.onNode(notSet).performScrollTo().performClick()
        saved("Not set") { it.sizeCapGbPerHour == null }
        assertEquals("a size schedules nothing", 0, switchSchedules(from))
    }

    @Test
    fun atTwiceTheFontSizeTheCellsGrowTallerStayAGridAndEveryLineIsWhole() {
        page(deps(overrides = { it.copy(sdCard = { true }, notificationsOn = { false }) }), fontScale = 2f)
        assertGrid()
        val height = compose.onNode(hasContentDescription(DownloadSettings.label(2))).fetchSemanticsNode().boundsInRoot.height
        val minimum = with(compose.density) { 56.dp.toPx() }
        assertTrue("the cells grow taller with their words: $height against $minimum", height > minimum)
        val width = context.resources.displayMetrics.widthPixels
        listOf(
            DownloadSettings.UNMETERED, DownloadSettings.UNMETERED_DETAIL, DownloadSettings.CHARGING_DETAIL, DownloadSettings.ONE_AT_A_TIME,
            DownloadSettings.SIZE_EXPLANATION[0], DownloadSettings.SIZE_EXPLANATION[1], DownloadSettings.DELETE_ONCE_WATCHED,
            DownloadSettings.DELETE_ONCE_WATCHED_DETAIL, DownloadSettings.STAYS, DownloadSettings.LATER,
            DownloadSettings.NO_DOWNLOADS, "50.0 GB free", DownloadSettings.notificationsLine(on = false),
        ).forEach { compose.assertWhole(it, width) }
    }

    @Test
    fun saveDownloadsToIsThisPhoneWithTheSdCardGreyedOnlyWhileOneIsIn() {
        page(deps(overrides = { it.copy(sdCard = { true }) }))
        val phone = hasText(DownloadSettings.THIS_PHONE)
        val card = hasText(DownloadSettings.SD_CARD)
        compose.onNode(phone).performScrollTo()
        // 51 GB Android can give, less the 1 GB kept free for the phone.
        assertTrue(exists(hasText("50.0 GB free")))
        compose.onNode(radio(DownloadSettings.THIS_PHONE)).assertIsSelected().assertIsEnabled()
        compose.onNode(radio(DownloadSettings.SD_CARD)).assertIsNotSelected().assertIsNotEnabled()
        assertTrue(exists(hasText(DownloadSettings.LATER)))
        assertTrue(exists(hasText(DownloadSettings.STAYS)))
        // Each section's header is a heading, for TalkBack's heading navigation.
        listOf(DownloadSettings.SIZE, DownloadSettings.SAVE_TO, DownloadSettings.ON_THIS_PHONE).forEach {
            assertTrue("'$it' a heading", exists(hasText(it, ignoreCase = true) and isHeading()))
        }
        // Side by side, This phone first.
        assertEquals(top(phone), top(card), 1f)
        assertTrue(left(phone) < left(card))
    }

    @Test
    fun withNoCardInSaveDownloadsToIsThisPhoneAlone() {
        // The emulator has no card: Android's own answer, read by the app's deps.
        val volumes = context.getSystemService(android.os.storage.StorageManager::class.java).storageVolumes
        assumeFalse("a removable volume is mounted here", volumes.any { !it.isPrimary && it.isRemovable && it.state == android.os.Environment.MEDIA_MOUNTED })
        page(deps())
        compose.onNode(hasText(DownloadSettings.THIS_PHONE)).performScrollTo()
        assertFalse(exists(hasText(DownloadSettings.SD_CARD)))
        assertFalse(exists(hasText(DownloadSettings.LATER)))
    }

    @Test
    fun deleteAllAsksFirstThenDeletesEveryDownloadAndThePageReadsEmpty() = runBlocking<Unit> {
        val made = listOf(
            seeds.seed(episode(2, 4, DownloadState.Done, size = 1_700_000_000), bytes = FILE_BYTES),
            seeds.seed(episode(2, 5, DownloadState.Done, size = 1_700_000_000), bytes = FILE_BYTES),
            seeds.seed(film(), bytes = FILE_BYTES),
            seeds.seed(episode(2, 6, DownloadState.Queued, size = 1_200_000_000, position = 1)),
        )
        // Delete all deletes every row the app has: none but this test's, so nothing of another's goes with them.
        val all = container.data.downloads.all().first().map { it.id }.toSet()
        assertEquals("rows of another test", made.map { it.id }.toSet(), all)
        val space = DownloadSpace(available = 51 * GB, used = 8_900_000_000, queued = 1_200_000_000)
        page(deps(space = space))
        compose.waitFor(hasText("8.9 GB in downloads"))
        assertTrue(exists(hasText("3 episodes and 1 film · 48.8 GB free")))
        val deleteAll = hasText(DownloadSettings.DELETE_ALL) and hasClickAction()
        compose.onNode(deleteAll).performScrollTo().assertIsEnabled()

        // Asked first; Cancel deletes nothing.
        compose.onNode(deleteAll).performClick()
        compose.waitFor(hasText(DownloadSettings.CONFIRM_TITLE))
        assertTrue(exists(hasText("Deletes the 8.9 GB of downloads on this phone: 3 episodes and 1 film. A download running now stops.")))
        compose.onNode(hasText("Cancel") and hasClickAction()).performClick()
        compose.waitTill("the question gone") { !exists(isDialog()) }
        assertEquals(4, seeds.mine().size)

        compose.onNode(deleteAll).performClick()
        compose.waitFor(hasText(DownloadSettings.CONFIRM_TITLE))
        compose.onNode(hasText(DownloadSettings.DELETE) and hasClickAction()).performClick()
        compose.waitTill("every download deleted") { seeds.mine().isEmpty() }
        made.forEach { row ->
            assertFalse("${row.id}'s video", container.downloadStorage.videoFolder(row.id)?.exists() ?: false)
            assertFalse("${row.id}'s bookkeeping", container.downloadStorage.bookkeeping(row.id).exists())
        }
        compose.waitFor(hasText(DownloadSettings.NO_DOWNLOADS))
        compose.onNode(hasText(DownloadSettings.DELETE_ALL)).assertIsNotEnabled()
        assertTrue("the queue empty", container.downloads.state.value.isEmpty())
    }

    @Test
    fun notificationsSaysWhetherAndroidShowsThemAndOpensItsPageForTheApp() {
        page(deps())
        // Android's own answer: on a new install the permission isn't granted, so off, unless a test before granted it.
        val on = NotificationManagerCompat.from(context).areNotificationsEnabled()
        val row = hasText(DownloadSettings.NOTIFICATIONS) and hasClickAction()
        compose.onNode(row).performScrollTo()
        assertTrue(exists(hasText(DownloadSettings.notificationsLine(on))))
        compose.onNode(row).performClick()
        compose.waitTill("Android's page asked for") { androidSettings.asked.isNotEmpty() }
        val asked = androidSettings.asked.single()
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, asked.action)
        assertEquals(context.packageName, asked.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    fun notificationsOffShowsItsWarningAndTheAllowLine() {
        page(deps(overrides = { it.copy(notificationsOn = { false }) }))
        compose.onNode(hasText(DownloadSettings.NOTIFICATIONS)).performScrollTo()
        assertTrue(exists(hasText("Off: allow them in Android’s settings")))
    }

    /** Download size's cells, each by how TalkBack reads it: two full rows of four, each row sharing a top, cells alike in size. */
    private fun assertGrid() {
        val rows = listOf(listOf(null, 1, 2, 3), listOf(4, 6, 8, 12))
        val nodes = rows.map { row -> row.map { compose.onNode(hasContentDescription(DownloadSettings.label(it))).fetchSemanticsNode().boundsInRoot } }
        nodes.forEachIndexed { i, row ->
            assertEquals("row ${i + 1} shares a top: $row", 1, row.map { it.top }.distinct().size)
            // Alike to the pixel a weight's rounding leaves.
            assertTrue("row ${i + 1}'s cells alike: $row", row.all { abs(it.width - row[0].width) <= 1f && abs(it.height - row[0].height) <= 1f })
        }
        assertTrue("rows in order", nodes[0][0].bottom <= nodes[1][0].top)
        assertTrue("columns line up", (0..3).all { abs(nodes[0][it].left - nodes[1][it].left) <= 1f })
    }

    /** Waits until the downloads' settings as saved meet [condition], the page's clock running meanwhile, as a tap's work may wait for it. */
    private fun saved(what: String, condition: (DownloadPrefs) -> Boolean) {
        compose.waitTill("saved: $what") { runBlocking { condition(settings.downloads.first()) } }
    }

    private fun selected(matcher: SemanticsMatcher): Boolean =
        compose.onAllNodes(matcher).fetchSemanticsNodes().any { it.config.getOrElseNullable(SemanticsProperties.Selected) { null } == true }

    private fun radio(text: String) = hasText(text) and SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.RadioButton)

    private fun exists(matcher: SemanticsMatcher) = compose.exists(matcher)

    private fun top(matcher: SemanticsMatcher): Float = compose.onAllNodes(matcher).fetchSemanticsNodes().first().boundsInRoot.top

    private fun left(matcher: SemanticsMatcher): Float = compose.onAllNodes(matcher).fetchSemanticsNodes().first().boundsInRoot.left

    private val switch = SemanticsMatcher.expectValue(SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Switch)

    private companion object {
        const val GB = 1_000_000_000L
        const val SEEDING_MILLIS = 60_000L

        /** How long a second ask of the host would take to follow the first: both come within milliseconds of the save. */
        const val ANOTHER_ASK_MILLIS = 1_500L

        /** A seeded file's bytes: small, its row says its size. */
        const val FILE_BYTES = 64 * 1024
    }
}
