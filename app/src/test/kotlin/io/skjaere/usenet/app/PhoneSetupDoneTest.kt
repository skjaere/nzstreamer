package io.skjaere.usenet.app

import io.skjaere.usenet.app.debrid.AddServiceRoute
import io.skjaere.usenet.app.debrid.DebridServicesRoute
import io.skjaere.usenet.app.debrid.ServiceKeyRoute
import io.skjaere.usenet.app.debrid.ServiceRoute
import io.skjaere.usenet.app.debrid.TorrentIndexerRoute
import io.skjaere.usenet.app.debrid.TorrentIndexersRoute
import io.skjaere.usenet.app.debrid.debridPages
import org.junit.jupiter.api.Test
import kotlin.reflect.KClass
import kotlin.test.assertEquals

/**
 * Set up with your phone's Done, once something from the phone was saved (L209, DbTvSignIn 6): the page goes, and so does
 * a form it was opened from, which read its record once and would write its old values over the phone's on Save. A debrid
 * service's key form goes back past Add a service as its own Save does, to Debrid services (or First run) with the
 * service listed, never to a list of the services not signed in to yet. A stack of the routes' classes stands in for the
 * navigation's, the page's own entry gone already.
 */
class PhoneSetupDoneTest {
    private val pages = debridPages(shown = true, register = {}, open = {})
    private val forms = listOf(ProviderRoute::class, IndexerRoute::class, MetadataRoute::class) + pages.setupForms

    /** What is left of [stack], bottom first, once Done closed what it closes under the page. */
    private fun done(vararg stack: KClass<*>): List<KClass<*>> {
        val left = stack.toMutableList()
        closeUnderPhoneSetup(
            atop = { routes -> left.lastOrNull()?.let { it in routes } == true },
            pop = { left.removeAt(left.lastIndex) },
            forms = forms, pickers = pages.setupPickers,
        )
        return left
    }

    @Test
    fun `a key form opened from Add a service closes with it, as its own Save goes back past it`() {
        assertEquals(
            listOf(SettingsRoute::class, DebridServicesRoute::class),
            done(SettingsRoute::class, DebridServicesRoute::class, AddServiceRoute::class, ServiceKeyRoute::class),
        )
        // First run's way in: back on Home.
        assertEquals(listOf(HomeRoute::class), done(HomeRoute::class, AddServiceRoute::class, ServiceKeyRoute::class))
    }

    @Test
    fun `any other form closes alone, and a page opened from no form closes nothing more`() {
        // Replace key's form, opened from the account's page: back on the page.
        assertEquals(listOf(DebridServicesRoute::class, ServiceRoute::class), done(DebridServicesRoute::class, ServiceRoute::class, ServiceKeyRoute::class))
        assertEquals(listOf(SettingsRoute::class, ProvidersRoute::class), done(SettingsRoute::class, ProvidersRoute::class, ProviderRoute::class))
        assertEquals(listOf(TorrentIndexersRoute::class), done(TorrentIndexersRoute::class, TorrentIndexerRoute::class))
        assertEquals(listOf(HomeRoute::class, SettingsRoute::class), done(HomeRoute::class, SettingsRoute::class))
    }
}
