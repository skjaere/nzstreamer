package io.skjaere.usenet.app.settings

import io.skjaere.usenet.core.ProviderConfig
import io.skjaere.usenet.core.UsenetError
import io.skjaere.usenet.data.ProviderRecord
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class ProvidersTest {
    private val main = ProviderRecord("p1", "Main provider", "news.example.net", 563, "user", useTls = true, connections = 20)
    private val backup = ProviderRecord("p2", "", "block.example.com", 119, "user", useTls = false, connections = 1)
    private val mainLogin = ProviderLogin.of(main, "hunter2")
    private val backupLogin = ProviderLogin.of(backup, "pw2")

    private fun rows(
        vararg providers: Pair<ProviderRecord, ProviderLogin?>,
        tests: Map<String, ProviderTestResult> = emptyMap(),
        testing: String? = null,
    ) = providerRows(providers.map { (record, login) -> SavedProvider(record, login) }, tests, testing)

    @Test
    fun `a row shows its place, its name or else its host, and how it connects`() {
        val (first, second) = rows(main to mainLogin, backup to backupLogin)
        assertEquals(listOf(1, 2), listOf(first.position, second.position))
        assertEquals("Main provider", first.name)
        assertEquals("block.example.com", second.name)
        assertEquals("news.example.net:563 · TLS · 20 connections", first.address)
        assertEquals("block.example.com:119 · 1 connection", second.address)
        assertEquals(listOf(false to true, true to false), listOf(first, second).map { it.canMoveUp to it.canMoveDown })
    }

    @Test
    fun `an untested provider has no status`() {
        assertNull(rows(main to mainLogin).single().status)
    }

    @Test
    fun `the last test of the saved login shows as Connected or as the failure in words`() {
        val tests = mapOf(
            "p1" to ProviderTestResult(mainLogin, ok = true, message = "Connected and logged in to news.example.net"),
            "p2" to ProviderTestResult(backupLogin, ok = false, message = "The provider rejected the username or password."),
        )
        val (first, second) = rows(main to mainLogin, backup to backupLogin, tests = tests)
        assertEquals(ProviderStatus.Connected, first.status)
        assertEquals("Connected", first.status?.text)
        assertEquals("The provider rejected the username or password.", second.status?.text)
    }

    @Test
    fun `a test of other settings than the saved ones doesn't show`() {
        val otherPassword = ProviderTestResult(ProviderLogin.of(main, "typed but not saved"), ok = true, message = "Connected")
        val otherHost = ProviderTestResult(ProviderLogin.of(main.copy(host = "news2.example.net"), "hunter2"), ok = true, message = "Connected")
        assertNull(rows(main to mainLogin, tests = mapOf("p1" to otherPassword)).single().status)
        assertNull(rows(main to mainLogin, tests = mapOf("p1" to otherHost)).single().status)
        // The name and the connection count aren't part of what a test proves.
        val renamed = main.copy(name = "Renamed", connections = 4)
        val tested = ProviderTestResult(mainLogin, ok = true, message = "Connected")
        assertEquals(ProviderStatus.Connected, rows(renamed to ProviderLogin.of(renamed, "hunter2"), tests = mapOf("p1" to tested)).single().status)
    }

    @Test
    fun `the engine's check of a saved provider before using it shows on its row as a Test of it would`() {
        // What the provider sync gives the engine for the saved provider.
        val config = ProviderConfig("p1", main.name, main.host, main.port, main.username, "hunter2", main.useTls, main.connections)
        assertEquals(ProviderStatus.Connected, rows(main to mainLogin, tests = mapOf("p1" to config.loginResult(null))).single().status)
        assertEquals(
            "The provider rejected the username or password.",
            rows(main to mainLogin, tests = mapOf("p1" to config.loginResult(UsenetError.AuthFailed("481")))).single().status?.text,
        )
    }

    @Test
    fun `off and an unreadable password come before any test, and a running test shows`() {
        val tested = mapOf("p1" to ProviderTestResult(mainLogin, ok = true, message = "Connected"))
        assertEquals("Off", rows(main.copy(enabled = false) to mainLogin, tests = tested).single().status?.text)
        assertEquals("Off", rows(main.copy(enabled = false) to null).single().status?.text)
        assertEquals("Password can't be read here: enter it again", rows(main to null, tests = tested).single().status?.text)
        assertEquals("Testing…", rows(main to mainLogin, tests = tested, testing = "p1").single().status?.text)
    }

    @Test
    fun `a password is kept only as a digest`() {
        assertEquals(ProviderLogin.of(main, "hunter2"), mainLogin)
        assertNotEquals(ProviderLogin.of(main, "hunter3"), mainLogin)
        assertEquals(false, "hunter2" in mainLogin.toString())
    }

    @Test
    fun `back on the list the focus goes to the provider opened, the one in its place once deleted, or the one added`() {
        val listed = listOf("p1", "p2", "p3")
        assertEquals(ListFocus.Row("p2"), focusAfterEdit("p2", listed, listed))
        assertEquals(ListFocus.Row("p2"), focusAfterEdit("p2", listed, listOf("p2", "p1", "p3")))
        assertEquals(ListFocus.Row("p3"), focusAfterEdit("p2", listed, listOf("p1", "p3")))
        assertEquals(ListFocus.Row("p2"), focusAfterEdit("p1", listed, listOf("p2", "p3")))
        assertEquals(ListFocus.Row("p2"), focusAfterEdit("p3", listed, listOf("p1", "p2")))
        assertEquals(ListFocus.Add, focusAfterEdit("p1", listOf("p1"), emptyList()))
        assertEquals(ListFocus.Row("p4"), focusAfterEdit(null, listed, listed + "p4"))
        assertEquals(ListFocus.Row("p1"), focusAfterEdit(null, emptyList(), listOf("p1")))
        assertEquals(ListFocus.Add, focusAfterEdit(null, listed, listed))
        assertEquals(ListFocus.Add, focusAfterEdit(null, emptyList(), emptyList()))
    }

    @Test
    fun `the settings hub sums up the providers, saying how many are off`() {
        assertEquals("None yet", providersSummary(emptyList()))
        assertEquals("1 provider · 20 connections", providersSummary(listOf(main)))
        assertEquals("2 providers · 21 connections", providersSummary(listOf(main, backup)))
        assertEquals("2 providers · 21 connections · 1 off", providersSummary(listOf(main, backup.copy(enabled = false))))
        assertEquals("2 providers · 21 connections · 2 off", providersSummary(listOf(main.copy(enabled = false), backup.copy(enabled = false))))
    }

    @Test
    fun `no provider is usable when none is saved, all are switched off or their passwords are unreadable`() {
        assertEquals(NoUsableProvider.NoneSaved, NoUsableProvider.of(emptyList(), usable = 0))
        assertEquals(NoUsableProvider.AllOff, NoUsableProvider.of(listOf(main.copy(enabled = false), backup.copy(enabled = false)), usable = 0))
        assertEquals(NoUsableProvider.Unreadable(1), NoUsableProvider.of(listOf(main, backup.copy(enabled = false)), usable = 0))
        assertEquals(NoUsableProvider.Unreadable(2), NoUsableProvider.of(listOf(main, backup), usable = 0))
        assertNull(NoUsableProvider.of(listOf(main, backup.copy(enabled = false)), usable = 1))
    }

    @Test
    fun `each case says what is wrong in its own words`() {
        assertEquals("No Usenet provider is set up yet.", NoUsableProvider.NoneSaved.message)
        assertEquals("Every Usenet provider is switched off.", NoUsableProvider.AllOff.message)
        assertEquals("Your Usenet provider's password can't be read here: enter it again.", NoUsableProvider.Unreadable(1).message)
        assertEquals("Your Usenet providers' passwords can't be read here: enter them again.", NoUsableProvider.Unreadable(2).message)
    }
}
