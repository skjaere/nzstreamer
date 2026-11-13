package io.skjaere.usenet.app.link

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.activity.compose.BackHandler
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.skjaere.usenet.app.casting.CAST_INTRO_GOT_IT
import io.skjaere.usenet.app.casting.CAST_INTRO_TEXT
import io.skjaere.usenet.app.casting.CAST_INTRO_TITLE
import io.skjaere.usenet.app.casting.CastIntroRule
import io.skjaere.usenet.app.casting.CastIntroRule.Over
import io.skjaere.usenet.app.casting.CastPlaying
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.play.PlayState
import io.skjaere.usenet.app.play.PlaybackCoordinator
import io.skjaere.usenet.app.saveOrLog
import io.skjaere.usenet.app.setup.LanAddress
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.PrimaryButton
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.tryFocus
import kotlinx.coroutines.launch

/**
 * Where the Cast button shows, for its introduction (L123): a screen's button reports itself here as it is placed, and the
 * introduction circles it, presses it and gives it the focus. The phone's shell holds one ([LocalCastButtonSpot]); the
 * button last placed there is the one, until it leaves the screen. The player holds its own, to give its button the
 * focus back as Play on's list closes.
 */
@Stable
class CastButtonSpot {
    /** The button placed now; none on a screen without one, or one scrolled out of sight. */
    var placed: Placed? by mutableStateOf(null)
        private set

    /** A placed button: its [bounds] in the shell's window, [press] as a tap on it does, [focus] given to it. */
    class Placed internal constructor(internal val owner: Any, val bounds: Rect, val press: () -> Unit, val focus: () -> Unit)

    /** The button [owner] is at [bounds] now, pressed by [press] and focused by [focus]. */
    internal fun place(owner: Any, bounds: Rect, press: () -> Unit, focus: () -> Unit) {
        if (bounds.isEmpty) return left(owner)
        val now = placed
        if (now != null && now.owner === owner && now.bounds == bounds) return
        placed = Placed(owner, bounds, press, focus)
    }

    /** The button [owner] left the screen. */
    internal fun left(owner: Any) {
        if (placed?.owner === owner) placed = null
    }
}

/**
 * The shell's spot for its Cast button, or the player's: none where nothing introduces it or gives it the focus (a TV, a
 * test's own screens).
 */
val LocalCastButtonSpot = staticCompositionLocalOf<CastButtonSpot?> { null }

/**
 * Whether the introduction moves the focus (L123), to Got it as it appears and to the Cast button once it closes: for
 * TalkBack ([exploring]: a screen reader explores by touch) and for a keyboard (not in touch mode), whose focus would stay
 * behind the overlay. A finger needs neither, and a focused button would keep its outline.
 */
object CastIntroFocus {
    /** Whether a screen reader explores by touch now. A test says it does rather than turn TalkBack on. */
    @VisibleForTesting
    @Volatile
    var exploring: (Context) -> Boolean = { it.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true }

    /** Whether the focus moves, in [touch] mode or not. */
    fun moves(context: Context, touch: Boolean): Boolean = !touch || exploring(context)
}

/** Where the introduction's circle is, in the window, for tests: round the button it points at. */
val CastIntroCircle = SemanticsPropertyKey<Rect>("CastIntroCircle")
private var SemanticsPropertyReceiver.castIntroCircle by CastIntroCircle

/**
 * The phone's shell ([content]) with the introduction to the Cast button over it (W24, W48, W61, L123; CsPhoneIntro): once,
 * as [CastIntroRule] says, on the first screen whose button is placed ([LocalCastButtonSpot]), with nothing over it (Play
 * on's list or a question, the play sheet or [tvStart]'s, [cast]'s prompt or banner, a line [playing] says, the player).
 * It is kept shown in the app's own settings as it appears, so a crash or a turn never brings it back, and shows until
 * Got it, Back or a tap anywhere closes it; it hides while something comes over it. [home]: Home shows, where the button
 * sits beside Settings. It calls nothing of Google's: Play services is known through castAvailable, which reads package
 * facts alone (L146). Never on a TV.
 */
@Composable
fun CastIntroFrame(home: Boolean, playback: PlaybackCoordinator, tvStart: TvStart?, playing: RemotePlaying?, cast: CastPlaying?, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val container = context.container
    val spot = remember { CastButtonSpot() }
    var open by remember { mutableStateOf(false) }
    var focusBack by remember { mutableStateOf(false) }
    val shown by container.castIntroShown.collectAsStateWithLifecycle()
    val available by container.castAvailable.collectAsStateWithLifecycle()
    // Nothing more is watched once it was shown, nor where it never shows.
    val watching = !container.television && (open || (shown == false && available == true))
    val over = if (watching) overTheScreen(container.playOn, playback, tvStart, playing, cast) else emptySet()
    val onLan = if (watching) remember(context) { LanAddress.watch(context) }.collectAsStateWithLifecycle(null).value != null else false
    val setupDone = if (watching) container.setup.collectAsStateWithLifecycle().value?.complete == true else false
    val placed = spot.placed
    val wanted = watching && CastIntroRule.show(container.television, available, onLan, setupDone, placed != null, over, shown)
    LaunchedEffect(wanted) {
        // Once, as it appears: the flag and Play services read again as they are, not as the frame last drew them.
        if (wanted && !open && container.castIntroShown.value == false && container.castAvailable.value == true) {
            open = true
            container.appScope.launch { saveOrLog("keep the Cast introduction shown") { container.settings.updateCast { it.copy(introShown = true) } } }
        }
    }
    val moves = CastIntroFocus.moves(context, LocalInputModeManager.current.inputMode == InputMode.Touch)
    val visible = open && placed != null && over.isEmpty()
    Box(Modifier.fillMaxSize()) {
        // Under it, the screen is TalkBack's no more than the eye's, nor a keyboard's: the introduction is all there is to
        // read and to reach.
        val under = if (visible) Modifier.clearAndSetSemantics {}.focusProperties { onEnter = { cancelFocusChange() } }.focusGroup() else Modifier
        Box(Modifier.fillMaxSize().then(under)) {
            CompositionLocalProvider(LocalCastButtonSpot provides spot, content = content)
        }
        if (visible && placed != null) {
            CastIntroOverlay(placed, home, moves) { pressed ->
                open = false
                if (pressed) placed.press() else focusBack = moves
            }
        }
    }
    LaunchedEffect(focusBack) {
        if (!focusBack) return@LaunchedEffect
        // Once the overlay has gone: the button's place in TalkBack's order is back.
        withFrameNanos {}
        spot.placed?.focus()
        focusBack = false
    }
}

