package io.skjaere.usenet.data

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SeasonCountsTest {
    @Test
    fun `seasons and their episode counts round trip in order`() {
        val counts = listOf(1 to 10, 2 to 8, 12 to 105)
        assertEquals("1:10,2:8,12:105", SeasonCounts.encode(counts))
        assertEquals(counts, SeasonCounts.parse(SeasonCounts.encode(counts)))
        assertEquals("", SeasonCounts.encode(emptyList()))
        assertEquals(emptyList(), SeasonCounts.parse(""))
    }

    @Test
    fun `malformed parts are skipped`() {
        assertEquals(listOf(1 to 10, 3 to 8, 5 to 2), SeasonCounts.parse("1:10,x,2:,:4,3:8,4:-1,6:1:2, 5:2 ,7:a"))
    }
}
