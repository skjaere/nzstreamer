package io.skjaere.usenet.logging

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What [redactSecrets] writes over before a line reaches logcat or the in-app log, and what it leaves. */
class RedactionTest {
    @Test
    fun `indexer API keys in a query are hidden, the rest of the address kept`() {
        assertEquals(
            "GET https://indexer.example.org/api?t=search&q=night&apikey=<redacted>&o=json",
            redactSecrets("GET https://indexer.example.org/api?t=search&q=night&apikey=0123456789abcdef&o=json"),
        )
        assertEquals(
            "https://indexer.example.org/api?t=get&id=4d2f9a&apikey=<redacted>",
            redactSecrets("https://indexer.example.org/api?t=get&id=4d2f9a&apikey=0123456789abcdef"),
        )
        assertEquals("https://api.example.org/3/tv/1?api_key=<redacted>&language=en", redactSecrets("https://api.example.org/3/tv/1?api_key=abc123&language=en"))
        assertEquals("?API_KEY=<redacted>&Api-Key=<redacted>", redactSecrets("?API_KEY=abc&Api-Key=def"))
        // A download link that carries the account's id and RSS token instead.
        assertEquals(
            "https://indexer.example.org/getnzb/4d2f9a.nzb&i=<redacted>&r=<redacted>",
            redactSecrets("https://indexer.example.org/getnzb/4d2f9a.nzb&i=42&r=0123456789abcdef"),
        )
        assertEquals("https://example.org/feed?key=<redacted>&token=<redacted>", redactSecrets("https://example.org/feed?key=s3cr3t&token=t0k3n"))
        // A key already written over by the newznab client's own placeholder.
        assertEquals("https://indexer.example.org/api?t=caps&apikey=<redacted>", redactSecrets("https://indexer.example.org/api?t=caps&apikey={apikey}"))
    }

    /**
     * What torrents carry (L186): a tracker's passkey or authkey in its links, a device code, and a magnet, hidden whole with
     * its name and trackers. TorBox's key in its link request (token=) and a tracker's torrent_pass were hidden already.
     */
    @Test
    fun `a tracker's passkey or authkey, a device code and a whole magnet are hidden`() {
        assertEquals("https://tracker.example.org/announce?passkey=<redacted>", redactSecrets("https://tracker.example.org/announce?passkey=0123456789abcdef0123456789abcdef"))
        assertEquals("https://tracker.example.org/rss?authkey=<redacted>&type=tv", redactSecrets("https://tracker.example.org/rss?authkey=f00dfeedf00dfeed&type=tv"))
        assertEquals("Download(passkey=<redacted>, id=7)", redactSecrets("Download(passkey=abc123, id=7)"))
        assertEquals("""{"device_code":"<redacted>","interval":5}""", redactSecrets("""{"device_code":"GA0d-1234-ZZ","interval":5}"""))
        assertEquals("polling with device_code=<redacted>", redactSecrets("polling with device_code=GA0d1234"))
        assertEquals(
            "added <redacted> to the list",
            redactSecrets("added magnet:?xt=urn:btih:2f5a3c9e1b7d4f6a8c0e2b4d6f8a1c3e5b7d9f0a&dn=Harbor.Lights.S01E02.1080p&tr=udp%3A%2F%2Ftracker.example.org%3A6969%2Fannounce to the list"),
        )
        assertEquals("""{"magnet":"<redacted>"}""", redactSecrets("""{"magnet":"magnet:?xt=urn:btih:2f5a3c9e1b7d4f6a8c0e2b4d6f8a1c3e5b7d9f0a&tr=http://tracker.example.org/a/0123abcd/announce"}"""))
        assertEquals("<redacted>", redactSecrets("MAGNET:?xt=urn:btih:2F5A3C9E1B7D4F6A8C0E2B4D6F8A1C3E5B7D9F0A"))
        // Hidden already: the key TorBox's link request takes, and a tracker's pass.
        assertEquals(
            "GET https://api.torbox.app/v1/api/torrents/requestdl?token=<redacted>&torrent_id=42&file_id=1&redirect=false",
            redactSecrets("GET https://api.torbox.app/v1/api/torrents/requestdl?token=0f1e2d3c-4b5a-6978&torrent_id=42&file_id=1&redirect=false"),
        )
        assertEquals("https://tracker.example.org/dl/7?torrent_pass=<redacted>", redactSecrets("https://tracker.example.org/dl/7?torrent_pass=abad1dea"))
        // A word that only starts like one is left: "magnet" alone, and a passkeys line's words.
        assertEquals("the magnet link was dropped; passkeys stay on the device", redactSecrets("the magnet link was dropped; passkeys stay on the device"))
    }

