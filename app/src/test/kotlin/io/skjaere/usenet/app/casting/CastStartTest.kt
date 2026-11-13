package io.skjaere.usenet.app.casting

import io.skjaere.usenet.app.link.PlayTarget
import io.skjaere.usenet.app.play.CastTo
import io.skjaere.usenet.app.play.Left
import io.skjaere.usenet.app.play.PlaySessions
import io.skjaere.usenet.app.play.PlayOrigin
import io.skjaere.usenet.app.play.PlayOutcome
import io.skjaere.usenet.app.play.PlayRequest
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.SavedRelease
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.app.play.playCandidate
import io.skjaere.usenet.app.play.retryNotice
import io.skjaere.usenet.core.AttemptFailure
import io.skjaere.usenet.core.FilePicker
import io.skjaere.usenet.core.PipelineStep
import io.skjaere.usenet.core.StepStatus
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.releases.RankedRelease
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.search.RankedItem
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A cast play's start (L65, L83, L84; CsPhoneStarting), as the coordinator wires it: a launch for a Cast device goes to
 * the device, never to this phone's player; the sheet stays on "Starting on Kitchen display" until the device plays,
 * then closes; each failure has its words and buttons, and each button asks the coordinator for its own thing. The show,
 * device and releases are invented.
 */
class CastStartTest {
    private val kitchen = CastTo("route-kitchen", "Kitchen display")
    private val release = SavedRelease("fake", "hl-s02e04", RELEASE, null, 0, "nzb-hl", "Harbor.Lights.S02E04.mkv", 1_400_000_000)
    private val here = PlayerLaunch(
        UsenetUris.build("nzb-hl", 0, release.fileName, release.fileSize), "Harbor Lights", "S02E04 · Low Water", WatchKey.episode(900002, 2, 4),
        WatchTarget(WatchKind.Episode, "Harbor Lights S02E04", showTmdbId = 900002, season = 2, episode = 4), release, requestId = "play-1",
        startPositionMs = 1_930_000,
    )
    private val cast = here.copy(cast = kitchen)

    private val steps = mapOf(
        PipelineStep.FetchNzb to StepStatus.Done(500, "1.4 MB"),
        PipelineStep.CheckAvailability to StepStatus.Done(1_600),
        PipelineStep.Open to StepStatus.Done(800),
        PipelineStep.ProbeTracks to StepStatus.Done(1_900),
    )
    private val running = PlayState.Running(
        "Harbor Lights", "S02E04 · Low Water", "Searched 2 indexers by TVDB id · 6 found", 1, 3, RELEASE, listOf("1080p", "WEB-DL"), steps, notice = null,
    )

    private fun failed(failure: CastLoadFailure, canTryAnother: Boolean = true) =
        assertIs<PlayState.Failed>(CastStart.after(CastLoadOutcome.Failed(failure), cast, CastStart.loading(running), 30_000, canTryAnother))

    private fun PlayState.Failed.castFailed(): CastFailed = cast ?: throw AssertionError("not a cast's failure: $this")

    /**
     * L60, W36: a failure's sheet offers the device more only while it is the one chosen, connected. The session ended while
     * the sheet showed (the receiver's five minutes, another sender, another choice): Try again and Try another release go,
     * and only this phone and Close are left.
     */
    @Test
    fun `a device no longer chosen and connected is offered nothing more`() {
        val connected = PlayTarget.Cast(kitchen.route, kitchen.name, CastLink.Connected)
        assertTrue(CastStart.live(kitchen, connected))
        listOf(PlayTarget.Phone, connected.copy(link = CastLink.Reconnecting), connected.copy(link = CastLink.Connecting), PlayTarget.Cast("route-den", "Den TV", CastLink.Connected))
            .forEach { assertFalse(CastStart.live(kitchen, it), "$it") }
        val reach = failed(CastLoadFailure(CastFailKind.CannotReach)).castFailed()
        val play = failed(CastLoadFailure(CastFailKind.CouldNotPlay, DECODE, format = true)).castFailed()
        assertEquals(listOf(CastButton.TryAgain, CastButton.PlayHere, CastButton.Close), CastStart.buttons(reach, live = true))
        assertEquals(listOf(CastButton.PlayHere, CastButton.Close), CastStart.buttons(reach, live = false))
        assertEquals(listOf(CastButton.TryAnother, CastButton.PlayHere, CastButton.Close), CastStart.buttons(play, live = true))
        assertEquals(listOf(CastButton.PlayHere, CastButton.Close), CastStart.buttons(play, live = false))
    }

