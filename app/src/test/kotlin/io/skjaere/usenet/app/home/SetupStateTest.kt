package io.skjaere.usenet.app.home

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SetupStateTest {
    @Test
    fun `with nothing saved the provider comes first and there is no Not now`() {
        val s = SetupState(providers = emptyList(), hasIndexer = false, hasTmdbKey = false, indexerSkipped = false)
        assertFalse(s.complete)
        assertEquals(SetupStep.Provider, s.next)
        assertEquals("Add a Usenet provider", s.action)
        assertFalse(s.canSkip)
        assertEquals(listOf(false, false, false), s.rows.map { it.done })
        assertEquals(listOf(1, 2, 3), s.rows.map { it.number })
        assertEquals(listOf(SetupStep.Provider, SetupStep.Indexer, SetupStep.Metadata), s.rows.map { it.step })
        assertEquals(listOf("Required", "Required", "Optional"), s.rows.map { it.step.need })
        assertEquals(
            listOf("Your Usenet account, to stream from", "To search for releases", "Enables your collection, posters and episode lists"),
            s.rows.map { it.subtitle },
        )
    }

    @Test
    fun `once a provider is saved its row names the host, the indexer is next and Not now shows`() {
        val s = SetupState(providers = listOf("news.example.net"), hasIndexer = false, hasTmdbKey = true, indexerSkipped = false)
        assertFalse(s.complete)
        assertEquals(SetupStep.Indexer, s.next)
        assertEquals("Add an indexer", s.action)
        assertTrue(s.canSkip)
        assertEquals(listOf(true, false, true), s.rows.map { it.done })
        assertEquals("news.example.net", s.rows.first().subtitle)
    }

    @Test
    fun `with several providers the row names the first and how many more`() {
        val two = SetupState(listOf("Main provider", "block.example.com"), hasIndexer = true, hasTmdbKey = false, indexerSkipped = false)
        assertEquals("Main provider and 1 more", two.rows.first().subtitle)
        assertTrue(two.rows.first().done)
        assertTrue(two.complete)
        val three = SetupState(listOf("Main provider", "block.example.com", "Third"), hasIndexer = false, hasTmdbKey = false, indexerSkipped = false)
        assertEquals("Main provider and 2 more", three.rows.first().subtitle)
        assertEquals(SetupStep.Indexer, three.next)
    }

    @Test
    fun `Not now completes setup only with a provider`() {
        assertTrue(SetupState(listOf("news.example.net"), hasIndexer = false, hasTmdbKey = false, indexerSkipped = true).complete)
        val noProvider = SetupState(emptyList(), hasIndexer = false, hasTmdbKey = false, indexerSkipped = true)
        assertFalse(noProvider.complete)
        assertEquals(SetupStep.Provider, noProvider.next)
        assertFalse(noProvider.canSkip)
    }

    @Test
    fun `a provider and an indexer complete setup, with or without a TMDB key`() {
        val s = SetupState(listOf("news.example.net"), hasIndexer = true, hasTmdbKey = false, indexerSkipped = false)
        assertTrue(s.complete)
        assertNull(s.next)
        assertNull(s.action)
        assertFalse(s.canSkip)
    }

    @Test
    fun `an indexer without a provider still asks for the provider`() {
        val s = SetupState(emptyList(), hasIndexer = true, hasTmdbKey = false, indexerSkipped = false)
        assertFalse(s.complete)
        assertEquals(SetupStep.Provider, s.next)
        assertEquals(listOf(false, true, false), s.rows.map { it.done })
        assertFalse(s.canSkip)
    }

    /** With no service saved (none can be yet), a torrent indexer alone changes nothing, nor a service alone (L165). */
    @Test
    fun `a service or a torrent indexer without the other leaves every answer as before`() {
        val providers = listOf(emptyList(), listOf("news.example.net"), listOf("Main provider", "block.example.com"))
        providers.forEach { saved ->
            listOf(false, true).forEach { indexer ->
                listOf(false, true).forEach { skipped ->
                    val before = SetupState(saved, hasIndexer = indexer, hasTmdbKey = false, indexerSkipped = skipped)
                    listOf(
                        SetupState(saved, indexer, hasTmdbKey = false, indexerSkipped = skipped, services = emptyList(), hasTorrentIndexer = false),
                        SetupState(saved, indexer, hasTmdbKey = false, indexerSkipped = skipped, hasTorrentIndexer = true),
                        SetupState(saved, indexer, hasTmdbKey = false, indexerSkipped = skipped, services = listOf("TorBox")),
                    ).forEach { s ->
                        val case = "$saved, indexer $indexer, skipped $skipped: $s"
                        assertEquals(before.complete, s.complete, case)
                        assertEquals(before.canPlay, s.canPlay, case)
                        assertEquals(before.next, s.next, case)
                        assertEquals(before.action, s.action, case)
                        assertEquals(before.canSkip, s.canSkip, case)
                        assertEquals(before.rows, s.rows, case)
                    }
                }
            }
        }
    }

    /** First run's two pairs (L165): a provider and a Newznab indexer (or "Not now"), or a service and a torrent indexer. */
    @Test
    fun `a service and a torrent indexer complete setup and let the TV play, a service with Newznab indexers alone doesn't`() {
        val torrentPair = SetupState(emptyList(), hasIndexer = false, hasTmdbKey = false, indexerSkipped = false, services = listOf("TorBox"), hasTorrentIndexer = true, shown = true)
        assertTrue(torrentPair.complete)
        assertTrue(torrentPair.canPlay)
        val newznabOnly = SetupState(emptyList(), hasIndexer = true, hasTmdbKey = false, indexerSkipped = false, services = listOf("TorBox"), shown = true)
        assertFalse(newznabOnly.complete)
        assertFalse(newznabOnly.canPlay)
        // "Not now" stands in for Usenet's indexer, never the torrent pair's.
        assertFalse(SetupState(emptyList(), hasIndexer = false, hasTmdbKey = false, indexerSkipped = true, services = listOf("TorBox"), shown = true).complete)
        // Usenet's pair completes beside a half-made torrent one.
        assertTrue(SetupState(listOf("news.example.net"), hasIndexer = true, hasTmdbKey = false, indexerSkipped = false, services = listOf("TorBox"), shown = true).canPlay)
    }

    /** L192: hidden, a service counts for nothing, so the torrent pair a shown build saved doesn't stand in for Usenet's. */
    @Test
    fun `hidden, the torrent pair completes nothing and first run is today's`() {
        val saved = SetupState(emptyList(), hasIndexer = false, hasTmdbKey = false, indexerSkipped = false, services = listOf("TorBox"), hasTorrentIndexer = true, shown = false)
        val today = SetupState(emptyList(), hasIndexer = false, hasTmdbKey = false, indexerSkipped = false)
        assertFalse(saved.complete)
        assertFalse(saved.canPlay)
        assertEquals(today.rows, saved.rows)
        assertEquals(listOf(SetupGroup(null, today.rows)), saved.groups)
        assertEquals(SetupStep.Provider, saved.next)
        assertEquals("Add a Usenet provider", saved.action)
        assertNull(saved.alternative)
    }

    /** DbPhoneFirstRun 2: two ways to finish, Usenet's first (W81), each in its group; neither step required until one is under way. */
    @Test
    fun `with debrid shown and nothing saved, Usenet's way leads and the debrid service is the second button`() {
        val s = SetupState(emptyList(), hasIndexer = false, hasTmdbKey = false, indexerSkipped = false, shown = true)
        assertFalse(s.complete)
        assertEquals(SetupStep.Provider, s.next)
        assertEquals("Add a Usenet provider", s.action)
        assertEquals(SetupStep.Service, s.alternative)
        assertEquals("Add a debrid service", s.alternativeAction)
        assertFalse(s.canSkip)
        assertEquals(listOf("Usenet", "Or torrents, through a debrid service", null), s.groups.map { it.heading })
        assertEquals(
            listOf(
                listOf(SetupRow(SetupStep.Provider, 1, false, "Your Usenet account, to stream from", need = ""), SetupRow(SetupStep.Indexer, 2, false, "To search for Usenet releases", need = "")),
                listOf(SetupRow(SetupStep.Service, 1, false, "Your debrid account, with its API key", need = ""), SetupRow(SetupStep.TorrentIndexer, 2, false, "Prowlarr or Jackett, to search for torrents", need = "")),
                listOf(SetupRow(SetupStep.Metadata, 3, false, "Enables your collection, posters and episode lists")),
            ),
            s.groups.map { it.rows },
        )
        assertEquals(s.groups.flatMap { it.rows }, s.rows)
        assertEquals(listOf("Debrid service", "Torrent indexer"), listOf(SetupStep.Service, SetupStep.TorrentIndexer).map { it.title })
    }

    /** DbPhoneFirstRun 3: once one way is under way, the one button adds its next step, which is ringed and required. */
    @Test
    fun `once a service is signed in, the torrent indexer is next and required, and the button adds it`() {
        val s = SetupState(emptyList(), hasIndexer = false, hasTmdbKey = false, indexerSkipped = false, services = listOf("TorBox"), shown = true)
        assertFalse(s.complete)
        assertEquals(SetupStep.TorrentIndexer, s.next)
        assertEquals("Add a torrent indexer", s.action)
        assertNull(s.alternative)
        assertNull(s.alternativeAction)
        assertFalse(s.canSkip, "Not now only with a provider")
        val torrents = s.groups[1].rows
        assertEquals(SetupRow(SetupStep.Service, 1, true, "TorBox", need = ""), torrents[0])
        assertEquals(SetupRow(SetupStep.TorrentIndexer, 2, false, "Prowlarr or Jackett, to search for torrents", need = "Required"), torrents[1])
        assertEquals(listOf("", ""), s.groups[0].rows.map { it.need }, "Usenet's way isn't under way")
        // A torrent indexer saved first: the service is what's left.
        val indexerFirst = SetupState(emptyList(), hasIndexer = false, hasTmdbKey = false, indexerSkipped = false, hasTorrentIndexer = true, shown = true)
        assertEquals(SetupStep.Service, indexerFirst.next)
        assertEquals("Add a debrid service", indexerFirst.action)
        assertNull(indexerFirst.alternative)
        // Several services: their names, A to Z as given.
        assertEquals("Premiumize, TorBox", SetupState(emptyList(), false, false, false, services = listOf("Premiumize", "TorBox"), shown = true).groups[1].rows[0].subtitle)
    }

    @Test
    fun `once Usenet's way is under way it goes first, a service beside it or not, and Not now shows with a provider`() {
        val provider = SetupState(listOf("news.example.net"), hasIndexer = false, hasTmdbKey = false, indexerSkipped = false, shown = true)
        assertEquals(SetupStep.Indexer, provider.next)
        assertEquals("Add an indexer", provider.action)
        assertNull(provider.alternative)
        assertTrue(provider.canSkip)
        assertEquals(listOf("", "Required"), provider.groups[0].rows.map { it.need })
        assertEquals(SetupRow(SetupStep.Provider, 1, true, "news.example.net", need = ""), provider.groups[0].rows[0])
        val both = SetupState(listOf("news.example.net"), hasIndexer = false, hasTmdbKey = false, indexerSkipped = false, services = listOf("TorBox"), shown = true)
        assertEquals(SetupStep.Indexer, both.next)
        assertTrue(both.canSkip)
        // A Newznab indexer alone is Usenet's way under way too: its provider is next.
        val indexer = SetupState(emptyList(), hasIndexer = true, hasTmdbKey = false, indexerSkipped = false, services = listOf("TorBox"), shown = true)
        assertEquals(SetupStep.Provider, indexer.next)
        assertEquals(listOf("Required", ""), indexer.groups[0].rows.map { it.need })
    }

    /** The phone setup's wizard is for a TV that can't play yet: "Not now" on the first run doesn't make it one that can. */
    @Test
    fun `the TV can play with a provider and an indexer, Not now or not`() {
        assertTrue(SetupState(listOf("news.example.net"), hasIndexer = true, hasTmdbKey = false, indexerSkipped = false).canPlay)
        assertFalse(SetupState(listOf("news.example.net"), hasIndexer = false, hasTmdbKey = true, indexerSkipped = true).canPlay)
        assertFalse(SetupState(emptyList(), hasIndexer = true, hasTmdbKey = true, indexerSkipped = false).canPlay)
        assertFalse(SetupState(emptyList(), hasIndexer = false, hasTmdbKey = false, indexerSkipped = false).canPlay)
    }
}
