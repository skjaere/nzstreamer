package io.skjaere.usenet.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/** Sizes as the app shows them, in decimal units: whole KB under 1 MB, one decimal under 10 MB, whole MB under 1 GB, GB with one decimal. */
class ByteSizeTest {
    private val saved = Locale.getDefault()

    // The digits follow the default locale; pin one so the decimal separator is predictable.
    @BeforeEach
    fun pin() = Locale.setDefault(Locale.US)

    @AfterEach
    fun unpin() = Locale.setDefault(saved)

    @Test
    fun `a size reads in whole KB under 1 MB, MB with one decimal under 10 MB, whole MB under 1 GB, then GB with one decimal`() {
        assertEquals("0 KB", formatSize(0))
        assertEquals("52 KB", formatSize(52_000))
        assertEquals("999 KB", formatSize(999_000))
        assertEquals("1.0 MB", formatSize(1_000_000))
        assertEquals("2.3 MB", formatSize(2_300_000))
        assertEquals("9.9 MB", formatSize(9_900_000))
        assertEquals("10 MB", formatSize(10_000_000))
        assertEquals("350 MB", formatSize(350_000_000))
        assertEquals("999 MB", formatSize(999_000_000))
        assertEquals("1.0 GB", formatSize(1_000_000_000))
        assertEquals("12.4 GB", formatSize(12_400_000_000))
    }

    /** Rounded half up, in the unit the rounded figure reads in: never "1000 KB", "10.0 MB" or "1000 MB". */
    @Test
    fun `a size that rounds up to the next unit reads in that unit`() {
        assertEquals("999 KB", formatSize(999_499))
        assertEquals("1.0 MB", formatSize(999_500))
        assertEquals("2.3 MB", formatSize(2_349_999))
        assertEquals("2.4 MB", formatSize(2_350_000))
        assertEquals("9.9 MB", formatSize(9_949_999))
        assertEquals("10 MB", formatSize(9_950_000))
        assertEquals("999 MB", formatSize(999_499_999))
        assertEquals("1.0 GB", formatSize(999_500_000))
    }
}
