package io.skjaere.usenet.handoff

import io.skjaere.usenet.handoff.SetupAction.Add
import io.skjaere.usenet.handoff.SetupAction.Back
import io.skjaere.usenet.handoff.SetupAction.Drop
import io.skjaere.usenet.handoff.SetupAction.Enter
import io.skjaere.usenet.handoff.SetupAction.Keep
import io.skjaere.usenet.handoff.SetupAction.Next
import io.skjaere.usenet.handoff.SetupAction.Open
import io.skjaere.usenet.handoff.SetupAction.Send
import io.skjaere.usenet.handoff.SetupAction.TestCard
import io.skjaere.usenet.handoff.SetupAction.TestHttp
import io.skjaere.usenet.handoff.SetupAction.Undo
import io.skjaere.usenet.handoff.SetupField.IndexerKey
import io.skjaere.usenet.handoff.SetupField.IndexerName
import io.skjaere.usenet.handoff.SetupField.IndexerUrl
import io.skjaere.usenet.handoff.SetupField.ProviderConnections
import io.skjaere.usenet.handoff.SetupField.ProviderHost
import io.skjaere.usenet.handoff.SetupField.ProviderName
import io.skjaere.usenet.handoff.SetupField.ProviderPassword
import io.skjaere.usenet.handoff.SetupField.ProviderPort
import io.skjaere.usenet.handoff.SetupField.ProviderTls
import io.skjaere.usenet.handoff.SetupField.ProviderUsername
import io.skjaere.usenet.handoff.SetupField.ServiceKey
import io.skjaere.usenet.handoff.SetupField.ServiceKind
import io.skjaere.usenet.handoff.SetupField.TmdbKey
import io.skjaere.usenet.handoff.SetupField.TorrentIndexerDebrid
import io.skjaere.usenet.handoff.SetupField.TorrentIndexerKey
import io.skjaere.usenet.handoff.SetupField.TorrentIndexerName
import io.skjaere.usenet.handoff.SetupField.TorrentIndexerUrl
import io.skjaere.usenet.handoff.SetupSection.Indexer
import io.skjaere.usenet.handoff.SetupSection.Provider
import io.skjaere.usenet.handoff.SetupSection.Service
import io.skjaere.usenet.handoff.SetupSection.TorrentIndexer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The session's page on the TV without a socket: steps, cards, what each holds, its tests, and what Send hands over. */
class SetupFlowTest {
    private val main = SavedProvider("prov-1", "Main provider", "news.example.invalid", 563, true, "someone", 20, true, SavedSecret.Saved)
    // Named after its host, so its name is blank as the provider form blanks it; switched off; no login.
    private val block = SavedProvider("prov-2", "", "block.example.invalid", 119, false, "", 8, false, SavedSecret.None)
    private val indexerA = SavedIndexer("idx-1", "Indexer A", "https://indexer-a.example.invalid/api", false, true, SavedSecret.Saved)
    private val home = SavedIndexer("idx-2", "Home", "http://192.168.1.10:9696/1", true, false, SavedSecret.Saved)
    private val seed = SetupSeed(listOf(main, block), listOf(indexerA, home), SavedSecret.Saved)

    private val p1 = SetupSlot(Provider, 1)
    private val p2 = SetupSlot(Provider, 2)
    private val p3 = SetupSlot(Provider, 3)
    private val i1 = SetupSlot(Indexer, 1)
    private val i2 = SetupSlot(Indexer, 2)
    private val t = SetupSlot.Tmdb

    private val works = SetupResult(SetupOutcome.Passed, "Connected and logged in")
    private val fails = SetupResult(SetupOutcome.Failed, "Login refused")

    /** A provider card's fields as the page posts them: every input, the box only when ticked. */
    private fun provider(name: String = "", host: String = "", port: String = "", tls: Boolean = true, user: String = "", pass: String = "", conns: String = "") =
        listOfNotNull(
            ProviderName to name, ProviderHost to host, ProviderPort to port, (ProviderTls to "on").takeIf { tls },
            ProviderUsername to user, ProviderPassword to pass, ProviderConnections to conns,
        ).toTypedArray()

    private fun indexer(name: String = "", url: String = "", key: String = "") = arrayOf(IndexerName to name, IndexerUrl to url, IndexerKey to key)

    /** [main]'s card as the page shows it, posted back as it is. */
    private val mainAsShown = provider("Main provider", "news.example.invalid", "563", true, "someone", "", "20")

    private val typed = provider("Mine", "news.example.invalid", "563", true, "someone", "marker-pass", "")

    private fun SetupFlow.post(
        action: SetupAction,
        vararg fields: Pair<SetupField, String>,
        problems: Map<SetupField, SetupRejection.Reason> = emptyMap(),
        closingSoon: Boolean = false,
    ): Applied = apply(SetupPost(version, action, fields.toMap(), problems), closingSoon)

    /** Tests what [action] tests, and writes [result] onto it; the card's slot afterwards. */
    private fun SetupFlow.tested(action: SetupAction, vararg fields: Pair<SetupField, String>, result: SetupResult = works): SetupSlot? =
        finish(assertIs<Applied.Testing>(post(action, *fields), action.value).ticket, result)

    private fun SetupFlow.view() = show()

    private fun SetupView.card(slot: SetupSlot) = cards.single { it.slot == slot }

    // The debrid cards (L209): offered only while the seed names the services, A to Z, as the app gives them.

    private val choices = listOf(
        ServiceChoice("premiumize", "Premiumize", "In Premiumize: Account › API key"),
        ServiceChoice("torbox", "TorBox", "In TorBox: Settings › API key"),
    )
    private val torbox = SavedService("svc-1", "torbox", "quay.viewer@example.invalid", true, SavedSecret.Saved)
    private val prowlarr = SavedTorrentIndexer("tor-1", "Home Prowlarr", "http://192.168.1.20:9696/3/api", false, true, true, SavedSecret.Saved)
    private val debridSeed = seed.copy(serviceKinds = choices, services = listOf(torbox), torrentIndexers = listOf(prowlarr))
    private val s1 = SetupSlot(Service, 1)
    private val s2 = SetupSlot(Service, 2)
    private val r1 = SetupSlot(TorrentIndexer, 1)
    private val r2 = SetupSlot(TorrentIndexer, 2)
    private val signedIn = SetupResult(SetupOutcome.Passed, "Signed in as quay.viewer@example.invalid")

    /** A service card's fields as the page posts them: the radio only when one is chosen. */
    private fun service(kind: String? = null, key: String = "") = listOfNotNull(kind?.let { ServiceKind to it }, ServiceKey to key).toTypedArray()

    /** A torrent indexer card's fields as the page posts them: the box only when ticked. */
    private fun torrent(name: String = "", url: String = "", key: String = "", debrid: Boolean = true) =
        listOfNotNull(TorrentIndexerName to name, TorrentIndexerUrl to url, TorrentIndexerKey to key, (TorrentIndexerDebrid to "on").takeIf { debrid }).toTypedArray()

    private val prowlarrAsShown = torrent("Home Prowlarr", prowlarr.shownUrl)

    /** Hidden, the page has no debrid section, whatever is saved: none of its slots or buttons is one a form may send (L192). */
    @Test
    fun `the debrid sections are the page's only while the seed offers its services`() {
        val hidden = SetupFlow(SetupMode.Edit, seed.copy(services = listOf(torbox), torrentIndexers = listOf(prowlarr)))
        val view = hidden.view()
        assertFalse(view.debrid)
        assertEquals(emptyList(), view.services + view.torrentIndexers)
        assertEquals(listOf(p1, p2, i1, i2, t), view.cards.map { it.slot })
        val layout = hidden.layout()
        for (action in listOf(Add(Service), Add(TorrentIndexer), Open(s1), TestCard(s1), Drop(s1), Undo(s1), Open(r1), Drop(r1))) {
            assertFalse(layout.offers(action), action.value)
        }
        for (body in listOf("v=1&do=add%3Aservice", "v=1&do=add%3Atorrent-indexer", "v=1&do=open%3As1", "v=1&do=drop%3Ar1", "v=1&do=test&s.key=K")) {
            assertIs<SetupRejection>(SetupForm.parse(body.toByteArray(), layout), body)
        }
        hidden.post(Drop(p2))
        val sent = assertIs<Applied.Sent>(hidden.post(Send)).changes
        assertEquals(emptyList(), sent.services)
        assertEquals(emptyList(), sent.torrentIndexers)

        val shown = SetupFlow(SetupMode.Edit, debridSeed)
        val cards = shown.view()
        assertTrue(cards.debrid)
        assertEquals(listOf(p1, p2, i1, i2, s1, r1, t), cards.cards.map { it.slot }, "after Indexers, as Settings orders them")
        for (action in listOf(Add(Service), Add(TorrentIndexer), Open(s1), TestCard(s1), Drop(s1), Undo(s1), Open(r1), TestCard(r1), Drop(r1), Undo(r1))) {
            assertTrue(shown.layout().offers(action), action.value)
        }
        val account = cards.card(s1)
        assertEquals(listOf(true, false, true, SecretState.Saved), listOf(account.saved, account.changed, account.ready, account.secret))
        assertEquals(mapOf(ServiceKind to "torbox"), account.values)
        assertEquals(listOf("TorBox", "quay.viewer@example.invalid"), listOf(account.savedName, account.account))
        val indexer = cards.card(r1)
        assertEquals(mapOf(TorrentIndexerName to "Home Prowlarr", TorrentIndexerUrl to prowlarr.shownUrl, TorrentIndexerDebrid to "on"), indexer.values)
        assertEquals(listOf(true, false, true), listOf(indexer.saved, indexer.changed, indexer.ready))
        assertIs<Applied.Shown>(shown.post(Send))
        assertEquals(SetupNotice.NothingToSend, shown.show().notice)
    }

