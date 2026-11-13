package io.skjaere.usenet.player

import org.junit.jupiter.api.Test
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The video fixtures' SHA-256s as the README beside them records them (the first 8 and last 4 hex digits), which is how a
 * rerun of tools/dovi/make_video_fixtures.sh, or of the README's commands for the gap fixtures, is checked to give the same bytes.
 */
class FixtureChecksumsTest {
    @Test
    fun `the README's checksums are the fixtures'`() {
        val readme = ExtractedFormats.fixture("README.md").decodeToString()
        // "- `dv-p5.mkv` and `dv-p7.mkv` (3 KB each, `0366971f…675b` and `99145708…e203`)": names, then their checksums in order.
        val recorded = readme.lines().filter { it.trimStart().startsWith("- `") }.flatMap { line ->
            val names = Regex("`([\\w-]+\\.(?:mkv|mp4|ts|mpg))`").findAll(line.substringBefore("(")).map { it.groupValues[1] }.toList()
            val sums = Regex("`([0-9a-f]{8})…([0-9a-f]{4})`").findAll(line).map { it.groupValues[1] to it.groupValues[2] }.toList()
            if (sums.isEmpty()) emptyList() else names.zip(sums)
        }
        assertEquals(16, recorded.size, "$recorded")
        recorded.forEach { (name, sum) ->
            val hex = MessageDigest.getInstance("SHA-256").digest(ExtractedFormats.fixture(name)).joinToString("") { "%02x".format(it) }
            assertTrue(hex.startsWith(sum.first) && hex.endsWith(sum.second), "$name is $hex, the README says ${sum.first}…${sum.second}")
        }
    }
}
