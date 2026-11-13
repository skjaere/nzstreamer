package io.skjaere.usenet.handoff

import io.skjaere.usenet.handoff.SetupRejection.Reason
import org.junit.jupiter.api.Test
import java.net.URLEncoder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SetupFormTest {
    private fun encode(vararg fields: Pair<String, String>): ByteArray =
        fields.joinToString("&") { (name, value) -> "${URLEncoder.encode(name, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}" }
            .toByteArray(Charsets.US_ASCII)

    private fun rejected(body: ByteArray, field: SetupField?, reason: Reason, layout: SetupLayout) {
        assertEquals(SetupRejection(field, reason), SetupForm.parse(body, layout), String(body, Charsets.ISO_8859_1).take(80))
    }

    /** A page at render 1 with a card of [section] open, offering Test. */
    private fun open(section: SetupSection) = SetupLayout(1, SetupSlot(section, 1), setOf(SetupAction.Test))

    /** A page at render 1 with a debrid service card open whose radio offers [choices]. */
    private fun service(vararg choices: String) = SetupLayout(1, SetupSlot(SetupSection.Service, 1), setOf(SetupAction.Test), choices.toSet())

    /** What [fields] read into on a page with a card of [section] open, the counter and Test added. */
    private fun fields(section: SetupSection, vararg fields: Pair<String, String>): SetupPost =
        assertIs<SetupPost>(SetupForm.parse(encode("v" to "1", "do" to "test", *fields), open(section)), fields.toList().toString().take(80))

    /** What [body] reads into, with Test on a provider's card at render 1. */
    private fun body(body: String) = assertIs<SetupPost>(SetupForm.parse("v=1&do=test&$body".toByteArray(), open(SetupSection.Provider)), body.take(80))

    private fun refused(field: SetupField?, reason: Reason, vararg fields: Pair<String, String>) {
        val section = fields.firstNotNullOfOrNull { (name, _) -> SetupField.entries.firstOrNull { it.key == name }?.section } ?: SetupSection.Provider
        rejected(encode("v" to "1", "do" to "test", *fields), field, reason, open(section))
    }

    @Test
    fun `blank numbers are left to the defaults, a missing checkbox is off`() {
        assertEquals(mapOf(SetupField.ProviderHost to "h", SetupField.ProviderPort to "", SetupField.ProviderConnections to ""), fields(SetupSection.Provider, "p.host" to "h", "p.port" to "", "p.conns" to " ").fields)
        val provider = ProviderDraft("", "h", null, false, "", "", null)
        assertEquals(119, provider.portOrDefault)
        assertEquals(8, provider.connectionsOrDefault)
        assertEquals(563, provider.copy(useTls = true).portOrDefault)
        assertEquals(1119, provider.copy(port = 1119).portOrDefault)
    }

    @Test
    fun `ranges are the TV's to check, so a number out of them still arrives`() {
        assertEquals(mapOf(SetupField.ProviderPort to "99999", SetupField.ProviderConnections to "0"), fields(SetupSection.Provider, "p.port" to "99999", "p.conns" to "0").fields)
    }

    @Test
    fun `decodes plus as a space, percent escapes strictly, and UTF-8`() {
        assertEquals("My box! é ✓", body("p.name=My+box%21+%C3%A9+%E2%9C%93").fields[SetupField.ProviderName])
        assertEquals("a+b", body("p.host=a%2Bb").fields[SetupField.ProviderHost])
        // Names may be escaped too, as a form never does but a client may.
        assertEquals("h", body("p%2Ehost=h").fields[SetupField.ProviderHost])
        for (bad in listOf("%", "%4", "%zz", "%4g", "a%", "%%41")) {
            rejected("v=1&do=test&p.host=$bad".toByteArray(), SetupField.ProviderHost, Reason.Malformed, open(SetupSection.Provider))
        }
        // Not UTF-8: a lone continuation byte, a cut-short sequence, an overlong slash, a surrogate, a raw high byte.
        for (bad in listOf("%80", "%C3", "%C3%28", "%C0%AF", "%ED%A0%80", "%FF")) {
            rejected("v=1&do=test&p.name=$bad".toByteArray(), SetupField.ProviderName, Reason.Malformed, open(SetupSection.Provider))
        }
        rejected("v=1&do=test&t.key=".toByteArray() + byteArrayOf(0xE9.toByte()), SetupField.TmdbKey, Reason.Malformed, open(SetupSection.Tmdb))
    }

    @Test
    fun `a pair without an equals sign, or a name that can't be decoded, is malformed`() {
        val provider = open(SetupSection.Provider)
        rejected("v=1&do=test&p.host".toByteArray(), null, Reason.Malformed, provider)
        rejected("v=1&do=test&p.host=h&junk".toByteArray(), null, Reason.Malformed, provider)
        rejected("v=1&do=test&p.ho%zzst=h".toByteArray(), null, Reason.Malformed, provider)
        // Empty pairs (a stray "&") are skipped.
        assertEquals("h", body("&p.host=h&&").fields[SetupField.ProviderHost])
    }

    @Test
    fun `more than 64 fields, the button and the counter among them, are refused before any is read`() {
        val provider = open(SetupSection.Provider)
        rejected((1..65).joinToString("&") { "p.zzz$it=1" }.toByteArray(), null, Reason.TooManyFields, provider)
        rejected("&".repeat(70).toByteArray(), null, Reason.TooManyFields, provider)
        val atLimit = "v=1&do=test&p.host=h" + "&".repeat(61)
        assertIs<SetupPost>(SetupForm.parse(atLimit.toByteArray(), provider))
        rejected((atLimit + "&").toByteArray(), null, Reason.TooManyFields, provider)
    }

    @Test
    fun `each field has its length limit`() {
        val limits = mapOf(
            SetupField.ProviderName to 100, SetupField.ProviderHost to 253, SetupField.ProviderUsername to 256,
            SetupField.ProviderPassword to 256, SetupField.IndexerName to 100, SetupField.IndexerUrl to 2048,
            SetupField.IndexerKey to 512, SetupField.TmdbKey to 1024,
        )
        for ((field, limit) in limits) {
            assertEquals(limit, field.maxLength, field.name)
            assertEquals("a".repeat(limit), fields(field.section, field.key to "a".repeat(limit)).fields[field], field.name)
            refused(field, Reason.TooLong, field.key to "a".repeat(limit + 1))
        }
        // Counted as a browser's maxlength counts: UTF-16 units, so one emoji is two.
        assertEquals("😀".repeat(50), fields(SetupSection.Provider, "p.name" to "😀".repeat(50)).fields[SetupField.ProviderName])
        refused(SetupField.ProviderName, Reason.TooLong, "p.name" to "😀".repeat(50) + "a")
    }

    /** Characters that would break a line or let a name, host or address show as something else on the TV. */
    private val spoofing = listOf(
        '\u0000', '\t', '\n', '\r', '\u001b', '\u001f', '\u007f', '\u0085', '\u009f', '\u2028', '\u2029',
        '\u202a', '\u202b', '\u202c', '\u202d', '\u202e', '\u2066', '\u2067', '\u2068', '\u2069',
        '\u200b', '\u200c', '\u200d', '\u200e', '\u200f', '\ufeff',
    )

    @Test
    fun `names, hosts, addresses and usernames can't hold control, bidi or zero-width characters`() {
        val text = listOf(SetupField.ProviderName, SetupField.ProviderHost, SetupField.ProviderUsername, SetupField.IndexerName, SetupField.IndexerUrl)
        for (field in text) {
            for (c in spoofing) {
                for (value in listOf("news${c}example", "news.example$c")) {
                    val post = fields(field.section, field.key to value)
                    assertEquals(mapOf(field to Reason.Characters), post.problems, "$field ${c.code}")
                    assertFalse(field in post.fields)
                }
            }
        }
        // Other scripts are fine in a name.
        assertEquals("Fournisseur é 日本", fields(SetupSection.Provider, "p.name" to "Fournisseur é 日本").fields[SetupField.ProviderName])
    }

    @Test
    fun `a password can't hold only what would end its line, and keeps the rest as typed`() {
        for (c in listOf('\u0000', '\t', '\n', '\r', '\u001f', '\u007f')) {
            assertEquals(mapOf(SetupField.ProviderPassword to Reason.Characters), fields(SetupSection.Provider, "p.pass" to "pass${c}word").problems)
        }
        val typed = "  pä\u200bss\u202eword 😀 "
        assertEquals(typed, fields(SetupSection.Provider, "p.pass" to typed).fields[SetupField.ProviderPassword])
    }

    @Test
    fun `keys are printable ASCII, trimmed`() {
        for (field in listOf(SetupField.IndexerKey, SetupField.TmdbKey)) {
            for (bad in listOf("ab cd", "abé", "ab\tcd", "ab\u200bcd", "ab\u007fcd", "ab\u00a0cd")) {
                assertEquals(mapOf(field to Reason.Characters), fields(field.section, field.key to bad).problems, "$field $bad")
            }
        }
        assertEquals("eyJ.a-b_c~!", fields(SetupSection.Tmdb, "t.key" to "  eyJ.a-b_c~!  ").fields[SetupField.TmdbKey])
        assertEquals("ABC123", fields(SetupSection.Indexer, "i.key" to " ABC123 ").fields[SetupField.IndexerKey])
    }

    @Test
    fun `numbers are digits only`() {
        for (field in listOf(SetupField.ProviderPort, SetupField.ProviderConnections)) {
            for (bad in listOf("abc", "8.5", "-1", "+1", "1e3", "0x10", "1234567890", "8\n", "\u0663")) {
                assertEquals(mapOf(field to Reason.NotANumber), fields(SetupSection.Provider, field.key to bad).problems, "$field $bad")
            }
        }
        assertEquals("123456789", fields(SetupSection.Provider, "p.port" to "123456789").fields[SetupField.ProviderPort])
    }

    @Test
    fun `the checkbox is on only as a browser sends it`() {
        assertEquals("on", fields(SetupSection.Provider, "p.tls" to "on").fields[SetupField.ProviderTls])
        refused(SetupField.ProviderTls, Reason.Malformed, "p.tls" to "yes")
        refused(SetupField.ProviderTls, Reason.Malformed, "p.tls" to "")
    }

    @Test
    fun `nothing secret is printed`() {
        val post = fields(SetupSection.Provider, "p.host" to "news.example.invalid", "p.user" to "someone", "p.pass" to "pa ss")
        assertFalse(listOf("pa ss", "someone", "news.example.invalid").any { post.toString().contains(it) }, post.toString())
        val provider = ProviderDraft("Mine", "news.example.invalid", 563, true, "someone", "pa ss", 8)
        val indexer = IndexerDraft("Mine", "https://indexer.example.invalid/api?apikey=0123abcd", "0123abcd")
        for (printed in listOf(provider.toString(), indexer.toString(), SetupChanges(listOf(SetupChange(null, provider, false)), listOf(SetupChange("r", indexer, false)), "tmdbkey123").toString())) {
            for (secret in listOf("pa ss", "0123abcd", "tmdbkey123", "someone", "news.example.invalid", "indexer.example.invalid", "Mine")) {
                assertFalse(printed.contains(secret), "printed $secret: $printed")
            }
        }
        assertTrue(provider.toString().contains("password=set"))
        assertTrue(SetupTestItem.Tmdb("tmdbkey123").toString().let { !it.contains("tmdbkey123") && it.contains("set") })
        // A refusal names a field and a reason, never a value.
        val refusal = SetupForm.parse(encode("v" to "1", "do" to "test", "p.host" to "h", "p.pass" to "a".repeat(300) + "marker"), open(SetupSection.Provider))
        assertFalse(refusal.toString().contains("marker"), refusal.toString())
    }

    // The page of several cards, one open: what a form from it reads into.

    private val p2 = SetupSlot(SetupSection.Provider, 2)
    private val i1 = SetupSlot(SetupSection.Indexer, 1)

    /** A page at render [version] with the provider card [open] open, offering [actions]. */
    private fun layout(
        version: Int = 3,
        open: SetupSlot? = p2,
        actions: Set<SetupAction> = setOf(SetupAction.Enter, SetupAction.Test, SetupAction.Drop(p2), SetupAction.Open(SetupSlot(SetupSection.Provider, 1)), SetupAction.Next),
    ) = SetupLayout(version, open, actions)

    private fun readPage(body: String, layout: SetupLayout = layout()) = SetupForm.parse(body.toByteArray(), layout)

    private fun readPage(vararg fields: Pair<String, String>, layout: SetupLayout = layout()) = SetupForm.parse(encode(*fields), layout)

    private val openCard = arrayOf(
        "v" to "3", "do" to "test",
        "p.name" to " Mine ", "p.host" to " news.example.invalid ", "p.port" to " 563 ", "p.tls" to "on", "p.user" to " someone ", "p.pass" to " pa ss ",
        "p.conns" to "",
    )

    @Test
    fun `a page's form reads into its open card's fields, the button pressed and the page's render counter`() {
        val post = assertIs<SetupPost>(readPage(*openCard))
        assertEquals(3, post.version)
        assertEquals(SetupAction.Test, post.action)
        assertEquals(
            mapOf(
                SetupField.ProviderName to "Mine", SetupField.ProviderHost to "news.example.invalid", SetupField.ProviderPort to "563",
                SetupField.ProviderTls to "on", SetupField.ProviderUsername to "someone", SetupField.ProviderPassword to " pa ss ",
                SetupField.ProviderConnections to "",
            ),
            post.fields,
        )
        assertEquals(emptyMap(), post.problems)
        // Wherever they come, escaped as a client may escape names.
        assertEquals(SetupAction.Drop(p2), assertIs<SetupPost>(readPage("p.host=h&d%6F=drop%3Ap2&%76=3")).action)
        // No card open: only the counter and the button.
        val none = layout(open = null, actions = setOf(SetupAction.Enter, SetupAction.Send))
        assertEquals(SetupPost(3, SetupAction.Send), readPage("v=3&do=send", none))
        assertEquals(listOf("do", "v"), listOf(SetupForm.INTENT, SetupForm.VERSION))
    }

    @Test
    fun `only the open card's fields are the page's, so another kind's, or any with no card open, are unknown`() {
        rejected(encode("v" to "3", "do" to "test", "i.url" to "https://x.invalid"), null, Reason.UnknownField, layout())
        rejected(encode("v" to "3", "do" to "test", "t.key" to "k"), null, Reason.UnknownField, layout())
        rejected(encode("v" to "3", "do" to "next", "p.host" to "h"), null, Reason.UnknownField, layout(open = null, actions = setOf(SetupAction.Next)))
        rejected(encode("v" to "3", "do" to "test", "p.secret" to "x"), null, Reason.UnknownField, layout())
        rejected(encode("v" to "3", "do" to "test", "p2.host" to "h"), null, Reason.UnknownField, layout())
        rejected(encode("v" to "3", "do" to "test", "p.host" to "h", "p.host" to "h"), SetupField.ProviderHost, Reason.Repeated, layout())
        // An indexer's card has its own.
        val indexer = layout(open = i1, actions = setOf(SetupAction.Test))
        assertEquals(mapOf(SetupField.IndexerUrl to "https://x.invalid"), assertIs<SetupPost>(readPage("v" to "3", "do" to "test", "i.url" to "https://x.invalid", layout = indexer)).fields)
    }

    @Test
    fun `the counter and the button are each needed once, as the page writes them`() {
        for (bad in listOf("do=test", "v=3", "v=3&do=", "v=&do=test", "v=03&do=test", "v=0&do=test", "v=-3&do=test", "v=3a&do=test", "v=1234567890&do=test", "v=3&do=check", "v=3&do=test%3Ap02")) {
            rejected(bad.toByteArray(), null, Reason.Malformed, layout())
        }
        rejected("v=3&do=test&do=test".toByteArray(), null, Reason.Repeated, layout())
        rejected("v=3&v=3&do=test".toByteArray(), null, Reason.Repeated, layout())
        rejected("v=3&do".toByteArray(), null, Reason.Malformed, layout())
        assertEquals(SetupPost(123456789, SetupAction.Enter), readPage("v=123456789&do=enter", layout(version = 123456789, open = null, actions = setOf(SetupAction.Enter))))
    }

    @Test
    fun `a button the page doesn't have is malformed`() {
        for (action in listOf("send", "back", "add:provider", "drop:p1", "drop:p3", "keep:p2", "test-http", "open:p2", "undo:t")) {
            rejected("v=3&do=${URLEncoder.encode(action, "UTF-8")}".toByteArray(), null, Reason.Malformed, layout())
        }
        assertIs<SetupPost>(readPage("v=3&do=open%3Ap1"))
    }

    /** The phone's browser kept an older page (Back, a reload): its form applies nothing, so nothing in it is held against this page. */
    @Test
    fun `a form from an older page reads as one, with nothing of it kept`() {
        val old = readPage("v" to "2", "do" to "add:indexer", "i.url" to "https://x.invalid", "i.key" to "marker")
        assertEquals(SetupPost(2, SetupAction.Add(SetupSection.Indexer)), old)
        assertEquals(SetupPost(9, SetupAction.Send), readPage("v=9&do=send"))
        // What no page has, or no browser sends, is refused all the same.
        rejected(encode("v" to "2", "do" to "test", "p.secret" to "x"), null, Reason.UnknownField, layout())
        rejected(encode("v" to "2", "do" to "stay"), null, Reason.Malformed, layout())
        rejected(encode("v" to "2", "do" to "test", "p.host" to "a".repeat(254)), SetupField.ProviderHost, Reason.TooLong, layout())
    }

    @Test
    fun `characters or numbers the TV can't hold are problems for their fields, not a refusal of the form`() {
        val post = assertIs<SetupPost>(
            readPage("v" to "3", "do" to "next", "p.host" to "news‮.example", "p.port" to "1e3", "p.pass" to "pass\nword", "p.conns" to "8", "p.user" to "someone"),
        )
        assertEquals(mapOf(SetupField.ProviderHost to Reason.Characters, SetupField.ProviderPort to Reason.NotANumber, SetupField.ProviderPassword to Reason.Characters), post.problems)
        assertEquals(mapOf(SetupField.ProviderConnections to "8", SetupField.ProviderUsername to "someone"), post.fields)
        val keys = layout(open = i1, actions = setOf(SetupAction.Test))
        assertEquals(mapOf(SetupField.IndexerKey to Reason.Characters), assertIs<SetupPost>(readPage("v" to "3", "do" to "test", "i.key" to "abé", layout = keys)).problems)
        // Still refused: what a browser never sends, and more than a field holds.
        rejected(encode("v" to "3", "do" to "test", "p.tls" to "yes"), SetupField.ProviderTls, Reason.Malformed, layout())
        rejected("v=3&do=test&p.host=%zz".toByteArray(), SetupField.ProviderHost, Reason.Malformed, layout())
        rejected("v=3&do=test&p.name=%C3%28".toByteArray(), SetupField.ProviderName, Reason.Malformed, layout())
        rejected(encode("v" to "3", "do" to "test", "p.pass" to "p".repeat(257)), SetupField.ProviderPassword, Reason.TooLong, layout())
        rejected(encode("v" to "3", "do" to "test", "p.port" to "1".repeat(17)), SetupField.ProviderPort, Reason.TooLong, layout())
        // A problem names its field, never what was in it.
        assertFalse(readPage("v" to "3", "do" to "test", "p.pass" to "marker\u0001").toString().contains("marker"))
        assertFalse(readPage(*openCard).toString().contains("pa ss"))
    }

    /**
     * A service card's radio sends one of the services its card offers (L209): any other value, one the page never
     * offered this card (a service another card holds), another case, or an empty one, is no form a page sends.
     */
    @Test
    fun `a service's choice is one its card offers, and any other is refused`() {
        val read = SetupForm.parse(encode("v" to "1", "do" to "test", "s.kind" to "torbox", "s.key" to " KEY-0000 "), service("premiumize", "torbox"))
        assertEquals(mapOf(SetupField.ServiceKind to "torbox", SetupField.ServiceKey to "KEY-0000"), assertIs<SetupPost>(read).fields)
        // None chosen: a radio group with nothing checked sends nothing.
        assertEquals(mapOf(SetupField.ServiceKey to "KEY-0000"), assertIs<SetupPost>(SetupForm.parse(encode("v" to "1", "do" to "test", "s.key" to "KEY-0000"), service("torbox"))).fields)
        for (bad in listOf("realdebrid", "TorBox", "torbox ", "", "torbox,premiumize", "x".repeat(33))) {
            rejected(encode("v" to "1", "do" to "test", "s.kind" to bad), SetupField.ServiceKind, if (bad.length > 32) Reason.TooLong else Reason.Malformed, service("premiumize", "torbox"))
        }
        // A service the page didn't offer this card: another card has it.
        rejected(encode("v" to "1", "do" to "test", "s.kind" to "torbox"), SetupField.ServiceKind, Reason.Malformed, service("premiumize"))
    }

    @Test
    fun `a torrent indexer's box is on or missing, and its key is a key`() {
        val read = fields(SetupSection.TorrentIndexer, "r.name" to " Home ", "r.url" to "http://192.168.1.20:9696/3/api", "r.key" to " c41e07b9 ", "r.debrid" to "on")
        assertEquals(
            mapOf(SetupField.TorrentIndexerName to "Home", SetupField.TorrentIndexerUrl to "http://192.168.1.20:9696/3/api", SetupField.TorrentIndexerKey to "c41e07b9", SetupField.TorrentIndexerDebrid to "on"),
            read.fields,
        )
        refused(SetupField.TorrentIndexerDebrid, Reason.Malformed, "r.debrid" to "yes")
        assertEquals(mapOf(SetupField.TorrentIndexerKey to Reason.Characters), fields(SetupSection.TorrentIndexer, "r.key" to "key with spaces").problems)
        // Another card's fields with this one open: not this page's form.
        rejected(encode("v" to "1", "do" to "test", "r.name" to "Home"), null, Reason.UnknownField, service("torbox"))
    }

    @Test
    fun `the debrid drafts print whether a key is there, never the key`() {
        val service = ServiceDraft("torbox", "marker-service-key")
        val torrent = TorrentIndexerDraft("Home", "https://tracker.example.invalid/api?apikey=marker", "marker-torrent-key", useWithService = false)
        val changes = SetupChanges(emptyList(), emptyList(), null, listOf(SetupChange(null, service, false)), listOf(SetupChange("r", torrent, false)))
        for (printed in listOf(service.toString(), torrent.toString(), changes.toString())) assertFalse(printed.contains("marker"), printed)
        assertEquals("ServiceDraft(kind=torbox, key=set)", service.toString())
        assertTrue(torrent.toString().contains("useWithService=false"))
    }

    /**
     * Every page shape's form, at its worst (each field at its limit, every character three bytes of UTF-8 written as
     * %XX%XX%XX, nine bytes a unit, as a page with no validation can send), fits within the request's body limit and
     * the field count, so no form a page makes is refused for its size.
     */
    @Test
    fun `the worst form of every page fits the body limit and the field count`() {
        val widest = listOf("add:provider", "add:indexer", "add:service", "add:torrent-indexer", "test-http", "test:p999", "drop:i999", "drop:r999")
            .maxBy { URLEncoder.encode(it, "UTF-8").length }
        assertEquals("add:torrent-indexer", widest)
        for (section in SetupSection.entries) {
            val fields = SetupField.entries.filter { it.section == section }
            // A box sends "on" or nothing, a radio one of its card's choices (a word at its most); every other field its most.
            fun worst(field: SetupField) = when (field.kind) {
                SetupField.Kind.Checkbox -> "on"
                SetupField.Kind.Choice -> "z".repeat(field.maxLength)
                else -> "€".repeat(field.maxLength)
            }
            val body = (fields.map { it.key to worst(it) } + ("v" to "999999999") + ("do" to widest))
                .joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }.toByteArray()
            val size = fields.sumOf {
                it.key.length + 2 + when (it.kind) {
                    SetupField.Kind.Checkbox -> 2
                    SetupField.Kind.Choice -> it.maxLength
                    else -> it.maxLength * 9
                }
            } + "v=999999999&do=".length + URLEncoder.encode(widest, "UTF-8").length
            assertEquals(size, body.size, section.name)
            assertTrue(body.size <= SetupLimits().body, "${section.name}: ${body.size} bytes")
            assertTrue(fields.size + 2 <= SetupForm.MAX_FIELDS, section.name)
            val choices = fields.filter { it.kind == SetupField.Kind.Choice }.map(::worst).toSet()
            val read = SetupForm.parse(body, SetupLayout(999999999, SetupSlot(section, 1), setOf(SetupAction.of(widest)!!), choices))
            assertFalse(read is SetupRejection, "${section.name}: $read")
        }
        assertEquals(listOf(64, 32 * 1024), listOf(SetupForm.MAX_FIELDS, SetupLimits().body))
    }
}
