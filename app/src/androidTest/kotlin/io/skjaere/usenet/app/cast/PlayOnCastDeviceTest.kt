package io.skjaere.usenet.app.cast

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.isNotSelected
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.media3.cast.Cast
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.castExtras
import io.skjaere.usenet.app.casting.CAST_CONNECTED
import io.skjaere.usenet.app.casting.CAST_CONNECTING
import io.skjaere.usenet.app.casting.CAST_COULD_NOT_CONNECT
import io.skjaere.usenet.app.casting.CAST_DEVICES
import io.skjaere.usenet.app.casting.CAST_LOOKING
import io.skjaere.usenet.app.casting.CAST_LOOKING_SAID
import io.skjaere.usenet.app.casting.CAST_LOOK_FOR
import io.skjaere.usenet.app.casting.CAST_LOOK_LINE
import io.skjaere.usenet.app.casting.CAST_NONE_FOUND
import io.skjaere.usenet.app.casting.CAST_PRIVACY
import io.skjaere.usenet.app.casting.CAST_SIMPLE
import io.skjaere.usenet.app.casting.CAST_UNAVAILABLE
import io.skjaere.usenet.app.casting.CAST_UNAVAILABLE_WHY
import io.skjaere.usenet.app.casting.CastDeviceFacts
import io.skjaere.usenet.app.casting.CastDeviceKind
import io.skjaere.usenet.app.casting.CastFeed
import io.skjaere.usenet.app.casting.CastLink
import io.skjaere.usenet.app.casting.CastLinkNow
import io.skjaere.usenet.app.casting.CastPlayerState
import io.skjaere.usenet.app.casting.CastServiceRules.Why
import io.skjaere.usenet.app.casting.CastTimings
import io.skjaere.usenet.app.casting.STOP_CASTING
import io.skjaere.usenet.app.casting.connectingTo
import io.skjaere.usenet.app.casting.stopCastingTo
import io.skjaere.usenet.app.collection.ShowScreen
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.home.HomeScreen
import io.skjaere.usenet.app.link.PlayChoice
import io.skjaere.usenet.app.link.PlayHere
import io.skjaere.usenet.app.link.PlayOnHost
import io.skjaere.usenet.app.link.PlayOnTv
import io.skjaere.usenet.app.link.PortChoice
import io.skjaere.usenet.app.link.PlayRouter
import io.skjaere.usenet.app.link.PlayRouterDeps
import io.skjaere.usenet.app.link.PlayTarget
import io.skjaere.usenet.app.link.SHEET_TITLE
import io.skjaere.usenet.app.link.SOUND_ONLY_TEXT
import io.skjaere.usenet.app.link.THIS_PHONE
import io.skjaere.usenet.app.link.TvState
import io.skjaere.usenet.app.link.onTvLine
import io.skjaere.usenet.app.link.playOnLabel
import io.skjaere.usenet.app.link.soundOnlyTitle
import io.skjaere.usenet.app.onMainThread
import io.skjaere.usenet.app.play.CastTo
import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.app.play.EpisodeTarget
import io.skjaere.usenet.app.play.MovieTarget
import io.skjaere.usenet.app.play.PlayOrigin
import io.skjaere.usenet.app.play.PlayOutcome
import io.skjaere.usenet.app.play.PlayRequest
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.ReleaseName
import io.skjaere.usenet.app.play.SavedRelease
import io.skjaere.usenet.app.play.SessionMetadata
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.core.FileCandidate
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.EpisodeRef
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import io.skjaere.usenet.data.WatchTitle
import io.skjaere.usenet.link.KeyId
import io.skjaere.usenet.logging.LogBuffer
import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.metadata.EpisodeSummary
import io.skjaere.usenet.metadata.ExternalIds
import io.skjaere.usenet.metadata.Season
import io.skjaere.usenet.metadata.SeasonSummary
import io.skjaere.usenet.metadata.ShowDetails
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/**
 * The Play on sheet's Cast group and the TV button with a Cast device (W32, W33, W34, L77, L78, L79, L95; CsPhoneSheet,
 * CsPhoneConnecting) on a real device, over fake devices and sessions (L73): Google's software is never started. The group
 * looks, then lists the devices that show video by name with a busy one's own line, or says none were found, or that Cast
 * isn't available here. A tap says connecting with a spinner, the sheet open and the button's waves; connected, the sheet
 * closes, the button fills with Google's Cast icon, and Play and Up next say where they go; refused, or not connected in
 * the (shrunk, in that case alone) 15 s, the row says so and This phone is chosen again. Stop casting, or This phone
 * while casting, ends it: Stop casting is the connected device's block's, over the list, its row keeping its ✓ (L117).
 * TalkBack hears "Cast device" after each name, and each step once. With "Look for Cast devices" off (L111, L112;
 * CsPhoneLookFor), the group asks first and nothing starts, also when the app opens the sheet itself; the tap looks at
 * once, in the same sheet, and the next opening looks with nothing to tap (CastFakes chooses it for the other cases,
 * L143); turned off while casting, the list keeps the session's block and Stop casting. A route's device facts travel
 * with its session (L153). Speakers and speaker groups are listed after the screens, each saying it plays only the sound,
 * and a Play with one connected asks first (W50, L122); a Cast device at one of your TVs' addresses says it also runs
 * NZStreamer (L121). The devices, show and releases are invented.
 */
