package io.skjaere.usenet.app.setup

import io.skjaere.usenet.app.settings.INDEXER_LOCAL_HTTP
import io.skjaere.usenet.app.settings.INDEXER_PUBLIC_HTTP
import io.skjaere.usenet.app.settings.ProviderLogin
import io.skjaere.usenet.app.settings.ProviderTestResult
import io.skjaere.usenet.data.IndexerRecord
import io.skjaere.usenet.data.IndexerWithKey
import io.skjaere.usenet.data.ProviderRecord
import io.skjaere.usenet.data.ProviderWithPassword
import io.skjaere.usenet.data.ServiceAccount
import io.skjaere.usenet.data.ServiceSignIn
import io.skjaere.usenet.data.ServiceWithKey
import io.skjaere.usenet.data.TorrentIndexerRecord
import io.skjaere.usenet.data.TorrentIndexerWithKey
import io.skjaere.usenet.debrid.ServiceKind
import io.skjaere.usenet.handoff.IndexerDraft
import io.skjaere.usenet.handoff.ProviderDraft
import io.skjaere.usenet.handoff.ServiceDraft
import io.skjaere.usenet.handoff.SetupChange
import io.skjaere.usenet.handoff.SetupChanges
import io.skjaere.usenet.handoff.SetupMode
import io.skjaere.usenet.handoff.TorrentIndexerDraft
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the TV shows of what the phone sent, before anything is saved: each card the page held, by the record the TV
 * bound it to, as New, Changes “X” (with what changed) or Removes “X”; the saved ones left as they were by name; secrets
 * masked; a saved password or key kept only for the same server; and which items passed from the phone with exactly
 * what would be saved.
 */
class SetupReviewTest {
    private val main = ProviderWithPassword(ProviderRecord("p-main", "Main", "news.example.net", 563, "alice", connections = 20), "saved-pass")
    // Named as its host, as saves before names were optional did: its card's name is blank.
    private val block = ProviderWithPassword(ProviderRecord("p-block", "block.example.com", "block.example.com", 119, "", useTls = false, enabled = false), "")
    private val one = IndexerWithKey(IndexerRecord("i-one", "One", "http://192.168.1.5:9696/1/api"), "KEY-ONE-0000-1111")
    private val hidden = IndexerWithKey(IndexerRecord("i-hid", "Hidden", "https://u:p@indexer.example.org/api?r=token"), "KEY-HID-0000-3333")
    private val saved = SavedSources(listOf(main, block), listOf(one, hidden), storedKeys = setOf("i-one", "i-hid"), hasTmdbKey = true)
    private val none = SavedSources(emptyList(), emptyList(), emptySet(), hasTmdbKey = false)

    /** [main]'s card as the page shows it, sent back untouched. */
    private val mainAsShown = ProviderDraft("Main", "news.example.net", 563, true, "alice", "", 20)
    private val blockAsShown = ProviderDraft("", "block.example.com", 119, false, "", "", 8)
    private val oneAsShown = IndexerDraft("One", "http://192.168.1.5:9696/1/api", "")
    private val hiddenAsShown = IndexerDraft("Hidden", "https://indexer.example.org/api", "")

    private fun changes(
        providers: List<SetupChange<ProviderDraft>> = listOf(SetupChange("p-main", mainAsShown, false), SetupChange("p-block", blockAsShown, false)),
        indexers: List<SetupChange<IndexerDraft>> = listOf(SetupChange("i-one", oneAsShown, false), SetupChange("i-hid", hiddenAsShown, false)),
        tmdb: String? = null,
    ) = SetupChanges(providers, indexers, tmdb)

    private fun review(changes: SetupChanges, sources: SavedSources = saved, passes: PassLog = PassLog(), mode: SetupMode = SetupMode.Edit) =
        SetupReview.of(changes, sources, passes, mode, sentFrom = "192.168.1.40")

    private fun ReviewItem.text() = (listOf(title, heading) + lines.map { it.text } + listOfNotNull(problem)).joinToString("\n")

    @Test
    fun `what was left as it was is unchanged, listed by name and nothing else`() {
        val r = review(changes(tmdb = null))
        assertEquals(emptyList(), r.items)
        assertEquals(listOf("Main", "block.example.com", "One", "Hidden", "the TMDB key"), r.unchanged)
        assertEquals("Nothing changed", r.summary)
        assertEquals("192.168.1.40", r.sentFrom)
    }

