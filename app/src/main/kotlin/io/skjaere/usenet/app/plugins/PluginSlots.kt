package io.skjaere.usenet.app.plugins

import io.skjaere.usenet.app.HomeRoute
import io.skjaere.usenet.app.PluginsRoute
import io.skjaere.usenet.app.SettingsRoute
import kotlin.reflect.KClass

/** What the plugin slots do with the app's navigation, which AppNav does with its NavController. */
interface SlotMoves {
    /**
     * The route types of the entries on the current back stack, and of those still drawn as they leave (a page's exit
     * transition); only the plugin slots' and Choose release's need be there.
     */
    fun shown(): List<KClass<*>>

    /** The route types of the entries on the current back stack alone, not those only drawn as they leave. */
    fun stacked(): List<KClass<*>>

    /** Goes to a top-level tab as the bar and the rail do ([io.skjaere.usenet.app.switchTab]). */
    fun switchTab(route: Any, restore: Boolean = true)

    /** Opens [route] over the current screen. */
    fun navigate(route: Any)

    /** Clears slot [slot]'s saved stack, so its tab opens at its own page again. */
    fun clear(slot: Int)
}

/**
 * Open Plugins from a plugin's slot (L590): the slot is left first, so no entry of it is on the current stack while
 * Plugins shows (L581), then Settings › Plugins with plugin [id]'s card in view (its first button focused on a TV). On a
 * phone, where Settings isn't a tab, Home, then Settings over it; on a TV the Settings tab, at its own page, so Back from
 * Plugins goes to Settings, as from its hub row.
 */
fun SlotMoves.openPlugins(id: String, tv: Boolean) {
    if (tv) {
        switchTab(SettingsRoute, restore = false)
    } else {
        switchTab(HomeRoute)
        navigate(SettingsRoute)
    }
    navigate(PluginsRoute(focus = id))
}

/**
 * Whether an entry of slot [slot] is on the current back stack, or still drawn. The host's Choose release counts too: it
 * holds a plugin's releases and file picker for its play (L635), and is pushed over that plugin's page, so while one
 * shows, every slot waits.
 */
fun SlotMoves.onStack(slot: Int): Boolean {
    val shown = shown()
    return PluginReleasesRoute::class in shown || own(slot, shown)
}

/** Whether one of slot [slot]'s own destinations is among the [shown] route types. */
internal fun SlotMoves.own(slot: Int, shown: List<KClass<*>> = shown()): Boolean {
    val types = PluginSlots.of(slot).types
    return shown.any { it in types }
}

/**
 * Remove pressed on Settings › Plugins (L581), which is never on a slot's stack: should an entry of [slot] still be on the
 * current stack (a way in not foreseen), Home first, which saves the slot's stack for its clear, so the removal never
 * waits for the slot to leave.
 */
fun SlotMoves.beforeRemove(slot: Int) {
    if (own(slot)) switchTab(HomeRoute)
}

/**
 * The plugin slots' side of the app's navigation (L581, L590). AppNav calls [check] with the registry's plugins as they
 * change and whenever its back stack does. For each slot:
 * - While an entry of the slot is on the current back stack or still drawn (its tab, its pages, its settings, or the
 *   host's Choose release pushed over them, [onStack]), its plugin waits; a slot no plugin holds with an entry on the
 *   back stack (its plugin removed in a process that died) goes Home.
 * - Off the screen, its plugin is at its swap point ([swapPoint], the registry's): an update takes over, a withdrawn
 *   version goes. Asked on every change, so a read that finds work while Settings shows is taken at once too.
 * - Its saved stack is cleared once the slot is off the screen whenever the version that runs in it changed: at the swap
 *   point, or some other way the slots don't see (an error lets a version go at once, Install again's newer version takes
 *   over through the registry itself, Turn on again, Remove); and for a slot no plugin holds. So no page comes back that
 *   another version, or none, drew. A plugin's first load in a process keeps it: a stack saved across a process death
 *   opens the same plugin (L582).
 * [latest]: the registry's plugins as they stand, read after the swap point answered.
 */
