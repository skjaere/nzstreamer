package io.skjaere.usenet.app.play

import io.skjaere.usenet.app.collection.model.shortDate
import io.skjaere.usenet.core.AttemptFailure
import io.skjaere.usenet.core.MissingOn
import io.skjaere.usenet.core.PlayCandidate
import io.skjaere.usenet.core.ProviderConfig
import io.skjaere.usenet.core.TriedReleases
import io.skjaere.usenet.data.KnownMissing
import io.skjaere.usenet.data.LibraryRepository
import io.skjaere.usenet.data.ReleaseChecks
import io.skjaere.usenet.data.TriedRelease
import io.skjaere.usenet.newznab.NewznabItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * The releases found missing on the providers (Settings › Cache's "Releases found incomplete"), by
 * indexer and guid, those that needed a password they didn't come with, and those whose files were a
 * copy of a disc (W103): a play skips one missing on every provider in use, one that needed a
 * password, or a disc copy, without grabbing it, and one
 * found missing now is remembered for the providers in use ([inUse], the engine's) that were asked,
 * so a provider added or edited since is one it is tried on again; one that plays is forgotten for
 * them. The release list marks a release by the providers in use once they are checked
 * ([checkedInUse], the engine's): until then the engine names every configured one, one that can't
 * serve included (TR-11). A play looks one up by those in use as they are named now, once it has had
 * the engine check them as it begins ([forPlay]). [store] keeps them; [now] and [zone] date the checks.
 */
class MissingReleases(
    private val store: Store,
    private val inUse: () -> List<ProviderConfig>,
    private val checkedInUse: suspend () -> List<ProviderConfig>,
    private val now: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    /**
     * Where they are kept: the library's table ([of]). [observe] is [get] again whenever they change. [found] forgets a
     * release's checks of the providers it just played with, and that it needed a password. [recordPasswordNeeded]
     * remembers that it needed one it didn't come with; [recordDiscCopy], that its files were a copy of a disc.
     */
    interface Store {
        suspend fun get(ids: Collection<String>): Map<String, TriedRelease>
        fun observe(ids: Collection<String>): Flow<Map<String, TriedRelease>>
        suspend fun record(indexerId: String, guid: String, providers: Collection<String>, incomplete: Boolean)
        suspend fun found(indexerId: String, guid: String, providers: Collection<String>)
        suspend fun recordPasswordNeeded(indexerId: String, guid: String)
        suspend fun recordDiscCopy(indexerId: String, guid: String)

        companion object {
            fun of(library: LibraryRepository) = object : Store {
                override suspend fun get(ids: Collection<String>) = library.triedReleases(ids)

                override fun observe(ids: Collection<String>) = library.observeTriedReleases(ids)

                override suspend fun record(indexerId: String, guid: String, providers: Collection<String>, incomplete: Boolean) {
                    library.recordMissing(indexerId, guid, providers, incomplete)
                }

                override suspend fun found(indexerId: String, guid: String, providers: Collection<String>) {
                    library.recordFound(indexerId, guid, providers)
                }

                override suspend fun recordPasswordNeeded(indexerId: String, guid: String) {
                    library.recordPasswordNeeded(indexerId, guid)
                }

                override suspend fun recordDiscCopy(indexerId: String, guid: String) {
                    library.recordDiscCopy(indexerId, guid)
                }
            }
        }
    }

    private fun identities(providers: List<ProviderConfig>) = providers.map { ReleaseChecks.provider(it.id, it.host, it.port) }

    /**
     * What is known of the releases [keys] ([io.skjaere.usenet.search.RankedItem.key]) with the providers in use, chosen or
     * not: what the release list marks. Again whenever what is remembered changes, as a play from the list or a gap found
     * playing changes it while the list stays up.
     */
    fun known(keys: Collection<String>): Flow<Map<String, KnownMissing>> = store.observe(keys).map { entries ->
        val providers = identities(checkedInUse())
        val at = now()
        entries.mapNotNull { (key, entry) -> ReleaseChecks.known(entry, providers, at)?.let { key to it } }.toMap()
    }

    /**
     * What the pipeline skips and remembers in a play of [items] (by candidate id): those [chosen] by
     * the user are tried whatever is known of them, and one that plays is forgotten for the providers
     * in use. A release found missing is remembered for the providers in use when it was looked up
     * before its attempt, as [record] asks. [indexerName] names an item's indexer in the log.
     *
     * A lookup goes by the providers in use as they are named now ([inUse]): the caller has the engine
     * check them before the play's first (TR-11), and no lookup checks them again. The pipeline also
     * looks releases up to count those left to try, as an attempt starts and once the attempts run out,
     * and the sheet waits on that: a check due since a use failed
     * ([io.skjaere.usenet.core.UsenetEngine.recheckProviders]) would hold it on the last release's steps
     * while the engine logs in to each provider. The next use checks them anyway.
     */
    fun forPlay(items: Map<String, NewznabItem>, chosen: Boolean, indexerName: (indexerId: String) -> String?): TriedReleases = object : TriedReleases {
        /** The providers in use as each candidate (by id) was looked up. The pipeline asks of one candidate at a time. */
        private val asked = mutableMapOf<String, List<ProviderConfig>>()

        override suspend fun knownMissing(candidate: PlayCandidate): String? {
            val item = items[candidate.id] ?: return null
            val key = ReleaseChecks.key(item.indexerId, item.guid) ?: return null
            val providers = inUse().also { asked[candidate.id] = it }
            val at = now()
            return ReleaseChecks.skip(store.get(listOf(key))[key], identities(providers), at, chosen)?.let { skippedLine(it, at, zone()) }
        }

        override suspend fun missing(candidate: PlayCandidate, on: MissingOn) {
            val item = items[candidate.id] ?: return
            record(item.indexerId, item.guid, item.title, indexerName(item.indexerId), on, asked[candidate.id] ?: inUse())
        }

        override suspend fun found(candidate: PlayCandidate) {
            val item = items[candidate.id] ?: return
            ReleaseChecks.key(item.indexerId, item.guid) ?: return
            store.found(item.indexerId, item.guid, identities(inUse()))
        }

        override suspend fun passwordNeeded(candidate: PlayCandidate) {
            val item = items[candidate.id] ?: return
            ReleaseChecks.key(item.indexerId, item.guid) ?: return
            store.recordPasswordNeeded(item.indexerId, item.guid)
            log.info("{} from {} needs a password it didn't come with", item.title, indexerName(item.indexerId) ?: item.indexerId)
        }

        override suspend fun discCopy(candidate: PlayCandidate) {
            val item = items[candidate.id] ?: return
            ReleaseChecks.key(item.indexerId, item.guid) ?: return
            store.recordDiscCopy(item.indexerId, item.guid)
            log.info("{} from {} is a disc copy", item.title, indexerName(item.indexerId) ?: item.indexerId)
        }
    }

    /**
     * Of [items] (by candidate id), those a play of them would skip before grabbing their NZB, as [forPlay] tells it with
     * the providers in use: found missing on them, or needing a password they didn't come with; none where the user
     * [chosen] them. Try a smaller release offers none of those, which the play would pass straight over (L8).
     */
    suspend fun skipped(items: Map<String, NewznabItem>, chosen: Boolean): Set<String> {
        val keys = items.mapNotNull { (id, item) -> ReleaseChecks.key(item.indexerId, item.guid)?.let { id to it } }.toMap()
        if (keys.isEmpty()) return emptySet()
        val entries = store.get(keys.values)
        val providers = identities(inUse())
        val at = now()
        return keys.filterValues { key -> ReleaseChecks.skip(entries[key], providers, at, chosen) != null }.keys
    }

    /**
     * Remembers [indexerId]/[guid] ([title], from the indexer named [indexerName]) as missing [on] the
     * providers [asked] (those in use when the work that found the gap began: the engine's now, unless
     * the caller read them then), or as an NZB that lacks files; logged. Nothing for a release without
     * indexer or guid (an imported NZB), nor while no provider is in use. Nor while others are in use
     * now (one added or edited in Settings, one left out or back): which were asked isn't known, as work
     * begun on a streamer goes on with it once another replaces it (S9-7). A gap found fetching articles
     * ([MissingOn.ProvidersFetchedFrom]) is remembered only while one provider is in use: with several,
     * one may have been passed over without being asked.
     */
    suspend fun record(indexerId: String, guid: String, title: String, indexerName: String?, on: MissingOn, asked: List<ProviderConfig> = inUse()) {
        ReleaseChecks.key(indexerId, guid) ?: return
        val indexer = indexerName ?: indexerId
        when (on) {
            MissingOn.AnyProvider -> {
                store.record(indexerId, guid, emptyList(), incomplete = true)
                log.info("{}", recordedLine(title, indexer, null))
            }
            MissingOn.ProvidersInUse, MissingOn.ProvidersFetchedFrom -> {
                if (asked.isEmpty() || (on == MissingOn.ProvidersFetchedFrom && asked.size > 1)) return
                if (identities(inUse()).toSet() != identities(asked).toSet()) {
                    log.info("{} from {} not remembered as missing: the providers in use changed meanwhile", title, indexer)
                    return
                }
                store.record(indexerId, guid, identities(asked), incomplete = false)
                log.info("{}", recordedLine(title, indexer, asked))
            }
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(MissingReleases::class.java)
    }
}

/** The play sheet's line for a release skipped as [known] at [now]: "Skipped: missing on your providers (checked 3 days ago)". */
internal fun skippedLine(known: KnownMissing, now: Long, zone: ZoneId): String {
    val days = ChronoUnit.DAYS.between(dateOf(known.checkedAt, zone), dateOf(now, zone))
    val ago = when (days) {
        0L -> "today"
        1L -> "yesterday"
        else -> "$days days ago"
    }
    val why = when {
        known.discCopy -> DISC_COPY
        known.incompleteNzb -> "incomplete NZB"
        known.passwordNeeded -> PASSWORD_NEEDED
        else -> "missing on your providers"
    }
    return "$SKIPPED$why (checked $ago)"
}

/**
 * The play sheet's notice while it tries the first release after skipping those whose [skippedLine]s are [skipped], and
 * those a debrid service failed before, whose lines are the failure's words ([io.skjaere.usenet.app.debrid.ServiceMisses]):
 * "no longer cached" of those alone, else that the debrid service failed them (DEP-8: too large, removed, no link). Disc
 * copies, from either source, are said so alone; among others, no one reason says them all (L314).
 */
internal fun skippedNotice(skipped: List<String>): String {
    val count = skipped.size
    val releases = "$count release${if (count == 1) "" else "s"}"
    // Before Usenet's: a torrent's disc line starts as Usenet's lines do.
    val discs = skipped.count(::discSkip)
    if (discs == count) return if (count == 1) "Skipped 1 release that is a disc copy" else "Skipped $releases that are disc copies"
    if (discs > 0) return "Skipped $releases that failed before"
    val service = skipped.filterNot(::usenetSkip)
    val notCached = service.all { it == AttemptFailure.NoLongerCached.text }
    if (service.size == count) return if (notCached) "Skipped $releases no longer cached" else "Skipped $releases the debrid service failed before"
    val passwords = skipped.count { it.startsWith("$SKIPPED$PASSWORD_NEEDED") }
    val what = when (passwords) {
        0 -> "found incomplete"
        count - service.size -> "that needed a password"
        else -> "found incomplete or needing a password"
    }
    val failed = when {
        service.isEmpty() -> ""
        notCached -> " or no longer cached"
        else -> " or that the debrid service failed"
    }
    return "Skipped $releases $what$failed before"
}

/** Whether [line], a skipped release's on the play sheet, is Usenet's ([skippedLine]), not a debrid service's failure's words. */
internal fun usenetSkip(line: String): Boolean = line.startsWith(SKIPPED)

/** Whether [line], a skipped release's on the play sheet, says it is a disc copy ([skippedLine]), a Usenet release or a torrent. */
private fun discSkip(line: String): Boolean = line.startsWith("$SKIPPED$DISC_COPY")

private const val SKIPPED = "Skipped: "

private const val PASSWORD_NEEDED = "needs a password"

private const val DISC_COPY = "a disc copy"

/**
 * The release list's chip on a release [known] to be missing: "Missing on your providers · 14 Oct", "Needs a password · 14 Oct",
 * "Disc copy · 7 Nov" or "Incomplete NZB".
 */
internal fun missingChip(known: KnownMissing, today: LocalDate, zone: ZoneId): String = when {
    known.discCopy -> "Disc copy · ${shortDate(dateOf(known.checkedAt, zone), today)}"
    known.incompleteNzb -> "Incomplete NZB"
    known.passwordNeeded -> "Needs a password · ${shortDate(dateOf(known.checkedAt, zone), today)}"
    else -> "Missing on your providers · ${shortDate(dateOf(known.checkedAt, zone), today)}"
}

/** The log's line for [title] from [indexer] remembered as missing on [providers] (by name, else host), or, without, as an NZB that lacks files. */
internal fun recordedLine(title: String, indexer: String, providers: List<ProviderConfig>?): String =
    if (providers == null) "$title from $indexer has an incomplete NZB" else "$title from $indexer missing on ${providers.joinToString { it.name.ifBlank { it.host } }}"

private fun dateOf(millis: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
