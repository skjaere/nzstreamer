package app.nzstreamer.plugin.sportarr.ui

import app.nzstreamer.plugin.sportarr.source.Connection
import app.nzstreamer.plugin.sportarr.source.MapSecrets
import app.nzstreamer.plugin.sportarr.source.RecordingHttp
import app.nzstreamer.plugin.sportarr.source.SportarrClient
import app.nzstreamer.plugin.sportarr.source.answer
import app.nzstreamer.plugin.sportarr.source.loopbackSafety
import app.nzstreamer.plugin.sportarr.source.noAnswer
import io.skjaere.usenet.plugin.AddressSafety
import io.skjaere.usenet.plugin.HttpAnswer
import io.skjaere.usenet.plugin.PluginHttpException.Kind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The setup form as a model (L640), frame by frame as the phone's and the TV's setup designs draw it: the help line under
 * the address by the host's judgement of it; the key saved, changed and typed; Test's answers, each its line, and where a
 * TV's focus goes after one; Test and Save waiting for an address and a key; Save without a Test, and with the warning
 * shown. A counting fake of the host's HTTP holds the rule that nothing reaches Sportarr before Test or Save, and Save
 * itself sends nothing: the list's read after it is the refresh's, which [saves] records.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupModelTest {
    /** What each Save told the refresh: whether it changed the address or the key. */
    private val saves = mutableListOf<Boolean>()

    /** Sportarr as Test meets it, counting every request. */
    private fun sportarr(
        status: suspend () -> HttpAnswer = { answer(200, status("4.1.12")) },
        leagues: suspend () -> HttpAnswer = { answer(200, "[]") },
    ) = RecordingHttp { request ->
        when {
            request.url.endsWith("/api/system/status") -> status()
            request.url.endsWith("/api/leagues") -> leagues()
            else -> answer(404)
        }
    }

    private fun model(
        http: RecordingHttp = sportarr(),
        secrets: MapSecrets = MapSecrets(),
        tv: Boolean = false,
        safety: (String) -> AddressSafety = ::loopbackSafety,
        restored: SetupModel.Restored? = null,
    ) = SetupModel(Connection(secrets), http, safety, tv, { changed -> saves += changed }, restored, EmptyCoroutineContext)

    /** The form opened and filled in, as frame 2 has it. */
    private suspend fun filled(model: SetupModel, address: String = BASE, key: String = KEY): SetupModel {
        model.open()
        model.address(address)
        model.key(key)
        return model
    }

    @Test
    fun `first open - an empty form, the address's help, Test and Save waiting, nothing sent`() = runTest {
        val http = sportarr()
        val secrets = MapSecrets()
        val model = model(http, secrets)
        model.open()
        val form = model.form.value

        assertTrue(form.loaded)
        assertEquals("", form.address)
        assertEquals(Words.Setup.ADDRESS_HELP, form.help)
        assertFalse(form.helpWarns)
        assertEquals("", form.key)
        assertFalse(form.showsSavedKey, "no key saved yet")
        assertNull(form.keyPlaceholder)
        assertFalse(form.canTest, "Test waits for an address and a key")
        assertFalse(form.canSave, "Save waits for an address and a key")
        assertEquals(Words.Setup.TEST, form.testLabel)
        assertNull(form.line)
        assertEquals(0, http.requests.size, "nothing sent as the form opens")
        assertEquals(0, secrets.puts, "nothing stored as the form opens")
    }

    @Test
    fun `Test running - Testing, with Save ready already, and a second press sends nothing more`() = runTest {
        val status = CompletableDeferred<HttpAnswer>()
        val http = sportarr(status = { status.await() })
        val model = filled(model(http))

        val test = launch { model.test() }
        runCurrent()
        val form = model.form.value
        assertTrue(form.testing)
        assertEquals(Words.Setup.TESTING, form.testLabel)
        assertTrue(form.canTest, "Test stays enabled while it runs, so a TV's focus stays on it")
        assertTrue(form.canSave, "Save is ready already")
        assertNull(form.line, "no answer yet")

        model.test()
        assertEquals(1, http.requests.size, "a press while Test runs asks nothing more")

        status.complete(answer(200, status("4.1.12")))
        test.join()
        assertFalse(model.form.value.testing)
        assertEquals(Words.Setup.TEST, model.form.value.testLabel)
    }

    @Test
    fun `it works - connected with the version as Sportarr sent it, the key in its header alone, nothing stored`() = runTest {
        val http = sportarr()
        val secrets = MapSecrets()
        val model = filled(model(http, secrets))

        model.test()

        assertEquals(AnswerLine(true, "Connected · Sportarr 4.1.12"), model.form.value.line)
        assertEquals(listOf("$BASE/api/system/status", "$BASE/api/leagues"), http.requests.map { it.url })
        assertTrue(http.requests.all { it.headers == mapOf(SportarrClient.API_KEY_HEADER to KEY) }, "the key in X-Api-Key on each request")
        assertTrue(http.requests.none { KEY in it.url }, "the key never in an address")
        assertEquals(0, secrets.puts, "Test stores nothing")
        assertEquals(emptyList(), saves, "Test reads no list")
    }

    @Test
    fun `couldn't reach - the address's host and port as typed, this phone or this TV`() = runTest {
        val unreachable = { sportarr(status = { noAnswer(Kind.Unreachable) }) }
        val phone = filled(model(unreachable()))
        phone.test()
        assertEquals(
            AnswerLine(false, "Couldn't reach $HOST_PORT. Check that Sportarr is running and that this phone is on its network."),
            phone.form.value.line,
        )
        assertTrue(phone.form.value.canSave, "Save still saves, for setting up away from home")

        val tv = filled(model(unreachable(), tv = true))
        tv.test()
        assertEquals(
            AnswerLine(false, "Couldn't reach $HOST_PORT. Check that Sportarr is running and that this TV is on its network."),
            tv.form.value.line,
        )
    }

    @Test
    fun `opened again - the address as saved, the key Saved with Change, Test and Save ready`() = runTest {
        val http = sportarr()
        val model = model(http, MapSecrets(mapOf("url" to BASE, "key" to KEY)))
        model.open()
        val form = model.form.value

        assertEquals(BASE, form.address)
        assertEquals(Words.Setup.LOCAL_HTTP, form.help)
        assertTrue(form.showsSavedKey, "the saved key shows as Saved, never itself")
        assertEquals("", form.key, "the saved key never reaches the form")
        assertTrue(form.canTest)
        assertTrue(form.canSave)
        assertEquals(0, http.requests.size, "nothing sent as the page opens")
    }

    @Test
    fun `after Change - the key field empties for a new key, Test and Save wait for one, the focus goes into it`() = runTest {
        val http = sportarr()
        val secrets = MapSecrets(mapOf("url" to BASE, "key" to KEY))
        val model = model(http, secrets, tv = true)
        model.open()

        model.change()
        val changed = model.form.value
        assertFalse(changed.showsSavedKey)
        assertEquals("", changed.key)
        assertEquals(Words.Setup.PASTE_NEW_KEY, changed.keyPlaceholder)
        assertFalse(changed.canTest, "Test waits for the new key")
        assertFalse(changed.canSave, "Save waits for the new key")
        assertEquals(SetupFocus.Key, changed.move?.to, "Change opens the key's field, its keyboard up")
        assertEquals(0, http.requests.size)

        model.key(NEW_KEY)
        assertTrue(model.form.value.canTest)
        model.test()
        assertTrue(http.requests.all { it.headers[SportarrClient.API_KEY_HEADER] == NEW_KEY }, "Test sends the new key")
        assertTrue(model.save())
        assertEquals(NEW_KEY, secrets.stored.value["key"])
        assertEquals(listOf(true), saves)
    }

    @Test
    fun `the notes' answers, each its line, and the focus kept on Test`() = runTest {
        val cases = listOf<Pair<RecordingHttp, AnswerLine>>(
            sportarr(status = { answer(401) }) to AnswerLine(false, Words.Setup.REFUSED),
            sportarr(leagues = { answer(403) }) to AnswerLine(false, Words.Setup.REFUSED),
            sportarr(status = { answer(200, status("4.0.998")) }) to
                AnswerLine(false, "This Sportarr is 4.0.998. Sports needs 4.1.1 or newer: update Sportarr, then Test again."),
            sportarr(status = { answer(200, "<html><body>A router's page</body></html>") }) to
                AnswerLine(false, "This address answers, but it isn't Sportarr."),
            sportarr(status = { answer(404) }) to AnswerLine(false, "This address answers, but it isn't Sportarr."),
            sportarr(status = { answer(500) }) to AnswerLine(false, "Sportarr answered with an error (500). Try again in a moment."),
        )
        cases.forEachIndexed { index, (http, line) ->
            val model = filled(model(http, tv = true))
            model.test()
            assertEquals(line, model.form.value.line, "case $index")
            assertEquals(SetupFocus.Test, model.form.value.move?.to, "case $index: a TV's focus stays on Test")
            assertTrue(model.form.value.canSave, "case $index: Save still saves")
        }
    }

    @Test
    fun `a TV's focus after each answer - Save once Connected, else Test, where Up goes back to the key`() = runTest {
        val connected = filled(model(sportarr(), tv = true))
        connected.test()
        assertEquals(SetupFocus.Save, connected.form.value.move?.to)

        val refused = filled(model(sportarr(status = { answer(401) }), tv = true))
        refused.test()
        assertEquals(SetupFocus.Test, refused.form.value.move?.to)

        val unread = filled(model(sportarr(), tv = true), address = "127.0.0.1:1867")
        unread.test()
        assertEquals(SetupFocus.Test, unread.form.value.move?.to)

        // Each move a new one, so the same place asked twice moves the focus twice.
        val first = refused.form.value.move
        refused.test()
        assertTrue(first != refused.form.value.move, "a second answer is a second move")
    }

    @Test
    fun `Save without a Test stores the address and the key, asks for the list, and sends nothing itself`() = runTest {
        val http = sportarr()
        val secrets = MapSecrets()
        val model = filled(model(http, secrets), address = " $BASE/ ", key = " $KEY\n")

        assertTrue(model.save())

        assertEquals("$BASE/", secrets.stored.value["url"], "the address trimmed")
        assertEquals(KEY, secrets.stored.value["key"], "the key trimmed: a pasted line break can't ride in a header")
        assertEquals(listOf(true), saves, "the list is read at once")
        assertEquals(0, http.requests.size, "Save sends nothing itself")
    }

    @Test
    fun `Save with the public-http warning shown still saves`() = runTest {
        val secrets = MapSecrets()
        // The host judges the address public (as it would one over the internet); typed here as loopback, never sent.
        val model = filled(model(secrets = secrets, safety = { AddressSafety.PublicHttp }))

        assertEquals(Words.Setup.PUBLIC_HTTP, model.form.value.help)
        assertTrue(model.form.value.helpWarns)
        assertTrue(model.form.value.canSave)
        assertTrue(model.save())
        assertEquals(BASE, secrets.stored.value["url"])
        assertEquals(KEY, secrets.stored.value["key"])
        assertEquals(listOf(true), saves)
    }

    @Test
    fun `Test and Save wait for an address and a key`() = runTest {
        val cases = listOf("" to KEY, BASE to "", "   " to KEY, BASE to "  ")
        cases.forEach { (address, key) ->
            val http = sportarr()
            val secrets = MapSecrets()
            val model = filled(model(http, secrets), address = address, key = key)
            assertFalse(model.form.value.canTest, "Test with \"$address\" and \"$key\"")
            assertFalse(model.form.value.canSave, "Save with \"$address\" and \"$key\"")

            model.test()
            assertFalse(model.save())
            assertEquals(0, http.requests.size, "nothing sent with \"$address\" and \"$key\"")
            assertEquals(0, secrets.puts, "nothing stored with \"$address\" and \"$key\"")
            assertNull(model.form.value.line)
        }
        assertEquals(emptyList(), saves)
    }

    @Test
    fun `the address's help line follows the host's judgement of it`() = runTest {
        val judged = mapOf(
            "https://127.0.0.1:1867" to AddressSafety.Encrypted,
            "http://127.0.0.1:1867" to AddressSafety.LocalHttp,
            "http://localhost:1867" to AddressSafety.PublicHttp,
        )
        val model = model(safety = { judged[it.trim()] ?: AddressSafety.Invalid })
        model.open()
        val cases = listOf(
            "" to (Words.Setup.ADDRESS_HELP to false),
            "https://127.0.0.1:1867" to (Words.Setup.ADDRESS_HELP to false),
            "http://127.0.0.1:1867" to (Words.Setup.LOCAL_HTTP to false),
            " http://127.0.0.1:1867 " to (Words.Setup.LOCAL_HTTP to false),
            "http://localhost:1867" to (Words.Setup.PUBLIC_HTTP to true),
            "http://" to (Words.Setup.ADDRESS_HELP to false),
            "not an address" to (Words.Setup.ADDRESS_HELP to false),
        )
        cases.forEach { (typed, help) ->
            model.address(typed)
            assertEquals(help, model.form.value.help to model.form.value.helpWarns, "help for \"$typed\"")
        }
    }

    @Test
    fun `an address Sports can't read refuses Test and Save with its line, sending and storing nothing`() = runTest {
        listOf("127.0.0.1:1867", "ftp://127.0.0.1:1867", "$BASE/?page=1", "$BASE/#top").forEach { typed ->
            val http = sportarr()
            val secrets = MapSecrets()
            val model = filled(model(http, secrets), address = typed)
            assertTrue(model.form.value.canTest, "\"$typed\": Test is pressed, and says why it can't")

            model.test()
            assertEquals(AnswerLine(false, Words.Setup.INVALID_ADDRESS), model.form.value.line, "Test with \"$typed\"")
            model.address(typed)
            assertFalse(model.save(), "Save with \"$typed\"")
            assertEquals(AnswerLine(false, Words.Setup.INVALID_ADDRESS), model.form.value.line, "Save with \"$typed\"")
            assertEquals(0, http.requests.size, "nothing sent with \"$typed\"")
            assertEquals(0, secrets.puts, "nothing stored with \"$typed\"")
        }
        assertEquals(emptyList(), saves)
    }

    @Test
    fun `the line for an address Sports can't read says what to change, a missing http or what follows a question mark or hash`() = runTest {
        // An address copied from a browser's bar starts with http:// already, and is what the browser showed: the line can't
        // send the user back there, it has to name what Sports refuses in it.
        val model = filled(model(), address = "$BASE/?page=1")
        model.test()
        val line = model.form.value.line?.text.orEmpty()
        assertTrue("http://" in line && "https://" in line, "names the start it needs: $line")
        assertTrue("?" in line && "#" in line, "names what to leave out: $line")
        assertFalse("browser" in line, "doesn't send the user back to the browser's bar: $line")
    }

    @Test
    fun `the saved key is the one Test sends and Save keeps, and a Save that changes nothing reads nothing`() = runTest {
        val http = sportarr()
        val secrets = MapSecrets(mapOf("url" to BASE, "key" to KEY))
        val model = model(http, secrets)
        model.open()

        model.test()
        assertEquals(AnswerLine(true, "Connected · Sportarr 4.1.12"), model.form.value.line)
        assertTrue(http.requests.all { it.headers[SportarrClient.API_KEY_HEADER] == KEY })

        assertTrue(model.save())
        assertEquals(listOf(false), saves, "nothing changed: no read")
        assertEquals(0, secrets.puts)

        model.address("http://127.0.0.1:1868")
        assertTrue(model.save())
        assertEquals(listOf(false, true), saves)
        assertEquals("http://127.0.0.1:1868", secrets.stored.value["url"])
        assertEquals(KEY, secrets.stored.value["key"], "the saved key kept")
    }

    @Test
    fun `a key pasted with a line break is tested as Save keeps it, trimmed`() = runTest {
        // As the host's client does: a header value holding a control character is refused before anything is sent, and
        // the plugin reads that as no answer.
        val http = RecordingHttp { request ->
            if (request.headers.values.any { value -> value.any { it < ' ' } }) noAnswer(Kind.Unreachable)
            when {
                request.url.endsWith("/api/system/status") -> answer(200, status("4.1.12"))
                request.url.endsWith("/api/leagues") -> answer(200, "[]")
                else -> answer(404)
            }
        }
        val secrets = MapSecrets()
        val model = filled(model(http, secrets), key = " $KEY\r\n")

        model.test()
        assertEquals(AnswerLine(true, "Connected · Sportarr 4.1.12"), model.form.value.line, "Test with the pasted key")
        assertTrue(http.requests.all { it.headers[SportarrClient.API_KEY_HEADER] == KEY }, "the key sent trimmed")
        assertTrue(model.save())
        assertEquals(KEY, secrets.stored.value["key"], "the key Save keeps is the one Test sent")
    }

    @Test
    fun `an edit clears the answer, and an answer to what was edited away isn't shown`() = runTest {
        val model = filled(model())
        model.test()
        assertTrue(model.form.value.line != null)
        model.key("$KEY-2")
        assertNull(model.form.value.line, "the answer was for the key before")

        val status = CompletableDeferred<HttpAnswer>()
        val slow = filled(model(sportarr(status = { status.await() }), tv = true))
        val test = launch { slow.test() }
        runCurrent()
        slow.address("http://127.0.0.1:1868")
        status.complete(answer(401))
        test.join()
        assertFalse(slow.form.value.testing)
        assertNull(slow.form.value.line, "the refused key was for the address before")
        assertNull(slow.form.value.move, "and moves no focus")
    }

    @Test
    fun `nothing is sent while the form is opened, typed in and changed`() = runTest {
        val http = sportarr()
        val model = model(http, MapSecrets(mapOf("url" to BASE, "key" to KEY)), tv = true)
        model.open()
        model.address("http://127.0.0.1:1868")
        model.change()
        model.key(NEW_KEY)
        model.address(BASE)
        assertEquals(0, http.requests.size)
        assertEquals(emptyList(), saves)
    }

    @Test
    fun `the address typed and Change survive the page's recreation, the key never`() = runTest {
        val first = model(secrets = MapSecrets(mapOf("url" to BASE, "key" to KEY)))
        first.open()
        first.address("http://127.0.0.1:1868")
        first.change()
        first.key(NEW_KEY)
        val kept = first.kept()
        assertEquals(SetupModel.Restored("http://127.0.0.1:1868", changing = true), kept)
        assertTrue(SetupModel.Restored::class.java.declaredFields.none { it.type == String::class.java && it.name.contains("key", ignoreCase = true) })

        val again = model(secrets = MapSecrets(mapOf("url" to BASE, "key" to KEY)), restored = kept)
        again.open()
        assertEquals("http://127.0.0.1:1868", again.form.value.address, "the address as typed, not as saved")
        assertEquals(Words.Setup.PASTE_NEW_KEY, again.form.value.keyPlaceholder, "still after Change")
        assertEquals("", again.form.value.key, "the key typed is gone")
    }

    private companion object {
        const val BASE = "http://127.0.0.1:1867"
        const val HOST_PORT = "127.0.0.1:1867"
        const val KEY = "kestrel0123456789abcdef0123456789"
        const val NEW_KEY = "wren0123456789abcdef0123456789ab"

        fun status(version: String) = """{"appName":"Sportarr","version":"$version"}"""
    }
}
