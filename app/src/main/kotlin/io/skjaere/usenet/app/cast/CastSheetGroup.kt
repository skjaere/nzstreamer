package io.skjaere.usenet.app.cast

import android.content.Context
import android.view.View
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.skjaere.usenet.app.casting.CAST_DEVICES
import io.skjaere.usenet.app.casting.CAST_LOOKING
import io.skjaere.usenet.app.casting.CAST_LOOKING_SAID
import io.skjaere.usenet.app.casting.CAST_LOOK_FOR
import io.skjaere.usenet.app.casting.CAST_LOOK_LINE
import io.skjaere.usenet.app.casting.CAST_NONE_FOUND
import io.skjaere.usenet.app.casting.CAST_PRIVACY
import io.skjaere.usenet.app.casting.CAST_SIMPLE
import io.skjaere.usenet.app.casting.CAST_UNAVAILABLE
import io.skjaere.usenet.app.casting.CAST_UNAVAILABLE_WHY
import io.skjaere.usenet.app.casting.CastLauncher
import io.skjaere.usenet.app.casting.CastDeviceKind
import io.skjaere.usenet.app.casting.CastNow
import io.skjaere.usenet.app.casting.CastRowLook
import io.skjaere.usenet.app.casting.CastSessionEvents
import io.skjaere.usenet.app.casting.castRowDescription
import io.skjaere.usenet.app.casting.castRowLook
import io.skjaere.usenet.app.casting.castTimings
import io.skjaere.usenet.app.casting.connectedAnnouncement
import io.skjaere.usenet.app.casting.connectingAnnouncement
import io.skjaere.usenet.app.link.CastConnectedIcon
import io.skjaere.usenet.app.link.CastIcon
import io.skjaere.usenet.app.link.DeviceRow
import io.skjaere.usenet.app.link.PlayChoice
import io.skjaere.usenet.app.link.PlayOn
import io.skjaere.usenet.app.link.RowEnd
import io.skjaere.usenet.app.link.RowLine
import io.skjaere.usenet.app.link.SpeakerIcon
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.saveOrLog
import io.skjaere.usenet.app.setup.LanAddress
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.data.PairedTv
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket

/** Google's side of the sheet's group and of the cast service in seams (L73), so a device test runs them over fakes with no Google call. */
object CastSeams {
    /** Where the group's devices come from: MediaRouter's routes. */
    @VisibleForTesting
    @Volatile
    var routes: (Context) -> CastRouteSource = ::MediaRouterSource

    /** Who connects and ends sessions: Google's software, one for the process. */
    @VisibleForTesting
    @Volatile
    var connector: (Context) -> CastConnector = { google(it) }

    /** What TalkBack is told once, as a tap connects and as it is connected (L95). */
    @VisibleForTesting
    @Volatile
    var announce: (View, String) -> Unit = { view, words ->
        // The sheet closes as the device connects, so no node of its own could say it in a live region.
        @Suppress("DEPRECATION")
        view.announceForAccessibility(words)
    }

    /** What starts a cast play on its device (L65, L85): the cast service, its plays and their loads. */
    @VisibleForTesting
    @Volatile
    var launcher: (Context) -> CastLauncher = { CastPlays.with(it) }

    /** What loads a cast play's launch on its device over the service and follows it there (L60, L65, L68). */
    @VisibleForTesting
    @Volatile
    internal var loader: (Context) -> CastLoader = { Casting.with(it) }

    /** What Google's software says of the session and the device's media (L60), as the service hears it. */
    @VisibleForTesting
    @Volatile
    var events: (Context) -> CastSessionEvents = { GoogleCast.connection.events }

    /** The phone's address as it goes (L74): where the cast service's server listens. */
    @VisibleForTesting
    @Volatile
    var addresses: (Context) -> Flow<Inet4Address?> = LanAddress::watch

    /**
     * Whether an address is the phone's now (L74): a socket binds there, on any port, whatever the address flow has said
     * so far. Off the main thread.
     */
    @VisibleForTesting
    @Volatile
    var present: (InetAddress) -> Boolean = { address ->
        try {
            ServerSocket().use { it.bind(InetSocketAddress(address, 0)) }
            true
        } catch (e: IOException) {
            false
        }
    }

    private var google: GoogleConnector? = null

    private fun google(context: Context): GoogleConnector = google ?: GoogleConnector(context).also { google = it }
}