    @Test
    fun `each card is new, a change or a removal, in the page's order, and the summary counts them`() {
        val r = review(
            changes(
                providers = listOf(
                    SetupChange("p-main", mainAsShown, removed = true),
                    SetupChange("p-block", blockAsShown.copy(connections = 4), removed = false),
                    SetupChange(null, ProviderDraft("", "fill.example.net", null, true, "me2", "typed-pass", null), removed = false),
                ),
                indexers = listOf(SetupChange("i-one", oneAsShown, false), SetupChange("i-hid", hiddenAsShown.copy(url = "https://api.indexer.example.org/api", apiKey = "TYPED-KEY-0000-9999"), false)),
                tmdb = "tmdb-key-0123456789abcdef",
            ),
        )
        assertEquals(
            listOf(
                "Usenet provider · Removes “Main”", "Usenet provider · Changes “block.example.com” · switched off", "Usenet provider · New",
                "Indexer · Changes “Hidden”", "TMDB key · Changes the saved key",
            ),
            r.items.map { "${it.title} · ${it.heading}" },
        )
        assertEquals(listOf(ReviewAction.Remove, ReviewAction.Change, ReviewAction.New, ReviewAction.Change, ReviewAction.Change), r.items.map { it.action })
        assertEquals("1 new · 3 changed · 1 to remove", r.summary)
        assertEquals(listOf("One"), r.unchanged)
        assertTrue(r.items.first().warn, "a removal in the warning colour")
        assertEquals(listOf("news.example.net:563 · its saved password is deleted too"), r.items.first().lines.map { it.text })
        assertEquals(listOf("fill.example.net:563 · TLS · me2 · password typed"), r.items[2].lines.map { it.text })
        assertEquals(listOf("provider 1", "provider 2", "provider 3", "indexer 1", "tmdb 1"), r.items.map { it.part })
    }

    @Test
    fun `secrets are masked, a key to its last four and a password not at all`() {
        val r = review(
            changes(
                providers = listOf(SetupChange(null, ProviderDraft("", "new.example.net", null, true, "bob", "hunter2-secret", null), false)),
                indexers = listOf(SetupChange(null, IndexerDraft("", "https://new.example.org/api", "ABCDEFGH12345678WXYZ"), false)),
                tmdb = "tmdb-key-0123456789abcdef",
            ),
            none,
        )
        val all = r.items.joinToString("\n") { it.text() }
        listOf("hunter2-secret", "ABCDEFGH12345678WXYZ", "tmdb-key-0123456789abcdef").forEach { assertFalse(it in all, all) }
        assertTrue("password typed" in all && "•••• WXYZ" in all && "•••• cdef" in all, all)
        assertEquals("TMDB key · New", r.items.last().let { "${it.title} · ${it.heading}" })
        // Nor in what a log or a failed assertion prints.
        listOf(r.toString(), r.items.toString(), r.changes.toString()).forEach { printed ->
            listOf("hunter2-secret", "ABCDEFGH12345678WXYZ", "tmdb-key-0123456789abcdef").forEach { assertFalse(it in printed, printed) }
        }
    }

    /** A short key shows none of its characters: four of eight would be half of it. */
    @Test
    fun `a short key shows none of its characters`() {
        val r = review(
            changes(providers = emptyList(), indexers = listOf(SetupChange(null, IndexerDraft("", "https://new.example.org/api", "abc12345"), false)), tmdb = "xyz98765"),
            none,
        )
        assertEquals(listOf("https://new.example.org/api · API key ••••", "Key ••••"), r.items.map { it.lines.first().text })
        val all = r.items.joinToString("\n") { it.text() }
        for (key in listOf("abc12345", "xyz98765")) for (tail in key.windowed(4)) assertFalse(tail in all, "$tail in $all")
    }

