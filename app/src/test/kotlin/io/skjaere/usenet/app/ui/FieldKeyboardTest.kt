package io.skjaere.usenet.app.ui

import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The keyboard each kind of field gets, on every device: the layout for what is typed, and no
 * corrections or capitals where the keyboard changing a word would break it.
 */
class FieldKeyboardTest {
    @Test
    fun `hosts and URLs take the address keyboard, neither corrected nor capitalised`() {
        listOf(FieldKind.Host, FieldKind.Url).forEach { kind ->
            val options = fieldKeyboard(kind, ImeAction.Next)
            assertEquals(KeyboardType.Uri, options.keyboardType, "$kind")
            assertEquals(false, options.autoCorrectEnabled, "$kind corrected")
            assertEquals(KeyboardCapitalization.None, options.capitalization, "$kind capitalised")
        }
    }

    @Test
    fun `passwords and keys take the password keyboard, uncorrected and uncapitalised`() {
        listOf(FieldKind.Password, FieldKind.Key).forEach { kind ->
            val options = fieldKeyboard(kind, ImeAction.Next)
            assertEquals(KeyboardType.Password, options.keyboardType, "$kind")
            assertEquals(false, options.autoCorrectEnabled, "$kind corrected")
            assertEquals(KeyboardCapitalization.None, options.capitalization, "$kind capitalised")
        }
    }

    @Test
    fun `usernames are neither corrected nor capitalised`() {
        val options = fieldKeyboard(FieldKind.Username, ImeAction.Next)
        assertEquals(KeyboardType.Text, options.keyboardType)
        assertEquals(false, options.autoCorrectEnabled)
        assertEquals(KeyboardCapitalization.None, options.capitalization)
    }

    @Test
    fun `the port and other numbers take the number pad`() {
        listOf(FieldKind.Port, FieldKind.Number).forEach { kind -> assertEquals(KeyboardType.Number, fieldKeyboard(kind, ImeAction.Done).keyboardType, "$kind") }
    }

    /** A name is words: the keyboard's own help stays. */
    @Test
    fun `a name keeps the keyboard's corrections`() {
        val options = fieldKeyboard(FieldKind.Text, ImeAction.Next)
        assertEquals(KeyboardType.Text, options.keyboardType)
        assertTrue(options.autoCorrectEnabled != false, "a name is not corrected")
    }

    /** Next down a form, Done on its last field: as the form says. */
    @Test
    fun `the keyboard's action is the form's`() {
        FieldKind.entries.forEach { kind ->
            assertEquals(ImeAction.Next, fieldKeyboard(kind, ImeAction.Next).imeAction, "$kind")
            assertEquals(ImeAction.Done, fieldKeyboard(kind, ImeAction.Done).imeAction, "$kind")
        }
    }

    @Test
    fun `only passwords and keys are hidden as typed`() {
        assertEquals(setOf(FieldKind.Password, FieldKind.Key), FieldKind.entries.filter { it.secret }.toSet())
        assertFalse(FieldKind.Host.secret)
    }
}
