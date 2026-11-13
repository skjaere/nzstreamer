package io.skjaere.usenet.app.debrid

import io.skjaere.usenet.app.ui.ListPlace
import io.skjaere.usenet.data.ServiceAccount
import io.skjaere.usenet.data.ServiceSignIn
import io.skjaere.usenet.data.ServiceWithKey
import io.skjaere.usenet.data.SettingsRepository
import io.skjaere.usenet.debrid.Account
import io.skjaere.usenet.debrid.DebridService
import io.skjaere.usenet.debrid.ServiceError
import io.skjaere.usenet.debrid.ServiceException
import io.skjaere.usenet.debrid.ServiceKind
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Settings › Debrid services' words and models (L196; DbPhoneServiceAdd, DbTvSignIn, DbTvSources 2-4), as pure functions
// the screens draw. A service is named only from its kind (W80): no screen's words name one of their own.

/** The list with no account yet (DbPhoneServiceAdd 1), over its Add a service button. */
const val NO_SERVICE_LINE =
    "No service yet. Sign in with your own debrid service account’s API key: torrents your torrent indexers find then play from its cache."

/** Under the empty list's button. */
const val SEARCHED_ONLY_WHILE_ON = "Torrent indexers are searched only while a service is on."

/** Under the list of accounts (D2PhoneServiceOrder): what the order does, and that the next is tried when the first fails. */
const val SERVICES_FOOTNOTE = "Torrent indexers are searched while a service is on. Each torrent is checked on every service that’s on, and plays " +
    "from the first one here that has it cached; if it fails there, the next is tried."

/** Add a service's line under its title (DbPhoneServiceAdd 2). */
const val PICKER_LINE = "Sign in to an account you already have, with its API key."

/** Add a service's first footnote: what goes to a service (L159). */
const val HASH_LINE = "Only a torrent’s ID (its info hash) goes to the service: never the torrent file, its trackers or which indexer found it."

/** Add a service's second footnote: one account, one place at a time; on a TV, from the TV's side. */
fun shareLine(television: Boolean): String =
    "Use the account from one place at a time. " +
        (if (television) "Your phone on mobile data while this TV plays at home" else "This phone on mobile data while your TV plays at home") +
        " counts as two places, and services may block an account for it."

/** The key form's phone row on a TV (DbTvSignIn 2): the easier way in, above the field. */
const val KEY_FORM_PHONE_ROW = "Easier: Set up with your phone"

/** Replace key's empty field (DbPhoneServiceAdd close-up 6). */
const val REPLACE_KEY_PLACEHOLDER = "Paste the new key"

/** The service's page's switch and what turning it off does (DbPhoneServiceAdd 6). */
const val USE_THIS_SERVICE = "Use this service"
const val USE_THIS_SERVICE_HELP = "Off: torrents aren’t checked on it, and you stay signed in."

/**
 * The service's page's second switch, under "Use this service" (D2PhoneServiceSwitch, L246, W83): what a play on this
 * device adds to the account goes a couple of minutes after the play stops, unless it is switched off. Words that name
 * no service, as only the kind decides where it shows ([removeAfterPlayingShown]).
 */
const val REMOVE_AFTER_PLAYING = "Remove after playing"
const val REMOVE_AFTER_PLAYING_HELP = "A torrent a play adds to your account is removed a couple of minutes after you stop watching."

/** Whether [account]'s page has Remove after playing: only for a service whose plays add the torrent to the account (L246). */
fun removeAfterPlayingShown(account: ServiceAccount): Boolean = kindOf(account)?.addsToAccount == true

/** What TalkBack reads of a switch on a service's page (L251): its words, then the line under them. */
fun serviceSwitchSpoken(label: String, detail: String): String = "$label, $detail"

/** An account whose key a device transfer left behind (L213): the secrets never travel. */
const val KEY_MISSING_LINE = "API key missing · sign in again"

/** A saved account's service, or null for one only a newer version knows. */
fun kindOf(account: ServiceAccount): ServiceKind? = ServiceRegistry.kindOf(account)

/** The service's name, as its kind gives it; a newer version's own name for one this version doesn't know. */
fun nameOf(account: ServiceAccount): String = kindOf(account)?.displayName ?: account.kind

/** The accounts in the user's order, as stored (L228), any only a newer version knows after them. */
fun servicesInOrder(saved: List<ServiceWithKey>): List<ServiceWithKey> = saved.sortedBy { kindOf(it.record) == null }

/** The services Add a service lists (DbPhoneServiceAdd 2): those not signed in to yet, A to Z, as plain names (L183). */
fun pickerKinds(signedIn: Set<ServiceKind>): List<ServiceKind> = ServiceKind.entries.filter { it !in signedIn }.sortedBy { it.displayName }

