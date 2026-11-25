package app.nzstreamer.plugin.sportarr

import app.nzstreamer.plugin.sportarr.source.Connection
import app.nzstreamer.plugin.sportarr.source.KeptList
import app.nzstreamer.plugin.sportarr.source.ListReader
import app.nzstreamer.plugin.sportarr.source.Refresh
import app.nzstreamer.plugin.sportarr.source.SportarrClient
import app.nzstreamer.plugin.sportarr.ui.SetupModel
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
}
