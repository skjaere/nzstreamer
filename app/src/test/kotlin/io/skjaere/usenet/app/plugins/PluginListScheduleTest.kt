@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.skjaere.usenet.app.plugins

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * L620: when NZStreamer reads the plugin list. As the Plugins page opens, unless it was read in the last 5 minutes; once a
 * day while a plugin is installed, at the first resume 24 hours after the last attempt, whether that worked or not; and
 * never otherwise. A Retry-After holds every read, a day at most. The clock is the test's own.
 */
class PluginListScheduleTest {
    @TempDir
    lateinit var dir: File

    private var time = Instant.parse("2026-12-02T08:00:00Z").toEpochMilli()
    private var installed = false
    private val reader = FakeReader()

    private fun TestScope.list() = PluginList(
        source = { PluginSource.GITHUB },
        reader = { reader },
        cache = PluginListCache(File(dir, "list.json")),
        installed = { installed },
        scope = backgroundScope,
        io = StandardTestDispatcher(testScheduler),
        now = { time },
    )

    private fun pass(duration: Duration) {
        time += duration.toMillis()
    }

    @Test
    fun `the page reads the list at most every 5 minutes`() = runTest {
        val list = list()
        list.pageOpened().join()
        assertEquals(1, reader.reads)
        pass(Duration.ofMinutes(5).minusSeconds(1))
        list.pageOpened().join()
        assertEquals(1, reader.reads)
        pass(Duration.ofSeconds(1))
        list.pageOpened().join()
        assertEquals(2, reader.reads)
    }

    @Test
    fun `a page opened again after a read that failed reads again`() = runTest {
        val list = list()
        reader.answer = ListAnswer.Failed(ReadFailure.Network)
        list.pageOpened().join()
        pass(Duration.ofSeconds(10))
        list.pageOpened().join()
        assertEquals(2, reader.reads)
        assertTrue(list.state.value.failed)
    }

    @Test
    fun `Check again reads at once, the page's 5 minutes notwithstanding`() = runTest {
        val list = list()
        list.pageOpened().join()
        pass(Duration.ofSeconds(30))
        list.checkAgain().join()
        assertEquals(2, reader.reads)
    }

    @Test
    fun `the daily read is made only while a plugin is installed, 24 hours after the last attempt`() = runTest {
        val list = list()
        list.resumed().join()
        assertEquals(0, reader.reads, "no plugin installed")
        installed = true
        list.resumed().join()
        assertEquals(1, reader.reads, "never read before")
        pass(Duration.ofHours(24).minusSeconds(1))
        list.resumed().join()
        assertEquals(1, reader.reads)
        pass(Duration.ofSeconds(1))
        list.resumed().join()
        assertEquals(2, reader.reads)
        installed = false
        pass(Duration.ofDays(3))
        list.resumed().join()
        assertEquals(2, reader.reads, "the plugin removed")
    }

    @Test
    fun `the day is counted from the last attempt, the page's reads too`() = runTest {
        val list = list()
        installed = true
        list.pageOpened().join()
        pass(Duration.ofHours(23))
        list.resumed().join()
        assertEquals(1, reader.reads)
        pass(Duration.ofHours(1))
        list.resumed().join()
        assertEquals(2, reader.reads)
    }

    @Test
    fun `after a 5xx, a 429 or a network failure, resumes within the day ask nothing`() = runTest {
        listOf(ListAnswer.Busy(503, retryAfter = null), ListAnswer.Busy(429, retryAfter = null), ListAnswer.Failed(ReadFailure.Network)).forEach { failing ->
            File(dir, "list.json").delete()
            val list = list()
            installed = true
            reader.reads = 0
            reader.answer = failing
            list.resumed().join()
            assertEquals(1, reader.reads, "$failing")
            listOf(Duration.ofMinutes(1), Duration.ofHours(6), Duration.ofHours(18).minusMinutes(2)).forEach { gap ->
                pass(gap)
                list.resumed().join()
            }
            assertEquals(1, reader.reads, "$failing: within the day")
            pass(Duration.ofMinutes(1))
            list.resumed().join()
            assertEquals(2, reader.reads, "$failing: a day on")
        }
    }

    @Test
    fun `a Retry-After of a week holds every read for a day`() = runTest {
        val list = list()
        installed = true
        reader.answer = ListAnswer.Busy(429, retryAfter = Duration.ofDays(7))
        list.pageOpened().join()
        pass(Duration.ofHours(23))
        list.pageOpened().join()
        list.checkAgain().join()
        list.resumed().join()
        assertEquals(1, reader.reads)
        pass(Duration.ofHours(1))
        list.pageOpened().join()
        assertEquals(2, reader.reads)
    }

