package io.skjaere.usenet.app.setup

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.skjaere.usenet.app.settings.IndexerTest
import io.skjaere.usenet.app.settings.ProviderForm
import io.skjaere.usenet.app.settings.ProviderLogin
import io.skjaere.usenet.app.settings.ProviderTestResult
import io.skjaere.usenet.app.settings.ProviderTests
import io.skjaere.usenet.app.settings.TMDB_REJECTED
import io.skjaere.usenet.app.settings.TestCipher
import io.skjaere.usenet.app.settings.memorySettings
import io.skjaere.usenet.data.ProviderRecord
import io.skjaere.usenet.data.ServiceSignIn
import io.skjaere.usenet.debrid.Account
import io.skjaere.usenet.debrid.ServiceError
import io.skjaere.usenet.debrid.ServiceException
import io.skjaere.usenet.debrid.ServiceKind
import io.skjaere.usenet.handoff.IndexerDraft
import io.skjaere.usenet.handoff.ProviderDraft
import io.skjaere.usenet.handoff.SetupChange
import io.skjaere.usenet.handoff.SetupChanges
import io.skjaere.usenet.handoff.SetupEvent
import io.skjaere.usenet.handoff.SavedIndexer
import io.skjaere.usenet.handoff.SavedProvider
import io.skjaere.usenet.handoff.SavedSecret
import io.skjaere.usenet.handoff.SavedService
import io.skjaere.usenet.handoff.ServiceDraft
import io.skjaere.usenet.handoff.SetupField
import io.skjaere.usenet.handoff.SetupMode
import io.skjaere.usenet.handoff.SetupOutcome
import io.skjaere.usenet.handoff.SetupRejection
import io.skjaere.usenet.handoff.SetupResult
import io.skjaere.usenet.handoff.SetupSection
import io.skjaere.usenet.handoff.SetupSeed
import io.skjaere.usenet.handoff.SetupSlot
import io.skjaere.usenet.handoff.SetupStep
import io.skjaere.usenet.handoff.SetupTestItem
import io.skjaere.usenet.handoff.SetupTester
import io.skjaere.usenet.handoff.TorrentIndexerDraft
import io.skjaere.usenet.metadata.KeyCheck
import io.skjaere.usenet.newznab.IndexerCaps
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.IndexerKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "Set up with your phone"'s session, review and Save, over fake sessions and tests, the saving against real settings
 * in memory: a code that works while the page is in front, the page the phone gets by what is saved, what arrived
 * reviewed, and nothing saved until everything passes, then all of it at once.
 */
class PhoneSetupViewModelTest {
    @AfterEach
    fun main() = Dispatchers.resetMain()

    private fun v4(text: String) = InetAddress.getByAddress(text.split('.').map { it.toInt().toByte() }.toByteArray()) as Inet4Address

    private val home = v4("192.168.1.23")
    private val phone = v4("192.168.1.40")

    /** A session's server as the view model sees it: what it opened with, the events it hands on, its deadline, and whether it was closed. */
    private class FakeSession(val opening: SetupOpening, number: Int, override var deadline: Long) : SetupSession {
        val ip get() = opening.ip
        val tester get() = opening.tester
        val events get() = opening.onEvent
        override val url = "http://${opening.ip.hostAddress}:4123$number/code${number}code${number}code${number}code${number}cod"
        override val display = "${opening.ip.hostAddress}:4123$number/code-$number"
        var closed = false

        override fun close() {
            closed = true
        }
    }

    /** What the phone's page held: a new card of each kind at most, and a key typed. */
    private data class Typed(val provider: ProviderDraft?, val indexer: IndexerDraft?, val tmdbKey: String?)

    private inner class Fixture(val test: TestScope, val pages: SetupPages = SetupPages()) {
        val cipher = TestCipher()
        /** While set, every read of the settings waits for it: a read held part way. */
        var readGate: CompletableDeferred<Unit>? = null
        val settings = memorySettings(StandardTestDispatcher(test.testScheduler), cipher) { readGate?.await() }
        /** How long a new session's server says its code works for. */
        var sessionLifetime = LIFETIME
        val providerTests = ProviderTests()
        val addresses = MutableStateFlow<Inet4Address?>(home)
        val sessions = mutableListOf<FakeSession>()
        var bindFails = false
        var provider: suspend (ProviderForm, String) -> ProviderTestResult = { f, password -> result(f, password, ok = true) }
        var indexer: suspend (IndexerConfig) -> IndexerTest = { IndexerTest.Ok(IndexerCaps(serverTitle = "Example"), null) }
        var tmdb: suspend (String) -> KeyCheck = { KeyCheck.Valid }
        val testedPasswords = mutableListOf<String>()
        val testedKeys = mutableListOf<String>()
        val testedIndexerIds = mutableListOf<String>()
        val testedIndexerKinds = mutableListOf<IndexerKind>()
        /** The debrid keys signed in with, each on a client of its own (the registry's trial). */
        val serviceKeys = mutableListOf<Pair<ServiceKind, String>>()
        var service: (ServiceKind, String) -> Account = { _, _ -> QUAY_ACCOUNT }
        /** Whether the build shows debrid, as the page reads it at each code. */
        var shown = true
        private val store = ViewModelStore()

        val deps = PhoneSetupDeps(
            settings = settings,
            providerTests = providerTests,
            addresses = addresses,
            openSession = { opening ->
                if (bindFails) throw IOException("in use")
                FakeSession(opening, sessions.size, test.testScheduler.currentTime + sessionLifetime).also { sessions += it }
            },
            testProvider = { f, password -> testedPasswords += password; provider(f, password) },
            testIndexer = { config -> testedKeys += config.apiKey; testedIndexerIds += config.id; testedIndexerKinds += config.kind; indexer(config) },
            validateTmdbKey = { tmdb(it) },
            now = { test.testScheduler.currentTime },
            pages = pages,
            trialService = { kind, key -> serviceKeys += kind to key; SignInService(kind, key) { k, typed -> service(k, typed) } },
            debridShown = { shown },
        )

        val vm: PhoneSetupViewModel by lazy {
            Dispatchers.setMain(StandardTestDispatcher(test.testScheduler))
            ViewModelProvider.create(store, viewModelFactory { initializer { PhoneSetupViewModel(deps) } })[PhoneSetupViewModel::class]
        }

        val state get() = vm.state.value
        val session get() = sessions.last()

        fun clear() = store.clear()

        /** The page in front, its first code open. */
        fun started(): Fixture {
            vm.start()
            test.runCurrent()
            return this
        }

        /** Tests of [payload]'s items from the phone's page, a card each, asked for as the session's server asks, and run as far as they go. */
        fun phoneTest(payload: Typed): Map<SetupSection, Future<SetupResult>> =
            items(payload).associate { it.slot.section to session.tester.test(it) }.also { test.runCurrent() }

        /** [payload] from the phone at [phone], on the open session, as new cards. */
        fun receive(payload: Typed) = receive(changes(payload))

        /** [changes] from the phone at [phone], on the open session. */
        fun receive(changes: SetupChanges) {
            session.events(SetupEvent.Opened(phone))
            session.events(SetupEvent.Received(changes, phone))
            test.runCurrent()
        }

        val received get() = assertIs<PhoneSetupState.Received>(state).review

        /** What arrived, in whichever state of its review or save the page is. */
        val review: SetupReview
            get() = when (val s = state) {
                is PhoneSetupState.Received -> s.review
                is PhoneSetupState.Testing -> s.review
                is PhoneSetupState.Results -> s.review
                is PhoneSetupState.Saving -> s.review
                is PhoneSetupState.Saved -> s.review
                else -> error("nothing arrived: $s")
            }

        /** The key of the item of what arrived that [what] picks. */
        fun key(what: (ReviewItem) -> Boolean) = review.items.single(what).key
    }

    private val p1 = SetupSlot(SetupSection.Provider, 1)
    private val i1 = SetupSlot(SetupSection.Indexer, 1)

    /** [payload]'s items as the phone's page tests them: a card each. */
    private fun items(payload: Typed) = listOfNotNull(
        payload.provider?.let { SetupTestItem.Provider(p1, null, it) },
        payload.indexer?.let { SetupTestItem.Indexer(i1, null, it, plainHttp = false) },
        payload.tmdbKey?.let { SetupTestItem.Tmdb(it) },
    )

    /** [payload] as the phone's page hands it over at Send: new cards, and the key typed. */
    private fun changes(payload: Typed) = SetupChanges(
        listOfNotNull(payload.provider?.let { SetupChange(null, it, removed = false) }),
        listOfNotNull(payload.indexer?.let { SetupChange(null, it, removed = false) }),
        payload.tmdbKey,
    )

