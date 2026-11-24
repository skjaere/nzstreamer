package io.skjaere.usenet.app.plugins

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import io.skjaere.usenet.app.ifResumed
import io.skjaere.usenet.app.settings.PluginsPage
import io.skjaere.usenet.app.switchTab
import io.skjaere.usenet.app.tv.LocalRailFocus
import io.skjaere.usenet.app.tv.TopLevelBack
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.ui.LocalFormFactor
import io.skjaere.usenet.app.ui.PrimaryButton
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.rememberInitialFocus
import io.skjaere.usenet.app.ui.tryFocus
import io.skjaere.usenet.plugin.PluginNav
import kotlin.reflect.KClass

/**
 * The four plugin slots' destinations in the app's graph (L582, L590), added once, beside the build's extras: nothing
 * here captures what changes, the app's [registry] and the slots by reference, so the graph is never built again as
 * plugins come and go. Each slot's tab (with [TopLevelBack] first, as Discover's), its pages and its settings draw the
 * plugin the registry has in that slot, as its state now stands:
 * - its version running: the plugin's own page, its settings page, keyed by its instance, so a new instance starts its
 *   pages afresh and none of a version let go is drawn;
 * - its files not read yet, or its code still loading (a cold start, a few hundred ms at most): nothing;
 * - not running (after a crash or an error, withdrawn, refused, couldn't start, plugins off): the host's placeholder;
 * - no plugin in the slot: nothing, while the slots' watch ([PluginSlotWatch]) takes it Home.
 * The page or the placeholder tells a TV's rail what the content shows changed as it first shows, so the shell puts the
 * focus in. And the host's Choose release for a plugin's item (L628), over the page that opened it, or wherever an item's
 * Play with only possible matches opened it; it closes as a release it plays launches, or at once where its token went
 * with a process death.
 */
fun NavGraphBuilder.pluginScreens(nav: NavController, registry: PluginRegistry) {
    slot<PluginTab1, PluginPage1, PluginSettings1>(PluginSlots.of(1), nav, registry)
    slot<PluginTab2, PluginPage2, PluginSettings2>(PluginSlots.of(2), nav, registry)
    slot<PluginTab3, PluginPage3, PluginSettings3>(PluginSlots.of(3), nav, registry)
    slot<PluginTab4, PluginPage4, PluginSettings4>(PluginSlots.of(4), nav, registry)
    composable<PluginReleasesRoute> { screen ->
        PluginReleasesScreen(
            screen.toRoute<PluginReleasesRoute>().token,
            onBack = { screen.ifResumed { nav.popBackStack() } },
            // Once, and only while it is the screen shown: a launch may come as it is already going.
            onClose = { if (nav.currentBackStackEntry?.id == screen.id) nav.popBackStack() },
        )
    }
}

private inline fun <reified T : Any, reified P : PluginPageRoute, reified S : Any> NavGraphBuilder.slot(
    slot: PluginSlot,
    nav: NavController,
    registry: PluginRegistry,
) {
    composable<T> { screen ->
        TopLevelBack(nav, screen)
        SlotContent(slot, screen, nav, registry) { instance, pluginNav -> instance.plugin.Page("", null, instance.host, pluginNav) }
    }
    composable<P> { screen ->
        val route = screen.toRoute<P>()
        SlotContent(slot, screen, nav, registry) { instance, pluginNav -> instance.plugin.Page(route.page, route.arg, instance.host, pluginNav) }
    }
    composable<S> { screen ->
        SlotContent(slot, screen, nav, registry) { instance, pluginNav -> instance.plugin.Settings(instance.host, pluginNav) }
    }
}

/** [slot]'s destination [entry]: [draw] with the instance that runs there, or the placeholder, or nothing (see [pluginScreens]). */
@Composable
private fun SlotContent(
    slot: PluginSlot,
    entry: NavBackStackEntry,
    nav: NavController,
    registry: PluginRegistry,
    draw: @Composable (PluginInstance, PluginNav) -> Unit,
) {
    val plugins by registry.plugins.collectAsStateWithLifecycle()
    val plugin = plugins?.firstOrNull { it.slot == slot.number } ?: return
    val status = plugin.status
    if (status is PluginStatus.Running) {
        key(status.instance.key) {
            val pluginNav = remember { PluginNavImpl(nav, slot, entry, status.instance.host.chooser) }
            draw(status.instance, pluginNav)
            val rail = LocalRailFocus.current
            LaunchedEffect(Unit) { rail?.contentChanged() }
        }
        return
    }
    val placeholder = PluginPlaceholder.of(plugin) ?: return
    val tv = LocalFormFactor.current == FormFactor.Tv
    PlaceholderPage(placeholder, tv) { entry.ifResumed { NavSlotMoves(nav).openPlugins(plugin.id, tv) } }
}

/**
 * What a slot shows while its plugin's version isn't running (L590; card 5 and frame 6 of the plugin's list states): the
 * tab's [label] as the page's title, then one card, its [title] and its [line], and Open Plugins. The words name the tab
 * by its label and the plugin by its list's name, and say why: turned off after a crash, after an error or withdrawn, or
 * couldn't start with the Plugins page's reason; what the user watched is kept.
 */