    /** A change says what changed and nothing else, a host in the warning colour; the record keeps its id, place and state. */
    @Test
    fun `a change lists only what changed, and keeps the record's id and state`() {
        val moved = review(changes(providers = listOf(SetupChange("p-main", mainAsShown.copy(name = "Renamed", host = "eu.news.example.net", port = 443, connections = 30, password = "typed"), false))))
        val p = assertIs<ProviderReview>(moved.items.single())
        assertEquals(
            listOf(
                ReviewLine("Named “Renamed”"),
                ReviewLine("Host: news.example.net → eu.news.example.net", warn = true),
                ReviewLine("Port: 563 → 443"),
                ReviewLine("Connections: 20 → 30"),
                ReviewLine("New password typed"),
            ),
            p.lines,
        )
        assertEquals(listOf("p-main", "Renamed", "eu.news.example.net", "443", "30", true), listOf(p.form.id, p.form.name, p.form.host, p.form.port, p.form.connections, p.form.enabled))
        assertEquals("typed", p.form.passwordToSave)
        // A blank name clears it; TLS off and another username are said; a switched-off one stays off.
        val off = review(changes(providers = listOf(SetupChange("p-block", blockAsShown.copy(name = "Named", useTls = true, port = 563, username = "zed", password = "zed-pass"), false))))
        val b = assertIs<ProviderReview>(off.items.single())
        assertEquals(listOf("Named “Named”", "Port: 119 → 563", "TLS switched on", "Username: none → zed", "New password typed"), b.lines.map { it.text })
        assertEquals("Changes “block.example.com” · switched off", b.heading)
        assertEquals(false, b.form.enabled)
        val cleared = assertIs<ProviderReview>(review(changes(providers = listOf(SetupChange("p-main", mainAsShown.copy(name = ""), false)))).items.single())
        assertEquals(listOf("Name cleared: the host shows", "Saved password kept"), cleared.lines.map { it.text })
        assertEquals("", cleared.form.name)
        assertNull(cleared.form.passwordToSave, "the saved password kept")
    }

    /**
     * Whoever holds the code chooses where Save sends a saved secret: each way a card's server could be another than its
     * saved one's is a problem, and the item can't be saved without the secret typed.
     */
    @Test
    fun `a saved secret is kept only for the same server`() {
        val providers = mapOf(
            "another host" to mainAsShown.copy(host = "news.evil.example"),
            "the plain port with TLS off" to mainAsShown.copy(port = 119, useTls = false),
            "TLS off" to mainAsShown.copy(useTls = false),
            "another port" to mainAsShown.copy(port = 443),
            "another username" to mainAsShown.copy(username = "mallory"),
            "a look-alike host" to mainAsShown.copy(host = "news.examplе.net"),
        )
        for ((why, draft) in providers) {
            val p = review(changes(providers = listOf(SetupChange("p-main", draft, false)))).items.single()
            assertEquals(PROVIDER_SERVER_CHANGED, p.problem, why)
            assertFalse(p.tested, why)
            assertNull(assertIs<ProviderReview>(p).testPassword, why)
            assertFalse("kept" in p.text(), p.text())
        }
        assertEquals("The server changed: send the password too.", PROVIDER_SERVER_CHANGED)
        assertNull(review(changes(providers = listOf(SetupChange("p-main", mainAsShown.copy(host = "news.evil.example", password = "typed"), false)))).items.single().problem)
        val indexers = mapOf(
            "another host" to "https://evil.example/api",
            "a login hiding the host" to "http://192.168.1.5:9696%2F1@evil.example/1/api",
            "another port" to "http://192.168.1.5:9797/1/api",
            "another path" to "http://192.168.1.5:9696/2/api",
            "parsers that disagree" to "http://192.168.1.5:9696\\@evil.example/1/api",
        )
        for ((why, url) in indexers) {
            val i = review(changes(indexers = listOf(SetupChange("i-one", oneAsShown.copy(url = url), false)))).items.single()
            assertTrue(i.problem != null, "$why: ${i.text()}")
            assertNull(assertIs<IndexerReview>(i).testKey, why)
        }
        val down = review(changes(indexers = listOf(SetupChange("i-hid", hiddenAsShown.copy(url = "http://indexer.example.org/api"), false)))).items.single()
        assertEquals(INDEXER_ADDRESS_CHANGED, down.problem)
        assertEquals("The address changed: send the API key too.", INDEXER_ADDRESS_CHANGED)
        // Up to https, and the same endpoint written otherwise, keep it.
        val up = assertIs<IndexerReview>(review(changes(indexers = listOf(SetupChange("i-one", oneAsShown.copy(url = "https://192.168.1.5:9696/1"), false)))).items.single())
        assertNull(up.problem)
        assertEquals("KEY-ONE-0000-1111", up.testKey)
        assertTrue(up.lines.any { it.text == "Saved API key kept" }, up.text())
    }

    @Test
    fun `a saved secret this TV can't read must be sent again`() {
        val lost = SavedSources(listOf(main.copy(password = null)), listOf(one.copy(apiKey = null)), setOf("i-one"), hasTmdbKey = false)
        val r = review(changes(providers = listOf(SetupChange("p-main", mainAsShown.copy(name = "Renamed"), false)), indexers = listOf(SetupChange("i-one", oneAsShown.copy(name = "Renamed"), false))), lost)
        assertEquals(listOf(PROVIDER_PASSWORD_UNREADABLE, INDEXER_KEY_UNREADABLE), r.items.map { it.problem })
        val sent = review(changes(providers = listOf(SetupChange("p-main", mainAsShown.copy(password = "again"), false)), indexers = emptyList()), lost)
        assertNull(sent.items.single().problem)
    }

