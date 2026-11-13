package io.skjaere.usenet.app

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.play.PlayRequest
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.SavedRelease
import io.skjaere.usenet.app.play.WatchTarget
import io.skjaere.usenet.core.FilePicker
import io.skjaere.usenet.core.PrepareOutcome
import io.skjaere.usenet.core.PreparedNzb
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.releases.RankedRelease
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.releases.ReleaseParser
import io.skjaere.usenet.search.RankedItem
import kotlinx.coroutines.delay

/**
 * The gap tests' releases: the device tests' own media posted again on a server inside the test's process ([LocalNntp]),
 * prepared and put in the store, and launched straight into the player. The film is invented ([film]); [indexer] is the
 * test's.
 */
class GapFixtures(val local: LocalNntp, private val indexer: String, val film: WatchTarget) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val container = instrumentation.targetContext.container

    /** The test assets' media file [name]. */
    fun media(name: String): ByteArray = instrumentation.context.assets.open(name).use { it.readBytes() }

    /** [bytes] posted as [name] in parts of [partSize] under [tag], with [missing] answering 430: its NZB, and the release prepared from it and in the store. */
    suspend fun release(name: String, bytes: ByteArray, partSize: Int, tag: String, title: String, missing: (LocalNntp.Posted) -> Set<String>): Release {
        val posted = local.post(name, bytes, partSize, tag)
        local.missing += missing(posted)
        val nzb = local.nzb(posted)
        // Not prepareFixture, which skips the test when the shared mock lacks its articles: these are the test's own.
        val outcome = engineSetup("$name posted in parts of $partSize prepared") { container.preparer.prepare(nzb, title) }
        val prepared = (outcome as? PrepareOutcome.Ready)?.nzb ?: throw AssertionError("$name didn't prepare: $outcome")
        container.store.put(prepared, nzb)
        return Release(posted, prepared, nzb)
    }

    class Release(val posted: LocalNntp.Posted, val prepared: PreparedNzb, val nzb: ByteArray)

    /** [prepared] as the release [guid] played it. */
    fun saved(guid: String, prepared: PreparedNzb): SavedRelease {
        val file = prepared.defaultPick!!
        return SavedRelease(indexer, guid, prepared.title, null, file.index, prepared.id, file.name, file.size)
    }

    /** A search's release [guid] titled [title], as the request's candidates are. */
    fun candidate(guid: String, title: String) = RankedItem(
        NewznabItem(
            indexerId = indexer, guid = guid, title = title, sizeBytes = 140_000, publishedAt = null, postedAt = null,
            categories = emptyList(), grabs = null, passworded = false, files = null, tvdbId = null, tmdbId = null, imdbId = null,
            tvmazeId = null, season = null, episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = emptyMap(),
        ),
        RankedRelease(ReleaseFacts("$indexer/$guid", title, 140_000), ReleaseParser.parse(title), playable = true, score = 1.0, reasons = emptyList(), estimatedBitrate = null),
    )

    /** [prepared]'s launch as release [guid] from [startMs], as the coordinator makes it for [candidates] (kept as it keeps a play's request). */
    fun launch(prepared: PreparedNzb, guid: String, startMs: Long, candidates: List<RankedItem> = emptyList()): PlayerLaunch {
        val release = saved(guid, prepared)
        val request = PlayRequest("Lantern Field", "2026", candidates, FilePicker.Largest, film)
        return PlayerLaunch(
            uri = UsenetUris.build(prepared.id, release.fileIndex, release.fileName, release.fileSize), title = request.title, subtitle = request.subtitle,
            watchKey = WatchKey.movie(film.movieTmdbId!!), watch = film, release = release, requestId = container.playback.keep(request), startPositionMs = startMs,
        )
    }
}

fun <T> ActivityScenario<PlayerActivity>.read(probe: (PlayerActivity) -> T): T {
    var result: Result<T>? = null
    onActivity { result = runCatching { probe(it) } }
    return result!!.getOrThrow()
}

/** Polls [probe] every 100 ms until it gives something, for at most [timeoutMs]. */
suspend fun <T : Any> waitFor(what: String, timeoutMs: Long = 20_000, probe: () -> T?): T {
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    while (true) {
        probe()?.let { return it }
        if (SystemClock.elapsedRealtime() > deadline) throw AssertionError("timed out waiting for $what")
        delay(100)
    }
}

fun views(view: View): List<View> =
    listOf(view) + ((view as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { views(g.getChildAt(it)) } } ?: emptyList())

/** Every semantics node of the shown Compose views: the player's overlays, and a side panel while one is open. */
fun overlayNodes(activity: Activity): List<SemanticsNode> =
    views(activity.window.decorView).filterIsInstance<ViewRootForTest>().filter { it.view.isShown }
        .flatMap { subtree(it.semanticsOwner.unmergedRootSemanticsNode) }

private fun subtree(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::subtree)

private fun SemanticsNode.words(): List<String> =
    config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
        listOfNotNull(config.getOrNull(SemanticsProperties.PaneTitle))

fun overlayWords(activity: Activity) = overlayNodes(activity).flatMap { it.words() }

/** The words of the overlay node that has the focus, and those inside it. */
fun focusedWords(activity: Activity) =
    overlayNodes(activity).firstOrNull { it.config.getOrNull(SemanticsProperties.Focused) == true }?.let(::subtree).orEmpty().flatMap { it.words() }

/** Clicks the overlay's button that says [words], as a tap does. */
fun clickOverlay(activity: Activity, words: String) {
    val node = overlayNodes(activity).firstOrNull { n -> n.config.getOrNull(SemanticsActions.OnClick) != null && subtree(n).any { words in it.words() } }
        ?: throw AssertionError("no button \"$words\" in ${overlayWords(activity)}")
    node.config[SemanticsActions.OnClick].action?.invoke()
}

/** The player's moment label while it shows, else null. */
fun momentLabel(activity: Activity): TextView? =
    views(activity.window.decorView).filterIsInstance<TextView>().firstOrNull { it.isShown && it.text?.toString() == PlayerActivity.MISSING_PART_SKIPPED }