    private fun result(f: ProviderForm, password: String, ok: Boolean) = ProviderTestResult(
        ProviderLogin.of(f.host, f.portValue ?: 563, f.username, f.useTls, password), ok,
        if (ok) "Connected and logged in to ${f.host.trim()}" else "The provider rejected the username or password.",
    )

    private val providerDraft = ProviderDraft("", "news.example.net", null, true, "alice", "hunter2", null)
    private val indexerDraft = IndexerDraft("", "https://indexer.example.org/api", "INDEXER-KEY-0000-1111")
    private val all = Typed(providerDraft, indexerDraft, "tmdb-key-0123456789abcdef")

    @Test
    fun `the page waits for the phone at the TV's address, the code working for its lifetime`() = runTest {
        val f = Fixture(this).started()
        val waiting = assertIs<PhoneSetupState.Waiting>(f.state)
        assertEquals(f.session.url, waiting.url)
        assertEquals(f.session.display, waiting.display)
        assertEquals(home, f.session.ip)
        assertEquals(LIFETIME, waiting.left)
        advanceTimeBy(19_000)
        runCurrent()
        assertEquals(LIFETIME - 19_000, assertIs<PhoneSetupState.Waiting>(f.state).left)
        // What a log or a failed assertion prints holds no code.
        assertFalse("code" in waiting.toString(), waiting.toString())
    }

    @Test
    fun `the code expires after its lifetime, and New code opens another`() = runTest {
        val f = Fixture(this).started()
        val first = f.session
        advanceTimeBy(LIFETIME - 1)
        runCurrent()
        assertIs<PhoneSetupState.Waiting>(f.state)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(PhoneSetupState.Expired, f.state)
        assertTrue(first.closed)
        f.vm.newCode()
        runCurrent()
        assertIs<PhoneSetupState.Waiting>(f.state)
        assertNotEquals(first, f.session)
        assertFalse(f.session.closed)
    }

    @Test
    fun `wrong tries are counted, the page's opener named, and the fifth closes the code`() = runTest {
        val f = Fixture(this).started()
        f.session.events(SetupEvent.WrongTry(2))
        runCurrent()
        assertEquals(2, assertIs<PhoneSetupState.Waiting>(f.state).wrongTries)
        f.session.events(SetupEvent.Opened(phone))
        runCurrent()
        assertEquals("192.168.1.40", assertIs<PhoneSetupState.Waiting>(f.state).openedBy)
        f.session.events(SetupEvent.Locked)
        runCurrent()
        assertEquals(PhoneSetupState.Locked, f.state)
        assertTrue(f.session.closed)
    }

    @Test
    fun `a form that couldn't be read says which field, and the code stays open`() = runTest {
        val f = Fixture(this).started()
        val rejection = SetupRejection(SetupField.ProviderHost, SetupRejection.Reason.Characters)
        f.session.events(SetupEvent.Rejected(rejection))
        runCurrent()
        assertEquals(rejection, assertIs<PhoneSetupState.Waiting>(f.state).rejected)
        assertFalse(f.session.closed)
    }

    /** A test's form was read after the refused one: the phone has fixed it, and "couldn't be read" would be out of date. */
    @Test
    fun `a test from the phone clears the last form that couldn't be read`() = runTest {
        val f = Fixture(this).started()
        f.session.events(SetupEvent.Rejected(SetupRejection(SetupField.ProviderHost, SetupRejection.Reason.Characters)))
        f.session.events(SetupEvent.TestStarted(p1))
        runCurrent()
        val testing = assertIs<PhoneSetupState.Waiting>(f.state)
        assertNull(testing.rejected, "the refused form is still said")
        assertEquals(PhoneTest.Running, testing.phoneTest)
        // One refused after the test's form is newer: it is said, and the test's end leaves it.
        val newer = SetupRejection(SetupField.IndexerUrl, SetupRejection.Reason.TooLong)
        f.session.events(SetupEvent.Rejected(newer))
        f.session.events(SetupEvent.TestFinished(p1, SetupOutcome.Passed))
        runCurrent()
        assertEquals(newer, assertIs<PhoneSetupState.Waiting>(f.state).rejected)
    }

    /** Next, Add or Back from the phone tests nothing, but its form was read after the refused one all the same. */
    @Test
    fun `any later form read clears the last form that couldn't be read`() = runTest {
        val f = Fixture(this).started()
        f.session.events(SetupEvent.Rejected(SetupRejection(SetupField.ProviderHost, SetupRejection.Reason.Characters)))
        runCurrent()
        assertIs<SetupRejection>(assertIs<PhoneSetupState.Waiting>(f.state).rejected)
        f.session.events(SetupEvent.FormRead)
        runCurrent()
        assertNull(assertIs<PhoneSetupState.Waiting>(f.state).rejected)
    }

    /** The server answers a request after the code's time itself; the page ends as when its own countdown runs out. */
    @Test
    fun `the code expiring on the server ends the session, with New code`() = runTest {
        val f = Fixture(this).started()
        f.session.events(SetupEvent.Expired)
        runCurrent()
        assertEquals(PhoneSetupState.Expired, f.state)
        assertTrue(f.session.closed)
    }

    @Test
    fun `without a home network it says so, and opens a code once one comes`() = runTest {
        val f = Fixture(this)
        f.addresses.value = null
        f.started()
        assertEquals(PhoneSetupState.Starting, f.state)
        advanceTimeBy(PhoneSetupViewModel.NO_NETWORK_GRACE_MS + 1)
        runCurrent()
        assertEquals(PhoneSetupState.NoNetwork, f.state)
        assertEquals(emptyList(), f.sessions)
        f.addresses.value = home
        runCurrent()
        assertIs<PhoneSetupState.Waiting>(f.state)
    }

    @Test
    fun `the network changing or going ends the code`() = runTest {
        val f = Fixture(this).started()
        val first = f.session
        f.addresses.value = v4("192.168.7.7")
        runCurrent()
        assertEquals(PhoneSetupState.NetworkChanged, f.state)
        assertTrue(first.closed)
        f.vm.newCode()
        runCurrent()
        assertEquals(v4("192.168.7.7"), f.session.ip)
        f.addresses.value = null
        runCurrent()
        assertEquals(PhoneSetupState.NetworkChanged, f.state)
        assertTrue(f.session.closed)
    }

    @Test
    fun `an address that can't be listened on is a changed network`() = runTest {
        val f = Fixture(this)
        f.bindFails = true
        f.started()
        assertEquals(PhoneSetupState.NetworkChanged, f.state)
    }

    @Test
    fun `stop closes the code, and start opens a new one`() = runTest {
        val f = Fixture(this).started()
        val first = f.session
        f.vm.stop()
        runCurrent()
        assertTrue(first.closed)
        assertEquals(PhoneSetupState.Starting, f.state)
        // A late event from the closed one changes nothing (a server sends none once closed; one it sent before the
        // close is the next test's).
        first.events(SetupEvent.Locked)
        runCurrent()
        assertEquals(PhoneSetupState.Starting, f.state)
        f.vm.start()
        runCurrent()
        assertEquals(2, f.sessions.size)
        assertNotEquals(first.url, assertIs<PhoneSetupState.Waiting>(f.state).url)
    }

    /**
     * The phone was answered "Sent" and its form handed over just as the page stopped (Home on the TV, say): the session
     * ends, but what it sent is still reviewed, as it would have been a moment earlier. Not after New code or Discard.
     */
    @Test
    fun `what was sent just before the page stopped is still reviewed`() = runTest {
        val f = Fixture(this).started()
        f.session.events(SetupEvent.Opened(phone))
        f.session.events(SetupEvent.Received(changes(all), phone))
        f.vm.stop()
        f.vm.start()
        runCurrent()
        assertEquals(listOf("Usenet provider · New", "Indexer · New", "TMDB key · New"), f.received.items.map { "${it.title} · ${it.heading}" })
        assertEquals(1, f.sessions.size, "a code opened over what arrived")
        // The code expiring, or the network changing, the same.
        val g = Fixture(this).started()
        g.session.events(SetupEvent.Received(changes(all), phone))
        g.addresses.value = v4("192.168.7.7")
        runCurrent()
        assertIs<PhoneSetupState.Received>(g.state)
        // New code after it: the user chose another code.
        val h = Fixture(this).started()
        h.session.events(SetupEvent.Received(changes(all), phone))
        h.vm.stop()
        h.vm.start()
        h.vm.newCode()
        runCurrent()
        assertIs<PhoneSetupState.Waiting>(h.state)
    }

