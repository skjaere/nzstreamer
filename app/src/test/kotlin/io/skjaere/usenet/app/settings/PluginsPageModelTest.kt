package io.skjaere.usenet.app.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.AppExtras
import io.skjaere.usenet.app.ExtraTab
import io.skjaere.usenet.app.HomeRoute
import io.skjaere.usenet.app.Tab
import io.skjaere.usenet.app.debrid.HubLine
import io.skjaere.usenet.app.pluginTabsFollow
import io.skjaere.usenet.app.plugins.FailedVersion
import io.skjaere.usenet.app.plugins.FakePlugin
import io.skjaere.usenet.app.plugins.HOST_LIBS
import io.skjaere.usenet.app.plugins.HostedPlugin
import io.skjaere.usenet.app.plugins.InstallState
import io.skjaere.usenet.app.plugins.InstalledPlugin
import io.skjaere.usenet.app.plugins.LastUpdate
import io.skjaere.usenet.app.plugins.ListedPlugin
import io.skjaere.usenet.app.plugins.ListedVersion
import io.skjaere.usenet.app.plugins.LoadFailure
import io.skjaere.usenet.app.plugins.PluginCompat
import io.skjaere.usenet.app.plugins.PluginIndex
import io.skjaere.usenet.app.plugins.PluginInstance
import io.skjaere.usenet.app.plugins.PluginListState
import io.skjaere.usenet.app.plugins.PluginStatus
import io.skjaere.usenet.app.plugins.PluginVersion
import io.skjaere.usenet.app.plugins.Refusal
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.plugin.PluginSecrets
import io.skjaere.usenet.plugin.PluginTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.jupiter.api.Test
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Settings › Plugins and its hub row (W157, L590, L620, L621, L633, L638): the cards, their lines
 * and buttons, the page's lines and its source line, from the registry's plugins, the list and the installs in each
 * state; the hub row's line and warn dot, with one plugin and two; and on a TV, which button has the focus as the page
 * opens and after each change, so something is focused after every one.
 */
class PluginsPageModelTest {
    private val zone = ZoneOffset.UTC
    private val now = Instant.parse("2026-11-24T20:41:00Z").toEpochMilli()
    private val compat = PluginCompat(1, 0, HOST_LIBS)
    private val libs = PluginCompat.parseLibs(HOST_LIBS)!!
    private val summary = "The leagues and sessions you follow in your own Copper Crown server, in a Sports tab."

    private fun version(code: Long, name: String, size: Long = 700_000, minor: Int = 0, minSdk: Int = 26) = ListedVersion(
        code, name, 1, minor, libs, minSdk, "https://github.com/skjaere/nzstreamer/releases/download/plugin-kestrel-v1.0.0/kestrel.apk",
        size, "a".repeat(64),
    )

    private fun listed(id: String, name: String, vararg versions: ListedVersion, withdrawn: Set<Long> = emptySet(), text: String = summary) =
        ListedPlugin(id, name, text, withdrawn, versions.sortedByDescending { it.versionCode })

    private fun list(vararg plugins: ListedPlugin, readAt: Long? = now - 60_000, failed: Boolean = false, checking: Boolean = false) =
        PluginListState(PluginIndex(Instant.EPOCH, plugins.toList(), emptyList()), tooNew = false, readAt = readAt, failed = failed, checking = checking)

    private object NoSecrets : PluginSecrets {
        override suspend fun get(name: String): String? = null
        override suspend fun put(name: String, value: String?) = Unit
        override fun has(name: String): Flow<Boolean> = flowOf(false)
    }

    /** A version that loaded in this process, its tab [label] given. */
    private fun running(id: String, label: String): PluginStatus = PluginStatus.Running(
        PluginInstance(PluginVersion(id, 1, 1), FakePlugin(label), HostedPlugin(id, File("unused"), File("unused"), NoSecrets, CoroutineScope(Job())), PluginTab(label, Icons.Filled.Star)),
    )

    private fun installed(
        id: String = "kestrel", name: String = "Kestrel", slot: Int = 1, versionName: String = "1.2", label: String? = "Sports",
        status: PluginStatus = running(id, label ?: "Sports"), failedUpdate: FailedVersion? = null, lastUpdate: LastUpdate? = null,
        withdrawn: Boolean = false, versionCode: Long = 12,
    ) = InstalledPlugin(
        id, name, slot, versionCode, versionName, label, status, waitingVersion = null, failedUpdate = failedUpdate, lastUpdate = lastUpdate,
        withdrawn = withdrawn, icon = (status as? PluginStatus.Running)?.instance?.tab?.icon,
    )

    private fun page(
        installed: List<InstalledPlugin>? = emptyList(),
        list: PluginListState = list(),
        installs: Map<String, InstallState> = emptyMap(),
        pluginsOff: Boolean = false,
        installAgain: (String) -> Boolean = { true },
        television: Boolean = false,
        after: String = "Discover",
        slots: Boolean = true,
    ) = PluginsPage.of(installed, pluginsOff, list, installs, installAgain, now, zone, television, after, slots, sdk = 34, compat = compat)

