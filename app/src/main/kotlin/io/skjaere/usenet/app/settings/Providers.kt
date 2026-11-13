package io.skjaere.usenet.app.settings

import io.skjaere.usenet.app.ui.ListPlace
import io.skjaere.usenet.data.ProviderRecord
import io.skjaere.usenet.data.ProviderWithPassword
import io.skjaere.usenet.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.security.MessageDigest

/** What the app calls a provider: its name, or its host without one. */
val ProviderRecord.displayName: String get() = name.trim().ifEmpty { host }

/** How it connects: "news.example.net:563 · TLS · 20 connections". */
val ProviderRecord.address: String
    get() = listOfNotNull("$host:$port", "TLS".takeIf { useTls }, "$connections connection${if (connections == 1) "" else "s"}").joinToString(" · ")

/**
 * What a Test of a provider logged in with. A result holds for the saved provider while it saves
 * the same: a test of edits that weren't saved says nothing about it. The password is kept only as
 * a digest.
 */
data class ProviderLogin(val host: String, val port: Int, val username: String, val useTls: Boolean, val passwordDigest: String) {
    companion object {
        fun of(host: String, port: Int, username: String, useTls: Boolean, password: String) =
            ProviderLogin(host.trim(), port, username.trim(), useTls, digest(password))

        fun of(record: ProviderRecord, password: String) = of(record.host, record.port, record.username, record.useTls, password)

        private fun digest(password: String): String =
            MessageDigest.getInstance("SHA-256").digest(password.encodeToByteArray()).joinToString("") { "%02x".format(it) }
    }
}

/** What a saved provider logs in with; null when its password can't be read here. */
val ProviderWithPassword.login: ProviderLogin? get() = password?.let { ProviderLogin.of(record, it) }

/** How a Test of [login] went: [message] is what the provider screen shows. */
data class ProviderTestResult(val login: ProviderLogin, val ok: Boolean, val message: String)

/** The last Test of each provider during this app session, by provider id, for the providers list. Kept in memory only. */
class ProviderTests {
    private val _results = MutableStateFlow<Map<String, ProviderTestResult>>(emptyMap())
    val results: StateFlow<Map<String, ProviderTestResult>> = _results.asStateFlow()

    fun record(id: String, result: ProviderTestResult) = _results.update { it + (id to result) }

    fun forget(id: String) = _results.update { it - id }
}

/**
 * "Test all": tests the providers switched on, one after another, as each one's Test does ([test]),
 * and records how each went in [tests]; [testing] names the one being tested. One whose password
 * can't be read is left out: its row already asks for the password.
 *
 * Each is read again just before its test, as the list stays open under a provider's screen while
 * the others are tested (S9-8): one deleted or switched off by then isn't logged in to, an edited one
 * is tested as saved now, and one deleted while it is tested keeps no result, as on deleting it.
 */
internal suspend fun testEnabledProviders(
    settings: SettingsRepository,
    tests: ProviderTests,
    testing: MutableStateFlow<String?>,
    test: suspend (ProviderWithPassword) -> ProviderTestResult,
) {
    try {
        settings.providers.first().filter { it.enabled }.forEach { listed ->
            val p = settings.provider(listed.id)?.takeIf { it.record.enabled && it.password != null } ?: return@forEach
            testing.value = p.record.id
            tests.record(p.record.id, test(p))
            if (settings.provider(p.record.id) == null) tests.forget(p.record.id)
        }
    } finally {
        testing.value = null
    }
}

/** A saved provider as the list knows it: [login] is null when its password can't be read here. */
data class SavedProvider(val record: ProviderRecord, val login: ProviderLogin?)

/** A row's status line under the provider's address. */
sealed interface ProviderStatus {
    val text: String

    data object Off : ProviderStatus {
        override val text = "Off"
    }

    data object Unreadable : ProviderStatus {
        override val text = "Password can't be read here: enter it again"
    }

    data object Testing : ProviderStatus {
        override val text = "Testing…"
    }

    data object Connected : ProviderStatus {
        override val text = "Connected"
    }

    data class Failed(override val text: String) : ProviderStatus
}

/** One provider in the list: its [position] (1 is tried first) of [count]. */
data class ProviderRow(
    val id: String,
    val position: Int,
    val count: Int,
    val name: String,
    val address: String,
    val enabled: Boolean,
    /** Null for one switched on, readable and not tested during this session. */
    val status: ProviderStatus?,
) {
    /** Its place in the list, as the ordered lists' rows show it ([io.skjaere.usenet.app.ui.OrderedRow]). */
    val place: ListPlace get() = ListPlace(position, count)
    val canMoveUp: Boolean get() = place.canMoveUp
    val canMoveDown: Boolean get() = place.canMoveDown
}

/**
 * The providers list's rows, in priority order. A provider switched off says so, then one whose
 * password can't be read; otherwise its last [tests] result, if it was for the login now saved,
 * or that a test of it is running ([testing]).
 */