@RunWith(AndroidJUnit4::class)
class PlayOnCastDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val container = context.container
    private val routes = FakeRoutes()
    private val connector = FakeConnector()
    private val announced = CopyOnWriteArrayList<String>()
    private val kitchen = castRoute("Kitchen display")
    private val den = castRoute("Den TV", description = "Casting Default Media Receiver")
    private val living = castRoute("Living room TV")
    private var tmdb: FakeTmdb? = null
    private var saved: AppSettings? = null
    private var keyAdded = false
    private var tvAdded: KeyId? = null
    private var page by mutableStateOf(Page.None)

    private enum class Page { None, Show, Home }

    private val show = ShowDetails(
        SHOW, "Harbor Lights", null, 2021, null, null, null, "Ended", false, 45,
        listOf(SeasonSummary(1, null, 6, LocalDate.of(2021, 3, 1), null)),
        lastEpisode = EpisodeSummary(1, 6, "Night Crossing", LocalDate.of(2021, 4, 5)), nextEpisode = null, ids = ExternalIds(tvdb = 991_401),
    )

    private val season = Season(SHOW, 1, null, null, (1..6).map { Episode(1, it, "Episode $it", null, LocalDate.of(2021, 3, 1).plusWeeks(it - 1L), 45, null) })

    @Before
    fun setUp() {
        assumeFalse("a TV has no Play on sheet, nor Cast (tv.TvNoCastTest)", container.television)
        // A device connects for as long as each case needs it to: only the give-up case shrinks the 15 s, as it taps.
        CastTimings.current = CastTimings(connectMillis = 60_000, noneFoundMillis = 1_500)
        CastFakes.started(routes, connector, announced)
        compose.setContent {
            AppTheme {
                when (page) {
                    Page.None -> Unit
                    Page.Show -> ShowScreen(SHOW, onBack = {})
                    Page.Home -> HomeScreen(onSearch = {}, onRecent = {}, onSettings = {}, onSetup = { _, _ -> }, onShow = {}, onCollection = {}, onImport = {})
                }
                PlayOnHost(onAddTv = {}, onPairAgain = {}, onPrivacy = {})
            }
        }
    }

    @After
    fun restore() {
        compose.runOnUiThread { container.playOn.closeSheet() }
        container.playOn.choose(PlayChoice.Phone)
        tmdb?.close()
        saved?.let { settings ->
            runBlocking {
                container.settings.restore(settings)
                if (keyAdded) container.settings.setTmdbKey(null)
                container.library.markEpisodes(listOf(EpisodeRef(SHOW, 1, 3, WatchTitle.episode("Harbor Lights", 1, 3))), watched = false)
                container.library.recordProgress(release(), 0, 2_700_000)
            }
        }
        tvAdded?.let { runBlocking { container.data.link.forgetTv(it) } }
        // Last: the settings as they were before CastFakes chose to look for Cast devices, which the test's own were read after.
        CastFakes.reset()
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(matcher: SemanticsMatcher, millis: Long = 10_000) = compose.waitUntil(millis) { exists(matcher) }

    private fun tap(matcher: SemanticsMatcher) {
        waitFor(matcher)
        compose.onNode(matcher).performClick()
    }

    private fun openSheet() {
        compose.runOnUiThread { container.playOn.openSheet() }
        waitFor(hasText(SHEET_TITLE))
    }

    /** A row by TalkBack's words for it: "Kitchen display, Cast device", and the line, if any. */
    private fun castRow(name: String, line: String? = null) = hasContentDescription(listOfNotNull(name, "Cast device", line).joinToString(", ")) and hasClickAction()

    private fun chosen(): PlayChoice = container.playOn.chosen.value

    private fun target(): PlayTarget = container.playOn.target.value

    private fun waitForTarget(target: PlayTarget) = compose.waitUntil(10_000) { target() == target }

    private val kitchenChoice = PlayChoice.Cast(kitchen.id, kitchen.name)

    /** Kitchen display tapped and connected: the sheet closed, the device chosen. */
    private fun connectKitchen() {
        routes.found(kitchen, den, living)
        openSheet()
        tap(castRow(kitchen.name))
        waitFor(hasText(CAST_CONNECTING))
        connector.started(kitchen)
        compose.waitUntil(10_000) { container.playOn.sheet.value == null }
        waitForTarget(PlayTarget.Cast(kitchen.id, kitchen.name, CastLink.Connected, kitchen.facts))
    }

    /** "Look for Cast devices" off, as on a phone that never chose it (L112): CastFakes chose it for the other cases. */
    private fun lookingOff() = runBlocking<Unit> {
        container.settings.updateCast { it.copy(look = false) }
        withTimeout(10_000) { container.castLooking.first { it == false } }
    }

    /** Whether Google's Cast software is still unstarted in this process, asked on the main thread (L143). */
    private fun castUnstarted(): Boolean = onMainThread { Cast.getSingletonInstance(context).needsInitialization() }

    /** The opt-in's row: "Look for Cast devices, button", its line read after it. */
    private val lookFor = hasText(CAST_LOOK_FOR) and hasClickAction()

    /**
     * W46, L111, L112 (CsPhoneLookFor 1; CsPhoneTalkBackC2 2-4): the first opening, the opt-in off, has "Cast to" hold one
     * row, "Look for Cast devices", its line, read after it, and Privacy, a link; no line about simple files. Nothing is
     * looked for, and nothing of Google's starts.
     */
    @Test
    fun theFirstOpeningAsksAndStartsNothing() {
        lookingOff()
        assertEquals("Play services there, as the fake says", true, container.castAvailable.value)
        openSheet()
        waitFor(lookFor)
        val heading = compose.onNode(hasContentDescription(CAST_DEVICES)).fetchSemanticsNode()
        assertTrue("\"Cast to, heading\"", heading.config.getOrNull(SemanticsProperties.Heading) != null)
        val row = compose.onNode(lookFor).fetchSemanticsNode()
        assertEquals("\"Look for Cast devices, button\"", Role.Button, row.config.getOrNull(SemanticsProperties.Role))
        assertFalse("its line is read after it, not in its name", row.config.getOrNull(SemanticsProperties.Text).orEmpty().any { CAST_LOOK_LINE in it.text })
        val line = compose.onNode(hasText(CAST_LOOK_LINE)).fetchSemanticsNode()
        assertTrue("under the row's name", row.boundsInRoot.top < line.boundsInRoot.top)
        val privacy = compose.onNode(hasText(CAST_PRIVACY)).fetchSemanticsNode()
        assertTrue("\"Privacy, link\"", privacy.config[SemanticsProperties.Text].any { it.getLinkAnnotations(0, it.length).isNotEmpty() })
        assertTrue("under the line", line.boundsInRoot.bottom <= privacy.boundsInRoot.top)
        assertFalse("nothing looks yet: no line about simple files", exists(hasText(CAST_SIMPLE)))
        assertFalse(exists(hasText(CAST_LOOKING)))
        assertEquals("nothing looked for", 0, routes.watching)
        assertEquals("nothing started", 0, CastFakes.starts.get())
        assertTrue("Google's Cast software unstarted", castUnstarted())
    }

    /**
     * L111: the list the app opens by itself (Play with a TV chosen that isn't open, as the router does) shows the row
     * too, and starts nothing before the opt-in.
     */
    @Test
    fun anOpeningByTheRouterShowsTheRowAndStartsNothing() {
        lookingOff()
        val closed = PlayTarget.Tv(KeyId.of(Random.nextBytes(91)), "Living room TV", TvState.NotOpen)
        val nothingHere = object : PlayHere {
            override fun playEpisode(ep: EpisodePlay, origin: PlayOrigin) = throw AssertionError("nothing plays here")
            override fun playEpisode(target: EpisodeTarget, origin: PlayOrigin) = throw AssertionError("nothing plays here")
            override fun playMovie(target: MovieTarget, origin: PlayOrigin) = throw AssertionError("nothing plays here")
            override fun resume(state: WatchState, origin: PlayOrigin) = throw AssertionError("nothing plays here")
            override fun play(request: PlayRequest, origin: PlayOrigin): Deferred<PlayOutcome> = throw AssertionError("nothing plays here")
            override fun castFile(nzbId: String, file: FileCandidate, origin: PlayOrigin) = throw AssertionError("nothing plays here")
        }
        val router = PlayRouter(
            PlayRouterDeps(
                target = { closed }, here = nothingHere, tv = PlayOnTv { _, _, _, _ -> throw AssertionError("nothing goes to a TV that isn't open") },
                watch = { null }, episode = { throw AssertionError("not looked up") }, movie = { _, _ -> throw AssertionError("not looked up") },
                now = System::currentTimeMillis, notOpen = container.playOn::openSheet,
            ),
        )
        onMainThread { router.playMovie(MovieTarget(FILM, "North Quay", 2023, null, 118)) }
        waitFor(hasText(SHEET_TITLE))
        waitFor(lookFor)
        assertEquals("nothing looked for", 0, routes.watching)
        assertEquals("nothing started", 0, CastFakes.starts.get())
        assertTrue("Google's Cast software unstarted", castUnstarted())
    }

    /**
     * W46, L112 (CsPhoneLookFor 2; CsPhoneTalkBackC2 3): the tap turns the opt-in on and, in the same sheet, the group
     * starts Google's software once and looks; TalkBack hears "Looking for Cast devices" once.
     */
    @Test
    fun theTapLooksInTheSameSheetAndStartsOnce() {
        lookingOff()
        openSheet()
        tap(lookFor)
        waitFor(hasText(CAST_LOOKING))
        assertTrue("the same sheet", container.playOn.sheet.value != null)
        assertEquals("started once", 1, CastFakes.starts.get())
        assertTrue("remembered", runBlocking { container.settings.cast.first().look })
        assertEquals(listOf(CAST_LOOKING_SAID), announced)
        assertTrue(exists(hasText(CAST_SIMPLE)))
        assertFalse(exists(hasText(CAST_LOOK_FOR)))
        compose.waitUntil(5_000) { routes.watching == 1 }
        routes.found(kitchen)
        waitFor(castRow(kitchen.name))
    }

    /** W46: once chosen, the next opening looks at once, with nothing to tap and nothing started again. */
    @Test
    fun theNextOpeningLooksAtOnce() {
        lookingOff()
        openSheet()
        tap(lookFor)
        waitFor(hasText(CAST_LOOKING))
        compose.runOnUiThread { container.playOn.closeSheet() }
        compose.waitUntil(5_000) { !exists(hasText(SHEET_TITLE)) }
        openSheet()
        waitFor(hasText(CAST_LOOKING))
        assertFalse(exists(hasText(CAST_LOOK_FOR)))
        assertEquals("started once in the process", 1, CastFakes.starts.get())
    }

    /**
     * L112, L117: "Look for Cast devices" turned off while casting (Settings › Google Cast) leaves the session as it is.
     * The list asks again, and the session's device heads it in its block, above the question, with Stop casting, which
     * still ends the session; nothing more is looked for.
     */
    @Test
    fun turnedOffWhileCastingTheListKeepsTheSessionsBlockAndStopCasting() {
        connectKitchen()
        lookingOff()
        openSheet()
        waitFor(lookFor)
        compose.waitForIdle()
        val block = hasText(kitchen.name) and isHeading()
        assertTrue("the session's device heads the list while it asks again", exists(block))
        fun top(matcher: SemanticsMatcher) = compose.onNode(matcher).fetchSemanticsNode().boundsInRoot.top
        assertTrue("above the question", top(block) < top(lookFor))
        assertTrue("This phone unticked", exists(hasText(THIS_PHONE) and isNotSelected()))
        assertFalse("nothing more looked for", exists(castRow(den.name)))
        assertEquals(kitchenChoice, chosen())
        tap(hasContentDescription(stopCastingTo(kitchen.name)) and hasClickAction())
        compose.waitUntil(5_000) { connector.ends.get() == 1 }
        compose.waitUntil(5_000) { chosen() == PlayChoice.Phone }
        // Gone with its session; the question stays.
        compose.waitUntil(5_000) { !exists(block) }
        assertTrue(exists(lookFor))
        assertTrue(exists(hasText(THIS_PHONE) and isSelected()))
    }

    @Test
    fun theGroupLooksThenSaysNoneWereFound() {
        openSheet()
        waitFor(hasText(CAST_LOOKING))
        assertTrue("its heading", compose.onAllNodes(hasContentDescription(CAST_DEVICES)).fetchSemanticsNodes().any { it.config.getOrNull(SemanticsProperties.Heading) != null })
        assertTrue(exists(hasText(CAST_SIMPLE)))
        assertEquals("the group looks while the sheet shows", 1, routes.watching)
        // After the shrunk 10 s with nothing found.
        waitFor(hasText(CAST_NONE_FOUND))
        assertFalse(exists(hasText(CAST_LOOKING)))
        // Found later, while it shows: listed.
        routes.found(kitchen)
        waitFor(castRow(kitchen.name))
        compose.runOnUiThread { container.playOn.closeSheet() }
        compose.waitUntil(5_000) { routes.watching == 0 }
    }

    /**
     * L78, L122 (W50; CsPhoneSheetConnected): the screens by name, a busy one with its own line, then the speakers, then the
     * speaker groups, each by name and saying it plays only the sound: none is hidden (Terms 3.4.5).
     */
    @Test
    fun theScreensThenTheSpeakersThenTheGroupsAreListedByNameWithTheirLines() {
        val hallway = castRoute("Hallway speaker", videoOut = false)
        val house = castRoute("Whole house", videoOut = false, group = true)
        routes.found(living, hallway, kitchen, house, den)
        openSheet()
        waitFor(castRow(kitchen.name))
        assertTrue(exists(castRow(den.name, "Casting Default Media Receiver") and isNotSelected()))
        assertTrue(exists(hasText("Casting Default Media Receiver")))
        assertTrue(exists(castRow(living.name) and isNotSelected()))
        assertTrue(exists(castRow(hallway.name, "Speaker · sound only") and isNotSelected()))
        assertTrue(exists(castRow(house.name, "Speaker group · sound only") and isNotSelected()))
        val rows = listOf(castRow(den.name, den.description), castRow(kitchen.name), castRow(living.name), castRow(hallway.name, "Speaker · sound only"), castRow(house.name, "Speaker group · sound only"))
        val tops = rows.map { compose.onNode(it).fetchSemanticsNode().boundsInRoot.top }
        assertEquals("the screens by name, then the speaker, then the group", tops.sorted(), tops)
        // One of the sheet's choices, as the TVs are.
        assertEquals(Role.RadioButton, compose.onNode(castRow(kitchen.name)).fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        assertTrue(exists(hasText(THIS_PHONE) and isSelected()))
    }

    /**
     * L121: a Cast device at the address of one of your TVs with NZStreamer (the Shield, in both groups) says it also runs
     * NZStreamer, by the TV's name where the two differ; TalkBack hears it after "Cast device". Never hidden.
     */
    @Test
    fun aCastDeviceAtOneOfYourTvsAddressesSaysItAlsoRunsNzStreamer() {
        val key = KeyId.of(Random.nextBytes(91))
        // This phone's link identity first: one made later would start with no TVs kept. A documentation address: nothing
        // there answers the TV's link.
        assertTrue(runBlocking { container.data.link.identity().let { container.data.link.addTv(key, "Living room TV", "192.0.2.31", PortChoice.DEFAULT) } })
        tvAdded = key
        val same = castRoute("Living room TV", address = "192.0.2.31")
        val named = castRoute("SHIELD", address = "192.0.2.31")
        routes.found(kitchen, same, named)
        openSheet()
        waitFor(castRow(same.name, "Also runs NZStreamer") and isNotSelected())
        waitFor(castRow(named.name, "Also runs NZStreamer: Living room TV") and isNotSelected())
        assertTrue("no other device says so", exists(castRow(kitchen.name)))
    }

    /**
     * W50, L122: a Play with a Cast speaker connected asks first, over the screen, before anything is searched: "Hallway
     * speaker plays only the sound", with Cast anyway, Play on this phone and Cancel. Play on this phone chooses This
     * phone, ending the session, and plays it here; with the speaker connected again, Cast anyway casts the same Play
     * there, and the session isn't asked again. Through the app's own router as the app wires it (PlayRouterDeps.of), its
     * plays recorded rather than started.
     */
    @Test
    fun aPlayWithASpeakerConnectedAsksFirstAndCastAnywayCastsThere() {
        val hallway = castRoute("Hallway speaker", videoOut = false)
        routes.found(kitchen, hallway)
        fun connectHallway() {
            openSheet()
            tap(castRow(hallway.name, "Speaker · sound only"))
            waitFor(hasText(CAST_CONNECTING))
            connector.started(hallway)
            compose.waitUntil(10_000) { container.playOn.sheet.value == null }
            waitForTarget(PlayTarget.Cast(hallway.id, hallway.name, CastLink.Connected, hallway.facts))
        }
        val played = CopyOnWriteArrayList<String>()
        val recorded = object : PlayHere {
            override fun playEpisode(ep: EpisodePlay, origin: PlayOrigin) = throw AssertionError("not this play")
            override fun playEpisode(target: EpisodeTarget, origin: PlayOrigin) = throw AssertionError("not this play")
            override fun playMovie(target: MovieTarget, origin: PlayOrigin) {
                played += "movie ${target.title} ${origin.cast?.name?.let { "cast to $it" } ?: "here"}"
            }
            override fun resume(state: WatchState, origin: PlayOrigin) = throw AssertionError("not this play")
            override fun play(request: PlayRequest, origin: PlayOrigin): Deferred<PlayOutcome> = throw AssertionError("not this play")
            override fun castFile(nzbId: String, file: FileCandidate, origin: PlayOrigin) = throw AssertionError("not this play")
        }
        val app = PlayRouterDeps.of(container)
        val router = PlayRouter(
            PlayRouterDeps(
                app.target, recorded, app.tv, app.watch, app.episode, app.movie, app.now, app.ask, app.notOpen,
                app.castAnywayTold, app.castAnyway, app.choosePhone,
            ),
        )
        val film = MovieTarget(FILM, "North Quay", 2023, null, 118)
        connectHallway()
        onMainThread { router.playMovie(film) }
        waitFor(hasText(soundOnlyTitle(hallway.name)))
        assertTrue(exists(hasText(SOUND_ONLY_TEXT)))
        assertTrue(exists(hasText("Cast anyway") and hasClickAction()))
        assertTrue(exists(hasText("Cancel") and hasClickAction()))
        assertTrue("nothing plays before the answer", played.isEmpty())
        tap(hasText("Play on this phone") and hasClickAction())
        compose.waitUntil(5_000) { played.size == 1 }
        assertEquals("movie North Quay here", played.single())
        compose.waitUntil("Play on this phone chose This phone", 5_000) { container.playOn.chosen.value == PlayChoice.Phone }
        compose.waitUntil("the speaker's session ended", 5_000) { connector.ends.get() == 1 }
        compose.waitUntil(5_000) { !exists(hasText(soundOnlyTitle(hallway.name))) }
        // Connected again: Cast anyway, once a session.
        connectHallway()
        onMainThread { router.playMovie(film) }
        waitFor(hasText(soundOnlyTitle(hallway.name)))
        tap(hasText("Cast anyway") and hasClickAction())
        compose.waitUntil(5_000) { played.size == 2 }
        assertEquals("movie North Quay cast to Hallway speaker", played.last())
        compose.waitUntil(5_000) { !exists(hasText(soundOnlyTitle(hallway.name))) }
        onMainThread { router.playMovie(film) }
        compose.waitUntil("the next Play cast there, not asked again in the session", 5_000) { played.size == 3 }
        assertFalse("not asked again in the session", exists(hasText(soundOnlyTitle(hallway.name))))
    }

    @Test
    fun withoutPlayServicesTheGroupSaysCastIsNotAvailable() {
        CastFakes.missing()
        assertEquals(false, container.castAvailable.value)
        openSheet()
        waitFor(hasText(CAST_UNAVAILABLE))
        assertTrue(exists(hasText(CAST_UNAVAILABLE_WHY)))
        assertFalse("no simple line", exists(hasText(CAST_SIMPLE)))
        assertFalse(exists(hasText(CAST_LOOKING)))
        assertEquals("nothing looked for", 0, routes.watching)
        // L111: Play services first, so a phone without it never sees "Look for Cast devices", the opt-in off or on.
        compose.runOnUiThread { container.playOn.closeSheet() }
        compose.waitUntil(5_000) { !exists(hasText(SHEET_TITLE)) }
        lookingOff()
        openSheet()
        waitFor(hasText(CAST_UNAVAILABLE))
        assertFalse(exists(hasText(CAST_LOOK_FOR)))
        assertEquals("nothing started", 0, CastFakes.starts.get())
    }

    @Test
    fun aTapConnectsWithTheSheetOpenThenItClosesAndPlaySaysWhere() {
        seedShowAndHome()
        page = Page.Show
        waitFor(hasText("Resume S01E03 · 2:00", substring = true))
        routes.found(kitchen, den, living)
        openSheet()
        tap(castRow(kitchen.name))
        // Connecting: the row says so, the sheet stays open, This phone is no longer ticked, the button's waves light.
        waitFor(hasText(CAST_CONNECTING))
        assertTrue(exists(castRow(kitchen.name, CAST_CONNECTING) and isSelected()))
        assertTrue(exists(hasText(THIS_PHONE) and isNotSelected()))
        assertTrue(container.playOn.sheet.value != null)
        assertEquals(listOf(kitchen.name), connector.selected)
        assertEquals(kitchenChoice, chosen())
        waitFor(hasContentDescription("Play on: Kitchen display, connecting"))
        assertEquals(listOf("Connecting to Kitchen display"), announced)
        // Connected: the sheet closes by itself, the button fills, Play says where it goes.
        connector.started(kitchen)
        compose.waitUntil(10_000) { container.playOn.sheet.value == null }
        waitFor(hasContentDescription(playOnLabel(PlayTarget.Cast(kitchen.id, kitchen.name, CastLink.Connected, kitchen.facts))))
        assertEquals(listOf("Connecting to Kitchen display", "Connected to Kitchen display"), announced)
        waitFor(hasText("Resume S01E03 · 2:00", substring = true) and hasText(onTvLine(kitchen.name), substring = true) and hasClickAction())
        // Home: the round button the same, and Up next's rows play there, a release played without TMDB too (L80).
        page = Page.Home
        waitFor(hasContentDescription("Play on: Kitchen display"))
        waitFor(hasContentDescription("Resume Harbor Lights S01E03 on Kitchen display"))
        // The release's row, named by what its name says.
        waitFor(hasContentDescription("Resume ${ReleaseName(RELEASE_TITLE).query} on Kitchen display"))
    }

    /**
     * W34, L95: a sheet made again while the device connects (closed and opened, as a rotation makes it again too) still
     * closes as it connects, and TalkBack hears "Connected" once.
     */
    @Test
    fun aSheetMadeAgainWhileTheDeviceConnectsClosesAsItConnects() {
        routes.found(kitchen)
        openSheet()
        tap(castRow(kitchen.name))
        waitFor(hasText(CAST_CONNECTING))
        compose.runOnUiThread { container.playOn.closeSheet() }
        compose.waitUntil(5_000) { !exists(hasText(SHEET_TITLE)) }
        openSheet()
        waitFor(castRow(kitchen.name, CAST_CONNECTING))
        connector.started(kitchen)
        compose.waitUntil(10_000) { container.playOn.sheet.value == null }
        assertEquals(listOf("Connecting to Kitchen display", "Connected to Kitchen display"), announced)
    }

    /** A device that connected while no sheet showed: the sheet opened later stays open, and says nothing more. */
    @Test
    fun aSheetOpenedAfterTheDeviceConnectedStaysOpen() {
        routes.found(kitchen)
        openSheet()
        tap(castRow(kitchen.name))
        waitFor(hasText(CAST_CONNECTING))
        compose.runOnUiThread { container.playOn.closeSheet() }
        compose.waitUntil(5_000) { !exists(hasText(SHEET_TITLE)) }
        connector.started(kitchen)
        waitForTarget(PlayTarget.Cast(kitchen.id, kitchen.name, CastLink.Connected, kitchen.facts))
        openSheet()
        waitFor(castRow(kitchen.name, CAST_CONNECTED))
        compose.mainClock.advanceTimeBy(2_000)
        assertTrue("the sheet stays open", container.playOn.sheet.value != null)
        assertEquals(listOf("Connecting to Kitchen display"), announced)
    }

    @Test
    fun playGreysWithWhatItWaitsForWhileTheDeviceConnects() {
        seedShowAndHome()
        page = Page.Show
        routes.found(kitchen)
        openSheet()
        tap(castRow(kitchen.name))
        waitFor(hasText(CAST_CONNECTING))
        compose.runOnUiThread { container.playOn.closeSheet() }
        waitFor(hasText(connectingTo(kitchen.name)))
        compose.onNode(hasText("Resume S01E03 · 2:00", substring = true) and hasClickAction()).assertIsNotEnabled()
        page = Page.Home
        waitFor(hasContentDescription("Resume Harbor Lights S01E03 on Kitchen display"))
        compose.onNode(hasContentDescription("Resume Harbor Lights S01E03 on Kitchen display")).assertIsNotEnabled()
    }

    @Test
    fun refusedOrNotConnectedInTimeTheRowSaysSoAndThisPhoneIsChosen() {
        routes.found(kitchen, den)
        openSheet()
        tap(castRow(kitchen.name))
        waitFor(hasText(CAST_CONNECTING))
        connector.refused(kitchen)
        waitFor(castRow(kitchen.name, CAST_COULD_NOT_CONNECT))
        assertTrue(exists(hasText(CAST_COULD_NOT_CONNECT)))
        compose.waitUntil(5_000) { chosen() == PlayChoice.Phone }
        waitFor(hasText(THIS_PHONE) and isSelected())
        assertTrue("the sheet stays open", container.playOn.sheet.value != null)
        // A tap tries again; this time nothing comes in the shrunk 15 s, and the attempt is given up.
        CastTimings.current = CastTimings.current.copy(connectMillis = 3_000)
        tap(castRow(kitchen.name, CAST_COULD_NOT_CONNECT))
        waitFor(castRow(kitchen.name, CAST_CONNECTING))
        assertEquals(kitchenChoice, chosen())
        waitFor(castRow(kitchen.name, CAST_COULD_NOT_CONNECT), millis = 10_000)
        compose.waitUntil(5_000) { chosen() == PlayChoice.Phone }
        assertEquals("the late attempt given up", 1, connector.ends.get())
    }

    /**
     * L117 (CsPhoneSheetConnected): opened during a cast, the device's row keeps Google's filled icon, "Connected" in green
     * and its ✓; Stop casting is the block's, over the list, and ends the cast.
     */
    @Test
    fun stopCastingInTheBlockEndsTheCast() {
        connectKitchen()
        openSheet()
        waitFor(castRow(kitchen.name, CAST_CONNECTED) and isSelected())
        assertTrue(exists(hasText(THIS_PHONE) and isNotSelected()))
        val stop = hasContentDescription(stopCastingTo(kitchen.name)) and hasClickAction()
        waitFor(stop)
        fun top(matcher: SemanticsMatcher) = compose.onNode(matcher).fetchSemanticsNode().boundsInRoot.top
        assertTrue("in the block, over the list", top(stop) < top(hasText(SHEET_TITLE)))
        tap(stop)
        compose.waitUntil(5_000) { connector.ends.get() == 1 }
        compose.waitUntil(5_000) { chosen() == PlayChoice.Phone }
        waitFor(castRow(kitchen.name) and isNotSelected())
        assertFalse(exists(hasText(STOP_CASTING)))
        assertTrue(exists(hasText(THIS_PHONE) and isSelected()))
    }

    /**
     * L153: what a device's route says of it (its Cast id, model, address, kind and volume) goes with its session from the
     * tap, and Play's target carries it once connected, for the play's CastTo and its ranking. The session's start is said
     * once, at INFO, by the device's model, kind and volume, never its name or address (M1 and O4 read it in Settings ›
     * Logs); a session that drops and comes back is the same one.
     */
    @Test
    fun aDevicesFactsTravelWithItsSessionToPlaysTarget() {
        val shield = castRoute("Living room TV", model = "SHIELD Android TV", address = "192.168.1.31", fixedVolume = true)
        val facts = CastDeviceFacts("test-device:Living room TV", "SHIELD Android TV", "192.168.1.31", CastDeviceKind.Video, fixedVolume = true)
        val mark = LogBuffer.snapshot().lastOrNull()?.seq ?: 0
        fun logged() = LogBuffer.snapshot().filter { it.seq > mark }
        fun connectedLines() = logged().filter { it.message.startsWith("cast: connected to") }
        routes.found(kitchen, shield)
        openSheet()
        tap(castRow(shield.name))
        waitFor(hasText(CAST_CONNECTING))
        assertEquals(facts, castExtras?.now?.value?.device)
        assertEquals("nothing said before the session starts", emptyList<String>(), connectedLines().map { it.message })
        connector.started(shield)
        compose.waitUntil(10_000) { container.playOn.sheet.value == null }
        waitForTarget(PlayTarget.Cast(shield.id, shield.name, CastLink.Connected, facts))
        assertEquals(CastLinkNow(shield.id, shield.name, CastLink.Connected, facts), castExtras?.now?.value)
        assertEquals(listOf("cast: connected to a SHIELD Android TV (video, route volume fixed)"), connectedLines().map { it.message })
        assertEquals(org.slf4j.event.Level.INFO, connectedLines().single().level)
        connector.suspended(shield)
        waitForTarget(PlayTarget.Cast(shield.id, shield.name, CastLink.Reconnecting, facts))
        connector.resumed(shield)
        waitForTarget(PlayTarget.Cast(shield.id, shield.name, CastLink.Connected, facts))
        assertEquals("said once a session, not again as it comes back", 1, connectedLines().size)
        assertFalse("never the device's name or address", logged().any { shield.name in it.message || "192.168.1.31" in it.message })
    }

    /** L78 (CsPhoneSheet's last frame): the connected row says what plays there while a cast play lives, "Connected" before. */
    @Test
    fun theConnectedRowSaysWhatPlaysThere() {
        connectKitchen()
        openSheet()
        waitFor(castRow(kitchen.name, CAST_CONNECTED) and isSelected())
        val episode = WatchTarget(WatchKind.Episode, WatchTitle.episode("Harbor Lights", 2, 4), showTmdbId = SHOW, season = 2, episode = 4)
        val release = SavedRelease("play-on-cast-test", RELEASE_TITLE, RELEASE_TITLE, null, 0, "play-on-cast-nzb", "harbor-lights.mkv", 2_000_000_000)
        val launch = PlayerLaunch(
            UsenetUris.build(release.nzbId, 0, release.fileName, release.fileSize), "Harbor Lights", "S02E04 · Low Water", WatchKey.episode(SHOW, 2, 4), episode,
            release, cast = CastTo(kitchen.id, kitchen.name),
        )
        // The play on the device, as its load follows it.
        onMainThread { Casting.follows(CastFeed("play-on-cast-load", launch, CastPlayerState.Playing, 1_930_000, 3_120_000)) }
        waitFor(castRow(kitchen.name, "Playing Harbor Lights · S02E04") and isSelected())
        assertTrue("in green, Stop casting still the block's", exists(hasContentDescription(stopCastingTo(kitchen.name)) and hasClickAction()))
        assertFalse(exists(hasText(CAST_CONNECTED)))
    }

    /**
     * L85, L117: the block's Stop casting with a cast play running ends the play as Stop casting does (the notification's
     * ×), not as a session that went by itself: one still starting is cancelled with its sheet, and the play is said as
     * stopped, never as closed after a long pause.
     */
    @Test
    fun stopCastingInTheBlockEndsACastPlayAsStopCasting() {
        connectKitchen()
        val said = SessionMetadata.starting("Harbor Lights", "S01E03 · Episode 3", WatchTarget(WatchKind.Episode, WatchTitle.episode("Harbor Lights", 1, 3), showTmdbId = SHOW, season = 1, episode = 3), container.appName)
        assertTrue("the cast's service started", onMainThread { container.castLauncher()?.starting(CastTo(kitchen.id, kitchen.name), said) == true })
        val play = onMainThread { CastPlays.now } ?: throw AssertionError("no cast play")
        openSheet()
        tap(hasContentDescription(stopCastingTo(kitchen.name)) and hasClickAction())
        assertEquals(Why.StopCasting, runBlocking { withTimeout(10_000) { play.over.await() } })
        compose.waitUntil(5_000) { connector.ends.get() == 1 }
        compose.waitUntil(5_000) { chosen() == PlayChoice.Phone }
    }

    @Test
    fun thisPhoneChosenWhileCastingEndsTheCast() {
        connectKitchen()
        openSheet()
        waitFor(castRow(kitchen.name, CAST_CONNECTED))
        tap(hasText(THIS_PHONE) and hasClickAction())
        compose.waitUntil(5_000) { connector.ends.get() == 1 }
        assertEquals(PlayChoice.Phone, chosen())
        compose.waitUntil(5_000) { container.playOn.sheet.value == null }
        waitForTarget(PlayTarget.Phone)
    }

    @Test
    fun theSessionsEndChoosesThisPhone() {
        connectKitchen()
        // Ended by the device, or by another sender (W36).
        connector.ended(kitchen)
        waitForTarget(PlayTarget.Phone)
        assertEquals(PlayChoice.Phone, chosen())
        assertEquals("nothing more to end", 0, connector.ends.get())
    }

    /** Harbor Lights S01E03 two minutes in, and a release played without TMDB, in Up next; TMDB the test's own. */
    private fun seedShowAndHome() {
        runBlocking {
            saved = container.settings.settings.first()
            assumeTrue("no Usenet provider saved, so Home shows the first-run screen", container.settings.providers.first().isNotEmpty())
            container.settings.setIndexerSetupSkipped(true)
            if (container.settings.tmdbKey() == null) {
                container.settings.setTmdbKey("play-on-cast-device-test")
                keyAdded = true
            }
            val episode = WatchState(WatchKey.episode(SHOW, 1, 3), WatchKind.Episode, showTmdbId = SHOW, season = 1, episode = 3, title = WatchTitle.episode("Harbor Lights", 1, 3), updatedAt = 0)
            container.library.recordProgress(episode, 120_000, 2_700_000)
            container.library.recordProgress(release(), 300_000, 2_700_000)
        }
        tmdb = FakeTmdb(shows = mapOf(SHOW to show), seasons = { id, _ -> if (id == SHOW) season else null })
    }

    private fun release() = WatchState(WatchKey.release("play-on-cast-test", 0), WatchKind.Release, title = RELEASE_TITLE, updatedAt = 0)

    private companion object {
        /** Invented: TMDB ids nothing real has, and a release name of no real release. */
        const val SHOW = 991_331
        const val FILM = 991_332
        const val RELEASE_TITLE = "Copper.Lantern.Home.Video.2019"
    }
}
