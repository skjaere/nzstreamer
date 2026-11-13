package io.skjaere.usenet.app.link

import io.skjaere.usenet.app.casting.CAST_CONNECTED
import io.skjaere.usenet.app.casting.CAST_CONNECTING
import io.skjaere.usenet.app.casting.CAST_COULD_NOT_CONNECT
import io.skjaere.usenet.app.casting.CAST_DEVICES
import io.skjaere.usenet.app.casting.CAST_LOOKING
import io.skjaere.usenet.app.casting.CAST_NONE_FOUND
import io.skjaere.usenet.app.casting.CAST_SIMPLE
import io.skjaere.usenet.app.casting.CAST_UNAVAILABLE
import io.skjaere.usenet.app.casting.CAST_UNAVAILABLE_WHY
import io.skjaere.usenet.app.casting.CastLink
import io.skjaere.usenet.app.casting.CastLinkNow
import io.skjaere.usenet.app.casting.CastRowLook
import io.skjaere.usenet.app.casting.STOP_CASTING
import io.skjaere.usenet.app.casting.castRowDescription
import io.skjaere.usenet.app.casting.castRowLook
import io.skjaere.usenet.app.casting.connectedAnnouncement
import io.skjaere.usenet.app.casting.connectingAnnouncement
import io.skjaere.usenet.app.casting.connectingTo
import io.skjaere.usenet.app.casting.stopCastingTo
import io.skjaere.usenet.data.LinkFile
import io.skjaere.usenet.data.LinkStore
import io.skjaere.usenet.data.PairedTv
import io.skjaere.usenet.link.KeyId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proposal B's TV button and the Play on sheet (W11, W12, L41; PtPhoneWhere B, PtPhoneSheet, PtPhonePairFail): what the
 * button says to TalkBack, Play's lines under it, each TV's row by its state, and what a tap on it does. And with a Cast
 * device (W33, W34, L78, L79, L95; CsPhoneSheet, CsPhoneConnecting): the button and Play while it connects and once
 * connected, the group's words, and each Cast row by the session now. The names are invented.
 */
class PlayOnSheetTest {
    private val living = KeyId.of(Random.nextBytes(91))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterEach
    fun stop() {
        scope.cancel()
    }

    private fun kept(key: KeyId, name: String) = PairedTv(key.hex, name, pairedAt = 0)

    @Test
    fun `the button says where Play goes, and whether that TV is open`() {
        assertEquals("Play on: this phone", playOnLabel(PlayTarget.Phone))
        assertEquals("Play on: Living room TV", playOnLabel(PlayTarget.Tv(living, "Living room TV", TvState.Ready)))
        assertEquals("Play on: Living room TV, not open", playOnLabel(PlayTarget.Tv(living, "Living room TV", TvState.NotOpen)))
        assertEquals("Play on: Living room TV, not open", playOnLabel(PlayTarget.Tv(living, "Living room TV", null)))
        // Reached, though it can't play yet: open, and Play asks it (the TV says what it needs).
        assertEquals("Play on: Living room TV", playOnLabel(PlayTarget.Tv(living, "Living room TV", TvState.NeedsProvider)))
    }

    @Test
    fun `Play is open on a TV that answers, and greyed with what to do while NZStreamer isn't open there`() {
        assertTrue(PlayTarget.Phone.open)
        // A TV chosen before its debrid way lacked a part still gets the Play, and says what it lacks (D2PhoneTvStates 3).
        for (state in listOf(TvState.Ready, TvState.NeedsProvider, TvState.NeedsIndexer, TvState.NeedsService, TvState.ServiceOff, TvState.NeedsTorrentIndexer)) {
            assertTrue(PlayTarget.Tv(living, "Living room TV", state).open, "$state")
        }
        for (state in listOf(null, TvState.NotOpen, TvState.SetUpAgain, TvState.RemovedHere, TvState.UpdateTv, TvState.UpdatePhone)) {
            assertFalse(PlayTarget.Tv(living, "Living room TV", state).open, "$state")
        }
        // Greyed only while NZStreamer isn't open there, or the TV isn't reached yet: Play on one set up again asks instead.
        assertFalse(PlayTarget.Phone.greyed)
        for (state in TvState.entries + listOf(null)) {
            assertEquals(state == null || state == TvState.NotOpen, PlayTarget.Tv(living, "Living room TV", state).greyed, "$state")
        }
        assertEquals("on Living room TV", onTvLine("Living room TV"))
        assertEquals("Open NZStreamer on Living room TV", openNzStreamerOn("Living room TV"))
        assertEquals("A release you choose plays on this phone.", CHOSEN_PLAYS_HERE)
    }