    @Test
    fun `a Retry-After taken after the clock went back holds no longer than a day from now`() = runTest {
        val list = list()
        reader.answer = ListAnswer.Busy(503, retryAfter = Duration.ofDays(1))
        list.pageOpened().join()
        time -= Duration.ofDays(30).toMillis()
        pass(Duration.ofHours(1))
        list.pageOpened().join()
        assertEquals(2, reader.reads, "a wait more than a day ahead isn't one GitHub asked for")
    }

    @Test
    fun `nothing reads the list otherwise`() = runTest {
        val list = list()
        installed = true
        runCurrent()
        pass(Duration.ofDays(10))
        runCurrent()
        assertEquals(0, reader.reads)
        assertEquals(null, list.state.value.readAt)
        assertFalse(File(dir, "list.json").exists(), "nothing written either")
    }

    @Test
    fun `two triggers at once make one read`() = runTest {
        val list = list()
        installed = true
        val gate = CompletableDeferred<Unit>()
        reader.before = { gate.await() }
        val page = list.pageOpened()
        val daily = list.resumed()
        runCurrent()
        assertTrue(list.state.value.checking, "Checking… while it reads")
        gate.complete(Unit)
        page.join()
        daily.join()
        assertEquals(1, reader.reads)
        assertFalse(list.state.value.checking)
    }

    @Test
    fun `the copy outlives the process, and a list older than it is ignored`() = runTest {
        reader.answer = ListAnswer.Fresh(list("2026-12-01T10:00:00Z", "harbor"), etag = "\"v1\"")
        list().pageOpened().join()
        val again = list()
        reader.answer = ListAnswer.Fresh(list("2026-11-30T10:00:00Z", "copper"), etag = "\"v0\"")
        pass(Duration.ofMinutes(5))
        again.pageOpened().join()
        assertEquals(listOf("harbor"), again.state.value.index?.plugins?.map { it.id })
        assertEquals(time, again.state.value.readAt)
        assertEquals("\"v1\"", reader.etags.last(), "the copy's ETag sent")
        pass(Duration.ofMinutes(5))
        again.pageOpened().join()
        assertEquals("\"v1\"", reader.etags.last(), "the older list's ETag not taken")
    }

    @Test
    fun `a list in a later format is kept and said so, a 404 empties the copy`() = runTest {
        val list = list()
        reader.answer = ListAnswer.Fresh("""{"format":2,"generated":"2026-12-03T10:00:00Z"}""".toByteArray(), etag = "\"v2\"")
        list.pageOpened().join()
        assertTrue(list.state.value.tooNew)
        assertEquals(null, list.state.value.index)
        reader.answer = ListAnswer.NoList
        pass(Duration.ofMinutes(5))
        list.pageOpened().join()
        assertFalse(list.state.value.tooNew)
        assertEquals(emptyList(), list.state.value.index?.plugins)
        pass(Duration.ofMinutes(5))
        list.pageOpened().join()
        assertEquals(null, reader.etags.last(), "no copy to name")
    }

    @Test
    fun `a list this build refuses is a failed read, and the copy stays`() = runTest {
        val list = list()
        reader.answer = ListAnswer.Fresh(list("2026-12-01T10:00:00Z", "harbor"), etag = "\"v1\"")
        list.pageOpened().join()
        reader.answer = ListAnswer.Fresh("not a list".toByteArray(), etag = "\"v2\"")
        pass(Duration.ofMinutes(5))
        list.pageOpened().join()
        assertTrue(list.state.value.failed)
        assertEquals(listOf("harbor"), list.state.value.index?.plugins?.map { it.id })
        pass(Duration.ofMinutes(5))
        list.pageOpened().join()
        assertEquals("\"v1\"", reader.etags.last())
    }

    /** A reader that counts its reads, keeps the ETags it was given, and gives [answer]: a list with nothing in it, unless set. */
    private class FakeReader : ListReader {
        var reads = 0
        val etags = mutableListOf<String?>()
        var answer: ListAnswer = ListAnswer.Fresh(list("2026-12-01T10:00:00Z"), etag = "\"v1\"")
        var before: suspend () -> Unit = {}

        override suspend fun read(etag: String?): ListAnswer {
            reads++
            etags += etag
            before()
            return answer
        }
    }

    private companion object {
        fun list(generated: String, vararg ids: String): ByteArray {
            val plugins = ids.joinToString(",") { id ->
                """{"id":"$id","package":"app.nzstreamer.plugin.$id","name":"Harbor Lights","summary":"","withdrawn":[],"versions":[]}"""
            }
            return """{"format":1,"generated":"$generated","plugins":[$plugins]}""".toByteArray()
        }
    }
}
