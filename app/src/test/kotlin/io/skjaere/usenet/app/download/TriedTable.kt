package io.skjaere.usenet.app.download

import io.skjaere.usenet.app.play.MissingReleases
import io.skjaere.usenet.data.ReleaseChecks
import io.skjaere.usenet.data.TriedRelease
import io.skjaere.usenet.releases.ReleaseKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The library's table of releases tried, in memory, by key, as the repository keeps it ([MissingReleases.Store.of]): what
 * the app's memory of them ([MissingReleases]) reads and writes for a download's Resolve and its runner, checked at [now].
 */
internal class TriedTable(private val now: Long) : MissingReleases.Store {
    val entries = mutableMapOf<String, TriedRelease>()

    private fun change(indexerId: String, guid: String, block: (TriedRelease?) -> TriedRelease?) {
        val id = ReleaseKey.of(indexerId, guid)
        val entry = block(entries[id])
        if (entry == null) entries -= id else entries[id] = entry
    }

    override suspend fun get(ids: Collection<String>) = ids.mapNotNull { entries[it] }.associateBy { it.id }

    override fun observe(ids: Collection<String>): Flow<Map<String, TriedRelease>> = flowOf(ids.mapNotNull { entries[it] }.associateBy { it.id })

    override suspend fun record(indexerId: String, guid: String, providers: Collection<String>, incomplete: Boolean) =
        change(indexerId, guid) { ReleaseChecks.record(it, indexerId, guid, providers, incomplete, now) }

    override suspend fun found(indexerId: String, guid: String, providers: Collection<String>) =
        change(indexerId, guid) { it?.let { entry -> ReleaseChecks.found(entry, providers, now) } }

    override suspend fun recordPasswordNeeded(indexerId: String, guid: String) =
        change(indexerId, guid) { ReleaseChecks.record(it, indexerId, guid, emptyList(), incomplete = false, now, needsPassword = true) }

    override suspend fun recordDiscCopy(indexerId: String, guid: String) =
        change(indexerId, guid) { ReleaseChecks.record(it, indexerId, guid, emptyList(), incomplete = false, now, discCopy = true) }
}