    /**
     * L124 steps 4-6 (W45): Play & connect's move loads the player's own launch on the device it moved to, from where the
     * film paused: the same release, file and request, so nothing is searched, and the coordinator keeps it as a cast
     * play's (its session, its next plays). It goes to the device, never to this phone's player. Whatever comes of it, the
     * sheet goes back to Idle, never a failure's sheet, as the player plays on here and says why; the player hears it.
     */
    @Test
    fun `a move loads the player's launch there from where it paused, and its end leaves the sheet Idle`() {
        val moving = CastStart.moving(here, 1_930_000, kitchen)
        assertEquals(here.copy(cast = kitchen, startPositionMs = 1_930_000), moving)
        assertEquals(CastStart.Handover.Device(kitchen), CastStart.handover(moving, wentBack = false), "to the device, never the player")
        assertEquals(PlayOrigin(cast = kitchen), PlayOrigin.after(moving), "the job's origin: the device's")
        assertEquals(CastStart.MoveEnd(PlayState.Idle, MoveOutcome.Moved), CastStart.moveEnd(CastLoadOutcome.Playing))
        val refused = CastLoadFailure(CastFailKind.FileType, refusedAsItOpened(CastFile("mov", video = true), kitchen.name))
        val failed = CastStart.moveEnd(CastLoadOutcome.Failed(refused))
        assertEquals(PlayState.Idle, failed.sheet, "never a failure's sheet")
        assertEquals(MoveOutcome.Failed(refused), failed.outcome)
        // A cast play's own failure, by contrast, shows its sheet.
        assertIs<PlayState.Failed>(CastStart.after(CastLoadOutcome.Failed(refused), moving, CastStart.loading(running), 1_000, canTryAnother = true))
    }

    @Test
    fun `a launch for a Cast device goes to the device, never to this phone's player`() {
        assertEquals(CastStart.Handover.Device(kitchen), CastStart.handover(cast, wentBack = false))
        assertEquals(CastStart.Handover.Player, CastStart.handover(here, wentBack = false))
        assertEquals(CastStart.Handover.Player, CastStart.handover(here, wentBack = true))
    }

    /** The missing-parts Try another went back to the release left for it: that one still plays there, so nothing loads (L84). */
    @Test
    fun `back to the release that still plays on the device, nothing is loaded`() {
        assertEquals(CastStart.Handover.Stays, CastStart.handover(cast, wentBack = true))
    }

    /**
     * L84, L94 (CsPhoneMissingParts 3): Try another release from the missing-parts prompt during a cast keeps the device:
     * the release playing there goes on until the next one loads, which the sheet says, with where it starts; the next
     * release loads there; if none plays, the play goes back to the one still playing, and nothing loads. A play here says
     * nothing of it: its player closes for the next.
     */
    @Test
    fun `try another for missing parts keeps the cast until the new load, and with none playing nothing loads`() {
        assertEquals("The release playing goes on there until this one is ready, at 33:35.", CastStart.goesOn(cast, 2_015_000))
        assertNull(CastStart.goesOn(here, 2_015_000), "a play here")
        assertEquals(kitchen, PlayOrigin.after(cast).cast, "on the same device")
        val next = cast.copy(release = release.copy(guid = "hl-s02e04-720p", title = "Harbor.Lights.S02E04.720p.WEB-DL.AAC2.0.H.264-GRP"))
        assertEquals(CastStart.Handover.Device(kitchen), CastStart.handover(next, wentBack = false), "the next release loads there")
        assertEquals(CastStart.Handover.Stays, CastStart.handover(cast, wentBack = true), "none played: the one there goes on")
    }

