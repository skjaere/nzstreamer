package io.skjaere.usenet.app.play

import io.skjaere.usenet.app.settings.NoUsableProvider
import io.skjaere.usenet.core.PlayCandidate
import io.skjaere.usenet.data.ProviderRecord
import io.skjaere.usenet.data.RememberedRelease
import io.skjaere.usenet.releases.ReleaseFacts
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The play's gates by source (L165): what may play is said by the routes usable now, Usenet's while a provider is usable
 * and each service account's that is on. With no account, every gate answers as it did when it asked for a provider alone.
 */
class PlaybackGatesTest {
    private val provider = ProviderRecord("a", "News A", "news.a.example")
    private val usenet = PlayCandidate("indexer-a/1", "Harbor.Lights.S01E02.1080p.WEB-DL-GRP", "indexer-a")
    private val other = PlayCandidate("indexer-a/2", "Harbor.Lights.S01E02.720p.WEB-DL-GRP", "indexer-a")
    private val onAccount = PlayCandidate("btih:abc", "Harbor.Lights.S01E02.1080p.WEB-DL-TOR", "torrents", source = "torrent", routes = listOf("service:a"))

    @Test
    fun `with no account the usable routes are Usenet's alone, while a provider is usable`() {
        val withProvider = UsableRoutes.of(providerUsable = true, accountsOn = emptyList())
        assertEquals(setOf(PlayCandidate.USENET), withProvider.routes)
        assertTrue(withProvider.any)
        assertTrue(withProvider.allows(usenet))
        assertFalse(withProvider.allows(onAccount))

        val without = UsableRoutes.of(providerUsable = false, accountsOn = emptyList())
        assertEquals(emptySet(), without.routes)
        assertFalse(without.any)
        assertFalse(without.allows(usenet))
    }

    @Test
    fun `an account that is on is a route of its own, beside Usenet's or alone`() {
        assertEquals(setOf(PlayCandidate.USENET, "service:a", "service:b"), UsableRoutes.of(true, listOf("a", "b")).routes)
        val accountAlone = UsableRoutes.of(providerUsable = false, accountsOn = listOf("a"))
        assertEquals(setOf(UsableRoutes.serviceRoute("a")), accountAlone.routes)
        assertTrue(accountAlone.any)
        assertTrue(accountAlone.allows(onAccount))
        assertFalse(accountAlone.allows(usenet))
        // A release on two accounts plays while either is on.
        assertTrue(UsableRoutes.of(false, listOf("b")).allows(onAccount.copy(routes = listOf("service:a", "service:b"))))
    }

    /**
     * DEP-4: a list older than a switch (Choose release kept up, a Next found ahead) names an account switched off since: its
     * torrent plays only from the accounts that are on now, so the one switched off is never called, and one cached there
     * alone isn't tried. While nothing is usable, the play tries what it has, as [UsableRoutes.keeps] says; an NZB is as it is.
     */
    @Test
    fun `a torrent plays only from the accounts on now, whatever its list says`() {
        val hash = "c0ffee11c0ffee22c0ffee33c0ffee44c0ffee55"
        val remembered = RememberedRelease("prowlarr", "btih:$hash", "Harbor.Lights.S01E02.1080p.WEB-DL-TOR", null, 0, "btih:$hash", source = ReleaseFacts.TORRENT)
        val onBoth = Replay.onAccounts(Replay.candidate(remembered), listOf("pm", "tb"))
        assertEquals(listOf("service:pm", "service:tb"), onBoth.playCandidate.routes)
        val tbOnly = UsableRoutes.of(providerUsable = true, accountsOn = listOf("tb"))
        assertEquals(listOf("service:tb"), tbOnly.narrow(onBoth).playCandidate.routes)
        val pmOnlyItem = Replay.onAccounts(Replay.candidate(remembered), listOf("pm"))
        assertEquals(emptyList(), tbOnly.cut(listOf(pmOnlyItem).map(tbOnly::narrow)) { it.playCandidate })
        val nothing = UsableRoutes.of(providerUsable = false, accountsOn = emptyList())
        assertEquals(onBoth, nothing.narrow(onBoth))
        val nzb = Replay.candidate(remembered.copy(guid = "1", nzbId = "nzb-1", source = null))
        assertEquals(nzb, tbOnly.narrow(nzb))
    }

