package io.skjaere.usenet.search

import io.skjaere.usenet.newznab.IndexerCaps
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.IndexerKind
import io.skjaere.usenet.newznab.IndexerOutcome
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.newznab.ReleaseSearch
import io.skjaere.usenet.newznab.SearchQuery
import io.skjaere.usenet.releases.Availability
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Checks a torrent indexer's answer against the user's debrid services' caches (L199), naming no service: gives [items]
 * back with their [NewznabItem.availability] set, as they settle and each once: those it already knows, and those of an
 * [indexer] whose "Use with your debrid service" is off, at once; the rest as each service answers.
 */
fun interface CacheCheck {
    fun check(indexer: IndexerConfig, items: List<NewznabItem>): Flow<List<NewznabItem>>
}

/**
 * [search], with each torrent indexer's torrents checked as its answer comes (L199, W71): a Usenet indexer's outcome, and
 * any but a torrent indexer's results, pass at once as they came; a torrent indexer's results become
 * [IndexerOutcome.Checking] at once, then an [IndexerOutcome.Checked] each time some of its torrents settle, the last
 * with none waiting. So nothing waits for a check but that answer's own torrents. A check that fails, or runs past
 * [bound], gives what is left as [Availability.NotChecked]: hidden, never tried, and checked again by the next search.
 */
class CheckedSearch(
    private val search: ReleaseSearch,
    private val bound: Duration = BOUND,
    private val check: CacheCheck,
) : ReleaseSearch {
    private val log = LoggerFactory.getLogger(CheckedSearch::class.java)

    override fun search(indexers: List<IndexerConfig>, fresh: Boolean, plan: (IndexerCaps) -> SearchQuery?): Flow<IndexerOutcome> = channelFlow {
        search.search(indexers, fresh, plan).collect { outcome ->
            val items = (outcome as? IndexerOutcome.Results)?.takeIf { it.indexer.kind == IndexerKind.Torznab }?.page?.items.orEmpty()
            if (items.isEmpty()) {
                send(outcome)
                return@collect
            }
            outcome as IndexerOutcome.Results
            val indexer = outcome.indexer
            val waiting = items.associateBy { it.key }.toMutableMap()
            send(IndexerOutcome.Checking(indexer, outcome.query, outcome.page, waiting.size))
            // Each answer's check on its own: the next answer, and the outcome after it, never wait for this one.
            launch {
                try {
                    withTimeoutOrNull(bound.toMillis()) {
                        check.check(indexer, items).collect { chunk ->
                            val settled = chunk.filter { waiting.remove(it.key) != null }
                            if (settled.isNotEmpty()) send(IndexerOutcome.Checked(indexer, settled, waiting.size))
                        }
                    } ?: log.info("checking {}'s torrents: {} unsettled after {} s", indexer.name, waiting.size, bound.seconds)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The check's own failure: its torrents can't be said to be cached, and the search goes on.
                    log.warn("checking {}'s torrents failed: {}", indexer.name, e.javaClass.simpleName)
                }
                if (waiting.isNotEmpty()) send(IndexerOutcome.Checked(indexer, waiting.values.map { it.copy(availability = Availability.NotChecked) }, 0))
            }
        }
    }

    companion object {
        /** How long a torrent indexer's answer waits for its check at most; the play's own pick waits no longer (L199). */
        val BOUND: Duration = Duration.ofSeconds(10)
    }
}