/**
 * The Play on sheet's Cast group, headed "Cast to" (L78, W47; CsPhoneSheet, CsPhoneConnecting, CsPhoneLookFor), between
 * the TVs and the rule. Its first composition is the sheet's opening, where the gate starts Google's software if it may
 * (L111): it opens again as the opt-in is read or turned on, in the same sheet. On a TV nothing shows. Unavailable: one
 * greyed row saying so, and no more. The opt-in off: "Look for Cast devices" with its line and Privacy ([onPrivacy],
 * the privacy page, L138), and nothing of Google's runs; a tap turns it on, and the group looks at once ("Looking for
 * Cast devices", once, to TalkBack). Turned off while casting, the session goes on until it ends (L112): its device's
 * block stays over the list, with Stop casting (L117). Not read yet: the heading alone, for a moment. Started: the line
 * about simple files, then while it looks (an active scan while the sheet shows) a spinner, after [castTimings]' 10 s
 * with none found the words for that, else the devices ([CastRows]: the screens by name, each Google's Cast icon, then
 * the speakers and the speaker groups, each Material's speaker), each with its own line: one at the address of one of
 * your [tvs] says it also runs NZStreamer (L121), a speaker that it plays only the sound (W50). A tap connects
 * ("Connecting…", a spinner in place of ✓; the sheet stays open, W34) and chooses the device in [playOn]; a busy device
 * is taken over at once (W32). Connected, the sheet closes; refused or not connected in 15 s, its row says so and This
 * phone is chosen again. The connected device's row keeps its ✓, and says what plays there while a cast play lives; its
 * Stop casting is the block's (L117). TalkBack hears "Cast device" after each name, and each step once. A tap goes first
 * to [onCastTapped], if there is one, the chosen device's too: answering true, it takes the tap, and the group neither
 * connects nor chooses (L151); the player's Play & connect moves its film to a device connected already that way (L124).
 */
@Composable
internal fun CastSheetGroup(playOn: PlayOn, television: Boolean, tvs: List<PairedTv>, onPrivacy: () -> Unit, onCastTapped: ((CastRoute) -> Boolean)? = null) {
    val context = LocalContext.current.applicationContext
    val container = LocalContext.current.container
    val view = LocalView.current
    // The opt-in (L112), null until settings.json is read: off until then, so nothing starts before it is known.
    val looking by container.castLooking.collectAsStateWithLifecycle()
    // The sheet's opening, and the flag read or turned on while it shows: the gate starts Google's software here, once it
    // may (L111).
    val gate = remember(looking) { CastGate.open(context, television, looking) }
    val look = castGroupLook(gate, looking)
    if (look == CastGroupLook.None) return
    Text(
        CAST_DEVICES.uppercase(), style = AppType.section, color = colors.muted,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = if (look == CastGroupLook.Devices) 2.dp else 6.dp)
            .semantics { heading(); contentDescription = CAST_DEVICES },
    )
    when (look) {
        CastGroupLook.None, CastGroupLook.Heading -> return
        CastGroupLook.LookFor -> {
            LookForRow(
                onLook = {
                    CastSeams.announce(view, CAST_LOOKING_SAID)
                    container.appScope.launch { saveOrLog("turn on Look for Cast devices") { container.settings.updateCast { it.copy(look = true) } } }
                },
                onPrivacy = onPrivacy,
            )
            return
        }
        CastGroupLook.Unavailable -> {
            DeviceRow(CastIcon, CAST_UNAVAILABLE, chosen = false, greyed = true, enabled = false, line = RowLine(CAST_UNAVAILABLE_WHY, RowLine.Tone.Muted)) {}
            return
        }
        CastGroupLook.Devices -> Unit
    }
    Text(CAST_SIMPLE, style = AppType.small, color = colors.muted, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 6.dp))
    val connection = GoogleCast.connection
    var routes by remember { mutableStateOf<List<CastRoute>?>(null) }
    DisposableEffect(Unit) {
        connection.attach(CastSeams.connector(context), context)
        val watching = CastSeams.routes(context).watch { routes = CastRows.of(it) }
        val showing = connection.showing()
        onDispose {
            watching.close()
            showing.close()
        }
    }
    var waited by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(castTimings().noneFoundMillis)
        waited = true
    }
    val now by connection.now.collectAsStateWithLifecycle()
    val failed by connection.failed.collectAsStateWithLifecycle()
    // What plays on the connected device, for its row's line (L78).
    val casting = LocalContext.current.container.castPlaying
    val played by remember(casting) { casting?.now ?: MutableStateFlow<CastNow?>(null) }.collectAsStateWithLifecycle()
    // The device a sheet's tap connects, kept by the connection, so a sheet made again meanwhile (a rotation, the sheet
    // closed and opened) still does it: once connected, TalkBack hears so and the sheet closes (W34).
    LaunchedEffect(now) {
        val live = now ?: return@LaunchedEffect
        if (connection.tappedConnected(live.route)) {
            CastSeams.announce(view, connectedAnnouncement(live.name))
            playOn.closeSheet()
        }
    }
    val found = routes.orEmpty()
    if (found.isEmpty()) {
        WaitRow(if (waited) CAST_NONE_FOUND else CAST_LOOKING, looking = !waited)
        return
    }
    found.forEach { route ->
        val look = castRowLook(route.id, CastRows.line(route, tvs), now, failed == route.id, played?.takeIf { it.route == route.id && it.live }?.label)
        CastRouteRow(look, route.name, route.kind) {
            // Taken by whoever showed the list, which does what it needs with the device (L151): nothing connects here.
            if (onCastTapped?.invoke(route) == true) return@CastRouteRow
            // Connecting or casting already: nothing more (Stop casting is the block's, L117).
            if (look.chosen) return@CastRouteRow
            CastSeams.announce(view, connectingAnnouncement(route.name))
            // The session says connecting before the device is chosen, so Play on never sees a choice without its session.
            connection.connect(route)
            playOn.choose(PlayChoice.Cast(route.id, route.name))
        }
    }
}

