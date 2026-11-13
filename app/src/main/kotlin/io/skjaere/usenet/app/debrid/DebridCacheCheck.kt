package io.skjaere.usenet.app.debrid

import io.skjaere.usenet.debrid.InfoHash
import io.skjaere.usenet.debrid.ServiceException
import io.skjaere.usenet.newznab.IndexerConfig
import io.skjaere.usenet.newznab.NewznabItem
import io.skjaere.usenet.releases.Availability
import io.skjaere.usenet.search.CacheCheck
import io.skjaere.usenet.search.CheckedSearch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * The app's cache check (L199): a torrent indexer's answer, its torrents' hashes alone sent to each service account that
 * is on and open ([accounts]: on, its gate not closed by a limit or a refused key), one batch per account, all at once.
 * A torrent is [Availability.Cached] when any account says so, with those that did in [accounts]' order (the user's,
 * L228); [Availability.NotCached] when every account asked says no; [Availability.NotChecked] when none says yes and
 * one couldn't be asked, or didn't answer in time. Each account's answers are kept [keep] in memory, so the same torrents in another list settle at
 * once and only what isn't kept is asked. An indexer whose "Use with your debrid service" is off sends nothing: its
 * torrents are [Availability.NotForService] at once.
 *
 * A torrent settles once every account asked has answered for it, so it lists every account that has it; but one an
 * account has cached waits no more than [grace] from the check's start for the others, and at [deadline], just inside the
 * search's bound ([CheckedSearch.BOUND]), every torrent left settles from the answers in hand. So an account still asking
 * (waiting out its 429s, or slow to answer) never takes another's yes with it: the search's bound would have given those
 * torrents as not checked.
 */
class DebridCacheCheck(
    private val accounts: suspend () -> List<ServiceRegistry.Client>,
    private val clock: Clock = Clock.systemUTC(),
    private val keep: Duration = Duration.ofMinutes(10),
    private val grace: Duration = Duration.ofSeconds(2),
    private val deadline: Duration = CheckedSearch.BOUND.minusMillis(500),
) : CacheCheck {
    /** An account's answer for a hash, and when it gave it. */
    private class Kept(val cached: Boolean, val at: Instant)

    private val log = LoggerFactory.getLogger(DebridCacheCheck::class.java)

    /** By account id, then hash. */
    private val memory = ConcurrentHashMap<String, ConcurrentHashMap<InfoHash, Kept>>()

    override fun check(indexer: IndexerConfig, items: List<NewznabItem>): Flow<List<NewznabItem>> = channelFlow {
        if (!indexer.useWithService) {
            send(items.map { it.copy(availability = Availability.NotForService) })
            return@channelFlow
        }
        val asked = accounts()
        val hashes = items.mapNotNull { item -> InfoHash.parse(item.infoHash)?.let { item to it } }.toMap()
        // A torrent with no hash can't be asked about (the reader leaves such out), nor can any while no account is open.
        val (askable, unaskable) = items.partition { hashes[it] != null && asked.isNotEmpty() }
        if (unaskable.isNotEmpty()) send(unaskable.map { it.copy(availability = Availability.NotChecked) })
        if (askable.isEmpty()) return@channelFlow

        // Each account's answer for each torrent: true or false, or null where it couldn't say.
        val answers = HashMap<InfoHash, HashMap<String, Boolean?>>()
        val waiting = askable.toMutableList()
        val lock = Mutex()
        // Until [grace] is over, a torrent one account has cached still waits for the others' answers.
        var patient = true

        /**
         * The torrents settled now, as they settle; none of them is given again: those every account has answered for, those
         * one has cached once [grace] is over, and with [all] (the [deadline]) every one left, from what is in hand.
         */
        suspend fun settle(all: Boolean = false) {
            val settled = lock.withLock {
                waiting.filter { item ->
                    val said = answers[hashes.getValue(item)].orEmpty()
                    all || said.size == asked.size || !patient && said.values.any { it == true }
                }.also { waiting -= it.toSet() }
                    .map { item -> item.copy(availability = availabilityOf(answers[hashes.getValue(item)].orEmpty(), asked)) }
            }
            if (settled.isNotEmpty()) send(settled)
        }

        val now = clock.instant()
        val missing = asked.associateWith { client ->
            val kept = memory.getOrPut(client.account.id) { ConcurrentHashMap() }
            // What is past its time goes, so the memory holds a few searches' worth at most.
            kept.values.removeIf { Duration.between(it.at, now) >= keep }
            askable.map { hashes.getValue(it) }.distinct().filter { hash ->
                val known = kept[hash]?.takeIf { Duration.between(it.at, now) < keep }
                known?.let { answers.getOrPut(hash) { HashMap() }[client.account.id] = it.cached }
                known == null
            }
        }
        // What the memory answers, at once.
        settle()
        val calls = missing.filterValues { it.isNotEmpty() }
        if (calls.isEmpty()) return@channelFlow
        val answered = withTimeoutOrNull(deadline.toMillis()) {
            coroutineScope {
                val patience = launch {
                    delay(grace.toMillis())
                    lock.withLock { patient = false }
                    settle()
                }
                calls.map { (client, hashesToAsk) ->
                    launch {
                        val said: Map<InfoHash, Boolean>? = try {
                            client.service.check(hashesToAsk)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: ServiceException) {
                            // Its client logged the call; a limit or a refused key closed its gate there.
                            null
                        } catch (e: Exception) {
                            // Not a service's answer: still this account's alone, which mustn't fail the others'.
                            log.warn("debrid: a cache check failed: {}", e.javaClass.simpleName)
                            null
                        }
                        val at = clock.instant()
                        said?.forEach { (hash, cached) -> memory.getValue(client.account.id)[hash] = Kept(cached, at) }
                        lock.withLock {
                            hashesToAsk.forEach { hash -> answers.getOrPut(hash) { HashMap() }[client.account.id] = said?.get(hash) }
                        }
                        settle()
                    }
                }.joinAll()
                patience.cancel()
            }
        }
        // An account still asking at the deadline: the rest settle from the answers in hand, its silence not checked.
        if (answered == null) settle(all = true)
    }

    /**
     * One torrent's [answers] from the accounts [asked], some of whom may not have answered: cached with those that said so,
     * in their order; not cached when every one said no; else not checked.
     */
    private fun availabilityOf(answers: Map<String, Boolean?>, asked: List<ServiceRegistry.Client>): Availability {
        val yes = asked.map { it.account.id }.filter { answers[it] == true }
        return when {
            yes.isNotEmpty() -> Availability.Cached(yes)
            asked.all { answers[it.account.id] == false } -> Availability.NotCached
            else -> Availability.NotChecked
        }
    }
}