    /**
     * L228: a torrent plays from its accounts in the user's order as it is now, the routes', not as its list had them: a list
     * shown before a move, or a Next found ahead, plays in the new order.
     */
    @Test
    fun `a torrent's accounts follow the routes' order, whatever its list's`() {
        val hash = "c0ffee11c0ffee22c0ffee33c0ffee44c0ffee55"
        val remembered = RememberedRelease("prowlarr", "btih:$hash", "Harbor.Lights.S01E02.1080p.WEB-DL-TOR", null, 0, "btih:$hash", source = ReleaseFacts.TORRENT)
        val listed = Replay.onAccounts(Replay.candidate(remembered), listOf("pm", "tb"))
        val torBoxFirst = UsableRoutes.of(providerUsable = true, accountsOn = listOf("tb", "pm"))
        assertEquals(listOf("service:tb", "service:pm"), torBoxFirst.narrow(listed).playCandidate.routes)
        val premiumizeFirst = UsableRoutes.of(providerUsable = false, accountsOn = listOf("pm", "tb"))
        assertEquals(listed, premiumizeFirst.narrow(listed))
        // An account on now that its check didn't find it on isn't added.
        val tbListed = Replay.onAccounts(Replay.candidate(remembered), listOf("tb"))
        assertEquals(listOf("service:tb"), UsableRoutes.of(true, listOf("pm", "tb")).narrow(tbListed).playCandidate.routes)
    }

    @Test
    fun `a request of Usenet releases with no provider fails with today's words`() {
        val none = UsableRoutes.of(providerUsable = false, accountsOn = emptyList())
        val cases = listOf(
            emptyList<ProviderRecord>() to NoUsableProvider.NoneSaved,
            listOf(provider.copy(enabled = false)) to NoUsableProvider.AllOff,
            listOf(provider) to NoUsableProvider.Unreadable(1),
        )
        cases.forEach { (saved, missing) ->
            val gate = noUsableSource(none, saved, usableProviders = 0)
            assertEquals(missing, gate)
            assertEquals(missing, noProviderFailure("Harbor Lights", "S01E02", gate!!).noProvider)
        }
        assertEquals(
            listOf("No Usenet provider is set up yet.", "Every Usenet provider is switched off.", "Your Usenet provider's password can't be read here: enter it again."),
            cases.map { (saved, _) -> noUsableSource(none, saved, usableProviders = 0)?.message },
        )
        // A provider usable lets the play start, its releases all kept.
        val usable = UsableRoutes.of(providerUsable = true, accountsOn = emptyList())
        assertNull(noUsableSource(usable, listOf(provider), usableProviders = 1))
        assertEquals(listOf(usenet, other), usable.cut(listOf(usenet, other)) { it })
    }

    @Test
    fun `a play's releases are cut to the routes usable now, and nothing is cut when none is`() {
        val usenetOnly = UsableRoutes.of(providerUsable = true, accountsOn = emptyList())
        assertEquals(listOf(usenet, other), usenetOnly.cut(listOf(usenet, onAccount, other)) { it })
        assertEquals(listOf(onAccount), UsableRoutes.of(providerUsable = false, accountsOn = listOf("a")).cut(listOf(usenet, onAccount, other)) { it })
        // A provider switched off since the play's gate: the releases go on to their attempts, which say what failed, as before.
        assertEquals(listOf(usenet, other), UsableRoutes.of(false, emptyList()).cut(listOf(usenet, other)) { it })
        assertTrue(UsableRoutes.of(false, emptyList()).keeps(usenet))
        assertFalse(usenetOnly.keeps(onAccount))
        // An account that is on is no gate's reason to stop.
        assertNull(noUsableSource(UsableRoutes.of(false, listOf("a")), emptyList(), usableProviders = 0))
    }