/**
 * A device's row by its [look] ([castRowLook]): Google's Cast icon for a screen, Material's speaker for a speaker or a
 * speaker group ([kind]), Google's filled Cast icon in the accent while its session lives; its [name] and line, and at
 * its end the ✓ or a spinner. A tap goes to [onTap], the chosen row's too, whose group says what it does.
 */
@Composable
private fun CastRouteRow(look: CastRowLook, name: String, kind: CastDeviceKind, onTap: () -> Unit) {
    DeviceRow(
        when {
            look.live -> CastConnectedIcon
            kind == CastDeviceKind.Video -> CastIcon
            else -> SpeakerIcon
        },
        name, chosen = look.chosen, line = look.line,
        iconTint = if (look.live) colors.accent else null, description = castRowDescription(name, look.line),
        trailing = when (look.end) {
            RowEnd.None -> null
            RowEnd.Spinner -> {
                { CircularProgressIndicator(Modifier.padding(horizontal = 1.dp).size(20.dp), strokeWidth = 2.dp, color = colors.accent, trackColor = colors.outline) }
            }
        },
        onClick = onTap,
    )
}

/** What the Cast group shows (L111, L112; CsPhoneLookFor), by the gate's word at its opening and the opt-in. */
internal enum class CastGroupLook {
    /** Nothing, not even its heading: a TV (W28, L67). */
    None,

    /** "Cast to" alone, while the opt-in isn't read yet (L112): the row would flash for someone who chose it. */
    Heading,

    /** "Look for Cast devices", its line and Privacy: the opt-in off, nothing of Google's started for it (L112). */
    LookFor,

    /** One greyed row: Cast isn't available on this phone, as Play services is missing or old, or Cast failed to start. */
    Unavailable,

    /** The line about simple files, then the devices as they are found (C1's group). */
    Devices;

    /** The group's own words as TalkBack reads them, from its heading to the first device (CsPhoneTalkBackC2 2-4). */
    val spoken: List<String>
        get() = when (this) {
            None -> emptyList()
            Heading -> listOf("$CAST_DEVICES, heading")
            LookFor -> listOf("$CAST_DEVICES, heading", "$CAST_LOOK_FOR, button", CAST_LOOK_LINE, "$CAST_PRIVACY, link")
            Unavailable -> listOf("$CAST_DEVICES, heading", CAST_UNAVAILABLE, CAST_UNAVAILABLE_WHY)
            Devices -> listOf("$CAST_DEVICES, heading", CAST_SIMPLE)
        }
}

/** The group by what the gate said at the sheet's opening ([gate]) and the opt-in as read ([looking], null: not yet). */
internal fun castGroupLook(gate: CastGateState, looking: Boolean?): CastGroupLook = when (gate) {
    CastGateState.Television -> CastGroupLook.None
    CastGateState.Unavailable -> CastGroupLook.Unavailable
    CastGateState.Started -> CastGroupLook.Devices
    CastGateState.NotAsked -> if (looking == false) CastGroupLook.LookFor else CastGroupLook.Heading
}

/**
 * The opt-in (W46, L112; CsPhoneLookFor): Google's Cast icon and the words in the accent, as Add a TV is, over the line
 * saying what Google's software sends; Privacy under it, its own link to the privacy page ([onPrivacy], L138). TalkBack
 * reads the row as a button, then its line, then the link (CsPhoneTalkBackC2 3-4).
 */
@Composable
private fun LookForRow(onLook: () -> Unit, onPrivacy: () -> Unit) {
    Row(
        Modifier.padding(horizontal = 8.dp).fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onLook).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(CastIcon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(CAST_LOOK_FOR, style = AppType.bodyStrong, color = colors.accent)
            // Read after the button, as written, rather than as part of its name.
            Text(CAST_LOOK_LINE, style = AppType.small, color = colors.muted, modifier = Modifier.semantics(mergeDescendants = true) {})
        }
    }
    // Under the words, 56 dp in.
    val privacy = buildAnnotatedString {
        withLink(LinkAnnotation.Clickable(PRIVACY_TAG, TextLinkStyles(SpanStyle(color = colors.accent, fontWeight = FontWeight.SemiBold))) { onPrivacy() }) {
            append(CAST_PRIVACY)
        }
    }
    Box(Modifier.padding(start = 56.dp, end = 20.dp).heightIn(min = 40.dp), contentAlignment = Alignment.CenterStart) {
        Text(privacy, style = AppType.secondary)
    }
}

private const val PRIVACY_TAG = "privacy"

/** While the group looks, a spinner where a device's icon goes; with none found, the Cast icon muted. */
@Composable
private fun WaitRow(words: String, looking: Boolean) {
    Row(
        Modifier.padding(horizontal = 8.dp).fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (looking) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = colors.accent, trackColor = colors.outline)
            } else {
                Icon(CastIcon, contentDescription = null, tint = colors.muted, modifier = Modifier.size(24.dp))
            }
        }
        Text(words, style = AppType.body, color = colors.muted)
    }
}