    /** One card for each service (L209): a card's radio offers only the services no other card holds, a saved one its own alone. */
    @Test
    fun `a service card's radio offers the services no other card holds, so a second TorBox card is refused`() {
        val flow = SetupFlow(SetupMode.Edit, debridSeed)
        flow.post(Open(s1))
        assertEquals(setOf("torbox"), flow.layout().choices, "a saved account keeps its service")
        assertEquals(listOf("torbox"), flow.view().card(s1).choices.map { it.value })
        flow.post(Add(Service), *service("torbox"))
        assertEquals(s2, flow.layout().open)
        assertEquals(setOf("premiumize"), flow.layout().choices)
        assertEquals(listOf("Premiumize"), flow.view().card(s2).choices.map { it.name })
        val second = "v=${flow.version}&do=test&s.kind=torbox&s.key=marker-key".toByteArray()
        assertEquals(SetupRejection(ServiceKind, SetupRejection.Reason.Malformed), SetupForm.parse(second, flow.layout()))
        assertIs<Applied.Testing>(flow.post(SetupAction.Test, *service("premiumize", "marker-key")))
        assertFalse(flow.layout().offers(Add(Service)), "a card for each service already")
        // Don't add frees its service for another card.
        flow.post(Drop(s2), *service("premiumize"))
        assertTrue(flow.layout().offers(Add(Service)))
        // With none saved, a new card offers each, A to Z as the seed gives them, none chosen.
        val fresh = SetupFlow(SetupMode.Edit, seed.copy(serviceKinds = choices))
        fresh.post(Add(Service))
        assertEquals(setOf("premiumize", "torbox"), fresh.layout().choices)
        val card = fresh.view().card(s1)
        assertEquals(listOf("Premiumize", "TorBox"), card.choices.map { it.name })
        assertEquals(mapOf(ServiceKind to ""), card.values)
        assertTrue(card.blank, "no service chosen and no key: blank, left out")
        fresh.post(Add(Service), *service("torbox"))
        assertEquals(mapOf(ServiceKind to "torbox"), fresh.view().card(s1).values)
        assertEquals(setOf("premiumize"), fresh.layout().choices)
        assertFalse(fresh.layout().offers(Add(Service)))
    }

    @Test
    fun `a service card has nothing to test until a service is chosen, then tests it with its key or the saved one`() {
        val flow = SetupFlow(SetupMode.Edit, debridSeed)
        flow.post(Add(Service))
        assertIs<Applied.Shown>(flow.post(SetupAction.Test, *service(key = "marker-key")))
        assertEquals(SetupNotice.NothingToTest(s2), flow.show().notice)
        val item = assertIs<Applied.Testing>(flow.post(SetupAction.Test, *service("premiumize", ""))).ticket.item
        assertEquals(SetupTestItem.Service(s2, null, ServiceDraft("premiumize", "marker-key")), item)
        assertFalse(item.toString().contains("marker"), item.toString())
        val saved = assertIs<Applied.Testing>(flow.post(TestCard(s1), *service("premiumize", ""))).ticket.item
        assertEquals(SetupTestItem.Service(s1, "svc-1", ServiceDraft("torbox", "")), saved)
    }

    @Test
    fun `a saved service is as saved until a key is typed for it, which needs a test, and Send hands it over`() {
        val flow = SetupFlow(SetupMode.Edit, debridSeed)
        flow.post(Open(s1))
        flow.post(Open(r1), *service("torbox", "marker-new-key"))
        val typed = flow.view().card(s1)
        assertEquals(listOf(true, false, Need.Test, SecretState.Typed), listOf(typed.changed, typed.ready, typed.need, typed.secret))
        assertIs<Applied.Shown>(flow.post(Send, *prowlarrAsShown))
        flow.tested(TestCard(s1), *prowlarrAsShown, result = signedIn)
        assertEquals(signedIn, flow.view().card(s1).result)
        val sent = assertIs<Applied.Sent>(flow.post(Send, *prowlarrAsShown)).changes
        assertEquals(listOf(SetupChange("svc-1", ServiceDraft("torbox", "marker-new-key"), removed = false)), sent.services)
        assertEquals(listOf(SetupChange("tor-1", TorrentIndexerDraft("Home Prowlarr", prowlarr.shownUrl, "", true), removed = false)), sent.torrentIndexers)
        // Remove signs it out on the TV; its draft as saved, no key.
        val removing = SetupFlow(SetupMode.Edit, debridSeed)
        removing.post(Drop(s1))
        assertTrue(removing.view().card(s1).removed)
        assertEquals(listOf(SetupChange("svc-1", ServiceDraft("torbox", ""), removed = true)), assertIs<Applied.Sent>(removing.post(Send)).changes.services)
        // A key the TV can't read must be typed, or the account removed.
        val lost = SetupFlow(SetupMode.Edit, debridSeed.copy(services = listOf(torbox.copy(key = SavedSecret.Unreadable))))
        assertEquals(Need.Secret, lost.view().card(s1).need)
    }

    /**
     * A torrent indexer card is an indexer's (its address's Test anyway, its hidden parts), with "Use with your debrid
     * service" ticked when new; the box changes nothing a test reads, so it needs none.
     */
    @Test
    fun `a torrent indexer card is an indexer's with its box, which is ticked when new and needs no test`() {
        val flow = SetupFlow(SetupMode.Edit, debridSeed)
        flow.post(Add(TorrentIndexer))
        assertEquals(r2, flow.layout().open)
        assertEquals(listOf("r.name", "r.url", "r.key", "r.debrid"), flow.layout().fields.map { it.key })
        assertTrue(flow.layout().offers(TestHttp))
        val blank = flow.view().card(r2)
        assertEquals(mapOf(TorrentIndexerName to "", TorrentIndexerUrl to "", TorrentIndexerDebrid to "on"), blank.values)
        assertTrue(blank.blank)
        val quarry = torrent("Quarry", "https://quarry.example.invalid/api", "marker-key", debrid = false)
        val item = assertIs<Applied.Testing>(flow.post(SetupAction.Test, *quarry)).ticket.item
        assertEquals(SetupTestItem.TorrentIndexer(r2, null, TorrentIndexerDraft("Quarry", "https://quarry.example.invalid/api", "marker-key", false), plainHttp = false), item)
        val plain = torrent("Quarry", "http://quarry.example.invalid/api", "", debrid = false)
        val anyway = assertIs<SetupTestItem.TorrentIndexer>(assertIs<Applied.Testing>(flow.post(TestHttp, *plain)).ticket.item)
        assertTrue(anyway.plainHttp)
        flow.finish(assertIs<Applied.Testing>(flow.post(SetupAction.Test, *plain)).ticket, works)
        // Ticked again after its pass: still that pass, as the box isn't tested.
        flow.post(SetupAction.Enter, *torrent("Quarry", "http://quarry.example.invalid/api", "", debrid = true))
        assertEquals(works, flow.view().card(r2).result)
        // The saved one unticked: a change, ready without a test.
        flow.post(Open(r1), *torrent("Quarry", "http://quarry.example.invalid/api", "", debrid = true))
        flow.post(Open(p1), *torrent("Home Prowlarr", prowlarr.shownUrl, "", debrid = false))
        val saved = flow.view().card(r1)
        assertEquals(listOf(true, true, null), listOf(saved.changed, saved.ready, saved.need))
        assertEquals(TorrentIndexerDraft("Home Prowlarr", prowlarr.shownUrl, "", false), flow.changes().torrentIndexers.first().draft)
        // Its address moved: a test, and its hidden parts no longer its.
        val hidden = SetupFlow(SetupMode.Edit, debridSeed.copy(torrentIndexers = listOf(prowlarr.copy(hiddenParts = true))))
        hidden.post(Open(r1))
        assertTrue(hidden.view().card(r1).hiddenParts)
        hidden.post(Open(p1), *torrent("Home Prowlarr", "http://192.168.1.21:9696/3/api"))
        assertEquals(listOf(false, Need.Test), hidden.view().card(r1).let { listOf(it.hiddenParts, it.need) })
    }

