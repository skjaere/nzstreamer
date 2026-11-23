package io.skjaere.usenet.app.settings

import io.skjaere.usenet.data.ProviderRecord
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/** A double tap on Save runs save() twice with the same form before the screen closes. */
class SaveSettingsFormTest {
    private val settings = memorySettings()

    @Test
    fun `saving a new provider twice keeps one provider`() = runBlocking<Unit> {
        val form = newProviderForm().copy(host = "news.example.net", username = "user", password = "hunter2")
        val ids = listOf(async { settings.save(form) }, async { settings.save(form) }).awaitAll()
        assertEquals(1, settings.providers.first().size)
        assertEquals(1, ids.distinct().size)
        assertEquals("hunter2", settings.provider(ids.first())?.password)
    }

    // S9-4: before providers had names, saving one stored its host as its name.
    @Test
    fun `a provider saved with its host as its name, then given a new host, goes by the new host`() = runBlocking<Unit> {
        val id = settings.saveProvider(ProviderRecord("", "news.example.net", "news.example.net", 563, "user"), "pw")
        val form = providerForm(settings.provider(id)!!)
        settings.save(form.copy(host = "eu.news.example.net"))
        assertEquals("eu.news.example.net", settings.providers.first().single().displayName)
        // The Name field starts empty: the host stands in for a name, as for one saved without.
        assertEquals("", form.name)
    }

    @Test
    fun `saving a provider that is switched off keeps it off, in its place`() = runBlocking<Unit> {
        val first = settings.save(newProviderForm().copy(host = "news.example.net", username = "user", password = "pw1"))
        val second = settings.save(newProviderForm().copy(host = "block.example.com", username = "user", password = "pw2"))
        settings.setProviderEnabled(first, false)
        val form = providerForm(settings.provider(first)!!).copy(name = "Main")
        settings.save(form)
        assertEquals(listOf(first to false, second to true), settings.providers.first().map { it.id to it.enabled })
        assertEquals("Main", settings.providers.first().first().name)
        assertEquals("pw1", settings.provider(first)?.password)
    }

    @Test
    fun `a new provider goes last`() = runBlocking<Unit> {
        val first = settings.save(newProviderForm().copy(host = "news.example.net"))
        val second = settings.save(newProviderForm().copy(host = "block.example.com"))
        settings.moveProvider(second, by = -1)
        val third = settings.save(newProviderForm().copy(host = "third.example.com"))
        assertEquals(listOf(second, first, third), settings.providers.first().map { it.id })
    }

    @Test
    fun `an indexer edited in its form keeps its place`() = runBlocking<Unit> {
        val first = settings.save(newIndexerForm().copy(url = "https://one.example.org/api", apiKey = "K1"))
        val second = settings.save(newIndexerForm().copy(url = "https://two.example.org/api", apiKey = "K2"))
        val saved = settings.indexer(first)!!
        settings.save(IndexerForm(first, "Renamed", saved.record.baseUrl, "", hasSavedKey = true, enabled = saved.record.enabled))
        assertEquals(listOf(first, second), settings.indexers.first().map { it.id })
        assertEquals("K1", settings.indexer(first)?.apiKey)
    }

    /**
     * L550: the form shows the User-Agent, so Save keeps what it holds, trimmed; emptied, the indexer has none of its own.
     * Opened again, the field holds the saved value, so an edit that leaves it keeps it.
     */
    @Test
    fun `an indexer's User-Agent is saved trimmed, empty means none, and an edit that leaves it keeps it`() = runBlocking<Unit> {
        val id = settings.save(newIndexerForm().copy(url = "https://one.example.org/api", apiKey = "K1", userAgent = "  ExampleReader/2.1 "))
        assertEquals("ExampleReader/2.1", settings.indexer(id)?.record?.userAgent)

        val loaded = indexerForm(settings.indexer(id)!!)
        assertEquals(IndexerForm(id, "one.example.org", "https://one.example.org/api", "", hasSavedKey = true, enabled = true, userAgent = "ExampleReader/2.1"), loaded)
        settings.save(loaded.copy(name = "Renamed", enabled = false))
        assertEquals(Triple("Renamed", false, "ExampleReader/2.1"), settings.indexer(id)!!.record.let { Triple(it.name, it.enabled, it.userAgent) })
        assertEquals("K1", settings.indexer(id)?.apiKey)

        settings.save(indexerForm(settings.indexer(id)!!).copy(userAgent = " \t"))
        assertEquals("", settings.indexer(id)?.record?.userAgent)
        assertEquals("", indexerForm(settings.indexer(id)!!).userAgent)
    }

    @Test
    fun `saving a new indexer twice keeps one indexer`() = runBlocking<Unit> {
        val form = newIndexerForm().copy(url = "https://indexer.example.org/api", apiKey = "KEY")
        val ids = listOf(async { settings.save(form) }, async { settings.save(form) }).awaitAll()
        assertEquals(1, settings.indexers.first().size)
        assertEquals(1, ids.distinct().size)
        assertEquals("KEY", settings.indexer(ids.first())?.apiKey)
    }
}