    @Test
    fun `authorization values and bearer tokens are hidden`() {
        assertEquals("Authorization: <redacted>", redactSecrets("Authorization: Bearer abc.def.ghi"))
        assertEquals("proxy-authorization=<redacted>, next", redactSecrets("proxy-authorization=Basic dXNlcjpwYXNz, next"))
        assertEquals("sent Bearer <redacted> to the server", redactSecrets("sent Bearer abcDEF123-_.~+/= to the server"))
        assertEquals("token <redacted> rejected", redactSecrets("token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjMifQ.c2lnbmF0dXJl rejected"))
        assertEquals("""{"apiKey":"<redacted>","name":"Main"}""", redactSecrets("""{"apiKey":"abc 123, x","name":"Main"}"""))
        assertEquals("""{"password" : "<redacted>"}""", redactSecrets("""{"password" : "p\"w"}"""))
    }

    @Test
    fun `NNTP login arguments are hidden, a refused login's answer kept`() {
        assertEquals("> AUTHINFO USER <redacted>", redactSecrets("> AUTHINFO USER alice"))
        assertEquals("> AUTHINFO PASS <redacted>\nnext line", redactSecrets("> AUTHINFO PASS hunter 2\nnext line"))
        assertEquals("authinfo pass <redacted>", redactSecrets("authinfo pass hunter2"))
        assertEquals("AUTHINFO SASL <redacted>", redactSecrets("AUTHINFO SASL PLAIN AGFsaWNlAGh1bnRlcjI="))
        assertEquals("AUTHINFO PASS failed: 481 Authentication rejected", redactSecrets("AUTHINFO PASS failed: 481 Authentication rejected"))
        assertEquals("AUTHINFO USER failed: 502 Too many connections", redactSecrets("AUTHINFO USER failed: 502 Too many connections"))
    }

    @Test
    fun `the login part of an address and named secrets in a toString are hidden`() {
        assertEquals("nntps://<redacted>@news.example.com:563", redactSecrets("nntps://alice:hunter2@news.example.com:563"))
        assertEquals("see https://<redacted>@indexer.example.org/api", redactSecrets("see https://alice@indexer.example.org/api"))
        assertEquals(
            "ProviderConfig(id=p1, name=Main, host=news.example.com, port=563, username=<redacted>, password=<redacted>, useTls=true)",
            redactSecrets("ProviderConfig(id=p1, name=Main, host=news.example.com, port=563, username=alice, password=hunter2, useTls=true)"),
        )
        assertEquals("NntpConfig(user=<redacted>, pass=<redacted>)", redactSecrets("NntpConfig(user=alice, pass=hunter2)"))
        assertEquals("secret=<redacted> passwd=<redacted> pwd=<redacted>", redactSecrets("secret=a passwd=b pwd=c"))
    }

    /**
     * A data class's toString prints its values as they are: a password with a space or a comma in
     * it was hidden only to there ("password=<redacted> horse battery"). A field's value runs to the
     * next field or the closing bracket.
     */
    @Test
    fun `a toString's secrets are hidden whole, spaces and commas in them included`() {
        assertEquals(
            "NntpConfig(host=news.example.com, port=563, username=<redacted>, password=<redacted>, useTls=true)",
            redactSecrets("NntpConfig(host=news.example.com, port=563, username=alice smith, password=correct horse, battery, useTls=true)"),
        )
        assertEquals("Login(user=<redacted>, pass=<redacted>) failed", redactSecrets("Login(user=alice, pass=p;w #2) failed"))
        assertEquals("Config(user=<redacted>) loaded, retries=3", redactSecrets("Config(user=alice b) loaded, retries=3"))
        assertEquals(
            "Outer(inner=Inner(token=<redacted>), next=Other(secret=<redacted>))",
            redactSecrets("Outer(inner=Inner(token=a b)c), next=Other(secret=x, y))"),
        )
        assertEquals("{user=<redacted>, password=<redacted>}", redactSecrets("{user=alice smith, password=a, b}"))
    }

    @Test
    fun `a quoted value is hidden whole`() {
        assertEquals("""password="<redacted>" kept""", redactSecrets("""password="correct horse" kept"""))
        assertEquals("password='<redacted>', next", redactSecrets("password='a b', next"))
        assertEquals("""token="<redacted>" kept""", redactSecrets("""token="a \"b\" c" kept"""))
        assertEquals("""Login(user="<redacted>", pass="<redacted>")""", redactSecrets("""Login(user="alice, b", pass="x) y")"""))
        // Never closed on its line: to the end of it.
        assertEquals("password=\"<redacted>\"\nnext", redactSecrets("password=\"correct horse\nnext"))
    }