    /** L209: in the wizard, the debrid service card sits on step 1 under the providers, the torrent indexer card on step 2 under the indexers. */
    @Test
    fun `in the wizard a debrid service is added on step 1 and a torrent indexer on step 2`() {
        val flow = SetupFlow(SetupMode.Wizard, SetupSeed(serviceKinds = choices))
        assertEquals(setOf(Enter, SetupAction.Test, TestCard(p1), Drop(p1), Add(Provider), Add(Service), Next), flow.layout().actions)
        flow.post(Add(Service), *provider())
        assertEquals(emptyList(), flow.view().providers, "the blank provider goes as the service card opens")
        assertEquals(listOf(s1), flow.view().services.map { it.slot })
        flow.post(Next, *service("torbox", "marker-service-key"))
        assertEquals(SetupStep.Providers, flow.step, "an untested card holds up Next")
        flow.tested(SetupAction.Test, *service("torbox", ""), result = signedIn)
        flow.post(Next, *service("torbox", ""))
        assertEquals(SetupStep.Indexers, flow.step)
        assertTrue(flow.layout().offers(Add(TorrentIndexer)))
        assertFalse(flow.layout().offers(Add(Service)) || flow.layout().offers(Open(s1)), "step 1's cards stay on step 1")
        flow.post(Add(TorrentIndexer), *indexer())
        assertEquals(emptyList(), flow.view().indexers)
        val home = torrent("Home Prowlarr", "http://192.168.1.20:9696/3/api", "marker-torrent-key")
        flow.tested(SetupAction.Test, *home)
        flow.post(Next, *torrent("Home Prowlarr", "http://192.168.1.20:9696/3/api"))
        flow.post(Next, TmdbKey to "")
        assertEquals(SetupStep.Check, flow.step)
        val sent = assertIs<Applied.Sent>(flow.post(Send)).changes
        assertEquals(emptyList(), sent.providers)
        assertEquals(emptyList(), sent.indexers)
        assertEquals(listOf(SetupChange(null, ServiceDraft("torbox", "marker-service-key"), removed = false)), sent.services)
        assertEquals(listOf(SetupChange(null, TorrentIndexerDraft("Home Prowlarr", "http://192.168.1.20:9696/3/api", "marker-torrent-key", true), removed = false)), sent.torrentIndexers)
        // A blank service card left on step 1 goes at Next, as a blank provider does.
        val left = SetupFlow(SetupMode.Wizard, SetupSeed(serviceKinds = choices))
        left.post(Add(Service), *provider())
        left.post(Next, *service())
        assertEquals(listOf(SetupStep.Indexers, 0), listOf(left.step, left.view().services.size))
    }

    /**
     * Send again over the debrid cards: each saved one as it was sent, new ones after, the keys typed kept for the same
     * service and address alone; a new card for a service another card now holds isn't brought back.
     */
    @Test
    fun `a page from an earlier one's debrid cards keeps their keys for the same service and address alone`() {
        val sent = SetupChanges(
            emptyList(), emptyList(), null,
            services = listOf(
                SetupChange("svc-1", ServiceDraft("torbox", "marker-torbox"), removed = false),
                SetupChange(null, ServiceDraft("premiumize", "marker-premiumize"), removed = false),
                SetupChange(null, ServiceDraft("torbox", "marker-second"), removed = false),
            ),
            torrentIndexers = listOf(
                SetupChange("tor-1", TorrentIndexerDraft("Home Prowlarr", prowlarr.shownUrl, "marker-prowlarr", false), removed = false),
                SetupChange(null, TorrentIndexerDraft("Quarry", "https://quarry.example.invalid/api", "marker-quarry", true), removed = false),
            ),
        )
        val flow = SetupFlow(SetupMode.Edit, debridSeed, from = sent)
        val view = flow.view()
        assertEquals(listOf(s1, s2), view.services.map { it.slot })
        assertEquals(listOf(mapOf(ServiceKind to "torbox"), mapOf(ServiceKind to "premiumize")), view.services.map { it.values })
        assertTrue(view.services.all { it.secret == SecretState.Typed && it.result == null })
        assertEquals(listOf(r1, r2), view.torrentIndexers.map { it.slot })
        assertEquals("", view.card(r1).values[TorrentIndexerDebrid], "unticked as sent")
        assertEquals(
            listOf(ServiceDraft("torbox", "marker-torbox"), ServiceDraft("premiumize", "marker-premiumize")),
            flow.changes().services.map { it.draft },
        )
        // Moved to another address, the key typed then stays behind.
        flow.post(Open(r2))
        flow.post(Open(p1), *torrent("Quarry", "https://elsewhere.example.invalid/api"))
        assertEquals(listOf("marker-prowlarr", ""), flow.changes().torrentIndexers.map { it.draft.key })
        // A new card's key typed for TorBox, its card turned to another service by the new code's owner: the key stays behind.
        val typedForTorBox = SetupChanges(emptyList(), emptyList(), null, services = listOf(SetupChange(null, ServiceDraft("torbox", "marker-torbox"), removed = false)))
        val switched = SetupFlow(SetupMode.Edit, seed.copy(serviceKinds = choices), from = typedForTorBox)
        switched.post(Open(s1))
        switched.post(Open(p1), *service("premiumize"))
        assertEquals(listOf(ServiceDraft("premiumize", "")), switched.changes().services.map { it.draft })
        assertEquals(SecretState.None, switched.view().card(s1).secret)
        // Without the debrid sections, nothing of them comes back.
        val hidden = SetupFlow(SetupMode.Edit, seed, from = sent)
        assertEquals(emptyList(), hidden.changes().services)
        assertEquals(emptyList(), hidden.changes().torrentIndexers)
    }

    @Test
    fun `cards can be added up to the caps, torrent indexers too`() {
        val flow = SetupFlow(SetupMode.Edit, SetupSeed(serviceKinds = choices), SetupLimits(torrentIndexers = 2))
        flow.post(Add(TorrentIndexer))
        flow.post(Add(TorrentIndexer), *torrent("One", "https://one.example.invalid/api", "k"))
        assertFalse(flow.layout().offers(Add(TorrentIndexer)))
        assertFalse(flow.view().canAddTorrentIndexer)
        assertTrue(flow.view().canAddService)
    }

    @Test
    fun `a wizard with nothing saved starts on step 1 with one blank provider open`() {
        val flow = SetupFlow(SetupMode.Wizard, SetupSeed())
        assertEquals(SetupStep.Providers, flow.step)
        assertEquals(1, flow.version)
        val layout = flow.layout()
        assertEquals(p1, layout.open)
        assertEquals(listOf("p.name", "p.host", "p.port", "p.tls", "p.user", "p.pass", "p.conns"), layout.fields.map { it.key })
        assertEquals(
            setOf(Enter, SetupAction.Test, TestCard(p1), Drop(p1), Add(Provider), Next),
            layout.actions,
        )
        val view = flow.view()
        val card = view.providers.single()
        assertEquals(listOf(true, true, true, false), listOf(card.open, card.blank, card.ready, card.saved))
        assertEquals(mapOf(ProviderName to "", ProviderHost to "", ProviderPort to "", ProviderTls to "on", ProviderUsername to "", ProviderConnections to ""), card.values)
        assertEquals(SecretState.None, card.secret)
        assertTrue(view.ready, "a blank card doesn't hold up Next")
        assertEquals(emptyList(), view.indexers)
        assertEquals(SecretState.None, view.tmdb.secret)
    }

