package io.skjaere.usenet.app.setup

import io.skjaere.usenet.app.debrid.keyFailure
import io.skjaere.usenet.app.debrid.signIn
import io.skjaere.usenet.app.debrid.signedInLine
import io.skjaere.usenet.app.settings.IndexerTest
import io.skjaere.usenet.app.settings.ProviderForm
import io.skjaere.usenet.app.settings.ProviderLogin
import io.skjaere.usenet.app.settings.ProviderTestResult
import io.skjaere.usenet.app.settings.indexerConnected
import io.skjaere.usenet.app.settings.tmdbKeyCheck
import io.skjaere.usenet.data.IndexerWithKey
import io.skjaere.usenet.data.ProviderWithPassword
import io.skjaere.usenet.data.ServiceSignIn
import io.skjaere.usenet.data.ServiceWithKey
import io.skjaere.usenet.data.SettingsRepository
import io.skjaere.usenet.data.TorrentIndexerWithKey
import io.skjaere.usenet.debrid.DebridService
import io.skjaere.usenet.debrid.ServiceException
import io.skjaere.usenet.debrid.ServiceKind
import io.skjaere.usenet.handoff.SetupOutcome
import io.skjaere.usenet.handoff.SetupPage
import io.skjaere.usenet.handoff.SetupResult
import io.skjaere.usenet.handoff.SetupTestItem
import io.skjaere.usenet.handoff.SetupTester
import io.skjaere.usenet.metadata.KeyCheck
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.IndexerKind
import io.skjaere.usenet.newznab.UrlSecurity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.future
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future

/** Why a card with a login is not tested from the phone's page without its password (after "Not tested: "): none of its own. */
const val PHONE_TEST_NO_PASSWORD = "type the password to test it here."

/** The same for an indexer without its API key. */
const val PHONE_TEST_NO_KEY = "type the API key to test it here."

/** A saved provider's card whose server changed: its saved password stays with the server it was saved for. */
const val PHONE_TEST_SERVER_CHANGED = "the server changed, so type the password to test it here."

/** The same for a saved indexer's card whose address changed. */
const val PHONE_TEST_ADDRESS_CHANGED = "the address changed, so type the API key to test it here."

/** A saved debrid account's card whose service changed: its saved key stays with the service it was saved for. */
const val PHONE_TEST_SERVICE_CHANGED = "the service changed, so type its API key to test it here."

/** A saved provider's password this TV can't read (after a move to a new device, say). */
const val PHONE_TEST_PASSWORD_UNREADABLE = "the password saved on the TV can't be read: type it."

/** The same for a saved indexer's API key. */
const val PHONE_TEST_KEY_UNREADABLE = "the API key saved on the TV can't be read: type it."

/** The TMDB key saved on the TV, asked for by the wizard, can't be read. */
const val PHONE_TEST_TMDB_UNREADABLE = "the key saved on the TV can't be read: type a new one."

/**
 * Why an indexer at a plain http address over the internet is not tested until the phone chooses Test anyway: the page
 * warns of it in its own words.
 */
const val PHONE_TEST_PUBLIC_HTTP = "the address is plain http over the internet, where your API key would be readable on its way."

/** An item whose test from the phone ran past [PhoneTester]'s limit. */
const val PHONE_TEST_UNFINISHED = "The test didn't finish in time."

/** A provider sent without a host, which its form can't test either. */
const val PHONE_TEST_NO_HOST = "No host: type the server's name."

/** An indexer sent without its API URL. */
const val PHONE_TEST_NO_URL = "No API URL: type the indexer's address."

/**
 * What a test from the phone may read of what is saved: the one record the TV bound the card to, by its id, read when
 * the test runs (a record changed on the TV meanwhile is compared as it is now), and whether its key is stored, readable
 * or not. Reads only.
 */
class SavedLookup(
    val provider: suspend (String) -> ProviderWithPassword?,
    val indexer: suspend (String) -> IndexerWithKey?,
    /** Whether a key is stored for an indexer, readable or not. */
    val keyStored: suspend (String) -> Boolean,
    val tmdbKey: suspend () -> String?,
    val service: suspend (String) -> ServiceWithKey? = { null },
    val serviceKeyStored: suspend (String) -> Boolean = { false },
    val torrentIndexer: suspend (String) -> TorrentIndexerWithKey? = { null },
    val torrentIndexerKeyStored: suspend (String) -> Boolean = { false },
) {
    companion object {
        fun of(settings: SettingsRepository) = SavedLookup(
            settings::provider, settings::indexer, settings::indexerKeyStored, settings::tmdbKey,
            settings::service, settings::serviceKeyStored, settings::torrentIndexer, settings::torrentIndexerKeyStored,
        )
    }
}

