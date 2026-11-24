package io.skjaere.usenet.app.plugins

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import io.skjaere.usenet.app.Tab
import kotlinx.serialization.Serializable
import kotlin.reflect.KClass

/*
 * The four plugin slots' destinations (L582), fixed in the graph at every start, as Discover's are: each slot's tab, its
 * pages and its settings, twelve distinct destinations, so each slot keeps its own saved history and nothing in the graph
 * changes as a plugin comes or goes. The registry binds an installed plugin to the lowest free slot and keeps it there, so
 * a back stack saved across a process death opens the same plugin. A route carries strings alone: the host restores it
 * after a process death with its own class loader.
 */

/** A plugin page's route: the plugin's [page] name and its [arg], as [io.skjaere.usenet.plugin.PluginNav.open] gave them. */
interface PluginPageRoute {
    val page: String
    val arg: String?
}

@Serializable data object PluginTab1

@Serializable data object PluginTab2

@Serializable data object PluginTab3

@Serializable data object PluginTab4

@Serializable data class PluginPage1(override val page: String, override val arg: String? = null) : PluginPageRoute

@Serializable data class PluginPage2(override val page: String, override val arg: String? = null) : PluginPageRoute

@Serializable data class PluginPage3(override val page: String, override val arg: String? = null) : PluginPageRoute

@Serializable data class PluginPage4(override val page: String, override val arg: String? = null) : PluginPageRoute

@Serializable data object PluginSettings1

@Serializable data object PluginSettings2

@Serializable data object PluginSettings3

@Serializable data object PluginSettings4

/**
 * The host's Choose release for a plugin's item (L628), over the plugin's page that opened it, shared by the slots: its
 * [token] names the releases the host keeps for it. A token the host doesn't know (a process death) closes the page.
 */
@Serializable data class PluginReleasesRoute(val token: String)

/** Slot [number]'s destinations: its [tab], its [settings] and its pages ([page]). */
class PluginSlot internal constructor(val number: Int, val tab: Any, val settings: Any, private val newPage: (String, String?) -> Any) {
    /** The route of the plugin's [page] opened with [arg]. */
    fun page(page: String, arg: String?): Any = newPage(page, arg)

    /** The three destinations' route types: an entry of one of them is the slot's. */
    val types: Set<KClass<*>> = setOf(tab::class, newPage("", null)::class, settings::class)
}

object PluginSlots {
    val all: List<PluginSlot> = listOf(
        PluginSlot(1, PluginTab1, PluginSettings1, ::PluginPage1),
        PluginSlot(2, PluginTab2, PluginSettings2, ::PluginPage2),
        PluginSlot(3, PluginTab3, PluginSettings3, ::PluginPage3),
        PluginSlot(4, PluginTab4, PluginSettings4, ::PluginPage4),
    )

    fun of(number: Int): PluginSlot = all.first { it.number == number }

    /** Every route type a slot or the host's Choose release for a plugin has. */
    val routeTypes: List<KClass<*>> = all.flatMap { it.types } + PluginReleasesRoute::class
}

/**
 * The installed plugins' tabs, in slot order (L618), for the phone's bar and the TV's rail: one for each plugin that has
 * loaded once, whatever its state now, with the label it last gave and the icon of its last instance in this process;
 * with no icon to show (its version can't load), the core set's Build. A plugin that never loaded has no tab.
 */
fun pluginTabs(installed: List<InstalledPlugin>?): List<Tab> = installed.orEmpty().sortedBy { it.slot }.mapNotNull { plugin ->
    val label = plugin.label ?: return@mapNotNull null
    val slot = PluginSlots.of(plugin.slot)
    Tab(label, plugin.icon ?: Icons.Filled.Build, slot.tab, slot.tab::class)
}