    private fun buttons(vararg actions: CardAction) = actions.map { CardButton(it) }

    @Test
    fun `the page's words, and its source line with when it was last read`() {
        assertEquals("Plugins", PluginsPage.TITLE)
        assertEquals("Plugins add a tab to NZStreamer. Each one is signed by NZStreamer's developer and checked before it runs.", PluginsPage.INTRO)
        assertEquals("From NZStreamer's plugin list on GitHub · checked today, 20:41", page(list = list(readAt = now)).source)
        val earlier = Instant.parse("2026-11-20T18:02:00Z").toEpochMilli()
        assertEquals("From NZStreamer's plugin list on GitHub · checked 20 Nov, 18:02", page(list = list(readAt = earlier)).source)
        val lastYear = Instant.parse("2025-11-20T18:02:00Z").toEpochMilli()
        assertEquals("From NZStreamer's plugin list on GitHub · checked 20 Nov 2025, 18:02", page(list = list(readAt = lastYear)).source)
        assertEquals("From NZStreamer's plugin list on GitHub · never checked", page(list = PluginListState()).source)
        assertEquals("Check again", page().checkAgain)
        assertEquals("Checking…", page(list = list(checking = true)).checkAgain)
    }

    @Test
    fun `a plugin the list offers has its version and size, the list's summary and Install`() {
        val card = page(list = list(listed("kestrel", "Kestrel", version(11, "1.1"), version(12, "1.2")))).cards.single()
        assertEquals("Kestrel", card.name)
        assertEquals(summary, card.summary)
        assertNull(card.icon, "the core set's Build until it has loaded in this process")
        assertEquals(listOf<CardLine>(CardLine.Version("Version 1.2 · 0.7 MB")), card.lines)
        assertEquals(buttons(CardAction.Install), card.buttons)
        // The size in MB with one decimal, never 0.0.
        assertEquals(CardLine.Version("Version 1.0 · 0.1 MB"), page(list = list(listed("kestrel", "Kestrel", version(10, "1.0", size = 1)))).cards.single().lines.single())
        assertEquals(CardLine.Version("Version 1.0 · 12.3 MB"), page(list = list(listed("kestrel", "Kestrel", version(10, "1.0", size = 12_345_678)))).cards.single().lines.single())
        assertNull(page(list = list(listed("kestrel", "Kestrel", version(10, "1.0"), text = ""))).cards.single().summary, "an empty summary shows nothing")
    }

    @Test
    fun `an install's card while it downloads, is checked, fails and is done`() {
        val kestrel = list(listed("kestrel", "Kestrel", version(12, "1.2")))
        fun card(state: InstallState, installedNow: List<InstalledPlugin> = emptyList(), after: String = "Discover", slots: Boolean = true) =
            page(installedNow, kestrel, mapOf("kestrel" to state), after = after, slots = slots).cards.single()

        assertEquals(listOf<CardLine>(CardLine.Downloading(40)), card(InstallState.Downloading(40)).lines)
        assertEquals(buttons(CardAction.Cancel), card(InstallState.Downloading(40)).buttons)
        assertEquals(listOf<CardLine>(CardLine.Checking), card(InstallState.Checking).lines)
        assertEquals(buttons(CardAction.Cancel), card(InstallState.Checking).buttons)
        assertEquals("Downloading…", PluginsPage.DOWNLOADING)
        assertEquals("Checking the file and its signature…", PluginsPage.CHECKING_FILE)

        listOf(
            "Couldn't download it. Check your connection.",
            "The file doesn't match the list, so nothing was installed.",
            "It isn't signed by NZStreamer, so nothing was installed.",
            "It was made for another NZStreamer version, so nothing was installed.",
            "Not enough space on this phone. Free 0.7 MB, then try again.",
        ).forEach { line ->
            val failed = card(InstallState.Failed(line))
            assertEquals(listOf<CardLine>(CardLine.Warn(line)), failed.lines)
            assertEquals(buttons(CardAction.TryAgain), failed.buttons)
        }

        val now = listOf(installed())
        val done = card(InstallState.Installed(1), now)
        assertEquals(listOf<CardLine>(CardLine.Done("Installed. Sports is now a tab after Discover.")), done.lines)
        assertEquals(listOf(CardButton(CardAction.Open, "Open Sports"), CardButton(CardAction.Settings), CardButton(CardAction.Remove)), done.buttons)
        assertEquals(1, done.slot)
        assertEquals(CardLine.Done("Installed. Sports is now a tab after Home."), card(InstallState.Installed(1), now, after = "Home").lines.single())
        // A card's Settings and Open wait for the slot's destinations (item 15).
        assertEquals(buttons(CardAction.Remove), card(InstallState.Installed(1), now, slots = false).buttons)
        // Never loaded yet: the list's name stands for the tab's label.
        val unnamed = page(listOf(installed(label = null, status = PluginStatus.Waiting)), kestrel, mapOf("kestrel" to InstallState.Installed(1))).cards.single()
        assertEquals(CardLine.Done("Installed. Kestrel is now a tab after Discover."), unnamed.lines.single())
        assertEquals("Open Kestrel", unnamed.buttons.first().label)
    }

