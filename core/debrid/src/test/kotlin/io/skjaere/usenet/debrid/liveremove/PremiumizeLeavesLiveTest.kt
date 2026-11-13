package io.skjaere.usenet.debrid.liveremove

import io.skjaere.usenet.debrid.ServiceHttp
import io.skjaere.usenet.debrid.live.LiveRun
import io.skjaere.usenet.debrid.premiumize.PremiumizeService
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * Whether a play's `directdl` leaves anything in your own Premiumize account (L235, C3), the lead's alone: enabled only
 * by `-PdebridLive=true`, which has the build put the key from local.properties (devPremiumizeKey) and the openly licensed
 * film's hash (`-PdebridLiveHash`) in the test's environment. [PremiumizeLeavesCheck]'s five real calls, within the run's
 * 25 ([CallBudget]). The report goes to build/reports/debridLive/premiumize-leaves.txt and the answer to d2-summary.txt
 * beside it. Kept out of the `live` package with TorBoxRemoveLiveTest, so it runs only when it is named.
 */
@EnabledIfSystemProperty(named = "debridLive", matches = "true")
class PremiumizeLeavesLiveTest {
    private val run = LiveRun("Premiumize", report = PremiumizeLeavesCheck.REPORT, title = "Premiumize live check for #318 (D2, L235)")

    @Test
    fun `what a directdl of the film leaves in the transfers and the space used`() {
        val key = run.key
        assumeTrue(key != null, "devPremiumizeKey isn't set in local.properties")
        val film = run.film
        assumeTrue(film != null, "no film's hash given (-PdebridLiveHash): nothing to ask about")
        val http = CallBudget.shared.guarding(run.recording(ServiceHttp.defaultHttpClient()))
        val check = PremiumizeLeavesCheck(run, key!!, film!!, http, PremiumizeService(key, http, log = run.lines::add))
        try {
            runBlocking { check.directdlLeaves() }
        } finally {
            try {
                run.write()
                LiveSummary.shared.put("Premiumize", "${PremiumizeLeavesCheck.REPORT}.txt", check.summary)
                LiveSummary.shared.write(run, CallBudget.shared)
            } finally {
                http.close()
            }
        }
    }
}
