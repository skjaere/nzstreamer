package io.skjaere.usenet.app.settings

import androidx.compose.ui.graphics.vector.ImageVector
import io.skjaere.usenet.app.debrid.HubLine
import io.skjaere.usenet.app.plugins.IndexReading
import io.skjaere.usenet.app.plugins.InstallState
import io.skjaere.usenet.app.plugins.InstalledPlugin
import io.skjaere.usenet.app.plugins.PluginCompat
import io.skjaere.usenet.app.plugins.PluginIndex
import io.skjaere.usenet.app.plugins.PluginInstaller
import io.skjaere.usenet.app.plugins.PluginListState
import io.skjaere.usenet.app.plugins.PluginRegistry
import io.skjaere.usenet.app.plugins.PluginStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** What a plugin card's button does, and its words. Open's are "Open <label>", the plugin's tab's. */
enum class CardAction(val label: String) {
    Install("Install"),
    Cancel("Cancel"),
    TryAgain("Try again"),
    InstallAgain("Install again"),
    Open("Open"),
    Settings("Settings"),
    Remove("Remove"),
    TurnOnAgain("Turn on again"),
}

/** A card's button: what it does, its words, and whether it can be pressed (a greyed one takes no focus). */
data class CardButton(val action: CardAction, val label: String = action.label, val enabled: Boolean = true)

/** A line of a plugin's card, under its name and summary. */
sealed interface CardLine {
    /** Small and muted: "Version 1.2 · 0.7 MB", "Version 1.2 · updates by itself". */
    data class Version(val text: String) : CardLine

    /** Small: "Two plugins are installed already". */
    data class Note(val text: String) : CardLine

    /** ✓ and the line: installed, updated. */
    data class Done(val text: String) : CardLine

    /** ⚠ and the line: why it is off, why an install failed. */
    data class Warn(val text: String) : CardLine

    /** "Downloading…" over a bar of the share downloaded. */
    data class Downloading(val percent: Int) : CardLine

    /** "Checking the file and its signature…" beside a spinner. */
    data object Checking : CardLine
}

/**
 * One plugin's card: its [name] as the list gives it, the list's [summary] (none where the list doesn't name it), its tab's
 * [icon] once it loaded in this process (null: the core set's Build), its tab's [label] (the name until it first loaded),
 * the [slot] its tab is in once installed, its [lines] and [buttons].
 */
data class PluginCard(
    val id: String,
    val name: String,
    val summary: String?,
    val icon: ImageVector?,
    val label: String,
    val slot: Int?,
    val lines: List<CardLine>,
    val buttons: List<CardButton>,
)

/** What shows in the cards' place: the list being read for the first time, or a list with nothing in it. */
enum class PagePlaceholder { Reading, NoneListed }

/** What can have a TV remote's focus on the page: the page's Turn on again, a card's button, or Check again. */
sealed interface PluginsFocus {
    data object TurnPluginsOn : PluginsFocus

    data class Button(val id: String, val action: CardAction) : PluginsFocus

    data object CheckAgain : PluginsFocus
}

/**
 * Settings › Plugins as it shows (L620, L621, L633, L638): the page's ⚠ [lines] (GitHub unreachable, a list in a later
 * format), whether [pluginsOff] after two early crashes (its line with Turn on again), one card per plugin in one group,
 * the installed first in their tabs' order, then those the list offers ([cards]), what shows in their place
 * ([placeholder]), and under them the list's [source] with Check again ("Checking…" while a read runs, [checking]).
 */
