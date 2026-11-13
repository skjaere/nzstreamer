package io.skjaere.usenet.app.debrid

import io.skjaere.usenet.app.settings.memorySettings
import io.skjaere.usenet.app.ui.ListPlace
import io.skjaere.usenet.app.ui.moveLabel
import io.skjaere.usenet.app.ui.secretFieldSpoken
import io.skjaere.usenet.app.ui.showSpoken
import io.skjaere.usenet.data.ServiceAccount
import io.skjaere.usenet.data.ServiceWithKey
import io.skjaere.usenet.debrid.Account
import io.skjaere.usenet.debrid.DebridService
import io.skjaere.usenet.debrid.InfoHash
import io.skjaere.usenet.debrid.Prepared
import io.skjaere.usenet.debrid.ServiceError
import io.skjaere.usenet.debrid.ServiceException
import io.skjaere.usenet.debrid.ServiceFile
import io.skjaere.usenet.debrid.ServiceKind
import io.skjaere.usenet.debrid.ServiceLink
import io.skjaere.usenet.debrid.ServiceListing
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Settings › Debrid services' pages as their models give them (L196; DbPhoneServiceAdd, DbTvSignIn, DbTvSources 2-4): the
 * list's lines and order (L228), Add a service's services A to Z, the key form's lines per service, Test's words for each answer, Save
 * keeping nothing the service refused, the service's page and Sign out, a TV's words, and what TalkBack reads (L214). The
 * boards' invented accounts.
 */
class ServicePagesTest {
    private val zone = ZoneOffset.UTC
    private val until2027 = Instant.parse("2027-01-12T10:00:00Z")
    private val torBox = ServiceAccount("tb", "TorBox", accountName = "quay.viewer@example.org", plan = "Standard", planUntil = until2027.toEpochMilli())
    private val premiumize = ServiceAccount("pm", "Premiumize", accountName = "customer 900000042", planUntil = Instant.parse("2027-03-03T00:00:00Z").toEpochMilli())

    @Test
    fun `the list's lines, empty and with accounts`() {
        assertEquals(
            "No service yet. Sign in with your own debrid service account’s API key: torrents your torrent indexers find then play from its cache.",
            NO_SERVICE_LINE,
        )
        assertEquals("Torrent indexers are searched only while a service is on.", SEARCHED_ONLY_WHILE_ON)
        // D2PhoneServiceOrder's footnote: what the order does, and that the next service is tried when the first fails.
        assertEquals(
            "Torrent indexers are searched while a service is on. Each torrent is checked on every service that’s on, and plays from the first one " +
                "here that has it cached; if it fails there, the next is tried.",
            SERVICES_FOOTNOTE,
        )
        assertEquals(HubLine("quay.viewer@example.org · until 12 Jan 2027"), serviceRowLine(ServiceWithKey(torBox, "TB-KEY"), zone))
        assertEquals(HubLine("customer 900000042 · off"), serviceRowLine(ServiceWithKey(premiumize.copy(enabled = false), "PM-KEY"), zone))
        assertEquals(HubLine("customer 900000042"), serviceRowLine(ServiceWithKey(premiumize.copy(planUntil = null), "PM-KEY"), zone))
        // DbPhoneServiceAdd close-up 5: a key reset on the service's site.
        assertEquals(HubLine("TorBox refused your API key. Replace it.", warn = true), serviceRowLine(ServiceWithKey(torBox.copy(keyRefused = true), "TB-KEY"), zone))
        // L213: a device transfer left the key behind.
        assertEquals(HubLine("API key missing · sign in again", warn = true), serviceRowLine(ServiceWithKey(torBox, null), zone))
        // A key saved untested (a debug build's seed): no account named yet.
        assertEquals(HubLine("Key saved"), serviceRowLine(ServiceWithKey(torBox.copy(accountName = "", planUntil = null), "TB-KEY"), zone))
    }

    @Test
    fun `the list is in the stored order, and Add goes once every service is signed in`() {
        val both = listOf(ServiceWithKey(torBox, "TB-KEY"), ServiceWithKey(premiumize, "PM-KEY"))
        assertEquals(listOf("tb", "pm"), servicesInOrder(both).map { it.record.id })
        assertEquals(listOf("pm", "tb"), servicesInOrder(both.reversed()).map { it.record.id })
        assertFalse(canAddService(both))
        assertTrue(canAddService(both.take(1)))
        assertTrue(canAddService(emptyList()))
        // One only a newer version knows is listed after, wherever it is stored, and doesn't stand in for one of these.
        val later = ServiceWithKey(ServiceAccount("x", "AnotherService"), "K")
        assertEquals(listOf("tb", "pm", "x"), servicesInOrder(listOf(later) + both).map { it.record.id })
        assertTrue(canAddService(listOf(later)))
    }