    /**
     * L618: a plugin that never loaded has no tab. An install (or Install again) whose version didn't come up says why, as
     * its card would after the page is left, and never "Installed. … is now a tab" with Open; nor once the list withdrew it.
     */
    @Test
    fun `an install whose version didn't come up shows the plugin's own state, not Installed`() {
        val kestrel = list(listed("kestrel", "Kestrel", version(12, "1.2")))
        fun card(plugin: InstalledPlugin) = page(listOf(plugin), kestrel, mapOf("kestrel" to InstallState.Installed(1))).cards.single()

        val errored = card(installed(label = null, status = PluginStatus.OffAfterError))
        assertEquals(listOf<CardLine>(CardLine.Warn("Turned off after an error")), errored.lines)
        assertEquals(buttons(CardAction.TurnOnAgain, CardAction.Remove), errored.buttons)
        val couldnt = card(installed(label = null, status = PluginStatus.CouldntStart(LoadFailure.Linkage)))
        assertEquals(listOf<CardLine>(CardLine.Warn("Couldn't start: its code didn't load.")), couldnt.lines)
        assertEquals(buttons(CardAction.Remove), couldnt.buttons)
        val refused = card(installed(status = PluginStatus.Refused(Refusal.Hash)))
        assertEquals(listOf<CardLine>(CardLine.Warn("Couldn't start: its file changed after it was installed.")), refused.lines)
        assertEquals(buttons(CardAction.InstallAgain, CardAction.Remove), refused.buttons)
        assertEquals(buttons(CardAction.TurnOnAgain, CardAction.Remove), card(installed(status = PluginStatus.OffAfterCrash)).buttons)
        assertEquals(
            CardLine.Warn("Version 1.2 was withdrawn, so it's off until a fixed version is out."),
            card(installed(withdrawn = true)).lines.single(),
            "withdrawn while the page shows it",
        )
    }

