package io.skjaere.usenet.app.play

import io.skjaere.usenet.app.debrid.DebridListingStore
import io.skjaere.usenet.app.debrid.ServiceFiles
import io.skjaere.usenet.app.debrid.ServiceTarget
import io.skjaere.usenet.core.PlayCandidate
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.debrid.InfoHash
import io.skjaere.usenet.debrid.ServiceFile
import io.skjaere.usenet.debrid.ServiceListing
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.releases.Availability
import io.skjaere.usenet.releases.ReleaseFacts
import io.skjaere.usenet.data.LibraryRepository
import io.skjaere.usenet.data.WatchKey
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.data.WatchState
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * L90: a play's progress as the player saved it, now shared with a cast's: the watch's template, where it was, its length,
 * the release that played (unless it failed there) and the keys it took over. The show and its release are invented.
 */
class WatchSaverTest {
    private val release = SavedRelease("fake", "hl-s02e04", "Harbor.Lights.S02E04.1080p.WEB.h264-CAST", null, 0, "nzb-hl", "hl.mkv", 1_400_000_000)
    private val launch = PlayerLaunch(
        UsenetUris.build("nzb-hl", 0, release.fileName, release.fileSize), "Harbor Lights", "S02E04 · Low Water", WatchKey.episode(900002, 2, 4),
        WatchTarget(WatchKind.Episode, "Harbor Lights S02E04", showTmdbId = 900002, season = 2, episode = 4), release,
        replaces = listOf(WatchKey.release("nzb-other", 1)),
    )
    private val left = WatchState(
        WatchKey.episode(900002, 2, 4), WatchKind.Episode, showTmdbId = 900002, season = 2, episode = 4, title = "Harbor Lights S02E04",
        positionMs = 90_000, durationMs = 3_120_000, lastPlayedAt = 1_000, updatedAt = 1_000,
    )

    @Test
    fun `the template is the watch's, as the player's was`() {
        val save = WatchSaver.of(launch, positionMs = 1_930_000, durationMs = 3_120_000, releaseFailed = false) ?: throw AssertionError("nothing saved")
        assertEquals(
            WatchState(
                key = WatchKey.episode(900002, 2, 4), kind = WatchKind.Episode, showTmdbId = 900002, season = 2, episode = 4, movieTmdbId = null,
                title = "Harbor Lights S02E04", updatedAt = 0,
            ),
            save.template,
        )
        assertEquals(1_930_000, save.positionMs)
        assertEquals(3_120_000, save.durationMs)
        assertEquals(release.toRemembered(), save.release)
        assertEquals(listOf(WatchKey.release("nzb-other", 1)), save.replaces)
    }

    @Test
    fun `a release that failed there is left out`() {
        assertNull(WatchSaver.of(launch, 1_930_000, 3_120_000, releaseFailed = true)?.release)
    }

    /** L147: a file Import opened saves no progress, here or cast, as Import's play here never has. */
    @Test
    fun `a file Import opened saves nothing`() {
        assertNull(WatchSaver.of(launch.copy(imported = true), positionMs = 1_930_000, durationMs = 3_120_000, releaseFailed = false))
        assertNull(WatchSaver.of(launch.copy(imported = true), positionMs = 3_120_000, durationMs = 3_120_000, releaseFailed = true))
    }

    /** Without a length nothing is saved: neither the 90 % rule nor a place in the film can be worked out. */
    @Test
    fun `nothing is saved without a length`() {
        assertNull(WatchSaver.of(launch, 1_930_000, durationMs = 0, releaseFailed = false))
        assertNull(WatchSaver.of(launch, 1_930_000, durationMs = -1, releaseFailed = false))
        assertNull(WatchSaver.of(launch, 1_930_000, durationMs = Long.MIN_VALUE + 1, releaseFailed = false))
    }