    /**
     * D2PhoneServiceOrder, L229: each row has its place and the move arrows, as Usenet providers' rows have them (the
     * first can't go up, the last can't go down; one service alone, its place and no arrows), and TalkBack reads its
     * place after its line (L251).
     */
    @Test
    fun `each service's row has its place and its arrows, and TalkBack reads its place`() {
        val rows = serviceRows(listOf(ServiceWithKey(torBox, "TB-KEY"), ServiceWithKey(premiumize, "PM-KEY")), zone)
        assertEquals(listOf("tb", "pm"), rows.map { it.id })
        assertEquals(listOf("TorBox", "Premiumize"), rows.map { it.name })
        assertEquals(listOf(ListPlace(1, 2), ListPlace(2, 2)), rows.map { it.place })
        assertEquals(listOf(false to true, true to false), rows.map { it.place.canMoveUp to it.place.canMoveDown })
        assertEquals(listOf(HubLine("quay.viewer@example.org · until 12 Jan 2027"), HubLine("customer 900000042 · until 3 Mar 2027")), rows.map { it.line })
        assertEquals("TorBox, quay.viewer@example.org, until 12 January 2027, position 1 of 2", rows[0].spoken)
        assertEquals("Premiumize, customer 900000042, until 3 March 2027, position 2 of 2", rows[1].spoken)
        assertEquals("Move TorBox up", moveLabel("TorBox", up = true))
        assertEquals("Move Premiumize down", moveLabel("Premiumize", up = false))

        val one = serviceRows(listOf(ServiceWithKey(torBox, "TB-KEY")), zone).single()
        assertEquals(ListPlace(1, 1), one.place)
        assertFalse(one.place.canMoveUp || one.place.canMoveDown, "one service: no arrows")
        assertEquals("TorBox, quay.viewer@example.org, until 12 January 2027, position 1 of 1", one.spoken)

        // Switched off, it keeps its place, its name muted and its line saying so; a key refused keeps its warning.
        val off = serviceRows(listOf(ServiceWithKey(torBox.copy(enabled = false), "TB-KEY"), ServiceWithKey(premiumize.copy(keyRefused = true), "PM-KEY")), zone)
        assertEquals(listOf(true, false), off.map { it.off })
        assertEquals("TorBox, quay.viewer@example.org, off, position 1 of 2", off[0].spoken)
        assertEquals(HubLine("Premiumize refused your API key. Replace it.", warn = true), off[1].line)
        assertEquals("Premiumize, Premiumize refused your API key. Replace it., position 2 of 2", off[1].spoken)
    }

    /** DGT-1: a service's page is read again on every settings write (a move is one), and the same account reads as the same. */
    @Test
    fun `a service's page read twice the same is equal`() {
        assertEquals(ServiceRead(ServiceWithKey(torBox, "TB-KEY")), ServiceRead(ServiceWithKey(torBox, "TB-KEY")))
        assertEquals(ServiceRead(null), ServiceRead(null))
    }

    @Test
    fun `Settings' row says which services are signed in, and warns of a key refused or missing`() {
        assertEquals(HubLine("None yet"), debridServicesHubLine(emptyList()))
        assertEquals(HubLine("TorBox · signed in"), debridServicesHubLine(listOf(ServiceWithKey(torBox, "K"))))
        assertEquals(
            HubLine("TorBox, Premiumize · 1 off"),
            debridServicesHubLine(listOf(ServiceWithKey(torBox, "K"), ServiceWithKey(premiumize.copy(enabled = false), "K"))),
        )
        assertEquals(HubLine("Premiumize, TorBox · signed in"), debridServicesHubLine(listOf(ServiceWithKey(premiumize, "K"), ServiceWithKey(torBox, "K"))))
        assertEquals(HubLine("TorBox · off"), debridServicesHubLine(listOf(ServiceWithKey(torBox.copy(enabled = false), "K"))))
        assertEquals(HubLine("TorBox · API key refused", warn = true), debridServicesHubLine(listOf(ServiceWithKey(torBox.copy(keyRefused = true), "K"))))
        assertEquals(HubLine("TorBox · API key missing", warn = true), debridServicesHubLine(listOf(ServiceWithKey(torBox, null))))
        assertEquals(
            HubLine("TorBox, Premiumize · TorBox’s API key refused", warn = true),
            debridServicesHubLine(listOf(ServiceWithKey(torBox.copy(keyRefused = true), "K"), ServiceWithKey(premiumize, "K"))),
        )
    }

