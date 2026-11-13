package io.skjaere.usenet.app.cast

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.media3.cast.Cast
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.ImportPicker
import io.skjaere.usenet.app.ImportScreen
import io.skjaere.usenet.app.collection.CollectionScreen
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.discover.DiscoverDeps
import io.skjaere.usenet.app.discover.DiscoverListScreen
import io.skjaere.usenet.app.discover.DiscoverScreen
import io.skjaere.usenet.app.discover.FakeDiscover
import io.skjaere.usenet.app.link.CastButtonLook
import io.skjaere.usenet.app.link.FakeTv
import io.skjaere.usenet.app.link.PlayChoice
import io.skjaere.usenet.app.link.PlayOnDeps
import io.skjaere.usenet.app.link.PlayOnHost
import io.skjaere.usenet.app.link.PlayOnList
import io.skjaere.usenet.app.link.SHEET_TITLE
import io.skjaere.usenet.app.link.SheetPlace
import io.skjaere.usenet.app.link.THIS_PHONE
import io.skjaere.usenet.app.link.TITLE_PAGE_LINE
import io.skjaere.usenet.app.link.inventedTvName
import io.skjaere.usenet.app.onMainThread
import io.skjaere.usenet.app.play.PlayOutcome
import io.skjaere.usenet.app.search.ReleaseList
import io.skjaere.usenet.app.search.SearchScreen
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.discover.DiscoverFilters
import io.skjaere.usenet.discover.DiscoverList
import io.skjaere.usenet.discover.DiscoverQuery
import io.skjaere.usenet.metadata.MediaType
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.search.ResultGroup
import io.skjaere.usenet.search.ResultGrouper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The Cast button on every screen with something to play (W44, W59, L115, L116, L148, L151; CsPhonePlaces) on a real
 * phone, over fake devices (L73): Search, a release list, Import, Collection, Discover and its list page each have it,
 * and each opens Play on's list from its own place: Search's, the release list's and Import's as screens whose Play plays
 * here, the others as a title's page. Drawing each and opening its list start nothing of Google's. From Search, with one
 * of your TVs chosen, the button says this phone, the list greys the TV with where it plays from and ticks This phone,
 * and a tap on This phone chooses it everywhere. Collection's Select and + Add show as icons, read as their words. A
 * handler given the list takes a Cast row's tap, and the group connects nothing. The TV, the devices and the releases are
 * invented.
 */
