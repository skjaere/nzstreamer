package io.skjaere.usenet.app.settings

import io.skjaere.usenet.data.ProviderRecord
import io.skjaere.usenet.data.ProviderWithPassword
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderFormTest {
    private val record = ProviderRecord("p1", "Main", "news.example.net", 563, "user", useTls = true, connections = 20)

    /** A TV's Connections stepper: one at a time, within the form's 1 to 100; a number out of them, or none, from the nearest. */
    @Test
    fun `the connections step by one within the form's limits`() {
        val form = ProviderForm("p1", connections = "20")
        assertEquals("21", form.steppedConnections(1).connections)
        assertEquals("19", form.steppedConnections(-1).connections)
        assertEquals("100", form.copy(connections = "100").steppedConnections(1).connections)
        assertEquals("1", form.copy(connections = "1").steppedConnections(-1).connections)
        assertEquals("100", form.copy(connections = "250").steppedConnections(1).connections, "typed beyond the limit on a phone")
        assertEquals("9", form.copy(connections = "").steppedConnections(1).connections, "none: from a new provider's 8")
        assertEquals("p1", form.steppedConnections(1).id)
    }

    @Test
    fun `a saved password shows as saved`() {
        val form = providerForm(ProviderWithPassword(record, "hunter2"))
        assertEquals(ProviderForm("p1", "Main", "news.example.net", "563", "user", "", hasSavedPassword = true, useTls = true, connections = "20"), form)
        assertEquals(null, form.passwordToSave, "an empty field keeps the saved password")
    }

    @Test
    fun `a provider without login has no saved password and needs none`() {
        val form = providerForm(ProviderWithPassword(record.copy(username = ""), ""))
        assertFalse(form.hasSavedPassword)
        assertFalse(form.passwordUnreadable)
    }

    @Test
    fun `a password that can't be read asks for it again`() {
        val form = providerForm(ProviderWithPassword(record, null))
        assertFalse(form.hasSavedPassword)
        assertTrue(form.passwordUnreadable)
    }

    // #131: Save with the field empty while the warning shows used to store "" as the password.
    @Test
    fun `a password that can't be read must be typed again before Save`() {
        val form = providerForm(ProviderWithPassword(record, null))
        assertTrue(form.needsPassword)
        assertFalse(form.valid)
        assertEquals(null, form.passwordToSave, "nothing would replace the saved password")
        val typed = form.copy(password = "hunter2")
        assertFalse(typed.needsPassword)
        assertTrue(typed.valid)
        assertEquals("hunter2", typed.passwordToSave)
    }

    @Test
    fun `without a login no password is needed, and none is kept`() {
        val unreadable = providerForm(ProviderWithPassword(record, null)).copy(username = "")
        assertFalse(unreadable.needsPassword)
        assertTrue(unreadable.valid)
        assertEquals("", unreadable.passwordToSave)
        assertEquals("", providerForm(ProviderWithPassword(record.copy(username = ""), "")).passwordToSave)
    }

    @Test
    fun `a new provider is switched on, a saved one keeps its switch`() {
        assertTrue(newProviderForm().enabled)
        assertFalse(providerForm(ProviderWithPassword(record.copy(enabled = false), "pw")).enabled)
        assertNotEquals(newProviderForm().id, newProviderForm().id)
    }

    /**
     * A login typed into the host (user:password@host) went to the engine with it, and the logs name
     * the host; a scheme (nntps://) never connects.
     */
    @Test
    fun `a host with a login or a scheme in it can't be saved`() {
        val form = providerForm(ProviderWithPassword(record, "pw"))
        assertTrue(form.valid)
        assertNull(form.hostProblem)
        listOf("alice:hunter2@news.example.net", "alice@news.example.net", "nntps://news.example.net", " NNTP://news.example.net").forEach { host ->
            val typed = form.copy(host = host)
            assertFalse(typed.valid, host)
            assertNotNull(typed.hostProblem, host)
        }
        assertEquals(
            "Only the server's name: the username and password go in their own fields.",
            form.copy(host = "nntps://alice:pw@news.example.net").hostProblem,
        )
        assertEquals("Only the server's name, without nntps:// or nntp://.", form.copy(host = "nntps://news.example.net").hostProblem)
    }

    @Test
    fun `the name is optional and the host stands in for it`() {
        assertEquals("Main", providerForm(ProviderWithPassword(record, "pw")).displayName)
        assertEquals("news.example.net", providerForm(ProviderWithPassword(record.copy(name = ""), "pw")).displayName)
    }
}