    @Test
    fun `an installed plugin's card in each state, its lines and buttons`() {
        val kestrel = list(listed("kestrel", "Kestrel", version(12, "1.2")))
        fun card(plugin: InstalledPlugin, pluginsOff: Boolean = false, installAgain: Boolean = true, slots: Boolean = true, index: PluginListState = kestrel) =
            page(listOf(plugin), index, pluginsOff = pluginsOff, installAgain = { installAgain }, slots = slots).cards.single()
        val settingsRemove = buttons(CardAction.Settings, CardAction.Remove)

        val runs = card(installed())
        assertEquals(listOf<CardLine>(CardLine.Version("Version 1.2 · updates by itself")), runs.lines)
        assertEquals(settingsRemove, runs.buttons)
        assertSame(Icons.Filled.Star, runs.icon, "its tab's icon, once it loaded")
        assertEquals(summary, runs.summary)
        assertEquals("Kestrel", runs.name)
        assertEquals(buttons(CardAction.Remove), card(installed(), slots = false).buttons)
        assertEquals(runs.lines, card(installed(status = PluginStatus.Waiting)).lines, "not loaded yet, as it runs")

        // Updated, for seven days, then the version line again.
        val today = LastUpdate("1.2", now - 3_600_000)
        assertEquals(listOf<CardLine>(CardLine.Done("Updated to 1.2 today")), card(installed(lastUpdate = today)).lines)
        assertEquals(settingsRemove, card(installed(lastUpdate = today)).buttons)
        val earlier = LastUpdate("1.2", Instant.parse("2026-11-20T09:00:00Z").toEpochMilli())
        assertEquals(CardLine.Done("Updated to 1.2 · 20 Nov"), card(installed(lastUpdate = earlier)).lines.single())
        val week = LastUpdate("1.2", now - 7 * 24 * 3_600_000L)
        assertEquals(CardLine.Version("Version 1.2 · updates by itself"), card(installed(lastUpdate = week)).lines.single())

        // Its newest listed version needs a newer NZStreamer.
        val newer = list(listed("kestrel", "Kestrel", version(11, "1.1"), version(12, "1.2", minor = 1)))
        val behind = card(installed(versionName = "1.1", versionCode = 11), index = newer)
        assertEquals(listOf<CardLine>(CardLine.Version("Version 1.1 · 1.2 needs a newer NZStreamer")), behind.lines)
        assertEquals(settingsRemove, behind.buttons)

        // An update that couldn't start: the old version runs on.
        val failedUpdate = card(installed(versionName = "1.1", versionCode = 11, failedUpdate = FailedVersion(12, "1.2", LoadFailure.Other.reason)))
        assertEquals(listOf<CardLine>(CardLine.Warn("Version 1.2 couldn't start: its code didn't load. Version 1.1 runs.")), failedUpdate.lines)
        assertEquals(settingsRemove, failedUpdate.buttons)

        val crashed = card(installed(status = PluginStatus.OffAfterCrash))
        assertEquals(listOf<CardLine>(CardLine.Warn("Turned off after a crash")), crashed.lines)
        assertEquals(buttons(CardAction.TurnOnAgain, CardAction.Remove), crashed.buttons)
        val errored = card(installed(status = PluginStatus.OffAfterError))
        assertEquals(listOf<CardLine>(CardLine.Warn("Turned off after an error")), errored.lines)
        assertEquals(buttons(CardAction.TurnOnAgain, CardAction.Remove), errored.buttons)

        val withdrawnLine = CardLine.Warn("Version 1.1 was withdrawn, so it's off until a fixed version is out.")
        listOf(installed(versionName = "1.1", status = PluginStatus.Withdrawn, withdrawn = true), installed(versionName = "1.1", withdrawn = true)).forEach { plugin ->
            val withdrawn = card(plugin)
            assertEquals(listOf<CardLine>(withdrawnLine), withdrawn.lines, "off, or still running until its swap point")
            assertEquals(buttons(CardAction.Remove), withdrawn.buttons)
        }

        val refused = card(installed(status = PluginStatus.Refused(Refusal.Hash)))
        assertEquals(listOf<CardLine>(CardLine.Warn("Couldn't start: its file changed after it was installed.")), refused.lines)
        assertEquals(buttons(CardAction.InstallAgain, CardAction.Remove), refused.buttons)
        assertEquals(buttons(CardAction.Remove), card(installed(status = PluginStatus.Refused(Refusal.Signer)), installAgain = false).buttons, "the list hasn't it")
        assertEquals(CardLine.Warn("Couldn't start: it isn't signed by NZStreamer."), card(installed(status = PluginStatus.Refused(Refusal.Signer))).lines.single())
        val couldnt = card(installed(status = PluginStatus.CouldntStart(LoadFailure.Linkage)))
        assertEquals(listOf<CardLine>(CardLine.Warn("Couldn't start: its code didn't load.")), couldnt.lines)
        assertEquals(buttons(CardAction.Remove), couldnt.buttons)

        // While plugins are off: its version and Remove alone, whatever its state.
        listOf(installed(), installed(status = PluginStatus.PluginsOff), installed(status = PluginStatus.OffAfterCrash)).forEach { plugin ->
            val off = card(plugin, pluginsOff = true)
            assertEquals(listOf<CardLine>(CardLine.Version("Version 1.2")), off.lines)
            assertEquals(buttons(CardAction.Remove), off.buttons)
        }
        // Install again's own progress shows on the installed card.
        val again = page(listOf(installed(status = PluginStatus.Refused(Refusal.Hash))), kestrel, mapOf("kestrel" to InstallState.Downloading(10))).cards.single()
        assertEquals(buttons(CardAction.Cancel), again.buttons)
        // A failed Install again: its line and Try again, and Remove still there, the plugin still installed.
        val failedAgain = page(
            listOf(installed(status = PluginStatus.Refused(Refusal.Hash))), kestrel, mapOf("kestrel" to InstallState.Failed("Couldn't download it. Check your connection.")),
        ).cards.single()
        assertEquals(listOf<CardLine>(CardLine.Warn("Couldn't download it. Check your connection.")), failedAgain.lines)
        assertEquals(buttons(CardAction.TryAgain, CardAction.Remove), failedAgain.buttons)
    }

    @Test
    fun `a plugin only a newer NZStreamer can run says so, on this phone or this TV, with no button`() {
        val tooNew = list(listed("kestrel", "Kestrel", version(12, "1.2", minor = 1)))
        val phone = page(list = tooNew).cards.single()
        assertEquals(listOf<CardLine>(CardLine.Warn("Needs a newer NZStreamer. Update NZStreamer on this phone to install it.")), phone.lines)
        assertEquals(emptyList(), phone.buttons)
        assertEquals(CardLine.Warn("Needs a newer NZStreamer. Update NZStreamer on this TV to install it."), page(list = tooNew, television = true).cards.single().lines.single())
        // One this device can't run for another reason isn't listed.
        assertEquals(emptyList(), page(list = list(listed("kestrel", "Kestrel", version(12, "1.2", minSdk = 35)))).cards)
    }

    @Test
    fun `with two plugins installed or on their way, a third's Install is greyed`() {
        val index = list(listed("kestrel", "Kestrel", version(12, "1.2")), listed("harbor", "Harbor", version(10, "1.0")), listed("copper", "Copper", version(10, "1.0", size = 500_000)))
        val two = listOf(installed(), installed("harbor", "Harbor", slot = 2, versionName = "1.0", label = "Talks"))
        val third = page(two, index).cards.last()
        assertEquals("copper", third.id)
        assertEquals(listOf(CardLine.Version("Version 1.0 · 0.5 MB"), CardLine.Note("Two plugins are installed already")), third.lines)
        assertEquals(listOf(CardButton(CardAction.Install, enabled = false)), third.buttons)
        // One installed, one downloading: the third waits too.
        val coming = page(listOf(installed()), index, mapOf("harbor" to InstallState.Downloading(5))).cards.last()
        assertEquals(listOf(CardButton(CardAction.Install, enabled = false)), coming.buttons)
        // One installed: the others install.
        assertEquals(buttons(CardAction.Install), page(listOf(installed()), index).cards.last().buttons)
    }