    /**
     * L135 (CsPhoneSlowCast 3; C-3): Try a smaller release from the slow-stream banner during a cast keeps the device, as Try
     * another release for missing parts does: the release playing goes on there until the smaller one loads, which the
     * sheet says, with where it starts. A play here says nothing of it; nor does one after a release that failed, which
     * plays no more.
     */
    @Test
    fun `try a smaller release keeps the cast until the new load, and its sheet says so`() {
        val remux = item("Harbor.Lights.S02E04.Low.Water.1080p.BluRay.REMUX.AVC.DTS-HD.MA.5.1-GRP").let { it.copy(ranked = it.ranked.copy(estimatedBitrate = 31_000_000)) }
        val web = item("Harbor.Lights.S02E04.Low.Water.1080p.WEB-DL.DDP5.1.H.264-GRP").let { it.copy(ranked = it.ranked.copy(estimatedBitrate = 7_900_000)) }
        val sessions = PlaySessions(newId = { "play-1" })
        sessions.start(episode.copy(candidates = listOf(remux, web)), emptySet())
        val playing = cast.copy(requestId = "play-1", release = release.copy(guid = remux.item.guid))
        val smaller = sessions.smaller(playing, 2_015_000, measuredBps = 19_000_000, playingBps = 31_000_000)
        assertEquals(kitchen, smaller.origin?.cast, "on the same device")
        assertEquals("The release playing goes on there until this one is ready, at 33:35.", CastStart.goesOn(playing, smaller))
        val here = playing.copy(cast = null)
        assertNull(CastStart.goesOn(here, sessions.smaller(here, 2_015_000, measuredBps = 19_000_000, playingBps = 31_000_000)), "a play here")
        // Try another release from the missing-parts prompt, as before (L84, L94).
        val missing = sessions.retry(playing, 2_015_000, Left(playing.release, playing.watchKey, emptyList()))
        assertEquals("The release playing goes on there until this one is ready, at 33:35.", CastStart.goesOn(playing, missing))
        assertNull(CastStart.goesOn(playing, sessions.retry(playing, 2_015_000)), "a release that failed")
    }

    @Test
    fun `the sheet says it is starting on the device until it plays, then closes`() {
        val loading = CastStart.loading(running)
        assertEquals(StepStatus.Running(), loading.castStep)
        assertEquals(running.steps, loading.steps)
        assertEquals("Starting on Kitchen display", startingOn(kitchen.name))
        assertEquals("Casting to Kitchen display", castingTo(kitchen.name))
        assertEquals(PlayState.Idle, CastStart.after(CastLoadOutcome.Playing, cast, loading, 4_000, canTryAnother = true))
        assertEquals(PlayOutcome.Launched, CastStart.outcome(PlayState.Idle))
    }

    @Test
    fun `the device couldn't play it, its reason over the release, then another release, this phone, or close`() {
        val failed = failed(CastLoadFailure(CastFailKind.CouldNotPlay, DECODE, format = true))
        assertEquals("Harbor Lights" to "S02E04 · Low Water", failed.title to failed.subtitle)
        assertEquals(listOf(RELEASE to DECODE), failed.failures)
        val c = failed.castFailed()
        assertEquals(CastFailKind.CouldNotPlay, c.kind)
        assertEquals(kitchen, c.device)
        assertEquals(cast, c.launch)
        assertEquals(
            CastFailedWords(headline = "Kitchen display couldn't play this release", cards = true, note = CAST_FORMAT_NOTE),
            CastStart.words(c),
        )
        assertEquals(
            "This phone plays it, but Cast devices play fewer kinds of video. A 1080p H.264 release plays on most.",
            CAST_FORMAT_NOTE,
        )
        assertEquals(listOf(CastButton.TryAnother, CastButton.PlayHere, CastButton.Close), CastStart.buttons(c))
        assertEquals(listOf("Try another release", "Play on this phone", "Close"), CastStart.buttons(c).map { it.words })
        // Not a decoding or format error: no word of what Cast devices play.
        assertNull(CastStart.words(failed(CastLoadFailure(CastFailKind.CouldNotPlay, LOST)).castFailed()).note)
    }