    /**
     * The server moves its deadline as a request comes in, and tells of it only once it has answered: a Send in the code's
     * last moment has moved it already, and the countdown, at the old one, mustn't close the code under it.
     */
    @Test
    fun `the countdown at its end looks at the server's deadline before it closes the code`() = runTest {
        val f = Fixture(this)
        f.sessionLifetime = 180_000
        f.started()
        advanceTimeBy(179_950)
        runCurrent()
        f.session.deadline = testScheduler.currentTime + LIFETIME
        advanceTimeBy(100)
        runCurrent()
        assertIs<PhoneSetupState.Waiting>(f.state)
        assertFalse(f.session.closed)
        f.receive(all)
        assertIs<PhoneSetupState.Received>(f.state)
    }

    @Test
    fun `clearing the view model closes the code`() = runTest {
        val f = Fixture(this).started()
        f.clear()
        assertTrue(f.session.closed)
    }

    @Test
    fun `what arrived is reviewed against what is saved, the code closed, and nothing saved`() = runTest {
        val f = Fixture(this).started()
        f.receive(all)
        val review = f.received
        assertEquals(listOf("Usenet provider · New", "Indexer · New", "TMDB key · New"), review.items.map { "${it.title} · ${it.heading}" })
        assertEquals(SetupMode.Wizard, review.mode)
        assertTrue(f.session.closed)
        assertEquals(emptyList(), f.settings.providers.first())
        assertEquals(emptyList(), f.settings.indexers.first())
        assertNull(f.settings.tmdbKey())
        assertFalse("hunter2" in f.state.toString(), f.state.toString())
    }

    /** The page's opener is said only while waiting, which a quick sender skips: the review names it too. */
    @Test
    fun `the review names the address it was sent from`() = runTest {
        val f = Fixture(this).started()
        f.receive(all)
        assertEquals("192.168.1.40", f.received.sentFrom)
    }

    @Test
    fun `what arrived is kept across stop and start, with no code opened`() = runTest {
        val f = Fixture(this).started()
        f.receive(all)
        f.vm.stop()
        runCurrent()
        f.vm.start()
        runCurrent()
        assertIs<PhoneSetupState.Received>(f.state)
        assertEquals(1, f.sessions.size)
    }

    /**
     * Save tests on the TV only what the phone didn't pass with exactly what would be saved, all at once, and saves
     * nothing until every one has passed: then everything at once.
     */
    @Test
    fun `Save tests on the TV only what the phone didn't, then saves everything at once`() = runTest {
        val f = Fixture(this)
        val idx = f.settings.saveIndexer(null, "Idx", "https://indexer.example.org/api", "SAVED-KEY-0000-1111")
        f.started()
        // The phone's page tested the new provider with these values.
        f.phoneTest(Typed(providerDraft, null, null)).values.single().get(0, TimeUnit.SECONDS)
        assertEquals(listOf("hunter2"), f.testedPasswords)
        val gate = CompletableDeferred<Unit>()
        f.indexer = { gate.await(); IndexerTest.Ok(IndexerCaps(serverTitle = "Example"), null) }
        f.receive(
            SetupChanges(
                listOf(SetupChange(null, providerDraft, removed = false)),
                listOf(SetupChange(idx, IndexerDraft("Renamed", "https://indexer.example.org/api", ""), removed = false)),
                "tmdb-key-0123456789abcdef",
            ),
        )
        assertEquals(listOf(true, false, false), f.received.items.map { it.tested })
        f.vm.save()
        runCurrent()
        val testing = assertIs<PhoneSetupState.Testing>(f.state)
        val provider = f.key { it.kind == ReviewKind.Provider }
        assertEquals(ItemStatus.Passed(PHONE_TESTED), testing.items[provider])
        assertEquals(ItemStatus.Running, testing.items[idx])
        assertEquals(ItemStatus.Passed("Key works"), testing.items[TmdbReview.TMDB], "tested at once, beside the indexer")
        assertEquals(listOf("hunter2"), f.testedPasswords, "the provider tested again")
        assertEquals(listOf("SAVED-KEY-0000-1111"), f.testedKeys, "the renamed indexer, with its own saved key")
        // Nothing saved while one still runs.
        assertEquals(emptyList(), f.settings.providers.first())
        assertNull(f.settings.tmdbKey())
        assertEquals("Idx", f.settings.indexer(idx)?.record?.name)
        gate.complete(Unit)
        runCurrent()
        val saved = assertIs<PhoneSetupState.Saved>(f.state)
        assertEquals(setOf(ItemStatus.Done(PHONE_SETUP_SAVED, ok = true)), saved.items.values.toSet())
        val p = f.settings.providers.first().single()
        assertEquals("news.example.net", p.host)
        assertEquals("hunter2", f.settings.provider(p.id)?.password)
        assertEquals(listOf(idx to "Renamed"), f.settings.indexers.first().map { it.id to it.name })
        assertEquals("SAVED-KEY-0000-1111", f.settings.indexer(idx)?.apiKey)
        assertEquals("tmdb-key-0123456789abcdef", f.settings.tmdbKey())
        // The providers list shows the phone's test for the provider as saved.
        assertEquals(true, f.providerTests.results.value[p.id]?.ok)
        assertTrue(f.vm.savedSomething.value)
    }

    /** All or nothing: a failure on the TV holds up the save until it passes, is saved anyway or is left out. */
    @Test
    fun `while a test on the TV fails nothing is saved, until it is tried again, saved anyway or left out`() = runTest {
        val f = Fixture(this).started()
        f.provider = { form, password -> result(form, password, ok = false) }
        f.receive(Typed(providerDraft, indexerDraft, null))
        f.vm.save()
        runCurrent()
        val results = assertIs<PhoneSetupState.Results>(f.state)
        val provider = f.key { it.kind == ReviewKind.Provider }
        val indexer = f.key { it.kind == ReviewKind.Indexer }
        assertEquals(ItemStatus.Failed("The provider rejected the username or password.", canRetry = true, canSaveAnyway = true), results.items[provider])
        assertEquals(ItemStatus.Passed("Connected · Example"), results.items[indexer])
        assertFalse(results.settled)
        assertEquals(provider, results.focus)
        assertEquals(emptyList(), f.settings.indexers.first(), "the indexer that passed saved alone")
        f.vm.saveNow()
        runCurrent()
        assertIs<PhoneSetupState.Results>(f.state)
        // Tried again, it fails again: still nothing.
        f.vm.retry(provider)
        runCurrent()
        assertEquals(provider, assertIs<PhoneSetupState.Results>(f.state).focus)
        assertEquals(emptyList(), f.settings.indexers.first())
        f.vm.saveAnyway(provider)
        runCurrent()
        val settled = assertIs<PhoneSetupState.Results>(f.state)
        assertTrue(settled.settled)
        assertNull(settled.focus, "Save takes the focus")
        assertFalse(f.vm.savedSomething.value)
        f.vm.saveNow()
        runCurrent()
        val saved = assertIs<PhoneSetupState.Saved>(f.state)
        assertEquals(ItemStatus.Done(PHONE_SETUP_SAVED_ANYWAY, ok = false), saved.items[provider])
        assertEquals(ItemStatus.Done(PHONE_SETUP_SAVED, ok = true), saved.items[indexer])
        assertEquals(1, f.settings.providers.first().size)
        assertEquals(1, f.settings.indexers.first().size)
        // The providers list shows the TV's test, which failed, for the provider saved anyway.
        assertEquals(false, f.providerTests.results.value[f.settings.providers.first().single().id]?.ok)
    }

    @Test
    fun `an item left out is not saved, and the rest is`() = runTest {
        val f = Fixture(this).started()
        f.indexer = { IndexerTest.Failed("No answer within 15 s") }
        f.receive(Typed(providerDraft, indexerDraft, null))
        f.vm.save()
        runCurrent()
        val indexer = f.key { it.kind == ReviewKind.Indexer }
        f.vm.leaveOut(indexer)
        f.vm.saveNow()
        runCurrent()
        assertEquals(ItemStatus.Done(PHONE_SETUP_LEFT_OUT, ok = false), assertIs<PhoneSetupState.Saved>(f.state).items[indexer])
        assertEquals(1, f.settings.providers.first().size)
        assertEquals(emptyList(), f.settings.indexers.first())
    }

    @Test
    fun `a TMDB key TMDB refuses is never saved, only left out`() = runTest {
        val f = Fixture(this).started()
        f.tmdb = { KeyCheck.Invalid }
        f.receive(Typed(null, null, "tmdb-key-0123456789abcdef"))
        f.vm.save()
        runCurrent()
        assertEquals(ItemStatus.Failed(TMDB_REJECTED, canRetry = true, canSaveAnyway = false), assertIs<PhoneSetupState.Results>(f.state).items[TmdbReview.TMDB])
        f.vm.saveAnyway(TmdbReview.TMDB)
        runCurrent()
        assertIs<ItemStatus.Failed>(assertIs<PhoneSetupState.Results>(f.state).items[TmdbReview.TMDB])
        f.vm.leaveOut(TmdbReview.TMDB)
        f.vm.saveNow()
        runCurrent()
        assertIs<PhoneSetupState.Saved>(f.state)
        assertNull(f.settings.tmdbKey())
    }