    /** D2PhoneServiceOrder's close-up: Settings' row names the services in the user's order, so "TorBox, Premiumize" after the move. */
    @Test
    fun `Settings' row names the services in the stored order`() {
        assertEquals(HubLine("TorBox, Premiumize · signed in"), debridServicesHubLine(listOf(ServiceWithKey(torBox, "K"), ServiceWithKey(premiumize, "K"))))
        assertEquals(HubLine("Premiumize, TorBox · signed in"), debridServicesHubLine(listOf(ServiceWithKey(premiumize, "K"), ServiceWithKey(torBox, "K"))))
    }

    @Test
    fun `Add a service lists the services not signed in yet, A to Z, as plain names`() {
        assertEquals(listOf(ServiceKind.Premiumize, ServiceKind.TorBox), pickerKinds(emptySet()))
        assertEquals(listOf("Premiumize", "TorBox"), pickerKinds(emptySet()).map { it.displayName })
        assertEquals(listOf(ServiceKind.Premiumize), pickerKinds(setOf(ServiceKind.TorBox)))
        assertEquals(emptyList(), pickerKinds(setOf(ServiceKind.TorBox, ServiceKind.Premiumize)))
        assertEquals("Sign in to an account you already have, with its API key.", PICKER_LINE)
        assertEquals("Only a torrent’s ID (its info hash) goes to the service: never the torrent file, its trackers or which indexer found it.", HASH_LINE)
        assertEquals(
            "Use the account from one place at a time. This phone on mobile data while your TV plays at home counts as two places, and services may block an account for it.",
            shareLine(television = false),
        )
        assertEquals(
            "Use the account from one place at a time. Your phone on mobile data while this TV plays at home counts as two places, and services may block an account for it.",
            shareLine(television = true),
        )
    }

    @Test
    fun `the key form says where each service keeps the key, and what the key opens`() {
        assertEquals("Sign in to TorBox", keyFormTitle(ServiceKind.TorBox, replacing = false))
        assertEquals("Sign in to Premiumize", keyFormTitle(ServiceKind.Premiumize, replacing = false))
        assertEquals("Replace TorBox key", keyFormTitle(ServiceKind.TorBox, replacing = true))
        assertEquals("In TorBox: Settings › API key", ServiceKind.TorBox.keyPlace)
        assertEquals("In Premiumize: Account › API key", ServiceKind.Premiumize.keyPlace)
        assertEquals("Paste the new key", REPLACE_KEY_PLACEHOLDER)
        assertEquals(
            "The key opens your whole TorBox account. It’s stored encrypted on this phone, and isn’t sent to your TVs, put in a backup or written to the logs.",
            keyFootnote(ServiceKind.TorBox, television = false),
        )
        assertEquals(
            "The key opens your whole Premiumize account. It’s stored encrypted on this phone, and isn’t sent to your TVs, put in a backup or written to the logs.",
            keyFootnote(ServiceKind.Premiumize, television = false),
        )
        assertEquals(
            "The key opens your whole TorBox account. It’s stored encrypted on this TV, and isn’t sent to your phones, put in a backup or written to the logs.",
            keyFootnote(ServiceKind.TorBox, television = true),
        )
        // DbTvSignIn 2: the phone row's title on the key form.
        assertEquals("Easier: Set up with your phone", KEY_FORM_PHONE_ROW)
    }

    @Test
    fun `Test's line names the account as the service does, and each failure in its own words`() {
        assertEquals("Signed in as quay.viewer@example.org · plan until 12 Jan 2027", signedInLine(Account("quay.viewer@example.org", "Standard", until2027), zone))
        assertEquals(
            "Signed in as customer 900000042 · plan until 3 Mar 2027",
            signedInLine(Account("customer 900000042", null, Instant.parse("2027-03-03T00:00:00Z")), zone),
        )
        assertEquals("Signed in as customer 900000042", signedInLine(Account("customer 900000042"), zone))
        assertEquals("TorBox refused this key. Check you copied all of it.", keyFailure(ServiceKind.TorBox, ServiceError.KeyRefused))
        assertEquals("Premiumize refused this key. Check you copied all of it.", keyFailure(ServiceKind.Premiumize, ServiceError.KeyRefused))
        // L198: authenticated but not allowed, so not the key's fault.
        assertEquals("Premiumize didn’t allow this account. Check it on Premiumize’s site.", keyFailure(ServiceKind.Premiumize, ServiceError.Refused()))
        assertEquals("The service’s limit was reached. Try again in about 20 minutes.", keyFailure(ServiceKind.TorBox, ServiceError.Limit(Duration.ofMinutes(20))))
        assertEquals("The service’s limit was reached. Try again in about 1 minute.", keyFailure(ServiceKind.TorBox, ServiceError.Limit(Duration.ofSeconds(30))))
        assertEquals("The service’s limit was reached. Try again later.", keyFailure(ServiceKind.TorBox, ServiceError.Limit(null)))
        listOf(ServiceError.Down, ServiceError.NotCached, ServiceError.Removed).forEach { error ->
            assertEquals("The service didn’t answer. Try again in a few minutes.", keyFailure(ServiceKind.TorBox, error))
        }
    }