    @Test
    fun `the sheet's words`() {
        assertEquals("Play on", SHEET_TITLE)
        assertEquals("This phone", THIS_PHONE)
        assertEquals("TVs with NZStreamer", TVS_WITH_NZSTREAMER)
        assertEquals("Play on NZStreamer on your TV, with this phone as the remote.", ADD_TV_LINE)
        assertEquals("The TV finds the release on its own indexers and plays it with its own player. NZStreamer must be open on the TV.", PLAY_ON_FOOT)
    }

    @Test
    fun `each TV's row by its state, in the order kept, the choice ticked`() {
        val keys = TvState.entries.associateWith { KeyId.of(Random.nextBytes(91)) }
        val unknown = KeyId.of(Random.nextBytes(91))
        val tvs = keys.map { (state, key) -> kept(key, "TV ${state.name}") } + kept(unknown, "New TV")
        val rows = playOnRows(tvs, keys.map { (state, key) -> key to TvNow(state, state == TvState.Ready) }.toMap(), chosen = keys.getValue(TvState.NotOpen))
        assertEquals(tvs.map { it.name }, rows.map { it.name })
        val by = rows.associateBy { it.state }
        assertEquals("Ready", by.getValue(TvState.Ready).line)
        assertEquals("Open NZStreamer on the TV", by.getValue(TvState.NotOpen).line)
        assertEquals("Needs a Usenet provider on the TV", by.getValue(TvState.NeedsProvider).line)
        assertEquals("Needs an indexer on the TV", by.getValue(TvState.NeedsIndexer).line)
        assertEquals("Needs a debrid service on the TV", by.getValue(TvState.NeedsService).line)
        assertEquals("The TV’s debrid service is off", by.getValue(TvState.ServiceOff).line)
        assertEquals("Needs a torrent indexer on the TV", by.getValue(TvState.NeedsTorrentIndexer).line)
        assertEquals("Set up again: pair again", by.getValue(TvState.SetUpAgain).line)
        assertEquals("TV RemovedHere removed this phone", by.getValue(TvState.RemovedHere).line)
        assertEquals("Update NZStreamer on the TV", by.getValue(TvState.UpdateTv).line)
        assertEquals("Update NZStreamer on this phone", by.getValue(TvState.UpdatePhone).line)
        // Not tried yet: no line until its link says.
        assertNull(rows.last().line)
        assertNull(rows.last().rowLine)
        assertEquals(listOf(keys.getValue(TvState.NotOpen)), rows.filter { it.chosen }.map { it.key })
        // Greyed: all but a ready TV and one not tried yet.
        assertEquals(listOf(false) + List(TvState.entries.size - 1) { true } + false, rows.map { it.greyed })
    }

    @Test
    fun `a tap chooses a ready TV or one not open, a TV that needs setting up can't be chosen, and the others ask`() {
        fun tap(state: TvState?) = PlayOnRow(living, "Living room TV", state, chosen = false).tap
        assertEquals(SheetTap.Choose(living), tap(TvState.Ready))
        assertEquals(SheetTap.Choose(living), tap(TvState.NotOpen))
        assertEquals(SheetTap.Choose(living), tap(null))
        assertEquals(SheetTap.Nothing, tap(TvState.NeedsProvider))
        assertEquals(SheetTap.Nothing, tap(TvState.NeedsIndexer))
        assertEquals(SheetTap.Nothing, tap(TvState.NeedsService))
        assertEquals(SheetTap.Nothing, tap(TvState.ServiceOff))
        assertEquals(SheetTap.Nothing, tap(TvState.NeedsTorrentIndexer))
        assertEquals(SheetTap.Ask(PlayOnAsk.SetUpAgain(living, "Living room TV")), tap(TvState.SetUpAgain))
        assertEquals(SheetTap.Ask(PlayOnAsk.Removed(living, "Living room TV")), tap(TvState.RemovedHere))
        assertEquals(SheetTap.Ask(PlayOnAsk.Update(tv = true, name = "Living room TV")), tap(TvState.UpdateTv))
        assertEquals(SheetTap.Ask(PlayOnAsk.Update(tv = false, name = "Living room TV")), tap(TvState.UpdatePhone))
    }

