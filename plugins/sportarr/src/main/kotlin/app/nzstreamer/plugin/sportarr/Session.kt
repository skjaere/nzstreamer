package app.nzstreamer.plugin.sportarr

import app.nzstreamer.plugin.sportarr.bouts.Cards
import app.nzstreamer.plugin.sportarr.bouts.CardsOption
import app.nzstreamer.plugin.sportarr.bouts.IndexFile
import app.nzstreamer.plugin.sportarr.bouts.KeptCards
import app.nzstreamer.plugin.sportarr.bouts.WikipediaBase
import app.nzstreamer.plugin.sportarr.bouts.WikipediaReads
import app.nzstreamer.plugin.sportarr.model.Item
import app.nzstreamer.plugin.sportarr.model.Sports
import app.nzstreamer.plugin.sportarr.source.Connection
import app.nzstreamer.plugin.sportarr.source.KeptList
import app.nzstreamer.plugin.sportarr.source.ListReader
import app.nzstreamer.plugin.sportarr.source.Refresh
import app.nzstreamer.plugin.sportarr.source.SportarrClient
import app.nzstreamer.plugin.sportarr.ui.KeptView
import app.nzstreamer.plugin.sportarr.ui.ListModel
import app.nzstreamer.plugin.sportarr.ui.ListPlace
import app.nzstreamer.plugin.sportarr.ui.PageModel
import app.nzstreamer.plugin.sportarr.ui.Plays
import app.nzstreamer.plugin.sportarr.ui.SetupModel
import io.skjaere.usenet.plugin.PluginHost
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Instant
import java.time.ZoneId

/**
 * What one loaded instance of the plugin keeps while it runs, made as its first page draws, from its [host]: the user's
 * Sportarr as the host's secrets hold it ([connection], L640), and the list's [refresh] in the host's scope, with its file
 * in the plugin's own folder (L641, L645); and UFC events' fight [cards], read from Wikipedia at [wikipediaBase] (the build
 * type's, a test's own). Every page and the settings page share it, so a Save on one reads the list the tab shows, and the
 * switch on settings is the one the pages follow.
 */
internal class Session(private val host: PluginHost, private val wikipediaBase: String = WikipediaBase.URL) {
    val connection = Connection(host.secrets)

    /** The list, read in the device's zone as the host's clock gives the time. */
    val refresh = Refresh(host.scope, connection, KeptList(host.dataDir), Instant::now) { saved ->
        ListReader(SportarrClient(host.http, saved.address, saved.key, host::addressSafety), Instant.now(), ZoneId.systemDefault()).read()
    }

    /**
     * UFC events' fight cards (L-B5, L-B6, L-B8): read in the host's scope as their pages open, one at a time, the time as the
     * host's clock gives it; the cards and the switch kept in the plugin's own folder, the index's rows in its cache folder.
     */
    val cards = Cards(
        host.scope,
        WikipediaReads(host.http, wikipediaBase, Instant::now, IndexFile(host.cacheDir)),
        KeptCards(host.dataDir),
        CardsOption(host.dataDir),
        Instant::now,
    )

    /** Whether an address and a key are saved, as that changes: the tab is the setup until both are. */
    val setUp: Flow<Boolean> = combine(host.secrets.has(Connection.URL), host.secrets.has(Connection.KEY)) { url, key -> url && key }

    /**
     * Whether the list comes back where it was left: from a page or the settings it opened, or its activity made again;
     * else, each time the tab opens, Recent (L645). In memory, as long as the plugin runs.
     */
    val listPlace = ListPlace()

    /**
     * The setup form's model on a [tv] or a phone, [restored] where its page's activity was made again; a Save reads the
     * list. The settings page's has the [eventPages] part, with the switch of the session's [cards].
     */
    fun setup(tv: Boolean, restored: SetupModel.Restored?, eventPages: Boolean = false): SetupModel =
        SetupModel(connection, host.http, host::addressSafety, tv, { changed -> refresh.saved(changed) }, restored, cards = cards.takeIf { eventPages })

    /**
     * The list's model on a [tv] or a phone, [restored] where its page comes back (Back from an event's page, its activity
     * made again): its view's choices in the plugin's folder, the time as the host's clock gives it in the device's zone.
     */
    fun list(tv: Boolean, restored: ListModel.Restored?): ListModel =
        ListModel(refresh.state, host.watch, KeptView(host.dataDir), tv, Instant::now, ZoneId::systemDefault, ::play, restored)

    /** How the pages and Up next play a part, and the lines Play and Choose release leave when they find nothing. */
    val plays = Plays(host.play, host.watch, host.search, host.scope, Instant::now)

    /**
     * An event's or a weekend's page ([page], [key]) on a [tv] or a phone, over the list the tab shows; a UFC card's with its
     * fight card from [cards], its credit linking to the article on the session's Wikipedia.
     */
    fun page(page: String, key: String, tv: Boolean): PageModel =
        PageModel(refresh.state, host.watch, plays, page, key, tv, Instant::now, ZoneId::systemDefault, cards = cards, wikipedia = wikipediaBase)

    /** Plays [item] of [sports] from the list's Up next, as its page would. */
    fun play(item: Item, sports: Sports) {
        plays.play(sports, item.event, item.part)
    }
}