data class PluginsPage(
    val lines: List<String>,
    val pluginsOff: Boolean,
    val cards: List<PluginCard>,
    val placeholder: PagePlaceholder?,
    val source: String,
    val checking: Boolean,
) {
    /** Check again's words: "Checking…" while a read runs, the button keeping the focus. */
    val checkAgain: String get() = if (checking) CHECKING else CHECK_AGAIN

    /** What a remote can focus, in the page's order: a greyed button takes none. */
    fun focusables(): List<PluginsFocus> = buildList {
        if (pluginsOff) add(PluginsFocus.TurnPluginsOn)
        cards.forEach { card -> card.buttons.filter { it.enabled }.forEach { add(PluginsFocus.Button(card.id, it.action)) } }
        add(PluginsFocus.CheckAgain)
    }

    /**
     * Where a remote starts: on Turn on again while plugins are off; else on the first enabled button of the card that
     * [opening] names (Open Plugins' way in), else of the first card that has one; else on Check again.
     */
    fun firstFocus(opening: String? = null): PluginsFocus {
        if (pluginsOff) return PluginsFocus.TurnPluginsOn
        cards.firstOrNull { it.id == opening }?.let(::firstButton)?.let { return it }
        return cards.firstNotNullOfOrNull(::firstButton) ?: PluginsFocus.CheckAgain
    }

    private fun firstButton(card: PluginCard): PluginsFocus? = card.buttons.firstOrNull { it.enabled }?.let { PluginsFocus.Button(card.id, it.action) }

    companion object {
        const val TITLE = "Plugins"
        const val INTRO = "Plugins add a tab to NZStreamer. Each one is signed by NZStreamer's developer and checked before it runs."
        const val READING = "Reading the plugin list…"
        const val NONE_LISTED = "No plugins are listed yet"
        const val CHECK_AGAIN = "Check again"
        const val CHECKING = "Checking…"
        const val NO_COPY = "Couldn't reach GitHub. The list shows once it can be read."
        const val PLUGINS_OFF = "Plugins are off: NZStreamer closed twice just after starting"
        const val DOWNLOADING = "Downloading…"
        const val CHECKING_FILE = "Checking the file and its signature…"
        const val TWO_INSTALLED = "Two plugins are installed already"
        const val CRASHED = "Turned off after a crash"
        const val ERRORED = "Turned off after an error"

        /** The hub's line after two early crashes (L619). */
        const val HUB_PLUGINS_OFF = "Plugins are off: NZStreamer closed twice"

        /** Remove's question (L633), the same for every plugin: its [name] as the list gives it. */
        fun removeQuestion(name: String): String = "Remove $name?"

        /** Under it: what goes and what stays, by the plugin's tab's [label]. */
        fun removeText(label: String): String =
            "The $label tab goes, with its settings and saved keys. What you watched stays marked, and Up next keeps what you were watching."

        /**
         * The page from the registry's [installed] plugins (null until it read its files) and whether [pluginsOff], the
         * [list] as the app last read it, the [installs] the page started ([PluginInstaller.states]), and whether Install
         * again would find a version to fetch for a plugin ([installAgain]), at [now] in [zone], on a [television] or a
         * phone; [after]: the tab plugin tabs follow ([io.skjaere.usenet.app.pluginTabsFollow]); [slots]: whether a card's
         * Settings and Open <label> have the slot's pages to open. What runs on this device of [sdk], by [compat]'s rule.
         */
        fun of(
            installed: List<InstalledPlugin>?,
            pluginsOff: Boolean,
            list: PluginListState,
            installs: Map<String, InstallState>,
            installAgain: (String) -> Boolean,
            now: Long,
            zone: ZoneId,
            television: Boolean,
            after: String,
            slots: Boolean,
            sdk: Int,
            compat: PluginCompat,
        ): PluginsPage {
            val plugins = installed.orEmpty()
            val index = list.index
            val device = if (television) "TV" else "phone"
            val cards = Cards(index, installs, now, zone, device, after, slots, sdk, compat)
            val own = plugins.map { cards.installed(it, pluginsOff, installAgain(it.id)) }
            // The others the list offers: two installed, or on their way, grey a third's Install (L618).
            val coming = installs.filter { (id, state) -> plugins.none { it.id == id } && (state is InstallState.Downloading || state is InstallState.Checking) }.keys
            val full = (plugins.map { it.id } + coming).distinct().size >= PluginRegistry.MAX_INSTALLED
            val offered = index?.plugins.orEmpty().filter { listed -> plugins.none { it.id == listed.id } }.mapNotNull { cards.listed(it.id, full) }
            val lines = buildList {
                when {
                    list.tooNew -> add(IndexReading.TooNew.line(television))
                    list.failed && index != null -> add("Couldn't reach GitHub. This list is from ${age(list.readAt ?: now, now, zone)}.")
                    list.failed -> add(NO_COPY)
                }
            }
            val all = own + offered
            val placeholder = when {
                index == null && !list.failed && !list.tooNew -> PagePlaceholder.Reading
                index != null && all.isEmpty() -> PagePlaceholder.NoneListed
                else -> null
            }
            return PluginsPage(lines, pluginsOff && plugins.isNotEmpty(), all, placeholder, source(list.readAt, now, zone), list.checking)
        }

        /**
         * Where a remote's focus goes as [after] replaces [before], from the [focused] element: where it is still there, it
         * stays (Check again keeps it while it reads). A card's button that went hands it to what took its place, the
         * button now where it was (Install to Cancel, Cancel to Open <label> or Try again, Remove to Install), else the
         * card's first; a card with none, or gone, to Check again. The page's Turn on again hands it to the page's first.
         * [auto]: the focus is where the page put it as it opened, the user having moved nothing: one put on Check again
         * only for want of a card goes to the first card's button once the list shows one.
         */
        fun focusAfter(before: PluginsPage, focused: PluginsFocus, after: PluginsPage, auto: Boolean): PluginsFocus {
            if (auto && focused == PluginsFocus.CheckAgain) return after.firstFocus()
            if (focused in after.focusables()) return focused
            return when (focused) {
                PluginsFocus.TurnPluginsOn, PluginsFocus.CheckAgain -> after.firstFocus()
                is PluginsFocus.Button -> {
                    val card = after.cards.firstOrNull { it.id == focused.id } ?: return PluginsFocus.CheckAgain
                    val enabled = card.buttons.filter { it.enabled }
                    val was = before.cards.firstOrNull { it.id == focused.id }?.buttons?.filter { it.enabled }?.indexOfFirst { it.action == focused.action } ?: -1
                    val next = enabled.getOrNull(was.coerceAtLeast(0)) ?: enabled.firstOrNull()
                    next?.let { PluginsFocus.Button(card.id, it.action) } ?: PluginsFocus.CheckAgain
                }
            }
        }

        /** "From NZStreamer's plugin list on GitHub · checked today, 20:41", "· checked 20 Nov, 18:02", "· never checked". */
        private fun source(readAt: Long?, now: Long, zone: ZoneId): String {
            val checked = readAt?.let { at ->
                val time = Instant.ofEpochMilli(at).atZone(zone)
                val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
                val day = when {
                    time.toLocalDate() == today -> "today"
                    time.year == today.year -> time.format(DAY)
                    else -> time.format(DAY_YEAR)
                }
                "checked $day, ${time.format(CLOCK)}"
            } ?: "never checked"
            return "From NZStreamer's plugin list on GitHub · $checked"
        }

        /** How old the list kept is, by the calendar: "today", "yesterday", "3 days ago". */
        private fun age(readAt: Long, now: Long, zone: ZoneId): String =
            when (val days = ChronoUnit.DAYS.between(Instant.ofEpochMilli(readAt).atZone(zone).toLocalDate(), Instant.ofEpochMilli(now).atZone(zone).toLocalDate())) {
                0L -> "today"
                1L -> "yesterday"
                else -> "$days days ago"
            }

        private val DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
        private val DAY_YEAR = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
        private val CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)
    }
}

