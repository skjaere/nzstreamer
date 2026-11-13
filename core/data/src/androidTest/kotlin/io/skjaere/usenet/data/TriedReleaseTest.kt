package io.skjaere.usenet.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The releases found missing on the providers, as the library stores them: by indexer and guid, each
 * provider with when it was checked, at most [ReleaseChecks.MAX_ENTRIES] of them, the expired ones
 * pruned, and Clear forgetting them all.
 */
@RunWith(AndroidJUnit4::class)
class TriedReleaseTest {
    private val db = LibraryDatabase.inMemory(InstrumentationRegistry.getInstrumentation().targetContext)
    private var time = 100 * DAY
    private val library = LibraryRepository(db) { time }

    @After
    fun close() {
        db.close()
    }

    @Test
    fun aReleaseFoundMissingIsRememberedByIndexerAndGuidWithEachProviderAsChecked() = runBlocking<Unit> {
        library.recordMissing("fake", "hl-s01e01-broken", listOf(A))
        time += DAY
        library.recordMissing("fake", "hl-s01e01-broken", listOf(B))
        library.recordMissing("other", "hl-s01e01-broken", emptyList(), incomplete = true)
        val ids = listOf("fake/hl-s01e01-broken", "other/hl-s01e01-broken", "fake/never-tried")
        assertEquals(
            mapOf(
                "fake/hl-s01e01-broken" to TriedRelease("fake/hl-s01e01-broken", "fake", "hl-s01e01-broken", listOf(ProviderCheck(A, 100 * DAY), ProviderCheck(B, 101 * DAY)), null, 101 * DAY),
                "other/hl-s01e01-broken" to TriedRelease("other/hl-s01e01-broken", "other", "hl-s01e01-broken", emptyList(), 101 * DAY, 101 * DAY),
            ),
            library.triedReleases(ids),
        )
        assertEquals(2, library.triedReleaseCount())
    }

    /**
     * A release that needed a password it didn't come with is remembered beside the ones found missing, whatever the
     * providers, kept with what was found of it before, and forgotten once it plays.
     */
    @Test
    fun aReleaseThatNeededAPasswordIsRememberedBesideTheMissingOnesUntilItPlays() = runBlocking<Unit> {
        library.recordMissing("fake", "hl-s01e01-passwd", listOf(A))
        time += DAY
        assertEquals(
            TriedRelease("fake/hl-s01e01-passwd", "fake", "hl-s01e01-passwd", listOf(ProviderCheck(A, 100 * DAY)), null, 101 * DAY, passwordAt = 101 * DAY),
            library.recordPasswordNeeded("fake", "hl-s01e01-passwd"),
        )
        assertEquals(101 * DAY, library.triedReleases(listOf("fake/hl-s01e01-passwd")).values.single().passwordAt)
        assertEquals(1, library.triedReleaseCount())
        library.recordFound("fake", "hl-s01e01-passwd", listOf(A))
        assertEquals(emptyMap<String, TriedRelease>(), library.triedReleases(listOf("fake/hl-s01e01-passwd")))
    }

    /** A release that played with providers isn't missing on them: their checks go, and the entry once none is left. */
    @Test
    fun aReleaseThatPlayedIsForgottenForTheProvidersItPlayedWith() = runBlocking<Unit> {
        library.recordMissing("fake", "hl-s01e01-broken", listOf(A))
        time += DAY
        library.recordMissing("fake", "hl-s01e01-broken", listOf(B), incomplete = true)
        library.recordFound("fake", "hl-s01e01-broken", listOf(B))
        assertEquals(
            TriedRelease("fake/hl-s01e01-broken", "fake", "hl-s01e01-broken", listOf(ProviderCheck(A, 100 * DAY)), null, 100 * DAY),
            library.triedReleases(listOf("fake/hl-s01e01-broken")).values.single(),
        )
        library.recordFound("fake", "hl-s01e01-broken", listOf(A, B))
        library.recordFound("fake", "never-tried", listOf(A))
        assertEquals(0, library.triedReleaseCount())
    }

