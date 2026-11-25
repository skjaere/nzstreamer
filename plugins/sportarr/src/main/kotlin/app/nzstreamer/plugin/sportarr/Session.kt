package app.nzstreamer.plugin.sportarr

import app.nzstreamer.plugin.sportarr.family.Matchers
import app.nzstreamer.plugin.sportarr.match.Nouns
import app.nzstreamer.plugin.sportarr.match.PartPicker
import app.nzstreamer.plugin.sportarr.match.Runner
import app.nzstreamer.plugin.sportarr.model.Item
import app.nzstreamer.plugin.sportarr.model.Sports
import app.nzstreamer.plugin.sportarr.source.Connection
import app.nzstreamer.plugin.sportarr.source.KeptList
import app.nzstreamer.plugin.sportarr.source.ListReader
import app.nzstreamer.plugin.sportarr.source.Refresh
import app.nzstreamer.plugin.sportarr.source.SportarrClient
import app.nzstreamer.plugin.sportarr.ui.KeptView
import app.nzstreamer.plugin.sportarr.ui.ListModel
import app.nzstreamer.plugin.sportarr.ui.SetupModel
import io.skjaere.usenet.plugin.PlayItem
import io.skjaere.usenet.plugin.PluginHost
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.Instant
import java.time.ZoneId

/**
 * What one loaded instance of the plugin keeps while it runs, made as its first page draws, from its [host]: the user's
 * Sportarr as the host's secrets hold it ([connection], L640), and the list's [refresh] in the host's scope, with its file
 * in the plugin's own folder (L641, L645). Every page and the settings page share it, so a Save on one reads the list the
 * tab shows.
 */
internal class Session(private val host: PluginHost) {
    val connection = Connection(host.secrets)

    /** The list, read in the device's zone as the host's clock gives the time. */
    val refresh = Refresh(host.scope, connection, KeptList(host.dataDir), Instant::now) { saved ->
        ListReader(SportarrClient(host.http, saved.address, saved.key, host::addressSafety), Instant.now(), ZoneId.systemDefault()).read()
    }

    /** Whether an address and a key are saved, as that changes: the tab is the setup until both are. */
    val setUp: Flow<Boolean> = combine(host.secrets.has(Connection.URL), host.secrets.has(Connection.KEY)) { url, key -> url && key }

    /** The setup form's model on a [tv] or a phone, [restored] where its page's activity was made again; a Save reads the list. */
    fun setup(tv: Boolean, restored: SetupModel.Restored?): SetupModel =
        SetupModel(connection, host.http, host::addressSafety, tv, { changed -> refresh.saved(changed) }, restored)

    /**
     * The list's model on a [tv] or a phone, [restored] where its page comes back (Back from an event's page, its activity
     * made again): its view's choices in the plugin's folder, the time as the host's clock gives it in the device's zone.
     */
    fun list(tv: Boolean, restored: ListModel.Restored?): ListModel =
        ListModel(refresh.state, host.watch, KeptView(host.dataDir), tv, Instant::now, ZoneId::systemDefault, ::play, restored)

    /** Finds a part's releases, for a play alone. */
    private val runner = Runner(host.search)

    /**
     * Plays [item] of [sports] from the list's Up next, named [title] as the row names it, through NZStreamer's play: the
     * release that played it before, else the releases the runner finds, which the host asks for only as it plays. The
     * event pages build their plays with more (Search indexers' words on the no-release sheet); Up next's rows play a
     * part begun or the next one, which the host has played or will search for.
     */
    fun play(item: Item, sports: Sports, title: String) {
        val event = item.event
        val part = item.part
        val matcher = Matchers(sports).of(event)
        val played = PlayItem(
            key = part.key, title = title, subtitle = event.league.name, runtimeMinutes = part.runtimeMinutes, noun = Nouns.item(event, part),
            picker = PartPicker.of(matcher, event, part),
        )
        host.play.play(played) { runner.releases(matcher, event, part) }
    }
}