    @Test
    fun `the cards, the installed first in their tabs' order, then those the list offers`() {
        val index = list(listed("copper", "Copper", version(10, "1.0")), listed("kestrel", "Kestrel", version(12, "1.2")), listed("harbor", "Harbor", version(10, "1.0")))
        val plugins = listOf(installed("harbor", "Harbor", slot = 1, label = "Talks"), installed(slot = 2))
        assertEquals(listOf("harbor", "kestrel", "copper"), page(plugins, index).cards.map { it.id })
        // An installed plugin the list doesn't name keeps its card, with no summary; one with no list read at all likewise.
        val unlisted = page(listOf(installed("crown", "Copper Crown", label = "Stage")), index).cards.first()
        assertEquals("Copper Crown", unlisted.name)
        assertNull(unlisted.summary)
        assertEquals(listOf("kestrel"), page(listOf(installed()), PluginListState(failed = true)).cards.map { it.id })
        assertEquals(emptyList(), page(installed = null).cards, "nothing before the registry read its files")
    }

    @Test
    fun `the page's lines, the list being read and no plugins listed`() {
        val kestrel = listed("kestrel", "Kestrel", version(12, "1.2"))
        assertEquals(emptyList(), page(list = list(kestrel)).lines)
        assertNull(page(list = list(kestrel)).placeholder)
        // Offline, with the list kept from the last read and its age.
        listOf(now - 3_600_000 to "today", now - 86_400_000 to "yesterday", now - 3 * 86_400_000 to "3 days ago").forEach { (readAt, age) ->
            val offline = page(list = list(kestrel, readAt = readAt, failed = true))
            assertEquals(listOf("Couldn't reach GitHub. This list is from $age."), offline.lines)
            assertEquals(listOf("kestrel"), offline.cards.map { it.id })
        }
        // Offline with no copy: the installed plugins alone.
        val none = page(listOf(installed()), PluginListState(failed = true))
        assertEquals(listOf("Couldn't reach GitHub. The list shows once it can be read."), none.lines)
        assertNull(none.placeholder)
        // A list in a later format: the installed plugins as they are.
        val later = page(listOf(installed()), PluginListState(tooNew = true, readAt = now))
        assertEquals(listOf("Update NZStreamer on this phone to see new plugins."), later.lines)
        assertEquals(listOf("Update NZStreamer on this TV to see new plugins."), page(list = PluginListState(tooNew = true, readAt = now), television = true).lines)
        assertEquals(listOf("kestrel"), later.cards.map { it.id })
        // Plugins off after two early crashes: the line and its Turn on again, above the cards.
        val off = page(listOf(installed(status = PluginStatus.PluginsOff)), list(kestrel), pluginsOff = true)
        assertEquals(true, off.pluginsOff)
        assertEquals("Plugins are off: NZStreamer closed twice just after starting", PluginsPage.PLUGINS_OFF)
        assertEquals(false, page(list = list(kestrel)).pluginsOff)
        // The first open, the list not read yet: "Reading the plugin list…" in the cards' place.
        assertEquals(PagePlaceholder.Reading, page(list = PluginListState(checking = true)).placeholder)
        assertEquals(PagePlaceholder.Reading, page(list = PluginListState()).placeholder)
        assertEquals("Reading the plugin list…", PluginsPage.READING)
        // A 404: none listed yet.
        val empty = page(list = PluginListState(PluginIndex.EMPTY, readAt = now))
        assertEquals(PagePlaceholder.NoneListed, empty.placeholder)
        assertEquals("No plugins are listed yet", PluginsPage.NONE_LISTED)
        assertNull(page(listOf(installed()), PluginListState(PluginIndex.EMPTY, readAt = now)).placeholder, "an installed plugin's card is there")
    }

    @Test
    fun `Remove asks first, naming the plugin and its tab`() {
        assertEquals("Remove Kestrel?", PluginsPage.removeQuestion("Kestrel"))
        assertEquals(
            "The Sports tab goes, with its settings and saved keys. What you watched stays marked, and Up next keeps what you were watching.",
            PluginsPage.removeText("Sports"),
        )
        assertEquals("Sports", page(listOf(installed()), list()).cards.single().label)
        assertEquals("Kestrel", page(listOf(installed(label = null, status = PluginStatus.Waiting)), list()).cards.single().label, "never loaded")
    }