    /** Nothing is saved before every test has passed: Back while testing has nothing to undo. */
    @Test
    fun `Back while testing stops the tests and returns to the review, nothing saved`() = runTest {
        val f = Fixture(this).started()
        val never = CompletableDeferred<IndexerTest>()
        f.indexer = { never.await() }
        f.receive(all)
        f.vm.save()
        runCurrent()
        assertIs<ItemStatus.Passed>(assertIs<PhoneSetupState.Testing>(f.state).items[f.key { it.kind == ReviewKind.Provider }])
        f.vm.cancelTests()
        runCurrent()
        assertIs<PhoneSetupState.Received>(f.state)
        never.complete(IndexerTest.Ok(IndexerCaps(), null))
        runCurrent()
        assertIs<PhoneSetupState.Received>(f.state)
        assertEquals(emptyList(), f.settings.providers.first())
        assertEquals(emptyList(), f.settings.indexers.first())
        assertNull(f.settings.tmdbKey())
        assertFalse(f.vm.savedSomething.value)
    }

    /**
     * L32: a phone's Play is refused busy-setup from the page's start to its stop, whatever it shows: waiting, the review
     * once the code closed as the form arrived, its tests. Not TV-8's server's life, which ends as a form arrives.
     */
    @Test
    fun `the page is busy for a phone's Play from its start to its stop, review and testing included`() = runTest {
        val f = Fixture(this)
        assertFalse(f.pages.open, "before the page starts")
        f.started()
        assertTrue(f.pages.open, "waiting")
        val never = CompletableDeferred<IndexerTest>()
        f.indexer = { never.await() }
        f.receive(all)
        assertTrue(f.session.closed, "the code closed as the form arrived")
        assertTrue(f.pages.open, "the review")
        f.vm.save()
        runCurrent()
        assertIs<PhoneSetupState.Testing>(f.state)
        assertTrue(f.pages.open, "testing")
        f.vm.stop()
        runCurrent()
        assertFalse(f.pages.open, "stopped, what arrived still kept to review")
        f.vm.stop()
        f.vm.start()
        runCurrent()
        assertTrue(f.pages.open, "started again, once")
        f.clear()
        assertFalse(f.pages.open, "cleared")
    }

    /** The focus follows the item being worked on: a Try again that fails again keeps it there. */
    @Test
    fun `after Try again the focus stays on that item while it fails, else moves to the next failing one`() = runTest {
        val f = Fixture(this).started()
        f.provider = { form, password -> result(form, password, ok = false) }
        f.indexer = { IndexerTest.Failed("No answer within 15 s") }
        f.tmdb = { KeyCheck.Invalid }
        f.receive(all)
        val (provider, indexer, tmdb) = f.received.items.map { it.key }
        f.vm.save()
        runCurrent()
        assertEquals(provider, assertIs<PhoneSetupState.Results>(f.state).focus, "the first failing one first")
        f.vm.retry(tmdb)
        runCurrent()
        assertEquals(tmdb, assertIs<PhoneSetupState.Results>(f.state).focus, "failed again: still there")
        f.indexer = { IndexerTest.Ok(IndexerCaps(serverTitle = "Example"), null) }
        f.vm.retry(indexer)
        runCurrent()
        assertEquals(tmdb, assertIs<PhoneSetupState.Results>(f.state).focus, "passed: the next failing one")
        f.tmdb = { KeyCheck.Valid }
        f.vm.retry(tmdb)
        runCurrent()
        assertEquals(provider, assertIs<PhoneSetupState.Results>(f.state).focus, "none after it: the first failing one")
        f.provider = { form, password -> result(form, password, ok = true) }
        f.vm.retry(provider)
        runCurrent()
        assertNull(assertIs<PhoneSetupState.Results>(f.state).focus, "nothing failing: Save")
        assertEquals(emptyList(), f.settings.providers.first(), "passing a Try again saves nothing by itself")
    }

    @Test
    fun `Back while trying one again returns to the results as they were`() = runTest {
        val f = Fixture(this).started()
        var answers = 0
        val never = CompletableDeferred<IndexerTest>()
        f.indexer = { if (answers++ == 0) IndexerTest.Failed("No answer within 15 s") else never.await() }
        f.receive(Typed(null, indexerDraft, null))
        f.vm.save()
        runCurrent()
        val before = assertIs<PhoneSetupState.Results>(f.state)
        val indexer = before.items.keys.single()
        f.vm.retry(indexer)
        runCurrent()
        assertEquals(indexer, assertIs<PhoneSetupState.Testing>(f.state).retry)
        f.vm.cancelTests()
        runCurrent()
        // As they were, the focus to go back to the one tried again.
        assertEquals(before.copy(retried = indexer), f.state)
    }

    /**
     * Save applies everything at once: removals (a provider's test result forgotten with it), changes over each record
     * read again, keeping its id, place and on/off and its saved password or key, then new ones after the saved ones in
     * the page's order.
     */
    @Test
    fun `Save removes, changes in place and adds in the page's order`() = runTest {
        val f = Fixture(this)
        val a = f.settings.saveProvider(ProviderRecord("", "A", "a.example.net", 563, "alice"), "pass-a")
        val b = f.settings.saveProvider(ProviderRecord("", "B", "b.example.net", 563, "bob", connections = 8), "pass-b")
        val c = f.settings.saveProvider(ProviderRecord("", "C", "c.example.net", 563, ""), "")
        f.settings.setProviderEnabled(b, false)
        val x = f.settings.saveIndexer(null, "X", "https://x.example.org/api", "KEY-X")
        val y = f.settings.saveIndexer(null, "Y", "https://u:p@y.example.org/api?r=t", "KEY-Y")
        f.providerTests.record(a, result(ProviderForm(a, host = "a.example.net", username = "alice"), "pass-a", ok = true))
        f.started()
        assertEquals(SetupMode.Edit, f.session.opening.mode)
        f.receive(
            SetupChanges(
                listOf(
                    SetupChange(a, ProviderDraft("A", "a.example.net", 563, true, "alice", "", 8), removed = true),
                    SetupChange(b, ProviderDraft("B", "b.example.net", 563, true, "bob", "", 20), removed = false),
                    SetupChange(c, ProviderDraft("C", "c.example.net", 563, true, "", "", 8), removed = false),
                    SetupChange(null, ProviderDraft("D", "d.example.net", null, true, "dan", "pass-d", null), removed = false),
                    SetupChange(null, ProviderDraft("E", "e.example.net", null, true, "eve", "pass-e", null), removed = false),
                ),
                listOf(
                    SetupChange(x, IndexerDraft("X", "https://x.example.org/api", ""), removed = true),
                    SetupChange(y, IndexerDraft("Y renamed", "https://y.example.org/api", ""), removed = false),
                    SetupChange(null, IndexerDraft("Z", "https://z.example.org/api", "KEY-Z"), removed = false),
                ),
                null,
            ),
        )
        assertEquals(listOf("1 · Removes “A”", "2 · Changes “B” · switched off", "3 · New", "4 · New", "1 · Removes “X”", "2 · Changes “Y”", "3 · New"),
            f.received.items.map { "${it.part.last()} · ${it.heading}" })
        f.vm.save()
        runCurrent()
        assertIs<PhoneSetupState.Saved>(f.state)
        val providers = f.settings.providers.first()
        assertEquals(listOf(b, c), providers.take(2).map { it.id }, "changed in place")
        assertEquals(listOf("d.example.net", "e.example.net"), providers.drop(2).map { it.host }, "new ones last, in the page's order")
        assertEquals(listOf(false, 20), providers.first().let { listOf(it.enabled, it.connections) }, "B stays off")
        assertEquals("pass-b", f.settings.provider(b)?.password)
        assertNull(f.settings.provider(a))
        assertNull(f.providerTests.results.value[a], "the removed one's result forgotten")
        val indexers = f.settings.indexers.first()
        assertEquals(listOf(y to "Y renamed", indexers[1].id to "Z"), indexers.map { it.id to it.name })
        assertEquals("https://u:p@y.example.org/api?r=t", indexers.first().baseUrl, "its saved address whole")
        assertEquals("KEY-Y", f.settings.indexer(y)?.apiKey)
        assertNull(f.settings.indexer(x))
    }