    @Test
    fun `the engine's provider check is waited on only while a Usenet release is left`() {
        assertTrue(waitsForProviders(listOf(usenet)))
        assertTrue(waitsForProviders(listOf(onAccount, usenet)))
        assertFalse(waitsForProviders(listOf(onAccount)))
        assertFalse(waitsForProviders(emptyList()))
        // A release that may play from Usenet or an account still needs the providers checked.
        assertTrue(waitsForProviders(listOf(onAccount.copy(routes = listOf("service:a", PlayCandidate.USENET)))))
    }

    /** A debrid-only user (L165): with a service account on and no provider, the torrents alone play, and nothing waits for the providers' check. */
    @Test
    fun `with a service on and no provider the torrents alone play, with no wait for the providers`() {
        val routes = UsableRoutes.of(providerUsable = false, accountsOn = listOf("a"))
        assertNull(noUsableSource(routes, emptyList(), usableProviders = 0, servicesSaved = true))
        val tried = routes.cut(listOf(usenet, onAccount, other)) { it }
        assertEquals(listOf(onAccount), tried)
        assertFalse(waitsForProviders(tried))
        assertTrue(waitsForProviders(listOf(usenet, onAccount)))
    }

    /** With every service account off and no provider set up, the sheet says the service is off, and where it is switched on (L213). */
    @Test
    fun `with the service off and no provider the sheet says the service is off`() {
        val none = UsableRoutes.of(providerUsable = false, accountsOn = emptyList())
        val gate = noUsableSource(none, emptyList(), usableProviders = 0, servicesSaved = true)
        assertEquals(NoUsableProvider.ServiceOff, gate)
        assertEquals("Your debrid service is off, and no Usenet provider is set up (Settings › Debrid services).", gate?.message)
        assertEquals(gate, noProviderFailure("Harbor Lights", "S01E02", gate!!).noProvider)
        // A provider saved, if off: today's words, the providers a way back too. No service saved, or debrid hidden: today's.
        assertEquals(NoUsableProvider.AllOff, noUsableSource(none, listOf(provider.copy(enabled = false)), usableProviders = 0, servicesSaved = true))
        assertEquals(NoUsableProvider.NoneSaved, noUsableSource(none, emptyList(), usableProviders = 0, servicesSaved = false))
    }

    /**
     * DEP-9: the sheet that says the service is off opens Debrid services, where it is switched on, as its words say; every
     * other sheet for want of a provider opens the providers. Without a way to Debrid services (debrid hidden), the providers.
     */
    @Test
    fun `the sheet that says the service is off opens Debrid services`() {
        val services = { }
        val providers = { }
        assertEquals("Open Debrid services" to services, noProviderWay(NoUsableProvider.ServiceOff, services, providers))
        assertEquals(NoUsableProvider.ACTION to providers, noProviderWay(NoUsableProvider.ServiceOff, null, providers))
        listOf(NoUsableProvider.NoneSaved, NoUsableProvider.AllOff, NoUsableProvider.Unreadable(1)).forEach { missing ->
            assertEquals(NoUsableProvider.ACTION to providers, noProviderWay(missing, services, providers), "$missing")
        }
    }

    /** Import plays from Usenet alone (L165): with a service on and no provider, it still asks for a provider, in today's words. */
    @Test
    fun `Import still asks for a provider with a service on`() {
        assertEquals(NoUsableProvider.NoneSaved, NoUsableProvider.of(emptyList(), usable = 0))
        assertNull(noUsableSource(UsableRoutes.of(providerUsable = false, accountsOn = listOf("a")), emptyList(), usableProviders = 0, servicesSaved = true))
    }
}