data class PluginPlaceholder(val label: String, val title: String, val line: String) {
    companion object {
        const val OPEN_PLUGINS = "Open Plugins"
        const val KEPT = "What you watched is kept."

        /** [plugin]'s placeholder, or null where its version runs or is still loading. */
        fun of(plugin: InstalledPlugin): PluginPlaceholder? {
            val label = plugin.label ?: plugin.name
            val off = "$label is turned off"
            val name = plugin.name
            return when (val status = plugin.status) {
                PluginStatus.Waiting, is PluginStatus.Running -> null
                PluginStatus.OffAfterCrash -> PluginPlaceholder(label, off, "The $name plugin was turned off after it crashed NZStreamer. $KEPT")
                PluginStatus.OffAfterError -> PluginPlaceholder(label, off, "The $name plugin was turned off after an error. $KEPT")
                PluginStatus.Withdrawn ->
                    PluginPlaceholder(label, off, "The $name plugin was turned off: version ${plugin.versionName} was withdrawn. $KEPT")
                is PluginStatus.Refused -> PluginPlaceholder(label, "$label couldn't start: ${status.refusal.reason}.", KEPT)
                is PluginStatus.CouldntStart -> PluginPlaceholder(label, "$label couldn't start: ${status.failure.reason}.", KEPT)
                PluginStatus.PluginsOff -> PluginPlaceholder(label, off, "${PluginsPage.PLUGINS_OFF}. $KEPT")
            }
        }
    }
}

/**
 * The placeholder, with the look of Discover's state cards: on a phone the page's title, then the card across the column;
 * on a TV the 480 dp card centred in the column, its Open Plugins taking the first focus, so every state has something to
 * focus (L590). [onOpenPlugins]: Open Plugins' way, the slot left first.
 */
@Composable
private fun PlaceholderPage(placeholder: PluginPlaceholder, tv: Boolean, onOpenPlugins: () -> Unit) {
    if (!tv) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(
                placeholder.label, style = AppType.screenTitle, color = colors.text,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp).semantics { heading() },
            )
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 18.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surface)
                    .padding(horizontal = 18.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(placeholder.title, style = AppType.cardTitle, color = colors.text)
                Text(placeholder.line, style = AppType.body, color = colors.textSecondary)
                PrimaryButton(PluginPlaceholder.OPEN_PLUGINS, onOpenPlugins, Modifier.padding(top = 4.dp))
            }
        }
        return
    }
    val button = remember { FocusRequester() }
    val rail = LocalRailFocus.current
    val initial = rememberInitialFocus()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text(
            placeholder.label, style = AppType.screenTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.heightIn(min = 40.dp).wrapContentHeight(Alignment.CenterVertically).padding(horizontal = 16.dp)
                .semantics { heading() },
        )
        Box(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 16.dp), contentAlignment = Alignment.Center) {
            Column(
                Modifier.width(TV_CARD_WIDTH).clip(RoundedCornerShape(16.dp)).background(colors.surface).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(placeholder.title, style = AppType.screenTitle, color = colors.text, textAlign = TextAlign.Center)
                Text(placeholder.line, style = AppType.body, color = colors.textSecondary, textAlign = TextAlign.Center)
                // Alone on its line, in the middle: Left goes to the rail, as from the column's first elements.
                PrimaryButton(
                    PluginPlaceholder.OPEN_PLUGINS, onOpenPlugins,
                    Modifier.focusRequester(button).focusProperties { rail?.let { left = it.wayIn } }.padding(top = 14.dp),
                )
            }
        }
    }
    // As it first shows, in place of a page whose focused element went (Compose has given the focus elsewhere by then) or
    // of nothing (a cold start): the shell told the content changed, and Open Plugins focused once the screen may take it
    // (not while the user holds the rail), asked a frame at a time until it is laid out.
    LaunchedEffect(placeholder) {
        rail?.contentChanged()
        initial.awaitTurn()
        repeat(FOCUS_FRAMES) {
            if (button.tryFocus()) return@LaunchedEffect
            withFrameNanos {}
        }
    }
}

/** The slots' moves on the app's [nav] ([SlotMoves]). */
class NavSlotMoves(private val nav: NavController) : SlotMoves {
    override fun shown(): List<KClass<*>> {
        val drawn = nav.visibleEntries.value.mapNotNull { entry -> PluginSlots.routeTypes.firstOrNull { entry.destination.hasRoute(it) } }
        return drawn + stacked()
    }

    override fun stacked(): List<KClass<*>> = PluginSlots.routeTypes.filter(::onBackStack)

    // Asked by type: the back stack as a whole is the navigation library's own API (RestrictedApi); an entry not on it is refused.
    private fun onBackStack(type: KClass<*>): Boolean = try {
        nav.getBackStackEntry(type)
        true
    } catch (e: IllegalArgumentException) {
        false
    }

    override fun switchTab(route: Any, restore: Boolean) = nav.switchTab(route, restore)

    override fun navigate(route: Any) = nav.navigate(route)

    override fun clear(slot: Int) {
        nav.clearBackStack(PluginSlots.of(slot).tab::class)
    }
}

/** Discover's TV state cards' width. */
private val TV_CARD_WIDTH = 480.dp

/** How many frames the placeholder waits for its button to be laid out. */
private const val FOCUS_FRAMES = 10