    /** A service that takes [taken] alone, as a real one takes only its account's key. */
    private class KeyService(override val kind: ServiceKind, private val key: String, private val taken: String, private val refusal: ServiceError = ServiceError.KeyRefused) : DebridService {
        var tests = 0
        override suspend fun test(): Account {
            tests++
            if (key != taken) throw ServiceException(refusal)
            return Account("quay.viewer@example.org", "Standard", Instant.parse("2027-01-12T10:00:00Z"))
        }
        override suspend fun check(hashes: List<InfoHash>): Map<InfoHash, Boolean> = error("not asked")
        override suspend fun prepare(hash: InfoHash, pick: suspend (ServiceListing) -> ServiceFile?): Prepared = error("not asked")
        override suspend fun link(prepared: Prepared, file: ServiceFile): ServiceLink = error("not asked")
    }

    @Test
    fun `Test signs in without saving anything`() = runBlocking<Unit> {
        val settings = memorySettings()
        val service = KeyService(ServiceKind.TorBox, "TB-KEY", taken = "TB-KEY")
        assertEquals(KeyTest.Passed("Signed in as quay.viewer@example.org · plan until 12 Jan 2027"), testKey(ServiceKind.TorBox, service, zone))
        assertEquals(KeyTest.Failed("TorBox refused this key. Check you copied all of it."), testKey(ServiceKind.TorBox, KeyService(ServiceKind.TorBox, "WRONG", "TB-KEY"), zone))
        assertEquals(emptyList(), settings.services.first())
    }

    @Test
    fun `Save signs in first and keeps the key only if the service takes it`() = runBlocking<Unit> {
        val settings = memorySettings()
        val made = mutableListOf<KeyService>()
        val trial = { kind: ServiceKind, key: String -> KeyService(kind, key, taken = "TB-KEY").also { made += it } }
        assertEquals(
            KeyTest.Failed("TorBox refused this key. Check you copied all of it."),
            saveKey(settings, trial, ServiceKind.TorBox, " WRONG ", replacing = null, zone),
        )
        assertEquals(emptyList(), settings.settings.first().services, "nothing kept of a refused key")
        assertEquals(
            KeyTest.Passed("Signed in as quay.viewer@example.org · plan until 12 Jan 2027"),
            saveKey(settings, trial, ServiceKind.TorBox, " TB-KEY ", replacing = null, zone),
        )
        val saved = settings.servicesWithKeys().single()
        assertEquals("TB-KEY", saved.apiKey)
        assertEquals(ServiceAccount(saved.record.id, "TorBox", accountName = "quay.viewer@example.org", plan = "Standard", planUntil = until2027.toEpochMilli(), updatedAt = saved.record.updatedAt, keySavedAt = saved.record.updatedAt), saved.record)
        assertEquals(listOf(1, 1), made.map { it.tests }, "each Save signs in once, with the key as trimmed")

        // Replace key: the old one stays until the new one is taken.
        val replacing = { kind: ServiceKind, key: String -> KeyService(kind, key, taken = "TB-NEW") }
        assertEquals(KeyTest.Failed("TorBox refused this key. Check you copied all of it."), saveKey(settings, replacing, ServiceKind.TorBox, "TB-KEY", replacing = saved.record.id, zone))
        assertEquals("TB-KEY", settings.service(saved.record.id)?.apiKey)
        assertTrue(saveKey(settings, replacing, ServiceKind.TorBox, "TB-NEW", replacing = saved.record.id, zone) is KeyTest.Passed)
        assertEquals("TB-NEW", settings.service(saved.record.id)?.apiKey)
        assertEquals(1, settings.services.first().size)

        // An account the service didn't allow is never saved either.
        val restricted = { kind: ServiceKind, key: String -> KeyService(kind, key, taken = "nothing", refusal = ServiceError.Refused()) }
        assertEquals(
            KeyTest.Failed("Premiumize didn’t allow this account. Check it on Premiumize’s site."),
            saveKey(settings, restricted, ServiceKind.Premiumize, "PM-KEY", replacing = null, zone),
        )
        assertEquals(listOf("TorBox"), settings.services.first().map { it.kind })
    }

