package io.skjaere.usenet.app.plugins

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Star
import io.skjaere.usenet.app.CollectionRoute
import io.skjaere.usenet.app.HomeRoute
import io.skjaere.usenet.app.PlaybackRoute
import io.skjaere.usenet.app.PluginsRoute
import io.skjaere.usenet.app.SearchRoute
import io.skjaere.usenet.app.SettingsRoute
import io.skjaere.usenet.plugin.PluginSecrets
import io.skjaere.usenet.plugin.PluginTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * L581, L582, L590, L618: the four plugin slots in the app's navigation, over given back stacks. A slot's plugin is at
 * its swap point only while no entry of the slot is on the current back stack or still drawn: its tab, its pages, its
 * settings, or the host's Choose release pushed over them. Then an update takes over and the slot's saved stack is
 * cleared, as it is whenever the slot's running version changes some other way (an error, Install again), and for a slot
 * no plugin holds, whose destinations go Home. Open Plugins leaves the slot first, and Remove goes Home first where a
 * slot entry is still on the current stack. The placeholder's words, the tabs the registry's plugins make, and the
 * registry listing a version off before its close() runs, so no page of it is drawn after.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PluginSlotsTest {
    @TempDir
    lateinit var dir: File

    /**
     * The app's back stack as the bar and the rail move it: switchTab saves the current tab's history under its own
     * screen and goes back to Home, then opens the tab, its history restored unless [restore] is false. Home is a tab too:
     * its own pages (a phone's Settings, which isn't a tab there) are its history, kept as Home is switched to from
     * them, and restored as it is from another tab; on a [tv] Settings is a tab of its own. [leaving] holds a page still
     * drawn as it leaves (a transition).
     */
    private class FakeNav(tv: Boolean = false) : SlotMoves {
        val stack = mutableListOf<Any>(HomeRoute)
        val leaving = mutableListOf<Any>()
        val saved = mutableMapOf<KClass<*>, List<Any>>()
        val moves = mutableListOf<String>()

        override fun shown(): List<KClass<*>> = (stack + leaving).map { it::class }

        override fun stacked(): List<KClass<*>> = stack.map { it::class }

        override fun switchTab(route: Any, restore: Boolean) {
            moves += "switchTab ${route::class.simpleName}"
            val pages = stack.drop(1)
            // The current tab's history: a tab's over Home, else Home's own pages, which stay where Home is switched to.
            val tab = pages.firstOrNull()?.let { it::class }?.takeIf { it in tabs } ?: HomeRoute::class
            if (tab == HomeRoute::class && route == HomeRoute) return
            if (pages.isNotEmpty()) saved[tab] = pages
            stack.subList(1, stack.size).clear()
            val restored = saved.remove(route::class).takeIf { restore }
            stack.addAll(restored ?: listOf(route).filter { it != HomeRoute })
        }

        override fun popTo(route: Any) {
            moves += "popTo ${route::class.simpleName}"
            stack.subList(stack.lastIndexOf(route) + 1, stack.size).clear()
        }

        override fun navigate(route: Any) {
            moves += "navigate ${route::class.simpleName}"
            stack += route
        }

        override fun clear(slot: Int) {
            moves += "clear $slot"
            saved.remove(PluginSlots.of(slot).tab::class)
        }

        /** The tabs over Home: the slots', the Collection's, Search's, and a TV's Settings. */
        private val tabs = PluginSlots.all.map { it.tab::class } + listOfNotNull(CollectionRoute::class, SearchRoute::class, SettingsRoute::class.takeIf { tv })
    }

    private object NoSecrets : PluginSecrets {
        override suspend fun get(name: String): String? = null
        override suspend fun put(name: String, value: String?) = Unit
        override fun has(name: String): Flow<Boolean> = flowOf(false)
    }

    private fun running(id: String, key: Long, label: String = "Sports"): PluginStatus {
        val host = HostedPlugin(id, File("unused"), File("unused"), NoSecrets, CoroutineScope(Job()))
        return PluginStatus.Running(PluginInstance(PluginVersion(id, 1, key), FakePlugin(label), host, PluginTab(label, Icons.Filled.Star)))
    }

    private fun installed(
        id: String = "skeleton", slot: Int = 1, status: PluginStatus = running(id, key = 1), label: String? = "Sports", name: String = "Sportarr",
        waitingVersion: Long? = null, versionName: String = "1.1",
    ) = InstalledPlugin(
        id, name, slot, 11, versionName, label, status, waitingVersion = waitingVersion, failedUpdate = null, lastUpdate = null,
        icon = (status as? PluginStatus.Running)?.instance?.tab?.icon,
    )

    /**
     * The registry's swap point as the slots call it: each call kept; [answer] says whether something changed. As the
     * registry waits for its start's load and its lock, [whileWaiting] runs; then, as the registry does once it holds the
     * lock, the slot is asked whether it is still off the screen, and nothing changes where it isn't.
     */
    private class Swaps(var answer: (String) -> Boolean = { false }) {
        val calls = mutableListOf<String>()
        var whileWaiting: () -> Unit = {}

        suspend fun swap(id: String, stillOff: () -> Boolean): Boolean {
            calls += id
            whileWaiting()
            if (!stillOff()) return false
            return answer(id)
        }
    }

    private fun watch(nav: FakeNav, swaps: Swaps, latest: () -> List<InstalledPlugin>? = { null }) = PluginSlotWatch(swaps::swap, nav, latest)

    @Test
    fun `a slot entry on the current stack waits, and none swaps, its saved stack then cleared`() = runTest {
        val nav = FakeNav()
        var plugins = listOf(installed(waitingVersion = 12))
        val swaps = Swaps { id ->
            // The registry swaps while an update waits: a new instance runs, and nothing waits any more.
            val waiting = plugins.single().updateWaiting
            if (waiting) plugins = listOf(installed(id, status = running(id, key = 2)))
            waiting
        }
        val watch = watch(nav, swaps) { plugins }
        nav.switchTab(PluginTab1)
        nav.navigate(PluginPage1("second", null))
        watch.check(plugins)
        assertEquals(emptyList(), swaps.calls, "its tab and its second page show: the update waits")
        assertTrue(watch.onStack(1))

        nav.switchTab(HomeRoute)
        assertEquals(listOf(PluginTab1, PluginPage1("second", null)), nav.saved[PluginTab1::class], "the slot's history saved by the switch")
        watch.check(plugins)
        assertEquals(listOf("skeleton"), swaps.calls, "Home shows: the swap point")
        assertNull(nav.saved[PluginTab1::class], "its saved stack cleared: the new version starts at its tab")
        watch.check(plugins)
        assertEquals(1, nav.moves.count { it == "clear 1" }, "the slots' own swap isn't counted again as the registry lists it")
    }

    @Test
    fun `the host's Choose release over a plugin page counts as the slot's, and so does a page still drawn as it leaves`() = runTest {
        val nav = FakeNav()
        val swaps = Swaps()
        val plugins = listOf(installed(waitingVersion = 12))
        val watch = watch(nav, swaps) { plugins }
        nav.switchTab(PluginTab1)
        nav.navigate(PluginPage1("game", "ev-1"))
        nav.navigate(PluginReleasesRoute("token"))
        watch.check(plugins)
        assertEquals(emptyList(), swaps.calls, "Choose release over the plugin's page")

        nav.switchTab(HomeRoute)
        nav.leaving += PluginReleasesRoute("token")
        watch.check(plugins)
        assertEquals(emptyList(), swaps.calls, "Choose release still drawn as it leaves")
        nav.leaving.clear()
        watch.check(plugins)
        assertEquals(listOf("skeleton"), swaps.calls)
    }

    /**
     * L581: the swap point waits for the start's load and the registry's lock before it acts, and the user may open the
     * slot's tab meanwhile: the update doesn't take over under them, and waits until they leave.
     */
    @Test
    fun `a slot opened while its swap point waited isn't swapped under the user`() = runTest {
        val nav = FakeNav()
        var plugins = listOf(installed(waitingVersion = 12))
        val swaps = Swaps { id ->
            plugins = listOf(installed(id, status = running(id, key = 2)))
            true
        }
        val watch = watch(nav, swaps) { plugins }
        swaps.whileWaiting = { if (nav.stack.size == 1) nav.switchTab(PluginTab1) }
        watch.check(plugins)
        assertEquals(listOf("skeleton"), swaps.calls, "asked while Home showed")
        assertEquals(12L, plugins.single().waitingVersion, "the update still waits: its tab shows")
        assertFalse("clear 1" in nav.moves)

        swaps.whileWaiting = {}
        nav.navigate(PluginPage1("second", null))
        watch.check(plugins)
        assertEquals(listOf("skeleton"), swaps.calls, "its pages show: not asked")
        nav.switchTab(HomeRoute)
        watch.check(plugins)
        assertEquals(null, plugins.single().waitingVersion, "taken over once it left")
        assertNull(nav.saved[PluginTab1::class], "and its stack cleared")
    }

    /** L590: a slot whose plugin was removed in a process that died: its pages, restored, go Home and are cleared. */
    @Test
    fun `an unbound slot goes Home and is cleared`() = runTest {
        val nav = FakeNav()
        val swaps = Swaps()
        val plugins = listOf(installed("kestrel", slot = 1))
        val watch = watch(nav, swaps) { plugins }
        nav.stack.addAll(listOf(PluginTab2, PluginPage2("results", null)))
        nav.saved[PluginTab3::class] = listOf(PluginTab3)
        watch.check(null)
        assertEquals(emptyList(), nav.moves, "nothing before the registry read its files")

        watch.check(plugins)
        assertEquals(listOf<Any>(HomeRoute), nav.stack, "slot 2's pages go Home")
        assertNull(nav.saved[PluginTab3::class], "a free slot's saved stack cleared")
        watch.check(plugins)
        assertNull(nav.saved[PluginTab2::class], "and slot 2's, once it left")
        assertEquals(listOf("kestrel", "kestrel"), swaps.calls, "only the bound slot's plugin asked")
        watch.check(plugins)
        assertEquals(1, nav.moves.count { it == "clear 2" }, "a free slot cleared once")
    }

    /**
     * L590: a page of a slot no plugin holds that is only still drawn as it leaves (clearing a saved stack restores it and
     * pops it again; Settings › Plugins shows) isn't sent Home: it is going already, and Home at every change while it is
     * drawn moved the app again and again, as its own move is a change. Its saved stack is cleared once it has gone.
     */
    @Test
    fun `a slot no plugin holds whose page is only drawn as it leaves isn't sent Home`() = runTest {
        val nav = FakeNav()
        val watch = watch(nav, Swaps())
        nav.navigate(SettingsRoute)
        nav.navigate(PluginsRoute(focus = "skeleton"))
        nav.leaving += PluginTab1
        repeat(3) { watch.check(emptyList()) }
        assertFalse("switchTab HomeRoute" in nav.moves, "Settings › Plugins moved: ${nav.moves}")
        assertEquals(listOf<Any>(HomeRoute, SettingsRoute, PluginsRoute(focus = "skeleton")), nav.stack)
        assertFalse("clear 1" in nav.moves, "not cleared while it is drawn")

        nav.leaving.clear()
        watch.check(emptyList())
        assertEquals(1, nav.moves.count { it == "clear 1" }, "cleared once it has gone")
    }

    /**
     * A slot no plugin holds, its saved stack cleared, then bound again (Remove, then an install that takes the slot): its
     * stack is empty already, so what the user builds in the new plugin's tab stays, whatever is still listed of the old
     * pages as they go (clearing a stack restores them and pops them again).
     */
    @Test
    fun `a slot bound again after its stack was cleared keeps what the user builds there`() = runTest {
        val nav = FakeNav()
        val watch = watch(nav, Swaps())
        nav.navigate(SettingsRoute)
        nav.navigate(PluginsRoute())
        watch.check(listOf(installed("skeleton")))
        watch.check(emptyList())
        assertEquals(1, nav.moves.count { it == "clear 1" }, "removed: its slot's stack cleared")

        nav.leaving += PluginTab1
        val kestrel = listOf(installed("kestrel", status = running("kestrel", key = 5)))
        watch.check(kestrel)
        nav.leaving.clear()
        nav.switchTab(PluginTab1)
        watch.check(kestrel)
        nav.navigate(PluginPage1("second", null))
        nav.switchTab(HomeRoute)
        watch.check(kestrel)
        assertEquals(listOf(PluginTab1, PluginPage1("second", null)), nav.saved[PluginTab1::class], "its history kept")
        assertEquals(1, nav.moves.count { it == "clear 1" })
    }

    /**
     * An error lets its version go at once, and Install again's newer version takes over through the registry itself: the
     * slot's saved stack is cleared once the slot is off the screen, so no page of the old version comes back.
     */
    @Test
    fun `a version let go outside the slots' own swap point clears the slot's saved stack once it is off the screen`() = runTest {
        val nav = FakeNav()
        val swaps = Swaps()
        val watch = watch(nav, swaps)
        nav.switchTab(PluginTab1)
        nav.navigate(PluginPage1("second", null))
        watch.check(listOf(installed(status = running("skeleton", key = 1))))
        watch.check(listOf(installed(status = PluginStatus.OffAfterError)))
        assertFalse("clear 1" in nav.moves, "the slot shows: its stack stays, the placeholder in its pages")

        nav.switchTab(HomeRoute)
        watch.check(listOf(installed(status = PluginStatus.OffAfterError)))
        assertNull(nav.saved[PluginTab1::class], "cleared once it left")

        // Turn on again, then Install again's swap, from Settings › Plugins.
        nav.switchTab(PluginTab1)
        nav.switchTab(HomeRoute)
        watch.check(listOf(installed(status = running("skeleton", key = 2))))
        assertNull(nav.saved[PluginTab1::class], "turned on again: what it showed before is gone")
        nav.switchTab(PluginTab1)
        nav.navigate(PluginPage1("second", null))
        nav.switchTab(HomeRoute)
        watch.check(listOf(installed(status = running("skeleton", key = 2))))
        assertEquals(listOf(PluginTab1, PluginPage1("second", null)), nav.saved[PluginTab1::class], "the same version: its history kept")
        watch.check(listOf(installed(status = running("skeleton", key = 3))))
        assertNull(nav.saved[PluginTab1::class], "a new instance: cleared")
    }

    /** L582: a back stack saved across a process death opens the same plugin: its first load doesn't clear it. */
    @Test
    fun `the first load in a process keeps the slot's saved stack`() = runTest {
        val nav = FakeNav()
        val watch = watch(nav, Swaps())
        nav.saved[PluginTab1::class] = listOf(PluginTab1, PluginPage1("second", null))
        watch.check(listOf(installed(status = PluginStatus.Waiting)))
        watch.check(listOf(installed(status = running("skeleton", key = 1))))
        assertEquals(listOf(PluginTab1, PluginPage1("second", null)), nav.saved[PluginTab1::class])
        assertFalse("clear 1" in nav.moves)
    }

    /** A read that finds an update or a withdrawal while Settings shows: reported then, not at the next navigation. */
    @Test
    fun `work found while the slot is off the screen is reported at once`() = runTest {
        val nav = FakeNav()
        val swaps = Swaps()
        val watch = watch(nav, swaps)
        nav.navigate(SettingsRoute)
        nav.navigate(PluginsRoute())
        watch.check(listOf(installed()))
        watch.check(listOf(installed(waitingVersion = 12)))
        assertEquals(listOf("skeleton", "skeleton"), swaps.calls)
    }

    /** L590, L581: Open Plugins leaves the slot first, so no entry of it is on the current stack while Plugins shows. */
    @Test
    fun `Open Plugins' way from a slot's tab leaves no slot entry on the current stack, phone and TV`() = runTest {
        val phone = FakeNav()
        phone.switchTab(PluginTab1)
        phone.navigate(PluginPage1("second", null))
        phone.openPlugins("skeleton", tv = false)
        assertEquals(listOf<Any>(HomeRoute, SettingsRoute, PluginsRoute(focus = "skeleton")), phone.stack, "Home, then Settings, then Plugins")
        assertFalse(watch(phone, Swaps()).onStack(1))

        val tv = FakeNav(tv = true)
        // The Settings tab left on one of its pages: Plugins still opens over Settings itself, so Back goes there.
        tv.switchTab(SettingsRoute)
        tv.navigate(PlaybackRoute)
        tv.switchTab(PluginTab1)
        tv.openPlugins("skeleton", tv = true)
        assertEquals(listOf<Any>(HomeRoute, SettingsRoute, PluginsRoute(focus = "skeleton")), tv.stack, "the Settings tab, then Plugins")
        assertFalse(watch(tv, Swaps()).onStack(1))
        assertEquals(listOf(PluginTab1), tv.saved[PluginTab1::class], "the slot's history saved, for the swap point to clear")
    }

    /**
     * L590, L581: a slot's page opened over Home's own pages (its card's Settings over Settings › Plugins, on a phone, where
     * Settings isn't a tab), whose Open Plugins Home's tab alone can't leave, as Home keeps its own pages: Home's own screen
     * first, so no entry of the slot is left on the current stack, and Back goes to Settings, then Home.
     */
    @Test
    fun `Open Plugins from a slot's page over Home's own pages leaves no slot entry on a phone`() = runTest {
        val phone = FakeNav()
        phone.navigate(SettingsRoute)
        phone.navigate(PluginsRoute())
        phone.navigate(PluginSettings1)
        phone.openPlugins("skeleton", tv = false)
        assertEquals(listOf<Any>(HomeRoute, SettingsRoute, PluginsRoute(focus = "skeleton")), phone.stack, "Home, then Settings, then Plugins")
        assertFalse(watch(phone, Swaps()).onStack(1))

        // From the slot's tab, Home's own pages that tab was left with go too: Back from Plugins reaches Settings, then Home.
        val left = FakeNav()
        left.navigate(SettingsRoute)
        left.navigate(PluginsRoute())
        left.switchTab(PluginTab1)
        left.openPlugins("skeleton", tv = false)
        assertEquals(listOf<Any>(HomeRoute, SettingsRoute, PluginsRoute(focus = "skeleton")), left.stack, "Settings › Plugins once")
    }

    /** L581: should a slot entry still be on the current stack as Remove is pressed, Remove goes Home first, and never waits. */
    @Test
    fun `Remove with a slot entry still on the current stack goes Home first and doesn't wait`() = runTest {
        val nav = FakeNav()
        val swaps = Swaps()
        val watch = watch(nav, swaps)
        nav.switchTab(PluginTab1)
        nav.navigate(PluginsRoute(focus = "skeleton"))
        watch.check(listOf(installed()))

        watch.beforeRemove(1)
        assertEquals(listOf<Any>(HomeRoute), nav.stack, "Home first")
        nav.moves += "remove"
        watch.check(emptyList())
        val moves = nav.moves.filterNot { it.startsWith("clear") && it != "clear 1" }
        assertEquals(listOf("switchTab PluginTab1", "navigate PluginsRoute", "switchTab HomeRoute", "remove", "clear 1"), moves)
        assertEquals(emptyList(), swaps.calls, "the removal waited for no swap point")
        assertNull(nav.saved[PluginTab1::class])

        // Off the stack already: nothing moves.
        val quiet = FakeNav()
        watch(quiet, Swaps()).beforeRemove(1)
        assertEquals(emptyList(), quiet.moves)
    }

    /** L590 (card 5 and frame 6): the placeholder's page title is the tab's label, its card says why, and what stays. */
    @Test
    fun `the placeholder says why its plugin isn't running`() {
        fun words(status: PluginStatus, label: String? = "Sports") =
            PluginPlaceholder.of(installed(status = status, label = label))?.let { listOf(it.label, it.title, it.line) }
        assertEquals(
            listOf("Sports", "Sports is turned off", "The Sportarr plugin was turned off after it crashed NZStreamer. What you watched is kept."),
            words(PluginStatus.OffAfterCrash),
        )
        assertEquals(
            listOf("Sports", "Sports is turned off", "The Sportarr plugin was turned off after an error. What you watched is kept."),
            words(PluginStatus.OffAfterError),
        )
        assertEquals(
            listOf("Sports", "Sports is turned off", "The Sportarr plugin was turned off: version 1.1 was withdrawn. What you watched is kept."),
            words(PluginStatus.Withdrawn),
        )
        assertEquals(
            listOf("Sports", "Sports couldn't start: its file changed after it was installed.", "What you watched is kept."),
            words(PluginStatus.Refused(Refusal.Hash)),
        )
        assertEquals(
            listOf("Sports", "Sports couldn't start: its code didn't load.", "What you watched is kept."),
            words(PluginStatus.CouldntStart(LoadFailure.Linkage)),
        )
        assertEquals(
            listOf("Sports", "Sports is turned off", "Plugins are off: NZStreamer closed twice just after starting. What you watched is kept."),
            words(PluginStatus.PluginsOff),
        )
        assertEquals("Sportarr", words(PluginStatus.OffAfterCrash, label = null)!!.first(), "never loaded: the list's name")
        assertNull(words(PluginStatus.Waiting), "loading: nothing")
        assertNull(words(running("skeleton", key = 1)), "running: its own page")
        assertEquals("Open Plugins", PluginPlaceholder.OPEN_PLUGINS)
    }

    /** L618: a tab for each plugin that ever loaded, in slot order; Build where no instance gave an icon in this process. */
    @Test
    fun `the plugins' tabs`() {
        assertEquals(emptyList(), pluginTabs(null))
        val tabs = pluginTabs(
            listOf(
                installed("kestrel", slot = 1, status = PluginStatus.OffAfterCrash, label = "Kestrel"),
                installed("harbor", slot = 2, status = PluginStatus.Refused(Refusal.Signer), label = null),
                installed("skeleton", slot = 3, status = running("skeleton", key = 1, label = "Sports")),
            ),
        )
        assertEquals(listOf("Kestrel", "Sports"), tabs.map { it.label }, "a plugin that never loaded has no tab")
        assertEquals(listOf<Any>(PluginTab1, PluginTab3), tabs.map { it.route })
        assertEquals(listOf<KClass<*>>(PluginTab1::class, PluginTab3::class), tabs.map { it.type })
        assertSame(Icons.Filled.Build, tabs[0].icon)
        assertSame(Icons.Filled.Star, tabs[1].icon)
        assertEquals(listOf(1, 2, 3, 4), PluginSlots.all.map { it.number })
        assertEquals(listOf(PluginSettings1, PluginSettings2, PluginSettings3, PluginSettings4), PluginSlots.all.map { it.settings })
        assertEquals(PluginPage4("a", "b"), PluginSlots.of(4).page("a", "b"))
    }

    /** L613's rules for a page the plugin opens: the message names the rule, never the value. */
    @Test
    fun `a page outside its rule is refused, naming the rule alone`() {
        PluginNavImpl.check("second-page", "ev-000731:main-card")
        PluginNavImpl.check("a", null)
        val page = assertFailsWith<IllegalArgumentException> { PluginNavImpl.check("Secret Page", null) }
        assertEquals("A page's name is 1 to 40 lower-case letters, digits and dashes", page.message)
        assertFailsWith<IllegalArgumentException> { PluginNavImpl.check("", null) }
        val arg = assertFailsWith<IllegalArgumentException> { PluginNavImpl.check("page", "hunter2".repeat(40)) }
        assertEquals("A page's argument is at most 200 characters", arg.message)
    }

    /**
     * The KDoc's promise, that none of a plugin's pages is drawn after its version is let go: the registry lists the
     * version as no longer running before its close() runs, at the swap point, after an error, on a withdrawal and on
     * Remove, so a slot that follows the list stops drawing it first.
     */
    @Test
    fun `the registry lists a version off before its close() runs`() = runTest {
        val h = RegistryHarness(dir, this)
        val atClose = mutableListOf<String>()
        lateinit var registry: PluginRegistry
        h.loading.plugin = { id, version ->
            FakePlugin(onClose = {
                val status = registry.plugins.value.orEmpty().firstOrNull { it.id == id }?.status
                val still = (status as? PluginStatus.Running)?.instance?.versionCode == version
                atClose += "$id $version ${if (still) "listed running" else "not listed running"}"
            })
        }
        registry = h.registry().apply { start(); firstActivity() }
        h.install(registry, "skeleton", 1)
        h.install(registry, "kestrel", 1)
        h.install(registry, "skeleton", 2)
        assertTrue(registry.atSwapPoint("skeleton"))
        registry.running("skeleton")!!.host.scope.launch { throw IllegalStateException("the fake's refresh") }
        advanceUntilIdle()
        assertEquals(PluginStatus.OffAfterError, registry.installed("skeleton").status)
        registry.withdraw("kestrel", 1)
        assertTrue(registry.atSwapPoint("kestrel"))
        assertEquals(PluginStatus.Withdrawn, registry.installed("kestrel").status)
        registry.turnOnAgain("skeleton")
        assertNotNull(registry.running("skeleton"))
        registry.remove("skeleton")
        advanceUntilIdle()
        assertEquals(
            listOf("skeleton 1", "skeleton 2", "kestrel 1", "skeleton 2").map { "$it not listed running" },
            atClose,
            "swap, error, withdrawal, Remove",
        )
    }
    /**
     * NzPlugin.close's promise, where a slot still draws a page as its version goes (an error on the page shown, or in a
     * page still drawn as it leaves): close() waits until no composition draws a page of the instance, none begins from
     * then on, and one that never lets go (a composition that no longer runs) holds it back a second at most.
     */
    @Test
    fun `a version is let go only once none of its pages is drawn`() = runTest {
        val h = RegistryHarness(dir, this)
        val made = mutableListOf<FakePlugin>()
        h.loading.plugin = { _, _ -> FakePlugin().also { made += it } }
        val registry = h.registry().apply { start(); firstActivity() }
        h.install(registry, "skeleton", 1)
        val first = registry.running("skeleton")!!
        assertTrue(first.pages.take(), "its page drawn")
        first.host.scope.launch { throw IllegalStateException("the fake's refresh") }
        runCurrent()
        assertEquals(PluginStatus.OffAfterError, registry.installed("skeleton").status, "listed off at once")
        assertFalse("close" in made.single().calls, "closed while its page is drawn")
        assertFalse(first.pages.take(), "a page begun as it goes")
        first.pages.release()
        runCurrent()
        assertEquals(listOf("tab", "close"), made.single().calls, "closed once its page went")

        // A composition that never lets go: a second at most.
        registry.turnOnAgain("skeleton")
        val second = registry.running("skeleton")!!
        assertTrue(second.pages.take())
        second.host.scope.launch { throw IllegalStateException("the fake's refresh") }
        advanceTimeBy(900)
        assertFalse("close" in made.last().calls, "closed before its second")
        advanceTimeBy(200)
        assertTrue("close" in made.last().calls, "closed once its second was up")
    }
}
