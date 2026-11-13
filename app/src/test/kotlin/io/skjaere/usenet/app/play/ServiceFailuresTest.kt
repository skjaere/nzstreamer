package io.skjaere.usenet.app.play

import io.skjaere.usenet.app.link.SheetMirror
import io.skjaere.usenet.core.AttemptFailure
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A debrid service's failures in words that name no service (L204, W80; DbPhoneFailures' short list): what the failure
 * sheet's cards and the log say ([AttemptFailure.text]), what the notice says as the play moves on ([shortFailure],
 * [retryNotice]), and what a paired phone is told of a TV's ([SheetMirror.failure]). Only a refused key names the
 * account's service, by its kind's name.
 */
class ServiceFailuresTest {
    private val route = "service:acct7f3e"

    private val failures = listOf(
        AttemptFailure.NoLongerCached,
        AttemptFailure.RemovedByService,
        AttemptFailure.ServiceLimit(20.minutes, route),
        AttemptFailure.ServiceLimit(null, route),
        AttemptFailure.ServiceRefused(tooLarge = true),
        AttemptFailure.ServiceRefused(tooLarge = false),
        AttemptFailure.KeyRefused("TorBox", route),
        AttemptFailure.ServiceDown(route),
        AttemptFailure.NoWorkingLink,
    )

    @Test
    fun `each failure's words for the failure sheet and the log`() {
        assertEquals(
            listOf(
                "No longer cached",
                "Removed by the debrid service",
                "The debrid service’s limit was reached · try again in about 20 min",
                "The debrid service’s limit was reached · try again later",
                "Too large for your debrid plan",
                "The debrid service won’t play this release",
                "TorBox refused your API key",
                "The debrid service stopped answering",
                "No longer available from the service",
            ),
            failures.map { it.text },
        )
        // The wait as the service gave it, never under a minute, in hours past one.
        assertEquals("The debrid service’s limit was reached · try again in about 1 min", AttemptFailure.ServiceLimit(20.seconds, route).text)
        assertEquals("The debrid service’s limit was reached · try again in about 2 h", AttemptFailure.ServiceLimit(110.minutes, route).text)
    }

    /**
     * As the play moves on, the notice names the release's failure briefly: a limit without its wait, as the next release
     * is tried at once (DbTvPlaySheet 2); the rest in their own words.
     */
    @Test
    fun `the notice says the failure briefly as the next release is tried`() {
        assertEquals(
            listOf(
                "No longer cached", "Removed by the debrid service", "The debrid service’s limit was reached", "The debrid service’s limit was reached",
                "Too large for your debrid plan", "The debrid service won’t play this release", "TorBox refused your API key",
                "The debrid service stopped answering", "No longer available from the service",
            ),
            failures.map { shortFailure(it) },
        )
        assertEquals("The debrid service’s limit was reached · trying release 2 of 3", retryNotice(AttemptFailure.ServiceLimit(20.minutes, route), 2, 3, cast = null))
        assertEquals("No longer cached · trying release 3 of 3", retryNotice(AttemptFailure.NoLongerCached, 3, 3, cast = null))
    }

    /** A paired phone is told a TV's failures in the TV's words (L37): none names the service, nor holds a release's. */
    @Test
    fun `a phone is told a TV's service failures in the TV's words`() {
        assertEquals(
            listOf(
                "No longer cached on the TV's debrid service",
                "Removed by the TV's debrid service",
                "The TV's debrid service's limit was reached",
                "The TV's debrid service's limit was reached",
                "Too large for the TV's debrid plan",
                "The TV's debrid service won't play this release",
                "The TV's debrid service refused its API key",
                "The TV's debrid service stopped answering",
                "No longer available from the TV's debrid service",
            ),
            failures.map { SheetMirror.failure(it) },
        )
    }

    /** Of the failures' words, only a refused key's names a service, the account's, from its kind; none holds the account's route. */
    @Test
    fun `only a refused key names the service`() {
        failures.forEach { failure ->
            val said = listOf(failure.text, shortFailure(failure), SheetMirror.failure(failure))
            said.forEach { words ->
                assertFalse("acct7f3e" in words, words)
                assertFalse("Premiumize" in words, words)
                assertEquals(failure is AttemptFailure.KeyRefused && words != SheetMirror.failure(failure), "TorBox" in words, words)
            }
        }
        assertEquals("Premiumize refused your API key", AttemptFailure.KeyRefused("Premiumize", route).text)
    }

    /**
     * A play's notice after releases it skipped as a service failed them before says so in its own words, not as found
     * incomplete: "no longer cached" only of those that are (DEP-8), the rest as the debrid service having failed them.
     */
    @Test
    fun `releases skipped as a service failed them before are said so`() {
        assertEquals("Skipped 1 release no longer cached", skippedNotice(listOf("No longer cached")))
        assertEquals("Skipped 2 releases no longer cached", skippedNotice(listOf("No longer cached", "No longer cached")))
        assertEquals("Skipped 2 releases the debrid service failed before", skippedNotice(listOf("No longer cached", "Removed by the debrid service")))
        assertEquals("Skipped 1 release the debrid service failed before", skippedNotice(listOf("Too large for your debrid plan")))
        assertEquals("Skipped 2 releases found incomplete or no longer cached before", skippedNotice(listOf("Skipped: missing on your providers (checked today)", "No longer cached")))
        assertEquals(
            "Skipped 2 releases found incomplete or that the debrid service failed before",
            skippedNotice(listOf("Skipped: missing on your providers (checked today)", "No longer available from the service")),
        )
        // Usenet's as before.
        assertEquals("Skipped 1 release found incomplete before", skippedNotice(listOf("Skipped: missing on your providers (checked today)")))
        assertEquals("Skipped 1 release that needed a password before", skippedNotice(listOf("Skipped: needs a password (checked today)")))
    }
}