    /** W39: another release won't help a device that can't reach the phone, so Try again comes first. */
    @Test
    fun `the device can't reach the phone, what to check, the steps ending in no request, then try again`() {
        val failed = failed(CastLoadFailure(CastFailKind.CannotReach))
        val c = failed.castFailed()
        assertEquals(
            CastFailedWords(
                where = "Kitchen display can't reach this phone",
                advice = "Is this phone on the same Wi-Fi as Kitchen display? A VPN on the phone, or a guest network, can block it.",
                steps = true,
            ),
            CastStart.words(c),
        )
        val shown = c.running ?: throw AssertionError("no steps")
        assertEquals(steps, shown.steps)
        assertEquals(running.searchLine, shown.searchLine)
        val last = assertIs<StepStatus.Failed>(shown.castStep)
        assertEquals("no request came", last.failure.text)
        assertEquals(listOf(CastButton.TryAgain, CastButton.PlayHere, CastButton.Close), CastStart.buttons(c))
        // The release list hears it in the sheet's words.
        assertEquals("Kitchen display can't reach this phone", assertIs<PlayOutcome.Failed>(CastStart.outcome(failed)).reason(RELEASE))
    }

    @Test
    fun `a release list hears why the device didn't play its release`() {
        val failed = failed(CastLoadFailure(CastFailKind.CouldNotPlay, DECODE, format = true))
        val outcome = assertIs<PlayOutcome.Failed>(CastStart.outcome(failed))
        assertEquals(DECODE, outcome.reason(RELEASE))
    }

    @Test
    fun `try another release keeps the device, and forgets the release only where the providers failed it`() {
        val device = failed(CastLoadFailure(CastFailKind.CouldNotPlay, DECODE, format = true)).castFailed()
        val again = assertIs<CastAction.TryAnother>(CastStart.press(CastButton.TryAnother, device))
        assertEquals(cast, again.launch)
        assertEquals(kitchen, PlayOrigin.after(again.launch).cast)
        assertEquals(1_930_000L, again.positionMs, "from where it was to start")
        assertEquals(false, again.forget, "this phone plays it: still the release Resume plays")
        val providers = failed(CastLoadFailure(CastFailKind.CouldNotPlay, "Couldn't read the release from your providers", providers = true)).castFailed()
        assertEquals(true, assertIs<CastAction.TryAnother>(CastStart.press(CastButton.TryAnother, providers)).forget)
    }

    @Test
    fun `without another release to try, only this phone and close`() {
        val c = failed(CastLoadFailure(CastFailKind.CouldNotPlay, DECODE), canTryAnother = false).castFailed()
        assertEquals(CastFailed.Another.None, c.another)
        assertEquals(listOf(CastButton.PlayHere, CastButton.Close), CastStart.buttons(c))
    }

    @Test
    fun `play on this phone ends the cast and plays the launch here, without the device`() {
        val c = failed(CastLoadFailure(CastFailKind.CouldNotPlay, DECODE)).castFailed()
        val action = assertIs<CastAction.PlayHere>(CastStart.press(CastButton.PlayHere, c))
        assertEquals(here, action.launch)
        assertNull(action.launch?.cast)
    }

    @Test
    fun `try again loads the same launch on the same device`() {
        val c = failed(CastLoadFailure(CastFailKind.CannotReach)).castFailed()
        assertEquals(CastAction.CastAgain(cast), CastStart.press(CastButton.TryAgain, c))
        assertEquals(CastAction.Close, CastStart.press(CastButton.Close, c))
    }