    /** L61: a play starts where its watch was left, here or cast, unless watched or under a minute in; without a row, at 0. */
    @Test
    fun `a play starts where its watch was left`() {
        assertEquals(90_000, WatchSaver.resumeAt(left))
        assertEquals(LibraryRepository.MIN_RESUME_MS, WatchSaver.resumeAt(left.copy(positionMs = LibraryRepository.MIN_RESUME_MS)))
        assertEquals(0, WatchSaver.resumeAt(left.copy(positionMs = LibraryRepository.MIN_RESUME_MS - 1)))
        assertEquals(0, WatchSaver.resumeAt(left.copy(watched = true)))
        assertEquals(0, WatchSaver.resumeAt(null))
    }

    /**
     * A tombstone is no place to start from: its row was removed (an undo of what made it, or another release taking its
     * progress over), and the play starts from the beginning, as without a row, not where the removed row was left.
     */
    @Test
    fun `a play whose watch row was removed starts from the beginning`() {
        assertEquals(0, WatchSaver.resumeAt(left.copy(deleted = true)))
    }

    /**
     * A torrent's play (L181): remembered with where it played from and its content id alone, as its guid and NZB id, its
     * file by its place, name and size in the listing; never the magnet, a tracker's link or a passkey, which the search
     * never kept either. Played without TMDB, its watch key is the content id's.
     */
    @Test
    fun `a torrent's play remembers where it played from and its content id, nothing of a tracker`(@TempDir dir: File) {
        val hash = InfoHash.of("c0ffee11c0ffee22c0ffee33c0ffee44c0ffee55")
        val title = "Copper.Crown.2019.1080p.WEB-DL.DDP5.1.H.264-TIDE"
        val item = NewznabItem(
            indexerId = "prowlarr", guid = "btih:${hash.hex}", title = title, sizeBytes = 6_442_450_944, publishedAt = null, postedAt = null,
            categories = emptyList(), grabs = 5_200, passworded = false, files = 1, tvdbId = null, tmdbId = null, imdbId = null, tvmazeId = null,
            season = null, episode = null, coverUrl = null, mediaInfo = null, downloadTemplate = null, attributes = mapOf("seeders" to listOf("300")),
            infoHash = hash.hex, availability = Availability.Cached(listOf("tb")),
        )
        val files = ServiceFiles(hash, title, ServiceListing(listOf(ServiceFile(0, "$title/Sample/sample.mkv", 40), ServiceFile(1, "$title/$title.mkv", 6_000_000_000, "f1"))))
        val candidate = PlayCandidate("btih:${hash.hex}", title, "prowlarr", source = ReleaseFacts.TORRENT, routes = listOf("service:tb"))
        val target = ServiceTarget(candidate, files, files.files[1], "0f3e9a1c5b7d2e4f6a8b0c1d2e3f4a5b", "tb", DebridListingStore(File(dir, "listings.json")))
        val saved = savedRelease(item, target)
        assertEquals(SavedRelease("prowlarr", "btih:${hash.hex}", title, null, 1, "btih:${hash.hex}", "$title.mkv", 6_000_000_000, ReleaseFacts.TORRENT), saved)
        val request = PlayRequest(title, null, emptyList(), watch = WatchTarget(WatchKind.Release, title))
        val launch = launchOf(request, saved, target.uri, "play-1", retry = null)
        assertEquals("release:btih:${hash.hex}:1", launch.watchKey)
        val row = assertNotNull(WatchSaver.of(launch, positionMs = 40_000, durationMs = 6_000_000, releaseFailed = false)?.release)
        assertEquals(ReleaseFacts.TORRENT, row.source)
        assertEquals("btih:${hash.hex}", row.guid)
        assertEquals("btih:${hash.hex}", row.nzbId)
        assertNull(row.downloadTemplate)
        listOf("magnet:", "tr=", "announce", "passkey", "torrent_pass", "http", "s3cr3t").forEach { assertFalse(it in row.toString(), it) }
        assertFalse("s3cr3t" in launch.encode())
    }
}