    /** Shown without its login and query, the address stands for the saved one whole: a rename keeps all of it. */
    @Test
    fun `an indexer left at its shown address keeps the saved one whole`() {
        val i = assertIs<IndexerReview>(review(changes(indexers = listOf(SetupChange("i-hid", hiddenAsShown.copy(name = "Renamed"), false)))).items.single())
        assertEquals(hidden.record.baseUrl, i.form.url)
        assertEquals(listOf("Named “Renamed”", "Saved API key kept"), i.lines.map { it.text })
        assertEquals("KEY-HID-0000-3333", i.testKey)
        val moved = assertIs<IndexerReview>(review(changes(indexers = listOf(SetupChange("i-hid", hiddenAsShown.copy(url = "https://api.indexer.example.org/api", apiKey = "TYPED-KEY-0000-9999"), false)))).items.single())
        // The old address as the phone saw it: the TV's review shows no more of it than the page did.
        assertEquals(ReviewLine("Address: https://indexer.example.org/api → https://api.indexer.example.org/api", warn = true), moved.lines.first())
        assertEquals("https://api.indexer.example.org/api", moved.form.url)
    }

    /** Items that passed from the phone with exactly what would be saved aren't tested again; anything else is. */
    @Test
    fun `an item passed from the phone with exactly these values is said to be tested`() {
        val passes = PassLog()
        val login = ProviderLogin.of("news.example.net", 563, "alice", true, "saved-pass")
        passes.passed(login, 20, ProviderTestResult(login, ok = true, message = "Connected and logged in to news.example.net"))
        passes.passed("http://192.168.1.5:9696/1/api", "TYPED-KEY", "Connected")
        passes.passed("tmdb-key-0123456789abcdef")
        val r = review(
            changes(
                providers = listOf(SetupChange("p-main", mainAsShown.copy(name = "Renamed"), false), SetupChange(null, mainAsShown.copy(connections = 21, password = "saved-pass"), false)),
                indexers = listOf(SetupChange("i-one", oneAsShown.copy(apiKey = "TYPED-KEY"), false), SetupChange(null, IndexerDraft("", "https://new.example.org/api", "OTHER-KEY"), false)),
                tmdb = "tmdb-key-0123456789abcdef",
            ),
            passes = passes,
        )
        assertEquals(listOf(true, false, true, false, true), r.items.map { it.tested })
        assertEquals("Tested from your phone", PHONE_TESTED)
    }

    /** What the first run's matcher did becomes a warning: two cards for one account would log in twice. */
    @Test
    fun `a new item for a saved one's server and login is said to be the same`() {
        val r = review(
            changes(
                providers = listOf(SetupChange("p-main", mainAsShown, false), SetupChange(null, mainAsShown.copy(host = "NEWS.example.net", password = "x"), false)),
                indexers = listOf(SetupChange(null, IndexerDraft("", "https://192.168.1.5:9696/1", "K-0000-1111-2222"), false)),
            ),
        )
        assertTrue(r.items[0].lines.any { it.text == "Same server and login as the saved “Main”" && it.warn }, r.items[0].text())
        assertTrue(r.items[1].lines.any { it.text == "Same address as the saved “One”" && it.warn }, r.items[1].text())
        // Not when that one goes.
        val instead = review(changes(providers = listOf(SetupChange("p-main", mainAsShown, true), SetupChange(null, mainAsShown.copy(password = "x"), false)), indexers = emptyList()))
        assertTrue(instead.items[1].lines.none { it.warn }, instead.items[1].text())
    }

    @Test
    fun `removing every provider or indexer says what stops working`() {
        val r = review(changes(providers = listOf(SetupChange("p-main", mainAsShown, true), SetupChange("p-block", blockAsShown, true)), indexers = listOf(SetupChange("i-one", oneAsShown, true), SetupChange("i-hid", hiddenAsShown, true))))
        assertEquals(listOf(ONLY_PROVIDER_REMOVED, ONLY_INDEXER_REMOVED), r.warnings)
        assertEquals("This removes your only provider: nothing will play until you add one.", ONLY_PROVIDER_REMOVED)
        assertEquals("This removes your only indexer: searching won't work until you add one.", ONLY_INDEXER_REMOVED)
        assertEquals(emptyList(), review(changes(providers = listOf(SetupChange("p-main", mainAsShown, true), SetupChange("p-block", blockAsShown, false)))).warnings)
        val replaced = review(changes(providers = listOf(SetupChange("p-main", mainAsShown, true), SetupChange("p-block", blockAsShown, true), SetupChange(null, mainAsShown.copy(password = "x"), false))))
        assertEquals(emptyList(), replaced.warnings)
    }