/** The cards' words and buttons (L621, L633, L638), one plugin at a time. */
private class Cards(
    private val index: PluginIndex?,
    private val installs: Map<String, InstallState>,
    private val now: Long,
    private val zone: ZoneId,
    private val device: String,
    private val after: String,
    private val slots: Boolean,
    private val sdk: Int,
    private val compat: PluginCompat,
) {
    /**
     * An installed plugin's card: an install of its own first (Install again's), then its state. "Installed" only while the
     * version runs, or waits for its first load: one that didn't come up has no tab (L618), and says why.
     */
    fun installed(plugin: InstalledPlugin, pluginsOff: Boolean, installAgain: Boolean): PluginCard {
        val label = plugin.label ?: plugin.name
        val comesUp = !plugin.withdrawn && (plugin.status is PluginStatus.Running || plugin.status == PluginStatus.Waiting)
        val (lines, buttons) = when {
            // While plugins are off, its version and Remove alone, whatever its state.
            pluginsOff || plugin.status == PluginStatus.PluginsOff -> listOf(CardLine.Version("Version ${plugin.versionName}")) to listOf(CardButton(CardAction.Remove))
            else -> installs[plugin.id]?.takeUnless { it is InstallState.Installed && !comesUp }?.let { install(it, label, installed = true) } ?: state(plugin, installAgain)
        }
        val summary = index?.plugins?.firstOrNull { it.id == plugin.id }?.summary
        return PluginCard(plugin.id, plugin.name, summary?.takeIf { it.isNotBlank() }, plugin.icon, label, plugin.slot, lines, buttons)
    }

    /** A plugin the list offers that isn't installed, or null where the list has nothing to show for it. */
    fun listed(id: String, full: Boolean): PluginCard? {
        val offer = index?.offer(id, installed = null, sdk, compat) ?: return null
        val plugin = offer.plugin
        val own = installs[id]?.let { install(it, plugin.name) }
        val offered = offer.offered
        val (lines, buttons) = when {
            own != null -> own
            offered != null && full -> listOf(CardLine.Version(versionLine(offered.versionName, offered.size)), CardLine.Note(PluginsPage.TWO_INSTALLED)) to
                listOf(CardButton(CardAction.Install, enabled = false))
            offered != null -> listOf(CardLine.Version(versionLine(offered.versionName, offered.size))) to listOf(CardButton(CardAction.Install))
            else -> listOf(CardLine.Warn("Needs a newer NZStreamer. Update NZStreamer on this $device to install it.")) to emptyList()
        }
        return PluginCard(id, plugin.name, plugin.summary.takeIf { it.isNotBlank() }, null, plugin.name, null, lines, buttons)
    }

    /**
     * An install the page started, as its card follows it; none once it is done and the page was left. A failed Install
     * again keeps Remove beside Try again: the plugin is still [installed].
     */
    private fun install(state: InstallState, label: String, installed: Boolean = false): Pair<List<CardLine>, List<CardButton>> = when (state) {
        is InstallState.Downloading -> listOf(CardLine.Downloading(state.percent)) to listOf(CardButton(CardAction.Cancel))
        InstallState.Checking -> listOf(CardLine.Checking) to listOf(CardButton(CardAction.Cancel))
        is InstallState.Failed -> listOf(CardLine.Warn(state.line)) to listOfNotNull(CardButton(CardAction.TryAgain), CardButton(CardAction.Remove).takeIf { installed })
        is InstallState.Installed -> listOf(CardLine.Done("Installed. $label is now a tab after $after.")) to
            listOfNotNull(CardButton(CardAction.Open, "Open $label").takeIf { slots }) + running()
    }

    /** An installed plugin's state: off and why, refused, withdrawn, or running, with its news. */
    private fun state(plugin: InstalledPlugin, installAgain: Boolean): Pair<List<CardLine>, List<CardButton>> {
        val remove = CardButton(CardAction.Remove)
        val turnOn = listOf(CardButton(CardAction.TurnOnAgain), remove)
        val status = plugin.status
        return when {
            status == PluginStatus.OffAfterCrash -> listOf(CardLine.Warn(PluginsPage.CRASHED)) to turnOn
            status == PluginStatus.OffAfterError -> listOf(CardLine.Warn(PluginsPage.ERRORED)) to turnOn
            // Withdrawn: off, or running on until its swap point lets it go (L621).
            plugin.withdrawn || status == PluginStatus.Withdrawn ->
                listOf(CardLine.Warn("Version ${plugin.versionName} was withdrawn, so it's off until a fixed version is out.")) to listOf(remove)
            status is PluginStatus.Refused ->
                listOf(CardLine.Warn("Couldn't start: ${status.refusal.reason}.")) to listOfNotNull(CardButton(CardAction.InstallAgain).takeIf { installAgain }, remove)
            status is PluginStatus.CouldntStart -> listOf(CardLine.Warn("Couldn't start: ${status.failure.reason}.")) to listOf(remove)
            else -> running(plugin) to running()
        }
    }

    /** A running version's line: an update that couldn't start, one that took over this week, one too new, or its version. */
    private fun running(plugin: InstalledPlugin): List<CardLine> {
        plugin.failedUpdate?.let { failed ->
            return listOf(CardLine.Warn("Version ${failed.versionName} couldn't start: ${failed.reason}. Version ${plugin.versionName} runs."))
        }
        plugin.lastUpdate?.takeIf { now - it.at < PluginInstaller.UPDATE_NEWS_MS }?.let { update ->
            return listOf(CardLine.Done(PluginInstaller.updatedLine(update, now, zone)))
        }
        index?.offer(plugin.id, plugin.versionCode, sdk, compat)?.needsNewer?.let { newest ->
            return listOf(CardLine.Version(PluginInstaller.needsNewerLine(plugin.versionName, newest.versionName)))
        }
        return listOf(CardLine.Version("Version ${plugin.versionName} · updates by itself"))
    }

    /** A running plugin's buttons: its settings, where the slot's pages are there to open, and Remove. */
    private fun running(): List<CardButton> = listOfNotNull(CardButton(CardAction.Settings).takeIf { slots }, CardButton(CardAction.Remove))

    /** "Version 1.2 · 0.7 MB": the list's size in MB with one decimal, never 0.0. */
    private fun versionLine(versionName: String, size: Long): String {
        val tenths = ((size + 50_000) / 100_000).coerceAtLeast(1)
        return "Version $versionName · ${String.format(Locale.ROOT, "%.1f", tenths / 10.0)} MB"
    }
}