/**
 * The tests from the phone that passed this session, by exactly what each tested: a provider's login and connections, an
 * indexer's or a torrent indexer's address and key, a debrid service and key, a TMDB key, the secrets as digests. The
 * TV's review takes an item whose values it would save are here as tested, and Save doesn't test it again; a debrid
 * account's is kept with what the service said of it, which Save keeps with the key. In memory only, with the session
 * and its review.
 */
class PassLog {
    private val providers = ConcurrentHashMap<Pair<ProviderLogin, Int>, ProviderTestResult>()
    private val indexers = ConcurrentHashMap<Pair<String, String>, String>()
    private val torrentIndexers = ConcurrentHashMap<Pair<String, String>, String>()
    private val services = ConcurrentHashMap<Pair<ServiceKind, String>, ServiceSignIn>()
    private val tmdbKeys = ConcurrentHashMap.newKeySet<String>()

    /** How the provider that logs in with [login] and [connections] passed, or null when it didn't with exactly those. */
    fun provider(login: ProviderLogin, connections: Int): ProviderTestResult? = providers[login to connections]

    /** What the indexer at [url] with [key] passed with ("Connected · …"), or null. */
    fun indexer(url: String, key: String): String? = indexers[url.trim() to digest(key.trim())]

    fun tmdb(key: String): Boolean = digest(key.trim()) in tmdbKeys

    /** What the torrent indexer at [url] with [key] passed with, or null: an indexer's pass is no torrent indexer's. */
    fun torrentIndexer(url: String, key: String): String? = torrentIndexers[url.trim() to digest(key.trim())]

    /** What [kind]'s service said of the account [key] opens, when its test passed, or null. */
    fun service(kind: ServiceKind, key: String): ServiceSignIn? = services[kind to digest(key.trim())]

    internal fun passed(login: ProviderLogin, connections: Int, result: ProviderTestResult) = providers.put(login to connections, result)

    internal fun passed(url: String, key: String, words: String) = indexers.put(url.trim() to digest(key.trim()), words)

    internal fun passed(tmdbKey: String) = tmdbKeys.add(digest(tmdbKey.trim()))

    internal fun passedTorrentIndexer(url: String, key: String, words: String) = torrentIndexers.put(url.trim() to digest(key.trim()), words)

    internal fun passed(kind: ServiceKind, key: String, signIn: ServiceSignIn) = services.put(kind to digest(key.trim()), signIn)

    // The counts alone: addresses may hold a token, and the digests are of secrets.
    override fun toString() =
        "PassLog(providers=${providers.size}, indexers=${indexers.size}, torrentIndexers=${torrentIndexers.size}, services=${services.size}, tmdb=${tmdbKeys.size})"

    private fun digest(secret: String): String =
        MessageDigest.getInstance("SHA-256").digest(secret.encodeToByteArray()).joinToString("") { "%02x".format(it) }
}

/**
 * The tests the phone's page asks for with a card's Test: the card tested as its form tests it, a card at a time (the
 * server runs one at a time), saving nothing and writing no record of a provider's test. A password or key left empty
 * is the card's saved one, read from [saved] for the record the TV bound the card to, and used only under
 * [SavedSecretRule] (the same server, or a debrid account's own service); a new card has none. An indexer or a torrent
 * indexer at a plain http address over the internet waits for Test anyway. A debrid service card signs in as its key
 * form's Test does, on a client of its own ([trialService]), so no saved account is marked by what it answers. Each
 * pass goes into [passes]. The work runs in [scope], the session's, so whatever ends the session stops it; [test] is
 * called on the server's thread and only starts it.
 */
