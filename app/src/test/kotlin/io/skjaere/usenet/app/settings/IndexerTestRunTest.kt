package io.skjaere.usenet.app.settings

import io.skjaere.usenet.newznab.IndexerCaps
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * The indexer screen's Test, as the provider screen's (#74): a result shown is cleared when the form changes, a test
 * still running for older form values never shows its result, and a new Test cancels the running one.
 */
class IndexerTestRunTest {
    private val form = newIndexerForm().copy(url = "https://indexer.example.org/api", apiKey = "KEY")
    private val ok = IndexerTest.Ok(IndexerCaps(serverTitle = "Example"), limits = null)

    /** Each test started, by the form it tests, answered when the test completes its deferred. */
    private val started = mutableListOf<Pair<IndexerForm, CompletableDeferred<IndexerTest>>>()
    /** The tests that were cancelled, by number in [started]. */
    private val cancelled = mutableListOf<Int>()

    private suspend fun test(f: IndexerForm): IndexerTest {
        val answer = CompletableDeferred<IndexerTest>()
        val number = started.size
        started += f to answer
        try {
            // As an HTTP call that doesn't notice it was given up on: it answers anyway.
            return withContext(NonCancellable) { answer.await() }
        } finally {
            if (!currentCoroutineContext().isActive) cancelled += number
        }
    }

    @Test
    fun `a result shows until the form changes`() = runTest {
        val run = IndexerTestRun(backgroundScope, ::test)
        run.start(form)
        runCurrent()
        assertEquals(IndexerTest.Running, run.state.value)
        started.single().second.complete(ok)
        runCurrent()
        assertEquals(ok, run.state.value)

        run.formChanged(form)
        assertEquals(ok, run.state.value, "an edit that changes nothing keeps the result")
        run.formChanged(form.copy(url = "https://indexer.example.org/v2/api"))
        assertEquals(IndexerTest.Idle, run.state.value)
    }

    /** The bug this fixes: a slow indexer's answer for the old address showed under the new one. */
    @Test
    fun `a test of older form values never shows its result`() = runTest {
        val run = IndexerTestRun(backgroundScope, ::test)
        run.start(form)
        runCurrent()
        run.formChanged(form.copy(apiKey = "OTHER"))
        assertEquals(IndexerTest.Idle, run.state.value)
        started.single().second.complete(ok)
        runCurrent()
        assertEquals(IndexerTest.Idle, run.state.value)
        assertEquals(listOf(0), cancelled)
    }

    /** Test pressed again for the same values: the first test is given up on, and its late answer never shows. */
    @Test
    fun `a new test cancels the running one, whose result never shows`() = runTest {
        val run = IndexerTestRun(backgroundScope, ::test)
        run.start(form)
        runCurrent()
        run.start(form)
        runCurrent()
        val failed = IndexerTest.Failed("No answer within 15 s")
        started[1].second.complete(failed)
        started[0].second.complete(ok)
        runCurrent()
        assertEquals(failed, run.state.value)
        assertEquals(listOf(0), cancelled)
    }

    @Test
    fun `a test started after the form changed shows its own result only`() = runTest {
        val run = IndexerTestRun(backgroundScope, ::test)
        run.start(form)
        runCurrent()
        val changed = form.copy(url = "https://other.example.org/api")
        run.formChanged(changed)
        run.start(changed)
        runCurrent()
        val failed = IndexerTest.Failed("No answer within 15 s")
        started[1].second.complete(failed)
        started[0].second.complete(ok)
        runCurrent()
        assertEquals(failed, run.state.value)
        assertEquals(listOf(changed), started.drop(1).map { it.first })
    }
}