    /** A record deleted on the TV after it arrived is said, never brought back: saving over it would revive its tombstone. */
    @Test
    fun `a change whose record went meanwhile isn't saved, and says so`() = runTest {
        val f = Fixture(this)
        val b = f.settings.saveProvider(ProviderRecord("", "B", "b.example.net", 563, "bob"), "pass-b")
        f.settings.saveIndexer(null, "X", "https://x.example.org/api", "KEY-X")
        f.started()
        f.receive(SetupChanges(listOf(SetupChange(b, ProviderDraft("B2", "b.example.net", 563, true, "bob", "", 8), removed = false)), emptyList(), null))
        f.settings.deleteProvider(b)
        f.vm.save()
        runCurrent()
        assertEquals(ItemStatus.Done(PHONE_SETUP_CHANGED_MEANWHILE, ok = false), assertIs<PhoneSetupState.Saved>(f.state).items[b])
        assertEquals(emptyList(), f.settings.providers.first())
    }

    /**
     * A record moved to another server on the TV after what arrived was reviewed: saving the phone's values over it would
     * send its new password or key to the old server. It isn't saved, and says so; the record stays as the TV left it.
     */
    @Test
    fun `a change whose record moved to another server meanwhile isn't saved, and says so`() = runTest {
        val f = Fixture(this)
        val b = f.settings.saveProvider(ProviderRecord("", "B", "b.example.net", 563, "bob"), "pass-b")
        val x = f.settings.saveIndexer(null, "X", "https://x.example.org/api", "KEY-X")
        f.started()
        f.receive(
            SetupChanges(
                listOf(SetupChange(b, ProviderDraft("B2", "b.example.net", 563, true, "bob", "", 8), removed = false)),
                listOf(SetupChange(x, IndexerDraft("X2", "https://x.example.org/api", ""), removed = false)),
                null,
            ),
        )
        // Before Save, each moved on the TV, with a secret of its own.
        f.settings.saveProvider(ProviderRecord(b, "B", "moved.example.net", 563, "bob"), "other-pass")
        f.settings.saveIndexer(x, "X", "https://moved.example.org/api", "OTHER-KEY")
        f.vm.save()
        runCurrent()
        val saved = assertIs<PhoneSetupState.Saved>(f.state)
        assertEquals(ItemStatus.Done(PHONE_SETUP_CHANGED_MEANWHILE, ok = false), saved.items[b])
        assertEquals(ItemStatus.Done(PHONE_SETUP_CHANGED_MEANWHILE, ok = false), saved.items[x])
        assertEquals(listOf("moved.example.net", "B"), f.settings.provider(b)?.record?.let { listOf(it.host, it.name) })
        assertEquals("other-pass", f.settings.provider(b)?.password)
        assertEquals(listOf("https://moved.example.org/api", "X"), f.settings.indexer(x)?.record?.let { listOf(it.baseUrl, it.name) })
        assertEquals("OTHER-KEY", f.settings.indexer(x)?.apiKey)
    }

    /**
     * Whoever holds the code chooses where Save sends a saved secret: a saved provider sent to another server with no
     * password typed can't be saved or tested, only left out.
     */
    @Test
    fun `Save never sends a saved password or key to another server`() = runTest {
        val f = Fixture(this)
        val main = f.settings.saveProvider(ProviderRecord("", "Main", "news.example.net", 563, "alice"), "saved-pass")
        val idx = f.settings.saveIndexer(null, "Idx", "https://indexer.example.org/api", "SAVED-KEY-0000-1111")
        f.started()
        f.receive(
            SetupChanges(
                listOf(SetupChange(main, ProviderDraft("Main", "news.examp1e.net", 563, true, "alice", "", 8), removed = false)),
                listOf(SetupChange(idx, IndexerDraft("Idx", "http://indexer.example.org/api", ""), removed = false)),
                null,
            ),
        )
        f.vm.save()
        runCurrent()
        val results = assertIs<PhoneSetupState.Results>(f.state)
        assertEquals(ItemStatus.Failed(PROVIDER_SERVER_CHANGED, canRetry = false, canSaveAnyway = false), results.items[main])
        assertEquals(ItemStatus.Failed(INDEXER_ADDRESS_CHANGED, canRetry = false, canSaveAnyway = false), results.items[idx])
        f.vm.retry(main)
        f.vm.saveAnyway(main)
        runCurrent()
        assertIs<ItemStatus.Failed>(assertIs<PhoneSetupState.Results>(f.state).items[main])
        assertEquals(emptyList(), f.testedPasswords)
        assertEquals(emptyList(), f.testedKeys)
        assertEquals("news.example.net", f.settings.provider(main)?.record?.host)
    }

    /** The user went past the wizard's indexer step: Home leaves the first run, as "Not now" there does. */
    @Test
    fun `a wizard that ends with a provider and no indexer passes the first run's indexer step`() = runTest {
        val f = Fixture(this).started()
        f.receive(Typed(providerDraft, null, null))
        f.vm.save()
        runCurrent()
        assertTrue(f.settings.indexerSetupSkipped.first())
        // Not from the edit page, nor with an indexer.
        val g = Fixture(this)
        g.settings.saveProvider(ProviderRecord("", "Main", "news.example.net", 563, ""), "")
        g.settings.saveIndexer(null, "Idx", "https://indexer.example.org/api", "KEY")
        g.started()
        g.receive(Typed(null, null, "tmdb-key-0123456789abcdef"))
        g.vm.save()
        runCurrent()
        assertFalse(g.settings.indexerSetupSkipped.first())
    }

    @Test
    fun `with nothing changed the review says so, and Save does nothing`() = runTest {
        val f = Fixture(this)
        val p = f.settings.saveProvider(ProviderRecord("", "Main", "news.example.net", 563, ""), "")
        f.started()
        f.receive(SetupChanges(listOf(SetupChange(p, ProviderDraft("Main", "news.example.net", 563, true, "", "", 8), removed = false)), emptyList(), null))
        assertEquals(emptyList(), f.received.items)
        assertEquals(SetupReview.NOTHING_CHANGED, f.received.summary)
        f.vm.save()
        runCurrent()
        assertIs<PhoneSetupState.Received>(f.state)
        assertFalse(f.vm.savedSomething.value)
    }

    /** A form under the page read its record before: once anything is saved, every way out must close it too. */
    @Test
    fun `a save is remembered for the page's life, past Send again`() = runTest {
        val f = Fixture(this).started()
        f.receive(all)
        assertFalse(f.vm.savedSomething.value)
        f.vm.save()
        runCurrent()
        assertTrue(f.vm.savedSomething.value)
        f.vm.newCode()
        runCurrent()
        assertIs<PhoneSetupState.Waiting>(f.state)
        assertTrue(f.vm.savedSomething.value)
    }

    /** Send again keeps what was typed: the next code's page starts from what arrived, its secrets still on the TV. */
    @Test
    fun `Send again opens a new code whose page starts from what arrived, until it is saved`() = runTest {
        val f = Fixture(this).started()
        assertNull(f.session.opening.from)
        f.receive(all)
        val arrived = f.received.changes
        f.vm.newCode()
        runCurrent()
        assertIs<PhoneSetupState.Waiting>(f.state)
        assertEquals(2, f.sessions.size)
        assertEquals(arrived, f.session.opening.from)
        assertEquals(emptyList(), f.settings.providers.first())
        // Saved, Send again starts from what is saved.
        f.receive(all)
        f.vm.save()
        runCurrent()
        assertIs<PhoneSetupState.Saved>(f.state)
        f.vm.newCode()
        runCurrent()
        assertNull(f.session.opening.from)
        assertEquals(SetupMode.Edit, f.session.opening.mode)
    }

    @Test
    fun `Discard drops what arrived without saving`() = runTest {
        val f = Fixture(this).started()
        f.receive(all)
        f.vm.discard()
        runCurrent()
        assertEquals(PhoneSetupState.Starting, f.state)
        assertEquals(1, f.sessions.size)
        assertEquals(emptyList(), f.settings.providers.first())
        assertNull(f.settings.tmdbKey())
        f.vm.newCode()
        runCurrent()
        assertNull(f.session.opening.from, "a discarded arrival isn't the next page's")
    }