    /**
     * A new item takes the place of what goes only if it is saved: one that can't be saved as sent, or that fails on the
     * TV and is left out, leaves the TV with none, and the review says so then.
     */
    @Test
    fun `a new item that won't be saved leaves the warning of what stops working`() {
        val gone = listOf(SetupChange("p-main", mainAsShown, true), SetupChange("p-block", blockAsShown, true))
        val broken = review(changes(providers = gone + SetupChange(null, mainAsShown.copy(host = "", password = "x"), false), indexers = emptyList()))
        assertEquals(listOf(ONLY_PROVIDER_REMOVED), broken.warnings, "a new one with no host can't be saved")
        val replaced = review(changes(providers = gone + SetupChange(null, mainAsShown.copy(host = "new.example.net", password = "x"), false), indexers = emptyList()))
        assertEquals(emptyList(), replaced.warnings)
        val new = replaced.items.single { it.action == ReviewAction.New }.key
        val stays = listOf(ItemStatus.Running, ItemStatus.Passed("ok"), ItemStatus.Anyway("no"), ItemStatus.Done(PHONE_SETUP_SAVED, ok = true), ItemStatus.Done(PHONE_SETUP_SAVED_ANYWAY, ok = false))
        for (status in stays) assertEquals(emptyList(), replaced.warnings(mapOf(new to status)), "$status")
        val goes = listOf(ItemStatus.Failed("no", canRetry = true, canSaveAnyway = true), ItemStatus.LeftOut, ItemStatus.Done(PHONE_SETUP_LEFT_OUT, ok = false))
        for (status in goes) assertEquals(listOf(ONLY_PROVIDER_REMOVED), replaced.warnings(mapOf(new to status)), "$status")
    }

    /** Absence never removes: a record saved on the TV after the page opened is still saved, and unchanged. */
    @Test
    fun `a record the page never had is left as it is`() {
        val more = saved.copy(providers = saved.providers + ProviderWithPassword(ProviderRecord("p-late", "Late", "late.example.net", 563, ""), ""))
        val r = review(changes(), more)
        assertEquals(emptyList(), r.items)
        assertTrue("Late" in r.unchanged)
    }

    /** A record deleted on the TV since the page opened is never brought back by what the phone sent for it. */
    @Test
    fun `a card whose record went meanwhile can't be saved`() {
        val r = review(changes(), saved.copy(providers = listOf(block)))
        val gone = r.items.single()
        assertEquals("Usenet provider · Changes “Main”", "${gone.title} · ${gone.heading}")
        assertEquals(RECORD_GONE, gone.problem)
        assertEquals("It was deleted on this TV meanwhile, so it isn't saved.", RECORD_GONE)
    }

    @Test
    fun `an http indexer over the internet carries the form's warning, and one at home its note`() {
        fun lines(url: String) = review(changes(providers = emptyList(), indexers = listOf(SetupChange(null, IndexerDraft("", url, "K-0000-1111-2222"), false))), none).items.single().lines
        assertTrue(lines("http://indexer.example.org/api").any { it.text == INDEXER_PUBLIC_HTTP && it.warn })
        assertTrue(lines("http://192.168.1.9:9696/api").any { it.text == INDEXER_LOCAL_HTTP && !it.warn })
        assertTrue(lines("https://indexer.example.org/api").none { it.warn })
    }

    // The debrid cards (L209): a service account and torrent indexers, with the boards' invented account and keys.

    private val torbox = ServiceWithKey(ServiceAccount("svc-tb", "TorBox", accountName = "quay.viewer@example.invalid"), "SAVED-SERVICE-KEY")
    private val prowlarr = TorrentIndexerWithKey(TorrentIndexerRecord("tor-1", "Home Prowlarr", "http://192.168.1.20:9696/3/api?apikey=hidden"), "SAVED-TORRENT-KEY")
    private val debrid = saved.copy(services = listOf(torbox), torrentIndexers = listOf(prowlarr), storedKeys = saved.storedKeys + "svc-tb" + "tor-1")
    private val prowlarrAsShown = TorrentIndexerDraft("Home Prowlarr", "http://192.168.1.20:9696/3/api", "", useWithService = true)