    @Test
    fun `the edit page shows every saved card in its order, none of them open`() {
        val flow = SetupFlow(SetupMode.Edit, seed)
        assertEquals(SetupStep.Edit, flow.step)
        val layout = flow.layout()
        assertNull(layout.open)
        assertEquals(emptyList(), layout.fields)
        for (slot in listOf(p1, p2, i1, i2)) {
            for (action in listOf(TestCard(slot), Open(slot), Drop(slot), Undo(slot))) assertTrue(layout.offers(action), action.value)
            assertFalse(layout.offers(Keep(slot)), slot.key)
        }
        assertTrue(layout.offers(Open(t)) && layout.offers(Undo(t)) && layout.offers(TestCard(t)))
        assertFalse(layout.offers(Drop(t)), "the TMDB key isn't removed from the phone")
        for (action in listOf(Add(Provider), Add(Indexer), Send, Enter)) assertTrue(layout.offers(action), action.value)
        for (action in listOf(Next, Back, SetupAction.Test, TestHttp, Open(p3), Drop(SetupSlot(Indexer, 3)))) assertFalse(layout.offers(action), action.value)

        val view = flow.view()
        assertEquals(listOf(p1, p2), view.providers.map { it.slot })
        assertEquals(listOf("Main provider", ""), view.providers.map { it.savedName })
        assertEquals(listOf(true, false), view.providers.map { it.enabled })
        assertEquals(listOf(SecretState.Saved, SecretState.None), view.providers.map { it.secret })
        assertEquals("news.example.invalid", view.providers[0].values[ProviderHost])
        assertEquals(listOf("563", "on", "20"), listOf(ProviderPort, ProviderTls, ProviderConnections).map { view.providers[0].values[it] })
        assertEquals("", view.providers[1].values[ProviderTls], "TLS off")
        assertEquals(listOf(i1, i2), view.indexers.map { it.slot })
        assertEquals(listOf(false, true), view.indexers.map { it.hiddenParts })
        assertEquals(home.shownUrl, view.indexers[1].values[IndexerUrl])
        assertEquals(SecretState.Saved, view.tmdb.secret)
        assertTrue((view.providers + view.indexers + view.tmdb).all { it.ready && !it.changed && !it.open })
        assertFalse(view.changed)
        assertFalse(view.ready, "nothing to send")
    }

    /**
     * Send again: a new code's page starts from what the last one sent, its cards as they were (changed, removed or new)
     * and the passwords and keys typed into them still on the TV, never in the page. A card whose record went meanwhile
     * comes back new, unless it was to be removed.
     */
    @Test
    fun `a page can start from what an earlier one sent, typed secrets kept on the TV`() {
        val sent = SetupChanges(
            providers = listOf(
                SetupChange(main.ref, ProviderDraft("Renamed", "news.example.invalid", 443, true, "someone", "marker-pass", 20), removed = false),
                SetupChange(block.ref, ProviderDraft("", "block.example.invalid", 119, false, "", "", 8), removed = true),
                SetupChange("prov-gone", ProviderDraft("Gone", "gone.example.invalid", 563, true, "", "", 8), removed = true),
                SetupChange("prov-moved", ProviderDraft("Moved", "moved.example.invalid", 563, true, "x", "marker-moved", null), removed = false),
                SetupChange(null, ProviderDraft("New", "new.example.invalid", null, true, "me", "marker-new", null), removed = false),
            ),
            indexers = listOf(
                SetupChange(home.ref, IndexerDraft("Home", home.shownUrl, "marker-key"), removed = false),
                SetupChange(null, IndexerDraft("", "https://new.example.invalid/api", "marker-new-key"), removed = false),
            ),
            tmdbKey = "marker-tmdb",
        )
        val flow = SetupFlow(SetupMode.Edit, seed, from = sent)
        val view = flow.view()
        assertEquals(listOf("Renamed", "", "Moved", "New"), view.providers.map { it.values[ProviderName] })
        assertEquals(listOf(false, true, false, false), view.providers.map { it.removed })
        assertEquals(listOf(true, true, false, false), view.providers.map { it.saved })
        assertEquals("443", view.providers[0].values[ProviderPort])
        assertEquals(listOf(SecretState.Typed, SecretState.None, SecretState.Typed, SecretState.Typed), view.providers.map { it.secret })
        assertEquals(listOf("Indexer A", "Home", ""), view.indexers.map { it.values[IndexerName] })
        assertEquals(listOf(SecretState.Saved, SecretState.Typed, SecretState.Typed), view.indexers.map { it.secret })
        assertTrue(view.indexers[1].hiddenParts, "still the address as shown")
        assertEquals(SecretState.Typed, view.tmdb.secret)
        // Changed cards wait for a test again; the secrets go with them, and only to Send.
        assertFalse(view.ready)
        val changes = flow.changes()
        assertEquals(listOf("marker-pass", "", "marker-moved", "marker-new"), changes.providers.map { it.draft.password })
        assertEquals(listOf(main.ref, block.ref, null, null), changes.providers.map { it.ref })
        assertEquals(listOf("", "marker-key", "marker-new-key"), changes.indexers.map { it.draft.apiKey })
        assertEquals("marker-tmdb", changes.tmdbKey)
        // The wizard starts from them too, at its first step; with nothing saved, the saved ones sent come back new.
        val wizard = SetupFlow(SetupMode.Wizard, SetupSeed(), from = sent)
        assertEquals(SetupStep.Providers, wizard.step)
        assertEquals(listOf("Renamed", "Moved", "New"), wizard.view().providers.map { it.values[ProviderName] })
        assertNull(wizard.layout().open, "its cards are there, none open")
    }

    /**
     * Send again's code is a new session, which the first device to open it owns, maybe not the one that typed the last
     * page's passwords: those stay only with the server each was sent for, so the new page can't test or send one
     * elsewhere. One typed on the new page is its owner's own.
     */
    @Test
    fun `a password or key from an earlier page goes only to the server it was sent for`() {
        val sent = SetupChanges(
            providers = listOf(SetupChange(null, ProviderDraft("Mine", "news.example.invalid", null, true, "someone", "marker-pass", null), removed = false)),
            indexers = listOf(SetupChange(null, IndexerDraft("", "https://indexer.example.invalid/api", "marker-key"), removed = false)),
            tmdbKey = null,
        )
        val flow = SetupFlow(SetupMode.Edit, SetupSeed(), from = sent)
        fun SetupTestItem.secret() = when (this) {
            is SetupTestItem.Provider -> draft.password
            is SetupTestItem.Indexer -> draft.apiKey
            is SetupTestItem.Service -> draft.key
            is SetupTestItem.TorrentIndexer -> draft.key
            is SetupTestItem.Tmdb -> key
        }
        fun Applied.secret() = assertIs<Applied.Testing>(this).ticket.item.secret()
        // Renamed only: its server still, so its password still.
        flow.post(Open(p1))
        assertEquals("marker-pass", flow.post(SetupAction.Test, *provider("Renamed", "news.example.invalid", "", true, "someone", "", "")).secret())
        // Pointed at another server, the password left empty: tested without it, and gone from the card.
        val elsewhere = provider("Renamed", "nntp.elsewhere.invalid", "119", false, "someone", "", "")
        assertEquals("", flow.post(SetupAction.Test, *elsewhere).secret())
        assertEquals(SecretState.None, flow.view().card(p1).secret)
        assertEquals("", flow.post(Enter, *provider("Renamed", "news.example.invalid", "", true, "someone", "", "")).secret(), "back to its server, it stays gone")
        // The indexer the same.
        assertEquals("marker-key", flow.post(TestCard(i1), *provider("Renamed", "news.example.invalid", "", true, "someone", "", "")).secret())
        flow.post(Open(i1), *provider("Renamed", "news.example.invalid", "", true, "someone", "", ""))
        assertEquals("", flow.post(SetupAction.Test, *indexer("", "https://indexer.elsewhere.invalid/api", "")).secret())
        val changes = flow.changes()
        assertEquals(listOf("", ""), listOf(changes.providers.single().draft.password, changes.indexers.single().draft.apiKey))
        assertFalse(SetupPage.render(flow.show()).contains("marker"))
        // Typed on this page, a password is the owner's own, wherever it goes.
        assertEquals("marker-new", flow.post(SetupAction.Test, *indexer("", "https://indexer.elsewhere.invalid/api", "marker-new")).secret())
        // A saved card's resumed password the same: it then falls back to the saved one's rule, which the app applies.
        val resumed = SetupChange(main.ref, ProviderDraft("Main provider", "news.example.invalid", 563, true, "someone", "marker-pass", 20), removed = false)
        val saved = SetupFlow(SetupMode.Edit, seed, from = SetupChanges(listOf(resumed), emptyList(), null))
        saved.post(Open(p1))
        assertEquals("", saved.post(SetupAction.Test, *mainAsShown.map { if (it.first == ProviderHost) it.first to "nntp.elsewhere.invalid" else it }.toTypedArray()).secret())
        assertEquals(SecretState.Saved, saved.view().card(p1).secret)
    }

