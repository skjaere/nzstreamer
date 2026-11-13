package io.skjaere.usenet.app.casting

import io.skjaere.usenet.app.link.PlayChoice
import io.skjaere.usenet.app.link.PlayTarget
import io.skjaere.usenet.app.link.TvState
import io.skjaere.usenet.app.play.CastTo
import io.skjaere.usenet.app.play.PlayOrigin
import io.skjaere.usenet.data.CastDeviceMemory
import io.skjaere.usenet.data.CastVideoCodec
import io.skjaere.usenet.link.KeyId
import io.skjaere.usenet.releases.RankingTarget
import io.skjaere.usenet.releases.VideoCodec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * L127, L153: whom releases are ranked for. A play's job ranks for its own device: its Cast device, or this phone for a
 * play here, whatever is chosen meanwhile. Outside a job, the Cast device chosen in Play on, connecting, connected or
 * reconnecting, by the profile its route's model and what it has shown give it (L128); This phone and a TV rank for this
 * device. A list on screen ranks again for a choice made, once Play goes there, and never as This phone comes back by
 * itself (W36). The devices are invented.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RankedForTest {
    private val kitchenFacts = CastDeviceFacts("device-kitchen", "Chromecast", "192.168.1.31", CastDeviceKind.Video, fixedVolume = false)
    private val den = CastTo("route:den", "Den TV", device = "device-den", model = "SHIELD Android TV")
    private val living = PlayTarget.Tv(KeyId.of(Random.nextBytes(91)), "Living room TV", TvState.Ready)

    /** Kitchen display has shown a 4K picture of H.264; nothing is known of the others. */
    private val shown = CastDeviceMemory("device-kitchen", reportsVideo = true, shown2160 = listOf(CastVideoCodec(VideoCodec.H264.name)))
    private val profiles = CastProfiles(listOf(shown))

    private fun kitchen(link: CastLink = CastLink.Connected, facts: CastDeviceFacts? = kitchenFacts) =
        PlayTarget.Cast("route:kitchen", "Kitchen display", link, facts)

    private val kitchenShown4k = CastRanking("Kitchen display", CastModel.Chromecast.profile(shown))

    @Test
    fun `a play's job ranks for its own Cast device, whatever is chosen`() {
        val ranking = rankingTargetOf(PlayOrigin(cast = den), kitchen(), profiles)
        assertEquals(CastRanking("Den TV", CastModel.Shield.profile(null)), ranking)
        assertEquals(RankingTarget.Cast("Den TV"), ranking?.target)
        assertEquals(2160, ranking?.profile?.displayHeight, "the Shield counts as 4K (W53)")
        // Its job's, whatever Play on says now: This phone, or a TV.
        assertEquals(ranking, rankingTargetOf(PlayOrigin(cast = den), PlayTarget.Phone, profiles))
        assertEquals(ranking, rankingTargetOf(PlayOrigin(cast = den), living, profiles))
    }

    @Test
    fun `a play's job on this phone ranks for this phone, a Cast device chosen or not`() {
        assertNull(rankingTargetOf(PlayOrigin.Here, kitchen(), profiles))
        assertNull(rankingTargetOf(PlayOrigin(start = 0), kitchen(CastLink.Connecting), profiles))
    }

    @Test
    fun `a Cast device chosen ranks for it, connecting, connected or reconnecting, by its model and what it has shown`() {
        for (link in CastLink.entries) assertEquals(kitchenShown4k, rankingTargetOf(null, kitchen(link), profiles), "$link")
        assertEquals(2160, kitchenShown4k.profile.displayHeight, "its 4K picture lifts its screen")
        // Before its session said what it is: as any other device, 1080p until it has shown 4K (W53).
        assertEquals(CastRanking("Kitchen display", CastModel.Other.profile(null)), rankingTargetOf(null, kitchen(facts = null), profiles))
        // Another phone's memory of nothing: the model's row alone.
        assertEquals(CastRanking("Kitchen display", CastModel.Chromecast.profile(null)), rankingTargetOf(null, kitchen(), CastProfiles(emptyList())))
    }

    @Test
    fun `a TV chosen ranks for this phone, and so does This phone`() {
        assertNull(rankingTargetOf(null, living, profiles))
        assertNull(rankingTargetOf(null, PlayTarget.Phone, profiles))
    }

    /** L127: a choice made on the list ranks again at once; the device its session says it is, once Play goes there. */
    @Test
    fun `a list on screen ranks again for a choice made, once Play goes there`() {
        val chosen = PlayChoice.Cast("route:kitchen", "Kitchen display")
        assertEquals(RankFor(kitchenShown4k), listRankingOf(chosen, kitchen(CastLink.Connecting), profiles))
        assertEquals(RankFor(kitchenShown4k), listRankingOf(chosen, kitchen(CastLink.Connected), profiles))
        // Play hasn't caught up with the choice yet: nothing until it has.
        assertNull(listRankingOf(chosen, PlayTarget.Phone, profiles))
        assertNull(listRankingOf(chosen, PlayTarget.Cast("route:den", "Den TV", CastLink.Connected), profiles))
        // This phone or a TV chosen: this device, at once.
        assertEquals(RankFor(null), listRankingOf(PlayChoice.Phone, kitchen(), profiles))
        assertEquals(RankFor(null), listRankingOf(PlayChoice.Phone, PlayTarget.Phone, profiles))
        assertEquals(RankFor(null), listRankingOf(PlayChoice.Tv(living.key), living, profiles))
    }

    /** L127, W36, risk 10: the session ended, failed or went to another device: the list keeps its order and its line. */
    @Test
    fun `This phone back by itself leaves a list on screen as it is`() {
        val chosen = PlayChoice.Cast("route:kitchen", "Kitchen display")
        assertNull(listRankingOf(chosen, PlayTarget.Phone, profiles))
        // The moment between the session's end and This phone chosen, the choice still the device's with no session.
        assertNull(listRankingOf(chosen, kitchen(CastLink.Connecting, facts = null), profiles))
    }

    /**
     * L127, risk 10 (CFU-5): what the chosen device shows while a list is on screen, and its session suspended a moment,
     * rank nothing again: they are no choice. The next choice made ranks by what it has shown by then.
     */
    @Test
    fun `what the device chosen shows while a list is on screen ranks it again only at the next choice`() = runTest {
        val kitchenChoice = PlayChoice.Cast("route:kitchen", "Kitchen display")
        val picked = MutableStateFlow<PlayChoice>(kitchenChoice)
        val target = MutableStateFlow<PlayTarget>(kitchen())
        val devices = MutableStateFlow(emptyList<CastDeviceMemory>())
        val cues = mutableListOf<RankFor>()
        backgroundScope.launch { listRankings(picked, target, devices).toList(cues) }
        runCurrent()
        val rowAlone = RankFor(CastRanking("Kitchen display", CastModel.Chromecast.profile(null)))
        assertEquals(listOf(rowAlone), cues)
        // Its first 4K picture, a second into a play started from the list.
        devices.value = listOf(shown)
        runCurrent()
        assertEquals(listOf(rowAlone), cues, "what the device showed ranked a list on screen again")
        // Its session suspended a moment, and back.
        target.value = kitchen(CastLink.Reconnecting)
        runCurrent()
        target.value = kitchen()
        runCurrent()
        assertEquals(listOf(rowAlone), cues, "a reconnect ranked a list on screen again")
        // This phone chosen, then Kitchen display again: by what it has shown by then.
        picked.value = PlayChoice.Phone
        target.value = PlayTarget.Phone
        runCurrent()
        picked.value = kitchenChoice
        target.value = kitchen()
        runCurrent()
        assertEquals(listOf(rowAlone, RankFor(null), RankFor(kitchenShown4k)), cues)
    }

    @Test
    fun `a ranking prints without its device's name`() {
        assertFalse("Kitchen display" in kitchenShown4k.toString())
        assertFalse("Kitchen display" in RankFor(kitchenShown4k).toString())
    }
}