    private fun debridChanges(services: List<SetupChange<ServiceDraft>> = emptyList(), torrentIndexers: List<SetupChange<TorrentIndexerDraft>> = emptyList()) =
        changes().copy(services = services, torrentIndexers = torrentIndexers)

    /**
     * While debrid shows, a service plays without a provider and a torrent indexer is searched while a service stays
     * (L165, L166): the providers or the indexers going stops nothing while those stay, and the last of a kind that
     * played or searched going, with nothing else left to, says so as for a provider or an indexer.
     */
    @Test
    fun `what stops working counts the debrid service and torrent indexers that stay`() {
        val providersGone = listOf(SetupChange("p-main", mainAsShown, true), SetupChange("p-block", blockAsShown, true))
        val indexersGone = listOf(SetupChange("i-one", oneAsShown, true), SetupChange("i-hid", hiddenAsShown, true))
        val torBoxGone = SetupChange("svc-tb", ServiceDraft("torbox", ""), removed = true)
        // TorBox plays without a provider, and Home Prowlarr is searched while TorBox stays.
        assertEquals(emptyList(), review(changes(providersGone, indexersGone), debrid).warnings)
        // TorBox going too: nothing plays, and Home Prowlarr isn't searched without a service.
        assertEquals(
            listOf(ONLY_PROVIDER_REMOVED, ONLY_SERVICE_REMOVED, ONLY_INDEXER_REMOVED),
            review(changes(providersGone, indexersGone).copy(services = listOf(torBoxGone)), debrid).warnings,
        )
        // The providers kept: Usenet still plays, but nothing is searched.
        assertEquals(listOf(ONLY_INDEXER_REMOVED), review(changes(indexers = indexersGone).copy(services = listOf(torBoxGone)), debrid).warnings)

        // A TV with the debrid pair alone.
        val pair = none.copy(services = listOf(torbox), torrentIndexers = listOf(prowlarr), storedKeys = setOf("svc-tb", "tor-1"))
        fun pairReview(services: List<SetupChange<ServiceDraft>>, torrentIndexers: List<SetupChange<TorrentIndexerDraft>> = emptyList()) =
            review(SetupChanges(emptyList(), emptyList(), null, services, torrentIndexers), pair)
        assertEquals(listOf(ONLY_SERVICE_REMOVED), pairReview(listOf(torBoxGone)).warnings)
        assertEquals("This removes your only debrid service: nothing will play until you add one.", ONLY_SERVICE_REMOVED)
        assertEquals(listOf(ONLY_TORRENT_INDEXER_REMOVED), pairReview(emptyList(), listOf(SetupChange("tor-1", prowlarrAsShown, removed = true))).warnings)
        assertEquals("This removes your only torrent indexer: searching won't work until you add one.", ONLY_TORRENT_INDEXER_REMOVED)
        // A new service in TorBox's place stands in for it, unless it fails at Save.
        val replaced = pairReview(listOf(torBoxGone, SetupChange(null, ServiceDraft("premiumize", "PREMIUMIZE-KEY-0000-a1b2"), removed = false)))
        assertEquals(emptyList(), replaced.warnings)
        val premiumize = replaced.items.single { it.action == ReviewAction.New }.key
        assertEquals(listOf(ONLY_SERVICE_REMOVED), replaced.warnings(mapOf(premiumize to ItemStatus.Failed("no", canRetry = true, canSaveAnyway = false))))
    }