    @Test
    fun `the service's page gives the account, the plan, the switch, the links line and Sign out's question`() {
        assertEquals(listOf("Account" to "quay.viewer@example.org", "Plan" to "Standard · until 12 Jan 2027"), accountFacts(torBox, zone))
        assertEquals(listOf("Account" to "customer 900000042", "Plan" to "Until 3 Mar 2027"), accountFacts(premiumize, zone))
        assertEquals(listOf("Account" to "customer 900000042"), accountFacts(premiumize.copy(planUntil = null), zone))
        assertEquals(listOf("Account" to "Not tested yet"), accountFacts(torBox.copy(accountName = "", plan = null, planUntil = null), zone))
        assertEquals("Use this service", USE_THIS_SERVICE)
        assertEquals("Off: torrents aren’t checked on it, and you stay signed in.", USE_THIS_SERVICE_HELP)
        assertEquals("Links TorBox gives for a play are used while it plays, and never kept, shown or sent to another device.", linksLine(ServiceKind.TorBox))
        assertEquals("Sign out of TorBox?", signOutQuestion(ServiceKind.TorBox))
        assertEquals(
            "The key is removed from this phone. Nothing plays from TorBox here until you sign in again. Your TorBox account stays as it is.",
            signOutText(ServiceKind.TorBox, television = false),
        )
        assertEquals(
            "The key is removed from this TV. Nothing plays from Premiumize here until you sign in again. Your Premiumize account stays as it is.",
            signOutText(ServiceKind.Premiumize, television = true),
        )
    }

    /**
     * D2PhoneServiceSwitch, D2TvServiceSwitch (L246, W83): "Remove after playing" under "Use this service" only on the page
     * of a service whose plays add the torrent to the account (TorBox's; not Premiumize's, nor one only a newer version
     * knows), in words that name no service, and on until it is switched off.
     */
    @Test
    fun `Remove after playing is on TorBox's page alone, in words naming no service, on until switched off`() {
        assertTrue(removeAfterPlayingShown(torBox))
        assertFalse(removeAfterPlayingShown(premiumize))
        assertFalse(removeAfterPlayingShown(ServiceAccount("x", "AnotherService")))
        assertEquals("Remove after playing", REMOVE_AFTER_PLAYING)
        assertEquals("A torrent a play adds to your account is removed a couple of minutes after you stop watching.", REMOVE_AFTER_PLAYING_HELP)
        ServiceKind.entries.forEach { kind ->
            assertFalse(kind.displayName in REMOVE_AFTER_PLAYING || kind.displayName in REMOVE_AFTER_PLAYING_HELP, "names ${kind.displayName}")
        }
        assertTrue(torBox.removeAfterPlay, "on as saved")
        assertFalse(removeAfterPlayingShown(torBox.copy(kind = "Premiumize")), "the kind decides, not the account")
    }

    /** L251: TalkBack reads each of the service's page's switches as its words, then the line under them. */
    @Test
    fun `what TalkBack reads of the service's page's switches`() {
        assertEquals(
            "Remove after playing, A torrent a play adds to your account is removed a couple of minutes after you stop watching.",
            serviceSwitchSpoken(REMOVE_AFTER_PLAYING, REMOVE_AFTER_PLAYING_HELP),
        )
        assertEquals("Use this service, Off: torrents aren’t checked on it, and you stay signed in.", serviceSwitchSpoken(USE_THIS_SERVICE, USE_THIS_SERVICE_HELP))
    }

    /** L214: the key field, its Show, and the service's row, as TalkBack reads them. */
    @Test
    fun `what TalkBack reads of the key field, its Show and a service's row`() {
        assertEquals("API key, hidden", secretFieldSpoken("API key", revealed = false))
        assertEquals("API key", secretFieldSpoken("API key", revealed = true))
        assertEquals("Show API key", showSpoken("API key", revealed = false))
        assertEquals("Hide API key", showSpoken("API key", revealed = true))
        assertEquals("TorBox, quay.viewer@example.org, until 12 January 2027", serviceRowSpoken(ServiceWithKey(torBox, "K"), zone))
        assertEquals("Premiumize, customer 900000042, off", serviceRowSpoken(ServiceWithKey(premiumize.copy(enabled = false), "K"), zone))
        assertEquals("TorBox, TorBox refused your API key. Replace it.", serviceRowSpoken(ServiceWithKey(torBox.copy(keyRefused = true), "K"), zone))
    }
}
