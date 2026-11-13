package io.skjaere.usenet.app.link

import androidx.compose.ui.graphics.vector.addPathNodes
import io.skjaere.usenet.app.casting.CastLink
import io.skjaere.usenet.link.KeyId
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The Cast button's faces (W43, W59, W66, L113, L146; CsPhoneButton): Google's Cast icon at rest on a phone with Google
 * Play services, or while that isn't known yet, and the TV icon without them; while a Cast device connects or reconnects
 * the chip's circle with the waves lighting in turn, about 1.3 s a loop, held on ③ with animations off; Google's filled
 * icon in the amber circle once connected; a chosen TV's own face on a title's page, and the This-phone face where Play
 * still plays here with a TV chosen (Search, the release lists, Import, the player). Nothing on a TV. TalkBack reads C1's
 * "Play on: …" words. The names are invented.
 */
class CastButtonFaceTest {
    private val living = KeyId.of(Random.nextBytes(91))
    private val kitchen = PlayTarget.Cast("route:kitchen", "Kitchen display", CastLink.Connected)
    private val phone = ButtonFace(ButtonFace.Look.Rest, "Play on: this phone")

    private fun tv(state: TvState?) = PlayTarget.Tv(living, "Living room TV", state)

    @Test
    fun `This phone shows Google's Cast icon with Play services there or not known yet, and the TV icon without them`() {
        assertEquals(phone, castButtonFace(PlayTarget.Phone, SheetPlace.Title, playServices = true, television = false))
        // Not read yet (it is read off the main thread as the app starts): Google's icon, as most phones have them.
        assertEquals(phone, castButtonFace(PlayTarget.Phone, SheetPlace.Title, playServices = null, television = false))
        assertEquals(
            ButtonFace(ButtonFace.Look.NoPlayServices, "Play on: this phone"),
            castButtonFace(PlayTarget.Phone, SheetPlace.Title, playServices = false, television = false),
        )
    }

    @Test
    fun `a Cast device connecting or reconnecting has the waves, connected the filled icon, from every place`() {
        for (place in SheetPlace.entries) {
            for (link in listOf(CastLink.Connecting, CastLink.Reconnecting)) {
                assertEquals(
                    ButtonFace(ButtonFace.Look.Connecting, "Play on: Kitchen display, connecting"),
                    castButtonFace(kitchen.copy(link = link), place, playServices = true, television = false), "$place $link",
                )
            }
            assertEquals(ButtonFace(ButtonFace.Look.Connected, "Play on: Kitchen display"), castButtonFace(kitchen, place, playServices = true, television = false), "$place")
        }
    }

    @Test
    fun `a TV chosen shows the TV on a title's page, amber while it answers, grey with the dot while it isn't open`() {
        for (state in listOf(TvState.Ready, TvState.NeedsProvider, TvState.NeedsIndexer, TvState.NeedsService, TvState.ServiceOff, TvState.NeedsTorrentIndexer)) {
            assertEquals(ButtonFace(ButtonFace.Look.Tv, "Play on: Living room TV"), castButtonFace(tv(state), SheetPlace.Title, playServices = true, television = false), "$state")
        }
        for (state in listOf(null, TvState.NotOpen, TvState.SetUpAgain, TvState.RemovedHere, TvState.UpdateTv, TvState.UpdatePhone)) {
            assertEquals(
                ButtonFace(ButtonFace.Look.TvNotOpen, "Play on: Living room TV, not open"),
                castButtonFace(tv(state), SheetPlace.Title, playServices = true, television = false), "$state",
            )
        }
    }

    @Test
    fun `where Play plays here with a TV chosen, the button says this phone (W59)`() {
        for (place in listOf(SheetPlace.PhoneOnly, SheetPlace.Player)) {
            for (state in TvState.entries + listOf(null)) {
                assertEquals(phone, castButtonFace(tv(state), place, playServices = true, television = false), "$place $state")
                assertEquals(
                    ButtonFace(ButtonFace.Look.NoPlayServices, "Play on: this phone"),
                    castButtonFace(tv(state), place, playServices = false, television = false), "$place $state",
                )
            }
        }
    }

    @Test
    fun `never on a TV`() {
        for (place in SheetPlace.entries) {
            for (target in listOf(PlayTarget.Phone, tv(TvState.Ready), kitchen, kitchen.copy(link = CastLink.Connecting))) {
                for (services in listOf(true, false, null)) assertNull(castButtonFace(target, place, services, television = true), "$place $target $services")
            }
        }
    }

    @Test
    fun `the design's sizes, in a top bar and round on Home`() {
        // A top bar: a 48 dp target, the 24 dp icon at rest, a 40 dp circle with a 22 dp icon once something is chosen.
        for (look in listOf(ButtonFace.Look.Rest, ButtonFace.Look.NoPlayServices)) assertEquals(ButtonSizes(48, null, 24), castButtonSizes(look, round = false), "$look")
        for (look in listOf(ButtonFace.Look.Connecting, ButtonFace.Look.Connected, ButtonFace.Look.Tv, ButtonFace.Look.TvNotOpen)) {
            assertEquals(ButtonSizes(48, 40, 22), castButtonSizes(look, round = false), "$look")
        }
        // Home's search bar: round as Settings beside it, a 40 dp circle with a 20 dp icon whatever it shows.
        for (look in ButtonFace.Look.entries) assertEquals(ButtonSizes(40, 40, 20), castButtonSizes(look, round = true), "$look")
    }

    @Test
    fun `the waves light from the corner out, about 1·3 s a loop, and stay on ③ with animations off`() {
        assertEquals(1_300, CastButtonMotion.PERIOD_MS)
        // The design's loop: a quarter with none lit, then the corner wave (①), then the middle one
        // too (②), then all three (③), and again.
        assertEquals(listOf(0, 0, 1, 1, 2, 2, 3, 3), (0 until 8).map { CastButtonMotion.waves(moves = true, fraction = it / 8f) })
        assertEquals(3, CastButtonMotion.waves(moves = true, fraction = 0.999f))
        assertEquals(0, CastButtonMotion.waves(moves = true, fraction = 1f % 1f))
        // "Remove animations" on: ③, whatever the time.
        for (i in 0..8) assertEquals(3, CastButtonMotion.waves(moves = false, fraction = i / 8f))
        assertEquals(3, CastButtonMotion.STILL)
    }

    @Test
    fun `the icon's parts are Google's, the three waves from the corner out and the frame make its Cast icon`() {
        assertEquals(3, CAST_WAVES.size)
        // CAST is Material's cast with the frame last: the corner wave, the middle one, the outer one, then the frame.
        assertEquals(addPathNodes(CAST), (CAST_WAVES + CAST_FRAME).flatMap(::addPathNodes))
        // Each wave reaches further from the corner (1, 21) than the one before: 3, 7 and 11 units.
        assertEquals(listOf(3f, 7f, 11f), CAST_WAVES.map { wave -> reach(wave) })
    }

    /** How far a wave's outline goes from the icon's bottom-left corner, along its bottom edge. */
    private fun reach(path: String): Float = Regex("L(\\d+(?:\\.\\d+)?),21").findAll(path).map { it.groupValues[1].toFloat() - 1f }.max()
}