    @Test
    fun `the hub row's line and warn dot, with one plugin`() {
        val kestrel = list(listed("kestrel", "Kestrel", version(12, "1.2"))).index
        fun line(plugin: InstalledPlugin?, off: Boolean = false, index: PluginIndex? = kestrel) =
            pluginsHubLine(listOfNotNull(plugin), off, index, now, sdk = 34, compat = compat)

        assertEquals(HubLine("None yet"), line(null))
        assertEquals(HubLine("Kestrel"), line(installed()))
        assertEquals(HubLine("Kestrel"), line(installed(status = PluginStatus.Waiting)))
        assertEquals(HubLine("Kestrel"), line(installed(failedUpdate = FailedVersion(13, "1.3", LoadFailure.Other.reason))), "the old version runs on")
        assertEquals(HubLine("Kestrel · updated to 1.2"), line(installed(lastUpdate = LastUpdate("1.2", now - 6 * 86_400_000L))))
        assertEquals(HubLine("Kestrel"), line(installed(lastUpdate = LastUpdate("1.2", now - 7 * 86_400_000L))), "seven days on")
        val newer = list(listed("kestrel", "Kestrel", version(12, "1.2"), version(13, "1.3", minor = 1))).index
        assertEquals(HubLine("Kestrel · update needs a newer NZStreamer"), line(installed(), index = newer))
        // An update that couldn't start, the old version running on: the name alone, as its card says why, whatever else.
        val failed = FailedVersion(13, "1.3", LoadFailure.Other.reason)
        assertEquals(HubLine("Kestrel"), line(installed(failedUpdate = failed, lastUpdate = LastUpdate("1.2", now - 86_400_000L))), "updated this week before")
        assertEquals(HubLine("Kestrel"), line(installed(failedUpdate = failed), index = newer), "a newer version listed too")
        assertEquals(HubLine("Kestrel · turned off after a crash", warn = true), line(installed(status = PluginStatus.OffAfterCrash)))
        assertEquals(HubLine("Kestrel · turned off after an error", warn = true), line(installed(status = PluginStatus.OffAfterError)))
        assertEquals(HubLine("Kestrel · withdrawn, off", warn = true), line(installed(status = PluginStatus.Withdrawn, withdrawn = true)))
        assertEquals(HubLine("Kestrel · couldn't start", warn = true), line(installed(status = PluginStatus.Refused(Refusal.Hash))))
        assertEquals(HubLine("Kestrel · couldn't start", warn = true), line(installed(status = PluginStatus.CouldntStart(LoadFailure.Other))))
        assertEquals(HubLine("Plugins are off: NZStreamer closed twice", warn = true), line(installed(status = PluginStatus.PluginsOff), off = true))
        assertEquals(HubLine("None yet"), line(null, off = true), "nothing to turn off")
    }

    @Test
    fun `the hub row's line with two plugins names both in their tabs' order, and one's state`() {
        val index = list(listed("kestrel", "Kestrel", version(12, "1.2")), listed("harbor", "Harbor Lights", version(12, "1.2"), version(13, "1.3", minor = 1))).index
        fun line(first: InstalledPlugin, second: InstalledPlugin, off: Boolean = false) = pluginsHubLine(listOf(first, second), off, index, now, sdk = 34, compat = compat)
        val kestrel = installed()
        fun harbor(status: PluginStatus = running("harbor", "Stage"), lastUpdate: LastUpdate? = null, withdrawn: Boolean = false) =
            installed("harbor", "Harbor Lights", slot = 2, label = "Stage", status = status, lastUpdate = lastUpdate, withdrawn = withdrawn, versionCode = 13, versionName = "1.3")

        assertEquals(HubLine("Kestrel, Harbor Lights"), line(kestrel, harbor()))
        assertEquals(HubLine("Kestrel, Harbor Lights · Harbor Lights updated to 1.3"), line(kestrel, harbor(lastUpdate = LastUpdate("1.3", now))))
        assertEquals(HubLine("Kestrel, Harbor Lights · Harbor Lights turned off after a crash", warn = true), line(kestrel, harbor(PluginStatus.OffAfterCrash)))
        assertEquals(HubLine("Kestrel, Harbor Lights · Harbor Lights turned off after an error", warn = true), line(kestrel, harbor(PluginStatus.OffAfterError)))
        assertEquals(HubLine("Kestrel, Harbor Lights · Harbor Lights withdrawn, off", warn = true), line(kestrel, harbor(PluginStatus.Withdrawn, withdrawn = true)))
        assertEquals(HubLine("Kestrel, Harbor Lights · Harbor Lights couldn't start", warn = true), line(kestrel, harbor(PluginStatus.Refused(Refusal.Signer))))
        val behind = installed("harbor", "Harbor Lights", slot = 2, label = "Stage", status = running("harbor", "Stage"), versionCode = 12)
        assertEquals(HubLine("Kestrel, Harbor Lights · Harbor Lights's update needs a newer NZStreamer"), line(kestrel, behind))
        val failedSwap = installed(
            "harbor", "Harbor Lights", slot = 2, label = "Stage", status = running("harbor", "Stage"), versionCode = 12,
            failedUpdate = FailedVersion(13, "1.3", LoadFailure.Other.reason), lastUpdate = LastUpdate("1.2", now),
        )
        assertEquals(HubLine("Kestrel, Harbor Lights"), line(kestrel, failedSwap), "an update that couldn't start: the names alone")
        // Both in a state: the first in tab order with the dot, else the first in tab order.
        val updated = installed(lastUpdate = LastUpdate("1.2", now))
        assertEquals(HubLine("Kestrel, Harbor Lights · Harbor Lights turned off after a crash", warn = true), line(updated, harbor(PluginStatus.OffAfterCrash)))
        assertEquals(HubLine("Kestrel, Harbor Lights · Kestrel updated to 1.2"), line(updated, harbor(lastUpdate = LastUpdate("1.3", now))))
        assertEquals(HubLine("Kestrel, Harbor Lights · Kestrel turned off after an error", warn = true), line(installed(status = PluginStatus.OffAfterError), harbor(PluginStatus.OffAfterCrash)))
        assertEquals(HubLine("Plugins are off: NZStreamer closed twice", warn = true), line(kestrel, harbor(), off = true))
    }