    @Test
    fun `the render counter goes up when the cards, the open one or the step change, and not for a test`() {
        val flow = SetupFlow(SetupMode.Wizard, SetupSeed())
        assertEquals(1, flow.version)
        flow.tested(SetupAction.Test, *typed)
        assertEquals(1, flow.version, "a test and its result")
        assertIs<Applied.Testing>(flow.post(Enter, *typed))
        assertEquals(1, flow.version)
        flow.post(Add(Provider), *typed)
        assertEquals(2, flow.version)
        flow.post(Open(p1), *provider())
        assertEquals(3, flow.version)
        flow.post(Next, *typed)
        assertEquals(listOf(4, SetupStep.Indexers), listOf(flow.version, flow.step))
        flow.post(Back, *indexer())
        assertEquals(5, flow.version)

        val edit = SetupFlow(SetupMode.Edit, seed)
        edit.post(Drop(p2))
        edit.post(Keep(p2))
        edit.post(Undo(p1))
        assertEquals(4, edit.version)
        edit.post(Send)
        assertEquals(4, edit.version, "a refused Send changes nothing on the page")
    }

    @Test
    fun `a form from an older page changes nothing and says so once`() {
        val flow = SetupFlow(SetupMode.Wizard, SetupSeed())
        flow.post(Add(Provider), *typed)
        val before = flow.view()
        val old = flow.apply(SetupPost(1, Next, provider(host = "changed.example.invalid").toMap()))
        assertEquals(Applied.Shown(SetupFlow.TOP), old)
        assertEquals(SetupStep.Providers, flow.step)
        val after = flow.view()
        assertEquals(SetupNotice.Stale, after.notice)
        assertEquals(before.providers.map { it.values }, after.providers.map { it.values })
        assertNull(flow.view().notice, "shown once")
        // A button the page doesn't have reads the same way; the parser refuses one before it gets here.
        assertEquals(Applied.Shown(SetupFlow.TOP), flow.post(Send))
        assertEquals(SetupNotice.Stale, flow.view().notice)
    }

    /** Two forms from one page before the phone fetched the first one's answer: the page after them has the newer one's line. */
    @Test
    fun `a notice is the newest form's, not one left from a form before it`() {
        val flow = SetupFlow(SetupMode.Wizard, SetupSeed())
        flow.tested(SetupAction.Test, *typed)
        flow.post(Add(Provider), *typed)
        // Next refused on the new card, then Don't add on it, both from the same page.
        flow.post(Next, *provider(host = "other.example.invalid"))
        flow.post(Drop(p2), *provider(host = "other.example.invalid"))
        assertNull(flow.view().notice, "the refused Next's line, for a card that has gone")
        // Test with nothing to test, then Add.
        flow.post(Add(Provider))
        flow.post(SetupAction.Test, *provider())
        flow.post(Add(Provider), *provider())
        assertNull(flow.view().notice, "nothing to test, over the card added after it")
    }

    @Test
    fun `the open card takes what was posted before any button acts, and a typed secret stays on the TV`() {
        val flow = SetupFlow(SetupMode.Wizard, SetupSeed())
        assertEquals(Applied.Shown("p2"), flow.post(Add(Provider), *typed))
        val view = flow.view()
        val first = view.card(p1)
        assertFalse(first.open)
        assertEquals("news.example.invalid", first.values[ProviderHost])
        assertEquals(SecretState.Typed, first.secret)
        assertFalse(first.values.containsKey(ProviderPassword))
        assertEquals(p2, view.open?.slot)
        assertEquals("marker-pass", flow.changes().providers.single().draft.password)
        // Left empty, a field for a secret keeps the one held; a new one replaces it.
        flow.post(Open(p1), *provider())
        flow.post(Enter, *typed.map { if (it.first == ProviderPassword) it.first to "" else it }.toTypedArray())
        assertEquals("marker-pass", flow.changes().providers.single().draft.password)
        flow.post(Next, *typed.map { if (it.first == ProviderPassword) it.first to "marker-new" else it }.toTypedArray())
        assertEquals("marker-new", flow.changes().providers.single().draft.password)
        // The TLS box unticked is off: a box a browser doesn't send.
        flow.post(Add(Provider), *typed.map { if (it.first == ProviderPassword) it.first to "" else it }.toTypedArray())
        flow.post(Add(Provider), *provider(host = "plain.example.invalid", tls = false))
        assertEquals(listOf(true, false), flow.changes().providers.map { it.draft.useTls })
        assertEquals("marker-new", flow.changes().providers.first().draft.password)
    }

    @Test
    fun `Back keeps everything typed, secrets too, and Next goes on only once the step is ready again`() {
        val flow = SetupFlow(SetupMode.Wizard, SetupSeed())
        flow.tested(SetupAction.Test, *typed)
        flow.post(Next, *typed)
        assertEquals(SetupStep.Indexers, flow.step)
        assertEquals(i1, flow.layout().open)
        flow.post(Back, *indexer("Mine", "https://indexer.example.invalid/api", "marker-key"))
        assertEquals(SetupStep.Providers, flow.step)
        assertNull(flow.layout().open, "a step with cards opens none")
        flow.post(Next)
        assertEquals(SetupStep.Indexers, flow.step)
        val card = flow.view().indexers.single()
        assertEquals(listOf(false, SecretState.Typed), listOf(card.open, card.secret))
        assertEquals("https://indexer.example.invalid/api", card.values[IndexerUrl])
        assertEquals("marker-key", flow.changes().indexers.single().draft.apiKey)
        assertEquals("marker-pass", flow.changes().providers.single().draft.password)
    }

    @Test
    fun `Next waits for a new card that hasn't passed, and for one changed since it did`() {
        val flow = SetupFlow(SetupMode.Wizard, SetupSeed())
        assertEquals(Applied.Shown("p1"), flow.post(Next, *typed))
        assertEquals(SetupStep.Providers, flow.step)
        val view = flow.view()
        assertEquals(SetupNotice.NotReady(p1), view.notice)
        assertEquals(listOf(p1, Need.Test), listOf(view.blocking?.slot, view.blocking?.need))
        assertFalse(view.ready)

        flow.tested(SetupAction.Test, *typed, result = fails)
        assertIs<Applied.Shown>(flow.post(Next, *typed))
        assertEquals(fails, flow.view().card(p1).result)

        flow.tested(SetupAction.Test, *typed)
        val passed = flow.view().card(p1)
        assertEquals(listOf(true, false), listOf(passed.ready, passed.changedSinceTest))
        // Changed after its test, in anything but its name: tested again, or not added.
        flow.post(Add(Provider), *typed.map { if (it.first == ProviderPort) it.first to "443" else it }.toTypedArray())
        val changed = flow.view().card(p1)
        assertEquals(listOf(false, true), listOf(changed.ready, changed.changedSinceTest))
        assertNull(changed.result)
        flow.post(Open(p1), *provider())
        flow.post(Next, *typed.map { if (it.first == ProviderPort) it.first to "443" else it }.toTypedArray())
        assertEquals(SetupStep.Providers, flow.step)
        // Its name alone doesn't need a test again.
        flow.post(Next, *typed.map { if (it.first == ProviderName) it.first to "Renamed" else it }.toTypedArray())
        assertEquals(SetupStep.Indexers, flow.step)
    }

    /** A pass is of the password or key it was tested with: another typed after it needs a test of its own. */
    @Test
    fun `a pass holds only for the secret it tested, so one typed after it waits for its own test`() {
        val flow = SetupFlow(SetupMode.Wizard, SetupSeed())
        flow.tested(SetupAction.Test, *typed)
        val other = typed.map { if (it.first == ProviderPassword) it.first to "marker-other" else it }.toTypedArray()
        assertEquals(Applied.Shown("p1"), flow.post(Next, *other))
        assertEquals(SetupStep.Providers, flow.step)
        val card = flow.view().card(p1)
        assertEquals(listOf(Need.Test, null, true), listOf(card.need, card.result, card.changedSinceTest))
        // The TMDB key the same.
        flow.tested(SetupAction.Test, *other)
        flow.post(Next, *other)
        flow.post(Next, *indexer())
        flow.tested(SetupAction.Test, TmdbKey to "marker-tmdb")
        assertEquals(Applied.Shown("t"), flow.post(Next, TmdbKey to "marker-tmdb-2"))
        assertEquals(SetupStep.Tmdb, flow.step)
        val key = flow.view().tmdb
        assertEquals(listOf(Need.Test, null, true), listOf(key.need, key.result, key.changedSinceTest))
    }