class PluginSlotWatch(
    private val swapPoint: suspend (String) -> Boolean,
    private val moves: SlotMoves,
    private val latest: () -> List<InstalledPlugin>?,
) {
    /** What each slot last held: by slot number, null before the registry's files were read. */
    private val seen = arrayOfNulls<Held>(PluginSlots.all.size + 1)

    /** The slots whose saved stack is to be cleared once they are off the screen. */
    private val dirty = BooleanArray(PluginSlots.all.size + 1)

    /** Whether an empty slot's saved stack was cleared since it was last bound: once is enough. */
    private val clearedEmpty = BooleanArray(PluginSlots.all.size + 1)

    /** Whether an entry of slot [slot] is on the current back stack, or still drawn ([SlotMoves.onStack]). */
    fun onStack(slot: Int): Boolean = moves.onStack(slot)

    /** Remove pressed on Settings › Plugins ([SlotMoves.beforeRemove]). */
    fun beforeRemove(slot: Int) = moves.beforeRemove(slot)

    /** The registry's [plugins] (null until it read its files: nothing is known then), or the back stack, changed. */
    suspend fun check(plugins: List<InstalledPlugin>?) {
        if (plugins == null) return
        for (slot in PluginSlots.all) {
            val n = slot.number
            val plugin = plugins.firstOrNull { it.slot == n }
            note(n, plugin)
            // A slot no plugin holds has nothing to draw: Home, then its stack is cleared once it is off the screen. Only for
            // an entry on the back stack: one still drawn as it leaves (as clearing a saved stack restores and pops it) is
            // going already, and Home, itself a change, would be asked again at every change while it is drawn.
            if (plugin == null && moves.own(n, moves.stacked())) moves.switchTab(HomeRoute)
            if (moves.onStack(n)) continue
            if (plugin != null && swapPoint(plugin.id)) {
                // Its own swap: what the registry lists now is the new version's, not a change made some other way.
                note(n, latest()?.firstOrNull { it.slot == n }, quiet = true)
                dirty[n] = true
            }
            if (plugin == null && !clearedEmpty[n]) dirty[n] = true
            if (dirty[n] && !moves.onStack(n)) {
                moves.clear(n)
                dirty[n] = false
                if (plugin == null) clearedEmpty[n] = true
            }
        }
    }

    /** Keeps what slot [n] holds now ([plugin]); a change of the version that runs there marks it dirty, unless [quiet]. */
    private fun note(n: Int, plugin: InstalledPlugin?, quiet: Boolean = false) {
        val before = seen[n]
        val now = Held.of(plugin, before)
        seen[n] = now
        if (plugin != null) clearedEmpty[n] = false
        if (!quiet && before != null && now.changedFrom(before)) dirty[n] = true
    }

    /**
     * What a slot holds: the plugin's [id], the [key] of the instance that runs there, and whether it was [off] since its
     * last instance ran (a crash, an error, a withdrawal, a refusal, a failed load or plugins off), through any wait to load.
     */
    private class Held(val id: String?, val key: Long?, val off: Boolean) {
        /**
         * Another plugin; the instance that ran let go, or replaced; or one running after it was off. Not a version that
         * loads for the first time in this process: what was saved before it may still be its own.
         */
        fun changedFrom(before: Held): Boolean = id != before.id || (before.key != null && key != before.key) || (before.off && key != null)

        companion object {
            fun of(plugin: InstalledPlugin?, before: Held?): Held {
                val status = plugin?.status ?: return Held(null, null, off = false)
                val key = (status as? PluginStatus.Running)?.instance?.key
                val off = when (status) {
                    is PluginStatus.Running -> false
                    // Loading again: still off, if it was, until an instance runs.
                    PluginStatus.Waiting -> before?.takeIf { it.id == plugin.id }?.off ?: false
                    else -> true
                }
                return Held(plugin.id, key, off)
            }
        }
    }
}
