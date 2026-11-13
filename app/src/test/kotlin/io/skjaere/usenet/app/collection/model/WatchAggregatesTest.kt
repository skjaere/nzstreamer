package io.skjaere.usenet.app.collection.model

import io.skjaere.usenet.data.ShowWatchAggregate
import io.skjaere.usenet.metadata.EpisodeWatch
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class WatchAggregatesTest {
    @Test
    fun `watched episodes and episodes in progress are read from the aggregate`() {
        val parsed = WatchAggregates.parse(ShowWatchAggregate(1, "1:1,1:2,2:5", "2:6:600000:50;1:3:120000:40", 50))
        assertEquals(
            listOf(
                EpisodeWatch(1, 1, true), EpisodeWatch(1, 2, true), EpisodeWatch(2, 5, true),
                EpisodeWatch(1, 3, false, 120_000, 40), EpisodeWatch(2, 6, false, 600_000, 50),
            ),
            parsed,
        )
    }

    @Test
    fun `a show with neither has no watches`() {
        assertEquals(emptyList(), WatchAggregates.parse(ShowWatchAggregate(1, null, null, 7)))
    }

    @Test
    fun `malformed parts are skipped`() {
        val parsed = WatchAggregates.parse(ShowWatchAggregate(1, "1:1,x:2,3,,2:y,1:2:3", "1:3:5000;2:1:600000:9;a:b:c:d;;2:2:600000", 9))
        assertEquals(listOf(EpisodeWatch(1, 1, true), EpisodeWatch(2, 1, false, 600_000, 9)), parsed)
    }

    /** ShowProgress resumes the most recently played; on a tie the earliest episode, whatever order SQL gave. */
    @Test
    fun `episodes in progress are in a fixed order, ties by episode`() {
        val one = WatchAggregates.parse(ShowWatchAggregate(1, null, "2:3:600000:50;1:9:700000:50;1:1:600000:20", 50))
        val other = WatchAggregates.parse(ShowWatchAggregate(1, null, "1:1:600000:20;1:9:700000:50;2:3:600000:50", 50))
        assertEquals(listOf(EpisodeWatch(1, 1, false, 600_000, 20), EpisodeWatch(1, 9, false, 700_000, 50), EpisodeWatch(2, 3, false, 600_000, 50)), one)
        assertEquals(one, other)
    }
}
