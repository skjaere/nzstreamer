package io.skjaere.usenet.handoff

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class SetupFieldTest {
    @Test
    fun `a kind's fields are its own, in the page's order`() {
        fun keys(section: SetupSection) = SetupField.entries.filter { it.section == section }.map { it.key }
        assertEquals(listOf("p.name", "p.host", "p.port", "p.tls", "p.user", "p.pass", "p.conns"), keys(SetupSection.Provider))
        assertEquals(listOf("i.name", "i.url", "i.key"), keys(SetupSection.Indexer))
        assertEquals(listOf("s.kind", "s.key"), keys(SetupSection.Service))
        assertEquals(listOf("r.name", "r.url", "r.key", "r.debrid"), keys(SetupSection.TorrentIndexer))
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
            listOf(SetupField.IndexerName, SetupField.IndexerUrl, SetupField.IndexerKey, SetupField.IndexerKey).map { it.maxLength to it.label },
            listOf(SetupField.TorrentIndexerName, SetupField.TorrentIndexerUrl, SetupField.TorrentIndexerKey, SetupField.ServiceKey).map { it.maxLength to it.label },
        )
    }
}