/** Whether the list offers Add: not once every service is signed in (one account per service). */
fun canAddService(saved: List<ServiceWithKey>): Boolean = pickerKinds(saved.mapNotNull { kindOf(it.record) }.toSet()).isNotEmpty()

private val shortDate = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
private val longDate = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)

private fun dateOf(epochMs: Long, zone: ZoneId, format: DateTimeFormatter): String = format.format(Instant.ofEpochMilli(epochMs).atZone(zone))

/** "TorBox refused your API key. Replace it." (DbPhoneServiceAdd close-up 5): the one line that names a service for a fault (W80). */
private fun refusedLine(account: ServiceAccount) = "${nameOf(account)} refused your API key. Replace it."

/**
 * An account's row in the list (DbPhoneServiceAdd 5): whose it is, then "off", else when its plan ends; a refused or
 * missing key says so instead, with the warning dot. A key saved without a test (a debug build's seed) names no account.
 */
fun serviceRowLine(saved: ServiceWithKey, zone: ZoneId = ZoneId.systemDefault()): HubLine {
    val record = saved.record
    return when {
        kindOf(record) == null -> HubLine("Not used by this version of the app")
        saved.apiKey == null -> HubLine(KEY_MISSING_LINE, warn = true)
        record.keyRefused -> HubLine(refusedLine(record), warn = true)
        else -> HubLine(listOfNotNull(record.accountName.ifBlank { "Key saved" }, if (!record.enabled) "off" else record.planUntil?.let { "until ${dateOf(it, zone, shortDate)}" }).joinToString(" · "))
    }
}

/** What TalkBack reads of an account's row (L214): its service, whose it is, and when its plan ends, the month in full. */
fun serviceRowSpoken(saved: ServiceWithKey, zone: ZoneId = ZoneId.systemDefault()): String {
    val record = saved.record
    val detail = when {
        kindOf(record) == null || saved.apiKey == null || record.keyRefused -> serviceRowLine(saved, zone).text.replace(" · ", ", ")
        else -> listOfNotNull(record.accountName.ifBlank { "Key saved" }, if (!record.enabled) "off" else record.planUntil?.let { "until ${dateOf(it, zone, longDate)}" }).joinToString(", ")
    }
    return "${nameOf(record)}, $detail"
}

/**
 * An account's row in the list (D2PhoneServiceOrder, L229), as Usenet providers' rows are: its [place] in the user's
 * order, its service's [name] (muted when [off]) and [line], and what TalkBack reads of it all ([spoken]).
 */
data class ServiceRow(val id: String, val name: String, val line: HubLine, val off: Boolean, val place: ListPlace, val spoken: String)

/** The list's rows, in the user's order ([servicesInOrder]): TalkBack reads each row's place after its words (L251). */
fun serviceRows(saved: List<ServiceWithKey>, zone: ZoneId = ZoneId.systemDefault()): List<ServiceRow> {
    val ordered = servicesInOrder(saved)
    return ordered.mapIndexed { i, account ->
        val place = ListPlace(i + 1, ordered.size)
        ServiceRow(
            account.record.id, nameOf(account.record), serviceRowLine(account, zone), off = !account.record.enabled, place,
            "${serviceRowSpoken(account, zone)}, position ${place.position} of ${place.count}",
        )
    }
}

/**
 * Settings' Debrid services row (DbPhoneSources close-up): "None yet", the services signed in to in the user's order
 * (D2PhoneServiceOrder's close-up) with "signed in" or how many are off, and a key refused or missing said with the
 * warning dot.
 */
fun debridServicesHubLine(saved: List<ServiceWithKey>): HubLine {
    if (saved.isEmpty()) return HubLine("None yet")
    val ordered = servicesInOrder(saved)
    val names = ordered.joinToString { nameOf(it.record) }
    ordered.firstOrNull { it.record.keyRefused || it.apiKey == null }?.let { troubled ->
        val what = if (troubled.record.keyRefused) "API key refused" else "API key missing"
        return HubLine(if (ordered.size == 1) "$names · $what" else "$names · ${nameOf(troubled.record)}’s $what", warn = true)
    }
    val off = ordered.count { !it.record.enabled }
    return HubLine(
        when {
            off == 0 -> "$names · signed in"
            ordered.size == 1 -> "$names · off"
            else -> "$names · $off off"
        },
    )
}

/** The key form's title (DbPhoneServiceAdd 3, close-up 6). */
fun keyFormTitle(kind: ServiceKind, replacing: Boolean): String =
    if (replacing) "Replace ${kind.displayName} key" else "Sign in to ${kind.displayName}"

/** Under the key form's buttons: what the key opens, and where it never goes. */
fun keyFootnote(kind: ServiceKind, television: Boolean): String =
    "The key opens your whole ${kind.displayName} account. It’s stored encrypted on this ${if (television) "TV" else "phone"}, and isn’t sent to " +
        "your ${if (television) "phones" else "TVs"}, put in a backup or written to the logs."