    @Test
    fun pastTwoThousandTheLeastRecentlyCheckedAreForgotten() = runBlocking<Unit> {
        val old = (1..ReleaseChecks.MAX_ENTRIES).map { TriedRelease("fake/r$it", "fake", "r$it", listOf(ProviderCheck(A, it.toLong())), null, updatedAt = time - DAY + it) }
        library.restoreTriedReleases(old)
        assertEquals(ReleaseChecks.MAX_ENTRIES, library.triedReleaseCount())
        library.recordMissing("fake", "new", listOf(A))
        assertEquals(ReleaseChecks.MAX_ENTRIES, library.triedReleaseCount())
        assertEquals(setOf("fake/r2", "fake/new"), library.triedReleases(listOf("fake/r1", "fake/r2", "fake/new")).keys)
    }

    /**
     * Settings › Cache counts the releases a play would still skip for what was found: not one whose every check has expired,
     * though it stays stored until the app's next start prunes it, which a process kept alive for weeks (a TV) puts off.
     */
    @Test
    fun theCountLeavesOutTheExpiredBeforeTheyArePruned() = runBlocking<Unit> {
        library.recordMissing("fake", "checked-long-ago", listOf(A))
        time += DAY
        library.recordMissing("other", "incomplete-long-ago", emptyList(), incomplete = true)
        time += ReleaseChecks.EXPIRY_MS - DAY
        library.recordMissing("fake", "checked-lately", listOf(A))
        assertEquals(2, library.triedReleaseCount())
        time += DAY
        assertEquals(1, library.triedReleaseCount())
        assertEquals(3, library.triedReleaseEntries().size)
    }

    @Test
    fun theExpiredArePrunedAndClearForgetsThemAll() = runBlocking<Unit> {
        library.recordMissing("fake", "checked-long-ago", listOf(A))
        time += ReleaseChecks.EXPIRY_MS
        library.recordMissing("fake", "checked-lately", listOf(A))
        library.pruneTriedReleases()
        assertEquals(listOf("fake/checked-lately"), library.triedReleaseEntries().map { it.id })
        library.clearTriedReleases()
        assertEquals(0, library.triedReleaseCount())
    }

    /**
     * The release list watches what is remembered of its releases: it comes again as one is found missing, plays, or Clear
     * forgets them all; past 999 releases too, which SQLite before Android 11 can't ask for at once.
     */
    @Test
    fun whatIsRememberedOfSomeReleasesIsWatched() = runBlocking<Unit> {
        val ids = (1..1_200).map { "fake/r$it" }
        val seen = Channel<Set<String>>(Channel.UNLIMITED)
        val watching = launch(Dispatchers.Default) { library.observeTriedReleases(ids).collect { seen.send(it.keys) } }
        seen.until(emptySet())
        library.recordMissing("fake", "r1100", listOf(A))
        library.recordMissing("fake", "never-listed", listOf(A))
        seen.until(setOf("fake/r1100"))
        library.recordMissing("fake", "r3", listOf(B))
        seen.until(setOf("fake/r3", "fake/r1100"))
        library.recordFound("fake", "r1100", listOf(A))
        seen.until(setOf("fake/r3"))
        library.clearTriedReleases()
        seen.until(emptySet())
        watching.cancel()
        assertEquals(emptyMap<String, TriedRelease>(), library.observeTriedReleases(emptyList()).first())
    }

    /** Until [expected] comes, or fails after five seconds. */
    private suspend fun Channel<Set<String>>.until(expected: Set<String>) = withTimeout(5_000) {
        while (receive() != expected) continue
    }

    /** A stored list of checks that doesn't parse reads as none: the release is tried, and its next check writes it again. */
    @Test
    fun checksThatDontParseReadAsNone() = runBlocking<Unit> {
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO tried_release (id, indexerId, guid, missingOn, incompleteAt, updatedAt) VALUES ('fake/garbled', 'fake', 'garbled', 'not json', NULL, 5)",
        )
        assertEquals(TriedRelease("fake/garbled", "fake", "garbled", emptyList(), null, 5), library.triedReleases(listOf("fake/garbled")).values.single())
    }

    private companion object {
        const val DAY = 24 * 60 * 60 * 1000L
        const val A = "p-a@news.example.com:563"
        const val B = "p-b@eu.example.net:443"
    }
}