/**
 * Settings' Plugins row (W157, L638): "None yet"; the installed plugins' names in their tabs' order, joined as Debrid
 * services' row joins its services; with one in a state, " · " and that state, the first in tab order whose state has
 * the warn dot, else the first in tab order: an update this week, an update this NZStreamer can't run, and with the warn
 * dot, off after a crash or an error, withdrawn, or couldn't start (refused, or its code didn't load). After two early
 * crashes, that plugins are off, with the dot. [index]: the list as last read, for an update too new; [now] for the week.
 */
fun pluginsHubLine(installed: List<InstalledPlugin>, pluginsOff: Boolean, index: PluginIndex?, now: Long, sdk: Int, compat: PluginCompat): HubLine {
    if (installed.isEmpty()) return HubLine("None yet")
    if (pluginsOff) return HubLine(PluginsPage.HUB_PLUGINS_OFF, warn = true)
    val states = installed.map { plugin -> plugin to hubState(plugin, index, now, sdk, compat) }
    val names = installed.joinToString { it.name }
    val (plugin, state) = states.firstOrNull { (_, state) -> state?.warn == true } ?: states.firstOrNull { it.second != null } ?: return HubLine(names)
    val warn = states.any { (_, state) -> state?.warn == true }
    // One plugin: its name and its state; two: both names, then which one, its state.
    val said = if (installed.size == 1) "${plugin.name} · ${state!!.words}" else "$names · ${plugin.name}${state!!.ofOne}"
    return HubLine(said, warn)
}

/** What the hub row says of a plugin, alone ([words]) and after its name among two ([ofOne]); null while it runs as it should. */
private class HubState(val words: String, val ofOne: String = " $words", val warn: Boolean = false)

private fun hubState(plugin: InstalledPlugin, index: PluginIndex?, now: Long, sdk: Int, compat: PluginCompat): HubState? {
    val status = plugin.status
    return when {
        status == PluginStatus.OffAfterCrash -> HubState("turned off after a crash", warn = true)
        status == PluginStatus.OffAfterError -> HubState("turned off after an error", warn = true)
        plugin.withdrawn || status == PluginStatus.Withdrawn -> HubState("withdrawn, off", warn = true)
        status is PluginStatus.Refused || status is PluginStatus.CouldntStart -> HubState("couldn't start", warn = true)
        else -> plugin.lastUpdate?.takeIf { now - it.at < PluginInstaller.UPDATE_NEWS_MS }?.let { HubState("updated to ${it.versionName}") }
            ?: index?.offer(plugin.id, plugin.versionCode, sdk, compat)?.needsNewer?.let {
                HubState("update needs a newer NZStreamer", ofOne = "'s update needs a newer NZStreamer")
            }
    }
}