/** What is over the screen now, each of which keeps the introduction away (W48). */
@Composable
private fun overTheScreen(playOn: PlayOn, playback: PlaybackCoordinator, tvStart: TvStart?, playing: RemotePlaying?, cast: CastPlaying?): Set<Over> {
    val container = LocalContext.current.container
    val list by playOn.sheet.collectAsStateWithLifecycle()
    val asking by playOn.asking.collectAsStateWithLifecycle()
    val state by playback.state.collectAsStateWithLifecycle()
    val expanded by playback.expanded.collectAsStateWithLifecycle()
    val player by container.playerInFront.handle.collectAsStateWithLifecycle()
    val tvSheet = tvStart?.now?.collectAsStateWithLifecycle()?.value?.expanded == true
    val prompt = cast?.missing?.collectAsStateWithLifecycle()?.value
    val banner = cast?.slow?.collectAsStateWithLifecycle()?.value
    val said = playing?.said?.collectAsStateWithLifecycle()?.value
    return buildSet {
        if (list != null) add(Over.List)
        if (asking != null) add(Over.Question)
        if ((state != PlayState.Idle && expanded) || tvSheet) add(Over.PlaySheet)
        if (prompt != null) add(Over.CastPrompt)
        if (banner != null) add(Over.Banner)
        if (said != null) add(Over.Said)
        if (player != null) add(Over.Player)
    }
}

/**
 * Look A (W61; CsPhoneIntro): the screen dimmed, a circle cut out round [button] with an amber ring, the words under it
 * and Got it. Got it, Back or a tap anywhere closes it ([onClose]); a tap in the circle also presses the button (true).
 * TalkBack reads it once, as a pane, with where the button is ([CastIntroRule.spoken]); where the focus [moves], it starts
 * on Got it, a key pressed in touch mode giving it there too, and a keyboard's stays there, as a pane's, until it closes.
 */
@Composable
private fun CastIntroOverlay(button: CastButtonSpot.Placed, home: Boolean, moves: Boolean, onClose: (pressed: Boolean) -> Unit) {
    val spoken = CastIntroRule.spoken(home)
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    val radius = with(density) { CIRCLE_RADIUS.toPx() }
    val center = button.bounds.center - origin
    val close by rememberUpdatedState(onClose)
    val circle by rememberUpdatedState(center)
    val gotIt = remember { FocusRequester() }
    val ring = colors.accent
    BackHandler { close(false) }
    LaunchedEffect(moves) {
        if (!moves) return@LaunchedEffect
        withFrameNanos {}
        gotIt.tryFocus()
    }
    Box(
        Modifier.fillMaxSize()
            .focusProperties { onExit = { cancelFocusChange() } }.focusGroup()
            .onGloballyPositioned { origin = it.positionInRoot() }
            .pointerInput(radius) { detectTapGestures { at -> close((at - circle).getDistance() <= radius) } }
            .semantics {
                paneTitle = spoken
                castIntroCircle = Rect(button.bounds.center, radius)
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val hole = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(size.toRect())
                addOval(Rect(center, radius))
            }
            drawPath(hole, SCRIM)
            drawCircle(ring, radius, center, style = Stroke(RING_WIDTH.toPx()))
        }
        Column(
            Modifier.fillMaxSize().padding(top = with(density) { center.y.toDp() } + WORDS_BELOW).verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.semantics(mergeDescendants = true) { contentDescription = spoken }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(CAST_INTRO_TITLE, style = AppType.screenTitle, color = colors.text)
                Text(CAST_INTRO_TEXT, style = AppType.body, color = colors.textSecondary)
            }
            Box(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 24.dp), contentAlignment = Alignment.CenterEnd) {
                // Focusable in touch mode too: TalkBack keeps the phone there, and its focus follows this one.
                PrimaryButton(CAST_INTRO_GOT_IT, onClick = { close(false) }, modifier = Modifier.focusRequester(gotIt).focusProperties { canFocus = true })
            }
        }
    }
}

/** The circle round the button (the design's 30 dp: a 40 dp button, 10 dp round it), its ring, the words' place under its middle. */
private val CIRCLE_RADIUS = 30.dp
private val RING_WIDTH = 2.dp
private val WORDS_BELOW = 70.dp

/** The design's dimming: nearly black, the screen just seen through it. */
private val SCRIM = Color.Black.copy(alpha = 0.92f)
