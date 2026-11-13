package io.skjaere.usenet.handoff

import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SetupPageTest {
    private val fixedPages = mapOf(
        "sent" to SetupPage.sent, "used" to SetupPage.used, "locked" to SetupPage.locked, "expired" to SetupPage.expired,
        "closed at the cap" to SetupPage.closedAtCap, "notFound" to SetupPage.notFound, "rejected" to SetupPage.rejected,
        "otherDevice" to SetupPage.otherDevice, "refused" to SetupPage.refused,
    )
    private val allPages by lazy { fixedPages + flowPages }

    private fun styleOf(page: String): String {
        val styles = Regex("(?s)<style>(.*?)</style>").findAll(page).map { it.groupValues[1] }.toList()
        assertEquals(1, styles.size, "one inline style")
        return styles.single()
    }

    private fun sha256(text: String) = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))

    /** [text] as a browser reads it in an attribute or between tags: the page's escapes undone, &amp; last. */
    private fun unescape(text: String) =
        text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")

    private fun buttons(page: String) = Regex("""<button ([^>]*)>([^<]*)</button>""").findAll(page).map { it.groupValues[1] to it.groupValues[2] }.toList()

    /** Each tag of [page] that is an input, as its attributes. */
    private fun inputs(page: String): List<Map<String, String?>> = Regex("<input ([^>]*)>").findAll(page).map { tag ->
        Regex("""([a-z0-9-]+)(?:="([^"]*)")?""").findAll(tag.groupValues[1]).associate { it.groupValues[1] to it.groups[2]?.value }
    }.toList()

    @Test
    fun `every response carries the security headers, and only those`() {
        assertEquals(
            listOf(
                "Content-Type" to "text/html; charset=utf-8",
                "Cache-Control" to "no-store",
                "Referrer-Policy" to "same-origin",
                "X-Frame-Options" to "DENY",
                "X-Content-Type-Options" to "nosniff",
                "Content-Security-Policy" to SetupPage.contentSecurityPolicy,
            ),
            SetupPage.headers,
        )
    }

    @Test
    fun `the policy allows only the page's own style and posting to itself`() {
        // Pinned: a change to the style changes its hash, here as in the page.
        assertEquals(
            "default-src 'none'; style-src 'sha256-$PINNED_STYLE_HASH'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'",
            SetupPage.contentSecurityPolicy,
        )
        allPages.forEach { (name, page) -> assertEquals(PINNED_STYLE_HASH, sha256(styleOf(page)), name) }
    }

    @Test
    fun `pages have no script, no link and nothing from elsewhere`() {
        for ((name, page) in allPages) {
            assertTrue(page.startsWith("<!doctype html>"), name)
            assertTrue(page.contains("<meta charset=\"utf-8\">"), name)
            assertTrue(page.contains("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"), name)
            for (banned in listOf("<script", "<a ", "href=", "src=", "<link", "<img", "<iframe", "<object", "<base", "@import", "url(", "javascript:", " style=")) {
                assertFalse(page.contains(banned, ignoreCase = true), "$name has $banned")
            }
            assertFalse(Regex("""\son[a-z]+=""").containsMatchIn(page), "$name has an event handler")
        }
    }

    @Test
    fun `the fixed pages say what happened and point back to the TV`() {
        assertTrue(SetupPage.sent.contains("Sent. Check your TV."))
        assertTrue(SetupPage.used.contains("This page has been used. Check your TV."))
        assertTrue(SetupPage.locked.contains("This code was closed after 5 wrong tries."))
        assertTrue(SetupPage.notFound.contains("Nothing here. Check the address on your TV."))
        assertTrue(SetupPage.rejected.contains("Something you typed couldn't be sent."))
        assertTrue(SetupPage.otherDevice.contains("This code is open on another device."))
        assertTrue(SetupPage.refused.contains("This request was refused."))
        fixedPages.forEach { (name, page) -> assertFalse(page.contains("<form"), name) }
    }

    @Test
    fun `result words are escaped, cut to 200 characters and rid of controls`() {
        fun line(words: String): String {
            val flow = SetupFlow(SetupMode.Wizard, SetupSeed())
            flow.tested(SetupAction.Test, SetupField.ProviderHost to "h", result = SetupResult(SetupOutcome.Failed, words))
            return Regex("""<p class="result fail">([^<]*)</p>""").find(flow.page())!!.groupValues[1]
        }
        assertEquals("✗ &lt;b&gt;&quot;no&quot;&lt;/b&gt; &amp; &#39;nope&#39;", line("<b>\"no\"</b> & 'nope'"))
        assertEquals("✗ line one line two tab end", line("line one\nline two\ttab\u0000\u001b\u007f\u0085 \u202eend\u200b"))
        assertEquals("✗ " + "a".repeat(199) + "…", line("a".repeat(300)))
        assertEquals("✗ " + "a".repeat(200), line("a".repeat(200)))
        // A character outside the BMP isn't cut in half.
        assertEquals("✗ " + "a".repeat(198) + "…", line("a".repeat(198) + "😀".repeat(5)))
    }

    /** What a log or a failed assertion prints of a result: how it went, never its words, which may name a host. */
    @Test
    fun `a result prints its outcome, not its words`() {
        val passed = SetupResult(SetupOutcome.Passed, "Connected and logged in to news.example.invalid")
        assertFalse(passed.toString().contains("Connected"), passed.toString())
        assertFalse(SetupEvent.TestFinished(SetupSlot(SetupSection.Provider, 1), SetupOutcome.Passed).toString().contains("Connected"))
    }

    // The wizard's steps and the edit page, rendered from the flow.

    private val main = SavedProvider("prov-1", "Main provider", "news.example.invalid", 563, true, "someone", 20, true, SavedSecret.Saved)
    private val block = SavedProvider("prov-2", "Old block", "block.example.invalid", 563, true, "someone", 8, true, SavedSecret.Saved)
    private val indexerA = SavedIndexer("idx-1", "Indexer A", "https://indexer-a.example.invalid/api", false, true, SavedSecret.Saved)
    private val home = SavedIndexer("idx-2", "Home", "http://192.168.1.10:9696/1", true, false, SavedSecret.Saved)
    private val seed = SetupSeed(listOf(main, block), listOf(indexerA, home), SavedSecret.Saved)
    private val p1 = SetupSlot(SetupSection.Provider, 1)
    private val p2 = SetupSlot(SetupSection.Provider, 2)
    private val p3 = SetupSlot(SetupSection.Provider, 3)
    private val i1 = SetupSlot(SetupSection.Indexer, 1)
    private val works = SetupResult(SetupOutcome.Passed, "Connected and logged in to news.example.invalid")
    private val fails = SetupResult(SetupOutcome.Failed, "Login refused: check the username and password.")

    private fun provider(name: String = "", host: String = "", port: String = "", tls: Boolean = true, user: String = "", pass: String = "", conns: String = "") =
        listOfNotNull(
            SetupField.ProviderName to name, SetupField.ProviderHost to host, SetupField.ProviderPort to port, (SetupField.ProviderTls to "on").takeIf { tls },
            SetupField.ProviderUsername to user, SetupField.ProviderPassword to pass, SetupField.ProviderConnections to conns,
        ).toTypedArray()

    private fun indexer(name: String = "", url: String = "", key: String = "") =
        arrayOf(SetupField.IndexerName to name, SetupField.IndexerUrl to url, SetupField.IndexerKey to key)

    private val typed = provider("Mine", "fill.example.invalid", "563", true, "me2", "marker-pass", "8")

    private fun SetupFlow.post(action: SetupAction, vararg fields: Pair<SetupField, String>, closingSoon: Boolean = false) =
        apply(SetupPost(version, action, fields.toMap()), closingSoon)

    private fun SetupFlow.tested(action: SetupAction, vararg fields: Pair<SetupField, String>, result: SetupResult = works) =
        finish((post(action, *fields) as Applied.Testing).ticket, result)

    private fun SetupFlow.page(closingSoon: Boolean = false, closesIn: Int? = null) = SetupPage.render(show(closingSoon, closesIn))

    private fun wizard() = SetupFlow(SetupMode.Wizard, SetupSeed())

    /** Step 1 with a provider that works and a second one open that failed, its password typed. */
    private fun stepOne() = wizard().apply {
        tested(SetupAction.Test, *typed)
        post(SetupAction.Add(SetupSection.Provider), *typed)
        tested(SetupAction.Test, *provider(host = "other.example.invalid", user = "me3", pass = "marker-other"), result = fails)
    }

    private fun stepTwo() = wizard().apply {
        tested(SetupAction.Test, *typed)
        post(SetupAction.Next, *typed)
        tested(SetupAction.Test, *indexer("Home Prowlarr", "http://192.168.1.10:9696/2/api", "marker-key"))
    }

    private fun stepThree() = stepTwo().apply { post(SetupAction.Next, *indexer("Home Prowlarr", "http://192.168.1.10:9696/2/api", "")) }

    private fun stepFour() = stepThree().apply {
        tested(SetupAction.Test, SetupField.TmdbKey to "marker-tmdb")
        post(SetupAction.Next, SetupField.TmdbKey to "")
    }

    /** The edit page with one saved provider removed, one changed and tested, a new one, and the TMDB key typed. */
    private fun editing() = SetupFlow(SetupMode.Edit, seed).apply {
        post(SetupAction.Drop(p2))
        post(SetupAction.Add(SetupSection.Provider))
        tested(SetupAction.Test, *typed)
        post(SetupAction.Open(i1), *typed)
        tested(SetupAction.Test, *indexer("Indexer A", "https://api.indexer-a.example.invalid/api", ""))
        post(SetupAction.Open(p1), *indexer("Indexer A", "https://api.indexer-a.example.invalid/api", ""))
    }

    // The debrid cards (L209, DbPhoneSetupService), with the boards' invented account, addresses and keys.

    private val choices = listOf(
        ServiceChoice("premiumize", "Premiumize", "In Premiumize: Account › API key"),
        ServiceChoice("torbox", "TorBox", "In TorBox: Settings › API key"),
    )
    private val torbox = SavedService("svc-1", "torbox", "quay.viewer@example.org", true, SavedSecret.Saved)
    private val lantern = SavedIndexer("idx-3", "Lantern Index", "https://lantern.example.org/api", false, true, SavedSecret.Saved)
    private val s1 = SetupSlot(SetupSection.Service, 1)
    private val r1 = SetupSlot(SetupSection.TorrentIndexer, 1)
    private val signedIn = SetupResult(SetupOutcome.Passed, "Signed in as quay.viewer@example.org · plan until 12 Jan 2027")

    private fun service(kind: String? = null, key: String = "") =
        listOfNotNull(kind?.let { SetupField.ServiceKind to it }, SetupField.ServiceKey to key).toTypedArray()

    private fun torrent(name: String = "", url: String = "", key: String = "", debrid: Boolean = true) = listOfNotNull(
        SetupField.TorrentIndexerName to name, SetupField.TorrentIndexerUrl to url, SetupField.TorrentIndexerKey to key,
        (SetupField.TorrentIndexerDebrid to "on").takeIf { debrid },
    ).toTypedArray()

    /** DbPhoneSetupService 1: the edit page, TorBox chosen on a new Debrid service card, its key pasted. */
    private fun serviceTyped() = SetupFlow(SetupMode.Edit, SetupSeed(listOf(main), listOf(lantern), SavedSecret.Saved, serviceKinds = choices)).apply {
        post(SetupAction.Add(SetupSection.Service))
        post(SetupAction.Enter, *service("torbox", "marker-service-key"))
    }

    /** DbPhoneSetupService 2: after its Test. */
    private fun serviceTested() = serviceTyped().apply { tested(SetupAction.Test, *service("torbox", ""), result = signedIn) }

    /** DbPhoneSetupService 4: another day, TorBox saved; a torrent indexer added and filled in. */
    private fun torrentTyped() = SetupFlow(SetupMode.Edit, SetupSeed(listOf(main), listOf(lantern), SavedSecret.Saved, choices, listOf(torbox))).apply {
        post(SetupAction.Add(SetupSection.TorrentIndexer))
        post(SetupAction.Enter, *torrent("Home Prowlarr", "http://192.168.1.20:9696/3/api", "marker-torrent-key"))
    }

    /** The wizard's step 1 and 2 with debrid shown: a service under the providers, a torrent indexer under the indexers. */
    private fun debridWizard() = SetupFlow(SetupMode.Wizard, SetupSeed(serviceKinds = choices)).apply {
        post(SetupAction.Add(SetupSection.Service), *provider())
        tested(SetupAction.Test, *service("torbox", "marker-service-key"), result = signedIn)
    }

    private fun debridStepTwo() = debridWizard().apply {
        post(SetupAction.Next, *service("torbox", ""))
        post(SetupAction.Add(SetupSection.TorrentIndexer), *indexer())
        tested(SetupAction.Test, *torrent("Home Prowlarr", "http://192.168.1.20:9696/3/api", "marker-torrent-key"))
    }

    private fun debridStepFour() = debridStepTwo().apply {
        post(SetupAction.Next, *torrent("Home Prowlarr", "http://192.168.1.20:9696/3/api"))
        post(SetupAction.Next, SetupField.TmdbKey to "")
    }

    private val flowPages: Map<String, String> by lazy {
        mapOf(
            "step 1, blank" to wizard().page(),
            "step 1" to stepOne().page(),
            "step 2" to stepTwo().page(),
            "step 3" to stepThree().page(),
            "step 4" to stepFour().page(),
            "edit" to SetupFlow(SetupMode.Edit, seed).page(),
            "edit, changes" to editing().page(),
            "edit, closing" to editing().page(closingSoon = true, closesIn = 4),
            "edit, a service typed" to serviceTyped().page(),
            "edit, a service tested" to serviceTested().page(),
            "edit, a torrent indexer typed" to torrentTyped().page(),
            "step 1, debrid" to debridWizard().page(),
            "step 2, debrid" to debridStepTwo().page(),
            "step 4, debrid" to debridStepFour().page(),
        )
    }

    private fun headings(page: String) = Regex("<h2>([^<]*)</h2>").findAll(page).map { it.groupValues[1] }.toList()

    /** The open card [slot] of [page], from its fieldset's start to its end. */
    private fun fieldset(page: String, slot: SetupSlot) = Regex("(?s)<fieldset id=\"${slot.key}\">.*?</fieldset>").find(page)!!.value

    @Test
    fun `the debrid sections follow Indexers as Settings orders them, and aren't there while the seed offers no service`() {
        val saved = SetupSeed(listOf(main), listOf(lantern), SavedSecret.Saved, choices, listOf(torbox), listOf(SavedTorrentIndexer("tor-1", "Home Prowlarr", "http://192.168.1.20:9696/3/api", false, true, false, SavedSecret.Saved)))
        val page = SetupFlow(SetupMode.Edit, saved).page()
        assertEquals(listOf("Usenet providers · tried in this order", "Indexers", "Debrid service", "Torrent indexers", "TMDB"), headings(page))
        assertTrue(page.contains("<p class=\"ct\">TorBox</p>\n<p class=\"cm\">quay.viewer@example.org · key saved</p>\n<p class=\"st\">Saved</p>"), page)
        assertTrue(page.contains("<p class=\"ct\">Home Prowlarr</p>\n<p class=\"cm\">http://192.168.1.20:9696/3/api · key saved · not used with your debrid service</p>"), page)
        assertEquals(
            listOf("Add a provider", "Add an indexer", "Add a debrid service", "Add a torrent indexer"),
            buttons(page).filter { it.first.contains("value=\"add:") }.map { it.second },
        )
        assertTrue(page.contains("<p class=\"warn\">$SETUP_NOT_ENCRYPTED ${SetupPage.SAVED_SHOWN}</p>"))
        // Both services signed in: no Add, one account each.
        val both = SetupFlow(SetupMode.Edit, saved.copy(services = listOf(torbox, SavedService("svc-2", "premiumize", "customer 900000042", false, SavedSecret.Saved)))).page()
        assertFalse(both.contains("value=\"add:service\""))
        assertTrue(both.contains("<p class=\"ct\">Premiumize</p>\n<p class=\"cm\">customer 900000042 · key saved</p>\n<p class=\"st\">Saved · switched off</p>"), both)

        val hidden = SetupFlow(SetupMode.Edit, saved.copy(serviceKinds = emptyList())).page()
        assertEquals(listOf("Usenet providers · tried in this order", "Indexers", "TMDB"), headings(hidden))
        for (gone in listOf("TorBox", "Home Prowlarr", "debrid", "torrent", "quay.viewer")) assertFalse(hidden.contains(gone, ignoreCase = true), gone)

        // Hidden, the wizard's steps are today's too, the torrent pair saved standing in for nothing: step 1 with its
        // provider alone, step 2 with its indexer alone, and each says what an empty step means.
        val wizard = SetupFlow(SetupMode.Wizard, SetupSeed(services = listOf(torbox), torrentIndexers = saved.torrentIndexers))
        val stepOne = wizard.page()
        wizard.post(SetupAction.Next, *provider())
        val stepTwo = wizard.page()
        assertEquals("Step 2 of 4 · Indexers", texts(stepTwo, "step").single())
        for ((step, page) in listOf("step 1" to stepOne, "step 2" to stepTwo)) {
            assertEquals(emptyList<String>(), headings(page), step)
            for (gone in listOf("TorBox", "Home Prowlarr", "debrid", "torrent", "quay.viewer")) assertFalse(page.contains(gone, ignoreCase = true), "$step: $gone")
        }
        assertEquals(listOf("Add a provider"), buttons(stepOne).filter { it.first.contains("value=\"add:") }.map { it.second })
        assertTrue(stepOne.contains(SetupPage.NO_PROVIDER), stepOne)
        assertEquals(listOf("Add an indexer"), buttons(stepTwo).filter { it.first.contains("value=\"add:") }.map { it.second })
        assertTrue(stepTwo.contains(SetupPage.NO_INDEXER), stepTwo)
    }

    /**
     * DbPhoneSetupService 1-2: the service a radio (the page has no script, so the lines under the key name each service's
     * place for it); Test runs on the TV and its words land on the card; the key never comes back, its line says it is kept.
     */
    @Test
    fun `a service card offers its services as radios, says where each keeps its key, and never shows the key`() {
        val typed = serviceTyped().page()
        assertTrue(typed.contains("<fieldset id=\"s1\">\n<legend>Debrid service 1</legend>\n"), typed)
        assertTrue(
            typed.contains(
                "<label id=\"s.kind-l\">Service</label>\n<div role=\"radiogroup\" aria-labelledby=\"s.kind-l\">\n" +
                    "<label class=\"check\"><input type=\"radio\" name=\"s.kind\" value=\"premiumize\"> Premiumize</label>\n" +
                    "<label class=\"check\"><input type=\"radio\" name=\"s.kind\" value=\"torbox\" checked> TorBox</label>\n</div>\n",
            ),
            typed,
        )
        assertEquals(listOf("In Premiumize: Account › API key", "In TorBox: Settings › API key", SetupPage.TYPED_KEPT), texts(typed, "fh"))
        val key = inputs(typed).single { it["name"] == "s.key" }
        assertEquals(listOf(null, "s.key-w1 s.key-w2 s.key-h", "512"), listOf(key["value"], key["aria-describedby"], key["maxlength"]))
        assertTrue("data-1p-ignore" in key && key["type"] == null, "a key's field, as an indexer's")
        assertEquals(listOf("Test", "Don't add this one"), buttons(fieldset(typed, s1)).map { it.second })
        assertTrue(typed.contains("<strong>Send to TV waits until TorBox is tested or not added.</strong>"), typed)

        val tested = serviceTested().page()
        assertTrue(tested.contains("<p class=\"result ok\">✓ Signed in as quay.viewer@example.org · plan until 12 Jan 2027</p>"), tested)
        assertEquals(listOf("Test again", "Don't add this one"), buttons(fieldset(tested, s1)).map { it.second })
        assertTrue(buttons(tested).any { it.second == "Send to TV" && "disabled" !in it.first })
        for (page in listOf(typed, tested)) assertFalse(page.contains("marker"), "the key typed came back")

        // A saved account's card keeps its service: its own radio alone, and its saved key's line.
        val saved = SetupFlow(SetupMode.Edit, SetupSeed(serviceKinds = choices, services = listOf(torbox))).apply { post(SetupAction.Open(s1)) }.page()
        assertTrue(saved.contains("<legend>TorBox</legend>"), saved)
        assertEquals(listOf("torbox"), inputs(saved).filter { it["type"] == "radio" }.map { it["value"] })
        assertTrue("checked" in inputs(saved).single { it["type"] == "radio" })
        assertEquals(listOf("In TorBox: Settings › API key", SetupPage.SERVICE_SAVED_KEPT), texts(saved, "fh"))
        assertEquals("Saved on the TV · leave empty to keep it.", SetupPage.SERVICE_SAVED_KEPT)
        assertEquals(listOf("Test", "Remove"), buttons(fieldset(saved, s1)).map { it.second })
        // A new card with none chosen yet: both radios, neither checked.
        val none = SetupFlow(SetupMode.Edit, SetupSeed(serviceKinds = choices)).apply { post(SetupAction.Add(SetupSection.Service)) }.page()
        assertEquals(listOf(false, false), inputs(none).filter { it["type"] == "radio" }.map { "checked" in it })
    }

    /** DbPhoneSetupService 4: an indexer's fields, then "Use with your debrid service", ticked by default, with its line. */
    @Test
    fun `a torrent indexer card has an indexer's fields and its box, ticked by default, with its line`() {
        val page = torrentTyped().page()
        assertTrue(page.contains("<fieldset id=\"r1\">\n<legend>Torrent indexer 1</legend>\n"), page)
        assertEquals(listOf("v", "r.name", "r.url", "r.key", "r.debrid"), inputs(page).map { it["name"] })
        assertEquals(listOf("url", "url"), inputs(page).single { it["name"] == "r.url" }.let { listOf(it["type"], it["inputmode"]) })
        assertEquals(listOf("Home Prowlarr", "http://192.168.1.20:9696/3/api", null), listOf("r.name", "r.url", "r.key").map { n -> inputs(page).single { it["name"] == n }["value"] })
        assertTrue(
            page.contains(
                "<label class=\"check\"><input type=\"checkbox\" id=\"r.debrid\" name=\"r.debrid\" value=\"on\" aria-describedby=\"r.debrid-h\" checked> " +
                    "Use with your debrid service</label>\n<p id=\"r.debrid-h\" class=\"fh\">Some private trackers don’t allow debrid services. Untick it for those.</p>\n",
            ),
            page,
        )
        assertEquals(listOf(SetupPage.TYPED_KEPT, SetupPage.TORRENT_DEBRID_HELP), texts(page, "fh"))
        assertFalse(page.contains("marker"))
        assertTrue(page.contains("<strong>Send to TV waits until Home Prowlarr is tested or not added.</strong>"), page)
        // Unticked, the box comes back unticked.
        val off = torrentTyped().apply { post(SetupAction.Enter, *torrent("Home Prowlarr", "http://192.168.1.20:9696/3/api", "", debrid = false)) }.page()
        assertFalse("checked" in inputs(off).single { it["name"] == "r.debrid" })
    }

    /** L209: in the wizard, the service card on step 1 under the providers, the torrent indexer card on step 2 under the indexers. */
    @Test
    fun `the wizard has the service under the providers and the torrent indexer under the indexers, and step 4 lists them`() {
        val one = debridWizard().page()
        assertEquals("Step 1 of 4 · Usenet providers", texts(one, "step").single())
        assertEquals(listOf("Debrid service"), headings(one))
        assertEquals(listOf("Add a provider", "Add another debrid service"), buttons(one).filter { it.first.contains("value=\"add:") }.map { it.second })
        assertFalse(one.contains(SetupPage.NO_PROVIDER), "a service plays without a provider")
        val two = debridStepTwo().page()
        assertEquals(listOf("Torrent indexers"), headings(two))
        assertEquals(listOf("Add an indexer", "Add another torrent indexer"), buttons(two).filter { it.first.contains("value=\"add:") }.map { it.second })
        assertFalse(two.contains(SetupPage.NO_INDEXER))
        val four = debridStepFour().page()
        assertEquals(listOf("Usenet providers", "Indexers", "Debrid service", "Torrent indexers", "TMDB"), headings(four))
        assertTrue(four.contains("<p class=\"ct\"><span class=\"tag\">New</span>TorBox</p>\n<p class=\"cm\">key typed</p>"), four)
        assertTrue(four.contains("<p class=\"ct\"><span class=\"tag\">New</span>Home Prowlarr</p>\n<p class=\"cm\">http://192.168.1.20:9696/3/api · key typed</p>"), four)
        assertFalse(four.contains("marker"))
        // The Usenet lists left empty say so plainly: a debrid card plays and searches in their place.
        assertEquals(2, four.split("<p class=\"hint\">${SetupPage.NONE_ADDED}</p>").size - 1)
        assertFalse(four.contains(SetupPage.NO_PROVIDER) || four.contains(SetupPage.NO_INDEXER))
        // Without them, step 4 is as it was.
        assertEquals(listOf("Usenet providers", "Indexers", "TMDB"), headings(stepFour().page()))
    }

    /**
     * Step 4 says what an empty Usenet list means as steps 1 and 2 do: a debrid card being removed plays and searches in
     * nothing's place, so the lines that nothing plays, or nothing is searched, stay, over the debrid card's Removes.
     */
    @Test
    fun `step 4 warns of an empty Usenet list when the debrid card in its place is being removed`() {
        fun stepFour(seed: SetupSeed, drop: SetupSlot) = SetupFlow(SetupMode.Wizard, seed).apply {
            if (drop.section == SetupSection.Service) post(SetupAction.Drop(drop), *provider())
            post(SetupAction.Next, *provider())
            if (drop.section == SetupSection.TorrentIndexer) post(SetupAction.Drop(drop), *indexer())
            post(SetupAction.Next, *indexer())
            post(SetupAction.Next, SetupField.TmdbKey to "")
        }.page()

        // TorBox saved, nothing else: the wizard, and TorBox removed on step 1.
        val service = stepFour(SetupSeed(serviceKinds = choices, services = listOf(torbox)), s1)
        assertEquals("Step 4 of 4 · Check and send", texts(service, "step").single())
        assertEquals(listOf("Usenet providers", "Indexers", "Debrid service", "TMDB"), headings(service))
        assertTrue(service.contains("<h2>Usenet providers</h2>\n<p class=\"hint\">${SetupPage.NO_PROVIDER}</p>"), service)
        assertTrue(service.contains("<p class=\"ct\"><span class=\"tag rm\">Removes</span>TorBox</p>"), service)
        assertFalse(service.contains(SetupPage.NONE_ADDED), "a debrid service being removed plays in nothing's place")

        // Home Prowlarr saved, nothing else: removed on step 2.
        val prowlarr = SavedTorrentIndexer("tor-1", "Home Prowlarr", "http://192.168.1.20:9696/3/api", false, true, true, SavedSecret.Saved)
        val torrent = stepFour(SetupSeed(serviceKinds = choices, torrentIndexers = listOf(prowlarr)), r1)
        assertEquals(listOf("Usenet providers", "Indexers", "Torrent indexers", "TMDB"), headings(torrent))
        assertTrue(torrent.contains("<h2>Indexers</h2>\n<p class=\"hint\">${SetupPage.NO_INDEXER}</p>"), torrent)
        assertTrue(torrent.contains("<p class=\"ct\"><span class=\"tag rm\">Removes</span>Home Prowlarr</p>"), torrent)
        assertFalse(torrent.contains(SetupPage.NONE_ADDED), "a torrent indexer being removed searches in nothing's place")
    }

    /** Every form a page makes, every kind of card filled and open in turn, is within the field count and reads back. */
    @Test
    fun `the form of every page, a card of each kind open, fits the field count and reads back`() {
        val flows = listOf(stepOne(), stepTwo(), stepThree(), editing(), serviceTyped(), serviceTested(), torrentTyped(), debridWizard(), debridStepTwo(), debridStepFour())
        for (flow in flows) {
            val page = flow.page()
            assertTrue(formOf(page).size + 1 <= SetupForm.MAX_FIELDS, "${flow.step}: ${formOf(page).size}")
            assertIs<SetupPost>(parse(page, flow, SetupAction.Enter), "${flow.step}: ${flow.layout().open}")
        }
        val sections = flows.mapNotNull { it.layout().open?.section }.toSet()
        assertEquals(SetupSection.entries.toSet(), sections, "a kind of card no page had open")
    }

    /** What a browser posts from [page], the button aside: the hidden counter, each input's value, the box when ticked. */
    private fun formOf(page: String): List<Pair<String, String>> = inputs(page).mapNotNull { input ->
        val name = input.getValue("name")!!
        when {
            input["type"] == "checkbox" -> if ("checked" in input) name to "on" else null
            // Of a radio group, the one checked, if any.
            input["type"] == "radio" -> if ("checked" in input) name to unescape(input["value"].orEmpty()) else null
            else -> name to unescape(input["value"].orEmpty())
        }
    }

    private fun parse(page: String, flow: SetupFlow, action: SetupAction) =
        SetupForm.parse((formOf(page) + ("do" to action.value)).joinToString("&") { (k, v) -> "$k=${java.net.URLEncoder.encode(v, "UTF-8")}" }.toByteArray(), flow.layout())

    /** The lines of [cls], whether or not a field names them for a screen reader. */
    private fun texts(page: String, cls: String) = Regex("""<p(?: id="[^"]*")? class="$cls">(.*?)</p>""").findAll(page).map { it.groupValues[1] }.toList()

    @Test
    fun `every page of the wizard and the edit page has the one style, nothing from elsewhere and no script`() {
        for ((name, page) in flowPages + mapOf("expired" to SetupPage.expired, "closed at the cap" to SetupPage.closedAtCap)) {
            assertEquals(PINNED_STYLE_HASH, sha256(styleOf(page)), name)
            assertTrue(page.startsWith("<!doctype html>"), name)
            for (banned in listOf("<script", "<a ", "href=", "src=", "<link", "<img", "<iframe", "<object", "<base", "@import", "url(", "javascript:", " style=", "formaction", "formmethod", "required")) {
                assertFalse(page.contains(banned, ignoreCase = true), "$name has $banned")
            }
            assertFalse(Regex("""\son[a-z]+=""").containsMatchIn(page), "$name has an event handler")
        }
        // The app's colours, always dark.
        val style = styleOf(flowPages.getValue("step 1"))
        for (token in listOf("color-scheme:dark", "#0E0F12", "#1A1C21", "#2A2D34", "#F3F1EC", "#C9CBD1", "#A3A6AE", "#3A3D44", "#F2A33A", "#1A1206", "#1F3328", "#A8E0A4", "#3A2412", "#FFB27A", "#FFC9A3", "accent-color:#F2A33A", "border-radius:24px")) {
            assertTrue(style.contains(token), token)
        }
    }

    @Test
    fun `a form page posts to itself without validation, Enter's button first, then the counter and the open card's fields`() {
        for ((name, page) in flowPages) {
            val form = Regex("<form ([^>]*)>").findAll(page).map { it.groupValues[1] }.toList()
            assertEquals(listOf("""method="post" accept-charset="UTF-8" autocomplete="off" novalidate"""), form, name)
            val first = buttons(page).first().first
            assertEquals("""type="submit" name="do" value="enter" class="offscreen" tabindex="-1" aria-hidden="true"""", first, name)
            assertTrue(buttons(page).all { it.first.startsWith("type=\"submit\" name=\"do\" value=\"") }, name)
            assertEquals("hidden", inputs(page).first()["type"], name)
        }
        val flow = stepOne()
        val page = flow.page()
        assertEquals(listOf("v") + flow.layout().fields.map { it.key }, inputs(page).map { it["name"] })
        assertTrue(page.contains("""<input type="hidden" name="v" value="${flow.version}">"""))
        assertEquals(listOf("v"), inputs(stepFour().page()).map { it["name"] }, "step 4 has no inputs but the counter")
        assertEquals(listOf("v"), inputs(SetupFlow(SetupMode.Edit, seed).page()).map { it["name"] }, "no card open")
    }

    @Test
    fun `every button a page has is one its form may send`() {
        for (flow in listOf(wizard(), stepOne(), stepTwo(), stepThree(), stepFour(), SetupFlow(SetupMode.Edit, seed), editing(), serviceTyped(), serviceTested(), torrentTyped(), debridWizard(), debridStepTwo(), debridStepFour())) {
            val page = flow.page()
            val values = buttons(page).map { Regex("value=\"([^\"]+)\"").find(it.first)!!.groupValues[1] }
            for (value in values) {
                val action = SetupAction.of(value) ?: error("not an action: $value")
                assertTrue(flow.layout().offers(action), "${flow.step}: $value")
            }
        }
    }

    /**
     * Every field of every kind of card, as the pages have them open: the browser stops at the TV's limit (past it the
     * whole form would be refused), no capitals, corrections or saved entries, the keyboard for what it takes, and
     * nothing required (one empty field mustn't keep another card's buttons from working).
     */
    @Test
    fun `fields ask for plain typing, within the TV's limits, and none is required`() {
        val seen = mutableMapOf<SetupField, Map<String, String?>>()
        for ((name, page) in flowPages) for (input in inputs(page)) {
            val field = SetupField.entries.firstOrNull { it.key == input["name"] } ?: continue
            seen[field] = input
            assertFalse("required" in input, "$name: ${field.key}")
            if (field.kind == SetupField.Kind.Checkbox || field.kind == SetupField.Kind.Choice) continue
            assertEquals("${field.maxLength}", input["maxlength"], "$name: ${field.key}")
            assertEquals(listOf("none", "off", "false", "off"), listOf(input["autocapitalize"], input["autocorrect"], input["spellcheck"], input["autocomplete"]), "$name: ${field.key}")
        }
        assertEquals(SetupField.entries.toSet(), seen.keys, "a field no page showed")
        val keyboards = mapOf(
            SetupField.ProviderHost to listOf(null, "url"),
            SetupField.ProviderPort to listOf(null, "numeric"),
            SetupField.ProviderConnections to listOf(null, "numeric"),
            SetupField.ProviderTls to listOf("checkbox", null),
            SetupField.ProviderPassword to listOf("password", null),
            SetupField.IndexerUrl to listOf("url", "url"),
            SetupField.IndexerKey to listOf(null, null),
            SetupField.ServiceKind to listOf("radio", null),
            SetupField.ServiceKey to listOf(null, null),
            SetupField.TorrentIndexerUrl to listOf("url", "url"),
            SetupField.TorrentIndexerKey to listOf(null, null),
            SetupField.TorrentIndexerDebrid to listOf("checkbox", null),
            SetupField.TmdbKey to listOf(null, null),
        )
        for ((field, kind) in keyboards) assertEquals(kind, seen.getValue(field).let { listOf(it["type"], it["inputmode"]) }, field.key)
    }

    @Test
    fun `what is typed comes back as typed and reads back the same, but never a password or key`() {
        val hostileTyped = provider("A \"quoted\" <b>name</b> & 'more'", "news.example.invalid\"><x>", "1119", false, "o'neil&co", "marker \"<pass>'&", "3")
        val flow = wizard()
        flow.post(SetupAction.Enter, *hostileTyped)
        val page = flow.page()
        val before = flow.show()
        val read = parse(page, flow, SetupAction.Enter) as SetupPost
        assertEquals(hostileTyped.filterNot { it.first == SetupField.ProviderPassword }.toMap() + (SetupField.ProviderPassword to ""), read.fields)
        assertFalse(page.contains("marker"), "the password came back")
        // Applied again, it changes nothing: the password held stays.
        flow.apply(read)
        assertEquals(before.providers.single().values, flow.show().providers.single().values)
        assertEquals("marker \"<pass>'&", flow.changes().providers.single().draft.password)
        for (banned in listOf("<b>", "<x>", "'&")) assertFalse(page.contains(banned), banned)
        Regex("""value="([^"]*)"""").findAll(page).map { it.groupValues[1] }.forEach { value ->
            assertTrue(value.none { it in "<>\"'" } && Regex("&(?!(amp|lt|gt|quot|#39);)").find(value) == null, value)
        }
    }

    @Test
    fun `no page holds a password or key typed, and a secret's field says what it keeps`() {
        for ((name, page) in flowPages) for (marker in listOf("marker-pass", "marker-other", "marker-key", "marker-tmdb", "marker-service-key", "marker-torrent-key")) {
            assertFalse(page.contains(marker), "$name holds $marker")
        }
        val one = stepOne().page()
        assertEquals(listOf(SetupPage.TYPED_KEPT), texts(one, "fh"))
        assertEquals("Typed · kept on the TV. Leave empty to keep it.", SetupPage.TYPED_KEPT)
        val password = inputs(one).single { it["name"] == "p.pass" }
        assertEquals(listOf("password", "off", null, "true", null, "other"), listOf(password["type"], password["autocomplete"], password["data-1p-ignore"], password["data-lpignore"], password["data-bwignore"], password["data-form-type"]))
        assertTrue("data-1p-ignore" in password && "data-bwignore" in password)
        assertFalse("value" in password)
        assertEquals(1, inputs(one).count { it["type"] == "password" }, "one password field a page")
        val edit = SetupFlow(SetupMode.Edit, seed).apply { post(SetupAction.Open(p1)) }.page()
        assertEquals(listOf(SetupPage.SAVED_KEPT), texts(edit, "fh"))
        assertEquals("Saved on the TV · leave empty to keep it (for the same server).", SetupPage.SAVED_KEPT)
        val unreadable = SetupFlow(SetupMode.Edit, SetupSeed(listOf(main.copy(login = SavedSecret.Unreadable)))).apply { post(SetupAction.Open(p1)) }.page()
        assertEquals(listOf(SetupPage.PASSWORD_UNREADABLE), texts(unreadable, "fh"))
        val key = inputs(stepTwo().apply { post(SetupAction.Open(i1)) }.page()).single { it["name"] == "i.key" }
        assertFalse("value" in key)
        assertTrue("data-lpignore" in key)
    }

    @Test
    fun `a page that lists saved items shows them escaped, cleaned of controls, and says it shows no passwords or keys`() {
        val odd = SetupSeed(listOf(main.copy(name = "Main <b>\"provider\"</b>‮ & co", username = "o'neil")), listOf(home))
        val page = SetupFlow(SetupMode.Edit, odd).page()
        assertTrue(page.contains("Main &lt;b&gt;&quot;provider&quot;&lt;/b&gt; &amp; co"), page)
        assertFalse(page.contains("‮"))
        assertTrue(page.contains("<p class=\"warn\">$SETUP_NOT_ENCRYPTED ${SetupPage.SAVED_SHOWN}</p>"))
        assertEquals("This page shows your saved servers and usernames, never passwords or keys.", SetupPage.SAVED_SHOWN)
        assertTrue(page.contains("o&#39;neil"))
        assertTrue(page.contains(SetupPage.HIDDEN_PARTS))
        assertFalse(wizard().page().contains(SetupPage.SAVED_SHOWN), "nothing saved, nothing shown")
        assertTrue(wizard().page().contains("<p class=\"warn\">$SETUP_NOT_ENCRYPTED</p>"))
    }

    @Test
    fun `the wizard's steps have their headings, their bar, Back after the first and Next once ready`() {
        val headings = listOf(wizard(), stepTwo(), stepThree(), stepFour()).map { texts(it.page(), "step").single() }
        assertEquals(listOf("Step 1 of 4 · Usenet providers", "Step 2 of 4 · Indexers", "Step 3 of 4 · TMDB key (optional)", "Step 4 of 4 · Check and send"), headings)
        assertEquals(listOf(1, 2, 3, 4), listOf(wizard(), stepTwo(), stepThree(), stepFour()).map { Regex("<span class=\"on\"></span>").findAll(it.page()).count() })
        fun labels(flow: SetupFlow) = buttons(flow.page()).filter { "value=\"next\"" in it.first || "value=\"back\"" in it.first || "value=\"send\"" in it.first }
        assertEquals(listOf("Next"), labels(wizard()).map { it.second })
        assertEquals(listOf("Back", "Next"), labels(stepTwo()).map { it.second })
        assertEquals(listOf("Back", "Skip"), labels(stepThree()).map { it.second })
        assertEquals(listOf("Back", "Send to TV"), labels(stepFour()).map { it.second })
        assertTrue(labels(stepThree().apply { post(SetupAction.Enter, SetupField.TmdbKey to "k") }).any { it.second == "Next" })
        // Not ready: Next can't be pressed, and the page says why.
        val waiting = stepOne().page()
        assertEquals("""type="submit" name="do" value="next" disabled""", buttons(waiting).single { "value=\"next\"" in it.first }.first)
        assertTrue(waiting.contains("<p class=\"hint\"><strong>To go on, test Provider 2 again or don't add it.</strong></p>"), waiting)
        assertFalse("disabled" in buttons(stepTwo().page()).single { "value=\"next\"" in it.first }.first)
        // The empty steps say what it means.
        val noProvider = wizard().apply { post(SetupAction.Drop(p1), *provider()) }.page()
        assertTrue(noProvider.contains(SetupPage.NO_PROVIDER))
        assertEquals("No provider added: nothing plays until you add one, here or on the TV.", SetupPage.NO_PROVIDER)
        assertTrue(buttons(noProvider).any { it.second == "Add a provider" })
        assertTrue(buttons(stepOne().page()).any { it.second == "Add another provider" })
        assertTrue(buttons(stepTwo().page()).any { it.second == "Add another indexer" })
        assertTrue(wizard().apply { post(SetupAction.Next, *provider()) }.page().contains(SetupPage.NO_INDEXER))
        assertEquals("No indexer added: you can still play .nzb files you open.", SetupPage.NO_INDEXER)
        assertTrue(stepThree().page().contains(SetupPage.TMDB_WHY))
        assertTrue(stepThree().page().contains(SetupPage.TMDB_SKIP_HINT))
        assertFalse(stepThree().page().contains(SetupPage.TRY_ON_TV), "step 3 says what Skip does instead")
    }

    @Test
    fun `a card's lines say how its test went, or why it needs one`() {
        val one = stepOne().page()
        // The summary: what it is and how it went; the open card: its legend, then its result.
        assertTrue(one.contains("<div class=\"card\" id=\"p1\">\n<p class=\"ct\">Mine</p>\n<p class=\"cm\">fill.example.invalid:563 · TLS · me2 · password typed</p>\n<p class=\"st good\">✓ Works</p>"), one)
        assertTrue(one.contains("<fieldset id=\"p2\">\n<legend>Provider 2</legend>\n<p class=\"result fail\">✗ Login refused: check the username and password.</p>"), one)
        // Changed after it worked, and closed: its summary says so.
        val changed = stepOne().apply { post(SetupAction.Open(p1), *provider(host = "other.example.invalid", user = "me3", pass = "x")) }
        changed.post(SetupAction.Add(SetupSection.Provider), *typed.map { if (it.first == SetupField.ProviderPort) it.first to "443" else it }.toTypedArray())
        assertTrue(changed.page().contains("<p class=\"cm\">fill.example.invalid:443 · TLS · me2 · password typed</p>\n<p class=\"st\">${SetupPage.CHANGED_SUMMARY}</p>"), changed.page())
        assertEquals("Changed since its test", SetupPage.CHANGED_SUMMARY)
        val open = stepOne().apply { post(SetupAction.Enter, *provider(host = "other.example.invalid", port = "443", user = "me3")) }.page()
        assertTrue(open.contains("<p class=\"result\">${SetupPage.CHANGED_SINCE_TEST}</p>"), open)
        assertEquals("Changed since its test: test it again, or don't add it.", SetupPage.CHANGED_SINCE_TEST)
        // No test can run: it may go on, and says the TV tests it.
        val closing = wizard().apply { post(SetupAction.Enter, *typed) }.page(closingSoon = true)
        assertTrue(closing.contains("<p class=\"result\">${SetupPage.UNTESTED_CLOSING}</p>"))
        assertEquals("Not tested: this code closes in less than a minute. Send now; Save on the TV tests it.", SetupPage.UNTESTED_CLOSING)
        val noneLeft = SetupFlow(SetupMode.Wizard, SetupSeed(), SetupLimits(tests = 0)).apply { post(SetupAction.Enter, *typed) }.page()
        assertTrue(noneLeft.contains("<p class=\"result\">${SetupPage.UNTESTED_NO_TESTS}</p>"))
        assertEquals("Not tested: the TV tests it when you save.", SetupPage.UNTESTED_NO_TESTS)
        // Plain http over the internet: the page warns, and offers to test it anyway.
        val http = stepTwo().apply {
            post(SetupAction.Open(i1), *indexer("Home Prowlarr", "http://192.168.1.10:9696/2/api", ""))
            tested(SetupAction.Test, *indexer("Far", "http://indexer.example.invalid/api", "k"), result = SetupResult(SetupOutcome.NotTested, "the app's words", plainHttp = true))
        }.page()
        assertTrue(http.contains("<p class=\"result http\">${SetupPage.PLAIN_HTTP}</p>"), http)
        assertEquals("This address isn't encrypted: your API key would cross the internet readable.", SetupPage.PLAIN_HTTP)
        assertTrue(buttons(http).any { "value=\"test-http\"" in it.first && it.second == "Test anyway" })
        // A plain not-tested line, words cleaned and escaped.
        val notTested = wizard().apply { tested(SetupAction.Test, *typed, result = SetupResult(SetupOutcome.NotTested, "type the <password>\nhere")) }.page()
        assertTrue(notTested.contains("<p class=\"result\">Not tested: type the &lt;password&gt; here</p>"), notTested)
    }

    @Test
    fun `a field the TV couldn't hold is named on its card, and what was in it never comes back`() {
        val flow = wizard()
        flow.apply(SetupPost(flow.version, SetupAction.Next, mapOf(SetupField.ProviderName to "Mine"), mapOf(SetupField.ProviderHost to SetupRejection.Reason.Characters, SetupField.ProviderPort to SetupRejection.Reason.NotANumber)))
        val page = flow.page()
        assertEquals(
            listOf("Host: it has a character it can't hold, so it wasn't kept.", "Port: it isn't a whole number, so it wasn't kept."),
            texts(page, "result fail"),
        )
        // Closed by another card's button, it still says so.
        flow.apply(SetupPost(flow.version, SetupAction.Add(SetupSection.Provider), mapOf(SetupField.ProviderName to "Mine"), mapOf(SetupField.ProviderHost to SetupRejection.Reason.Characters)))
        assertTrue(flow.page().contains("<p class=\"st bad\">${SetupPage.PROBLEMS_SUMMARY}</p>"))
    }

    @Test
    fun `the edit page shows every saved card with what it can do, Add below the caps, and Send once something changed`() {
        val plain = SetupFlow(SetupMode.Edit, seed).page()
        assertEquals(listOf("Usenet providers · tried in this order", "Indexers", "TMDB"), Regex("<h2>(.*?)</h2>").findAll(plain).map { it.groupValues[1] }.toList())
        assertTrue(plain.contains(SetupPage.EDIT_INTRO))
        assertEquals("Change, add or remove, then send. Nothing is saved until you choose Save on the TV.", SetupPage.EDIT_INTRO)
        fun cardButtons(page: String, slot: SetupSlot) =
            Regex("""<div class="card[^"]*" id="${slot.key}">(.*?)</div>\n</div>""", RegexOption.DOT_MATCHES_ALL).find(page)!!.groupValues[1]
                .let { card -> Regex("<button [^>]*>([^<]*)</button>").findAll(card).map { it.groupValues[1] }.toList() }
        assertEquals(listOf("Edit", "Remove"), cardButtons(plain, p1))
        assertEquals(listOf("Change"), cardButtons(plain, SetupSlot.Tmdb))
        assertTrue(plain.contains("<p class=\"st\">Saved</p>"))
        assertTrue(plain.contains("<p class=\"st\">Saved · switched off</p>"))
        assertTrue(plain.contains("news.example.invalid:563 · TLS · someone · password saved"))
        assertEquals("""type="submit" name="do" value="send" disabled""", buttons(plain).single { it.second == "Send to TV" }.first)
        assertEquals(listOf("Add a provider", "Add an indexer"), buttons(plain).map { it.second }.filter { it.startsWith("Add") })

        val changes = editing().page()
        assertEquals(listOf("Edit", "Undo changes", "Remove"), cardButtons(changes, i1))
        assertEquals(listOf("Keep it"), cardButtons(changes, p2))
        assertTrue(changes.contains("<div class=\"card gone\" id=\"p2\">"))
        assertTrue(changes.contains("<p class=\"st bad\">Will be removed</p>"))
        assertTrue(changes.contains("<p class=\"ct\"><span class=\"tag\">New</span>Mine</p>"))
        assertEquals(listOf("Edit", "Don't add this one"), cardButtons(changes, p3))
        assertTrue(changes.contains("<p class=\"st good\">Changed · ✓ Works</p>"), changes)
        assertFalse("disabled" in buttons(changes).single { it.second == "Send to TV" }.first)
        assertTrue(changes.contains("<p class=\"hint\">1 new · 1 changed · 1 to remove</p>"), changes)
        // The open card is the one being edited, with its saved name as its legend.
        assertTrue(changes.contains("<fieldset id=\"p1\">\n<legend>Main provider</legend>"))

        val capped = SetupFlow(SetupMode.Edit, seed, SetupLimits(providers = 2, indexers = 2)).page()
        assertEquals(emptyList(), buttons(capped).map { it.second }.filter { it.startsWith("Add") })
        // A saved value the page couldn't send back: shown, edited on the TV.
        val odd = SetupFlow(SetupMode.Edit, SetupSeed(listOf(main.copy(host = "u@news.example.invalid")))).page()
        assertEquals(listOf("Remove"), cardButtons(odd, p1))
        assertTrue(odd.contains(SetupPage.READ_ONLY))
    }

    @Test
    fun `step 4 lists what Send would do, with no inputs and no secret`() {
        val page = stepFour().page()
        assertEquals(listOf("Usenet providers", "Indexers", "TMDB"), Regex("<h2>(.*?)</h2>").findAll(page).map { it.groupValues[1] }.toList())
        assertTrue(page.contains("<p class=\"ct\"><span class=\"tag\">New</span>Mine</p>\n<p class=\"cm\">fill.example.invalid:563 · TLS · me2 · password typed</p>\n<p class=\"st good\">✓ Works</p>"), page)
        assertTrue(page.contains("<p class=\"ct\"><span class=\"tag\">New</span>TMDB key</p>"))
        assertTrue(page.contains(SetupPage.CHECK_INTRO))
        val partial = SetupFlow(SetupMode.Wizard, SetupSeed(listOf(main, block))).apply {
            post(SetupAction.Drop(p2))
            post(SetupAction.Next)
            post(SetupAction.Next, *indexer())
            post(SetupAction.Next, SetupField.TmdbKey to "")
        }.page()
        assertTrue(partial.contains("<p class=\"ct\"><span class=\"tag\">Saved</span>Main provider</p>\n<p class=\"cm\">news.example.invalid:563 · TLS · someone · saved password kept</p>"), partial)
        assertTrue(partial.contains("<p class=\"ct\"><span class=\"tag rm\">Removes</span>Old block</p>"))
        assertTrue(partial.contains(SetupPage.NO_INDEXER))
        assertTrue(partial.contains("<p class=\"ct\">No key</p>\n<p class=\"cm\">Skipped. Add one later on the TV or here.</p>"), partial)
        val nothing = SetupFlow(SetupMode.Wizard, SetupSeed(listOf(main))).apply {
            post(SetupAction.Next)
            post(SetupAction.Next, *indexer())
            post(SetupAction.Next, SetupField.TmdbKey to "")
        }.page()
        assertTrue(nothing.contains("<p class=\"hint\"><strong>${SetupPage.NOTHING_TO_SEND}</strong></p>"), nothing)
    }

    /** A key typed over the one saved replaces it: step 4 and the edit page's counts say so, as the TV's review does. */
    @Test
    fun `a TMDB key typed over a saved one reads as a change`() {
        val wizard = SetupFlow(SetupMode.Wizard, SetupSeed(tmdbKey = SavedSecret.Saved)).apply {
            post(SetupAction.Next, *provider())
            post(SetupAction.Next, *indexer())
            tested(SetupAction.Test, SetupField.TmdbKey to "marker-tmdb")
            post(SetupAction.Next, SetupField.TmdbKey to "")
        }.page()
        assertTrue(wizard.contains("<p class=\"ct\"><span class=\"tag\">Changes</span>TMDB key</p>"), wizard)
        val edit = SetupFlow(SetupMode.Edit, seed).apply {
            post(SetupAction.Open(SetupSlot.Tmdb))
            tested(SetupAction.Test, SetupField.TmdbKey to "marker-tmdb")
        }.page()
        assertTrue(edit.contains("<p class=\"hint\">1 changed</p>"), edit)
        // With none saved, it is new.
        assertTrue(stepFour().page().contains("<p class=\"ct\"><span class=\"tag\">New</span>TMDB key</p>"))
    }

    /** A card marked for removal has its password or key deleted with it: nothing on it says one is kept. */
    @Test
    fun `a card to be removed says nothing of its password being kept`() {
        val gone = "<p class=\"cm\">block.example.invalid:563 · TLS · someone</p>"
        val partial = SetupFlow(SetupMode.Wizard, SetupSeed(listOf(main, block))).apply {
            post(SetupAction.Drop(p2))
            post(SetupAction.Next)
            post(SetupAction.Next, *indexer())
            post(SetupAction.Next, SetupField.TmdbKey to "")
        }.page()
        assertTrue(partial.contains("<span class=\"tag rm\">Removes</span>Old block</p>\n$gone\n"), partial)
        val edit = editing().page()
        assertTrue(edit.contains("<p class=\"ct\">Old block</p>\n$gone\n"), edit)
        // A card that stays still says what it keeps.
        assertTrue(partial.contains("news.example.invalid:563 · TLS · someone · saved password kept"))
    }

    /** Where the address is typed, the page says what changing it costs: the login, query or fragment kept on the TV. */
    @Test
    fun `an open card whose address has hidden parts says so under the address`() {
        val home = SetupFlow(SetupMode.Edit, seed).apply { post(SetupAction.Open(SetupSlot(SetupSection.Indexer, 2))) }.page()
        assertTrue(Regex("""<input type="url" id="i.url" [^>]*>\n<p(?: id="[^"]*")? class="fh">${Regex.escape(SetupPage.HIDDEN_PARTS_KEPT)}</p>""").containsMatchIn(home), home)
        assertEquals("Some parts of this address are kept on the TV and not shown. Change it and they are left out.", SetupPage.HIDDEN_PARTS_KEPT)
        val plain = SetupFlow(SetupMode.Edit, seed).apply { post(SetupAction.Open(i1)) }.page()
        assertFalse(plain.contains(SetupPage.HIDDEN_PARTS_KEPT), "an address with nothing hidden")
    }

    /** In a wizard, a key may be saved already (a TV with a provider and the key but no indexer): going on keeps it. */
    @Test
    fun `step 3 with a key saved goes on with Next, which keeps it`() {
        val flow = SetupFlow(SetupMode.Wizard, SetupSeed(tmdbKey = SavedSecret.Saved)).apply {
            post(SetupAction.Next, *provider())
            post(SetupAction.Next, *indexer())
        }
        val page = flow.page()
        assertEquals("Next", buttons(page).single { "value=\"next\"" in it.first }.second)
        assertTrue(page.contains("<p class=\"hint\">${SetupPage.TMDB_KEEP_HINT}</p>"), page)
        assertFalse(page.contains(SetupPage.TMDB_SKIP_HINT))
        assertEquals("Next keeps the saved key. A key you type is tested before you go on.", SetupPage.TMDB_KEEP_HINT)
        // One that can't be read is kept the same way.
        val unreadable = SetupFlow(SetupMode.Wizard, SetupSeed(tmdbKey = SavedSecret.Unreadable)).apply {
            post(SetupAction.Next, *provider())
            post(SetupAction.Next, *indexer())
        }.page()
        assertTrue(unreadable.contains(SetupPage.TMDB_KEEP_HINT))
        // With none saved, Skip, which goes on without one.
        assertTrue(stepThree().page().contains("<p class=\"hint\">${SetupPage.TMDB_SKIP_HINT}</p>"))
    }

    /**
     * A number input under novalidate sends "" for what it can't read (so the default port, silently) and ignores
     * maxlength: as text with a number keyboard, what was typed reaches the TV, which names the field, and the browser
     * stops at the TV's limit.
     */
    @Test
    fun `port and connections are typed as text with a number keyboard, within the TV's limit`() {
        val page = wizard().page()
        for (field in listOf(SetupField.ProviderPort, SetupField.ProviderConnections)) {
            val input = inputs(page).single { it["name"] == field.key }
            assertEquals(listOf(null, "numeric", "${field.maxLength}"), listOf(input["type"], input["inputmode"], input["maxlength"]), field.key)
            assertFalse(listOf("min", "max", "step").any { it in input }, field.key)
        }
        // What it then sends as typed is a problem on its card, not the default.
        val flow = wizard()
        val body = "v=${flow.version}&do=test&p.port=563-&p.conns=8".toByteArray()
        assertEquals(mapOf(SetupField.ProviderPort to SetupRejection.Reason.NotANumber), (SetupForm.parse(body, flow.layout()) as SetupPost).problems)
    }

    /**
     * Moving by controls, a screen reader hears each summary's buttons with their card's name ("Remove: Old block", not
     * five "Remove"s), and a field with what it keeps or why it wasn't kept.
     */
    @Test
    fun `a screen reader hears which card a button is for, and what a field keeps or why it wasn't kept`() {
        fun names(page: String, slot: SetupSlot) =
            Regex("""<div class="card[^"]*" id="${slot.key}">(.*?)</div>\n</div>""", RegexOption.DOT_MATCHES_ALL).find(page)!!.groupValues[1]
                .let { card -> buttons(card).map { (attributes, label) -> label to Regex("aria-label=\"([^\"]*)\"").find(attributes)?.groupValues?.get(1) } }
        val edit = editing().page()
        assertEquals(listOf("Edit" to "Edit: Indexer A", "Undo changes" to "Undo changes: Indexer A", "Remove" to "Remove: Indexer A"), names(edit, i1))
        assertEquals(listOf("Keep it" to "Keep it: Old block"), names(edit, p2))
        assertEquals(listOf("Edit" to "Edit: Mine", "Don't add this one" to "Don't add this one: Mine"), names(edit, p3))
        assertEquals(listOf("Change" to "Change: TMDB key"), names(SetupFlow(SetupMode.Edit, seed).page(), SetupSlot.Tmdb))
        val hostile = SetupFlow(SetupMode.Edit, SetupSeed(listOf(main.copy(name = "A <b>\"name\"</b>")))).page()
        assertTrue(hostile.contains("aria-label=\"Edit: A &lt;b&gt;&quot;name&quot;&lt;/b&gt;\""), hostile)
        // The open card's password: what it keeps, under it.
        val password = inputs(edit).single { it["name"] == "p.pass" }
        assertEquals("p.pass-h", password["aria-describedby"])
        assertTrue(edit.contains("<p id=\"p.pass-h\" class=\"fh\">${SetupPage.SAVED_KEPT}</p>"), edit)
        assertEquals(null, inputs(edit).single { it["name"] == "p.host" }["aria-describedby"])
        // A field the TV couldn't hold: why, at the top of its card.
        val flow = wizard()
        flow.apply(SetupPost(flow.version, SetupAction.Test, mapOf(SetupField.ProviderName to "Mine"), mapOf(SetupField.ProviderHost to SetupRejection.Reason.Characters)))
        val problem = flow.page()
        assertEquals("p.host-p", inputs(problem).single { it["name"] == "p.host" }["aria-describedby"])
        assertTrue(problem.contains("<p id=\"p.host-p\" class=\"result fail\">Host: it has a character it can't hold, so it wasn't kept.</p>"), problem)
    }

    @Test
    fun `a one-shot notice heads the next page only`() {
        val flow = wizard()
        flow.apply(SetupPost(flow.version + 1, SetupAction.Next))
        val page = flow.page()
        assertTrue(page.contains("<p class=\"notice\">${SetupPage.STALE}</p>"), page)
        assertTrue(page.indexOf(SetupPage.STALE) < page.indexOf("<form"))
        assertEquals("This page was out of date, so nothing from it was kept.", SetupPage.STALE)
        assertFalse(flow.page().contains(SetupPage.STALE))
        flow.post(SetupAction.Test, *provider())
        assertTrue(flow.page().contains("<p class=\"notice\">${SetupPage.NOTHING_TO_TEST}</p>"))
        flow.post(SetupAction.Next, *typed)
        assertTrue(flow.page().contains("<p class=\"notice\">To go on, test Mine or don't add it.</p>"))
    }

    /**
     * A refused Next or Send, and a Test with nothing to test, send the phone back to the card: the browser scrolls to it,
     * so the line saying why is on that card, after its legend or title, not at the top of the page out of sight.
     */
    @Test
    fun `a notice about a card is on that card, where the page opens`() {
        fun card(page: String, slot: SetupSlot) =
            Regex("""<(fieldset|div class="card[^"]*") id="${slot.key}">(.*?)</(fieldset|div>\n</div)>""", RegexOption.DOT_MATCHES_ALL).find(page)!!.groupValues[2]
        val waiting = "<p class=\"notice\">To go on, test Mine or don't add it.</p>"
        val next = wizard().apply { post(SetupAction.Next, *typed) }.page()
        assertTrue(card(next, p1).startsWith("\n<legend>Provider 1</legend>\n$waiting"), next)
        assertFalse(next.substringBefore("<fieldset").contains("class=\"notice\""), "the line above the card, off screen")
        val nothing = "<p class=\"notice\">${SetupPage.NOTHING_TO_TEST}</p>"
        val blank = wizard().apply { post(SetupAction.Test, *provider()) }.page()
        assertTrue(card(blank, p1).startsWith("\n<legend>Provider 1</legend>\n$nothing"), blank)
        // On the edit page: the TMDB key's summary, and its open card.
        val summary = SetupFlow(SetupMode.Edit, seed).apply { post(SetupAction.TestCard(SetupSlot.Tmdb)) }.page()
        assertTrue(card(summary, SetupSlot.Tmdb).startsWith("\n<p class=\"ct\">TMDB key</p>\n$nothing"), summary)
        val open = SetupFlow(SetupMode.Edit, seed).apply { post(SetupAction.Open(SetupSlot.Tmdb)); post(SetupAction.Test, SetupField.TmdbKey to "") }.page()
        assertTrue(card(open, SetupSlot.Tmdb).startsWith("\n<legend>TMDB</legend>\n$nothing"), open)
        // Send refused on the edit page: on the card it waits for.
        val send = SetupFlow(SetupMode.Edit, seed).apply { post(SetupAction.Add(SetupSection.Provider)); post(SetupAction.Send, *typed) }.page()
        assertTrue(card(send, p3).startsWith("\n<legend>Provider 3</legend>\n<p class=\"notice\">Send to TV waits until Mine is tested or not added.</p>"), send)
        assertFalse(send.substringBefore("<form").contains("class=\"notice\""))
        // A line about the page as a whole stays at its top.
        val stale = wizard().apply { apply(SetupPost(version + 1, SetupAction.Next)) }.page()
        assertTrue(stale.substringBefore("<form").contains("<p class=\"notice\">${SetupPage.STALE}</p>"))
    }

    @Test
    fun `tests left show from three, and with none the Test buttons go but Enter's stays`() {
        // Typed, not tested: Next refused.
        fun left(n: Int) = SetupFlow(SetupMode.Wizard, SetupSeed(), SetupLimits(tests = n)).apply { post(SetupAction.Next, *typed) }.page()
        assertEquals(listOf(null, "3 tests left with this code.", "1 test left with this code."), listOf(4, 3, 1).map { n -> texts(left(n), "hint").firstOrNull { "left with this code" in it } })
        val none = left(0)
        assertTrue(none.contains("<p class=\"hint\">${SetupPage.NO_TESTS_LEFT}</p>"))
        assertEquals(listOf("enter"), buttons(none).map { Regex("value=\"([^\"]+)\"").find(it.first)!!.groupValues[1] }.filter { it.startsWith("test") || it == "enter" })
        assertTrue(texts(left(4), "hint").contains(SetupPage.TRY_ON_TV))
        assertEquals("Test asks the TV to try it. Nothing is saved until you choose Save on the TV.", SetupPage.TRY_ON_TV)
    }

    @Test
    fun `a page says how long it lasts, and in the code's last minutes how long is left`() {
        for ((name, page) in flowPages) assertTrue(page.contains("<p class=\"hint\">${SetupPage.IDLE_LIFETIME}</p>"), name)
        assertEquals("This page closes after 10 minutes without use.", SetupPage.IDLE_LIFETIME)
        assertTrue(flowPages.getValue("edit, closing").contains("<p class=\"hint\">This code closes in about 4 minutes.</p>"))
        assertTrue(wizard().page(closesIn = 1).contains("<p class=\"hint\">This code closes in about 1 minute.</p>"))
        assertFalse(wizard().page().contains("This code closes in"))
    }

    @Test
    fun `the expired pages point back to the TV and hold no form`() {
        assertTrue(SetupPage.expired.contains("This code closed after 10 minutes without use."))
        assertTrue(SetupPage.closedAtCap.contains("This code closed: a code works for 45 minutes at most."))
        for (page in listOf(SetupPage.expired, SetupPage.closedAtCap)) {
            assertTrue(page.contains("Choose New code on the TV."))
            assertFalse(page.contains("<form"))
        }
        assertTrue(SetupPage.sent.contains("Nothing is saved until you choose Save there. You can close this page."))
    }

    /** Nothing saved can reach a page but through the view, whose cards hold no password or key. */
    @Test
    fun `a page from the flow is made from its view alone`() {
        val render = SetupPage::class.java.declaredMethods.filter { it.name.startsWith("render") && it.parameterTypes.toList() == listOf(SetupView::class.java) }
        assertEquals(1, render.size)
        for (type in listOf(SavedProvider::class.java, SavedIndexer::class.java, SetupSeed::class.java, CardView::class.java, SetupView::class.java)) {
            val names = type.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }
            assertTrue(names.none { n -> listOf("password", "pass", "apikey", "secretvalue", "token").any { n.lowercase() == it } }, "${type.simpleName}: $names")
        }
        assertEquals(
            listOf("ref", "name", "host", "port", "useTls", "username", "connections", "enabled", "login"),
            SavedProvider::class.java.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name },
        )
        assertEquals(
            listOf("ref", "name", "shownUrl", "hiddenParts", "enabled", "key"),
            SavedIndexer::class.java.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name },
        )
        assertEquals(SavedSecret::class.java, SavedIndexer::class.java.getDeclaredField("key").type)
        assertEquals(SavedSecret::class.java, SetupSeed::class.java.getDeclaredField("tmdbKey").type)
        // Printed, a seed names no host, name, username or address.
        for (printed in listOf(main.toString(), home.toString())) {
            for (value in listOf("news.example.invalid", "Main provider", "someone", "192.168.1.10")) assertFalse(printed.contains(value), printed)
        }
    }

    private companion object {
        const val PINNED_STYLE_HASH = "LQ7WBeCw+iTX8bQ4jqWo60YRvL8DYd/c+nkR8pTPIfo="
    }
}