    /**
     * A saved secret goes only with its own card, while the server is the one it was saved for: new cards at the saved
     * addresses aren't tested without what was typed; the saved cards themselves, as the page shows them, are tested with
     * their saved password and key. Nothing is saved or recorded, and the code stays open.
     */
    @Test
    fun `a test from the phone uses a saved secret for its own card on the same server only, and saves nothing`() = runTest {
        val f = Fixture(this)
        val provider = f.settings.saveProvider(ProviderRecord("", "Main", "news.example.net", 563, "alice"), "saved-pass")
        val indexer = f.settings.saveIndexer(null, "Idx", "https://indexer.example.org/api", "SAVED-KEY-0000-1111")
        f.settings.setTmdbKey("saved-tmdb-key-0000")
        f.started()
        val before = f.settings.settings.first()
        val blanks = f.phoneTest(Typed(providerDraft.copy(password = ""), indexerDraft.copy(apiKey = ""), null)).mapValues { it.value.get(0, TimeUnit.SECONDS).outcome }
        assertEquals(
            mapOf(SetupSection.Provider to SetupOutcome.NotTested, SetupSection.Indexer to SetupOutcome.NotTested),
            blanks,
        )
        assertEquals(emptyList(), f.testedPasswords, "a new card tested with the saved password")
        assertEquals(emptyList(), f.testedKeys, "a new card tested with the saved key")
        // The saved cards, left empty: their own saved secrets.
        val own = listOf(
            SetupTestItem.Provider(p1, provider, providerDraft.copy(name = "Main", password = "")),
            SetupTestItem.Indexer(i1, indexer, indexerDraft.copy(name = "Idx", apiKey = ""), plainHttp = false),
        ).map { f.session.tester.test(it) }
        runCurrent()
        assertEquals(listOf(SetupOutcome.Passed, SetupOutcome.Passed), own.map { it.get(0, TimeUnit.SECONDS).outcome })
        assertEquals(listOf("saved-pass"), f.testedPasswords)
        assertEquals(listOf("SAVED-KEY-0000-1111"), f.testedKeys)
        // Moved to another host, the saved card isn't tested without its password typed.
        val moved = f.session.tester.test(SetupTestItem.Provider(p1, provider, providerDraft.copy(host = "news.examp1e.net", password = "")))
        runCurrent()
        assertEquals(SetupOutcome.NotTested, moved.get(0, TimeUnit.SECONDS).outcome)
        assertEquals(listOf("saved-pass"), f.testedPasswords)
        // Typed, they are what is tested: the indexer under an id no saved one has.
        val typed = f.phoneTest(all).mapValues { it.value.get(0, TimeUnit.SECONDS).outcome }
        assertEquals(setOf(SetupOutcome.Passed), typed.values.toSet())
        assertEquals(listOf("saved-pass", "hunter2"), f.testedPasswords)
        assertEquals(listOf("SAVED-KEY-0000-1111", "INDEXER-KEY-0000-1111"), f.testedKeys)
        assertTrue(f.testedIndexerIds.none { id -> id == indexer || f.settings.indexers.first().any { it.id == id } })
        // Nothing saved, recorded or changed, and the code still open.
        assertEquals(before, f.settings.settings.first())
        assertEquals("saved-pass", f.settings.provider(provider)?.password)
        assertEquals("SAVED-KEY-0000-1111", f.settings.indexer(indexer)?.apiKey)
        assertEquals("saved-tmdb-key-0000", f.settings.tmdbKey())
        assertEquals(emptyMap(), f.providerTests.results.value)
        assertFalse(f.vm.savedSomething.value)
        assertIs<PhoneSetupState.Waiting>(f.state)
        assertFalse(f.session.closed)
    }

    /** The server asks from a connection's thread: the call returns at once, the work runs on the view model's own. */
    @Test
    fun `a test from the phone is asked for on another thread and runs on the view model's`() = runTest {
        val f = Fixture(this).started()
        var future: Future<SetupResult>? = null
        thread { future = f.session.tester.test(items(all).first()) }.join(2_000)
        val asked = assertNotNull(future, "the call didn't return")
        assertFalse(asked.isDone, "the test ran on the server's thread")
        runCurrent()
        assertEquals(SetupOutcome.Passed, asked.get(0, TimeUnit.SECONDS).outcome)
    }

    /** Its work belongs to the session: whatever ends that ends the test. */
    @Test
    fun `ending the session stops a test from the phone`() = runTest {
        val ends = mapOf<String, Fixture.() -> Unit>(
            "the page left" to { vm.stop() },
            "New code" to { vm.newCode() },
            "the form sent" to { session.events(SetupEvent.Received(changes(all), phone)) },
            "the network changed" to { addresses.value = v4("192.168.7.7") },
            "the code expired" to { test.advanceTimeBy(5_000) },
            "the view model cleared" to { clear() },
        )
        for ((why, end) in ends) {
            val f = Fixture(this).started()
            // Near the code's end, so it expires before the test's own limit.
            advanceTimeBy(LIFETIME - 5_000)
            runCurrent()
            var stopped = false
            f.provider = { _, _ ->
                try {
                    awaitCancellation()
                } finally {
                    stopped = true
                }
            }
            val future = f.phoneTest(Typed(providerDraft, null, null)).getValue(SetupSection.Provider)
            assertFalse(future.isDone, why)
            f.end()
            runCurrent()
            assertTrue(stopped, "$why: the test goes on")
            assertTrue(future.isCancelled, why)
        }
    }

    /** Waiting says while the phone's test runs, then how each card's latest one went: the server's events, nothing more. */
    @Test
    fun `a test from the phone shows on Waiting while it runs, then how each card went`() = runTest {
        val f = Fixture(this).started()
        f.session.events(SetupEvent.Opened(phone))
        f.session.events(SetupEvent.TestStarted(p1))
        runCurrent()
        assertEquals(PhoneTest.Running, assertIs<PhoneSetupState.Waiting>(f.state).phoneTest)
        val p2 = SetupSlot(SetupSection.Provider, 2)
        f.session.events(SetupEvent.TestFinished(p2, SetupOutcome.Failed))
        f.session.events(SetupEvent.TestFinished(SetupSlot.Tmdb, SetupOutcome.Passed))
        f.session.events(SetupEvent.TestFinished(p1, SetupOutcome.Failed))
        f.session.events(SetupEvent.TestFinished(i1, SetupOutcome.NotTested))
        // A card tested again: its latest.
        f.session.events(SetupEvent.TestFinished(p1, SetupOutcome.Passed))
        runCurrent()
        val tested = assertIs<PhoneSetupState.Waiting>(f.state)
        assertEquals(
            PhoneTest.Finished(mapOf(p2 to SetupOutcome.Failed, SetupSlot.Tmdb to SetupOutcome.Passed, p1 to SetupOutcome.Passed, i1 to SetupOutcome.NotTested)),
            tested.phoneTest,
        )
        assertEquals("192.168.1.40", tested.openedBy)
        // A newer test: running again.
        f.session.events(SetupEvent.TestStarted(i1))
        runCurrent()
        assertEquals(PhoneTest.Running, assertIs<PhoneSetupState.Waiting>(f.state).phoneTest)
        assertFalse(f.session.closed)
    }

    /** Next or Back on the phone stopped its test before it ended: Waiting stops saying one runs, and says what it did before. */
    @Test
    fun `a test the phone's Next or Back stopped no longer shows as running`() = runTest {
        val f = Fixture(this).started()
        f.session.events(SetupEvent.Opened(phone))
        f.session.events(SetupEvent.TestStarted(p1))
        f.session.events(SetupEvent.StepChanged(SetupStep.Indexers))
        f.session.events(SetupEvent.TestStopped(p1))
        runCurrent()
        assertNull(assertIs<PhoneSetupState.Waiting>(f.state).phoneTest, "no test has ended, and none runs")
        // With one ended before it: how that went, as before the test that stopped.
        f.session.events(SetupEvent.TestStarted(i1))
        f.session.events(SetupEvent.TestFinished(i1, SetupOutcome.Failed))
        f.session.events(SetupEvent.TestStarted(i1))
        f.session.events(SetupEvent.StepChanged(SetupStep.Providers))
        f.session.events(SetupEvent.TestStopped(i1))
        runCurrent()
        val stopped = assertIs<PhoneSetupState.Waiting>(f.state)
        assertEquals(PhoneTest.Finished(mapOf(i1 to SetupOutcome.Failed)), stopped.phoneTest)
        assertEquals(SetupStep.Providers, stopped.step)
        assertFalse(f.session.closed)
    }

    @Test
    fun `a test of a session that has ended says nothing on the next`() = runTest {
        val f = Fixture(this).started()
        val first = f.session
        f.vm.newCode()
        runCurrent()
        first.events(SetupEvent.TestStarted(p1))
        first.events(SetupEvent.TestFinished(p1, SetupOutcome.Passed))
        runCurrent()
        assertNull(assertIs<PhoneSetupState.Waiting>(f.state).phoneTest)
        // A new code starts with no test said.
        f.session.events(SetupEvent.TestStarted(p1))
        runCurrent()
        f.vm.newCode()
        runCurrent()
        assertNull(assertIs<PhoneSetupState.Waiting>(f.state).phoneTest)
    }

