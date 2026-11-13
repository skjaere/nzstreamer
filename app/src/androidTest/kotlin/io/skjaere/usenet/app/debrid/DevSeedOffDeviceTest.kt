package io.skjaere.usenet.app.debrid

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.container
import io.skjaere.usenet.debrid.ServiceKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/**
 * L182: a device run never reaches the developer's real accounts unless asked (`debridLive=true`). The runner turns debug's
 * debrid seeding off before the app starts and points the services at a closed address: so the debug build installed for
 * the run, whatever local.properties gave it, saved no service account or torrent indexer, and a seeding asked for now
 * (with invented values) saves nothing either.
 */
@RunWith(AndroidJUnit4::class)
class DevSeedOffDeviceTest {
    private val container = InstrumentationRegistry.getInstrumentation().targetContext.container
    private val settings = container.settings

    @Test
    fun aRunWithoutDebridLiveSeedsNothingAndReachesNoService() = runBlocking<Unit> {
        container.devSettingsSaved.await()
        assertFalse("the runner left debrid seeding on", DevServices.seeding)
        ServiceKind.entries.forEach { kind -> assertEquals("$kind's address", ServiceEndpoints.CLOSED, ServiceEndpoints.of(kind)) }
        assertEquals("a service account saved", emptyList<Any>(), settings.services.first())
        assertEquals("a torrent indexer saved", emptyList<Any>(), settings.torrentIndexers.first())

        val before = settings.settings.first()
        seedDevDebrid(settings, "TorBox\tinvented-dev-key", "Quarry\thttp://192.0.2.7:9696/1\tinvented-indexer-key")
        assertEquals(before, settings.settings.first())
    }
}