@RunWith(AndroidJUnit4::class)
class CastPlacesDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.container
    private val link = container.data.link
    private val routes = FakeRoutes()
    private val connector = FakeConnector()
    private val announced = CopyOnWriteArrayList<String>()
    private val kitchen = castRoute("Kitchen display")
    private val realPicker = ImportPicker.contract
    private val discover = FakeDiscover()
    private var tv: FakeTv? = null
    private var saved: AppSettings? = null
    private var page by mutableStateOf(Page.None)

    private enum class Page { None, Search, Releases, Import, Collection, Discover, DiscoverList, List }

    @Before
    fun setUp() {
        assumeFalse("a TV has no Cast button (tv.TvNoCastTest)", container.television)
        saved = runBlocking { container.settings.settings.first() }
        CastFakes.started(routes, connector, announced)
        routes.found(kitchen)
        // Import's picker answered by no app: its screen stays, with nothing picked.
        ImportPicker.contract = {
            object : ActivityResultContract<Array<String>, Uri?>() {
                override fun createIntent(context: Context, input: Array<String>) = Intent("io.skjaere.usenet.test.NO_DOCUMENT_PICKER")
                override fun parseResult(resultCode: Int, intent: Intent?): Uri? = null
            }
        }
        val group = releases()
        val deps = DiscoverDeps(
            hasTmdbKey = flowOf(true), showArtwork = flowOf(false), language = flowOf(null),
            collectedShows = flowOf(emptySet()), collectedMovies = flowOf(emptySet()), source = discover,
        )
        val query = DiscoverQuery(MediaType.Tv, DiscoverList.Trending, DiscoverFilters())
        compose.setContent {
            AppTheme {
                when (page) {
                    Page.None -> Unit
                    Page.Search -> SearchScreen(initial = null, onIndexers = {}, onPlay = { CompletableDeferred(PlayOutcome.Stopped) })
                    Page.Releases -> ReleaseList(group, "Harbor Lights", onBack = {}, onPlay = {})
                    Page.Import -> ImportScreen(opened = null, onBack = {}, onProviders = {})
                    Page.Collection -> CollectionScreen(onShow = {}, onMovie = {}, onAdd = {}, onMetadata = {})
                    Page.Discover -> DiscoverScreen(onShow = {}, onMovie = {}, onList = {}, onMetadata = {}, deps = { deps })
                    Page.DiscoverList -> DiscoverListScreen(query, onBack = {}, onShow = {}, onMovie = {}, onMetadata = {}, deps = { deps })
                    Page.List -> PlayOnList(PlayOnDeps.of(container), SheetPlace.Player, onAddTv = {}, onPrivacy = {}, onCastTapped = { handler(it) })
                }
                if (page != Page.List) PlayOnHost(onAddTv = {}, onPairAgain = {}, onPrivacy = {})
            }
        }
    }

    @After
    fun restore() {
        compose.runOnUiThread { container.playOn.closeSheet() }
        container.playOn.choose(PlayChoice.Phone)
        ImportPicker.contract = realPicker
        discover.close()
        tv?.let {
            it.close()
            runBlocking { link.forgetTv(it.key.id) }
        }
        // Last: the settings as they were before CastFakes chose to look for Cast devices.
        CastFakes.reset()
        saved?.let { runBlocking { container.settings.restore(it) } }
    }

    /** What a Cast row's tap goes to on the list of [Page.List]: true takes it. */
    @Volatile
    private var handler: (CastRoute) -> Boolean = { true }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, millis: Long = 15_000) = compose.waitUntil(millis) { exists(matcher) }

    /** The Cast button, by its own semantics. */
    private val button = SemanticsMatcher.keyIsDefined(CastButtonLook)

    /** What TalkBack reads of the button now. */
    private fun buttonWords(): String? =
        compose.onAllNodes(button).fetchSemanticsNodes().singleOrNull()?.config?.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()

    /** Whether Google's Cast software is still unstarted in this process, asked on the main thread (L143). */
    private fun castUnstarted(): Boolean = onMainThread { Cast.getSingletonInstance(context).needsInitialization() }

    /** Three releases of an invented episode, ranked as Search ranks them, in one group: a release list's. */
    private fun releases(): ResultGroup {
        fun item(title: String, size: Long) = NewznabItem(
            indexerId = "cast-places", guid = title, title = title, sizeBytes = size, publishedAt = null, postedAt = null,
            categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null,
            tvmazeId = null, season = null, episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
        )
        val ranking = runBlocking { container.rankingContext() }
        return ResultGrouper().group(RELEASES.map { (title, size) -> item(title, size) }, ranking).sections.flatMap { it.groups }.first()
    }

    /** [screen] shown: its button there, its list opened from it from [place], with the Cast group as it opens. */
    private fun opensFrom(screen: Page, place: SheetPlace) {
        page = screen
        waitFor(button and hasClickAction())
        assertEquals("$screen: where Play goes", "Play on: this phone", buttonWords())
        assertTrue("$screen drawn: Google's Cast software unstarted", castUnstarted())
        compose.onNode(button and hasClickAction()).performClick()
        waitFor(hasText(SHEET_TITLE))
        assertEquals("$screen's list from its place", place, container.playOn.sheet.value)
        waitFor(hasContentDescription("Kitchen display, Cast device"))
        assertTrue("$screen's list open: Google's Cast software unstarted", castUnstarted())
        compose.runOnUiThread { container.playOn.closeSheet() }
        compose.waitUntil(5_000) { !exists(hasText(SHEET_TITLE)) }
    }

    @Test
    fun everyScreenWithSomethingToPlayHasTheButtonAndOpensTheListFromItsPlace() {
        opensFrom(Page.Search, SheetPlace.PhoneOnly)
        opensFrom(Page.Releases, SheetPlace.PhoneOnly)
        opensFrom(Page.Import, SheetPlace.PhoneOnly)
        opensFrom(Page.Collection, SheetPlace.Title)
        opensFrom(Page.Discover, SheetPlace.Title)
        opensFrom(Page.DiscoverList, SheetPlace.Title)
        // The fakes' Cast "started" for the group's opening, never Google's.
        assertTrue("Google's Cast software unstarted", castUnstarted())
    }

    /**
     * W59, L116, L148: with your TV chosen, Search's button says this phone, as Play plays here; its list greys the TV with
     * where it plays from, can't choose it, and ticks This phone; a tap there chooses This phone everywhere. The same TV on
     * a title's page (Collection's) is ticked and can be chosen.
     */
    @Test
    fun fromSearchTheTvIsGreyedAndThisPhoneTickedWithTheTvChosen() {
        val fake = runBlocking {
            val phone = link.identity().identity.key.id
            FakeTv(context, inventedTvName("Living room TV"), paired = setOf(phone), advertised = false).also { fake ->
                tv = fake
                assertTrue(link.addTv(fake.key.id, fake.name, fake.address.hostAddress!!, fake.port))
            }
        }
        container.playOn.choose(PlayChoice.Tv(fake.key.id))
        page = Page.Search
        waitFor(button and hasClickAction())
        assertEquals("Play on: this phone", buttonWords())
        compose.onNode(button and hasClickAction()).performClick()
        waitFor(hasText(TITLE_PAGE_LINE))
        val tvRow = hasText(fake.name) and hasClickAction()
        assertFalse("the TV can't be chosen from Search", exists(tvRow and isEnabled()))
        assertFalse("nor is it ticked", exists(tvRow and isSelected()))
        assertTrue("This phone ticked", exists(hasText(THIS_PHONE) and isSelected()))
        // From a title's page the same TV is ticked and can be chosen.
        compose.runOnUiThread { container.playOn.closeSheet() }
        compose.waitUntil(5_000) { !exists(hasText(SHEET_TITLE)) }
        page = Page.Collection
        waitFor(button and hasClickAction())
        compose.onNode(button and hasClickAction()).performClick()
        waitFor(tvRow and isSelected())
        assertTrue("the TV can be chosen there", exists(tvRow and isEnabled()))
        assertFalse(exists(hasText(TITLE_PAGE_LINE)))
        assertFalse("This phone not ticked there", exists(hasText(THIS_PHONE) and isSelected()))
        compose.runOnUiThread { container.playOn.closeSheet() }
        compose.waitUntil(5_000) { !exists(hasText(SHEET_TITLE)) }
        // From Search again, This phone tapped: chosen for every screen, and the list closes.
        page = Page.Search
        waitFor(button and hasClickAction())
        compose.onNode(button and hasClickAction()).performClick()
        waitFor(hasText(THIS_PHONE) and isSelected())
        compose.onNode(hasText(THIS_PHONE) and hasClickAction()).performClick()
        compose.waitUntil(5_000) { container.playOn.chosen.value == PlayChoice.Phone && container.playOn.sheet.value == null }
        assertTrue("Google's Cast software unstarted", castUnstarted())
    }

    /** W66: Collection's bar makes room for the button: Select and + Add as icons, which TalkBack still reads by their words. */
    @Test
    fun collectionsSelectAndAddAreIconsReadAsTheirWords() {
        page = Page.Collection
        waitFor(button and hasClickAction())
        assertTrue(exists(hasContentDescription("Select") and hasClickAction()))
        assertTrue(exists(hasContentDescription("Add to the collection") and hasClickAction()))
        assertFalse("no words on the pills", exists(hasText("+ Add")) || exists(hasText("Select")))
    }

    /** L151: the list given a handler (the player's) hands it a Cast row's tap; answering true, nothing connects or is chosen. */
    @Test
    fun aHandlerTakesACastRowsTapAndTheGroupConnectsNothing() {
        val tapped = CopyOnWriteArrayList<String>()
        handler = { route -> tapped += route.name; true }
        page = Page.List
        waitFor(hasContentDescription("Kitchen display, Cast device") and hasClickAction())
        compose.onNode(hasContentDescription("Kitchen display, Cast device") and hasClickAction()).performClick()
        compose.waitUntil(5_000) { tapped.isNotEmpty() }
        assertEquals(listOf("Kitchen display"), tapped)
        compose.waitForIdle()
        assertEquals("nothing connected", emptyList<String>(), connector.selected)
        assertEquals(PlayChoice.Phone, container.playOn.chosen.value)
        // Answering false, the group connects it and chooses it, as without a handler.
        handler = { route -> tapped += route.name; false }
        compose.onNode(hasContentDescription("Kitchen display, Cast device") and hasClickAction()).performClick()
        compose.waitUntil(5_000) { connector.selected.isNotEmpty() }
        assertEquals(listOf("Kitchen display"), connector.selected)
        assertEquals(PlayChoice.Cast(kitchen.id, kitchen.name), container.playOn.chosen.value)
        assertTrue("Google's Cast software unstarted", castUnstarted())
    }

    private companion object {
        val RELEASES = listOf(
            "Harbor.Lights.S02E05.The.Long.Tide.1080p.WEB-DL.DDP5.1.H.264-NZST" to 3_200_000_000L,
            "Harbor.Lights.S02E05.The.Long.Tide.720p.WEB-DL.DDP5.1.H.264-NZST" to 1_400_000_000L,
        )
    }
}