    /** L60: the session ended before the device played: it is no longer chosen (W36), so only this phone, or close. */
    @Test
    fun `once the session has ended with the failure, only this phone and close`() {
        listOf(CastLoadFailure(CastFailKind.CannotReach, sessionEnded = true), CastLoadFailure(CastFailKind.CouldNotPlay, "Kitchen display closed before it played", sessionEnded = true))
            .forEach { failure ->
                assertEquals(listOf(CastButton.PlayHere, CastButton.Close), CastStart.buttons(failed(failure).castFailed()), "$failure")
            }
    }

    private val avi = "Harbor.Lights.S02E04.Low.Water.DVDRip.XviD-GRP"
    private val aviFailure = AttemptFailure.NotForCast("avi")

    private fun exhausted(vararg failures: Pair<String, AttemptFailure>, missing: List<Pair<String, String>> = emptyList()) =
        PlayState.Failed("Harbor Lights", "S02E04 · Low Water", failures.map { (r, f) -> r to f.text }, notTried = 0, missing = missing)

    @Test
    fun `a file type refused before anything was sent says so, and a hand-picked one goes back to its list`() {
        val failed = CastStart.refused(exhausted(avi to aviFailure), listOf(aviFailure), kitchen, chosen = true) ?: throw AssertionError("not a file type's")
        assertEquals(listOf(avi to "AVI file. Cast devices play MKV, MP4, WebM and MOV."), failed.failures)
        val c = failed.castFailed()
        assertEquals(CastFailKind.FileType, c.kind)
        assertNull(c.launch)
        assertEquals(
            CastFailedWords(headline = "This file type can't play on Kitchen display", cards = true, note = "Checked before anything was sent to Kitchen display."),
            CastStart.words(c),
        )
        assertEquals(listOf(CastButton.TryAnother, CastButton.PlayHere, CastButton.Close), CastStart.buttons(c))
        assertEquals(CastAction.BackToList, CastStart.press(CastButton.TryAnother, c))
        // Nothing was made for the device: the play is asked for again, here.
        assertEquals(CastAction.PlayHere(null), CastStart.press(CastButton.PlayHere, c))
        assertEquals("AVI file. Cast devices play MKV, MP4, WebM and MOV.", assertIs<PlayOutcome.Failed>(CastStart.outcome(failed)).reason(avi))
    }

    @Test
    fun `an automatic pick that found every file refused has no other release to offer`() {
        val ts = "Harbor.Lights.S02E04.1080i.HDTV.MPEG2-GRP"
        val failed = CastStart.refused(exhausted(avi to aviFailure, ts to AttemptFailure.NotForCast("ts")), listOf(aviFailure, AttemptFailure.NotForCast("ts")), kitchen, chosen = false)
            ?: throw AssertionError("not a file type's")
        val c = failed.castFailed()
        assertEquals(CastFailed.Another.None, c.another)
        assertEquals(listOf(CastButton.PlayHere, CastButton.Close), CastStart.buttons(c))
    }

    @Test
    fun `a play that failed for anything else besides shows as a play here's`() {
        val incomplete = AttemptFailure.MissingArticles("430")
        assertNull(CastStart.refused(exhausted(avi to aviFailure, RELEASE to incomplete), listOf(aviFailure, incomplete), kitchen, chosen = false))
        assertNull(CastStart.refused(exhausted(RELEASE to incomplete), listOf(incomplete), kitchen, chosen = true))
        val skipped = listOf(RELEASE to "Skipped: missing on your providers (checked 3 days ago)")
        assertNull(CastStart.refused(exhausted(avi to aviFailure, missing = skipped), listOf(aviFailure), kitchen, chosen = false))
    }

    /** A release as a search found it, ranked for Kitchen display: its title is what the sheet and its log name. */
    private fun item(title: String) = RankedItem(
        NewznabItem(
            indexerId = "fake", guid = "g-" + title.lowercase(), title = title, sizeBytes = 9_000_000_000, publishedAt = null, postedAt = null,
            categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null,
            tvmazeId = null, season = null, episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
        ),
        RankedRelease(ReleaseFacts("fake/g-$title", title, 9_000_000_000), ReleaseParser.parse(title), playable = true, score = 1.0, reasons = emptyList(), estimatedBitrate = null),
    )

