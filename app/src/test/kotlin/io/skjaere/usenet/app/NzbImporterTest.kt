package io.skjaere.usenet.app

import io.skjaere.usenet.app.settings.NoUsableProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class NzbImporterTest {
    private val a = "<nzb>A</nzb>".toByteArray()
    private val b = "<nzb>B</nzb>".toByteArray()

    /** What was prepared, as "title" or "title:password" with the bytes' text. */
    private val prepared = mutableListOf<String>()

    /** Every NZB is password protected; [prepareGate] holds a prepare until it completes. */
    private var prepareGate: CompletableDeferred<Unit>? = null

    private fun TestScope.importer() = NzbImporter(backgroundScope) { bytes, title, password ->
        prepared += listOfNotNull(title, bytes.decodeToString(), password).joinToString(":")
        prepareGate?.await()
        ImportUi.NeedsPassword(title, wrongPassword = password != null)
    }

    @Test
    fun `the screen shows the file is being opened while it is still read`() = runTest {
        val importer = importer()
        importer.open { awaitCancellation() }
        assertEquals(ImportUi.Working(null), importer.state.value)
    }

    @Test
    fun `picking another file while the first is still read drops the first`() = runTest {
        val importer = importer()
        val aRead = CompletableDeferred<Pair<ByteArray, String>>()
        importer.open { aRead.await() }
        runCurrent()
        importer.open { b to "B" }
        runCurrent()
        aRead.complete(a to "A")
        runCurrent()
        assertEquals(listOf("B:<nzb>B</nzb>"), prepared)
        assertEquals(ImportUi.NeedsPassword("B", wrongPassword = false), importer.state.value)
    }

    @Test
    fun `the password unlocks the file it was asked for, also when an earlier pick finishes later`() = runTest {
        val importer = importer()
        val aRead = CompletableDeferred<Pair<ByteArray, String>>()
        importer.open { aRead.await() }
        runCurrent()
        // B is read at once, then its prepare is slower than A's read.
        prepareGate = CompletableDeferred()
        val bPrepared = prepareGate!!
        importer.open { b to "B" }
        runCurrent()
        prepareGate = null
        aRead.complete(a to "A")
        runCurrent()
        bPrepared.complete(Unit)
        runCurrent()
        assertEquals(ImportUi.NeedsPassword("B", wrongPassword = false), importer.state.value)

        importer.unlock("pw")
        runCurrent()
        assertEquals("B:<nzb>B</nzb>:pw", prepared.last())
    }

    @Test
    fun `a read that finishes without noticing it was dropped isn't prepared`() = runTest {
        val importer = importer()
        val aRead = CompletableDeferred<Pair<ByteArray, String>>()
        importer.open { withContext(NonCancellable) { aRead.await() } }
        runCurrent()
        importer.open { b to "B" }
        runCurrent()
        aRead.complete(a to "A")
        runCurrent()
        assertEquals(listOf("B:<nzb>B</nzb>"), prepared)
        assertEquals(ImportUi.NeedsPassword("B", wrongPassword = false), importer.state.value)
    }

    /**
     * Opened before any provider is set up (another app's .nzb on a fresh install): the file waits
     * while Import offers to add one, and is prepared once there is one, without being read again.
     */
    @Test
    fun `a file opened without a provider is prepared once one is added`() = runTest {
        var hasProvider = false
        var reads = 0
        val importer = NzbImporter(backgroundScope) { bytes, title, password ->
            prepared += listOfNotNull(title, bytes.decodeToString(), password).joinToString(":")
            if (hasProvider) ImportUi.Error("prepared $title") else ImportUi.NeedsProvider(title, NoUsableProvider.NoneSaved)
        }
        importer.open { reads++; a to "A" }
        runCurrent()
        assertEquals(ImportUi.NeedsProvider("A", NoUsableProvider.NoneSaved), importer.state.value)

        hasProvider = true
        importer.retry()
        runCurrent()
        assertEquals(ImportUi.Error("prepared A"), importer.state.value)
        assertEquals(listOf("A:<nzb>A</nzb>", "A:<nzb>A</nzb>"), prepared)
        assertEquals(1, reads)

        // Only a file waiting for a provider is prepared again.
        importer.retry()
        runCurrent()
        assertEquals(2, prepared.size)
    }

    /**
     * The providers change while the file waits, and still none is usable: Import says what is wrong
     * now, not what was (S9-9), and opens the file once one is usable.
     */
    @Test
    fun `a file waiting for a provider says why none is usable as the providers change`() = runTest {
        var missing: NoUsableProvider? = NoUsableProvider.AllOff
        val importer = NzbImporter(backgroundScope) { bytes, title, password ->
            prepared += listOfNotNull(title, bytes.decodeToString(), password).joinToString(":")
            missing?.let { ImportUi.NeedsProvider(title, it) } ?: ImportUi.Error("prepared $title")
        }
        importer.open { a to "A" }
        runCurrent()
        assertEquals(ImportUi.NeedsProvider("A", NoUsableProvider.AllOff), importer.state.value)

        // Switched on, but its password can't be read here.
        missing = NoUsableProvider.Unreadable(1)
        importer.providersChanged(missing)
        runCurrent()
        assertEquals(ImportUi.NeedsProvider("A", NoUsableProvider.Unreadable(1)), importer.state.value)
        // Then deleted.
        missing = NoUsableProvider.NoneSaved
        importer.providersChanged(missing)
        runCurrent()
        assertEquals(ImportUi.NeedsProvider("A", NoUsableProvider.NoneSaved), importer.state.value)
        assertEquals(1, prepared.size)

        missing = null
        importer.providersChanged(null)
        runCurrent()
        assertEquals(ImportUi.Error("prepared A"), importer.state.value)
        assertEquals(2, prepared.size)

        // Nothing waits any more: a later change leaves what the screen shows alone.
        importer.providersChanged(NoUsableProvider.AllOff)
        runCurrent()
        assertEquals(ImportUi.Error("prepared A"), importer.state.value)
        assertEquals(2, prepared.size)
    }
}