    @Test
    fun `a blank new card is ready and left out, so a step may stay empty`() {
        val flow = SetupFlow(SetupMode.Wizard, SetupSeed())
        // Only the TLS box, ticked from the start: blank.
        flow.post(Next, *provider())
        assertEquals(SetupStep.Indexers, flow.step)
        flow.post(Back, *indexer())
        assertEquals(listOf(p1), flow.view().providers.map { it.slot }, "an empty step starts with a blank card again")
        flow.post(Drop(p1), *provider())
        assertEquals(emptyList(), flow.view().providers)
        assertNull(flow.layout().open)
        flow.post(Next)
        flow.post(Next, *indexer())
        assertEquals(SetupStep.Tmdb, flow.step)
        assertEquals(t, flow.layout().open)
        flow.post(Next, TmdbKey to "")
        assertEquals(SetupStep.Check, flow.step)
        assertEquals(SetupChanges(emptyList(), emptyList(), null), flow.changes())
        assertFalse(flow.view().ready)
        assertEquals(Applied.Shown(SetupFlow.TOP), flow.post(Send))
        assertEquals(SetupNotice.NothingToSend, flow.view().notice)
    }

    @Test
    fun `saved cards are ready unchanged or renamed, need a test once changed, and need a secret the TV can't read`() {
        val unreadable = main.copy(ref = "prov-3", login = SavedSecret.Unreadable)
        val flow = SetupFlow(SetupMode.Wizard, SetupSeed(listOf(main, unreadable)))
        assertEquals(listOf(p1, p2), flow.view().providers.map { it.slot })
        assertNull(flow.layout().open, "saved cards start closed")
        flow.post(Next)
        assertEquals(SetupStep.Providers, flow.step)
        val blocking = flow.view().blocking!!
        assertEquals(listOf(p2, Need.Secret, SecretState.Unreadable), listOf(blocking.slot, blocking.need, blocking.secret))

        flow.post(Open(p2))
        flow.post(Next, *mainAsShown.map { if (it.first == ProviderPassword) it.first to "marker-pass" else it }.toTypedArray())
        assertEquals(listOf(p2, Need.Test), flow.view().blocking.let { listOf(it?.slot, it?.need) })
        flow.tested(SetupAction.Test, *mainAsShown)
        flow.post(Open(p1), *mainAsShown)
        // Renamed only: still as saved.
        flow.post(Next, *mainAsShown.map { if (it.first == ProviderName) it.first to "My main one" else it }.toTypedArray())
        assertEquals(SetupStep.Indexers, flow.step)
        val changes = flow.changes().providers
        assertEquals(listOf("prov-1", "prov-3"), changes.map { it.ref })
        assertEquals(listOf("My main one", "Main provider"), changes.map { it.draft.name })
        assertEquals(listOf("", "marker-pass"), changes.map { it.draft.password })
    }

    @Test
    fun `Don't add drops a new card, Remove marks a saved one, Keep it keeps it, Undo puts back what is saved`() {
        val flow = SetupFlow(SetupMode.Edit, seed)
        flow.post(Open(p1))
        flow.post(Add(Provider), *mainAsShown.map { if (it.first == ProviderHost) it.first to "news2.example.invalid" else it }.toTypedArray())
        assertTrue(flow.view().card(p1).changed)
        assertEquals(p3, flow.layout().open)
        flow.post(Drop(p3), *typed)
        assertEquals(listOf(p1, p2), flow.view().providers.map { it.slot })
        assertNull(flow.layout().open)

        flow.post(Open(p1))
        // Undo on the open card throws away what was posted for it, and keeps it open.
        assertEquals(Applied.Shown("p1"), flow.post(Undo(p1), *provider(host = "elsewhere.example.invalid", pass = "marker-pass")))
        val undone = flow.view().card(p1)
        assertEquals(listOf(true, false, SecretState.Saved), listOf(undone.open, undone.changed, undone.secret))
        assertEquals("news.example.invalid", undone.values[ProviderHost])

        flow.post(Drop(p1), *provider(host = "elsewhere.example.invalid"))
        val removed = flow.view().card(p1)
        assertEquals(listOf(true, false, true, true), listOf(removed.removed, removed.open, removed.changed, removed.ready))
        assertTrue(flow.layout().offers(Keep(p1)))
        assertFalse(flow.layout().offers(Open(p1)) || flow.layout().offers(Drop(p1)) || flow.layout().offers(TestCard(p1)))
        assertEquals(SetupChange("prov-1", flow.changes().providers[0].draft, removed = true), flow.changes().providers[0])
        assertEquals("news.example.invalid", flow.changes().providers[0].draft.host, "a removed card is as saved")
        flow.post(Keep(p1))
        assertEquals(listOf(false, false), flow.view().card(p1).let { listOf(it.removed, it.changed) })
    }

    @Test
    fun `cards can be added up to the caps, saved ones counted, and all saved ones show above them`() {
        val capped = SetupFlow(SetupMode.Edit, seed, SetupLimits(providers = 2, indexers = 1))
        val view = capped.view()
        assertEquals(listOf(2, 2), listOf(view.providers.size, view.indexers.size))
        assertEquals(listOf(false, false), listOf(view.canAddProvider, view.canAddIndexer))
        assertFalse(capped.layout().offers(Add(Provider)) || capped.layout().offers(Add(Indexer)))

        val flow = SetupFlow(SetupMode.Edit, seed, SetupLimits(providers = 3, indexers = 3))
        flow.post(Add(Provider))
        assertFalse(flow.view().canAddProvider)
        assertFalse(flow.layout().offers(Add(Provider)))
        assertTrue(flow.layout().offers(Add(Indexer)))
        assertEquals(listOf(8, 10), SetupLimits().let { listOf(it.providers, it.indexers) })
        // The wizard adds only its step's kind.
        val wizard = SetupFlow(SetupMode.Wizard, SetupSeed())
        assertFalse(wizard.layout().offers(Add(Indexer)))
    }

    @Test
    fun `Send hands over every card in page order, blank new ones left out, once something changed and every card is ready`() {
        val flow = SetupFlow(SetupMode.Edit, seed)
        flow.post(Drop(p2))
        flow.post(Add(Provider))
        flow.tested(SetupAction.Test, *typed)
        flow.post(Add(Provider), *typed)
        flow.post(Open(i1), *provider())
        flow.tested(SetupAction.Test, *indexer("Indexer A", "https://api.indexer-a.example.invalid/api", ""))
        flow.post(Open(t), *indexer("Indexer A", "https://api.indexer-a.example.invalid/api", ""))
        flow.tested(SetupAction.Test, TmdbKey to "marker-tmdb")
        assertTrue(flow.view().ready)
        val sent = assertIs<Applied.Sent>(flow.post(Send, TmdbKey to ""))
        val changes = sent.changes
        assertEquals(listOf("prov-1", "prov-2", null), changes.providers.map { it.ref })
        assertEquals(listOf(false, true, false), changes.providers.map { it.removed })
        assertEquals(ProviderDraft("Main provider", "news.example.invalid", 563, true, "someone", "", 20), changes.providers[0].draft)
        assertEquals(ProviderDraft("Mine", "news.example.invalid", 563, true, "someone", "marker-pass", null), changes.providers[2].draft)
        assertEquals(listOf("idx-1", "idx-2"), changes.indexers.map { it.ref })
        assertEquals(IndexerDraft("Indexer A", "https://api.indexer-a.example.invalid/api", ""), changes.indexers[0].draft)
        assertEquals(IndexerDraft("Home", home.shownUrl, ""), changes.indexers[1].draft)
        assertEquals("marker-tmdb", changes.tmdbKey)
        assertFalse(changes.toString().contains("marker"), changes.toString())

        // A new card not ready holds Send up.
        val waiting = SetupFlow(SetupMode.Edit, seed)
        waiting.post(Add(Indexer))
        assertEquals(Applied.Shown("i3"), waiting.post(Send, *indexer("New", "https://new.example.invalid/api", "k")))
        assertEquals(SetupNotice.NotReady(SetupSlot(Indexer, 3)), waiting.view().notice)
    }