    @Test
    fun `plugin tabs follow Discover where the build has it, else Home`() {
        val discover = object : AppExtras {
            override fun tabs(formFactor: FormFactor) = listOf(ExtraTab(Tab("Discover", Icons.Filled.Star, HomeRoute, HomeRoute::class), after = HomeRoute::class))
            override fun NavGraphBuilder.screens(nav: NavController, container: AppContainer, formFactor: FormFactor) = Unit
        }
        assertEquals("Discover", pluginTabsFollow(FormFactor.Phone, discover))
        assertEquals("Discover", pluginTabsFollow(FormFactor.Tv, discover))
        assertEquals("Home", pluginTabsFollow(FormFactor.Phone, extras = null))
        assertEquals("Home", pluginTabsFollow(FormFactor.Tv, extras = null))
    }

    @Test
    fun `a TV's first focus, by the page's state`() {
        val kestrel = listed("kestrel", "Kestrel", version(12, "1.2"))
        val harbor = listed("harbor", "Harbor", version(10, "1.0"))
        fun button(id: String, action: CardAction) = PluginsFocus.Button(id, action)

        // Plugins off: Turn on again above the cards.
        assertEquals(PluginsFocus.TurnPluginsOn, page(listOf(installed(status = PluginStatus.PluginsOff)), list(kestrel), pluginsOff = true, television = true).firstFocus())
        // The first card's first enabled button: Install, Settings, Turn on again.
        assertEquals(button("kestrel", CardAction.Install), page(list = list(kestrel)).firstFocus())
        assertEquals(button("kestrel", CardAction.Settings), page(listOf(installed()), list(kestrel)).firstFocus())
        assertEquals(button("kestrel", CardAction.Remove), page(listOf(installed()), list(kestrel), slots = false).firstFocus())
        assertEquals(button("kestrel", CardAction.TurnOnAgain), page(listOf(installed(status = PluginStatus.OffAfterCrash)), list(kestrel)).firstFocus())
        assertEquals(button("kestrel", CardAction.TurnOnAgain), page(listOf(installed(status = PluginStatus.OffAfterError)), list(kestrel)).firstFocus())
        // No button on any card, no card, the list being read: Check again.
        assertEquals(PluginsFocus.CheckAgain, page(list = list(listed("kestrel", "Kestrel", version(12, "1.2", minor = 1)))).firstFocus())
        assertEquals(PluginsFocus.CheckAgain, page(list = PluginListState(PluginIndex.EMPTY, readAt = now)).firstFocus())
        assertEquals(PluginsFocus.CheckAgain, page(list = PluginListState(checking = true)).firstFocus())
        assertEquals(PluginsFocus.CheckAgain, page(list = PluginListState(failed = true)).firstFocus())
        // A greyed button takes no focus.
        val third = listed("copper", "Copper", version(10, "1.0"))
        val two = listOf(installed(status = PluginStatus.OffAfterCrash), installed("harbor", "Harbor", slot = 2, label = "Talks", status = PluginStatus.Withdrawn, withdrawn = true))
        assertEquals(listOf(button("kestrel", CardAction.TurnOnAgain), button("kestrel", CardAction.Remove), button("harbor", CardAction.Remove), PluginsFocus.CheckAgain), page(two, list(kestrel, harbor, third)).focusables())
        // Opened for a plugin (Open Plugins): its card's first button.
        val both = page(listOf(installed(), installed("harbor", "Harbor", slot = 2, label = "Talks", status = PluginStatus.Refused(Refusal.Hash))), list(kestrel, harbor))
        assertEquals(button("harbor", CardAction.InstallAgain), both.firstFocus(opening = "harbor"))
        assertEquals(button("kestrel", CardAction.Settings), both.firstFocus(opening = "copper"), "one not listed: the page's own")
    }