    @Test
    fun `the sheet opens and closes, and a question is asked once until answered`() {
        val on = PlayOn(scope, LinkStore(MemoryStore(LinkFile())).tvs, MutableStateFlow(emptyMap())) { AutoCloseable {} }
        assertNull(on.sheet.value)
        on.openSheet()
        assertEquals(SheetPlace.Title, on.sheet.value)
        on.closeSheet()
        assertNull(on.sheet.value)
        val ask = PlayOnAsk.Removed(living, "Living room TV")
        on.ask(ask)
        assertEquals(ask, on.asking.value)
        on.answered()
        assertNull(on.asking.value)
    }

    /**
     * L116, L148 (CsPhonePlaces): opened from Search, a release list, Import or the player, where Play goes to this phone or
     * a Cast device, every TV is greyed, can't be tapped, and says where it plays from, whatever its state; none is ticked.
     */
    @Test
    fun `from Search, a release list, Import or the player every TV row is greyed with where it plays from`() {
        val keys = TvState.entries.associateWith { KeyId.of(Random.nextBytes(91)) }
        val tvs = keys.map { (state, key) -> kept(key, "TV ${state.name}") } + kept(living, "Living room TV")
        val states = keys.map { (state, key) -> key to TvNow(state, state == TvState.Ready) }.toMap()
        for (place in listOf(SheetPlace.PhoneOnly, SheetPlace.Player)) {
            val rows = playOnRows(tvs, states, chosen = keys.getValue(TvState.Ready), place = place)
            assertEquals(tvs.map { it.name }, rows.map { it.name })
            for (row in rows) {
                assertTrue(row.greyed, "$place $row")
                assertFalse(row.chosen, "$place $row")
                assertEquals(SheetTap.Nothing, row.tap, "$place $row")
                assertEquals(RowLine("Plays from the title’s page", RowLine.Tone.Muted), row.rowLine, "$place $row")
            }
        }
        assertEquals("Plays from the title’s page", TITLE_PAGE_LINE)
        // On a title's page, as before: the chosen TV ticked, each by its state.
        val title = playOnRows(tvs, states, chosen = keys.getValue(TvState.Ready), place = SheetPlace.Title)
        assertEquals(playOnRows(tvs, states, chosen = keys.getValue(TvState.Ready)), title)
        assertEquals(listOf(keys.getValue(TvState.Ready)), title.filter { it.chosen }.map { it.key })
        assertEquals("Ready", title.first { it.state == TvState.Ready }.line)
    }

    /**
     * W59, L148: there, with a TV chosen, This phone is the ticked row, as Play plays here; a tap on it chooses This phone
     * everywhere and closes the list, as on any screen. A Cast device chosen is ticked in its own group from every place.
     */
    @Test
    fun `there This phone is ticked with a TV chosen, and a tap on it chooses This phone everywhere`() {
        val tv = PlayChoice.Tv(living)
        val kitchen = PlayChoice.Cast("route:kitchen", "Kitchen display")
        for (place in listOf(SheetPlace.PhoneOnly, SheetPlace.Player)) {
            assertTrue(thisPhoneTicked(tv, place), "$place")
            assertTrue(thisPhoneTicked(PlayChoice.Phone, place), "$place")
            assertFalse(thisPhoneTicked(kitchen, place), "$place")
        }
        assertFalse(thisPhoneTicked(tv, SheetPlace.Title))
        assertTrue(thisPhoneTicked(PlayChoice.Phone, SheetPlace.Title))
        assertFalse(thisPhoneTicked(kitchen, SheetPlace.Title))
        val on = PlayOn(scope, LinkStore(MemoryStore(LinkFile())).tvs, MutableStateFlow(emptyMap())) { AutoCloseable {} }
        on.choose(tv)
        on.openSheet(SheetPlace.PhoneOnly)
        on.tap(SheetTap.Choose(null))
        assertEquals(PlayChoice.Phone, on.chosen.value)
        assertEquals(PlayChoice.Phone, on.picked.value)
        assertNull(on.sheet.value)
    }