fun providerRows(providers: List<SavedProvider>, tests: Map<String, ProviderTestResult>, testing: String?): List<ProviderRow> =
    providers.mapIndexed { i, (record, login) ->
        val tested = tests[record.id]?.takeIf { login != null && it.login == login }
        val status = when {
            !record.enabled -> ProviderStatus.Off
            login == null -> ProviderStatus.Unreadable
            record.id == testing -> ProviderStatus.Testing
            tested == null -> null
            tested.ok -> ProviderStatus.Connected
            else -> ProviderStatus.Failed(tested.message)
        }
        ProviderRow(record.id, i + 1, providers.size, record.displayName, record.address, record.enabled, status)
    }

/** Where the focus goes on the providers list: a provider's row, or Add. */
sealed interface ListFocus {
    data class Row(val id: String) : ListFocus
    data object Add : ListFocus
}

/**
 * Where the focus goes back to on the providers list after a provider's screen opened from it
 * (S9-11), from the providers [listed] then and those listed [now]: the row of the one [opened],
 * or once it is deleted, the one in its place (the new last when it was last); after Add (null),
 * the one just added. Else Add.
 */
fun focusAfterEdit(opened: String?, listed: List<String>, now: List<String>): ListFocus {
    val row = when {
        opened == null -> now.firstOrNull { it !in listed }
        opened in now -> opened
        else -> now.getOrNull(listed.indexOf(opened).coerceAtMost(now.lastIndex))
    }
    return row?.let { ListFocus.Row(it) } ?: ListFocus.Add
}

/** The Settings hub's line for the providers: "2 providers · 28 connections", and " · 1 off" when some are switched off. */
fun providersSummary(providers: List<ProviderRecord>): String {
    if (providers.isEmpty()) return "None yet"
    val off = providers.count { !it.enabled }
    return "${providers.size} provider${if (providers.size > 1) "s" else ""} · ${providers.sumOf { it.connections }} connections" +
        if (off > 0) " · $off off" else ""
}

/**
 * Why nothing can be streamed: no saved provider is usable, nor, for a play, a debrid service account ([ServiceOff]).
 * Import and the play sheet say so at once.
 */
sealed interface NoUsableProvider {
    val message: String

    data object NoneSaved : NoUsableProvider {
        override val message = "No Usenet provider is set up yet."
    }

    data object AllOff : NoUsableProvider {
        override val message = "Every Usenet provider is switched off."
    }

    /**
     * No provider is set up, and the debrid service accounts saved are all off (L165, L213): a debrid-only user's play,
     * which Settings › Debrid services switches on. Import, which plays from Usenet alone, never says it.
     */
    data object ServiceOff : NoUsableProvider {
        override val message = "Your debrid service is off, and no Usenet provider is set up (Settings › Debrid services)."
    }

    /** The [count] providers switched on all have a password that can't be read here. */
    data class Unreadable(val count: Int) : NoUsableProvider {
        override val message: String
            get() = if (count == 1) "Your Usenet provider's password can't be read here: enter it again."
            else "Your Usenet providers' passwords can't be read here: enter them again."
    }

    companion object {
        /** The button that opens the providers list. */
        const val ACTION = "Open Usenet providers"

        /** The button that opens Settings › Debrid services, where [ServiceOff]'s words say the service is switched on. */
        const val SERVICES_ACTION = "Open Debrid services"

        /** From the [saved] providers and how many of them are [usable]; null while one is. */
        fun of(saved: List<ProviderRecord>, usable: Int): NoUsableProvider? = when {
            usable > 0 -> null
            saved.isEmpty() -> NoneSaved
            saved.none { it.enabled } -> AllOff
            else -> Unreadable(saved.count { it.enabled })
        }
    }
}

/**
 * The provider screen's Test (#74): one test at a time, and a result only for the form as it is.
 * A new Test cancels the one running, and a change to the form cancels it and clears the result,
 * so a test of older values never shows, even one that answers after it was given up on.
 * [onResult] hears of each result that shows.
 */
class ProviderTestRun(
    private val scope: CoroutineScope,
    private val test: suspend (ProviderForm) -> ProviderTestResult,
    private val onResult: (ProviderTestResult) -> Unit = {},
) {
    private val _state = MutableStateFlow<TestState>(TestState.Idle)
    val state: StateFlow<TestState> = _state.asStateFlow()

    /** The form the running test, or the result shown, is for. */
    private var tested: ProviderForm? = null
    private var job: Job? = null

    fun start(form: ProviderForm) {
        job?.cancel()
        tested = form
        _state.value = TestState.Running
        job = scope.launch {
            val result = test(form)
            ensureActive()
            if (tested != form) return@launch
            _state.value = TestState.Done(result.ok, result.message)
            onResult(result)
        }
    }

    /** The form is now [form]: a test of other values is cancelled, and a result for them cleared. */
    fun formChanged(form: ProviderForm) {
        if (form == tested) return
        job?.cancel()
        job = null
        tested = null
        _state.value = TestState.Idle
    }
}