class PhoneTester(
    private val scope: CoroutineScope,
    private val saved: SavedLookup,
    private val testProvider: suspend (ProviderForm, String) -> ProviderTestResult,
    private val testIndexer: suspend (IndexerConfig) -> IndexerTest,
    private val validateTmdbKey: suspend (String) -> KeyCheck,
    private val passes: PassLog,
    /** A client for a key not saved: a debrid service card's test signs in on it, marking no account. */
    private val trialService: (ServiceKind, String) -> DebridService = { _, _ -> error("no debrid services") },
    /** How long a test may take, inside the server's wait for it. */
    private val limitMillis: Long = LIMIT_MS,
) : SetupTester {
    override fun test(item: SetupTestItem): Future<SetupResult> = scope.future {
        val result = bounded {
            when (item) {
                is SetupTestItem.Provider -> provider(item)
                is SetupTestItem.Indexer -> indexer(item)
                is SetupTestItem.Tmdb -> tmdb(item.key)
                is SetupTestItem.Service -> service(item)
                is SetupTestItem.TorrentIndexer -> torrentIndexer(item)
            }
        }
        log.info("phone setup: a test from the phone ({} {})", item.slot.label, outcome(result.outcome))
        result
    }

    private suspend fun bounded(test: suspend () -> SetupResult): SetupResult = withTimeoutOrNull(limitMillis) {
        try {
            test()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // What it says isn't shown: it may hold anything.
            SetupResult(SetupOutcome.Failed, SetupPage.COULDNT_RUN)
        }
    } ?: SetupResult(SetupOutcome.Failed, PHONE_TEST_UNFINISHED)

    /** The provider form's checks, then its Test: with no username, without a login, as the form tests. */
    private suspend fun provider(item: SetupTestItem.Provider): SetupResult {
        val draft = item.draft
        val form = ProviderForm(
            id = THROWAWAY, name = draft.name, host = draft.host, port = draft.portOrDefault.toString(), username = draft.username,
            password = draft.password, useTls = draft.useTls, connections = draft.connectionsOrDefault.toString(),
        )
        val problem = when {
            form.host.isBlank() -> PHONE_TEST_NO_HOST
            form.hostProblem != null -> form.hostProblem
            form.portValue == null -> PROVIDER_PORT_RANGE
            form.connectionsValue == null -> PROVIDER_CONNECTIONS_RANGE
            else -> null
        }
        if (problem != null) return SetupResult(SetupOutcome.Failed, problem)
        val password = when {
            draft.password.isNotEmpty() -> draft.password
            draft.username.isBlank() -> ""
            else -> when (val use = SavedSecretRule.password(item.ref?.let { saved.provider(it) }, draft)) {
                is SavedUse.Use -> use.secret
                is SavedUse.No -> return SetupResult(SetupOutcome.NotTested, words(use.why, provider = true))
            }
        }
        val result = testProvider(form, password)
        if (result.ok) passes.passed(ProviderLogin.of(form.host, draft.portOrDefault, form.username, form.useTls, password), draft.connectionsOrDefault, result)
        return SetupResult(if (result.ok) SetupOutcome.Passed else SetupOutcome.Failed, result.message)
    }

    /**
     * The indexer form's checks, then its Test, under an id no saved indexer has: the caps it forgets are no saved one's.
     * A saved card shown as it was is tested at its saved address whole. Not over plain http on the internet until the
     * phone, warned, chooses Test anyway.
     */
    private suspend fun indexer(item: SetupTestItem.Indexer): SetupResult {
        val record = item.ref?.let { saved.indexer(it) }
        return anIndexer(item.draft.name, item.draft.url, item.draft.apiKey, item.plainHttp, record?.record?.baseUrl, IndexerKind.Newznab, passes::passed) {
            SavedSecretRule.key(record, item.ref?.let { saved.keyStored(it) } == true, item.draft.url)
        }
    }

    /** A torrent indexer card (L209): its own form's Test, a torrent indexer's, under the same rules as an indexer's. */
    private suspend fun torrentIndexer(item: SetupTestItem.TorrentIndexer): SetupResult {
        val record = item.ref?.let { saved.torrentIndexer(it) }
        return anIndexer(item.draft.name, item.draft.url, item.draft.key, item.plainHttp, record?.record?.baseUrl, IndexerKind.Torznab, passes::passedTorrentIndexer) {
            SavedSecretRule.torrentIndexerKey(record, item.ref?.let { saved.torrentIndexerKeyStored(it) } == true, item.draft.url)
        }
    }

    /**
     * An indexer's or a torrent indexer's ([kind]) test, as its form tests it: its address the saved one ([savedUrl])
     * whole while the card shows it, its key typed or the saved one under [savedKey]'s rule. A torrent indexer's Test
     * searches as a torrent indexer, so a feed of NZBs is said to be one in that form's words.
     */
    private suspend fun anIndexer(
        name: String,
        sent: String,
        typedKey: String,
        plainHttp: Boolean,
        savedUrl: String?,
        kind: IndexerKind,
        passed: (String, String, String) -> Any?,
        savedKey: suspend () -> SavedUse,
    ): SetupResult {
        val url = savedUrl?.let { SavedSecretRule.indexerAddress(it, sent) } ?: sent.trim()
        when {
            url.isEmpty() -> return SetupResult(SetupOutcome.Failed, PHONE_TEST_NO_URL)
            UrlSecurity.of(url) == UrlSecurity.Invalid -> return SetupResult(SetupOutcome.Failed, INDEXER_URL_SCHEME)
            UrlSecurity.of(url) == UrlSecurity.PublicHttp && !plainHttp -> return SetupResult(SetupOutcome.NotTested, PHONE_TEST_PUBLIC_HTTP, plainHttp = true)
        }
        val key = typedKey.trim().ifEmpty {
            when (val use = savedKey()) {
                is SavedUse.Use -> use.secret
                is SavedUse.No -> return SetupResult(SetupOutcome.NotTested, words(use.why, provider = false))
            }
        }
        return when (val result = testIndexer(IndexerConfig("phone-test-${UUID.randomUUID()}", name, url, key, kind = kind))) {
            is IndexerTest.Ok -> indexerConnected(result).let { words ->
                passed(url, key, words)
                SetupResult(SetupOutcome.Passed, words)
            }
            // Its words can hold the address tested whole (a timeout's do): at a saved address, its hidden parts.
            is IndexerTest.Failed -> SetupResult(SetupOutcome.Failed, withoutHiddenParts(result.message))
            // The test only ever answers one of those.
            IndexerTest.Idle, IndexerTest.Running -> SetupResult(SetupOutcome.Failed, PHONE_TEST_UNFINISHED)
        }
    }

    /**
     * A debrid service card (L209): signed in to as its key form's Test does, with the key typed or, for a saved
     * account's card of its own service, its saved key, on a client of its own that marks no saved account. Its line is
     * that Test's: the account as the service names it, or the failure in the form's words.
     */
    private suspend fun service(item: SetupTestItem.Service): SetupResult {
        val draft = item.draft
        // The page offers only the services this version knows.
        val kind = serviceKindOf(draft.kind) ?: return SetupResult(SetupOutcome.Failed, SetupPage.COULDNT_RUN)
        val key = draft.key.trim().ifEmpty {
            val stored = item.ref?.let { saved.serviceKeyStored(it) } == true
            when (val use = SavedSecretRule.serviceKey(item.ref?.let { saved.service(it) }, stored, draft.kind)) {
                is SavedUse.Use -> use.secret
                is SavedUse.No -> return SetupResult(SetupOutcome.NotTested, if (use.why == SavedRefusal.ServerChanged) PHONE_TEST_SERVICE_CHANGED else words(use.why, provider = false))
            }
        }
        return try {
            val account = trialService(kind, key).test()
            passes.passed(kind, key, account.signIn())
            SetupResult(SetupOutcome.Passed, signedInLine(account))
        } catch (e: ServiceException) {
            SetupResult(SetupOutcome.Failed, keyFailure(kind, e.error))
        }
    }

    /** Metadata's check of the key typed, or of the saved one (the wizard's TMDB step asks for that), never saved. */
    private suspend fun tmdb(typed: String): SetupResult {
        val key = typed.trim().ifEmpty { saved.tmdbKey() ?: return SetupResult(SetupOutcome.NotTested, PHONE_TEST_TMDB_UNREADABLE) }
        val check = tmdbKeyCheck(validateTmdbKey(key))
        if (check.ok) passes.passed(key)
        return SetupResult(if (check.ok) SetupOutcome.Passed else SetupOutcome.Failed, check.message)
    }

    /**
     * [text] with each address in it cut to what a page may show of one: its scheme, host, port and path. A login, a
     * query or a fragment may hold a password, a token or a key, and the words go to the phone's page.
     */
    private fun withoutHiddenParts(text: String): String = ADDRESS.replace(text) { match ->
        val run = match.value
        // Punctuation after it is the sentence's, not the address's.
        val address = run.trimEnd(',', '.', ';', ')', ']')
        val scheme = address.substringBefore("://")
        val rest = address.substringAfter("://").substringBefore('?').substringBefore('#')
        val authority = rest.substringBefore('/')
        "$scheme://${authority.substringAfterLast('@')}${rest.substring(authority.length)}${run.substring(address.length)}"
    }

    private fun words(why: SavedRefusal, provider: Boolean): String = when (why) {
        SavedRefusal.NoneSaved -> if (provider) PHONE_TEST_NO_PASSWORD else PHONE_TEST_NO_KEY
        SavedRefusal.ServerChanged -> if (provider) PHONE_TEST_SERVER_CHANGED else PHONE_TEST_ADDRESS_CHANGED
        SavedRefusal.Unreadable -> if (provider) PHONE_TEST_PASSWORD_UNREADABLE else PHONE_TEST_KEY_UNREADABLE
    }

    private companion object {
        /** About 25 s: inside the server's 30 s wait, so the page gets the test's own answer. */
        const val LIMIT_MS = 25_000L

        /** The provider form a test from the phone uses has no saved provider's id; the test doesn't look at it. */
        const val THROWAWAY = "phone-test"

        /** An address in a test's words: a scheme, then everything up to a space. */
        val ADDRESS = Regex("""[A-Za-z][A-Za-z0-9+.-]*://\S+""")
    }
}

/** "ok", "failed": how a test from the phone went, for the log, in words with nothing typed in them. */
private fun outcome(outcome: SetupOutcome): String = when (outcome) {
    SetupOutcome.Passed -> "ok"
    SetupOutcome.Failed -> "failed"
    SetupOutcome.NotTested -> "not tested"
}

private val log = LoggerFactory.getLogger(PhoneTester::class.java)
