package io.skjaere.usenet.app

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.core.EngineSettings
import io.skjaere.usenet.core.PrepareOutcome
import io.skjaere.usenet.core.UsenetUris
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * End to end on the device: prepare the NZB written by tools/mock-seed against the mock
 * NNTP server on the emulator host, then read the file through the real Media3
 * DataSource, including a mid-file seek. Skipped unless the mock is reachable and the
 * seeded files were copied into build/smoke/ before assembling the test APK.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class EngineSmokeTest {
    private val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
    private val container = InstrumentationRegistry.getInstrumentation().targetContext.container

    @Before
    fun requireMock() {
        val assets = testAssets.list("").orEmpty()
        assumeTrue("build/smoke/sample.nzb not packaged", "sample.nzb" in assets && "sample.mkv" in assets)
        val reachable = runCatching { Socket().use { it.connect(InetSocketAddress("10.0.2.2", 1119), 2_000) } }.isSuccess
        assumeTrue("mock NNTP server not reachable at 10.0.2.2:1119", reachable)
    }

    @Test
    fun prepareAndStreamThroughDataSource() = runBlocking {
        val nzbBytes = testAssets.open("sample.nzb").use { it.readBytes() }
        val expected = testAssets.open("sample.mkv").use { it.readBytes() }
        val outcome = engineSetup("the mock on the engine, the sample prepared") {
            container.engine.configure(EngineSettings(listOf(MOCK_PROVIDER)))
            container.preparer.prepare(nzbBytes, "sample")
        }
        val nzb = (outcome as? PrepareOutcome.Ready)?.nzb ?: error("prepare failed: $outcome")
        container.store.put(nzb, nzbBytes)
        val file = nzb.defaultPick ?: error("no video in ${nzb.candidates}")
        assertEquals(expected.size.toLong(), file.size)

        val uri = Uri.parse(UsenetUris.build(nzb.id, file.index))
        assertArrayEquals(expected, read(uri, 0, C.LENGTH_UNSET.toLong()))

        val seek = expected.size / 2L + 4_321
        assertArrayEquals(expected.copyOfRange(seek.toInt(), seek.toInt() + 500_000), read(uri, seek, 500_000))
    }

    private fun read(uri: Uri, position: Long, length: Long): ByteArray {
        val source = container.dataSourceFactory.createDataSource()
        val out = ByteArrayOutputStream()
        try {
            source.open(DataSpec.Builder().setUri(uri).setPosition(position).setLength(length).build())
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = source.read(buffer, 0, buffer.size)
                if (n == C.RESULT_END_OF_INPUT) break
                out.write(buffer, 0, n)
            }
        } finally {
            source.close()
        }
        return out.toByteArray()
    }
}