    /**
     * L209: the TV's review lists "Debrid service · New · TorBox · API key •••• 00aa", or a new key for the saved account
     * ("Replaces the saved key"); its key masked, the account left alone when nothing was typed for it, and its Remove
     * signs it out.
     */
    @Test
    fun `a debrid service sent from the phone is new, or a new key for the saved one, its key masked`() {
        val r = review(
            debridChanges(
                services = listOf(
                    SetupChange("svc-tb", ServiceDraft("torbox", "aaaaaaaa-0000-4000-8000-0000000000aa"), removed = false),
                    SetupChange(null, ServiceDraft("premiumize", "PREMIUMIZE-KEY-0000-a1b2"), removed = false),
                ),
            ),
            debrid,
        )
        assertEquals(
            listOf("Debrid service\nReplaces the saved key\nTorBox · API key •••• 00aa", "Debrid service\nNew\nPremiumize · API key •••• a1b2"),
            r.items.map { it.text() },
        )
        val (replaces, new) = r.items.map { assertIs<ServiceReview>(it) }
        assertEquals(listOf(ReviewAction.Change, ReviewAction.New), listOf(replaces.action, new.action))
        assertEquals(listOf("svc-tb", torbox), listOf(replaces.key, replaces.before))
        assertEquals(listOf(ServiceKind.TorBox, ServiceKind.Premiumize), listOf(replaces.service, new.service))
        assertEquals("PREMIUMIZE-KEY-0000-a1b2", new.apiKey)
        assertEquals(listOf("service 1", "service 2"), r.items.map { it.part })
        assertFalse(r.items.joinToString { it.text() + it }.contains("KEY-0000"), "a key shown or printed")
        assertEquals("1 new · 1 changed", r.summary)
        // Left as it was: no item, and it is among the unchanged.
        val untouched = review(debridChanges(services = listOf(SetupChange("svc-tb", ServiceDraft("torbox", ""), removed = false))), debrid)
        assertEquals(emptyList(), untouched.items)
        assertTrue("TorBox" in untouched.unchanged && "Home Prowlarr" in untouched.unchanged, untouched.unchanged.toString())
        // Remove signs it out on this TV.
        val removed = review(debridChanges(services = listOf(SetupChange("svc-tb", ServiceDraft("torbox", ""), removed = true))), debrid).items.single()
        assertEquals("Debrid service\nRemoves “TorBox”\nquay.viewer@example.invalid · its saved API key is deleted too", removed.text())
        assertEquals(ReviewKind.Service, removed.kind)
    }

    /** A new card for a service signed in to on the TV since the page opened replaces that account's key, as Save would. */
    @Test
    fun `a new service card for an account signed in to meanwhile replaces its key, and one with no key can't be saved`() {
        val r = review(debridChanges(services = listOf(SetupChange(null, ServiceDraft("torbox", "NEW-TORBOX-KEY-77aa"), removed = false))), debrid)
        val item = assertIs<ServiceReview>(r.items.single())
        assertEquals(listOf(ReviewAction.Change, "Replaces the saved key", torbox), listOf(item.action, item.heading, item.before))
        val noKey = review(debridChanges(services = listOf(SetupChange(null, ServiceDraft("premiumize", ""), removed = false))), debrid).items.single()
        assertEquals(INDEXER_NO_KEY, noKey.problem)
        // Its account signed out of on the TV meanwhile: never brought back.
        val gone = review(debridChanges(services = listOf(SetupChange("svc-tb", ServiceDraft("torbox", "NEW-TORBOX-KEY-77aa"), removed = false))), saved).items.single()
        assertEquals(listOf("Debrid service · Changes “TorBox”", RECORD_GONE), listOf("${gone.title} · ${gone.heading}", gone.problem))
        // Passed from the phone with this very key: tested.
        val passes = PassLog().apply { passed(ServiceKind.TorBox, "NEW-TORBOX-KEY-77aa", ServiceSignIn("quay.viewer@example.invalid")) }
        assertTrue(review(debridChanges(services = listOf(SetupChange("svc-tb", ServiceDraft("torbox", "NEW-TORBOX-KEY-77aa"), false))), debrid, passes).items.single().tested)
        assertFalse(review(debridChanges(services = listOf(SetupChange(null, ServiceDraft("premiumize", "NEW-TORBOX-KEY-77aa"), false))), debrid, passes).items.single().tested, "another service's pass")
    }

