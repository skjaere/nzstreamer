package app.nzstreamer.plugin.sportarr.ui

import app.nzstreamer.plugin.sportarr.bouts.Cards
import app.nzstreamer.plugin.sportarr.source.Connection
import app.nzstreamer.plugin.sportarr.source.SportarrAddress
import app.nzstreamer.plugin.sportarr.source.SportarrClient
import app.nzstreamer.plugin.sportarr.source.TestAnswer
import io.skjaere.usenet.plugin.AddressSafety
import io.skjaere.usenet.plugin.PluginHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/** Where the form puts a remote's focus: its fields and its two buttons. */
internal enum class SetupFocus { Address, Key, Test, Save }

/**
 * One move of the focus the form asks for, [to] that place: on a TV after Test's answer, and on any device after Change,
 * whose key field opens to type in. [count] tells each move from the last, so the same place asked twice is two moves.
 */
internal data class FocusMove(val to: SetupFocus, val count: Int)

/** Test's line under the buttons: ✓ when [ok], else ⚠, and its words. */
internal data class AnswerLine(val ok: Boolean, val text: String)

/**
 * The setup form as it stands (L640): the address as typed and the host's judgement of it ([safety]), the key as typed, or
 * [keySaved] where one is stored and Change wasn't pressed ([changing]), Test running or its [answer], and the focus [move]
 * it asks for. What it shows follows from these alone; [tv] names the device in Test's lines ("this TV").
 */
internal data class SetupForm(
    val tv: Boolean,
    val loaded: Boolean = false,
    val address: String = "",
    val safety: AddressSafety = AddressSafety.Invalid,
    val key: String = "",
    val keySaved: Boolean = false,
    val changing: Boolean = false,
    val testing: Boolean = false,
    val saving: Boolean = false,
    val answer: TestAnswer? = null,
    val move: FocusMove? = null,
) {
    /**
     * The address's help line, by the host's judgement: plain http on the local network says so, over the internet it
     * warns; anything else (nothing typed, https, an address still being typed) says where to find it.
     */
    val help: String
        get() = when {
            address.isBlank() -> Words.Setup.ADDRESS_HELP
            safety == AddressSafety.LocalHttp -> Words.Setup.LOCAL_HTTP
            safety == AddressSafety.PublicHttp -> Words.Setup.PUBLIC_HTTP
            else -> Words.Setup.ADDRESS_HELP
        }

    /** Whether the help line is the warning, in the warning colour. Save still saves with it shown, as the indexer form does. */
    val helpWarns: Boolean get() = help == Words.Setup.PUBLIC_HTTP

    /** The key field shows "Saved" with Change, never the key itself. */
    val showsSavedKey: Boolean get() = keySaved && !changing

    /** After Change, the emptied field asks for the new key. */
    val keyPlaceholder: String? get() = if (changing) Words.Setup.PASTE_NEW_KEY else null

    private val hasKey: Boolean get() = showsSavedKey || key.isNotBlank()

    /**
     * Test waits for an address and a key, and stays enabled while it runs: a button disabled under a remote's focus would
     * drop it. An address Sports can't read is still tested, and Test says so, sending nothing.
     */
    val canTest: Boolean get() = loaded && address.isNotBlank() && hasKey

    /** Save waits for the same, and never for a passed Test: it saves after any answer, for setting up away from home. */
    val canSave: Boolean get() = canTest

    val testLabel: String get() = if (testing) Words.Setup.TESTING else Words.Setup.TEST

    /** Test's answer as its line, or none while it runs or before it. */
    val line: AnswerLine? get() = answer?.let { AnswerLine(it is TestAnswer.Connected, Words.Setup.answer(it, tv)) }
}

/**
 * The setup form's model (L640), for the tab's first screen until a Save and the settings page alike: it opens with what
 * is stored, follows what is typed, and runs Test and Save. Nothing reaches Sportarr but on Test, through the plugin's
 * client on the host's [http]; Save stores the address and the key in the host's secrets ([connection]) and sends
 * nothing itself, then tells [saved] whether either changed, which reads the list at once (L641). [safety] is the host's
 * judgement of an address; [work] decodes Test's answers off the caller's thread.
 *
 * A page made again after its activity was ([restored]) keeps the address as typed and Change pressed; the key typed is
 * never kept anywhere but the host's secrets, once saved.
 *
 * The settings page alone has the Fight cards from Wikipedia switch (L-B8, W3): [cards]' own, which the UFC cards' pages
 * follow; the tab's first screen has none.
 */