    @Test
    fun `a field the TV can't hold keeps its earlier value and holds up its card, but not the other cards' buttons`() {
        val flow = SetupFlow(SetupMode.Wizard, SetupSeed())
        val problems = mapOf(ProviderPort to SetupRejection.Reason.NotANumber, ProviderPassword to SetupRejection.Reason.Characters)
        val posted = typed.filterNot { it.first in problems }.toTypedArray()
        assertEquals(Applied.Shown("p1"), flow.post(Next, *posted, problems = problems))
        assertEquals(SetupStep.Providers, flow.step, "Next wasn't taken")
        val card = flow.view().card(p1)
        assertEquals(listOf(true, false, Need.Fix), listOf(card.open, card.ready, card.need))
        assertEquals(problems, card.problems)
        assertEquals(listOf("news.example.invalid", ""), listOf(card.values[ProviderHost], card.values[ProviderPort]))
        assertEquals(SecretState.None, card.secret)
        // Its own test doesn't run on what it kept instead of what was typed.
        assertEquals(Applied.Shown("p1"), flow.post(SetupAction.Test, *posted, problems = problems))
        assertEquals(Applied.Shown("p1"), flow.post(Enter, *posted, problems = problems))
        assertEquals(0, flow.testsStarted)
        // Another card's button works; the card keeps its problems, closed, until it is posted again.
        assertEquals(Applied.Shown("p2"), flow.post(Add(Provider), *posted, problems = problems))
        assertEquals(listOf(false, Need.Fix), flow.view().card(p1).let { listOf(it.open, it.need) })
        flow.post(Open(p1), *provider())
        flow.post(Add(Provider), *typed)
        assertEquals(emptyMap(), flow.view().card(p1).problems)
        // Don't add throws the card's values away, problems and all.
        flow.post(Open(p1), *provider())
        flow.post(Drop(p1), *posted, problems = problems)
        assertEquals(emptyList(), flow.view().providers)
    }

    @Test
    fun `tests are counted per card, and with none left an untested card may go on`() {
        val flow = SetupFlow(SetupMode.Wizard, SetupSeed(), SetupLimits(tests = 2))
        flow.tested(SetupAction.Test, *typed, result = fails)
        assertIs<Applied.Testing>(flow.post(Enter, *typed))
        assertEquals(2, flow.testsStarted)
        // None left: nothing runs.
        assertEquals(Applied.Shown("p1"), flow.post(SetupAction.Test, *typed))
        assertEquals(2, flow.testsStarted)
        val view = flow.view()
        assertEquals(listOf(0, false), listOf(view.testsLeft, view.canTest))
        // It failed with these very values: that still holds it up.
        assertEquals(listOf(false, null), view.card(p1).let { listOf(it.ready, it.untested) })
        // Changed, it can't be tested any more: the TV tests it at Save.
        val other = typed.map { if (it.first == ProviderPort) it.first to "443" else it }.toTypedArray()
        flow.post(Next, *other)
        assertEquals(SetupStep.Indexers, flow.step)
        assertEquals(Untested.NoTestsLeft, flow.view().providers.single().untested)
    }

    @Test
    fun `with too little time left for a test none starts, and an untested card may go on`() {
        val flow = SetupFlow(SetupMode.Wizard, SetupSeed())
        assertEquals(Applied.Shown("p1"), flow.post(SetupAction.Test, *typed, closingSoon = true))
        assertEquals(0, flow.testsStarted)
        val card = flow.show(closingSoon = true).card(p1)
        assertEquals(listOf(true, Untested.ClosingSoon), listOf(card.ready, card.untested))
        assertFalse(flow.show().card(p1).ready, "with time left, it needs its test")
        flow.post(Next, *typed, closingSoon = true)
        assertEquals(SetupStep.Indexers, flow.step)
    }

    @Test
    fun `a card with nothing in it has nothing to test`() {
        val flow = SetupFlow(SetupMode.Wizard, SetupSeed())
        assertEquals(Applied.Shown("p1"), flow.post(SetupAction.Test, *provider()))
        assertEquals(SetupNotice.NothingToTest(p1), flow.view().notice)
        // Enter on it shows the page again, saying nothing.
        assertEquals(Applied.Shown("p1"), flow.post(Enter, *provider()))
        assertNull(flow.view().notice)
        assertEquals(0, flow.testsStarted)
        // With no card open, Enter shows the page.
        assertEquals(Applied.Shown(SetupFlow.TOP), SetupFlow(SetupMode.Edit, seed).post(Enter))
    }

    @Test
    fun `the TMDB step skips with nothing typed, needs a key typed to pass, and tests a saved one in the wizard only`() {
        val flow = SetupFlow(SetupMode.Wizard, SetupSeed())
        flow.post(Next, *provider())
        flow.post(Next, *indexer())
        assertEquals(listOf(SetupStep.Tmdb, t), listOf(flow.step, flow.layout().open))
        assertEquals(listOf("t.key"), flow.layout().fields.map { it.key })
        assertTrue(flow.layout().offers(Undo(t)) && flow.layout().offers(Back) && flow.layout().offers(Next))
        flow.post(Next, TmdbKey to "marker-tmdb")
        assertEquals(SetupStep.Tmdb, flow.step)
        assertEquals(SetupNotice.NotReady(t), flow.view().notice)
        val testing = assertIs<Applied.Testing>(flow.post(SetupAction.Test, TmdbKey to ""))
        assertEquals(SetupTestItem.Tmdb("marker-tmdb"), testing.ticket.item)
        flow.finish(testing.ticket, works)
        flow.post(Undo(t), TmdbKey to "")
        assertEquals(SecretState.None, flow.view().tmdb.secret)
        flow.post(Next, TmdbKey to "")
        assertEquals(SetupStep.Check, flow.step)

        // A key saved on the TV: the wizard may test it, the edit page has only a key typed to test.
        val saved = SetupFlow(SetupMode.Wizard, SetupSeed(tmdbKey = SavedSecret.Saved))
        saved.post(Next, *provider())
        saved.post(Next, *indexer())
        assertEquals(SetupTestItem.Tmdb(""), assertIs<Applied.Testing>(saved.post(SetupAction.Test, TmdbKey to "")).ticket.item)
        val edit = SetupFlow(SetupMode.Edit, seed)
        edit.post(Open(t))
        assertEquals(Applied.Shown("t"), edit.post(SetupAction.Test, TmdbKey to ""))
        assertEquals(SetupNotice.NothingToTest(t), edit.view().notice)
    }

    /** A test of other values says nothing of a saved card typed back as it is saved: it is as saved, with nothing to undo. */
    @Test
    fun `a saved card typed back to its saved values is as saved, whatever was tested in between`() {
        val moved = mainAsShown.map { if (it.first == ProviderHost) it.first to "news-eu.example.invalid" else it }.toTypedArray()
        val flow = SetupFlow(SetupMode.Edit, seed)
        flow.post(Open(p1))
        flow.tested(SetupAction.Test, *moved, result = SetupResult(SetupOutcome.NotTested, "the server changed"))
        // Typed back, the card left open.
        flow.post(Drop(p2), *mainAsShown)
        val open = flow.view().card(p1)
        assertEquals(listOf(true, false, false, true), listOf(open.open, open.changed, open.changedSinceTest, open.ready))
        assertFalse(SetupPage.render(flow.show()).contains(SetupPage.CHANGED_SINCE_TEST_SAVED))
        // Closed: its summary says it is saved.
        flow.post(Open(i1), *mainAsShown)
        assertFalse(flow.view().card(p1).changedSinceTest)
        val page = SetupPage.render(flow.show())
        assertFalse(page.contains(SetupPage.CHANGED_SUMMARY), page)
        assertTrue(page.contains("<p class=\"st\">Saved</p>"))
    }

    @Test
    fun `a saved value the page couldn't send back makes its card read-only, but it can still be removed`() {
        val odd = listOf(
            main.copy(ref = "a", host = "news.example.invalid‮"),
            main.copy(ref = "b", name = "n".repeat(101)),
            main.copy(ref = "c", host = "someone@news.example.invalid"),
            main.copy(ref = "d", host = "nntps://news.example.invalid"),
        )
        val flow = SetupFlow(SetupMode.Edit, SetupSeed(odd + main, listOf(indexerA.copy(shownUrl = "https://x.invalid/" + "a".repeat(2048)))))
        val view = flow.view()
        assertEquals(listOf(true, true, true, true, false), view.providers.map { it.readOnly })
        assertTrue(view.indexers.single().readOnly)
        for (slot in (1..4).map { SetupSlot(Provider, it) } + i1) {
            assertFalse(flow.layout().offers(Open(slot)) || flow.layout().offers(TestCard(slot)) || flow.layout().offers(Undo(slot)), slot.key)
            assertTrue(flow.layout().offers(Drop(slot)), slot.key)
            assertTrue(view.card(slot).ready)
        }
        assertTrue(flow.layout().offers(Open(SetupSlot(Provider, 5))))
    }

