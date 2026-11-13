package io.skjaere.usenet.app.settings

import io.skjaere.usenet.data.ProviderRecord
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
 * The provider screen's Test (#74): a result shown is cleared when the form changes, a test still
 * running for older form values never shows its result, and a new Test cancels the running one.
 */
class ProviderTestRunTest {
    private val form = newProviderForm().copy(host = "news.example.net", username = "user", password = "hunter2")
    private val login = ProviderLogin.of(ProviderRecord(form.id, "", form.host, 563, "user"), "hunter2")
    private val ok = ProviderTestResult(login, ok = true, message = "Connected and logged in to news.example.net")
    private val failed = ProviderTestResult(login, ok = false, message = "The provider rejected the username or password.")

    /** Each test started, by the form it tests, answered when the test completes its deferred. */
    private val started = mutableListOf<Pair<ProviderForm, CompletableDeferred<ProviderTestResult>>>()
    private val recorded = mutableListOf<ProviderTestResult>()
    /** The tests that were cancelled, by number in [started]. */
    private val cancelled = mutableListOf<Int>()

    private suspend fun test(f: ProviderForm): ProviderTestResult {
        val answer = CompletableDeferred<ProviderTestResult>()
        val number = started.size
        started += f to answer
        try {
            // As a connection that doesn't notice it was given up on: it answers anyway.
            return withContext(NonCancellable) { answer.await() }
        } finally {
            if (!currentCoroutineContext().isActive) cancelled += number
        }
    }

    @Test
    fun `a result shows until the form changes`() = runTest {
        val run = ProviderTestRun(backgroundScope, ::test) { recorded += it }
        run.start(form)
        runCurrent()
        assertEquals(TestState.Running, run.state.value)
        started.single().second.complete(ok)
        runCurrent()
        assertEquals(TestState.Done(true, ok.message), run.state.value)
        assertEquals(listOf(ok), recorded)

        run.formChanged(form)
        assertEquals(TestState.Done(true, ok.message), run.state.value, "an edit that changes nothing keeps the result")
        run.formChanged(form.copy(port = "443"))
        assertEquals(TestState.Idle, run.state.value)
    }

    @Test
    fun `a test of older form values never shows its result`() = runTest {
        val run = ProviderTestRun(backgroundScope, ::test) { recorded += it }
        run.start(form)
        runCurrent()
        run.formChanged(form.copy(password = "hunter3"))
        assertEquals(TestState.Idle, run.state.value)
        started.single().second.complete(ok)
        runCurrent()
        assertEquals(TestState.Idle, run.state.value)
        assertEquals(emptyList(), recorded)
        assertEquals(listOf(0), cancelled)
    }

    @Test
    fun `a new test cancels the running one, whose result never shows`() = runTest {
        val run = ProviderTestRun(backgroundScope, ::test) { recorded += it }
        run.start(form)
        runCurrent()
        val changed = form.copy(host = "news2.example.net")
        run.formChanged(changed)
        run.start(changed)
        runCurrent()
        assertEquals(TestState.Running, run.state.value)
        // The second answers first, then the first, given up on, answers late.
        started[1].second.complete(failed)
        runCurrent()
        started[0].second.complete(ok)
        runCurrent()
        assertEquals(TestState.Done(false, failed.message), run.state.value)
        assertEquals(listOf(failed), recorded)
        assertEquals(listOf(0), cancelled)

        // Test again with nothing changed: the one running is given up on as well.
        run.start(changed)
        runCurrent()
        run.start(changed)
        runCurrent()
        started[3].second.complete(ok)
        runCurrent()
        started[2].second.complete(failed)
        runCurrent()
        assertEquals(TestState.Done(true, ok.message), run.state.value)
        assertEquals(listOf(failed, ok), recorded)
        assertEquals(listOf(0, 2), cancelled)
    }
}