internal class SetupModel(
    private val connection: Connection,
    private val http: PluginHttp,
    private val safety: (String) -> AddressSafety,
    tv: Boolean,
    private val saved: (changed: Boolean) -> Unit,
    private val restored: Restored? = null,
    private val work: CoroutineContext = Dispatchers.Default,
    private val cards: Cards? = null,
) {
    private val current = MutableStateFlow(SetupForm(tv, address = restored?.address.orEmpty(), changing = restored?.changing == true))
    private var moves = 0

    /** The form as it stands. */
    val form: StateFlow<SetupForm> = current.asStateFlow()

    /**
     * The Fight cards from Wikipedia switch, on or off as options.json keeps it, null until that is read; none (null) on the
     * tab's first screen.
     */
    val cardsSwitch: StateFlow<Boolean?>? = cards?.switch

    /**
     * Reads what is stored: the address (unless one typed was [restored]) and whether a key is, and the switch where the
     * page has it. Sends nothing.
     */
    suspend fun open() {
        cards?.readSwitch()
        if (current.value.loaded) return
        val stored = connection.address()
        val keySaved = connection.key() != null
        current.update { now ->
            val address = if (restored != null) now.address else stored.orEmpty()
            now.copy(loaded = true, address = address, safety = judged(address), keySaved = keySaved, changing = now.changing && keySaved)
        }
    }

    /** The address as typed; an answer to what was there before goes. */
    fun address(typed: String) = current.update { it.copy(address = typed, safety = judged(typed), answer = null, move = null) }

    /** The key as typed; an answer to the one before goes. */
    fun key(typed: String) = current.update { it.copy(key = typed, answer = null, move = null) }

    /** Change, in the saved key's field: it empties for a new key and opens to type it. */
    fun change() {
        val move = moveTo(SetupFocus.Key)
        current.update { it.copy(changing = true, key = "", answer = null, move = move) }
    }

    /**
     * Test (L640): the status, then the leagues, with the key typed or the one stored, and its answer as the form's line. A
     * press while one runs asks nothing more. An answer to an address or key edited meanwhile isn't shown. On a TV the
     * focus then goes to Save after Connected, and stays on Test after any other answer, where Up goes back to the key.
     */
    suspend fun test() {
        val asked = current.value
        if (!asked.canTest || asked.testing) return
        val key = keyOf(asked) ?: return
        current.update { it.copy(testing = true, answer = null, move = null) }
        val answer = try {
            SportarrClient(http, asked.address, key, safety, work).test()
        } finally {
            current.update { it.copy(testing = false) }
        }
        val move = moveTo(focusAfter(answer))
        current.update { now ->
            val edited = now.address != asked.address || now.key != asked.key || now.changing != asked.changing
            if (edited) now else now.copy(answer = answer, move = move)
        }
    }

    /**
     * Save: the address and the key stored, the key typed or the one stored, whether or not Test ran or passed; true once
     * stored. It waits for an address and a key, and refuses an address Sports can't read with Test's line for it. Once
     * started it finishes, should the page go meanwhile: an address stored without its key would be asked with the old one.
     */
    suspend fun save(): Boolean {
        val asked = current.value
        if (!asked.canSave || asked.saving) return false
        if (SportarrAddress.read(asked.address, safety) == null) {
            current.update { it.copy(answer = TestAnswer.InvalidAddress, move = null) }
            return false
        }
        current.update { it.copy(saving = true) }
        return try {
            withContext(NonCancellable) {
                val key = keyOf(asked) ?: return@withContext false
                saved(connection.save(asked.address, key))
                true
            }
        } finally {
            current.update { it.copy(saving = false) }
        }
    }

    /**
     * The switch turned [on] or off, kept the moment it is, as the app's switches are: Save stays for the address and the
     * key, and the form is left as it is. Off cancels a card's read and deletes the kept cards ([Cards.setOn]).
     */
    fun switchCards(on: Boolean) {
        cards?.setOn(on)
    }

    /** What a page keeps of the form for its activity made again: the address as typed and Change, never the key. */
    fun kept(): Restored = current.value.let { Restored(it.address, it.changing) }

    /** The address as typed and whether Change was pressed: strings and booleans alone, as a page's saved state must be. */
    data class Restored(val address: String, val changing: Boolean)

    /**
     * The key typed, trimmed as Save stores it (a pasted key may bring a line break, which no header can carry, and Test
     * would read the refusal as no answer), or the one stored where the field shows Saved; null where none is.
     */
    private suspend fun keyOf(form: SetupForm): String? = if (form.showsSavedKey) connection.key() else form.key.trim().takeIf { it.isNotEmpty() }

    private fun judged(typed: String): AddressSafety = SportarrAddress.safety(typed, safety)

    // Outside update's lambda, which may run more than once.
    private fun moveTo(to: SetupFocus) = FocusMove(to, ++moves)

    private companion object {
        /** A TV's focus after Test's [answer]: on to Save once connected, else where it was, on Test. */
        fun focusAfter(answer: TestAnswer): SetupFocus = if (answer is TestAnswer.Connected) SetupFocus.Save else SetupFocus.Test
    }
}
