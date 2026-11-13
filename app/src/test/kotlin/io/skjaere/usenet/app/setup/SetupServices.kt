package io.skjaere.usenet.app.setup

import io.skjaere.usenet.debrid.Account
import io.skjaere.usenet.debrid.DebridService
import io.skjaere.usenet.debrid.InfoHash
import io.skjaere.usenet.debrid.Prepared
import io.skjaere.usenet.debrid.ServiceFile
import io.skjaere.usenet.debrid.ServiceKind
import io.skjaere.usenet.debrid.ServiceLink
import io.skjaere.usenet.debrid.ServiceListing
import java.time.Instant

/**
 * A debrid service for the setup page's tests: its Test answers [answer] for the key it was made for (an account, or a
 * thrown ServiceException). Nothing else is asked of a service from the page or the review.
 */
internal class SignInService(override val kind: ServiceKind, private val key: String, private val answer: (ServiceKind, String) -> Account) : DebridService {
    override suspend fun test(): Account = answer(kind, key)

    override suspend fun check(hashes: List<InfoHash>): Map<InfoHash, Boolean> = error("the setup page checks no torrent")

    override suspend fun prepare(hash: InfoHash, pick: suspend (ServiceListing) -> ServiceFile?): Prepared = error("the setup page prepares nothing")

    override suspend fun link(prepared: Prepared, file: ServiceFile): ServiceLink = error("the setup page asks for no link")
}

/** The invented account the setup tests' keys open (the boards' quay.viewer, at an example domain). */
internal val QUAY_ACCOUNT = Account("quay.viewer@example.invalid", "Standard", Instant.parse("2027-01-12T00:00:00Z"))