    /** L209: a torrent indexer reads as an indexer does, with "Use with your debrid service" said when it is off or changes. */
    @Test
    fun `a torrent indexer sent from the phone reads as an indexer does, with its switch`() {
        val r = review(
            debridChanges(
                torrentIndexers = listOf(
                    SetupChange("tor-1", prowlarrAsShown.copy(useWithService = false), removed = false),
                    SetupChange(null, TorrentIndexerDraft("Quarry", "https://quarry.example.invalid/api", "QUARRY-KEY-0000-9a9a", useWithService = false), removed = false),
                ),
            ),
            debrid,
        )
        assertEquals(
            listOf(
                "Torrent indexer\nChanges “Home Prowlarr”\nSaved API key kept\nUse with your debrid service switched off",
                "Torrent indexer\nNew\nhttps://quarry.example.invalid/api · API key •••• 9a9a\nNamed “Quarry”\nNot used with your debrid service",
            ),
            r.items.map { it.text() },
        )
        val (change, new) = r.items.map { assertIs<TorrentIndexerReview>(it) }
        assertEquals("http://192.168.1.20:9696/3/api?apikey=hidden", change.form.fields.url, "its saved address whole")
        assertEquals(listOf("SAVED-TORRENT-KEY", false), listOf(change.testKey, change.form.useWithService))
        assertEquals(listOf("QUARRY-KEY-0000-9a9a", false), listOf(new.testKey, new.form.useWithService))
        assertEquals(listOf("torrent indexer 1", "torrent indexer 2"), r.items.map { it.part })
        // Left as shown: no item.
        assertEquals(emptyList(), review(debridChanges(torrentIndexers = listOf(SetupChange("tor-1", prowlarrAsShown, false))), debrid).items)
        // Ticked again on one saved off: said.
        val off = debrid.copy(torrentIndexers = listOf(prowlarr.copy(record = prowlarr.record.copy(useWithService = false))))
        assertEquals(
            listOf("Saved API key kept", "Use with your debrid service switched on"),
            review(debridChanges(torrentIndexers = listOf(SetupChange("tor-1", prowlarrAsShown, false))), off).items.single().lines.map { it.text },
        )
        // Removed: its key with it.
        val removed = review(debridChanges(torrentIndexers = listOf(SetupChange("tor-1", prowlarrAsShown, true))), debrid).items.single()
        assertEquals("Torrent indexer\nRemoves “Home Prowlarr”\nhttp://192.168.1.20:9696/3/api · its saved API key is deleted too", removed.text())
    }

    /** L209: moved to another host with its key left empty, a torrent indexer card can't be saved, and its saved key isn't taken for it. */
    @Test
    fun `a torrent indexer moved to another host without its key can't be saved, nor its saved key taken`() {
        val moved = review(debridChanges(torrentIndexers = listOf(SetupChange("tor-1", prowlarrAsShown.copy(url = "https://tracker.example.invalid/3/api"), false))), debrid)
        val item = assertIs<TorrentIndexerReview>(moved.items.single())
        assertEquals(INDEXER_ADDRESS_CHANGED, item.problem)
        assertNull(item.testKey)
        assertTrue(item.lines.any { it.text == "Address: http://192.168.1.20:9696/3/api → https://tracker.example.invalid/3/api" && it.warn }, item.text())
        // A new one at a saved one's address is said to be the same; a pass from the phone counts for a torrent indexer alone.
        val passes = PassLog().apply { passedTorrentIndexer("http://192.168.1.20:9696/3/api", "TYPED-TORRENT-KEY", "Connected") }
        val same = review(debridChanges(torrentIndexers = listOf(SetupChange(null, TorrentIndexerDraft("", "http://192.168.1.20:9696/3/api", "TYPED-TORRENT-KEY", true), false))), debrid, passes).items.single()
        assertTrue(same.lines.any { it.text == "Same address as the saved “Home Prowlarr”" && it.warn }, same.text())
        assertTrue(same.tested)
        assertEquals(INDEXER_NO_KEY, review(debridChanges(torrentIndexers = listOf(SetupChange(null, TorrentIndexerDraft("", "https://quarry.example.invalid/api", "", true), false))), debrid).items.single().problem)
        // A typed key at the new address is the one it takes.
        val typed = review(debridChanges(torrentIndexers = listOf(SetupChange("tor-1", prowlarrAsShown.copy(url = "https://tracker.example.invalid/3/api", key = "TYPED-TORRENT-KEY"), false))), debrid).items.single()
        assertEquals(listOf(null, "TYPED-TORRENT-KEY"), listOf(typed.problem, assertIs<TorrentIndexerReview>(typed).testKey))
    }

    @Test
    fun `what the TV's forms wouldn't save is a problem`() {
        listOf(
            ProviderDraft("", "", null, true, "x", "p", null) to "No host",
            ProviderDraft("", "alice:pw@news.example.net", null, true, "", "", null) to "Only the server's name",
            ProviderDraft("", "news.example.net", 70000, true, "", "", null) to "port",
            ProviderDraft("", "news.example.net", null, true, "", "", 0) to "Connections",
        ).forEach { (draft, words) ->
            val p = review(changes(providers = listOf(SetupChange(null, draft, false)), indexers = emptyList()), none).items.single()
            assertTrue(p.problem?.contains(words) == true, "${p.problem} for $words")
        }
        fun indexer(url: String, key: String) = review(changes(providers = emptyList(), indexers = listOf(SetupChange(null, IndexerDraft("", url, key), false))), none).items.single()
        assertEquals(INDEXER_NO_KEY, indexer("https://new.example.org/api", "").problem)
        assertEquals(INDEXER_URL_SCHEME, indexer("ftp://new.example.org/api", "K-0000-1111-2222").problem)
    }
}