    @Test
    fun `a TV's focus after each change goes to what replaced the button that went`() {
        val kestrel = list(listed("kestrel", "Kestrel", version(12, "1.2")))
        fun on(id: String = "kestrel", action: CardAction) = PluginsFocus.Button(id, action)
        fun after(before: PluginsPage, focused: PluginsFocus, next: PluginsPage, auto: Boolean = false) = PluginsPage.focusAfter(before, focused, next, auto)
        val available = page(list = kestrel)
        val downloading = page(list = kestrel, installs = mapOf("kestrel" to InstallState.Downloading(10)))
        val further = page(list = kestrel, installs = mapOf("kestrel" to InstallState.Downloading(60)))
        val checking = page(list = kestrel, installs = mapOf("kestrel" to InstallState.Checking))
        val done = page(listOf(installed()), kestrel, mapOf("kestrel" to InstallState.Installed(1)))
        val doneNoSlots = page(listOf(installed()), kestrel, mapOf("kestrel" to InstallState.Installed(1)), slots = false)
        val failed = page(list = kestrel, installs = mapOf("kestrel" to InstallState.Failed("Couldn't download it. Check your connection.")))
        val running = page(listOf(installed()), kestrel)

        // Install to Cancel, which stays as it downloads and is checked.
        assertEquals(on(action = CardAction.Cancel), after(available, on(action = CardAction.Install), downloading))
        assertEquals(on(action = CardAction.Cancel), after(downloading, on(action = CardAction.Cancel), further))
        assertEquals(on(action = CardAction.Cancel), after(further, on(action = CardAction.Cancel), checking))
        // Cancel to Open <label> once installed, else its first button; to Try again on a failure; to Install when cancelled.
        assertEquals(on(action = CardAction.Open), after(checking, on(action = CardAction.Cancel), done))
        assertEquals(on(action = CardAction.Remove), after(checking, on(action = CardAction.Cancel), doneNoSlots))
        assertEquals(on(action = CardAction.TryAgain), after(downloading, on(action = CardAction.Cancel), failed))
        assertEquals(on(action = CardAction.Install), after(downloading, on(action = CardAction.Cancel), available))
        assertEquals(on(action = CardAction.Cancel), after(failed, on(action = CardAction.TryAgain), downloading))
        // Remove to the card's Install once removed; a card that goes with it, to Check again.
        assertEquals(on(action = CardAction.Install), after(running, on(action = CardAction.Remove), available))
        assertEquals(PluginsFocus.CheckAgain, after(running, on(action = CardAction.Remove), page(list = list())))
        // Turn on again to the card's first button as it runs; Install again to Cancel.
        val crashed = page(listOf(installed(status = PluginStatus.OffAfterCrash)), kestrel)
        assertEquals(on(action = CardAction.Settings), after(crashed, on(action = CardAction.TurnOnAgain), running))
        val refused = page(listOf(installed(status = PluginStatus.Refused(Refusal.Hash))), kestrel)
        val again = page(listOf(installed(status = PluginStatus.Refused(Refusal.Hash))), kestrel, mapOf("kestrel" to InstallState.Downloading(0)))
        assertEquals(on(action = CardAction.Cancel), after(refused, on(action = CardAction.InstallAgain), again))
        // Install again gone with the list's version: the card's next button.
        val noAgain = page(listOf(installed(status = PluginStatus.Refused(Refusal.Hash))), kestrel, installAgain = { false })
        assertEquals(on(action = CardAction.Remove), after(refused, on(action = CardAction.InstallAgain), noAgain))
        // The page's Turn on again: the first card's first button.
        val off = page(listOf(installed(status = PluginStatus.PluginsOff)), kestrel, pluginsOff = true)
        assertEquals(on(action = CardAction.Settings), after(off, PluginsFocus.TurnPluginsOn, running))
        // Check again keeps the focus while it reads, and after.
        val reading = page(listOf(installed()), list(listed("kestrel", "Kestrel", version(12, "1.2")), checking = true))
        assertEquals(PluginsFocus.CheckAgain, after(running, PluginsFocus.CheckAgain, reading))
        assertEquals(PluginsFocus.CheckAgain, after(reading, PluginsFocus.CheckAgain, running))
        // A first focus the page put on Check again, as nothing else was there, goes to the list's first button once it shows;
        // not where the user put it there.
        val first = page(list = PluginListState(checking = true))
        assertEquals(on(action = CardAction.Install), after(first, PluginsFocus.CheckAgain, available, auto = true))
        assertEquals(PluginsFocus.CheckAgain, after(first, PluginsFocus.CheckAgain, available, auto = false))
        // A focused button still there keeps it; one greyed (another plugin on its way) loses it to its card's next, else
        // Check again.
        val index = list(listed("kestrel", "Kestrel", version(12, "1.2")), listed("harbor", "Harbor", version(10, "1.0")), listed("copper", "Copper", version(10, "1.0")))
        val one = page(listOf(installed()), index)
        val coming = page(listOf(installed()), index, mapOf("harbor" to InstallState.Checking))
        assertEquals(on(action = CardAction.Settings), after(one, on(action = CardAction.Settings), coming))
        assertEquals(PluginsFocus.CheckAgain, after(one, on("copper", CardAction.Install), coming))
        assertEquals(PluginsFocus.CheckAgain, after(one, on("copper", CardAction.Install), page(listOf(installed(), installed("harbor", "Harbor", slot = 2, label = "Talks")), index)))
    }
}