    private val dtsRemux = item("Harbor.Lights.S02E04.Low.Water.1080p.BluRay.REMUX.AVC.DTS-HD.MA.5.1-GRP")
    private val vc1Remux = item("Harbor.Lights.S02E04.Low.Water.1080p.BluRay.REMUX.VC-1.DTS-HD.MA.5.1-GRP")
    private val uhd = item("Harbor.Lights.S02E04.Low.Water.2160p.WEB-DL.DDP5.1.H.264-GRP")
    private val xvid = item(avi)
    private val episode = PlayRequest(
        "Harbor Lights", "S02E04 · Low Water", listOf(dtsRemux, vc1Remux, uhd, xvid), FilePicker.Episode(2, 4),
        WatchTarget(WatchKind.Episode, "Harbor Lights S02E04", showTmdbId = 900002, season = 2, episode = 4), searchLine = "Searched 2 indexers by TVDB id · 6 found",
    )
    private val tried = episode.candidates.associateBy { it.key }

    private fun attempts(vararg failures: Pair<RankedItem, AttemptFailure>) = failures.map { (item, failure) -> item.playCandidate to failure }

    private fun notFully(vararg failures: Pair<RankedItem, AttemptFailure>, missing: List<Pair<String, String>> = emptyList()) = CastStart.notFully(
        exhausted(*failures.map { (item, failure) -> item.item.title to failure }.toTypedArray(), missing = missing), attempts(*failures), kitchen, episode, tried,
    )

    /**
     * L130, L149: an automatic pick whose every release its device can't fully play, found before anything was sent to it,
     * says so release by release, and offers this phone, and Cast anyway with the list's best release, as nothing may be
     * blocked from a Cast device (Terms 3.4.5).
     */
    @Test
    fun `every release its device can't fully play says so, with this phone and Cast anyway`() {
        val failed = notFully(dtsRemux to AttemptFailure.NoCastAudio(listOf("DTS-HD")), vc1Remux to AttemptFailure.NoCastVideo("VC-1"), uhd to AttemptFailure.TooBigForCast(2160, 1080))
            ?: throw AssertionError("not the verdict's")
        assertEquals(
            listOf(dtsRemux.item.title to "No sound for Cast devices (DTS-HD)", vc1Remux.item.title to "Video Cast devices can't show (VC-1)", uhd.item.title to "2160p over the device's 1080p"),
            failed.failures,
        )
        val c = failed.castFailed()
        assertEquals(CastFailKind.NotFully, c.kind)
        assertNull(c.launch)
        assertEquals(
            CastFailedWords(headline = "None of these releases plays fully on Kitchen display", cards = true, note = "Checked before anything was sent to Kitchen display."),
            CastStart.words(c),
        )
        assertEquals(listOf(CastButton.PlayHere, CastButton.CastAnyway, CastButton.Close), CastStart.buttons(c))
        // Cast anyway: the best release, alone, on the device, whatever it can't play there.
        val anyway = episode.copy(candidates = listOf(dtsRemux), chosen = true, rememberedFirst = false, castAnyway = true)
        assertEquals(CastAction.CastAnyway(anyway, kitchen), CastStart.press(CastButton.CastAnyway, c))
        // Nothing was made for the device: the play is asked for again, here.
        assertEquals(CastAction.PlayHere(null), CastStart.press(CastButton.PlayHere, c))
        assertEquals(CastAction.Close, CastStart.press(CastButton.Close, c))
        // The device no longer chosen and connected: only this phone.
        assertEquals(listOf(CastButton.PlayHere, CastButton.Close), CastStart.buttons(c, live = false))
        assertEquals("No sound for Cast devices (DTS-HD)", assertIs<PlayOutcome.Failed>(CastStart.outcome(failed)).reason(dtsRemux.item.title))
    }