    /** Cards by their place on the page, never by anything typed on it: in the page's order, each card's latest test. */
    @Test
    fun `the test line names the cards by place, in the page's order`() {
        assertEquals("Your phone is testing…", phoneTestLine(PhoneTest.Running))
        assertEquals(PHONE_SETUP_TESTING, phoneTestLine(PhoneTest.Running))
        val p2 = SetupSlot(SetupSection.Provider, 2)
        val i2 = SetupSlot(SetupSection.Indexer, 2)
        assertEquals(
            "Tested from your phone: Provider 1 works · Provider 2 failed · Indexer 2 not tested · TMDB key works",
            phoneTestLine(PhoneTest.Finished(mapOf(SetupSlot.Tmdb to SetupOutcome.Passed, i2 to SetupOutcome.NotTested, p2 to SetupOutcome.Failed, p1 to SetupOutcome.Passed))),
        )
        assertEquals("Tested from your phone: Indexer 1 failed", phoneTestLine(PhoneTest.Finished(mapOf(i1 to SetupOutcome.Failed))))
    }

    /** Where the phone is, from the server's events: the wizard's step, or the edit page. */
    @Test
    fun `the progress line says the wizard's step or the edit page`() {
        assertEquals("On your phone: step 1 of 4, providers", progressLine(SetupStep.Providers))
        assertEquals("On your phone: step 2 of 4, indexers", progressLine(SetupStep.Indexers))
        assertEquals("On your phone: step 3 of 4, TMDB key", progressLine(SetupStep.Tmdb))
        assertEquals("On your phone: step 4 of 4, check and send", progressLine(SetupStep.Check))
        assertEquals("On your phone: editing", progressLine(SetupStep.Edit))
        assertEquals("Follow the steps on your phone.", stepsLine(SetupMode.Wizard))
        assertEquals("Change, add or remove on your phone, then send.", stepsLine(SetupMode.Edit))
        assertEquals("Nothing is saved until you choose Save here.", PHONE_SETUP_SAVE_HERE)
    }

    /** The page the phone gets is decided at each code's opening, from what is saved then (switched off or unreadable counts). */
    @Test
    fun `the wizard opens while the TV can't play, the edit page once a provider and an indexer are saved`() = runTest {
        val f = Fixture(this).started()
        assertEquals(SetupMode.Wizard, f.session.opening.mode)
        assertEquals(SetupSeed(serviceKinds = SERVICE_CHOICES), f.session.opening.seed)
        assertEquals(SetupMode.Wizard, assertIs<PhoneSetupState.Waiting>(f.state).mode)
        f.cipher.unreadable += "lost-pass"
        val p = f.settings.saveProvider(ProviderRecord("", "Main", "news.example.net", 563, "alice"), "lost-pass")
        f.settings.setProviderEnabled(p, false)
        f.vm.newCode()
        runCurrent()
        assertEquals(SetupMode.Wizard, f.session.opening.mode, "a provider alone")
        assertEquals(listOf(SavedSecret.Unreadable), f.session.opening.seed.providers.map { it.login })
        val i = f.settings.saveIndexer(null, "Idx", "https://indexer.example.org/api?apikey=K", "KEY")
        f.settings.setTmdbKey("tmdb-key")
        f.vm.newCode()
        runCurrent()
        assertEquals(SetupMode.Edit, f.session.opening.mode, "a provider switched off, whose password can't be read, still counts")
        assertEquals(SetupMode.Edit, assertIs<PhoneSetupState.Waiting>(f.state).mode)
        assertEquals(SetupStep.Edit, assertIs<PhoneSetupState.Waiting>(f.state).step)
        assertEquals(
            SetupSeed(
                listOf(SavedProvider(p, "Main", "news.example.net", 563, true, "alice", 8, false, SavedSecret.Unreadable)),
                listOf(SavedIndexer(i, "Idx", "https://indexer.example.org/api", hiddenParts = true, enabled = true, key = SavedSecret.Saved)),
                SavedSecret.Saved,
                SERVICE_CHOICES,
            ),
            f.session.opening.seed,
        )
    }

    /**
     * L165, L209: the torrent pair (a service and a torrent indexer, while debrid shows) makes the edit page as Usenet's
     * pair does, its cards in the seed; hidden, neither the pair nor its cards count, and the page is today's wizard.
     */
    @Test
    fun `a service and a torrent indexer saved make the edit page, and while hidden the page has no debrid cards`() = runTest {
        val f = Fixture(this)
        val tb = f.settings.saveService("TorBox", "SAVED-SERVICE-KEY") { ServiceSignIn("quay.viewer@example.invalid") }
        f.started()
        assertEquals(SetupMode.Wizard, f.session.opening.mode, "a service alone")
        assertEquals(listOf(SavedService(tb, "torbox", "quay.viewer@example.invalid", true, SavedSecret.Saved)), f.session.opening.seed.services)
        val r = f.settings.saveTorrentIndexer(null, "Home Prowlarr", "http://192.168.1.20:9696/3/api", "SAVED-TORRENT-KEY")
        f.vm.newCode()
        runCurrent()
        assertEquals(SetupMode.Edit, f.session.opening.mode)
        assertEquals(SERVICE_CHOICES, f.session.opening.seed.serviceKinds)
        assertEquals(listOf(r), f.session.opening.seed.torrentIndexers.map { it.ref })
        f.shown = false
        f.vm.newCode()
        runCurrent()
        assertEquals(SetupMode.Wizard, f.session.opening.mode)
        assertEquals(SetupSeed(), f.session.opening.seed)
    }

    /**
     * L209: a service card the phone tested isn't signed in to again; a torrent indexer it didn't test is tested on the
     * TV as a torrent indexer; Save keeps the account (its key, and its name as the phone's test had it) and the torrent
     * indexer with its switch.
     */
    @Test
    fun `a debrid service and a torrent indexer from the phone are tested where the phone didn't, then saved`() = runTest {
        val f = Fixture(this).started()
        val s1 = SetupSlot(SetupSection.Service, 1)
        val tested = f.session.tester.test(SetupTestItem.Service(s1, null, ServiceDraft("torbox", "TYPED-SERVICE-KEY")))
        runCurrent()
        assertEquals(SetupOutcome.Passed, tested.get(0, TimeUnit.SECONDS).outcome)
        assertEquals(listOf(ServiceKind.TorBox to "TYPED-SERVICE-KEY"), f.serviceKeys)
        f.receive(
            SetupChanges(
                emptyList(), emptyList(), null,
                services = listOf(SetupChange(null, ServiceDraft("torbox", "TYPED-SERVICE-KEY"), removed = false)),
                torrentIndexers = listOf(SetupChange(null, TorrentIndexerDraft("Home Prowlarr", "http://192.168.1.20:9696/3/api", "TYPED-TORRENT-KEY", useWithService = false), removed = false)),
            ),
        )
        assertEquals(listOf("Debrid service · New", "Torrent indexer · New"), f.received.items.map { "${it.title} · ${it.heading}" })
        assertEquals(listOf(true, false), f.received.items.map { it.tested })
        assertEquals(emptyList(), f.settings.services.first(), "nothing saved before Save")
        f.vm.save()
        runCurrent()
        val saved = assertIs<PhoneSetupState.Saved>(f.state)
        assertEquals(setOf(ItemStatus.Done(PHONE_SETUP_SAVED, ok = true)), saved.items.values.toSet())
        assertEquals(1, f.serviceKeys.size, "the phone's test stands: the service isn't asked again")
        assertEquals(listOf(IndexerKind.Torznab), f.testedIndexerKinds)
        assertEquals(listOf("TYPED-TORRENT-KEY"), f.testedKeys)
        val account = f.settings.servicesWithKeys().single()
        assertEquals(listOf("TorBox", "quay.viewer@example.invalid", "Standard", "TYPED-SERVICE-KEY"), listOf(account.record.kind, account.record.accountName, account.record.plan, account.apiKey))
        val torrent = f.settings.torrentIndexers.first().single()
        assertEquals(listOf("Home Prowlarr", "http://192.168.1.20:9696/3/api", false, true), listOf(torrent.name, torrent.baseUrl, torrent.useWithService, torrent.enabled))
        assertEquals("TYPED-TORRENT-KEY", f.settings.torrentIndexer(torrent.id)?.apiKey)
        assertTrue(f.vm.savedSomething.value)
    }

    /** L196: a key the service refuses on the TV is never saved, as the key form's Save keeps only a key the service takes. */
    @Test
    fun `a debrid key the service refuses at Save is never saved, only tried again or left out`() = runTest {
        val f = Fixture(this).started()
        f.service = { _, _ -> throw ServiceException(ServiceError.KeyRefused) }
        f.receive(SetupChanges(emptyList(), emptyList(), null, services = listOf(SetupChange(null, ServiceDraft("premiumize", "REFUSED-KEY"), removed = false))))
        f.vm.save()
        runCurrent()
        val key = f.key { it.kind == ReviewKind.Service }
        val failed = ItemStatus.Failed("Premiumize refused this key. Check you copied all of it.", canRetry = true, canSaveAnyway = false)
        assertEquals(failed, assertIs<PhoneSetupState.Results>(f.state).items[key])
        f.vm.saveAnyway(key)
        assertEquals(failed, assertIs<PhoneSetupState.Results>(f.state).items[key], "no key is saved untested")
        f.vm.retry(key)
        runCurrent()
        assertEquals(listOf(ServiceKind.Premiumize to "REFUSED-KEY", ServiceKind.Premiumize to "REFUSED-KEY"), f.serviceKeys)
        f.vm.leaveOut(key)
        f.vm.saveNow()
        runCurrent()
        assertEquals(ItemStatus.Done(PHONE_SETUP_LEFT_OUT, ok = false), assertIs<PhoneSetupState.Saved>(f.state).items[key])
        assertEquals(emptyList(), f.settings.services.first())
    }

