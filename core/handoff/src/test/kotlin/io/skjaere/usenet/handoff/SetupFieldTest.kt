package io.skjaere.usenet.handoff

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SetupFieldTest {
    @Test
    fun `a kind's fields are its own, in the page's order`() {
        fun keys(section: SetupSection) = SetupField.entries.filter { it.section == section }.map { it.key }
        assertEquals(listOf("p.name", "p.host", "p.port", "p.tls", "p.user", "p.pass", "p.conns"), keys(SetupSection.Provider))
        assertEquals(listOf("i.name", "i.url", "i.key", "i.ua"), keys(SetupSection.Indexer))
        assertEquals(listOf("s.kind", "s.key"), keys(SetupSection.Service))
        assertEquals(listOf("r.name", "r.url", "r.key", "r.ua", "r.debrid"), keys(SetupSection.TorrentIndexer))
        assertEquals(listOf("t.key"), keys(SetupSection.Tmdb))
        assertEquals(listOf("p.pass", "i.key", "s.key", "r.key", "t.key"), SetupField.entries.filter { it.isSecret }.map { it.key })
    }

    /** The debrid cards' fields are their Settings forms' (L209): the service a radio, the torrent indexer's switch a box. */
    @Test
    fun `the debrid cards' fields are a choice, keys, an indexer's text and a box`() {
        assertEquals(SetupField.Kind.Choice, SetupField.ServiceKind.kind)
        assertEquals(listOf(SetupField.Kind.Key, SetupField.Kind.Key), listOf(SetupField.ServiceKey.kind, SetupField.TorrentIndexerKey.kind))
        assertEquals(SetupField.Kind.Checkbox, SetupField.TorrentIndexerDebrid.kind)
        assertEquals("Use with your debrid service", SetupField.TorrentIndexerDebrid.label)
        // As an indexer's: the same limits for the same things.
        assertEquals(
            listOf(SetupField.IndexerName, SetupField.IndexerUrl, SetupField.IndexerKey, SetupField.IndexerAgent, SetupField.IndexerKey).map { it.maxLength to it.label },
            listOf(SetupField.TorrentIndexerName, SetupField.TorrentIndexerUrl, SetupField.TorrentIndexerKey, SetupField.TorrentIndexerAgent, SetupField.ServiceKey)
                .map { it.maxLength to it.label },
        )
    }

    /** L555: an indexer's own User-Agent, on both indexer cards, held to the app's rule (L548): 256 chars, not a secret. */
    @Test
    fun `both indexer cards have a User-Agent of their own kind, no secret`() {
        for (field in listOf(SetupField.IndexerAgent, SetupField.TorrentIndexerAgent)) {
            assertEquals(listOf(SetupField.Kind.Agent, 256, "User-Agent"), listOf(field.kind, field.maxLength, field.label), field.name)
            assertFalse(field.isSecret, field.name)
        }
        assertEquals(listOf(SetupField.IndexerAgent), SetupField.entries.filter { it.kind == SetupField.Kind.Agent && it.section == SetupSection.Indexer })
        assertEquals(listOf(SetupField.TorrentIndexerAgent), SetupField.entries.filter { it.kind == SetupField.Kind.Agent && it.section == SetupSection.TorrentIndexer })
    }
}