    /**
     * CFU-1 on the all-failed sheet: after the pre-check's Play a compatible release, Play on this phone asks again for the
     * play asked for, the release picked by hand, here. Where this phone's own probe refused that file (DTS with no DTS
     * decoder here), it would end the cast for a play that fails at once, so it isn't offered: Cast anyway and Close are
     * left, and Close alone once the device isn't live. Where it plays the file, all three, as for any play.
     */
    @Test
    fun `after a compatible release none of which plays fully, this phone is offered only where it plays the picked file`() {
        val failures = arrayOf(dtsRemux.item.title to AttemptFailure.NoCastAudio(listOf("DTS")))
        val notHere = CastStart.notFully(exhausted(*failures), attempts(dtsRemux to AttemptFailure.NoCastAudio(listOf("DTS"))), kitchen, episode, tried, here = false)
            ?.castFailed() ?: throw AssertionError("not the verdict's")
        assertEquals(listOf(CastButton.CastAnyway, CastButton.Close), CastStart.buttons(notHere))
        assertEquals(listOf(CastButton.Close), CastStart.buttons(notHere, live = false))
        val here = CastStart.notFully(exhausted(*failures), attempts(dtsRemux to AttemptFailure.NoCastAudio(listOf("DTS"))), kitchen, episode, tried, here = true)
            ?.castFailed() ?: throw AssertionError("not the verdict's")
        assertEquals(listOf(CastButton.PlayHere, CastButton.CastAnyway, CastButton.Close), CastStart.buttons(here))
        assertEquals(listOf(CastButton.PlayHere, CastButton.Close), CastStart.buttons(here, live = false))
    }

    /**
     * And on the file type's sheet: every one of the compatible release's others refused for its type, Play on this phone
     * would ask for the picked file again here, so it is offered only where this phone plays that file; Close stays.
     */
    @Test
    fun `after a compatible release refused for its file type, this phone is offered only where it plays the picked file`() {
        val ts = "Harbor.Lights.S02E04.1080i.HDTV.MPEG2-GRP"
        val failure = AttemptFailure.NotForCast("ts")
        val notHere = CastStart.refused(exhausted(ts to failure), listOf(failure), kitchen, chosen = false, here = false)?.castFailed() ?: throw AssertionError("not a file type's")
        assertEquals(listOf(CastButton.Close), CastStart.buttons(notHere))
        assertEquals(listOf(CastButton.Close), CastStart.buttons(notHere, live = false))
        val here = CastStart.refused(exhausted(ts to failure), listOf(failure), kitchen, chosen = false, here = true)?.castFailed() ?: throw AssertionError("not a file type's")
        assertEquals(listOf(CastButton.PlayHere, CastButton.Close), CastStart.buttons(here))
        assertEquals(listOf(CastButton.PlayHere, CastButton.Close), CastStart.buttons(here, live = false))
    }

    /** One release is said as one; a type the device can't open is never cast anyway, so the best of the others is. */
    @Test
    fun `one release is said as one, and a file type refused beside the others isn't cast anyway`() {
        val one = notFully(dtsRemux to AttemptFailure.NoCastAudio(listOf("DTS-HD")))?.castFailed() ?: throw AssertionError("not the verdict's")
        assertEquals("This release doesn't play fully on Kitchen display", CastStart.words(one).headline)
        val mixed = notFully(xvid to AttemptFailure.NotForCast("avi"), vc1Remux to AttemptFailure.NoCastVideo("VC-1"))?.castFailed() ?: throw AssertionError("not the verdict's")
        assertEquals("None of these releases plays fully on Kitchen display", CastStart.words(mixed).headline)
        assertEquals(
            CastAction.CastAnyway(episode.copy(candidates = listOf(vc1Remux), chosen = true, rememberedFirst = false, castAnyway = true), kitchen),
            CastStart.press(CastButton.CastAnyway, mixed),
        )
    }

