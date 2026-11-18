package io.skjaere.usenet.app.download

import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.FakeTmdb
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.debrid.DebridFakes
import io.skjaere.usenet.app.debrid.DebridGate
import io.skjaere.usenet.app.everyStep
import io.skjaere.usenet.data.AppSettings
import io.skjaere.usenet.data.ServiceSignIn
import io.skjaere.usenet.debrid.ServiceKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue

/**
 * A download case's sources over the fakes (L422, L464): the fake TorBox account the only service account on, and nothing
 * else of the device's own. Every saved service account and torrent indexer is hidden for the case (tombstoned, their keys
 * untouched), every Usenet indexer switched off, and, where [providers] is false, the Usenet providers hidden too, so the
 * account is the only source. The debrid fake is held and reset and the services pointed at it before the account is
 * signed in; the Torznab fake's Prowlarr feed is the one torrent indexer where [torznab]. Where [tmdb], TMDB's seam is
 * strict: anything asked of it is refused, never sent, and fails the case at [close] (a class with a strict seam of its
 * own passes false). [close] puts each back as it was, the case's own account and torrent indexer removed with their
 * keys, whatever the step before it did.
 */
internal class FakeAccountOnly(private val torznab: Boolean, private val providers: Boolean = true, private val tmdb: Boolean = true) : AutoCloseable {
    private val container = InstrumentationRegistry.getInstrumentation().targetContext.container
    private val settings = container.settings
    private lateinit var saved: AppSettings
    private var strict: FakeTmdb? = null
    private var giveBack: (() -> Unit)? = null
    private var pointBack: (() -> Unit)? = null
    private var account: String? = null
    private var indexer: String? = null

    fun open() = runBlocking<Unit> {
        assumeTrue("debrid is hidden in this build", DebridGate.shown)
        DebridFakes.assumeDebrid()
        if (torznab) DebridFakes.assumeTorznab()
        // The debug build's own settings saved first, or they could land after the case took them away.
        container.devSettingsSaved.await()
        saved = settings.settings.first()
        settings.restore(
            saved.copy(
                services = saved.services.map { it.copy(deleted = true) },
                torrentIndexers = saved.torrentIndexers.map { it.copy(deleted = true) },
                providers = if (providers) saved.providers else saved.providers.map { it.copy(deleted = true) },
            ),
        )
        settings.indexers.first().filter { it.enabled }.forEach { settings.setIndexerEnabled(it.id, false) }
        if (tmdb) strict = FakeTmdb(strict = true)
        if (torznab) indexer = settings.saveTorrentIndexer(null, "Home Prowlarr", DebridFakes.PROWLARR_URL, DebridFakes.TORZNAB_KEY)
        giveBack = DebridFakes.holdTheFake()
        DebridFakes.control("reset")
        pointBack = DebridFakes.pointServicesAtTheFake()
        account = settings.saveService(ServiceKind.TorBox.name, DebridFakes.TORBOX_KEY) { ServiceSignIn(DebridFakes.TORBOX_ACCOUNT) }
    }

    /** The fake TorBox account's id, as the settings saved it at [open]. */
    val accountId: String get() = checkNotNull(account) { "not open" }

    /** Switches the fake's account off, as its page's "Use this service" does, or back on. */
    fun accountOn(on: Boolean) = runBlocking<Unit> { account?.let { settings.setServiceEnabled(it, on) } }

    override fun close() = runBlocking<Unit> {
        everyStep(
            { giveBack?.let { DebridFakes.control("reset") } },
            { giveBack?.invoke() },
            { pointBack?.invoke() },
            {
                if (::saved.isInitialized) {
                    // The keys go with them: restore only puts back the settings document.
                    account?.let { settings.signOut(it) }
                    indexer?.let { settings.deleteTorrentIndexer(it) }
                    settings.restore(saved)
                    // The app's own provider sync puts the engine back on the providers put back; let it before the next case.
                    if (!providers) delay(1_000)
                }
            },
            { strict?.close() },
        )
    }
}