    @Test
    fun `a TV row's line is as before, Ready in green, not open muted, the rest a warning`() {
        fun line(state: TvState) = PlayOnRow(living, "Living room TV", state, chosen = false).rowLine
        assertEquals(RowLine("Ready", RowLine.Tone.Good), line(TvState.Ready))
        assertEquals(RowLine("Open NZStreamer on the TV", RowLine.Tone.Muted), line(TvState.NotOpen))
        for (state in TvState.entries - TvState.Ready - TvState.NotOpen) {
            assertEquals(RowLine(sheetStateLine(state, "Living room TV"), RowLine.Tone.Warning), line(state), "$state")
        }
    }

    /** L251: TalkBack reads a TV row's warning with its ⚠ as "Warning", the colon a pause; a ready or closed TV's row as shown. */
    @Test
    fun `TalkBack reads a TV row's warning as Warning`() {
        fun spoken(state: TvState?) = PlayOnRow(living, "Bedroom TV", state, chosen = false).spoken
        assertEquals("Bedroom TV, Warning: Needs a debrid service on the TV", spoken(TvState.NeedsService))
        assertEquals("Bedroom TV, Warning: The TV’s debrid service is off", spoken(TvState.ServiceOff))
        assertEquals("Bedroom TV, Warning: Needs a torrent indexer on the TV", spoken(TvState.NeedsTorrentIndexer))
        assertEquals("Bedroom TV, Warning: Needs a Usenet provider on the TV", spoken(TvState.NeedsProvider))
        // Read from their name and line as they show.
        for (state in listOf(TvState.Ready, TvState.NotOpen, null)) assertNull(spoken(state), "$state")
        assertNull(PlayOnRow(living, "Bedroom TV", TvState.NeedsService, chosen = false, SheetPlace.PhoneOnly).spoken, "where it plays from: a muted line")
    }

    private val kitchen = "route:kitchen"

    private fun session(link: CastLink, route: String = kitchen) = CastLinkNow(route, "Kitchen display", link)

    @Test
    fun `the button and Play with a Cast device, greyed while it connects and open once connected`() {
        val connecting = PlayTarget.Cast(kitchen, "Kitchen display", CastLink.Connecting)
        val connected = connecting.copy(link = CastLink.Connected)
        val reconnecting = connecting.copy(link = CastLink.Reconnecting)
        assertEquals("Play on: Kitchen display", playOnLabel(connected))
        assertEquals("Play on: Kitchen display, connecting", playOnLabel(connecting))
        assertEquals("Play on: Kitchen display, connecting", playOnLabel(reconnecting))
        assertTrue(connected.open)
        assertFalse(connected.greyed)
        for (waiting in listOf(connecting, reconnecting)) {
            assertFalse(waiting.open)
            assertTrue(waiting.greyed)
        }
        assertEquals("Kitchen display", connected.deviceName)
        assertEquals("Living room TV", PlayTarget.Tv(living, "Living room TV", TvState.Ready).deviceName)
        assertNull(PlayTarget.Phone.deviceName)
        assertEquals("Connecting to Kitchen display…", connectingTo("Kitchen display"))
    }