    /**
     * A read-only card can't be opened, so no password can be typed for it: one whose saved password can't be read
     * doesn't hold up Next or Send (the TV fixes it), or Remove would be the only way on.
     */
    @Test
    fun `a read-only card whose secret can't be read doesn't hold the page up`() {
        val odd = main.copy(ref = "odd", host = "u@news.example.invalid", login = SavedSecret.Unreadable)
        val wizard = SetupFlow(SetupMode.Wizard, SetupSeed(listOf(odd)))
        val card = wizard.view().card(p1)
        assertEquals(listOf(true, true, null), listOf(card.readOnly, card.ready, card.need))
        wizard.post(Next)
        assertEquals(SetupStep.Indexers, wizard.step)
        val edit = SetupFlow(SetupMode.Edit, SetupSeed(listOf(odd), listOf(indexerA)))
        edit.post(Add(Provider))
        edit.tested(SetupAction.Test, *typed)
        assertIs<Applied.Sent>(edit.post(Send, *typed))
    }

    @Test
    fun `a result lands on the card it was of, with what it tested, and goes with the card`() {
        val flow = SetupFlow(SetupMode.Wizard, SetupSeed())
        flow.post(Add(Provider), *typed)
        flow.post(Open(p1), *provider())
        val ticket = assertIs<Applied.Testing>(flow.post(SetupAction.Test, *typed)).ticket
        assertEquals(p1, ticket.slot)
        // The phone moved on while it ran, the card changed: it lands on its card all the same, as a test of what it was.
        flow.post(Add(Provider), *typed.map { if (it.first == ProviderPort) it.first to "443" else it }.toTypedArray())
        assertEquals(p1, flow.finish(ticket, works))
        val changed = flow.view().card(p1)
        assertEquals(listOf(null, true, false), listOf(changed.result, changed.changedSinceTest, changed.ready), "a pass of 563 taken for 443")
        // Put back as tested, it is that test's pass.
        flow.post(Open(p1), *provider())
        flow.post(Add(Provider), *typed)
        assertEquals(works, flow.view().card(p1).result)

        val dropped = assertIs<Applied.Testing>(flow.post(TestCard(p1), *provider())).ticket
        flow.post(Drop(p1), *provider())
        assertNull(flow.finish(dropped, works))
    }

    @Test
    fun `Test anyway asks for the plain http test, and a test names the card, its saved record and what to test`() {
        val flow = SetupFlow(SetupMode.Edit, seed)
        val saved = assertIs<Applied.Testing>(flow.post(TestCard(p1))).ticket.item
        assertEquals(SetupTestItem.Provider(p1, "prov-1", ProviderDraft("Main provider", "news.example.invalid", 563, true, "someone", "", 20)), saved)
        flow.post(Open(i2))
        assertFalse(flow.layout().offers(Next), "no Next on the edit page")
        val anyway = assertIs<Applied.Testing>(flow.post(TestHttp, *indexer("Home", home.shownUrl, ""))).ticket.item
        assertEquals(SetupTestItem.Indexer(i2, "idx-2", IndexerDraft("Home", home.shownUrl, ""), plainHttp = true), anyway)
        // Test then, at the same address: still as the phone chose.
        val plain = assertIs<Applied.Testing>(flow.post(SetupAction.Test, *indexer("Home", home.shownUrl, "marker-key"))).ticket.item
        assertEquals(SetupTestItem.Indexer(i2, "idx-2", IndexerDraft("Home", home.shownUrl, "marker-key"), plainHttp = true), plain)
        assertEquals(false, (assertIs<Applied.Testing>(flow.post(TestCard(i1), *indexer("Home", home.shownUrl, ""))).ticket.item as SetupTestItem.Indexer).plainHttp)
        flow.post(Open(p1), *indexer("Home", home.shownUrl, ""))
        assertFalse(flow.layout().offers(TestHttp), "a provider has no Test anyway")
        assertFalse(plain.toString().contains("marker"), plain.toString())
    }

    /** The parts of a saved address kept on the TV go with that address only: typed over, the page stops saying so. */
    @Test
    fun `hidden parts hold only while the card has the address shown`() {
        val flow = SetupFlow(SetupMode.Edit, seed)
        flow.post(Open(i2))
        assertTrue(flow.view().card(i2).hiddenParts)
        flow.post(Open(i1), *indexer("Home", "http://192.168.1.10:9696/1/api", ""))
        assertFalse(flow.view().card(i2).hiddenParts, "another address typed")
        assertFalse(SetupPage.render(flow.show()).contains(SetupPage.HIDDEN_PARTS))
        // Back to the address shown, they are its again.
        flow.post(Open(i2), *indexer("Indexer A", indexerA.shownUrl, ""))
        flow.post(Open(i1), *indexer("Home", home.shownUrl, ""))
        assertTrue(flow.view().card(i2).hiddenParts)
        assertTrue(SetupPage.render(flow.show()).contains(SetupPage.HIDDEN_PARTS))
    }

    /** Test anyway is the phone's choice for an address: a later test of the same one (Go on the keyboard, say) keeps it. */
    @Test
    fun `Test anyway holds for its address, so a later test of it isn't held back again`() {
        val flow = SetupFlow(SetupMode.Wizard, SetupSeed())
        flow.post(Next, *provider())
        val far = indexer("Far", "http://indexer.example.invalid/api", "marker-key")
        val first = assertIs<Applied.Testing>(flow.post(SetupAction.Test, *far)).ticket
        assertFalse(assertIs<SetupTestItem.Indexer>(first.item).plainHttp)
        flow.finish(first, SetupResult(SetupOutcome.NotTested, "plain http", plainHttp = true))
        flow.tested(TestHttp, *far)
        assertTrue(flow.view().card(i1).ready)
        // The name corrected, then Go: the same address, tested as chosen.
        val again = assertIs<Applied.Testing>(flow.post(Enter, *indexer("Far away", "http://indexer.example.invalid/api", ""))).ticket
        assertTrue(assertIs<SetupTestItem.Indexer>(again.item).plainHttp, "the phone's choice forgotten")
        // Another address asks again.
        val moved = assertIs<Applied.Testing>(flow.post(SetupAction.Test, *indexer("Far away", "http://indexer2.example.invalid/api", ""))).ticket
        assertFalse(assertIs<SetupTestItem.Indexer>(moved.item).plainHttp)
    }

    @Test
    fun `a card's view never holds a password or key`() {
        val flow = SetupFlow(SetupMode.Wizard, SetupSeed())
        flow.post(Add(Provider), *typed)
        val all = flow.view().cards
        assertTrue(all.all { card -> card.values.keys.none { it.isSecret } })
        val fields = CardView::class.java.declaredFields.map { it.name }
        assertTrue(fields.none { it.contains("password", ignoreCase = true) || it.contains("key", ignoreCase = true) }, fields.toString())
        assertFailsWith<IllegalArgumentException> {
            CardView(
                p1, false, null, mapOf(ProviderPassword to "x"), SecretState.Typed, false, true, false, false, false, false, false, false, null, false, null,
                emptyMap(), null,
            )
        }
    }

    @Test
    fun `every action's value reads back as it, and nothing else is one`() {
        val all = listOf(
            Enter, SetupAction.Test, TestHttp, Next, Back, Send, Add(Provider), Add(Indexer), Add(Service), Add(TorrentIndexer),
            TestCard(p1), Open(SetupSlot(Indexer, 10)), Drop(p3), Keep(i2), Undo(t), Open(s2), Drop(SetupSlot(TorrentIndexer, 10)),
            TestCard(SetupSlot(Provider, 123)),
        )
        for (action in all) assertEquals(action, SetupAction.of(action.value), action.value)
        assertEquals(
            listOf(
                "enter", "test", "test-http", "next", "back", "send", "add:provider", "add:indexer", "add:service", "add:torrent-indexer", "test:p1",
                "open:i10", "drop:p3", "keep:i2", "undo:t", "open:s2", "drop:r10",
            ),
            all.dropLast(1).map { it.value },
        )
        // As the page writes them, in lower case only.
        for (
            bad in listOf(
                "", "Test", "TEST", "Drop:p1", "drop:P1", "test ", "test:", "test:p", "test:p0", "test:p01", "test:p1000", "test:x1", "test:t1", "add:tmdb", "drop",
                "send:p1", "open:p1:p2", "stay", "add:services", "add:torrent", "open:s", "open:s01", "drop:R1",
            )
        ) {
            assertNull(SetupAction.of(bad), bad)
        }
        assertFailsWith<IllegalArgumentException> { Add(SetupSection.Tmdb) }
        assertEquals(listOf(p1, SetupSlot(Indexer, 10), t, null, null, s1, r2), listOf("p1", "i10", "t", "p", "i01", "s1", "r2").map { SetupSlot.of(it) })
        assertEquals(listOf("Provider 2", "Indexer 1", "TMDB key", "Debrid service 1", "Torrent indexer 2"), listOf(p2, i1, t, s1, r2).map { it.label })
    }
}
