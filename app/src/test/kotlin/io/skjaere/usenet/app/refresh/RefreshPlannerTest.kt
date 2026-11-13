package io.skjaere.usenet.app.refresh

import io.skjaere.usenet.app.collection.model.TitleKey
import io.skjaere.usenet.data.RefreshCandidate
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RefreshPlannerTest {
    private val now = 100L * 24 * 3_600_000
    private val day = 24 * 3_600_000L

    private fun c(id: Int, addedAt: Long = id.toLong(), fetchedAt: Long? = null, staleAt: Long? = null, retryAt: Long? = null, watched: Long? = null) =
        RefreshCandidate(id, addedAt, fetchedAt, staleAt, retryAt, failures = null, lastWatchedAt = watched)

    private fun shows(vararg c: RefreshCandidate) = c.associateBy { TitleKey.show(it.tmdbId) }

    @Test
    fun `recently watched first, then what is on screen, then never asked, then the stalest`() {
        val candidates = shows(
            c(1, fetchedAt = 1, staleAt = now - 5),
            c(2, fetchedAt = 1, staleAt = now - 50),
            c(3, watched = now - 2 * day, fetchedAt = 1, staleAt = now),
            c(4, watched = now - day, fetchedAt = 1, staleAt = 0),
            c(5, addedAt = 9),
            c(6, addedAt = 30),
            c(7, watched = now - 31 * day, fetchedAt = 1, staleAt = now - 1),
            c(8, fetchedAt = 1, staleAt = now - 1),
        )
        val visible = listOf(TitleKey.show(8), TitleKey.show(1))
        assertEquals(listOf(4, 3, 8, 1, 6, 5, 2, 7), RefreshPlanner.order(candidates, now, visible, emptySet()).map { it.tmdbId })
    }

    @Test
    fun `titles in flight, still fresh or waiting to retry are left out`() {
        val candidates = shows(
            c(1, fetchedAt = 1, staleAt = now + 1),
            c(2, fetchedAt = 1, staleAt = now - 1, retryAt = now + 1),
            c(3),
            c(4, retryAt = now),
            c(5, fetchedAt = 1, staleAt = now),
        )
        assertEquals(listOf(4, 5), RefreshPlanner.order(candidates, now, emptyList(), setOf(TitleKey.show(3))).map { it.tmdbId }.sorted())
    }

    @Test
    fun `shows and movies with the same id are different titles`() {
        val candidates = shows(c(1)) + mapOf(TitleKey.movie(1) to c(1, addedAt = 50))
        assertEquals(listOf(TitleKey.movie(1), TitleKey.show(1)), RefreshPlanner.order(candidates, now, emptyList(), emptySet()))
    }

    @Test
    fun `the next wake is when the first title not in flight is due`() {
        val candidates = shows(c(1, fetchedAt = 1, staleAt = now + 500), c(2, fetchedAt = 1, staleAt = now + 900, retryAt = now + 2_000), c(3, fetchedAt = 1, staleAt = now + 100))
        assertEquals(now + 100, RefreshPlanner.nextWake(candidates, now, emptySet()))
        assertEquals(now + 500, RefreshPlanner.nextWake(candidates, now, setOf(TitleKey.show(3))))
        assertEquals(now + 2_000, RefreshPlanner.nextWake(candidates - TitleKey.show(1), now, setOf(TitleKey.show(3))))
        assertNull(RefreshPlanner.nextWake(shows(c(1, fetchedAt = 1, staleAt = now - 1)), now, emptySet()))
        assertNull(RefreshPlanner.nextWake(emptyMap(), now, emptySet()))
    }
}
