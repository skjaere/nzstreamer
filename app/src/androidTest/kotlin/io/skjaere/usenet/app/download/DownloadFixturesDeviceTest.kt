package io.skjaere.usenet.app.download

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.ktor.utils.io.readAvailable
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.download.DownloadFixtures.Packing
import io.skjaere.usenet.app.engineSetup
import io.skjaere.usenet.app.mockOnEngine
import io.skjaere.usenet.core.EngineSettings
import io.skjaere.usenet.core.NzbStreamOpener
import io.skjaere.usenet.core.PrepareOutcome
import io.skjaere.usenet.core.PreparedNzb
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

/**
 * The downloads' archive fixtures (T4), proven before the writer that uses them: each release [DownloadFixtures] posts on
 * the test's own server opens through the app's preparer as one video of the source's name and size, and its bytes, streamed
 * through the app's opener from the first to the last, are the source's. Seeded bytes at either end of the fixtures' sizes
 * (16 and 64 MB), plain, as a split stored RAR4 set and as a split 7z (Copy); the test assets' film, where a test plays it,
 * as a set too. What a test writes beside them goes with them.
 */
@RunWith(AndroidJUnit4::class)
class DownloadFixturesDeviceTest {
    private val container = InstrumentationRegistry.getInstrumentation().targetContext.container
    private val fixtures = DownloadFixtures()

    @Before
    fun setUp() = runBlocking<Unit> {
        engineSetup("the engine on the test's own NNTP server") {
            mockOnEngine()
            container.engine.configure(EngineSettings(listOf(fixtures.local.provider)))
        }
    }

    @After
    fun tearDown() {
        fixtures.close()
    }

    @Test
    fun plainSeededBytesOpenWholeThroughThePreparer() = runBlocking<Unit> {
        val release = fixtures.post(Packing.Plain, TITLE, DownloadFixtures.seeded(16 * DownloadFixtures.MB, seed = 1))
        assertEquals(listOf("$TITLE.mkv"), release.posted.map { it.name })
        opensWhole(release)
    }

    @Test
    fun aSplitStoredRar4SetOpensWholeThroughThePreparer() = runBlocking<Unit> {
        val release = fixtures.post(Packing.Rar4, TITLE, DownloadFixtures.seeded(16 * DownloadFixtures.MB, seed = 2))
        // Volumes of 5 MB at most: four, named as a RAR4 set's are, the first .rar.
        assertEquals(listOf("$TITLE.rar", "$TITLE.r00", "$TITLE.r01", "$TITLE.r02"), release.posted.map { it.name })
        assertTrue(release.posted.all { it.data.size <= DownloadFixtures.VOLUME })
        opensWhole(release)
    }

    @Test
    fun aSplit7zCopySetOfTheLargestSizeOpensWholeThroughThePreparer() = runBlocking<Unit> {
        val release = fixtures.post(Packing.SevenZip, TITLE, DownloadFixtures.seeded(64 * DownloadFixtures.MB, seed = 3))
        assertEquals((1..13).map { "$TITLE.7z.%03d".format(it) }, release.posted.map { it.name })
        opensWhole(release)
    }

    @Test
    fun theTestAssetsFilmGoesInASplitSetWhereATestPlaysIt() = runBlocking<Unit> {
        val film = DownloadFixtures.media("chapters.mkv")
        val release = fixtures.post(Packing.Rar4, FILM, film, partSize = 64 * 1024, volumeSize = 250_000)
        assertEquals(3, release.posted.size)
        opensWhole(release)
    }

    @Test
    fun seededBytesAreTheSeedsAlwaysAndOfTheFixturesSizesOnly() {
        val size = 16 * DownloadFixtures.MB
        assertArrayEquals(DownloadFixtures.seeded(size, seed = 7), DownloadFixtures.seeded(size, seed = 7))
        assertNotEquals(DownloadFixtures.sha256(DownloadFixtures.seeded(size, seed = 7)), DownloadFixtures.sha256(DownloadFixtures.seeded(size, seed = 8)))
        assertThrows(IllegalArgumentException::class.java) { DownloadFixtures.seeded(size - 1, seed = 7) }
        assertThrows(IllegalArgumentException::class.java) { DownloadFixtures.seeded(64 * DownloadFixtures.MB + 1, seed = 7) }
    }

    @Test
    fun whatATestWritesBesideTheFixturesIsDeletedAfter() {
        val written = fixtures.file("v.mkv.part").apply { writeBytes(ByteArray(1_024)) }
        assertTrue(written.isFile)
        assertEquals(DownloadFixtures.sha256(ByteArray(1_024)), DownloadFixtures.sha256(written))
        fixtures.close()
        assertFalse(written.exists())
        assertFalse(written.parentFile!!.exists())
    }

    /**
     * [release] prepared by the app's preparer, as Resolve prepares one: a listing of one video, its name and size the
     * source's; then its whole file streamed through the app's opener, every byte the source's and no part missing.
     */
    private suspend fun opensWhole(release: DownloadFixtures.Release) {
        val outcome = engineSetup("${release.title} prepared") { container.preparer.prepare(release.nzb, release.title) }
        val prepared = (outcome as? PrepareOutcome.Ready)?.nzb ?: throw AssertionError("${release.title} (${release.packing}) didn't prepare: $outcome")
        assertEquals(listOf(release.fileName to release.size), prepared.files.map { it.name to it.size })
        assertTrue(prepared.oneVideo)
        assertEquals(release.sha256, streamed(prepared))
    }

    /** The SHA-256 of [prepared]'s one file, streamed whole through the app's opener; it fails where the stream fills a gap. */
    private suspend fun streamed(prepared: PreparedNzb): String = withTimeout(120_000) {
        val file = prepared.files.single()
        val opened = NzbStreamOpener(container.engine).open(prepared, file.index, 0 until file.size)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val chunk = ByteArray(64 * 1024)
            var read = 0L
            while (true) {
                val n = opened.channel.readAvailable(chunk, 0, chunk.size)
                if (n == -1) break
                digest.update(chunk, 0, n)
                read += n
            }
            opened.channel.closedCause?.let { throw AssertionError("the stream of ${prepared.title} failed after $read bytes", it) }
            assertEquals(file.size, read)
            assertEquals("parts filled with zeros", emptyList<LongRange>(), opened.gaps.all())
            digest.digest().joinToString("") { "%02x".format(it) }
        } finally {
            opened.job.cancel()
        }
    }

    private companion object {
        const val TITLE = "Harbor.Lights.S02E04.1080p.WEB-DL.H.264-KESTREL"
        const val FILM = "Copper.Crown.2019.1080p.WEB-DL.H.264-GRP"
    }
}