    @Test
    fun `the Cast group's words`() {
        // The Cast group is "Cast to" under the list's "Play on" (W47).
        assertEquals("Cast to", CAST_DEVICES)
        assertEquals("Simple files only: some releases play without sound or subtitles", CAST_SIMPLE)
        assertEquals("Looking for Cast devices…", CAST_LOOKING)
        assertEquals("No Cast devices found. They show here when on the same Wi-Fi as this phone.", CAST_NONE_FOUND)
        assertEquals("Cast isn't available on this phone", CAST_UNAVAILABLE)
        assertEquals("Google Play services is missing or out of date.", CAST_UNAVAILABLE_WHY)
        assertEquals("Connecting…", CAST_CONNECTING)
        assertEquals("Connected", CAST_CONNECTED)
        assertEquals("Couldn't connect. Tap to try again.", CAST_COULD_NOT_CONNECT)
        assertEquals("Stop casting", STOP_CASTING)
    }

    /**
     * L117 (CsPhoneSheetConnected): the connected row keeps its ✓ and its line; Stop casting has moved up into the block at
     * the list's top, so no row has it.
     */
    @Test
    fun `a Cast row by the session now, a spinner while it connects, then its tick and its line`() {
        assertEquals(
            CastRowLook(RowLine("Connecting…", RowLine.Tone.Muted), RowEnd.Spinner, chosen = true, live = false),
            castRowLook(kitchen, null, session(CastLink.Connecting), failed = false),
        )
        // Connected: "Connected" in green with the dot until something plays (L97), and the ✓.
        assertEquals(
            CastRowLook(RowLine("Connected", RowLine.Tone.Good), RowEnd.None, chosen = true, live = true),
            castRowLook(kitchen, null, session(CastLink.Connected), failed = false),
        )
        // A cast play living there: what plays, in place of "Connected" (L78; CsPhoneSheet's last frame).
        assertEquals(
            CastRowLook(RowLine("Playing Harbor Lights · S02E04", RowLine.Tone.Good), RowEnd.None, chosen = true, live = true),
            castRowLook(kitchen, null, session(CastLink.Connected), failed = false, playing = "Harbor Lights · S02E04"),
        )
        // Suspended: connecting again, still the choice.
        assertEquals(
            CastRowLook(RowLine("Connecting…", RowLine.Tone.Muted), RowEnd.None, chosen = true, live = true),
            castRowLook(kitchen, null, session(CastLink.Reconnecting), failed = false),
        )
        // Refused, or not connected in 15 s: a tap tries again.
        assertEquals(
            CastRowLook(RowLine("Couldn't connect. Tap to try again.", RowLine.Tone.Warning), RowEnd.None, chosen = false, live = false),
            castRowLook(kitchen, null, null, failed = true),
        )
        // Another device's session: this row is as it was, its own text under it.
        assertEquals(
            CastRowLook(RowLine("Casting Default Media Receiver", RowLine.Tone.Muted), RowEnd.None, chosen = false, live = false),
            castRowLook(kitchen, "Casting Default Media Receiver", session(CastLink.Connected, route = "route:den"), failed = false),
        )
    }

    @Test
    fun `This phone is unticked while a Cast device connects`() {
        val cast = MutableStateFlow<CastLinkNow?>(session(CastLink.Connecting))
        val on = PlayOn(scope, LinkStore(MemoryStore(LinkFile())).tvs, MutableStateFlow(emptyMap()), cast) { AutoCloseable {} }
        on.choose(PlayChoice.Cast(kitchen, "Kitchen display"))
        assertFalse(on.chosen.value.thisPhone)
        on.choose(PlayChoice.Phone)
        assertTrue(on.chosen.value.thisPhone)
    }

    @Test
    fun `TalkBack's words for the Cast rows`() {
        assertEquals("Kitchen display, Cast device", castRowDescription("Kitchen display", null))
        assertEquals(
            "Den TV, Cast device, Casting Default Media Receiver",
            castRowDescription("Den TV", RowLine("Casting Default Media Receiver", RowLine.Tone.Muted)),
        )
        assertEquals("Connecting to Kitchen display", connectingAnnouncement("Kitchen display"))
        assertEquals("Connected to Kitchen display", connectedAnnouncement("Kitchen display"))
        assertEquals("Stop casting to Kitchen display", stopCastingTo("Kitchen display"))
    }
}