    /** A link nested in another's query is percent-encoded, and one in XML or HTML has its & written as &amp;. */
    @Test
    fun `keys in an encoded link are hidden`() {
        assertEquals(
            "url=https%3A%2F%2Findexer.example.org%2Fapi%3Ft%3Dget%26id%3D1%26apikey%3D<redacted>%26o%3Djson",
            redactSecrets("url=https%3A%2F%2Findexer.example.org%2Fapi%3Ft%3Dget%26id%3D1%26apikey%3D0123abcd%26o%3Djson"),
        )
        assertEquals("next=%2Fapi%3fapikey%3d<redacted>", redactSecrets("next=%2Fapi%3fapikey%3d0123abcd"))
        assertEquals(
            "https%3A%2F%2Fexample.org%2Fgetnzb%3Fid%3D1%26r%3D<redacted>%26i%3D<redacted>",
            redactSecrets("https%3A%2F%2Fexample.org%2Fgetnzb%3Fid%3D1%26r%3DKEY%26i%3D42"),
        )
        assertEquals(
            "https://indexer.example.org/getnzb?id=4d2f9a&amp;r=<redacted>&amp;i=<redacted>",
            redactSecrets("https://indexer.example.org/getnzb?id=4d2f9a&amp;r=0123456789abcdef&amp;i=42"),
        )
    }

    @Test
    fun `a secret's name after a prefix is hidden`() {
        assertEquals("jackett_apikey=<redacted> sonarr_api_key=<redacted>", redactSecrets("jackett_apikey=abc sonarr_api_key=def"))
    }

    /**
     * A host typed as user:password@host (the Host field took what it was given) reached the logs as
     * it was: the pool's name, a failed connect's warning and ProviderConfig's toString print it
     * without a scheme.
     */
    @Test
    fun `the login part of a host without a scheme is hidden`() {
        assertEquals(
            "NNTP pool[0] initialized: <redacted>@news.example.com:563 maxConnections=8",
            redactSecrets("NNTP pool[0] initialized: alice:hunter2@news.example.com:563 maxConnections=8"),
        )
        assertEquals(
            "Failed to connect to <redacted>@news.example.com:563: Unable to resolve host",
            redactSecrets("Failed to connect to alice:p@ss:w0rd@news.example.com:563: Unable to resolve host"),
        )
        assertEquals(
            "ProviderConfig(id=p1, name=Main, host=<redacted>@news.example.com:563, tls=true)",
            redactSecrets("ProviderConfig(id=p1, name=Main, host=alice:hunter2@news.example.com:563, tls=true)"),
        )
        assertEquals(
            "pool[<redacted>@news.example.com:563], host='<redacted>@news.example.com'",
            redactSecrets("pool[alice:pw@news.example.com:563], host='alice:pw@news.example.com'"),
        )
        assertEquals("<redacted>@news.example.com:563\n\tat x", redactSecrets("alice:hunter2@news.example.com:563\n\tat x"))
    }

    @Test
    fun `the login of an address with an @ in its password is hidden whole`() {
        assertEquals("nntps://<redacted>@news.example.com:563/x@y", redactSecrets("nntps://alice:p@ss@news.example.com:563/x@y"))
    }

    @Test
    fun `lines without secrets are left as they are`() {
        listOf(
            "play_pipeline: candidate open 412 ms",
            "Segment <part1of12.Xy3@news.example.com> not found on pool[0] (news.example.com:563), trying pool[1]",
            "conn=3 drain: success after 4096 bytes",
            "Stream startup trace [file] (items=5, concurrency=4, readAhead=12)",
            "Archive is password-protected; pwcheck=present, hasPasswordCheck=true, passwordIncorrect=false",
            "find Show S01E02: 3 playable, 1 hidden; t=tvsearch&season=1&ep=2",
            "Couldn't save the password: disk full",
            "Basic playback works",
            "user@example.com wrote",
            "Fetching <part1of12:Xy3@news.example.com> from pool[0]: job=StandaloneCoroutine{Active}@1a2b3c, state: idle@pool",
            "",
        ).forEach { line -> assertEquals(line, redactSecrets(line)) }
    }

    @Test
    fun `a redacted line stays as it is when redacted again`() {
        listOf(
            "GET https://alice:pw@indexer.example.org/api?apikey=abc&i=1 Authorization: Bearer x AUTHINFO PASS y",
            "Config(user=alice smith, password=a, b) next=%3Fapikey%3Dk&amp;r=t password=\"q w\" pwd='x y'",
            "pool[alice:p@ss@news.example.com:563] host=alice:pw@news.example.com nntps://alice:p@ss@news.example.com",
        ).forEach { line ->
            val once = redactSecrets(line)
            assertEquals(once, redactSecrets(once))
        }
    }

    @Test
    fun `every secret in a multi-line stack trace is hidden`() {
        val trace = """
            |io.ktor.client.plugins.ClientRequestException: Client request(GET https://indexer.example.org/api?t=caps&apikey=0123abcd) invalid: 401
            |	at io.ktor.client.plugins.DefaultResponseValidationKt.validate(DefaultResponseValidation.kt:54)
            |Caused by: java.io.IOException: AUTHINFO PASS hunter2
            |	at io.skjaere.nntp.NntpConnection.performAuth(NntpConnection.kt:381)
        """.trimMargin()
        val redacted = redactSecrets(trace)
        listOf("0123abcd", "hunter2").forEach { secret -> assertFalse(secret in redacted, "$secret left in:\n$redacted") }
        assertEquals(trace.lines().size, redacted.lines().size)
        assertTrue("NntpConnection.kt:381" in redacted)
    }
}