    @Test
    fun `a play that failed for anything else too isn't the verdict's`() {
        // Every one of a type the device can't open: the file type's own sheet.
        assertNull(notFully(xvid to AttemptFailure.NotForCast("avi")))
        // Another failure beside them, or releases skipped as missing before, which the play's own sheet lists.
        assertNull(notFully(dtsRemux to AttemptFailure.NoCastAudio(listOf("DTS-HD")), vc1Remux to AttemptFailure.MissingArticles("430")))
        assertNull(notFully(dtsRemux to AttemptFailure.NoCastAudio(listOf("DTS-HD")), missing = listOf(uhd.item.title to "Skipped: missing on your providers (checked 3 days ago)")))
        assertNull(notFully())
    }

    /** L149: an automatic pick's notice as it moves on names the device and what it can't play there. */
    @Test
    fun `an automatic pick's notice names the device and what it can't play`() {
        assertEquals("No sound on Kitchen display · trying release 2 of 3", retryNotice(AttemptFailure.NoCastAudio(listOf("DTS-HD")), 2, 3, kitchen))
        assertEquals("No picture on Kitchen display · trying release 2 of 3", retryNotice(AttemptFailure.NoCastVideo("VC-1"), 2, 3, kitchen))
        assertEquals("Kitchen display shows HD · trying release 2 of 3", retryNotice(AttemptFailure.TooBigForCast(2160, 1080), 2, 3, kitchen))
        assertEquals("Den TV shows 4K · trying release 3 of 3", retryNotice(AttemptFailure.TooBigForCast(4320, 2160), 3, 3, CastTo("route-den", "Den TV")))
        // The rest as before.
        assertEquals("AVI file can't be cast · trying release 2 of 3", retryNotice(aviFailure, 2, 3, kitchen))
        assertEquals("Release incomplete · trying release 2 of 3", retryNotice(AttemptFailure.MissingArticles("430"), 2, 3, null))
        assertEquals("That release failed · trying release 2 of 3", retryNotice(null, 2, 3, kitchen))
    }

    /** L102: a castable file the device refused as it opened: Try another release and this phone as for one it couldn't play. */
    @Test
    fun `a file the device refused as it opened offers another release and this phone as one it couldn't play`() {
        val mov = "MOV file. Kitchen display refused it as it opened."
        val failed = failed(CastLoadFailure(CastFailKind.FileType, mov))
        assertEquals(listOf(RELEASE to mov), failed.failures)
        val c = failed.castFailed()
        assertEquals(CastFailedWords(headline = "This file type can't play on Kitchen display", cards = true), CastStart.words(c))
        assertEquals(listOf(CastButton.TryAnother, CastButton.PlayHere, CastButton.Close), CastStart.buttons(c))
        assertEquals(CastAction.TryAnother(cast, 1_930_000, forget = false), CastStart.press(CastButton.TryAnother, c))
        assertEquals(CastAction.PlayHere(here), CastStart.press(CastButton.PlayHere, c))
    }

    @Test
    fun `a service Android wouldn't start fails the play before anything is searched`() {
        val failed = CastStart.notStarted("Harbor Lights", "S02E04 · Low Water", kitchen)
        val c = failed.castFailed()
        assertEquals(CastFailKind.NotStarted, c.kind)
        assertEquals(CastFailedWords(advice = "Couldn't start casting. Try again with NZStreamer open."), CastStart.words(c))
        assertEquals(listOf(CastButton.PlayHere, CastButton.Close), CastStart.buttons(c))
        assertEquals(CastAction.PlayHere(null), CastStart.press(CastButton.PlayHere, c))
        assertEquals("Couldn't start casting. Try again with NZStreamer open.", assertIs<PlayOutcome.Failed>(CastStart.outcome(failed)).reason(RELEASE))
    }

    private companion object {
        const val RELEASE = "Harbor.Lights.S02E04.Low.Water.2160p.WEB-DL.DV.HDR10.DDP5.1.H.265-GRP"
        const val DECODE = "The device's player stopped with a decoding error"
        const val LOST = "Kitchen display lost the stream from this phone"
    }
}