/** A passed Test (DbPhoneServiceAdd 3, 4): the account as the service names it, and when its plan ends where it says. */
fun signedInLine(account: Account, zone: ZoneId = ZoneId.systemDefault()): String =
    "Signed in as ${account.name}" + (account.planUntil?.let { " · plan until ${shortDate.format(it.atZone(zone))}" } ?: "")

/**
 * A failed Test or Save, by the service's answer: a refused key names the service (W80) and says what to check (close-up
 * 3); an account the service won't serve (Premiumize's `permission_denied`) isn't the key's fault (L198); a limit, its
 * wait; anything else, that the service didn't answer.
 */
fun keyFailure(kind: ServiceKind, error: ServiceError): String = when (error) {
    ServiceError.KeyRefused -> "${kind.displayName} refused this key. Check you copied all of it."
    is ServiceError.Refused -> "${kind.displayName} didn’t allow this account. Check it on ${kind.displayName}’s site."
    is ServiceError.Limit -> "The service’s limit was reached. " + (error.retryAfter?.let { "Try again in about ${minutes(it)}." } ?: "Try again later.")
    ServiceError.Down, ServiceError.NotCached, ServiceError.Removed -> "The service didn’t answer. Try again in a few minutes."
}

/** A wait in whole minutes, rounded up, at least one. */
private fun minutes(wait: Duration): String {
    val n = maxOf(1, (wait.seconds + 59) / 60)
    return if (n == 1L) "1 minute" else "$n minutes"
}

/** A key's Test or Save, as the key form and the service's page show it. */
sealed interface KeyTest {
    data object Idle : KeyTest
    data object Running : KeyTest
    data class Passed(val line: String) : KeyTest
    data class Failed(val line: String) : KeyTest
}

/** What an account keeps of the service's answer to its key. */
internal fun Account.signIn(): ServiceSignIn = ServiceSignIn(name, plan, planUntil?.toEpochMilli())

/** Signs in on [service] without saving anything (Test): its account's line, or the failure's words. */
internal suspend fun testKey(kind: ServiceKind, service: DebridService, zone: ZoneId = ZoneId.systemDefault()): KeyTest = try {
    KeyTest.Passed(signedInLine(service.test(), zone))
} catch (e: ServiceException) {
    KeyTest.Failed(keyFailure(kind, e.error))
}

/**
 * Save (DbPhoneServiceAdd 3): signs in with [key] on a client of its own ([trial]) and keeps the key only if the service
 * takes it, as a new account of [kind] or, [replacing] one, as its new key (the old one kept until then).
 */
internal suspend fun saveKey(
    settings: SettingsRepository,
    trial: (ServiceKind, String) -> DebridService,
    kind: ServiceKind,
    key: String,
    replacing: String?,
    zone: ZoneId = ZoneId.systemDefault(),
): KeyTest {
    var account: Account? = null
    val signIn: suspend (String) -> ServiceSignIn = { typed -> trial(kind, typed).test().also { account = it }.signIn() }
    return try {
        if (replacing == null) settings.saveService(kind.name, key, signIn) else settings.replaceKey(replacing, key, signIn)
        KeyTest.Passed(signedInLine(account!!, zone))
    } catch (e: ServiceException) {
        KeyTest.Failed(keyFailure(kind, e.error))
    }
}

/** The service's page's facts (DbPhoneServiceAdd 6): whose account, and its plan and when it ends, where the service said. */
fun accountFacts(account: ServiceAccount, zone: ZoneId = ZoneId.systemDefault()): List<Pair<String, String>> {
    val until = account.planUntil?.let { dateOf(it, zone, shortDate) }
    val plan = when {
        account.plan != null && until != null -> "${account.plan} · until $until"
        account.plan != null -> account.plan
        until != null -> "Until $until"
        else -> null
    }
    return listOfNotNull("Account" to account.accountName.ifBlank { "Not tested yet" }, plan?.let { "Plan" to it })
}

/** The service's page's footnote: its links stay in the play. */
fun linksLine(kind: ServiceKind): String =
    "Links ${kind.displayName} gives for a play are used while it plays, and never kept, shown or sent to another device."

/** Sign out's question and what it does (DbPhoneServiceAdd 7, DbTvSources 4). */
fun signOutQuestion(kind: ServiceKind): String = "Sign out of ${kind.displayName}?"

fun signOutText(kind: ServiceKind, television: Boolean): String =
    "The key is removed from this ${if (television) "TV" else "phone"}. Nothing plays from ${kind.displayName} here until you sign in again. " +
        "Your ${kind.displayName} account stays as it is."
