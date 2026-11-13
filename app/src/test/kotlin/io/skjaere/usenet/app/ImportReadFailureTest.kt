package io.skjaere.usenet.app

import io.skjaere.usenet.core.MAX_NZB_BYTES
import io.skjaere.usenet.core.NzbTooLargeException
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.Locale
import kotlin.test.assertEquals

class ImportReadFailureTest {
    @Test
    fun `a file reported over the cap says it is too large, with its size`() {
        // formatSize follows the default locale; pin one so the decimal separator is predictable.
        val saved = Locale.getDefault()
        Locale.setDefault(Locale.US)
        try {
            assertEquals(
                "This file is too large to be an NZB (2.0 GB).",
                describeReadFailure(NzbTooLargeException(2_000_000_000, MAX_NZB_BYTES)),
            )
        } finally {
            Locale.setDefault(saved)
        }
    }

    @Test
    fun `a stream that ran past the cap says it is too large, with the cap`() {
        assertEquals(
            "This file is too large to be an NZB (over 64 MB).",
            describeReadFailure(NzbTooLargeException(null, MAX_NZB_BYTES)),
        )
    }

    @Test
    fun `other read errors keep their message`() {
        assertEquals("Could not read the file: Permission denied", describeReadFailure(IOException("Permission denied")))
    }
}
