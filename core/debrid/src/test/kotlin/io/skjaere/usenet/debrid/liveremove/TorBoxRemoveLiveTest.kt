package io.skjaere.usenet.debrid.liveremove

import io.ktor.client.HttpClient
import io.skjaere.usenet.debrid.ServiceHttp
import io.skjaere.usenet.debrid.live.LiveRun
import io.skjaere.usenet.debrid.torbox.TorBoxService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * TorBox's answers #318 needs (L235, W91), with your own account, the lead's alone: enabled only by
 * `-PdebridLive=true` and `-PdebridLiveRemove=true` together (L253). The first has the build put the key from
 * local.properties (devTorBoxKey) and the openly licensed film's hash (`-PdebridLiveHash`) in the test's environment. The cases are [TorBoxRemoveCheck]'s, in order, each asked
 * only when the one before passed: `createtorrent` of the hash the list holds, a link opened before the delete, the
 * delete of D1's film entry alone (the only delete approved), then the link, the list and the cache after it.
 * Eleven real calls at most, within the run's 25 ([CallBudget]). The report goes to
 * build/reports/debridLive/torbox-remove.txt and the answers to d2-summary.txt beside it.
 *
 * It sends a delete, so `-PdebridLive=true` alone never runs it, with or without a `--tests` filter; it is also kept out
 * of the `live` package that D1's `--tests 'io.skjaere.usenet.debrid.live.*'` names. Run it once: its delete is the
 * film entry's, and a second run finds no entry to ask about.
 */
@EnabledIfSystemProperty(named = "debridLive", matches = "true")
@EnabledIfSystemProperty(named = "debridLiveRemove", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class TorBoxRemoveLiveTest {
    private val run = LiveRun("TorBox", report = TorBoxRemoveCheck.REPORT, title = "TorBox live check for #318 (D2, L235)")
    private var http: HttpClient? = null
    private lateinit var check: TorBoxRemoveCheck

    @BeforeAll
    fun setUp() {
        val key = run.key
        assumeTrue(key != null, "devTorBoxKey isn't set in local.properties")
        val film = run.film
        assumeTrue(film != null, "no film's hash given (-PdebridLiveHash): nothing to ask about")
        val answers = LastAnswers()
        val client = answers.install(CallBudget.shared.guarding(run.recording(ServiceHttp.defaultHttpClient()))).also { http = it }
        val links = LinkReader(counted = {
            CallBudget.shared.take()
            run.countCdnRead()
        })
        check = TorBoxRemoveCheck(run, key!!, film!!, client, TorBoxService(key, client, log = run.lines::add), links, answers)
    }

    @Test
    @Order(1)
    fun `1 createtorrent of the hash the account's list holds`() {
        runBlocking { check.createOnHeld() }
    }

    @Test
    @Order(2)
    fun `2 a link made and opened before the delete, its body left flowing`() {
        assumeTrue(check.created, "case 1 didn't pass: nothing more is asked")
        runBlocking { check.linkBeforeDelete() }
    }

    @Test
    @Order(3)
    fun `3 controltorrent delete of the film's entry alone`() {
        assumeTrue(check.linked, "case 2 didn't pass: no delete")
        runBlocking { check.deleteHeld() }
    }

    @Test
    @Order(4)
    fun `4 the flowing body, the link, the list and the cache after the delete`() {
        assumeTrue(check.deleteSent, "no delete was sent: nothing after it to see")
        runBlocking { check.afterDelete() }
    }

    @AfterAll
    fun tearDown() {
        if (!::check.isInitialized) return
        try {
            check.close()
            run.write()
            LiveSummary.shared.put("TorBox", "${TorBoxRemoveCheck.REPORT}.txt", check.summary)
            LiveSummary.shared.write(run, CallBudget.shared)
        } finally {
            http?.close()
        }
    }
}