    /** L209: Save gives a saved account its new key (tested on the TV first), signs a removed one out, and removes a torrent indexer. */
    @Test
    fun `Save gives a saved account its new key, signs a removed one out, and removes a torrent indexer`() = runTest {
        val f = Fixture(this)
        val tb = f.settings.saveService("TorBox", "OLD-SERVICE-KEY") { ServiceSignIn("old.viewer@example.invalid") }
        val pm = f.settings.saveService("Premiumize", "PM-SERVICE-KEY") { ServiceSignIn("customer 900000042") }
        val r = f.settings.saveTorrentIndexer(null, "Home Prowlarr", "http://192.168.1.20:9696/3/api", "SAVED-TORRENT-KEY")
        f.started()
        f.receive(
            SetupChanges(
                emptyList(), emptyList(), null,
                services = listOf(
                    SetupChange(tb, ServiceDraft("torbox", "NEW-SERVICE-KEY"), removed = false),
                    SetupChange(pm, ServiceDraft("premiumize", ""), removed = true),
                ),
                torrentIndexers = listOf(SetupChange(r, TorrentIndexerDraft("Home Prowlarr", "http://192.168.1.20:9696/3/api", "", true), removed = true)),
            ),
        )
        assertEquals(listOf("Replaces the saved key", "Removes “Premiumize”", "Removes “Home Prowlarr”"), f.received.items.map { it.heading })
        f.vm.save()
        runCurrent()
        assertIs<PhoneSetupState.Saved>(f.state)
        assertEquals(listOf(ServiceKind.TorBox to "NEW-SERVICE-KEY"), f.serviceKeys)
        val account = f.settings.service(tb)
        assertEquals(listOf("NEW-SERVICE-KEY", "quay.viewer@example.invalid"), listOf(account?.apiKey, account?.record?.accountName), "its place kept, its new key and name")
        assertNull(f.settings.service(pm), "signed out")
        assertEquals(emptyList(), f.settings.torrentIndexers.first())
    }

    /**
     * L209: a saved torrent indexer's card moved to another host with its key left empty: its test from the phone is
     * refused and Save can't take it, so the saved key reaches no request; one whose record moved meanwhile isn't saved.
     */
    @Test
    fun `a torrent indexer card moved to another host with an empty key is refused, and the saved key reaches no request`() = runTest {
        val f = Fixture(this)
        val r = f.settings.saveTorrentIndexer(null, "Home Prowlarr", "http://192.168.1.20:9696/3/api", "SAVED-TORRENT-KEY")
        val q = f.settings.saveTorrentIndexer(null, "Quarry", "https://quarry.example.invalid/api", "QUARRY-KEY")
        f.started()
        val moved = TorrentIndexerDraft("Home Prowlarr", "https://tracker.example.invalid/3/api", "", true)
        val test = f.session.tester.test(SetupTestItem.TorrentIndexer(SetupSlot(SetupSection.TorrentIndexer, 1), r, moved, plainHttp = false))
        runCurrent()
        assertEquals(SetupResult(SetupOutcome.NotTested, PHONE_TEST_ADDRESS_CHANGED), test.get(0, TimeUnit.SECONDS))
        f.receive(
            SetupChanges(
                emptyList(), emptyList(), null,
                torrentIndexers = listOf(SetupChange(r, moved, removed = false), SetupChange(q, TorrentIndexerDraft("Quarry 2", "https://quarry.example.invalid/api", "", true), removed = false)),
            ),
        )
        // Before Save, Quarry moved on the TV, with a key of its own.
        f.settings.saveTorrentIndexer(q, "Quarry", "https://moved.example.invalid/api", "OTHER-KEY")
        f.vm.save()
        runCurrent()
        val results = assertIs<PhoneSetupState.Results>(f.state)
        assertEquals(ItemStatus.Failed(INDEXER_ADDRESS_CHANGED, canRetry = false, canSaveAnyway = false), results.items[r])
        f.vm.leaveOut(r)
        f.vm.saveNow()
        runCurrent()
        assertEquals(ItemStatus.Done(PHONE_SETUP_CHANGED_MEANWHILE, ok = false), assertIs<PhoneSetupState.Saved>(f.state).items[q])
        assertFalse(f.testedKeys.any { it == "SAVED-TORRENT-KEY" || it == "OTHER-KEY" }, f.testedKeys.toString())
        assertEquals(listOf("http://192.168.1.20:9696/3/api", "https://moved.example.invalid/api"), f.settings.torrentIndexers.first().map { it.baseUrl })
        assertEquals("OTHER-KEY", f.settings.torrentIndexer(q)?.apiKey)
    }

    /** Reading what is saved (decrypting each password) takes a moment: a stop, New code or another network meanwhile wins. */
    @Test
    fun `a read of the saved settings that something ended meanwhile opens no code`() = runTest {
        val f = Fixture(this)
        f.settings.saveProvider(ProviderRecord("", "Main", "news.example.net", 563, "alice"), "saved-pass")
        f.readGate = CompletableDeferred()
        f.started()
        assertEquals(PhoneSetupState.Starting, f.state, "Starting while it reads")
        assertEquals(0, f.sessions.size)
        f.vm.stop()
        f.readGate?.complete(Unit)
        runCurrent()
        assertEquals(0, f.sessions.size, "the page left while reading, and a code opened")
        assertEquals(PhoneSetupState.Starting, f.state)
        // New code while reading: one code, from the read after it.
        f.readGate = CompletableDeferred()
        f.vm.start()
        runCurrent()
        f.vm.newCode()
        runCurrent()
        f.readGate?.complete(Unit)
        runCurrent()
        assertEquals(1, f.sessions.size)
        assertIs<PhoneSetupState.Waiting>(f.state)
        // Another network while reading: the code opens on it.
        f.vm.stop()
        f.readGate = CompletableDeferred()
        f.vm.start()
        runCurrent()
        f.addresses.value = v4("192.168.7.7")
        runCurrent()
        f.readGate?.complete(Unit)
        runCurrent()
        assertEquals(2, f.sessions.size)
        assertEquals(v4("192.168.7.7"), f.session.ip)
        // The network gone while reading: none.
        f.vm.stop()
        f.readGate = CompletableDeferred()
        f.vm.start()
        runCurrent()
        f.addresses.value = null
        advanceTimeBy(PhoneSetupViewModel.NO_NETWORK_GRACE_MS + 1)
        runCurrent()
        f.readGate?.complete(Unit)
        runCurrent()
        assertEquals(2, f.sessions.size)
        assertEquals(PhoneSetupState.NoNetwork, f.state)
    }

    /** The server keeps the code's time, on the app's clock: the countdown shows its deadline, which use moves, and ends at it. */
    @Test
    fun `the countdown follows the server's deadline, and the code closes at it`() = runTest {
        val f = Fixture(this)
        f.sessionLifetime = 180_000
        f.started()
        assertEquals(180_000, assertIs<PhoneSetupState.Waiting>(f.state).left, "the server's own deadline")
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(60_000, assertIs<PhoneSetupState.Waiting>(f.state).left)
        // The phone used the page: ten minutes from now.
        f.session.events(SetupEvent.DeadlineMoved(testScheduler.currentTime + LIFETIME))
        runCurrent()
        assertEquals(LIFETIME, assertIs<PhoneSetupState.Waiting>(f.state).left)
        advanceTimeBy(LIFETIME - 1)
        runCurrent()
        assertIs<PhoneSetupState.Waiting>(f.state)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(PhoneSetupState.Expired, f.state)
        assertTrue(f.session.closed)
    }

    /** Waiting follows the phone through the wizard's steps, by the server's events. */
    @Test
    fun `Waiting follows the phone's step`() = runTest {
        val f = Fixture(this).started()
        assertEquals(SetupStep.Providers, assertIs<PhoneSetupState.Waiting>(f.state).step)
        f.session.events(SetupEvent.Opened(phone))
        f.session.events(SetupEvent.StepChanged(SetupStep.Indexers))
        runCurrent()
        assertEquals(SetupStep.Indexers, assertIs<PhoneSetupState.Waiting>(f.state).step)
    }

    private companion object {
        const val LIFETIME = 600_000L
    }
}
