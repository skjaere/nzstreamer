package io.skjaere.usenet.app

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.StateListAnimator
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.PictureInPictureUiState
import android.app.RemoteAction
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.drawable.Animatable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.Layout
import android.text.TextUtils
import android.util.Rational
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.doOnAttach
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.session.MediaSession
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.TimeBar
import io.skjaere.usenet.app.cast.CastRoute
import io.skjaere.usenet.app.casting.CastFailKind
import io.skjaere.usenet.app.casting.CastLink
import io.skjaere.usenet.app.casting.CastLoadFailure
import io.skjaere.usenet.app.casting.CastMove
import io.skjaere.usenet.app.casting.CastPrecheck
import io.skjaere.usenet.app.casting.MoveOutcome
import io.skjaere.usenet.app.debrid.ServiceFiles
import io.skjaere.usenet.app.link.CastButton
import io.skjaere.usenet.app.link.CastButtonSpot
import io.skjaere.usenet.app.link.CastIntroFocus
import io.skjaere.usenet.app.link.LocalCastButtonSpot
import io.skjaere.usenet.app.link.PlayChoice
import io.skjaere.usenet.app.link.PlayOnAsk
import io.skjaere.usenet.app.link.SoundOnlyQuestion
import io.skjaere.usenet.app.link.SheetPlace
import io.skjaere.usenet.app.play.Area
import io.skjaere.usenet.app.play.AskedPlay
import io.skjaere.usenet.app.play.AutoAdvance
import io.skjaere.usenet.app.play.CastPrecheckQuestion
import io.skjaere.usenet.app.play.CastTo
import io.skjaere.usenet.app.play.ControlsPlayer
import io.skjaere.usenet.app.play.Cutout
import io.skjaere.usenet.app.play.DipDetector
import io.skjaere.usenet.app.play.DisplayModeSwitcher
import io.skjaere.usenet.app.play.DisplayPort
import io.skjaere.usenet.app.play.DisplayPorts
import io.skjaere.usenet.app.play.EdgeTaps
import io.skjaere.usenet.app.play.EndOfPlay
import io.skjaere.usenet.app.play.EpisodePlay
import io.skjaere.usenet.app.play.FileDetailsCollector
import io.skjaere.usenet.app.play.FileDetailsPanel
import io.skjaere.usenet.app.play.FileOrigin
import io.skjaere.usenet.app.play.HeldCommands
import io.skjaere.usenet.app.play.IMPORT_GONE
import io.skjaere.usenet.app.play.KeepAwake
import io.skjaere.usenet.app.play.MissingParts
import io.skjaere.usenet.app.play.MissingPartsPrompt
import io.skjaere.usenet.app.play.MomentLabelPlace
import io.skjaere.usenet.app.play.MovingSpinner
import io.skjaere.usenet.app.play.NextEpisodeCard
import io.skjaere.usenet.app.play.NowPlaying
import io.skjaere.usenet.app.play.PictureFrame
import io.skjaere.usenet.app.play.PictureInPictureOff
import io.skjaere.usenet.app.play.PictureInPicturePolicy
import io.skjaere.usenet.app.play.PipActions
import io.skjaere.usenet.app.play.PipScene
import io.skjaere.usenet.app.play.PipShape
import io.skjaere.usenet.app.play.PlaybackFailedPanel
import io.skjaere.usenet.app.play.PlaybackFailure
import io.skjaere.usenet.app.play.PlayOnPanel
import io.skjaere.usenet.app.play.PlayerCast
import io.skjaere.usenet.app.play.PlayerLaunch
import io.skjaere.usenet.app.play.PlayerHandle
import io.skjaere.usenet.app.play.PlayerTitles
import io.skjaere.usenet.app.play.PlayOrigin
import io.skjaere.usenet.app.play.PlayWhenMade
import io.skjaere.usenet.app.play.Prefetch
import io.skjaere.usenet.app.play.RemoteKeys
import io.skjaere.usenet.app.play.RemotePlay
import io.skjaere.usenet.app.play.SavedRelease
import io.skjaere.usenet.app.play.ScrubRate
import io.skjaere.usenet.app.play.SeekBurst
import io.skjaere.usenet.app.play.SeekHold
import io.skjaere.usenet.app.play.SessionMetadata
import io.skjaere.usenet.app.play.SessionPlayer
import io.skjaere.usenet.app.play.SideMark
import io.skjaere.usenet.app.play.SpeedCue
import io.skjaere.usenet.app.play.Sides
import io.skjaere.usenet.app.play.SkipButton
import io.skjaere.usenet.app.play.SkipSegmentButton
import io.skjaere.usenet.app.play.SlowStream
import io.skjaere.usenet.app.play.SlowStreamBanner
import io.skjaere.usenet.app.play.SourceNames
import io.skjaere.usenet.app.play.StallLog
import io.skjaere.usenet.app.play.StoppedPlay
import io.skjaere.usenet.app.play.TrackChoices
import io.skjaere.usenet.app.play.TrackSheet
import io.skjaere.usenet.app.play.TrackSheetPanel
import io.skjaere.usenet.app.play.VideoTiming
import io.skjaere.usenet.app.play.VideoZoom
import io.skjaere.usenet.app.play.WatchSaver
import io.skjaere.usenet.app.play.WindowOff
import io.skjaere.usenet.app.play.fileChapters
import io.skjaere.usenet.app.play.nextAiredEpisode
import io.skjaere.usenet.app.play.previousAiredEpisode
import io.skjaere.usenet.app.ui.AppColors
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.app.ui.FocusStyle
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.ui.tryFocus
import io.skjaere.usenet.core.ProviderConfig
import io.skjaere.usenet.core.UsenetError
import io.skjaere.usenet.core.UsenetUris
import io.skjaere.usenet.data.AspectMode
import io.skjaere.usenet.data.LibraryRepository
import io.skjaere.usenet.data.PlaybackPrefs
import io.skjaere.usenet.data.WatchKind
import io.skjaere.usenet.link.Playback
import io.skjaere.usenet.metadata.FileChapter
import io.skjaere.usenet.metadata.Segment
import io.skjaere.usenet.metadata.SegmentKind
import io.skjaere.usenet.metadata.Segments
import io.skjaere.usenet.player.AudioChoice
import io.skjaere.usenet.player.BecomingNoisy
import io.skjaere.usenet.player.ExoTrackProbe
import io.skjaere.usenet.player.GapReports
import io.skjaere.usenet.player.LinkUnavailable
import io.skjaere.usenet.player.NoisyPolicy
import io.skjaere.usenet.player.PlayGaps
import io.skjaere.usenet.player.PlayUris
import io.skjaere.usenet.player.PlayerFactory
import io.skjaere.usenet.player.SubtitleChoice
import io.skjaere.usenet.player.SubtitlePlan
import io.skjaere.usenet.player.TrackChoice
import io.skjaere.usenet.player.TrackChoiceApplier
import io.skjaere.usenet.player.TrackKey
import io.skjaere.usenet.player.TrackOption
import io.skjaere.usenet.player.TrackOptions
import io.skjaere.usenet.player.TrackSelection
import io.skjaere.usenet.player.UsenetDataSourceException
import io.skjaere.usenet.player.VideoTracks
import io.skjaere.usenet.player.formatRate
import io.skjaere.usenet.player.tv.DeviceMemory
import io.skjaere.usenet.player.tv.FrameRatePlan
import io.skjaere.usenet.player.tv.FrameRatePlanner
import io.skjaere.usenet.player.tv.PlaybackBudget
import io.skjaere.usenet.player.tv.PlaybackBudgets
import io.skjaere.usenet.player.tv.ScreenMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Full-screen playback of one file. With a [PlayerLaunch] it resumes from the saved position and
 * saves progress every 15 s and on leaving; for a TMDB episode it offers the next one near the end
 * ([AutoAdvance]), and the controls' Previous and Next play the episodes either side
 * ([ControlsPlayer.playPrevious], [ControlsPlayer.playNext]). A bare URI (opened NZB file) just
 * plays. With nothing to move on to at the end, the player leaves, back to where the play started
 * ([EndOfPlay]). Any file's intro and recap chapters get a skip button ([SkipButton]); for a TMDB
 * episode, so may TheIntroDB's times when the user has turned that on.
 *
 * Audio and subtitles are planned for each player as it reports its tracks ([TrackChoiceApplier]):
 * what the sheet picked during this play, else what the show remembers, else the preferences and
 * the subtitle rule. The sheet ([TrackSheetPanel]) opens from the top bar shown with the controls;
 * what it picks outlives the player (onStop/onStart, recreation) and, during an episode of a TMDB
 * show, is saved for the show's later episodes. Beside its button ⓘ opens File details
 * ([FileDetailsPanel]): the release and the file, the video, what this device does with it and
 * where it came from, gathered as the play goes ([FileDetailsCollector]). On a phone or a tablet the Cast button is
 * last, right of ⓘ ([PlayerCast]): it opens Play on's list ([PlayOnPanel]), where your TVs are greyed, as they play from a
 * title's page (L116). The three are side panels, one at a time. A Cast device chosen there takes the film there (Play &
 * connect, [CastMove]): paused where it is, "Moving to Kitchen display…" while it connects, then loaded there from that
 * place, and the player closes for the remote once the device plays it; a type no Cast device plays, a connect that
 * fails or a device that doesn't play it leaves the film playing here, saying why. A speaker asks first.
 *
 * The top bar's aspect button and a pinch size the picture ([VideoZoom]): only the video surface
 * is scaled, so the subtitles and the overlays stay where Fit puts them. Where the front camera
 * sits in a display cutout, Fill beside camera fills the screen but for a black strip beside it,
 * following it as the screen turns or the window changes ([applyInsets]). The choice is kept in the
 * playback preferences for the next play, and outlives the player like the sheet's picks.
 *
 * Rewind and fast-forward go 10 s, and taps in quick succession make one seek, after the last
 * ([ControlsPlayer]): every seek opens the file's stream again. The controls show where it goes at
 * once. Anything else done meanwhile has it seek first; as the player is left or stopped, where it
 * was going is what is saved and played on from, and the player that goes doesn't seek.
 *
 * On a phone a double-tap in the picture's left or right third goes 10 s back or on, and single taps
 * there after it 10 s more each, into the same burst ([EdgeTaps]); the side mark says how far
 * ([SideMark]), and the controls stay as they were. A single tap at the sides shows or hides them once
 * the double-tap timeout has passed, one in the middle at once.
 *
 * A part of the release no provider has is skipped where the file's index allows: the play jumps past it, and the
 * moment label says "A missing part was skipped" as playback gets there. Once three such parts lie within ten minutes of
 * the film, a prompt asks whether to try another release, from where the play is, keeping this one until another plays
 * ([MissingParts], [MissingPartsPrompt]).
 *
 * When the stream comes slower than the film plays, a banner says so ("Streaming is slower than this video needs", what
 * it gets of what the film needs) with a hint about Maximum bitrate ([DipDetector], [SlowStreamBanner]); for a play
 * started from a list of releases with one well under this one in it, it offers Try a smaller release, from where the
 * play is ([PlaybackCoordinator.trySmaller]). While playback waits for bytes, Media3's spinner shows. Neither takes the
 * focus; a remote reaches the banner's button from the controls.
 *
 * When playback fails for good, a panel says why and offers another release of the same request
 * ([PlaybackCoordinator.tryAnother]), started where this one stopped, and File details, with what
 * was read of the file before it stopped. A play started while the
 * player is open (it is singleTask) replaces the one playing (#154).
 *
 * Stopped (the screen off, in the picture-in-picture window or full screen; Home or another app; a TV asleep), the
 * activity lets its player go; started again, the player it makes waits paused where the film was, its picture shown,
 * rather than playing as the screen comes back (W106, [PlayWhenMade]). A play's first player plays, and so does one made
 * again for a change of dark mode, the font or the language if the one before played, or one OK asked to play during a
 * TV's frame-rate switch.
 *
 * The player is immersive: the status and navigation bars hide while it is in front
 * ([hideSystemBars]) and the picture fills the screen, under them and the display cutout. The
 * controls and the overlays keep clear of both, the bars as if they showed, and on a TV inside its
 * safe area ([applyInsets]). Fit's bars are black. The screen stays on only while playback runs or
 * is about to ([KeepAwake]).
 *
 * A remote's keys go to [RemoteKeys] first: with the controls hidden OK plays or pauses, and Left
 * and Right seek, as a TV's player does; Back hides the controls before it leaves. The captions and
 * audio track keys switch tracks as the sheet does; Info opens and closes File details. The
 * sample buffer is sized for the device's memory ([PlaybackBudget]).
 *
 * On a TV with "Match the TV to the video's frame rate" on, the window asks the display for a mode that shows the
 * video's rate before the player is made ([matchFrameRate]); where the TV must switch for it, the play waits on black
 * behind "Matching the TV to 23.976 Hz…" until it has. Off, the default, the play is as it was before the setting.
 *
 * Headphones unplugged pause the play, as Android says its audio is "becoming noisy": [BecomingNoisy], made for each
 * player. On a TV only headphones do, never its HDMI going away as the box re-syncs it (a switch of the TV's rate,
 * Dolby Vision, the TV waking, a receiver switched on). A TV set's own headphones pause it; from a box with HDMI
 * connected Android says nothing as headphones go, and the play goes on through the TV, as it did before.
 *
 * On a phone or a tablet a play the user leaves playing (Home, Recents, another app) goes into the picture-in-picture
 * window ([PictureInPicturePolicy]): from Android 12 Android opens it itself, the player keeping it told whether it may;
 * before, the player asks as the user leaves. Not paused, ended or stopped, nor on a TV. The window takes the picture's
 * shape ([PipShape]) and shows the picture alone ([PipScene]), with its own buttons, −10 s, Play or Pause and +10 s
 * ([PipActions]); "Playback stopped" there is one line on black. Closed, the player saves where the play was, lets it go
 * and closes, its task with it; expanded, it is as it was left. Alone in its task after the window, it brings the app's
 * screens forward as it leaves. Another play started from the app's screens under the window pauses the window's;
 * Android then expands the window with the new play. The top bar's button puts the play in the window too, playing or
 * paused; with picture-in-picture off for the app in Android's settings, it says so, with the way there.
 *
 * Each player has a media session, on phones and TVs alike ([SessionPlayer]): a Bluetooth headset's or earbuds' keys, the
 * Assistant and the picture-in-picture window play, pause and seek the play through it, as the controls do, and its Next
 * and Previous are the controls' own. It says the play as the top bar does, or only the app's name for a play TMDB doesn't
 * name ([SessionMetadata]). A media key nothing in the player takes goes no further.
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private val log = LoggerFactory.getLogger(PlayerActivity::class.java)
    @VisibleForTesting
    internal var player: ExoPlayer? = null
        private set
    /** The budget the player was made with ([budgetRead]). */
    @VisibleForTesting
    internal var budget: PlaybackBudget? = null
        private set
    /** What a play may hold in memory on this device, read off the main thread as the activity is made, for every player it makes. */
    private lateinit var budgetRead: Deferred<PlaybackBudget>
    /** A TV or anything else, as the activity was made: on a TV the controls and overlays keep inside its safe area ([applyInsets]). */
    private lateinit var formFactor: FormFactor
    /** [player] as the controls see it, which seeks once for a burst of rewind and fast-forward taps. */
    private var controlsPlayer: ControlsPlayer? = null
    /**
     * The media session over [controlsPlayer], made with each player and let go with it ([SessionPlayer]): what a Bluetooth
     * headset, the Assistant and the picture-in-picture window play, pause and seek it through.
     */
    @VisibleForTesting
    internal var session: MediaSession? = null
        private set
    /** [controlsPlayer] as [session] shows it. */
    private var sessionPlayer: SessionPlayer? = null
    private lateinit var playerView: PlayerView
    private var resumePosition = C.TIME_UNSET
    /** Whether the next player made plays as it is made, or waits paused at its place ([PlayWhenMade], W106). */
    private var playsWhenMade = PlayWhenMade.FIRST
    private var launch: PlayerLaunch? = null

    /**
     * Who holds, for this player, what a debrid play added to the service account (L234): one id, kept as Android makes the
     * player again, so its lease runs from its launch until it closes ([letGo]) and never ends at onStop.
     */
    private var lease = "player:${UUID.randomUUID()}"

    private var setupJob: Job? = null
    private var progressJob: Job? = null
    private var nextJob: Job? = null
    private var previousJob: Job? = null
    private var segmentsJob: Job? = null
    /** The file's intro, recap and credits, once known for the setup now running. */
    private var segments: List<Segment>? = null
    private lateinit var skipView: ComposeView
    /** What the skip button shows; null while it is hidden. */
    private var skipShown by mutableStateOf<SkipButton.Shown?>(null)
    private var skipButton: SkipButton? = null
    /** The play's missing parts, what it says of them and when ([MissingParts]): a new count for each launch, kept as the player is made again. */
    private var missingParts = newMissingParts()
    private lateinit var promptView: ComposeView
    /** The missing-parts prompt as it shows, and whether Keep watching takes a remote's focus; null while hidden. */
    private var promptShown by mutableStateOf<PromptShown?>(null)
    /** The prompt the count asks with now, shown or waiting for the slot ([showPrompt]); null while it asks nothing. */
    private var asking: MissingParts.Prompt? = null
    /** Missing stretches the play's streams found, waiting for the next tick ([watchMissingParts]): they come from download threads. */
    private val foundGaps = ConcurrentLinkedQueue<LongRange>()
    /** The jumps the player's extractors made past them, waiting likewise: they come from the loader's thread. */
    private val gapJumps = ConcurrentLinkedQueue<PlayGaps.Jump>()
    /** What this player's extractors tell of its file's missing stretches: the file's index, to place one in the film. */
    private var playGaps: PlayGaps? = null
    /** This player's listening for the stretches its streams find. */
    private var gapsListening: AutoCloseable? = null
    private var gapsJob: Job? = null
    /** The providers this player's streams start on: what a part found missing is remembered against. */
    private var providers: List<ProviderConfig> = emptyList()
    /** Every jump past a missing part this activity's players made, as their extractors told it, for a test. */
    @VisibleForTesting
    internal val jumps = mutableListOf<PlayGaps.Jump>()
    /** The missing-parts prompt's FocusManager: a test on a TV moves the focus inside it with it, as tv36 doesn't for its arrow keys. */
    @VisibleForTesting
    internal var promptFocus: FocusManager? = null
        private set
    private lateinit var dipView: ComposeView
    /** The slow-stream banner as it shows; null while hidden. */
    private var dipShown by mutableStateOf<DipShown?>(null)
    /** This player's slow-stream detector, which its listener and the loop feed ([watchDips]). */
    private var dips: DipDetector? = null
    private var dipJob: Job? = null
    /** This player's "becoming noisy" watcher, from the player's making to its going ([becomingNoisy]); a test calls it as Android's broadcast would. */
    @VisibleForTesting
    internal var noisy: BecomingNoisy? = null
        private set
    private lateinit var cardView: ComposeView
    /** What the next-episode card shows; null while it is hidden. */
    private var nextCard by mutableStateOf<NextCard?>(null)
    /** The card's decisions for the setup now running; each setup makes its own. */
    private var autoAdvance: AutoAdvance? = null
    /** The next episode's release search, kept across setups so coming back doesn't search again. */
    private var prefetch: Pair<EpisodePlay, StateFlow<Prefetch>>? = null
    /** Stop on the card holds for the rest of this episode, also after the player is set up again. */
    private var cardStopped = false
    /** The next episode's lookup for the setup now running, which the end of the play waits on ([EndOfPlay]); none but for a show's episode. */
    private var nextLookup = EndOfPlay.Next.None
    /** Set as another episode is handed over (the next, or by Previous the one before): none is ever started twice. */
    private var switched = false
    /** Whether the playback controls are up, as PlayerView last reported; the card is compact meanwhile. */
    private var controlsShown by mutableStateOf(false)
    /** Back hides the card while it shows, and otherwise leaves the player as before. */
    private val backStopsCard = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = stopCard()
    }
    /** Back hides the controls while they are fully up for a remote, before anything else it does ([updateBackHidesControls]). */
    private val backHidesControls = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = playerView.hideController()
    }
    /** Back hides the skip button while it shows. */
    private val backHidesSkip = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            skipButton?.dismiss()
            showSkip(null)
        }
    }
    /** Back keeps watching while the missing-parts prompt asks (W6); with the count alone it leaves as usual. */
    private val backKeepsWatching = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = keepWatching()
    }
    /**
     * Shows and hides with the playback controls: the back arrow, the title, the picture-in-picture button, the aspect
     * button, the audio and subtitles button and File details' (ⓘ).
     */
    private lateinit var topBar: LinearLayout
    /** The top bar's picture-in-picture button: there only where the player offers the window ([pictureInPicture]). */
    private lateinit var pipButton: ImageButton
    private lateinit var aspectButton: ImageButton
    private lateinit var audioButton: ImageButton
    private lateinit var detailsButton: ImageButton
    /** The top bar's Cast button, right of ⓘ ([castButton]): on phones and tablets alone ([PlayerCast.shows]), none on a TV. */
    private var castView: ComposeView? = null
    /** Where the Cast button is: as Play on's list closes, the focus goes back into it through here ([castFocus]). */
    private val castSpot = CastButtonSpot()
    /** Play & connect under way ([moveTo]): from the tap until the device plays the film, or it didn't go. */
    private var moveJob: Job? = null
    /** Whether the move under way connects its device itself, rather than take a session that was there ([callOffMove]). */
    private var moveConnects = false
    /** What comes of the move once the coordinator hands the film to the device; null before that ([callOffMove]). */
    private var moveOutcome: Deferred<MoveOutcome>? = null
    /** The speaker question Play & connect asks over the film (L124 step 2); null while none is asked. */
    private var castAsking by mutableStateOf<PlayOnAsk.SoundOnly?>(null)
    /** The pre-check Play & connect asks over the film (L124 step 2, item 44); null while none is asked. */
    private var castQuestion by mutableStateOf<CastQuestion?>(null)
    /** What the Cast device makes of the film, read as its row is tapped, before anything connects ([castFit]). */
    private var judging: Job? = null
    /** The speaker the user told Cast anyway in the question over the film: kept for the session the move opens ([moveTo]). */
    private var castAnywayFor: String? = null
    /** Where the speaker question and the pre-check are asked from, each a window of its own: made the first time one is. */
    private var askView: ComposeView? = null
    /** The spinner before "Moving to Kitchen display…" (CsPhonePlayer 3), made once. */
    private val movingMark by lazy { AppColors().let { c -> MovingSpinner(resources.displayMetrics.density, c.accent.toArgb(), c.outline.toArgb()) } }
    /** The top bar's title, with the show's name under it. */
    private lateinit var titles: View
    /** Whether the controls were fully up as PlayerView last reported. */
    private var controlsFull = false
    /** The controls fading after their timeout: their buttons going or gone, the time bar not yet. */
    private var controlsFading = false
    private lateinit var root: FrameLayout
    /**
     * The side panels, the audio and subtitles sheet, File details and Play on's list, in one view over everything; made the
     * first time one opens, gone while none shows.
     */
    private var panelView: ComposeView? = null
    /** The side panel showing; null while none does. */
    private var panel by mutableStateOf<Panel?>(null)
    /**
     * Where the focus goes back to as the panel closes: what had it as it opened (a button of the top bar or the controls,
     * the next-episode card's Play now as [cardView]), the "Playback stopped" panel's File details ([failureView]), or
     * nothing, for the picture.
     */
    private var opener: View? = null
    /** Whether the controls come back up as the panel closes: they were, or the focus was on one of them, as it opened. */
    private var openerControls = false
    /** What the sheet lists, kept up to date with the player's tracks. */
    private var trackSheet by mutableStateOf<TrackSheet?>(null)
    /** File details for the play running, kept as the players come and go. */
    private lateinit var fileDetails: FileDetailsCollector
    /** The settings' indexers and service accounts as a setup last read them, for File details' Source rows; null until the first read. */
    private var indexers: SourceNames? = null
    /** The panels' FocusManager: a test on a TV moves the focus with it, which tv36 doesn't do for its arrow keys. */
    @VisibleForTesting
    internal var panelFocus: FocusManager? = null
        private set
    /**
     * The picture's and the overlays' focus settings, put back as the panel closes: while it shows
     * they take no focus, and accessibility services (TalkBack) don't see them.
     */
    private var heldFocus: List<HeldFocus> = emptyList()
    /** Why playback stopped, while the panel says so; null otherwise. */
    private var failure by mutableStateOf<Failure?>(null)
    private lateinit var failureView: ComposeView
    /** The panel's File details button, which the focus goes back to as File details opened from it close. */
    private val failureDetails = FocusRequester()
    /** The next-episode card's Play now, which the focus goes back to as File details opened over it close. */
    private val cardPlayNow = FocusRequester()
    /** The missing-parts prompt's Keep watching, which takes a remote's focus as the prompt shows with the controls hidden. */
    private val promptKeepWatching = FocusRequester()
    /**
     * Set once this play's release has failed through its own fault, or the user has moved on to
     * another release: progress saved after that doesn't keep it as the release that played. A
     * failure of the provider or the device leaves it unset, so the release, played on after the
     * player comes back (onStop, onStart), is kept as it is saved.
     */
    private var releaseFailed = false
    /** Back with the panel up leaves the player, as Close does. */
    private val backLeavesFailure = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = finish()
    }
    /**
     * Back while the TV switches its frame rate leaves at once: left to the system, Back comes back to finish only after
     * a call to it, and a wait ending meanwhile would make the player.
     */
    private val backLeavesMatching = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = finish()
    }
    private lateinit var titleText: TextView
    private lateinit var underText: TextView

    /** What the sheet picked during this play: kept for the next player and across recreation (#107). */
    private var picked = TrackChoice()
    /** The show whose episodes share their audio and subtitles; null for films, releases and opened files. */
    private val show: Int? get() = TrackChoices.showOf(launch)
    /** The show's remembered choice, read once by the first setup and kept in step with the sheet after. */
    private var showChoice: TrackChoice? = null
    private var showChoiceRead = false
    /** Saves of the show's choice, one after the other. */
    private var showSave: Job? = null
    /** The preferred audio languages and the subtitle rule's switch, for the setup now running. */
    private var languages: List<String> = emptyList()
    private var subtitleRule = true
    private val trackPlans = TrackChoiceApplier<Player> { p, plan ->
        p.trackSelectionParameters = TrackSelection.apply(p.trackSelectionParameters, p.currentTracks, plan, languages)
        chose(plan.subtitles)
    }

    /**
     * How the picture is sized: the playback preferences' choice, read once by the first setup,
     * then the aspect button's or a pinch's. Kept for the next player, across recreation and for a
     * play started here.
     */
    private var zoom = VideoZoom()
    private var zoomKnown = false
    /** Saves of the choice, one after the other. */
    private var zoomSave: Job? = null
    /** media3's frame for the picture, laid out by Fit; the video surface in it is what is scaled. */
    private lateinit var contentFrame: View
    /** The display cutout as the window last had it ([applyInsets]): Fill beside camera keeps the picture beside its camera. */
    private var cutout = Cutout()
    /** Whether a camera was beside the picture as the button was last shown: then it offers Fill beside camera ([applyZoom]). */
    private var besideCamera = false
    /** media3's controls, inside PlayerView: their padding keeps them clear of the bars and the cutout ([applyInsets]). */
    private lateinit var controls: ViewGroup
    /** Their time bar, whose step goes by touch mode ([stepTimeBar]). */
    private lateinit var timeBar: TimeBar
    /** Says the picture's new size, or how far a burst of rewind and fast-forward taps goes, for a moment under the top bar. */
    private lateinit var momentLabel: TextView
    private var momentLabelJob: Job? = null

    /** The system bars' and the cutout's inset on the left, which beside an overlay is no room for the moment label. */
    private var momentLabelSafeLeft = 0
    /** Theirs at the top, as if the bars showed: the side mark's pill keeps below it ([sideMarkRoom]). */
    private var safeTop = 0
    /** The label's speed cues for a held key ([speedCue]), made once each: by how many chevrons are lit, and which way they point. */
    private val speedCues = mutableMapOf<Pair<Int, Boolean>, SpeedCue>()
    private lateinit var pinch: ScaleGestureDetector
    /** The key held now to seek (Left, Right, ⏪ or ⏩), from its down to its up: its repeats seek on whatever takes the focus meanwhile ([RemoteKeys.Scene.seekingKey]). */
    private var seekingKey: Int? = null
    /**
     * How long that key has been held, from its press as it reached the player ([heldMs]), over HDMI-CEC from the first of
     * the presses a TV sends it as, and how far it has sought ([SeekHold.stepMs]).
     */
    private val seekHold = SeekHold()
    /** What the label says of that key's seek, said again for TalkBack as it is let go, with where to ([endHold]). */
    private var heldSaid: SeekBurst.Said? = null
    /** Those words at an up over HDMI-CEC, waiting until the hold is sure to be over ([SeekHold.up]). */
    private var heldWords: Job? = null
    /** The subtitles chosen last during this play, which the captions key brings back ([chose]). */
    private var lastSubtitles: TrackKey? = null
    /** What the captions key turns them off to: the last choice without full subtitles, Forced only (the rule's "none") until one is made. */
    private var subtitlesOff: SubtitlePlan = SubtitlePlan.ForcedOnly
    /** The scale while a pinch goes on, over Fit's; null otherwise. */
    private var pinchScale: Float? = null
    /** Whether the touch under way is a pinch's: the views under it had it taken from them. */
    private var pinching = false
    /** Whether the touch under way came down on the picture itself, as PlayerView's touch listener says: no control, button, overlay or panel took it. */
    private var reachedPicture = false
    /** Where the picture is in the window, read for each touch event. */
    private val pictureAt = IntArray(2)
    /** Double-tap to seek at the picture's sides, on a phone ([EdgeTaps]). */
    private val edgeTaps by lazy {
        ViewConfiguration.get(this).let { vc ->
            EdgeTaps(SystemClock::uptimeMillis, vc.scaledTouchSlop.toFloat(), vc.scaledDoubleTapSlop.toFloat(), ViewConfiguration.getDoubleTapTimeout().toLong())
        }
    }
    /** A single tap at a side acts as its wait for a second ends ([EdgeTaps.toggleDueInMs]). */
    private val edgeTimeUp = Runnable { edge(edgeTaps.timeUp(), null) }
    /** The mark on the side a double-tap seeks from ([SideMark]). */
    private lateinit var sideMark: SideMark
    /** What the side mark says, kept to say it again as the controls come or go while it shows ([renderMark]); null while it doesn't. */
    private var marked: Marked? = null
    private var markJob: Job? = null
    /** Whether the label under the top bar says a burst's words, which the side mark takes over as it shows (M7). */
    private var momentSaysBurst = false
    /**
     * Whether the player has shown its first frame. Until then media3's black shutter covers
     * Fit's frame, and only that, while the surface may still hold the last frame of the play
     * before (one started here): the surface stays inside the shutter, unscaled.
     */
    private var pictureShown = false
    /** This window and the default display, as a frame-rate switch asks of them ([matchFrameRate]). */
    private lateinit var display: DisplayPort
    /** The mode the window asks the display for, while it asks for one: by its size and rate, for the play that follows. */
    private var heldMode: ScreenMode? = null
    /** While the TV switches its frame rate before the player is made: completed by OK, which plays at once. */
    private var matching: CompletableDeferred<Unit>? = null
    /** Whether this player asked to close without its transition (after a hand-over), for a test: the platform keeps it to itself. */
    @VisibleForTesting
    internal var closesWithoutTransition = false
        private set
    /** Whether the player offers picture-in-picture: the device has it, and isn't a TV (L1). Nothing is asked of the window without it. */
    private var pictureInPicture = false
    /** What the player last told Android of its picture-in-picture window ([updatePictureInPicture]); null before it told anything. */
    private var windowTold: WindowParams? = null
    /** The window's own buttons as the player last told Android of them ([PipActions]), which a test presses as Android's menu does. */
    @VisibleForTesting
    internal var windowButtons: List<RemoteAction> = emptyList()
        private set
    /** The window's buttons, pressed: Android sends their PendingIntents, as this app, to this receiver, which no other app reaches. */
    private val windowPresses = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val kind = intent.getStringExtra(EXTRA_WINDOW_BUTTON)?.let { name -> PipActions.Kind.entries.firstOrNull { it.name == name } } ?: return
            pressInWindow(kind)
        }
    }
    /** Set as the player closes ([finish]): from then on the window doesn't open as the user leaves. */
    private var leaving = false
    /**
     * Whether the player is in the picture-in-picture window, or going in ([showPinned]): read from Android's own as the
     * activity is made (made again in the window, nothing says so) and started, and as Android says it changed.
     */
    private var pinned = false
    /**
     * Whether this player has been in the window: alone in its task after it, it brings the app's screens forward as it
     * leaves ([finish], L7). Kept as the player is made again (a change of dark mode, the font or the display size, or
     * Android bringing it back after letting the app's process go), which leaves it alone in its task all the same.
     */
    private var wasPinned = false
    /**
     * Whether a play the app's screens started while this one was in the window is on its way ([pauseForANewPlay]); it
     * comes by onNewIntent, unless the user goes back to this one first ([dropTheNewPlay]).
     */
    private var newPlayComing = false
    /** In the window, "Playback stopped" on black in place of its panel (L12). */
    private lateinit var stoppedLine: TextView
    /** Whether the player says picture-in-picture is off for the app, as Android refused the window ([showWindowOff]). */
    private var windowOff by mutableStateOf(false)
    private lateinit var windowOffView: ComposeView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Under the bars and the cutout. The bars show only for a moment, on a swipe, over the
        // picture: light icons, whatever the system's theme, the navigation buttons on the system's
        // scrim (NAVIGATION_SCRIM up to Android 9, which has none).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, NAVIGATION_SCRIM) { true },
        )
        launch = launchOf(intent)
        formFactor = FormFactor.of(this, intent)
        display = DisplayPorts.of(this)
        pictureInPicture = PictureInPicturePolicy.available(hasPictureInPicture(this), isTelevision(container))
        if (pictureInPicture) ContextCompat.registerReceiver(this, windowPresses, IntentFilter(WINDOW_BUTTON), ContextCompat.RECEIVER_NOT_EXPORTED)
        keepHandedOverMode()
        fileDetails = FileDetailsCollector(this, lifecycleScope).apply { start(detailsOrigin(indexers = null)) }
        // Under way while the first setup reads the settings it needs, so it rarely waits on this.
        budgetRead = lifecycleScope.async(Dispatchers.Default) {
            val memory = readMemory(applicationContext)
            PlaybackBudgets.of(memory, uhdDisplay = container.device.get().displayHeight >= UHD_HEIGHT).also { log.debug("Playback budget {} for {}", it, memory) }
        }
        // Behind Fit's bars, as on a TV they must be (TV-LO), rather than the theme's grey (#199).
        window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.BLACK))
        savedInstanceState?.getLong(KEY_POSITION)?.let { resumePosition = it }
        // As the player before did, made again for a change; waiting, made again after the process went (W106).
        savedInstanceState?.let { playsWhenMade = it.getBoolean(KEY_PLAYING, PlayWhenMade.FIRST) }
        savedInstanceState?.getString(KEY_TRACKS)?.let { picked = TrackChoices.decode(it) }
        wasPinned = savedInstanceState?.getBoolean(KEY_WAS_PINNED) == true
        // The lease from its launch, as Android made it or made it again (L234).
        savedInstanceState?.getString(KEY_LEASE)?.let { lease = it }
        holdLaunch()
        // Kept as the preferences keep it.
        savedInstanceState?.getString(KEY_ASPECT)?.let { aspect ->
            zoom = VideoZoom.of(PlaybackPrefs(aspectMode = aspect, zoomScale = savedInstanceState.getFloat(KEY_ZOOM, 1f)))
            zoomKnown = true
        }
        // Its children unclipped: the frame Fit lays the picture out in would otherwise crop the
        // scaled surface to the picture's shape, leaving the bars. The screen stays on as KeepAwake says.
        playerView = PlayerView(this).apply {
            clipChildren = false
            setShutterBackgroundColor(android.graphics.Color.BLACK)
            // A stall shows as one (W5): without it the picture just froze.
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        }
        contentFrame = playerView.findViewById(androidx.media3.ui.R.id.exo_content_frame)
        controls = playerView.findViewById(androidx.media3.ui.R.id.exo_controller)
        // media3's settings menu has nothing left to offer (ControlsPlayer takes away track selection and playback
        // speed), and media3 only greys its gear out then: gone, so the remote never stops on it.
        playerView.findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.visibility = View.GONE
        clipFadingControls()
        timeBar = (playerView.findViewById<View>(androidx.media3.ui.R.id.exo_progress) as TimeBar).apply {
            // A scrub of the time bar has a burst of rewind and fast-forward taps seek first, as it starts.
            addListener(
                object : TimeBar.OnScrubListener {
                    override fun onScrubStart(timeBar: TimeBar, position: Long) {
                        controlsPlayer?.flush()
                    }

                    override fun onScrubMove(timeBar: TimeBar, position: Long) = Unit

                    override fun onScrubStop(timeBar: TimeBar, position: Long, canceled: Boolean) = Unit
                },
            )
        }
        // Fit lays the picture out again for a video of another size or shape, or as the screen
        // turns: the scale follows, and where the picture-in-picture window opens from.
        val relayout = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            applyZoom()
            updatePictureInPicture()
        }
        playerView.addOnLayoutChangeListener(relayout)
        contentFrame.addOnLayoutChangeListener(relayout)
        pinch = ScaleGestureDetector(this, PinchListener()).apply { isQuickScaleEnabled = false }
        // Leaving as Stop on the card does first, so its countdown can't start the next episode on the way out.
        val back = roundButton(R.drawable.ic_back, "Back", ::leave)
        pipButton = roundButton(R.drawable.ic_picture_in_picture, "Picture in picture", ::pressPictureInPicture).apply { isVisible = pictureInPicture }
        aspectButton = roundButton(R.drawable.ic_aspect_fit, zoom.description(besideCamera), ::pressAspect)
        audioButton = roundButton(R.drawable.ic_audio_subtitles, "Audio and subtitles") { openPanel(Panel.Tracks, audioButton, controls = true) }
        detailsButton = roundButton(R.drawable.ic_file_details, "File details") { openPanel(Panel.Details, detailsButton, controls = true) }
        castView = if (PlayerCast.shows(container.television)) castButton() else null
        topBar = topBar(back, pipButton, aspectButton, audioButton, detailsButton, castView)
        showZoom()
        momentLabel = momentLabel()
        sideMark = SideMark(this)
        markTouchesOnPicture()
        // The top bar shows and hides with the playback controls. media3 hides them in two steps
        // after their timeout, the buttons first and the time bar 2 s later, and reports each
        // step: fully up and then not, they are fading until fully up again or gone.
        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                // In the window the controls aren't used, and nothing of the app's puts them up there; media3 does, as a
                // show still animating when the player went in ends, or with the time bar its fade leaves. Down again at
                // once, before a frame draws them: nothing else would take them down.
                if (pinned && visibility == View.VISIBLE) {
                    hideControlsAtOnce()
                    return@ControllerVisibilityListener
                }
                val full = playerView.isControllerFullyVisible
                controlsFading = visibility == View.VISIBLE && !full && (controlsFading || controlsFull)
                controlsFull = full
                showTopBar(visibility)
                controlsShown = visibility == View.VISIBLE
                // The words that picture-in-picture is off go with them, as the top bar's buttons fade.
                if (controlsFading || !controlsShown) hideWindowOff()
                updateBackHidesControls()
                // Its time while they are hidden, beside their centre buttons while they show.
                if (sideMark.isVisible) renderMark()
            },
        )
        playerView.viewTreeObserver.addOnTouchModeChangeListener {
            updateBackHidesControls()
            stepTimeBar()
        }
        // As the window first has it; the listener hears of each change after.
        playerView.doOnAttach { stepTimeBar() }
        // Gone while hidden, so the empty view can't hold a TV remote's focus.
        cardView = ComposeView(this).apply {
            visibility = View.GONE
            setContent {
                AppTheme(formFactor) {
                    nextCard?.let { c ->
                        NextEpisodeCard(
                            c.next, c.card, c.showStill, compact = controlsShown, takeFocus = c.takeFocus, onStop = ::stopCard, onPlayNow = { playNow(c.next) },
                            playNow = cardPlayNow,
                        )
                    }
                }
            }
        }
        // Where the card shows, and like it gone while hidden; shown alone, as the card gives way.
        failureView = ComposeView(this).apply {
            visibility = View.GONE
            setContent {
                AppTheme(formFactor) {
                    failure?.let { f ->
                        PlaybackFailedPanel(
                            f.reason, f.canTryAnother, onTryAnother = ::tryAnother, onClose = ::finish, onDetails = { openPanel(Panel.Details, failureView, controls = false) },
                            details = failureDetails,
                        )
                    }
                }
            }
        }
        // Where the card shows, and like it gone while hidden; the two never show together.
        skipView = ComposeView(this).apply {
            visibility = View.GONE
            setContent {
                AppTheme(formFactor) {
                    skipShown?.let { s ->
                        SkipSegmentButton(s.label, s.takeFocus, ::pressSkip)
                    }
                }
            }
        }
        // Where the card shows, and like it gone while hidden; it waits while another of them shows.
        promptView = ComposeView(this).apply {
            visibility = View.GONE
            setContent {
                AppTheme(formFactor) {
                    val focus = LocalFocusManager.current
                    SideEffect { promptFocus = focus }
                    promptShown?.let { s ->
                        MissingPartsPrompt(
                            s.prompt, compact = controlsShown, takeFocus = s.takeFocus, onKeepWatching = ::keepWatching, onTryAnother = ::tryAnotherForMissingParts,
                            keepWatching = promptKeepWatching,
                        )
                    }
                }
            }
        }
        // Where the card shows, and like it gone while hidden; it waits while another of them shows, and never asks for the focus.
        dipView = ComposeView(this).apply {
            visibility = View.GONE
            setContent {
                AppTheme(formFactor) {
                    dipShown?.let { d -> SlowStreamBanner(d.words, compact = controlsShown, onTrySmaller = ::trySmaller.takeIf { d.canSwitch }) }
                }
            }
        }
        // PipPlayerOff: under the top bar, in the moment label's place, centred in the screen's width less 48 dp at each
        // side. Taps beside it go through to the picture.
        windowOffView = ComposeView(this).apply {
            visibility = View.GONE
            setContent {
                AppTheme(formFactor) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                        if (windowOff) PictureInPictureOff(WindowOff.words(getString(R.string.app_name)), onOpenSettings = ::openWindowSettings)
                    }
                }
            }
        }
        // PipWindowStates: the panel's first line, larger, alone on black over the frozen picture.
        stoppedLine = text(14f, 600, AppColors().warnStrong).apply {
            text = "Playback stopped"
            gravity = Gravity.CENTER
            setBackgroundColor(android.graphics.Color.BLACK)
            visibility = View.GONE
        }
        root = object : FrameLayout(this) {
            override fun focusSearch(focused: View?, direction: Int): View? = nextFocus(focused, direction) ?: super.focusSearch(focused, direction)
        }
        setContentView(root.apply {
            addView(playerView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
            addView(momentLabel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            addView(
                windowOffView,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
                    .apply { setMargins(dp(WINDOW_OFF_SIDES_DP), 0, dp(WINDOW_OFF_SIDES_DP), 0) },
            )
            listOf(cardView, skipView, promptView, dipView, failureView).forEach { overlay ->
                addView(
                    overlay,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
                        .apply { setMargins(0, 0, dp(16), dp(CARD_BOTTOM_DP)) },
                )
            }
            addView(stoppedLine, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            // Over the overlays, which its pill keeps clear of where there is room and its tint goes round; it takes no touch.
            addView(sideMark, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            sideMark.overlays = listOf(cardView, skipView, promptView, dipView)
        })
        liftCentreControls()
        centreMomentLabel()
        placeSideMark()
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            applyInsets(insets)
            insets
        }
        hideSystemBars()
        // First, so the card, the skip button and the "Playback stopped" panel (the last added first) see Back before it. A
        // side panel takes Back itself while it shows (SidePanel), before all of these.
        onBackPressedDispatcher.addCallback(this, backHidesControls)
        onBackPressedDispatcher.addCallback(this, backStopsCard)
        onBackPressedDispatcher.addCallback(this, backHidesSkip)
        onBackPressedDispatcher.addCallback(this, backKeepsWatching)
        onBackPressedDispatcher.addCallback(this, backLeavesFailure)
        onBackPressedDispatcher.addCallback(this, backLeavesMatching)
        // Another play started from the app's screens while the window floats over them; never this one's own move, whose
        // start on the device the sheet says too (L124), which Play in the window or its expanding would drop.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                container.playback.starting.collect { if (pinned && moveJob?.isActive != true) pauseForANewPlay() }
            }
        }
        // Made again in the window (a change of dark mode, the font or the display size), with no mode change to say so (M2).
        showPinned(isInPictureInPictureMode)
    }

    /**
     * Another play while this one is open (#154): this one's progress is saved and its player
     * released, then the new one starts as if the player had just opened, with nothing of the
     * one before: its position, overlays, track choices, next-episode decisions and File details.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        newPlayComing = false
        dropMove()
        // A new play's first player plays (W106): set before endPlay tells the window of the play to come, which would
        // otherwise show Play until that player is made, after a stop before it.
        playsWhenMade = PlayWhenMade.FIRST
        endPlay()
        // The new launch's lease in place of the one before's: the same release's (the next episode of a pack) never let go.
        container.debridAdds.release(lease)
        launch = launchOf(intent)
        holdLaunch()
        resumePosition = C.TIME_UNSET
        picked = TrackChoice()
        showChoice = null
        showChoiceRead = false
        prefetch = null
        cardStopped = false
        switched = false
        releaseFailed = false
        missingParts = newMissingParts()
        trackSheet = null
        fileDetails.start(detailsOrigin(indexers = null))
        lastSubtitles = null
        subtitlesOff = SubtitlePlan.ForcedOnly
        showTitles()
        hideSystemBars()
        // Brought to the front from the background, onStart comes after this and starts it.
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) startPlay()
    }

    /**
     * The mockup's top bar: back arrow, the title (an episode's name, with its show under it), the
     * picture-in-picture button where the player offers the window (PipPlayerButton), the aspect
     * button, the audio and subtitles button and File details' (ⓘ), and on a phone or a tablet the
     * [cast] button last (CsPhonePlayer), over the edge their panels open from, the titles taking
     * what they leave ([PlayerCast.titleRoom]). Hidden until the controls show; clear of a cutout
     * or the system bars ([applyInsets]).
     */
    private fun topBar(back: View, pip: View, aspect: View, audio: View, details: View, cast: View?): LinearLayout {
        val c = AppColors()
        titleText = text(15f, 600, c.text)
        underText = text(12f, 400, c.textSecondary)
        titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(titleText)
            addView(underText)
        }
        showTitles()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            val button = dp(PlayerCast.BUTTON_DP)
            addView(back, LinearLayout.LayoutParams(button, button))
            addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(PlayerCast.TITLES_START_DP), 0, dp(PlayerCast.TITLES_END_DP), 0) })
            listOfNotNull(pip, aspect, audio, details, cast).forEach { addView(it, LinearLayout.LayoutParams(button, button)) }
            setPadding(dp(PlayerCast.BAR_START_DP), dp(8), dp(PlayerCast.BAR_END_DP), dp(8))
        }
    }

    /**
     * The top bar's Cast button (W44, L115; CsPhonePlayer 1): where Play goes from the player, Google's Cast icon at rest
     * (this phone, also with a TV chosen, W59), a Cast device's face while one connects or is connected; a tap opens Play
     * on's list as the player's own panel ([PlayOnPanel]). It calls nothing of Google's as it draws (L114).
     */
    private fun castButton(): ComposeView = ComposeView(this).apply {
        val view = this
        setContent {
            AppTheme(formFactor) {
                CompositionLocalProvider(LocalCastButtonSpot provides castSpot) {
                    val target by container.playOn.target.collectAsStateWithLifecycle()
                    CastButton(target, onClick = { openPanel(Panel.PlayOn, view, controls = true) }, place = SheetPlace.Player)
                }
            }
        }
    }

    /** One line of the player's own text, in the app's font. */
    private fun text(sizeSp: Float, weight: Int, color: Color) = TextView(this).apply {
        typeface = ResourcesCompat.getFont(this@PlayerActivity, R.font.ibm_plex_sans)
        fontVariationSettings = "'wght' $weight"
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color.toArgb())
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    /**
     * The picture's new size, or how far a burst of rewind and fast-forward taps goes, said for a
     * moment ([MOMENT_LABEL_MS]) under the top bar: a pill on the card's surface, centred, clear of
     * the controls' centre buttons down to a 360 dp tall screen with a navigation bar. There, while
     * the next-episode card or the skip button lifts them (liftCentreControls), it covers their
     * top: up to about half the play/pause circle with a 48 dp navigation bar under the card. Read
     * out once by TalkBack.
     */
    private fun momentLabel(): TextView {
        val c = AppColors()
        return text(14f, 600, c.text).apply {
            id = R.id.moment_label
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(c.surface.copy(alpha = 0.94f).toArgb())
            }
            setPadding(dp(16), dp(8), dp(16), dp(8))
            // Between a held key's speed cue and the words.
            compoundDrawablePadding = dp(8)
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
    }

    /** The top bar's title, for the launch playing: an episode's name with its show under it, else the title and its line ([PlayerTitles]). */
    private fun showTitles() {
        val (title, under) = PlayerTitles.of(launch, bareTitle = intent.getStringExtra(EXTRA_TITLE))
        listOf(titleText to title, underText to under).forEach { (view, value) ->
            view.text = value
            view.isVisible = !value.isNullOrEmpty()
        }
    }

    /**
     * Keeps the controls and the overlays clear of the system bars and the display cutout, the bars
     * as if they showed: they are hidden while the player is in front, and when a swipe brings them
     * back for a moment nothing moves. media3's controls, with the time bar at their bottom, keep
     * clear of the gesture area there too (#140), and so do the next-episode card and the skip
     * button above the time bar. The "Playback stopped" panel shows without the controls, and
     * CARD_BOTTOM_DP alone keeps it clear of that area. A TV has neither bars nor cutout: there all of
     * it keeps inside the TV's safe area, 48 dp from the sides and 27 dp from the top and bottom
     * (TV-OV). The picture fills the screen under it all, or with Fill beside camera all but the
     * strip beside the cutout's camera, wherever that now is.
     */
    private fun applyInsets(insets: WindowInsetsCompat) {
        val bars = Insets.max(insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars()), insets.getInsets(WindowInsetsCompat.Type.displayCutout()))
        val safe = if (formFactor == FormFactor.Tv) Insets.max(bars, Insets.of(dp(TV_SIDES_DP), dp(TV_ENDS_DP), dp(TV_SIDES_DP), dp(TV_ENDS_DP))) else bars
        val bottom = maxOf(safe.bottom, insets.getInsets(WindowInsetsCompat.Type.systemGestures()).bottom)
        // Nothing at their top: the centre buttons centre on the room above the bottom inset (and on
        // a short screen go up for the card or the skip button, see liftCentreControls).
        controls.setPadding(safe.left, 0, safe.right, bottom)
        topBar.setPadding(dp(PlayerCast.BAR_START_DP) + safe.left, dp(8) + safe.top, dp(PlayerCast.BAR_END_DP) + safe.right, dp(8))
        momentLabel.updateLayoutParams<FrameLayout.LayoutParams> { topMargin = dp(MOMENT_LABEL_TOP_DP) + safe.top }
        windowOffView.updateLayoutParams<FrameLayout.LayoutParams> { topMargin = dp(MOMENT_LABEL_TOP_DP) + safe.top }
        momentLabelSafeLeft = safe.left
        safeTop = safe.top
        listOf(cardView to bottom, skipView to bottom, promptView to bottom, dipView to bottom, failureView to 0).forEach { (overlay, above) ->
            overlay.updateLayoutParams<FrameLayout.LayoutParams> {
                rightMargin = dp(16) + safe.right
                bottomMargin = dp(CARD_BOTTOM_DP) + above
            }
        }
        // Fill beside camera follows the cutout: turned the other way round the screen keeps its size, and nothing is laid out again.
        cutout = insets.displayCutout?.let { c ->
            Cutout(Sides(c.safeInsetLeft, c.safeInsetTop, c.safeInsetRight, c.safeInsetBottom), c.boundingRects.map { Area(it.left, it.top, it.right, it.bottom) })
        } ?: Cutout()
        applyZoom()
    }

    /**
     * The controls' dim covers the whole picture, past their padding. As they fade to the time bar
     * media3 slides their bottom bar down until only its top edge shows: past their padding it is
     * cut off, as the screen's edge would.
     */
    private fun clipFadingControls() {
        controls.clipToPadding = false
        val bottomBar = controls.findViewById<View>(androidx.media3.ui.R.id.exo_bottom_bar)
        val shown = Rect()
        controls.viewTreeObserver.addOnPreDrawListener {
            val edge = controls.height - controls.paddingBottom - bottomBar.top - bottomBar.translationY.roundToInt()
            shown.set(0, 0, bottomBar.width, edge.coerceIn(0, bottomBar.height))
            bottomBar.clipBounds = shown
            true
        }
    }

    /**
     * On a short screen media3's centre buttons go up while the next-episode card, the skip
     * button, the missing-parts prompt or the slow-stream banner shows with them, as far as it
     * needs to fit under them, above the time bar (CARD_BOTTOM_DP): centred on the room above the
     * controls' bottom inset, they leave the compact
     * card that room only on a screen at least 360 dp tall plus that inset (392 dp with a 32 dp
     * gesture area), more with a larger font. They go no higher than the top bar's bottom: on a
     * shorter screen still the card or the button covers their bottom. Otherwise, a TV too, they
     * stay centred, where the label under the top bar keeps clear of them. Worked out as the
     * player's views are laid out: as the card or the button shows, hides or changes size, as the
     * controls show or hide and as the screen or its insets change. Moved, not given a margin:
     * media3 counts their margins as it decides whether they fit.
     */
    private fun liftCentreControls() {
        val row = controls.findViewById<View>(androidx.media3.ui.R.id.exo_center_controls)
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val overlay = listOf(cardView, skipView, promptView, dipView).firstOrNull { it.isVisible }?.takeIf { controls.isVisible }
            // In the root's coordinates, the buttons inside the row's padding: up until they end at
            // the overlay's top, but not past the top bar's bottom.
            val at = playerView.top + controls.top
            val up = overlay?.let { (at + row.bottom - row.paddingBottom - it.top).coerceAtMost(at + row.top + row.paddingTop - topBar.bottom) } ?: 0
            row.translationY = minOf(0, -up).toFloat()
        }
    }

    /**
     * Hides the status and navigation bars while the player is in front; a swipe from the screen's
     * edge shows them for a moment, and they go again by themselves. Called wherever they may have
     * come back: as the player is made, given a new play or resumed (after onStop too), as its
     * window gets the focus back (after a dialog, the share sheet or a permission prompt, Recents or
     * the lock screen) and as the screen turns. They are this window's: the app's other screens
     * keep theirs. A TV has none to hide.
     */
    private fun hideSystemBars() {
        WindowCompat.getInsetsController(window, window.decorView).run {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onResume() {
        super.onResume()
        // Out of the window by now: also where Android 15 gave up going in after saying it began (onPictureInPictureUiStateChanged).
        showPinned(isInPictureInPictureMode)
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        hideSystemBars()
    }

    /**
     * The top bar as the controls are: as they fade, the title, the picture-in-picture button, the
     * aspect button, the audio and subtitles button, ⓘ and the Cast button go with their buttons and
     * the dim behind them. The back arrow stays with the time bar, so a TV remote's OK on it still
     * leaves; so does a button while it has that focus (the Cast button's inside its view).
     */
    private fun showTopBar(visibility: Int) {
        topBar.visibility = visibility
        titles.visibility = if (controlsFading) View.INVISIBLE else View.VISIBLE
        listOfNotNull(pipButton.takeIf { pictureInPicture }, aspectButton, audioButton, detailsButton, castView)
            .forEach { b -> b.visibility = if (controlsFading && !b.hasFocus()) View.INVISIBLE else View.VISIBLE }
    }

    /**
     * The top bar's picture-in-picture button (W3, L19): the play into the window, playing or paused, as Home takes one
     * playing. Android refuses it while the user has turned picture-in-picture off for the app in its settings: the player
     * then stays as it was and says so ([showWindowOff]). It refuses it otherwise too, under App pinning or on a display
     * that has no window, where the settings have nothing to turn on: the player just stays as it was.
     */
    private fun pressPictureInPicture() {
        if (!pictureInPicture) return
        when {
            enterPictureInPictureMode(tell(windowParams())) -> {
                log.info("Picture-in-picture: asked for by its button")
                // The controls and the overlays go before Android shrinks the player, not after.
                showPinned(true)
            }
            offInSettings() -> {
                log.info("Picture-in-picture: refused, off for the app in Android's settings")
                showWindowOff()
            }
            else -> log.info("Picture-in-picture: refused, on for the app (App pinning, or a display without it)")
        }
    }

    /** Whether the user has turned picture-in-picture off for the app in Android's settings: its app-op, which Android reads first, not allowed. */
    private fun offInSettings(): Boolean {
        val ops = getSystemService(AppOpsManager::class.java)
        val op = AppOpsManager.OPSTR_PICTURE_IN_PICTURE
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(op, applicationInfo.uid, packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(op, applicationInfo.uid, packageName)
        }
        return mode != AppOpsManager.MODE_ALLOWED
    }

    /**
     * PipPlayerOff, proposal A: Android refused the window, picture-in-picture being off for the app in its settings. Under
     * the top bar, in the moment label's place, the player says so, with Open settings ([openWindowSettings]), while the
     * controls show, which the press keeps up as the aspect button's does; the words go with them ([hideWindowOff]), and a
     * moment's words take their place ([say]).
     */
    private fun showWindowOff() {
        hideMoment()
        playerView.showController()
        controlsFading = false
        showTopBar(View.VISIBLE)
        windowOff = true
        windowOffView.isVisible = true
    }

    private fun hideWindowOff() {
        if (!windowOff) return
        windowOff = false
        windowOffView.isVisible = false
    }

    /**
     * Open settings: Android's picture-in-picture page for the app, where the user turns it on again; its App info where a
     * device has no such page. Leaving for them stops the play as Home does, its place saved.
     */
    private fun openWindowSettings() {
        val app = Uri.fromParts("package", packageName, null)
        try {
            startActivity(Intent(PICTURE_IN_PICTURE_SETTINGS, app))
        } catch (e: ActivityNotFoundException) {
            log.info("Picture-in-picture: no page for it in Android's settings, App info instead")
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, app))
        }
    }

    /**
     * The aspect button: the next size ([VideoZoom.next]), Fill beside camera among them while a
     * camera is beside the picture. Like a press on the controls' own buttons it keeps them up,
     * also as they fade with the button holding a TV remote's focus.
     */
    private fun pressAspect() {
        setZoom(zoom.next(besideCamera))
        playerView.showController()
        controlsFading = false
        showTopBar(View.VISIBLE)
    }

    /** The picture sized as [z] chose: shown, said for a moment, and saved for the next play. */
    private fun setZoom(z: VideoZoom) {
        zoom = z
        zoomKnown = true
        applyZoom()
        showZoom()
        say(z.shownAs(besideCamera).label)
        val before = zoomSave
        // In the app's scope, so leaving doesn't cut a save short; after the saves before it, so the last choice wins.
        zoomSave = container.appScope.launch {
            before?.join()
            saveOrLog("save the picture's size") { container.settings.updatePlayback(z::saveIn) }
        }
    }

    /**
     * Says [text] for a moment ([forMs]) under the top bar, after a held key's speed [cue] if given; TalkBack reads it,
     * or [spoken] if given. [quiet] while a key is held to seek: the label follows each of its repeats, about 20 a
     * second, but isn't a live region then, and TalkBack hears it once, as the key is let go ([endHold], L8). [burst]: a
     * burst of seeks' words, which the side mark takes over if it comes to show them ([markSay]). The words that
     * picture-in-picture is off give way to it ([showWindowOff]).
     */
    private fun say(
        text: String,
        spoken: String? = null,
        forMs: Long = MOMENT_LABEL_MS,
        cue: SpeedCue? = null,
        quiet: Boolean = false,
        burst: Boolean = false,
    ) {
        // Nothing in the window: TalkBack would read it out over whatever the user is doing.
        if (!windowScene().overlays) return
        hideWindowOff()
        momentSaysBurst = burst
        // A live region again before its words change, so TalkBack is told of them.
        momentLabel.accessibilityLiveRegion = if (quiet) View.ACCESSIBILITY_LIVE_REGION_NONE else View.ACCESSIBILITY_LIVE_REGION_POLITE
        markMoment(cue)
        momentLabel.text = text
        // None while quiet, so the words said at the up are a change.
        momentLabel.contentDescription = spoken.takeUnless { quiet }
        momentLabel.isVisible = true
        momentLabelJob?.cancel()
        momentLabelJob = lifecycleScope.launch {
            delay(forMs)
            momentLabel.isVisible = false
        }
    }

    /**
     * Says [text] under the top bar until [hideMoment], as [say] says a moment's: the line on black while the TV switches
     * its frame rate, before the player is made; "Moving to Kitchen display…" after its spinning [mark] while Play & connect
     * moves the film. TalkBack reads [spoken].
     */
    private fun showMoment(text: String, spoken: String, mark: Drawable? = null) {
        hideWindowOff()
        momentLabelJob?.cancel()
        momentLabel.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        markMoment(mark)
        momentLabel.text = text
        momentLabel.contentDescription = spoken
        momentLabel.isVisible = true
    }

    private fun hideMoment() {
        momentLabelJob?.cancel()
        markMoment(null)
        momentLabel.isVisible = false
    }

    /** The moment label's [mark] before its words, a spinning one turning while it shows: one replaced stops. */
    private fun markMoment(mark: Drawable?) {
        val before = momentLabel.compoundDrawablesRelative[0]
        if (before !== mark) (before as? Animatable)?.stop()
        momentLabel.setCompoundDrawablesRelativeWithIntrinsicBounds(mark, null, null, null)
        (mark as? Animatable)?.start()
    }

    /** The aspect button as the picture is: its icon, and what a press does; a kept Fill beside camera as Fill screen without a camera. */
    private fun showZoom() {
        aspectButton.setImageResource(
            when (zoom.shownAs(besideCamera).mode) {
                AspectMode.Fit -> R.drawable.ic_aspect_fit
                AspectMode.Fill -> R.drawable.ic_aspect_fill
                AspectMode.FillBesideCamera -> R.drawable.ic_aspect_beside_camera
                AspectMode.Zoom -> R.drawable.ic_aspect_zoom
            },
        )
        aspectButton.contentDescription = zoom.description(besideCamera)
    }

    /** Where Fit lays the picture out in the player, as now laid out, with the window's cutout (the player fills the window). */
    private fun pictureFrame() = PictureFrame(contentFrame.width, contentFrame.height, playerView.width, playerView.height, cutout)

    /**
     * Scales the video surface alone, about its centre, from where Fit lays it out, and for Fill
     * beside camera moves it beside the camera and cuts it there, on black: the subtitles, the
     * buffering spinner and the overlays stay as they are. During a pinch, as it shows it
     * ([VideoZoom.pinched]); not before the player's first frame ([pictureShown]). As a camera
     * comes beside the picture or goes (the screen turned, the window or its cutout changed), the
     * button follows.
     */
    private fun applyZoom() {
        val frame = pictureFrame()
        if (frame.hasCamera != besideCamera) {
            besideCamera = frame.hasCamera
            showZoom()
        }
        val look = if (!pictureShown) VideoZoom().look(frame) else pinchScale?.let { zoom.pinched(it, frame) } ?: windowScene().zoom(zoom).look(frame)
        val (x, y) = look.scales
        val (dx, dy) = look.shift
        playerView.videoSurfaceView?.apply {
            scaleX = x
            scaleY = y
            translationX = dx
            translationY = dy
            clipBounds = look.clip?.let { Rect(it.left, it.top, it.right, it.bottom) }
        }
        // The strip beside the camera black, and a pinch from there on black; elsewhere, as Fit's bars, the window's own background, black too.
        playerView.setBackgroundColor(if (look.black) android.graphics.Color.BLACK else android.graphics.Color.TRANSPARENT)
    }

    /** A pinch on the picture: from the scale showing, between Fit and the frame's maximum; where it ends, [VideoZoom.afterPinch]. */
    private inner class PinchListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            if (panel != null || failure != null || !pictureShown || pinned) return false
            pinchScale = zoom.pinchStart(pictureFrame())
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            pinchScale = pinchScale?.let { (it * detector.scaleFactor).coerceIn(1f, pictureFrame().maxScale) }
            applyZoom()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            val scale = pinchScale ?: return
            pinchScale = null
            // Back where it started, the picture is as it was: nothing to say or save.
            val after = zoom.afterPinch(scale, pictureFrame())
            if (after == zoom) applyZoom() else setZoom(after)
        }
    }

    /**
     * A pinch zooms the picture, but not before it shows, nor under a side panel or the "Playback
     * stopped" panel. Once it starts, the touch is the pinch's: what had it gets a cancel, so
     * PlayerView doesn't take it for a tap that shows or hides the controls and no control under a
     * finger is pressed. On a phone, taps at the picture's sides go to [EdgeTaps] too: each touch's
     * down reaches the views first, so it is known whether it came down on the picture, and an up it
     * takes (a tap that waits for a second, or a seek's) reaches them as a cancel, as a pinch's touch
     * does, so PlayerView doesn't show or hide the controls for it. Other taps reach PlayerView as before.
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            pinching = false
            reachedPicture = false
        }
        pinch.onTouchEvent(event)
        if (pinchScale != null && !pinching) {
            pinching = true
            cancelViews(event)
        }
        if (action == MotionEvent.ACTION_DOWN) {
            val handled = super.dispatchTouchEvent(event)
            val touch = touch(event)
            edge(edgeTaps.down(touch, reachedPicture, edgeScene()), touch)
            return handled
        }
        val touch = touch(event)
        val act = when (action) {
            MotionEvent.ACTION_POINTER_DOWN -> edgeTaps.pointerDown()
            MotionEvent.ACTION_MOVE -> edgeTaps.move(touch)
            MotionEvent.ACTION_UP -> edgeTaps.up(touch)
            MotionEvent.ACTION_CANCEL -> edgeTaps.cancel()
            else -> EdgeTaps.Act.Pass
        }
        if (act.takesUp && !pinching) cancelViews(event)
        edge(act, touch)
        return pinching || act.takesUp || super.dispatchTouchEvent(event)
    }

    /**
     * Marks the touches that come down on the picture itself, which no child of PlayerView (a control, the time bar) took:
     * only those count as taps at the sides ([EdgeTaps]). It takes none of them, so PlayerView's own click, and what
     * TalkBack does with it, go on as before.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun markTouchesOnPicture() {
        playerView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) reachedPicture = true
            false
        }
    }

    /** The views under a touch get a cancel in its place: it is no longer theirs. */
    private fun cancelViews(event: MotionEvent) {
        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    /** [event]'s first finger on the picture, as [EdgeTaps] takes it. */
    private fun touch(event: MotionEvent): EdgeTaps.Touch {
        playerView.getLocationInWindow(pictureAt)
        return EdgeTaps.Touch(event.x - pictureAt[0], event.y - pictureAt[1], playerView.width, event.getToolType(0))
    }

    /** The player as a touch comes down, for [EdgeTaps]: off on a TV, under a panel, before the first frame and with touch exploration on (L10). */
    private fun edgeScene() = EdgeTaps.Scene(
        tv = formFactor == FormFactor.Tv,
        panel = panel != null,
        failure = failure != null,
        pictureShown = pictureShown && controlsPlayer != null,
        touchExploration = touchExploration(this),
    )

    /**
     * Does what [EdgeTaps] says of a touch event ([at] where on the picture, null for its wait ending), and has a tap
     * that waits for a second act once its wait is over: PlayerView's own click shows or hides the controls.
     */
    private fun edge(act: EdgeTaps.Act, at: EdgeTaps.Touch?) {
        when (act) {
            EdgeTaps.Act.Toggle -> playerView.performClick()
            EdgeTaps.Act.Hold -> controlsPlayer?.holdOpen()
            EdgeTaps.Act.Settle -> controlsPlayer?.settle(EdgeTaps.WINDOW_MS)
            EdgeTaps.Act.Back, EdgeTaps.Act.Forward -> edgeSeek(act == EdgeTaps.Act.Forward, at?.let { PointF(it.x, it.y) })
            EdgeTaps.Act.Pass, EdgeTaps.Act.Wait -> Unit
        }
        playerView.removeCallbacks(edgeTimeUp)
        edgeTaps.toggleDueInMs()?.let { playerView.postDelayed(edgeTimeUp, it) }
    }

    /**
     * A seek's tap at a side: 10 s that way (L16) into the controls' burst, the side mark saying how far ([markSay]). Its
     * seek waits the burst's window from this tap, also after a tap the burst was held for. The controls stay as they
     * were (L14): hidden stay hidden; up, their timeout is put off.
     */
    private fun edgeSeek(forward: Boolean, tap: PointF?) {
        val c = controlsPlayer ?: return
        if (controlsShown) playerView.showController()
        c.step(if (forward) PlayerFactory.SEEK_FORWARD_MS else -PlayerFactory.SEEK_BACK_MS, waitMs = EdgeTaps.WINDOW_MS) { said -> markSay(said, forward, tap) }
        c.settle(EdgeTaps.WINDOW_MS)
    }

    /**
     * Says a burst's [said] on the side mark (L13): on the side its total points to (L16b), else the side it shows on,
     * else [towards] (the side tapped, at a bound); with where it goes while the controls are hidden; [tap] where the finger
     * was, for the brighter ring, on that side. It fades a moment ([MOMENT_LABEL_MS]) after the last. [quiet] while a key
     * held to seek says it, as the label is ([say]). The label under the top bar leaves the burst's words to it (M7).
     */
    private fun markSay(said: SeekBurst.Said, towards: Boolean?, tap: PointF? = null, quiet: Boolean = false) {
        val c = controlsPlayer ?: return
        val was = marked?.takeIf { sideMark.isVisible }
        val forward = said.forward ?: was?.forward ?: towards ?: true
        val ring = tap?.takeIf { towards == forward } ?: was?.tap?.takeIf { was.forward == forward }
        marked = Marked(said, forward, format(c.currentPosition), ring, quiet)
        if (momentSaysBurst && momentLabel.isVisible) hideMoment()
        renderMark(appear = true)
        markJob?.cancel()
        markJob = lifecycleScope.launch {
            delay(MOMENT_LABEL_MS)
            sideMark.fade()
        }
    }

    /**
     * The side mark as [marked] has it, its time only while the controls are hidden: with them up the time bar shows where
     * it goes. [appear] for a burst's new words: in full again, its fade put off. Else (the controls come or go, a held
     * key's up) it says it as it is, showing or fading: a fade stopped here would leave it on the picture for good.
     */
    private fun renderMark(appear: Boolean = false) {
        val m = marked ?: return
        val time = m.time.takeUnless { controlsShown }
        if (appear) sideMark.show(m.said, m.forward, time, m.tap, m.quiet) else sideMark.update(m.said, m.forward, time, m.tap, m.quiet)
        sideMark.place(sideMarkRoom())
    }

    /** The side mark gone, at once: the player goes, a panel opens, playback stops. */
    private fun hideMark() {
        markJob?.cancel()
        marked = null
        sideMark.hide()
    }

    /** What the side mark says: a burst's words on the right if [forward], the [time] it goes to, the [tap]'s ring; [quiet] for a held key. */
    private data class Marked(val said: SeekBurst.Said, val forward: Boolean, val time: String, val tap: PointF?, val quiet: Boolean)

    /**
     * Where the side mark's pill goes (NxPhoneSeekControls, NxPhoneSeekOverlays, NxPhoneSeekInsets): on the room its third
     * leaves inside the controls' padding (the cutout's and the bars' insets, as if the bars showed), beside the controls'
     * centre buttons while they show; centred on the room above the bottom inset, as those buttons are, but up clear of an
     * overlay in the bottom-end slot that reaches into that room. Worked out as the player's views are laid out.
     */
    private fun placeSideMark() {
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (sideMark.isVisible) sideMark.place(sideMarkRoom()) }
    }

    private fun sideMarkRoom(): SideMark.Room {
        val width = root.width
        val forward = sideMark.forward
        val row = controls.findViewById<ViewGroup>(androidx.media3.ui.R.id.exo_center_controls)
        // The buttons that show in the centre row, across the root.
        val buttons = row.takeIf { controlsShown && it.isShown }?.let { r ->
            val at = IntArray(2).also(r::getLocationInWindow)[0] - IntArray(2).also(root::getLocationInWindow)[0]
            val shown = (0 until r.childCount).map(r::getChildAt).filter { it.isVisible }
            if (shown.isEmpty()) null else at + shown.minOf { it.left } to at + shown.maxOf { it.right }
        }
        val left = if (forward) maxOf(width * 2 / 3, buttons?.second ?: 0) else controls.paddingLeft
        val right = if (forward) width - controls.paddingRight else minOf(width / 3, buttons?.first ?: width)
        val overlay = listOf(cardView, skipView, promptView, dipView).firstOrNull { it.isVisible && it.width > 0 && it.left < right && it.right > left }
        // Under the top bar while it shows, else under the cutout and the status bar as if it showed.
        val top = if (controlsShown) topBar.bottom else safeTop
        return SideMark.Room(left, right, top, (root.height - controls.paddingBottom) / 2, overlay?.top, if (forward) controls.paddingRight else controls.paddingLeft)
    }

    /**
     * A round icon button over the picture: 44 dp to see, 48 dp to touch, filled while a remote's focus is on it. On a
     * TV it turns over as the app's own buttons do there, read from across the room (#148): the text's cream fills it,
     * the background's near-black draws its icon, and it grows a little; a pointer remote's hover does the same, as it
     * does on the app's own buttons there ([FocusStyle.hover]). Elsewhere the accent fills it.
     */
    private fun roundButton(icon: Int, description: String, onClick: () -> Unit) = ImageButton(this).apply {
        val c = AppColors()
        val tv = formFactor == FormFactor.Tv
        val style = if (tv) FocusStyle.Tv else FocusStyle.Phone
        fun circle(color: Color) = InsetDrawable(GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color.toArgb()) }, dp(2))
        val (fill, ink) = if (tv) c.text to c.background else c.accent to c.onAccent
        // The states that show as focused: the focus, and a pointer over it where hover looks the same. The lists below
        // take them first, then the empty set for any other state.
        val lit = listOfNotNull(intArrayOf(android.R.attr.state_focused), intArrayOf(android.R.attr.state_hovered).takeIf { style.hover })
        setImageResource(icon)
        contentDescription = description
        imageTintList = ColorStateList((lit + intArrayOf()).toTypedArray(), IntArray(lit.size) { ink.toArgb() } + c.text.toArgb())
        background = RippleDrawable(
            ColorStateList.valueOf(c.text.copy(alpha = 0.24f).toArgb()),
            StateListDrawable().apply { lit.forEach { addState(it, circle(fill)) } },
            circle(Color.White),
        )
        if (tv) stateListAnimator = focusGrows(style.buttonScale, lit)
        setOnClickListener { onClick() }
    }

    /** A view's growing to [scale] while in one of the [lit] states (focused, hovered), and back. */
    private fun focusGrows(scale: Float, lit: List<IntArray>) = StateListAnimator().apply {
        // No target here (the state list sets the view): ofPropertyValuesHolder would take the first holder for one, and
        // grow the view only down.
        fun to(s: Float) = ObjectAnimator().apply {
            setValues(PropertyValuesHolder.ofFloat(View.SCALE_X, s), PropertyValuesHolder.ofFloat(View.SCALE_Y, s))
            duration = FOCUS_SCALE_MS
        }
        lit.forEach { addState(it, to(scale)) }
        addState(intArrayOf(), to(1f))
    }

    /**
     * Opens [which] side panel over the player: the audio and subtitles sheet, File details, or Play on's list. While one
     * shows the controls stay down, the next-episode card, the skip button, the "Playback stopped" panel and the picture
     * take no focus, nor TalkBack's, and keys go to the panel; playback goes on. The sheet needs a player and no failure;
     * File details open over the "Playback stopped" panel too, with what was read before the play stopped; Play on's list
     * opens from the Cast button. As the panel closes the focus goes back to [from]: the button that opened it, what had
     * the focus as Info opened them (the card's Play now as [cardView]), the "Playback stopped" panel's ([failureView]),
     * or, for none (Info on the picture), the picture; with [controls], the controls come up again. Opening one closes
     * the other.
     */
    private fun openPanel(which: Panel, from: View?, controls: Boolean) {
        when (which) {
            Panel.Tracks -> updateSheet(player?.takeIf { failure == null } ?: return)
            Panel.Details -> if (fileDetails.details == null) return
            Panel.PlayOn -> Unit
        }
        val was = panel
        if (was == which) return
        opener = from
        openerControls = controls
        hideMark()
        if (was == null) {
            heldFocus = listOf<ViewGroup>(cardView, skipView, promptView, dipView, failureView, playerView).map { v ->
                HeldFocus(v, v.descendantFocusability, v.focusable, v.importantForAccessibility).also {
                    v.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    v.focusable = View.NOT_FOCUSABLE
                    v.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                }
            }
            skipShown = skipShown?.copy(takeFocus = false)
            promptShown = promptShown?.copy(takeFocus = false)
        }
        panel = which
        updateControlsUse()
        fileDetails.showing(which == Panel.Details)
        val view = panelView ?: ComposeView(this).apply {
            // On a TV inside its safe area, as the controls are (applyInsets).
            val safeArea = if (formFactor == FormFactor.Tv) WindowInsets(TV_SIDES_DP.dp, TV_ENDS_DP.dp, TV_SIDES_DP.dp, TV_ENDS_DP.dp) else WindowInsets(0)
            setContent {
                AppTheme(formFactor) {
                    val focus = LocalFocusManager.current
                    SideEffect { panelFocus = focus }
                    when (panel) {
                        Panel.Tracks -> trackSheet?.let { TrackSheetPanel(it, ::onSheetAction, ::closePanel, safeArea) }
                        Panel.Details -> fileDetails.details?.let { FileDetailsPanel(it, ::closePanel, safeArea) }
                        Panel.PlayOn -> PlayOnPanel(::closePanel, onAddTv = ::openAddTv, onPrivacy = ::openPrivacy, safeArea, onCastTapped = ::castTapped, onChoosing = ::choosingInTheList)
                        null -> Unit
                    }
                }
            }
            root.addView(this, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }.also { panelView = it }
        view.isVisible = true
    }

    /**
     * Closes the side panel showing; with [backToButton], the focus goes back to what opened it ([opener]): a button of
     * the top bar or the controls with the controls up again (the Cast button's inside its view, [castFocus]), the
     * next-episode card's Play now, the "Playback stopped" panel's File details, or the picture, also where what had it
     * has gone meanwhile.
     */
    private fun closePanel(backToButton: Boolean = true) {
        if (panel == null) return
        panel = null
        fileDetails.showing(false)
        panelView?.isVisible = false
        heldFocus.forEach { (v, descendants, focusable, accessibility) ->
            v.descendantFocusability = descendants
            v.focusable = focusable
            v.importantForAccessibility = accessibility
        }
        heldFocus = emptyList()
        // Under the "Playback stopped" panel and in the window they stay off.
        updateControlsUse()
        val to = opener.also { opener = null }
        if (!backToButton || isFinishing || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        when {
            to === failureView -> failureDetails.tryFocus()
            to != null -> {
                if (openerControls) {
                    playerView.showController()
                    controlsFading = false
                    showTopBar(View.VISIBLE)
                }
                // The card asks for the focus only as it shows: it is given back.
                val back = when {
                    to === cardView -> cardPlayNow.tryFocus()
                    to === castView -> castFocus()
                    else -> to.requestFocus()
                }
                if (!back) playerView.requestFocus()
                // TalkBack's focus too, which left with the button as the controls went down, whether or not the button
                // took the input focus: TalkBack keeps a phone in touch mode, where the top bar's buttons take none but
                // still take TalkBack's. Without an accessibility service this does nothing. From the skip button, which
                // only Info opens File details from, the opener is its Compose host: TalkBack's focus goes to the host,
                // not the button in it.
                if (to !== cardView && to !== castView && to.isShown) to.performAccessibilityAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null)
            }
            else -> playerView.requestFocus()
        }
    }

    /**
     * The focus back on the Cast button as Play on's list closes: its own, inside its view, which TalkBack's follows. Only
     * for TalkBack or a keyboard, as the introduction gives it ([CastIntroFocus]): a finger leaves no button focused, as
     * the top bar's others take none in touch mode. Whether it was given.
     */
    private fun castFocus(): Boolean {
        if (!CastIntroFocus.moves(this, playerView.isInTouchMode)) return false
        val button = castSpot.placed ?: return false
        button.focus()
        return true
    }

    /**
     * Privacy, from Play on's list (L138): the app's privacy page, over its screens. The player is left as for any leave,
     * its place saved; the list closes first, so a player kept in a task of its own (after the window) is as it was.
     */
    private fun openPrivacy() {
        closePanel(backToButton = false)
        startActivity(MainActivity.openPrivacy(this))
    }

    /** Add a TV, from Play on's list: the app's own, over its screens, as [openPrivacy] opens the privacy page. */
    private fun openAddTv() {
        closePanel(backToButton = false)
        startActivity(MainActivity.openAddTv(this))
    }

    /**
     * This phone about to be chosen in Play on's list (L148): chosen everywhere, and the list closes, as it does on any
     * screen. A move under way is called off first ([dropMove]), as the choice ends the session the move connects or loads
     * on at once (L85), which the move would take for a failure: the film plays on here, nothing said, as nothing failed.
     */
    private fun choosingInTheList() {
        if (moveJob?.isActive == true || judging?.isActive == true) {
            dropMove()
            hideMoment()
            controlsPlayer?.play()
        }
        closePanel()
    }

    /**
     * A Cast row tapped in Play on's list (L124, L151): the player takes it, so the list's group connects nothing itself,
     * and does what [CastMove] says of it, once it has read what the device makes of the film ([castFit]), whatever its
     * source (a debrid play's relayed by the phone, W73): a file type no Cast device plays says so, connecting nothing; a
     * speaker asks first; a film the device can't fully play asks the pre-check first; else the film moves there ([moveTo]).
     */
    private fun castTapped(route: CastRoute): Boolean {
        judging?.cancel()
        castAnywayFor = null
        judging = lifecycleScope.launch {
            val fit = castFit(route)
            castStep(CastMove.tapped(route.name, route.kind, playingFileName(), subtitlesShowing(), container.playOn.castAnywayTold(route.id), fit), route, fit)
        }
        return true
    }

    /**
     * What [route]'s device makes of the film (L124 steps 2 and 3, item 44): by the tracks its launch's probe read, or for a
     * film without them (a file Import opened, a launch from before they were kept) by what this player reads of the file
     * it plays. Nothing judged where nothing can be read, or the judging fails: the move goes on as without it.
     */
    private suspend fun castFit(route: CastRoute): CastMove.Fit {
        val tracks = launch?.tracks ?: player?.currentTracks?.takeUnless { it.isEmpty }?.let(ExoTrackProbe::tracksOf)
        val release = launch?.release?.title ?: openedRelease() ?: return CastMove.Fit.None
        return try {
            container.playback.castFit(launch, tracks, release, route.castTo())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Its class alone (L221): a debrid play's failure may hold a Media3 HTTP exception, whose cause can name the CDN's host.
            log.warn("Play & connect: the film couldn't be judged for the Cast device ({})", e.javaClass.simpleName)
            CastMove.Fit.None
        }
    }

    /** An opened file's release, as Import's cast names it: its NZB's title, else the file's name; null for none. */
    private fun openedRelease(): String? {
        val target = UsenetUris.parse(intent.getStringExtra(EXTRA_URI) ?: return null) ?: return null
        return container.store.get(target.nzbId)?.title ?: target.name
    }

    /** What the player does for a tap on [route]'s row, its film [fit]ting the device as it does, or for a question's answer. */
    private fun castStep(step: CastMove.Step, route: CastRoute, fit: CastMove.Fit) {
        when (step) {
            is CastMove.Step.Refuse -> {
                closePanel()
                sayLine(step.line)
            }
            CastMove.Step.AskSoundOnly -> {
                closePanel()
                askSoundOnly(route, fit)
            }
            is CastMove.Step.AskPrecheck -> {
                closePanel()
                askPrecheck(route, step.question)
            }
            is CastMove.Step.Connect -> moveTo(route, step.line, step.compatible)
            // Play on this phone or Cancel: the film plays on as it is. A tap or an answer comes to neither of the others.
            CastMove.Step.Stay, CastMove.Step.Close, is CastMove.Step.PlayOn -> Unit
        }
    }

    /**
     * L122's question over the film (L124 step 2): Cast anyway goes on, to the pre-check where the film doesn't [fit] the
     * device, else moving it there, and is remembered for the session that opens, as the router's is; Play on this phone
     * and Cancel, or Back, leave it playing here and connect nothing.
     */
    private fun askSoundOnly(route: CastRoute, fit: CastMove.Fit) {
        fun answer(with: PlayOnAsk.SoundOnly.Answer) = castStep(CastMove.answered(with, route.name, subtitlesShowing(), fit), route, fit)
        castAsking = PlayOnAsk.SoundOnly(
            route.id, route.name,
            castAnyway = {
                castAnywayFor = route.id
                answer(PlayOnAsk.SoundOnly.Answer.CastAnyway)
            },
            playHere = { answer(PlayOnAsk.SoundOnly.Answer.PlayHere) },
            cancel = { answer(PlayOnAsk.SoundOnly.Answer.Cancel) },
        )
        askOverTheFilm()
    }

    /**
     * The pre-check over the film (L124 step 2, L130; CsPhonePrecheck), before anything connects: Cast anyway moves it
     * there; Play a compatible release connects, then plays the list's others there in its place; Play on this phone and
     * Cancel, or Back, leave it playing here and connect nothing.
     */
    private fun askPrecheck(route: CastRoute, question: CastPrecheck) {
        val title = launch?.title ?: intent.getStringExtra(EXTRA_TITLE).orEmpty()
        castQuestion = CastQuestion(title, launch?.subtitle, question) { button ->
            castQuestion = null
            castStep(CastMove.prechecked(button, route.name, subtitlesShowing()), route, CastMove.Fit.None)
        }
        askOverTheFilm()
    }

    /** The view the questions over the film are asked from, made the first time one is. */
    private fun askOverTheFilm() {
        if (askView != null) return
        askView = ComposeView(this).apply {
            setContent {
                AppTheme(formFactor) {
                    castAsking?.let { ask -> SoundOnlyQuestion(ask, onAnswered = { castAsking = null }) }
                    castQuestion?.let { asked -> CastPrecheckQuestion(asked.title, asked.subtitle, asked.precheck, asked.answer) }
                }
            }
            root.addView(this, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    /** The pre-check asked over the film: the play's [title] and [subtitle] over [precheck], each answer to [answer]. */
    private class CastQuestion(val title: String, val subtitle: String?, val precheck: CastPrecheck, val answer: (CastPrecheck.Button) -> Unit)

    /** [this] route as a cast play names its device (L153). */
    private fun CastRoute.castTo() = CastTo(id, name, deviceId, model)

    /**
     * Play & connect (W45, L124 steps 3-6; CsPhonePlayer 3, 4): the list closes, the film pauses where it is (P) and [line]
     * says so, the button showing the device connecting, while [route]'s device connects, chosen in Play on as its row's
     * tap chooses it; one connected already stays as it is. Connected, the coordinator loads the same file there from P
     * ([PlaybackCoordinator.moveToCast]), an opened file's through Import's cast, or for Play a compatible release
     * ([compatible]) the list's others start there ([compatibleThere]); the film waits here, paused. Then [moved] does
     * what came of it.
     */
    private fun moveTo(route: CastRoute, line: CastMove.Line, compatible: Boolean = false) {
        val extras = castExtras ?: return
        val c = controlsPlayer ?: return
        closePanel(backToButton = false)
        // Where the controls show the film, a burst of rewind and fast-forward taps sought first.
        c.flush()
        c.pause()
        val at = c.currentPosition
        playerView.showController()
        showMoment(line.words, line.spoken, mark = movingMark)
        moveJob?.cancel()
        moveJob = lifecycleScope.launch {
            // A device whose session lives already (connected from another screen while the film played on here) isn't
            // connected again: selected anew, it would start no session, and the wait for one would end it.
            moveConnects = extras.now.value?.route != route.id
            moveOutcome = null
            if (moveConnects) extras.connect(route)
            container.playOn.choose(PlayChoice.Cast(route.id, route.name))
            // Cast anyway, told the speaker's question, lasts the session now there: kept as it connects, forgotten as it
            // ends or never connects (L122).
            if (castAnywayFor == route.id) container.playOn.castAnyway(route.id)
            // Connecting until the session says connected, or that it isn't: none, or another device's.
            val now = extras.now.first { it == null || it.route != route.id || it.link == CastLink.Connected }
            val step = when {
                now?.route != route.id -> CastMove.connectFailed(route.name)
                compatible -> return@launch compatibleThere(at, route.castTo())
                else -> {
                    val to = route.castTo()
                    val move = (launch?.let { container.playback.moveToCast(it, at, to) } ?: importedMove(at, to)).also { moveOutcome = it }
                    if (move != null) CastMove.after(move.await(), route.name) else notKept(route.name)
                }
            }
            moved(step)
        }
    }

    /**
     * Play a compatible release, its device connected (L152, L124 step 2): the player saves its place at [at] and closes, as
     * Try a smaller release closes it, and the coordinator plays the request's other releases the device [to] should play
     * there from that place ([PlaybackCoordinator.playCompatible]), the play sheet saying how it goes. The film itself is
     * never sent there. Offered only for a launch, which has the request: without one the film plays on here.
     */
    private fun compatibleThere(at: Long, to: CastTo) {
        hideMoment()
        val l = launch
        if (l == null) {
            controlsPlayer?.play()
            return
        }
        if (isFinishing) return
        player?.let { saveProgress(at, it.duration) }
        val handedOver = handOverMode()
        container.playback.playCompatible(l, at, to)
        finishHandingOver(handedOver)
    }

    /**
     * An opened file's move (L124, L147): the file the player plays, as the store keeps its NZB, through Import's cast;
     * null where the store no longer has it.
     */
    private fun importedMove(at: Long, to: CastTo): Deferred<MoveOutcome>? {
        val target = UsenetUris.parse(intent.getStringExtra(EXTRA_URI) ?: return null) ?: return null
        val file = container.store.get(target.nzbId)?.find(target.fileIndex, target.name, target.size) ?: return null
        return container.playback.moveToCast(target.nzbId, file, at, to)
    }

    /** The opened file's NZB is no longer kept: nothing went to the device, whose session ends as This phone is chosen. */
    private fun notKept(name: String): CastMove.Step {
        container.playOn.choose(PlayChoice.Phone)
        return CastMove.Step.PlayOn(CastMove.failedLine(CastLoadFailure(CastFailKind.CouldNotPlay, IMPORT_GONE), name))
    }

    /**
     * What came of a move (L124 steps 5 and 6): the device plays the film, so the player closes, its place saved as for any
     * exit, as a hand-over closes it, and the remote opens in the app's screens; or it didn't, and the film plays on here
     * from where it paused, This phone chosen, the line saying why.
     */
    private fun moved(step: CastMove.Step) {
        hideMoment()
        when (step) {
            CastMove.Step.Close -> {
                if (isFinishing) return
                switched = true
                autoAdvance?.stop()
                // Only from the player in front: Android lets no app start a screen from behind another.
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) startActivity(MainActivity.openRemote(this))
                finishHandingOver(handedOver = false)
                showCard(null)
            }
            is CastMove.Step.PlayOn -> {
                controlsPlayer?.play()
                sayLine(step.line)
                castFocusBack()
            }
            CastMove.Step.Stay -> castFocusBack()
            is CastMove.Step.Refuse, CastMove.Step.AskSoundOnly, is CastMove.Step.AskPrecheck, is CastMove.Step.Connect -> Unit
        }
    }

    /**
     * A move that didn't go (L124 step 6): TalkBack's focus, or a keyboard's, was on the list's row, which went as the move
     * began, and goes back to the Cast button with the controls up, as the list's Back gives it back ([castFocus]); a
     * finger leaves none.
     */
    private fun castFocusBack() {
        if (pinned || isFinishing || !CastIntroFocus.moves(this, playerView.isInTouchMode)) return
        playerView.showController()
        controlsFading = false
        showTopBar(View.VISIBLE)
        castFocus()
    }

    /** [line] under the top bar, for as long as it says. */
    private fun sayLine(line: CastMove.Line) = say(line.words, line.spoken, forMs = line.forMs ?: MOMENT_LABEL_MS)

    /**
     * The file playing, by its name: what a Cast device plays is told by its type (L57). Its URI's, else its launch's (a
     * service's URI holds none); null for a launch from before names were kept.
     */
    private fun playingFileName(): String? =
        PlayUris.parse(launch?.uri ?: intent.getStringExtra(EXTRA_URI).orEmpty())?.name ?: launch?.release?.fileName

    /** Whether subtitles show now: they don't on a Cast device (W55), which the move's line says. */
    private fun subtitlesShowing(): Boolean {
        val p = player?.takeUnless { it.currentTracks.isEmpty } ?: return false
        return TrackSelection.shownSubtitles(TrackOptions.of(p.currentTracks), p.trackSelectionParameters) is SubtitlePlan.Track
    }

    /**
     * Another play takes the player over ([onNewIntent]), This phone is chosen ([choosingInTheList]), or the player is made
     * again ([callOffMove]): no move, and no question, for this one.
     */
    private fun dropMove() {
        judging?.cancel()
        judging = null
        castAnywayFor = null
        moveJob?.cancel()
        moveJob = null
        moveOutcome = null
        castAsking = null
        castQuestion = null
    }

    /** The sheet as the player's tracks and selection now are; loading while it knows none (each player opens the file anew). */
    private fun updateSheet(p: Player, options: List<TrackOption> = TrackOptions.of(p.currentTracks)) {
        val subtitles = TrackSelection.shownSubtitles(options, p.trackSelectionParameters)
        trackSheet = TrackSheet(options, subtitles, canReset = show != null && showChoice != null, loading = p.currentTracks.isEmpty)
    }

    /**
     * Subtitles chosen by the sheet, the captions key or a player's plan (the rule's, the show's,
     * this play's), for the captions key: a track it brings back, or what it turns them off to.
     * Not what shows: as a player first reports its tracks, they are media3's own pick (the track
     * flagged default), not yet the plan's.
     */
    private fun chose(subtitles: SubtitlePlan) {
        if (subtitles is SubtitlePlan.Track) lastSubtitles = subtitles.option.key else subtitlesOff = subtitles
    }

    /**
     * A row of the sheet: applied to the player at once, kept for this play and, during an
     * episode of a TMDB show, saved for the show. The row's track is looked up again in the
     * player's tracks as they are now.
     */
    private fun onSheetAction(action: TrackSheet.Action) {
        val p = player ?: return
        // A burst of rewind and fast-forward taps seeks first.
        controlsPlayer?.flush()
        val now = p.currentTracks
        val options = TrackOptions.of(now)
        val shown = TrackChoices.shown(options, p.trackSelectionParameters)
        fun TrackOption.again() = options.firstOrNull { it.type == type && it.groupIndex == groupIndex && it.trackIndex == trackIndex && it.key == key }?.takeIf { it.selectable }
        when (action) {
            is TrackSheet.Action.Audio -> {
                val option = action.option.again() ?: return
                pick(TrackChoice(audio = AudioChoice.of(option)), shown)
                p.trackSelectionParameters = TrackSelection.withAudio(p.trackSelectionParameters, now, option, languages)
            }
            is TrackSheet.Action.Subtitles -> {
                val plan = when (val chosen = action.plan) {
                    is SubtitlePlan.Track -> SubtitlePlan.Track(chosen.option.again() ?: return)
                    else -> chosen
                }
                val choice = when (plan) {
                    SubtitlePlan.Off -> SubtitleChoice.Off
                    SubtitlePlan.ForcedOnly -> SubtitleChoice.ForcedOnly
                    is SubtitlePlan.Track -> SubtitleChoice.of(plan.option)
                }
                pick(TrackChoice(subtitles = choice), shown)
                p.trackSelectionParameters = TrackSelection.withSubtitles(p.trackSelectionParameters, now, plan)
                chose(plan)
            }
            TrackSheet.Action.Reset -> {
                picked = TrackChoice()
                showChoice = null
                show?.let { id -> saveShowChoice { it.clearTrackChoice(id) } }
                trackPlans.planAgain(p, options, picked, null, languages, subtitleRule)
            }
        }
        updateSheet(p, options)
    }

    /**
     * Keeps [change] for this play, with the other part as it is ([TrackChoices.afterPick]), so
     * the subtitle rule no longer decides; and for an episode of a TMDB show, for the show: only
     * the part picked, over what the show had, so a part never picked keeps following the
     * preferences and the rule in its later episodes.
     */
    private fun pick(change: TrackChoice, shown: TrackChoice) {
        picked = TrackChoices.afterPick(change, picked, shown)
        val id = show ?: return
        val remembered = TrackChoices.updated(showChoice, change)
        showChoice = remembered
        saveShowChoice { it.setTrackChoice(TrackChoices.toShow(id, remembered)) }
    }

    /** In the app's scope, so leaving doesn't cut a save short; after the saves before it, so the last pick wins. */
    private fun saveShowChoice(write: suspend (LibraryRepository) -> Unit) {
        val before = showSave
        showSave = container.appScope.launch {
            before?.join()
            saveOrLog("save the show's track choice") { write(container.library) }
        }
    }

    /**
     * Play and pause from a remote, or a wired headset's button, while a side panel shows; the controls that would take them
     * are down. The button plays or pauses as media3's controls take it full screen.
     */
    private fun panelMediaKey(event: KeyEvent): Boolean {
        val p = controlsPlayer ?: return false
        val handled = event.keyCode in listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK)
        if (handled && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY -> Util.handlePlayButtonAction(p)
                KeyEvent.KEYCODE_MEDIA_PAUSE -> Util.handlePauseButtonAction(p)
                else -> Util.handlePlayPauseButtonAction(p)
            }
        }
        return handled
    }

    /**
     * A remote's keys go to [RemoteKeys] first: with the controls hidden and only the picture
     * focused, OK plays or pauses and Left and Right seek (held, until let go, whatever takes the
     * focus meanwhile), as they do on the focused time bar and as ⏪ and ⏩ do, and some keys work
     * whatever has the focus. The rest go to PlayerView
     * first, as media3 asks, while it has the focus or nothing has: media keys and the D-pad reach
     * the player, and bring the controls back even when nothing is focused. Keys for anything else
     * focused (the top bar, the next-episode card, the skip button) go to it first, so OK presses
     * it also as the controls fade; any it leaves reach PlayerView.
     * While a side panel shows (the audio and subtitles sheet, File details), keys are its own, but
     * play and pause, and Info, which closes File details; while the "Playback stopped" panel shows,
     * they are all its own, or File details' over it. A key nothing takes is logged with
     * detailed logging, so a shared log says what a TV box's remote sends ([RemoteKeys.logsUnhandled]).
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        // Over HDMI-CEC a held key can come as presses, each soon after the up before: once enough come soon enough they
        // are a hold, the later ones its repeats, the key held to seek again ([SeekHold]).
        val repeat = if (down) seekHold.repeatCount(event.keyCode, event.repeatCount, event.source, event.eventTime) else event.repeatCount
        if (repeat != event.repeatCount) seekingKey = event.keyCode
        // The words of a key let go over HDMI-CEC wait to be sure it was: dropped as its run goes on, else said before this key acts.
        if (down && heldWords?.isActive == true) {
            heldWords?.cancel()
            if (!seekHold.goesOn) sayHeld()
        }
        val remote = if (RemoteKeys.appliesOn(formFactor)) RemoteKeys.action(event.keyCode, down, repeat, scene()) else RemoteKeys.Action.Pass
        // The key held to seek after this event: a press makes it so as it goes down (or a repeat, its press not having),
        // an up once it has ended the hold.
        val seeking = RemoteKeys.seekingKey(seekingKey, event.keyCode, down, repeat, remote)
        // A press that seeks is timed from; so is a repeat that made its key the one held to seek, from its press (#321).
        if (down && remote == RemoteKeys.Action.Seek && (repeat == 0 || seeking != seekingKey)) seekHold.pressed()
        if (down) seekingKey = seeking
        val handled = when {
            remote != RemoteKeys.Action.Pass -> {
                // Only downs act, but the up that ends a hold.
                if (down || remote == RemoteKeys.Action.SeekEnd) remoteKey(remote, event, repeat)
                true
            }
            failure != null -> super.dispatchKeyEvent(event)
            panel != null -> super.dispatchKeyEvent(event) || panelMediaKey(event)
            currentFocus == null || playerView.hasFocus() -> playerView.dispatchKeyEvent(event) || super.dispatchKeyEvent(event)
            else -> super.dispatchKeyEvent(event) || playerView.dispatchKeyEvent(event)
        }
        seekingKey = seeking
        if (!handled && down && event.repeatCount == 0 && RemoteKeys.logsUnhandled(event.keyCode)) log.debug("Key {} {} not handled", event.keyCode, KeyEvent.keyCodeToString(event.keyCode).removePrefix("KEYCODE_"))
        // A media key nothing here took goes no further: Android would hand it to this play's own session (L16).
        return handled || RemoteKeys.isMediaKey(event.keyCode)
    }

    /**
     * File details opened by a remote's Info, whatever has the focus: as they close it goes back there, the controls up
     * again if they were or it was on one of them. The card's Play now is given it back by the card's own requester,
     * also where the card's Stop had it: the card is kept as a whole, [cardView], not the button on it.
     * With only the picture focused it stays there, the controls hidden as they were.
     */
    private fun openDetailsByKey() {
        val focused = currentFocus
        val from = when {
            cardView.hasFocus() -> cardView
            focused === playerView -> null
            else -> focused
        }
        val onControls = from != null && from !== cardView && !skipView.hasFocus()
        openPanel(Panel.Details, from, controls = playerView.isControllerFullyVisible || onControls)
    }

    /** The player as a remote's key finds it. */
    private fun scene(): RemoteKeys.Scene {
        val focused = currentFocus
        return RemoteKeys.Scene(
            controlsFullyVisible = playerView.isControllerFullyVisible,
            focus = when {
                focused == null -> RemoteKeys.Focus.Nothing
                focused === playerView -> RemoteKeys.Focus.Picture
                cardView.hasFocus() || skipView.hasFocus() || promptView.hasFocus() || dipView.hasFocus() -> RemoteKeys.Focus.Overlay
                focused.id == androidx.media3.ui.R.id.exo_progress -> RemoteKeys.Focus.TimeBar
                else -> RemoteKeys.Focus.Controls
            },
            // Play on's list takes the keys as the sheet does.
            sheet = panel == Panel.Tracks || panel == Panel.PlayOn,
            failure = failure != null,
            cardHasNext = nextCard != null,
            seekingKey = seekingKey,
            details = panel == Panel.Details,
            switching = matching != null,
        )
    }

    /** Does what [RemoteKeys] says of a key going down, held or not: [repeat] is its repeat count, as [SeekHold] takes it. */
    private fun remoteKey(action: RemoteKeys.Action, event: KeyEvent, repeat: Int) {
        when (action) {
            // The controls come up focused on play/pause, where the next OK plays or pauses again.
            RemoteKeys.Action.TogglePlay -> {
                controlsPlayer?.let(Util::handlePlayPauseButtonAction)
                playerView.showController()
                playerView.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)?.requestFocus()
            }
            RemoteKeys.Action.Seek -> seekHeld(event, repeat)
            RemoteKeys.Action.SeekEnd -> endHold(event)
            RemoteKeys.Action.ShowControls -> playerView.showController()
            RemoteKeys.Action.ToggleDetails -> if (panel == Panel.Details) closePanel() else openDetailsByKey()
            RemoteKeys.Action.ToggleSubtitles -> toggleSubtitles()
            RemoteKeys.Action.NextAudio -> nextAudio()
            RemoteKeys.Action.PlayNext -> nextCard?.let { playNow(it.next) }
            // As the top bar's back arrow.
            RemoteKeys.Action.Leave -> leave()
            RemoteKeys.Action.PlayAtOnce -> matching?.complete(Unit)
            RemoteKeys.Action.Ignore, RemoteKeys.Action.Pass -> Unit
        }
    }

    /**
     * Left or Right with the controls hidden or on the focused time bar, or ⏪ or ⏩, down or held:
     * back or on as far as [ScrubRate] says for as long as the key has been held, through the
     * controls' burst. Nothing seeks while the key is down, however far apart its repeats come; its
     * up ([RemoteKeys.Action.SeekEnd]) has the one seek run a moment later, room for a correction
     * (L5). With the controls hidden they stay so and the label says how far and where to; with them
     * up (or fading) they stay up through the hold, the bar's thumb and time at the target, and the
     * label shows how far (L8). Either way TalkBack hears how far and where to, at the up ([endHold]).
     * The picture plays on at the old spot until the seek. After the words, the hold's speed cue
     * while the hold moves the target (W8). [repeat]: the event's repeat count, as [SeekHold] takes it.
     */
    private fun seekHeld(event: KeyEvent, repeat: Int) {
        val c = controlsPlayer ?: return
        val held = if (repeat == 0) 0 else heldMs(event)
        val step = seekHold.stepMs(held, c.duration)
        // Each of the key's events puts their timeout off.
        val up = controlsShown
        if (up) playerView.showController()
        val from = c.currentPosition
        val back = event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || event.keyCode == KeyEvent.KEYCODE_MEDIA_REWIND
        c.step(if (back) -step else step) { said ->
            // On a phone's side mark while it shows, whatever stepped (L13).
            if (sideMark.isVisible) return@step markSay(said, towards = !back, quiet = true)
            val target = said.withTarget(format(c.currentPosition))
            val shown = if (up) said else target
            // TalkBack hears where to at the up, with the controls up too: the time bar's own words change unannounced.
            heldSaid = SeekBurst.Said(shown.text, target.spoken)
            // None once the target stops at the start or in the file's last second (NxTvSeek4End): nothing moves there.
            say(shown.text, shown.spoken, cue = speedCue(ScrubRate.speed(held, c.duration), forward = !back).takeIf { c.currentPosition != from }, quiet = true, burst = true)
        }
        c.holdOpen()
    }

    /**
     * The up of a key held to seek: the burst's seek runs a moment later, room for a correction, longer after a fast hold
     * (L5). The label says where the hold went once more ([sayHeld]); over HDMI-CEC once the hold is sure to be over, as
     * the key pressed again in a moment may go on with it ([SeekHold]).
     */
    private fun endHold(event: KeyEvent) {
        controlsPlayer?.settle(ScrubRate.settleMs(heldMs(event)))
        val sureInMs = seekHold.up(event.keyCode, event.source, event.eventTime)
        if (sureInMs == 0L) {
            sayHeld()
        } else {
            heldWords = lifecycleScope.launch {
                delay(sureInMs)
                sayHeld()
            }
        }
    }

    /**
     * The words of a hold let go: the label says where it went once more, without the speed cue, and a live region again:
     * TalkBack hears it once, how far and where to (L8). So does the side mark, where it said the hold's words.
     */
    private fun sayHeld() {
        val mark = marked?.takeIf { it.quiet && sideMark.isVisible }
        if (mark != null) {
            marked = mark.copy(quiet = false)
            renderMark()
        } else {
            heldSaid?.let { say(it.text, it.spoken, burst = true) }
        }
        heldSaid = null
    }

    /**
     * How long the key of [event] has been held to seek: from its press as it reached the player ([seekHold]), also where
     * only its repeats seek (the focus came to the time bar as it was held, #321); from the last press the player saw
     * where another key is still taken for the one held to seek, its up lost, not by the event's downTime, each event's
     * own time over HDMI-CEC (CST-1).
     */
    private fun heldMs(event: KeyEvent): Long = if (event.keyCode == seekingKey) seekHold.heldMs(event.eventTime) else seekHold.sincePressMs(event.eventTime)

    /**
     * The label's cue for a hold at [speed] (W8), pointing on if [forward], else back: none in its first 2 s, two chevrons
     * lit while it doubles, three at the top.
     */
    private fun speedCue(speed: ScrubRate.Speed, forward: Boolean): SpeedCue? = when (speed) {
        ScrubRate.Speed.Fine -> null
        ScrubRate.Speed.Rising -> cue(lit = 2, forward)
        ScrubRate.Speed.Top -> cue(lit = 3, forward)
    }

    private fun cue(lit: Int, forward: Boolean) = speedCues.getOrPut(lit to forward) {
        AppColors().let { c -> SpeedCue(lit, forward, resources.displayMetrics.density, c.accent.toArgb(), c.markOutline.toArgb()) }
    }

    /**
     * The captions key: full subtitles off while they show, back to what showed before them
     * (forced ones stay, but after the sheet's Off), else back on as [RemoteKeys.captions] picks
     * them; said as the sheet's row says them (SDH, forced), and kept as a pick in the sheet is.
     */
    private fun toggleSubtitles() {
        val p = player?.takeUnless { it.currentTracks.isEmpty } ?: return
        val options = TrackOptions.of(p.currentTracks)
        val plan = RemoteKeys.captions(options, TrackSelection.shownSubtitles(options, p.trackSelectionParameters), lastSubtitles, subtitlesOff) ?: return say("No subtitles")
        onSheetAction(TrackSheet.Action.Subtitles(plan))
        say(if (plan is SubtitlePlan.Track) "Subtitles: ${plan.option.summary}" else "Subtitles off")
    }

    /**
     * The audio track key: the next audio this device plays ([RemoteKeys.nextAudio]); said as the
     * sheet's row says it, so a commentary isn't taken for the main audio, and kept as a pick in the
     * sheet is.
     */
    private fun nextAudio() {
        val p = player?.takeUnless { it.currentTracks.isEmpty } ?: return
        val next = RemoteKeys.nextAudio(TrackOptions.of(p.currentTracks)) ?: return
        if (!next.selected) onSheetAction(TrackSheet.Action.Audio(next))
        say("Audio: ${next.summary}")
    }

    /**
     * The time bar's own step, out of touch mode: as far as rewind and fast-forward, not media3's
     * twentieth of the file. A remote's Left and Right no longer reach it (they seek through the
     * burst, [RemoteKeys], L2), but its accessibility scroll actions take the step its keys would.
     * In touch mode it keeps that twentieth: TalkBack and Switch Access adjust the bar on a touch
     * screen by those actions, and 10 s would leave them no coarse way to seek.
     */
    private fun stepTimeBar() {
        if (playerView.isInTouchMode) timeBar.setKeyCountIncrement(TIME_BAR_STEPS) else timeBar.setKeyTimeIncrement(PlayerFactory.SEEK_FORWARD_MS)
    }

    /**
     * Back hides the controls first while they are fully up, but in touch mode, where it leaves as
     * before: a remote's user, whose OK brought them, has them go before leaving.
     */
    private fun updateBackHidesControls() {
        backHidesControls.isEnabled = controlsFull && !playerView.isInTouchMode && RemoteKeys.appliesOn(formFactor)
    }

    /**
     * Where a TV remote's D-pad goes from [focused], where that isn't by distance alone: with the
     * controls up, the skip button, the missing-parts prompt in its one-row form, or the slow-stream
     * banner's Try a smaller release, sits between play/pause and the time bar, where it shows. By
     * distance the D-pad would pass it going down from play/pause, and leave it down for the
     * settings and up for fast-forward. Up from the time
     * bar goes to play/pause, past it: on a TV's screen it sits right above the bar's end, so by
     * distance up would land on it. Up from the controls' centre buttons goes to the audio and
     * subtitles button (ⓘ is one Right from it), and down from the top bar back to play/pause.
     * While a side panel or the "Playback stopped" panel shows, the D-pad stays in it, in the side
     * panel over that one.
     */
    private fun nextFocus(focused: View?, direction: Int): View? {
        fun control(id: Int) = playerView.findViewById<View>(id)?.takeIf { it.isShown }
        val centre = playerView.findViewById<ViewGroup>(androidx.media3.ui.R.id.exo_center_controls)
        return when {
            panel != null -> panelView?.takeIf { focused == null || !it.hasFocus() } ?: focused
            failure != null -> failureView.takeIf { focused == null || !it.hasFocus() } ?: focused
            direction == View.FOCUS_UP && focused != null && focused.parent === centre && audioButton.isShown -> audioButton
            direction == View.FOCUS_DOWN && focused != null && (focused.parent === topBar || castView?.hasFocus() == true) -> control(androidx.media3.ui.R.id.exo_play_pause)
            else -> {
                // The one of them that shows: they never show together. The banner only with its button, the one thing on it to focus.
                val banner = dipView.takeIf { dipShown?.canSwitch == true }
                val slot = listOfNotNull(skipView, promptView, banner).firstOrNull { it.isVisible } ?: return null
                when {
                    slot.hasFocus() && direction == View.FOCUS_DOWN -> control(androidx.media3.ui.R.id.exo_progress)
                    slot.hasFocus() && direction == View.FOCUS_UP -> control(androidx.media3.ui.R.id.exo_play_pause)
                    focused?.id == androidx.media3.ui.R.id.exo_progress && direction == View.FOCUS_UP -> control(androidx.media3.ui.R.id.exo_play_pause)
                    focused?.id == androidx.media3.ui.R.id.exo_play_pause && direction == View.FOCUS_DOWN -> slot
                    else -> null
                }
            }
        }
    }

    /** What a burst of the controls' rewind and fast-forward taps says: on the side mark while it shows (L13, M7), else under the top bar. */
    private fun burstSaid(said: SeekBurst.Said) = if (sideMark.isVisible) markSay(said, towards = null) else say(said.text, said.spoken, burst = true)

    /** Rounded as media3 sizes its controls, so the card's margin and the time bar meet without overlap. */
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    override fun onStart() {
        super.onStart()
        // In the window again after the screen was off and on, or out of it while stopped.
        showPinned(isInPictureInPictureMode)
        // Play on TV's commands reach this player while it is started, and hear what it plays each second (L8, L34).
        // Before the setup, which may close the player at once: its finish() lets go of this too.
        container.playerInFront.started(handle)
        nowJob = lifecycleScope.launch {
            while (isActive) {
                container.playerInFront.said(handle, nowPlaying())
                delay(NOW_PLAYING_MS)
            }
        }
        startPlay()
    }

    /** Makes the player for the launch (or opened file) and starts it, once the settings it needs are read. */
    private fun startPlay() {
        val uri = launch?.uri ?: intent.getStringExtra(EXTRA_URI) ?: return finish()
        // Tied to the started state: onStop cancels it, so a player is never made after onStop
        // has run (nothing would release it and it would play in the background); finish() too.
        setupJob = lifecycleScope.launch {
            val playback = readPlayback(container)
            val languages = playback.effectiveAudioLanguages(Locale.getDefault().language)
            // This player's own position when it is made again; else where the release before failed; else the saved one.
            val start = resumePosition.takeIf { it != C.TIME_UNSET } ?: launch?.startPositionMs ?: savedPosition()
            // Once: after that the sheet keeps it in step, ahead of its saves.
            val remembered = if (showChoiceRead) showChoice else show?.let { id -> container.library.trackChoice(id)?.let(TrackChoices::fromShow) }
            val budget = budgetRead.await()
            // A debrid play's listing, put back in memory where this process doesn't hold it (a player made again after the
            // process went): its links and the pack's next episode are found by it (L171).
            launch?.let { container.debridListings.ensure(it) }
            // A read that ignores cancellation can return after onStop. Ask the job, not the lifecycle:
            // inside onStart it still reads CREATED, and after the next onStart a stale setup sees STARTED.
            if (!isActive) return@launch
            // Held since the launch came; held again after each onStart, which changes nothing (L234). Never by a setup
            // that outlived the player's close, which let go of it.
            holdLaunch()
            showChoice = remembered
            showChoiceRead = true
            this@PlayerActivity.budget = budget
            // Once: after that the button and pinches keep it, ahead of their saves.
            if (!zoomKnown) {
                zoom = VideoZoom.of(playback)
                zoomKnown = true
                showZoom()
            }
            applyZoom()
            this@PlayerActivity.languages = languages
            subtitleRule = playback.subtitlesWhenForeign
            // Before the player: Media3 picks the audio route and the Dolby Vision decoder as it is made, and keeps them.
            val playAtOnce = matchFrameRate(playback.matchFrameRate)
            // What a gap found playing is remembered against: the providers this player's streams start on.
            providers = container.engine.inUse
            fileDetails.origin(detailsOrigin(indexers))
            // The missing stretches its streams find and its extractors jump past: counted on the next tick (watchMissingParts).
            val gaps = PlayGaps { gapJumps += it }.also { playGaps = it }
            // Each player judges its own stream: a player made again starts afresh.
            val detector = DipDetector(SystemClock::elapsedRealtime, timings.dip).also { dips = it }
            val stalls = StallLog(SystemClock::elapsedRealtime)
            UsenetUris.parse(uri)?.let { target ->
                gapsListening = container.gapReports.listen(target.nzbId) { found -> if (found.isOf(target)) foundGaps += found.range }
            }
            // By the device's kind: only a TV's HDMI route changes under a play (a receiver switched on, the TV re-syncing).
            val p = PlayerFactory.create(this@PlayerActivity, dataSources(container), loadControlFor(budget), followAudioRoute = container.television, gaps = gaps)
            player = p
            // Before prepare(), so the decoder it starts is heard.
            fileDetails.follow(p)
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                .setPreferredAudioLanguages(*languages.toTypedArray())
                .build()
            p.addListener(object : Player.Listener {
                // Whether File details read where the file came from again, as this player first knew its tracks.
                private var originRead = false

                override fun onTracksChanged(tracks: Tracks) {
                    if (tracks.groups.isEmpty()) return
                    // Tracks chosen again load the file again from where it plays.
                    if (player === p) detector.reopened()
                    // The stream has the NZB in the store by now, also where it was only on disk as the setup read it (the
                    // process recreated): an NZB opened as a file is named by its title.
                    if (!originRead && player === p) {
                        originRead = true
                        fileDetails.origin(detailsOrigin(indexers))
                    }
                    // Video the player selected none of, as no decoder takes it, would leave the sound playing over a black
                    // screen. The probe catches most of it before playback; this is for where its renderers judge otherwise
                    // than the player's. Video the extractor drops (a codec it doesn't read) neither sees: the probe logs it.
                    VideoTracks.leftOut(tracks)?.let { found ->
                        if (player !== p || failure != null) return
                        val failed = PlaybackFailure.noVideo(found)
                        log.warn("Playback failed: {} (no video track selected)", failed.reason)
                        p.stop()
                        showFailure(failed)
                        return
                    }
                    val options = TrackOptions.of(tracks)
                    trackPlans.onTracksChanged(p, options, picked, showChoice, languages, subtitleRule)
                    updateSheet(p, options)
                }

                override fun onPlaybackStateChanged(state: Int) {
                    // The measuring line for a stall while playing (L238), either source's.
                    if (player === p) stalls.stateChanged(state, p.playWhenReady)?.let { log.info(it) }
                    if (state == Player.STATE_ENDED) saveEnd(p.duration, p.duration)
                    if (nextCard != null) updateControls()
                    keepAwake()
                    updatePictureInPicture()
                    // Saved as watched first: with nothing to move on to, the player leaves here.
                    if (state == Player.STATE_ENDED && player === p) followTheEnd()
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (nextCard != null) updateControls()
                    keepAwake()
                    updatePictureInPicture()
                    // Played on in the window, by its own Play or a headset's, while another play is on its way for it.
                    if (playWhenReady) dropTheNewPlay()
                }

                // The picture-in-picture window takes the picture's shape.
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (player === p) updatePictureInPicture()
                }

                // Loading's starts and stops as they come: a stop between two ticks breaks the detector's window.
                override fun onIsLoadingChanged(isLoading: Boolean) {
                    if (player === p) detector.loading(isLoading)
                }

                // A seek opens the stream again, and its buffering is no stall.
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    if (player === p && reason == Player.DISCONTINUITY_REASON_SEEK) {
                        detector.reopened()
                        stalls.seeked()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    val causes = generateSequence<Throwable>(error) { it.cause }.take(16).toList()
                    val usenet = causes.firstNotNullOfOrNull { it as? UsenetDataSourceException }?.error
                    val link = causes.firstNotNullOfOrNull { it as? LinkUnavailable }?.failure
                    val failed = PlaybackFailure.of(error, error.errorCode, providersLeftOut = container.engine.leftOut.value.isNotEmpty())
                    log.warn("Playback failed: {} ({})", failed.reason, link ?: usenet ?: error.errorCodeName, error)
                    if (player !== p) return
                    // Missing on the providers fetched from: remembered while one is in use, so a play skips the release without a grab.
                    if (usenet is UsenetError.MissingArticle) launch?.let { container.playback.rememberMissing(it, providers) }
                    // A debrid play's file no longer to be had: remembered against the account that played it, as a play's own
                    // failures are (L174); Try another release leaves out that account's releases where it stops them.
                    val stops = link?.let { l -> launch?.let { container.playback.serviceFailed(it, l) } }.orEmpty()
                    showFailure(failed, stops)
                }

                // As media3 opens its shutter: from here the picture is sized as chosen.
                override fun onRenderedFirstFrame() {
                    if (player !== p) return
                    pictureShown = true
                    applyZoom()
                    detector.firstFrame()
                    updatePictureInPicture()
                }
            })
            // A load Media3 opens again after it failed (L238): by the class of what failed, never its words, which can name
            // the file, its link or its host.
            p.addAnalyticsListener(object : AnalyticsListener {
                override fun onLoadError(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData, error: IOException, wasCanceled: Boolean) {
                    if (player === p) stalls.loadFailed(error, retried = !wasCanceled)?.let { log.info(it) }
                }

                // Renderers let go and taken again: tracks chosen again (a TV's audio route changed under the play) load the
                // file again from where it plays, saying no seek, and the buffering that brings is no stall (FSL-1).
                override fun onAudioDisabled(eventTime: AnalyticsListener.EventTime, decoderCounters: DecoderCounters) {
                    if (player === p) stalls.reloaded()
                }

                override fun onVideoDisabled(eventTime: AnalyticsListener.EventTime, decoderCounters: DecoderCounters) {
                    if (player === p) stalls.reloaded()
                }
            })
            // By its watch key, not its URI, which names the file: the session says both (M12).
            val said = SessionMetadata.of(launch, getString(R.string.app_name))
            // A phone's Pause or seek that came while the setup ran (its frame-rate switch on a TV) is taken now (L8); else it
            // plays, or after a stop waits paused where it was (W106), unless OK during the switch asked it to play.
            p.setMediaItem(MediaItem.Builder().setUri(uri).setMediaId(said.mediaId).setMediaMetadata(said.metadata()).build(), held.startAt(start.takeIf { it > 0 } ?: 0))
            if (start > 0 && resumePosition == C.TIME_UNSET) Toast.makeText(this@PlayerActivity, "Resuming at ${format(start)}", Toast.LENGTH_SHORT).show()
            p.playWhenReady = held.playWhenReady(otherwise = playsWhenMade || playAtOnce)
            held.clear()
            p.prepare()
            // The label says how far a burst of rewind and fast-forward taps goes, or that it is at the start or the end: the
            // side mark, while a double-tap's shows (L13).
            val shown = ControlsPlayer(p, onBurst = ::burstSaid).also { controlsPlayer = it }
            playerView.player = shown
            // The one session: let go before the next player's is made, which would have the same id ([endPlay]).
            val outside = SessionPlayer(shown, leave = ::leave).apply {
                pinned = this@PlayerActivity.pinned
                failed = failure != null
            }
            sessionPlayer = outside
            session = MediaSession.Builder(this@PlayerActivity, outside).setCallback(outside.callback()).build()
            // Media3's own is off (PlayerFactory): this player's, by the device's kind, pauses through the controls.
            noisy = BecomingNoisy(this@PlayerActivity, tv = container.television, outputs = { audioOutputs(this@PlayerActivity) }) { becomingNoisy(shown, it) }
                .apply { start() }
            progressJob = lifecycleScope.launch {
                while (isActive) {
                    delay(15_000)
                    if (p.isPlaying) saveProgress(p.currentPosition, p.duration)
                }
            }
            val episode = AutoAdvance.episodeOf(launch)
            // Both go by the position the controls show: where a burst of rewind and fast-forward taps goes, at once.
            segmentsJob = lifecycleScope.launch { findSegments(shown, episode) }
            gapsJob = lifecycleScope.launch { watchMissingParts(shown, uri) }
            dipJob = lifecycleScope.launch { watchDips(p, detector, stalls, uri, playback.maxBitrate?.let { (it / 1_000_000).toInt() }) }
            nextLookup = if (episode != null) EndOfPlay.Next.Pending else EndOfPlay.Next.None
            episode?.let { playing ->
                nextJob = lifecycleScope.launch { offerNextEpisode(shown, playing, playback.autoPlayNext) }
                previousJob = lifecycleScope.launch { offerPreviousEpisode(shown, playing) }
            }
            // Last, as only File details' Indexer row needs them: the player doesn't wait for this read.
            indexers = readIndexers(container)
            if (player === p) fileDetails.origin(detailsOrigin(indexers))
        }
    }

    /**
     * "Match the TV to the video's frame rate", before the player is made (TV-7): on a TV with the setting [on], the
     * window asks the display for the mode of its size that shows the launch's frame rate (the track probe's) without
     * pulldown ([FrameRatePlanner]), logged with the display's facts. Where the display runs it already, the window holds
     * it, and nothing else takes it away mid-film; where the TV must switch, the play waits on black behind the moment's
     * line ([switchMode]). A play that leaves the mode as it is (unknown rate, a clip, no fitting mode) gives up what the
     * window asked for (a play started here in place of one that held a mode). Off, or not on a TV, nothing is asked of
     * the window or the display: the play is as before the setting, Media3's own seamless matching. Whether OK was
     * pressed during the switch: the user's Play, also for a player made again after a stop, which would wait (W106).
     */
    private suspend fun matchFrameRate(on: Boolean): Boolean {
        if (!on || !isTelevision(container)) {
            log.debug(if (on) "Frame rate matching is off: not a TV" else "Frame rate matching is off")
            leaveMode()
            return false
        }
        val video = launch?.let(videoOf)
        val screen = display.read()
        val plan = FrameRatePlanner.plan(on = true, tv = true, video?.fps, video?.durationMs, screen)
        log.info(FrameRatePlanner.line(video?.fps, screen, plan))
        when (plan) {
            is FrameRatePlan.Leave -> leaveMode()
            is FrameRatePlan.Hold -> {
                heldMode = plan.mode
                if (plan.wait) return switchMode(plan.mode, video?.dolbyVision == true)
                if (display.preferredModeId != plan.mode.id) display.preferredModeId = plan.mode.id
            }
        }
        return false
    }

    /**
     * The TV switches to [mode]: "Matching the TV to 23.976 Hz…" on black until it has and has settled, with the HDMI
     * audio and, for a [dolbyVision] file, Dolby Vision back ([DisplayModeSwitcher]); OK plays at once, and Back (finish)
     * or Home (onStop) cancels the wait with the rest of the setup. How it went is logged. Whether OK was pressed.
     */
    private suspend fun switchMode(mode: ScreenMode, dolbyVision: Boolean): Boolean {
        val playNow = CompletableDeferred<Unit>().also { matching = it }
        val rate = formatRate(mode.refreshRate.toDouble())
        showMoment("Matching the TV to $rate Hz…", "Matching the TV to $rate hertz")
        backLeavesMatching.isEnabled = true
        try {
            // The TV's HDMI re-syncs as it switches: the route it drops meanwhile isn't taken for lost (the ranking's).
            log.info(container.device.whileSwitching { DisplayModeSwitcher(display, timings.switching).switchTo(mode, dolbyVision, playNow) })
            return playNow.isCompleted
        } finally {
            matching = null
            backLeavesMatching.isEnabled = false
            hideMoment()
        }
    }

    /** The window asks for no mode: the display goes back to its own once no other window asks for one. */
    private fun leaveMode() {
        heldMode = null
        if (display.preferredModeId != 0) display.preferredModeId = 0
    }

    /**
     * The TV's mode handed over by the play before (auto-advance, Previous/Next, Try another), which the play sheet's
     * window holds meanwhile ([DisplayModeHold]): asked for at once, before the first frame, so the display keeps it as
     * this window replaces the sheet's, while the display still runs it. The setup's own plan then keeps it (the same
     * rate: no switch, no wait), or switches once from it to this play's (another rate), or gives it up (a rate not known).
     */
    private fun keepHandedOverMode() {
        val hold = container.displayHold
        if (hold.held.value == null || launch == null || !isTelevision(container)) return
        val mode = hold.keptIn(display.read()) ?: return
        heldMode = mode
        display.preferredModeId = mode.id
    }

    /**
     * Hands the TV's mode to the play that follows, when this window asks for one ([DisplayModeHold]): the play sheet's
     * window keeps asking for it while it finds that play. Done before that play is asked for, so that one ending at once
     * finds the hold to let go. Whether it was handed over.
     */
    private fun handOverMode(): Boolean {
        val mode = heldMode?.takeIf { display.preferredModeId == it.id } ?: return false
        container.displayHold.handOver(mode)
        return true
    }

    /** Closes; after a hand-over ([handedOver]) without the transition, during which a window's mode counts for nothing. */
    private fun finishHandingOver(handedOver: Boolean) {
        if (handedOver && Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        finish()
        @Suppress("DEPRECATION")
        if (handedOver && Build.VERSION.SDK_INT < 34) overridePendingTransition(0, 0)
    }

    // Both as the platform's, noting a close without the transition ([closesWithoutTransition]).
    @RequiresApi(34)
    override fun overrideActivityTransition(overrideType: Int, enterAnim: Int, exitAnim: Int) {
        super.overrideActivityTransition(overrideType, enterAnim, exitAnim)
        if (overrideType == OVERRIDE_TRANSITION_CLOSE) closesWithoutTransition = enterAnim == 0 && exitAnim == 0
    }

    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun overridePendingTransition(enterAnim: Int, exitAnim: Int) {
        super.overridePendingTransition(enterAnim, exitAnim)
        if (isFinishing) closesWithoutTransition = enterAnim == 0 && exitAnim == 0
    }

    /**
     * Finds the file's intro, recap and credits: its chapters, and for a TMDB [episode]
     * TheIntroDB's times (asked once the file is open, with its length, only if the user turned
     * that on). Then shows the skip button while playback is in an intro or recap; the credits are
     * for [offerNextEpisode]. Nothing shows for a file without any.
     */
    private suspend fun findSegments(p: ControlsPlayer, episode: AutoAdvance.Episode?) {
        // The chapters come with the tracks, as the file opens; and its length, which picks
        // TheIntroDB's version of the episode.
        while (p.duration <= 0 || p.currentTracks.isEmpty) delay(TICK_MS)
        val online = episode?.let { lookUpSegments(container, it, p.duration) }.orEmpty()
        val accepted = Segments.accept(Segments.fromChapters(readChapters(p.currentTracks)), online, p.duration) { s, why ->
            log.debug("Skip segment dropped, {}: {}", why, s)
        }
        log.debug("Skip segments: {}", Segments.describe(accepted))
        segments = accepted
        if (accepted.none { it.kind == SegmentKind.Intro || it.kind == SegmentKind.Recap }) return
        val skip = SkipButton(SystemClock::elapsedRealtime, timings.skipShowForMs) { p.seekTo(it) }.also { skipButton = it }
        while (true) {
            // No focus for it while a side panel shows, nor while a burst's seek waits: a held Left or
            // Right, or quick presses, would come to it as their target came into the intro. Gone
            // while the "Playback stopped" panel shows.
            val shown = skip.update(p.currentPosition, accepted, controlsShown, cardShown = nextCard != null)?.let { if (panel != null || p.seeking) it.copy(takeFocus = false) else it }
            showSkip(shown.takeIf { failure == null && windowScene().overlays })
            delay(SKIP_TICK_MS)
        }
    }

    /**
     * Shows or hides the skip button. As it hides, a TV remote's focus goes back to play/pause if
     * the controls are up; otherwise Android hands it to PlayerView (or nothing), and either way a
     * D-pad key brings the controls.
     */
    private fun showSkip(shown: SkipButton.Shown?) {
        val changed = (shown != null) != (skipShown != null)
        val hadFocus = skipView.hasFocus()
        skipShown = shown
        if (!changed) return
        backHidesSkip.isEnabled = shown != null
        skipView.isVisible = shown != null
        // The prompt and the banner give way at once, not on their next tick: they share a place.
        if (shown != null) {
            hidePrompt()
            hideDip()
        }
        if (shown == null && hadFocus && controlsShown) playerView.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)?.requestFocus()
    }

    /**
     * The play's missing parts, on every tick as the skip button's ([MissingParts]): each stretch the streams found since
     * the last, placed in the film by the file's index (else by its share of the file, as the bitrate goes), and each jump
     * the extractors made; then the notice, which the moment label says as playback gets to where a jump went on from,
     * and the prompt. It waits for the slot while "Playback stopped", the next-episode card or the skip button holds it,
     * and its time doesn't run then, nor under a side panel. Logged as it asks, is answered or goes unanswered.
     */
    private suspend fun watchMissingParts(p: ControlsPlayer, uri: String) {
        val size = PlayUris.parse(uri)?.size ?: launch?.release?.fileSize
        /** Where byte [position] is in the film, from the file's index or, without one that can say, its share of the file. */
        fun timeAt(position: Long): Long? = playGaps?.timeMsAt(position) ?: size?.takeIf { it > 0 && p.duration > 0 }?.let { (position.toDouble() / it * p.duration).toLong() }
        val unplaced = mutableListOf<LongRange>()
        while (true) {
            generateSequence { foundGaps.poll() }.forEach { unplaced += it }
            unplaced.removeAll { range ->
                val at = timeAt(range.first) ?: return@removeAll false
                if (missingParts.found(range, at)) log.debug("Missing part at bytes {}-{}, {} into the film: {} so far", range.first, range.last, format(at), missingParts.count)
                true
            }
            generateSequence { gapJumps.poll() }.forEach { jump ->
                jumps += jump
                // The stream opened again at the point it went on from.
                dips?.reopened()
                (jump.toMs ?: timeAt(jump.bytes.last + 1))?.let { missingParts.jumped(jump.bytes, it) }
            }
            val l = launch
            val waiting = failure != null || nextCard != null || skipShown != null || windowScene().unseen
            val state = missingParts.update(p.currentPosition, waiting, tryAnother = l != null && container.playback.canTryAnother(l))
            if (state.notice) say(MISSING_PART_SKIPPED, forMs = NOTICE_MS)
            if (state.timedOut) log.info("Missing parts: the prompt went unanswered; watching on ({} so far)", missingParts.count)
            showPrompt(state.prompt)
            delay(SKIP_TICK_MS)
        }
    }

    /**
     * Shows the missing-parts prompt the count asks with ([asking]), or hides it. One overlay at a time (L6): it gives way
     * to "Playback stopped", the next-episode card and the skip button, and waits; under a side panel it stays, without
     * the focus. Keep watching takes a remote's focus with the controls hidden, as the skip button does (W6), but not while
     * a burst's seek waits; as it hides with the focus and the controls up, the focus goes back to play/pause.
     */
    private fun showPrompt(prompt: MissingParts.Prompt?) {
        if (prompt != asking) {
            if (prompt != null && asking?.count != prompt.count) {
                log.info(
                    if (prompt.tryAnother) "Missing parts: {} so far; asking whether to try another release" else "Missing parts: {} so far; no other release to try, saying so",
                    prompt.count,
                )
            }
            asking = prompt
        }
        val shown = prompt?.takeIf { failure == null && nextCard == null && skipShown == null && windowScene().overlays }
            ?.let { PromptShown(it, takeFocus = !controlsShown && panel == null && controlsPlayer?.seeking != true) }
        val changed = (shown != null) != (promptShown != null)
        val hadFocus = promptView.hasFocus()
        promptShown = shown
        backKeepsWatching.isEnabled = shown?.prompt?.tryAnother == true
        if (!changed) return
        // The banner gives way at once: they share a place.
        if (shown != null) hideDip()
        promptView.isVisible = shown != null
        if (shown == null && hadFocus && controlsShown) playerView.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)?.requestFocus()
    }

    /** Hides the prompt at once, as another overlay takes the slot or the player goes: it still asks, and shows again on its next tick if it may. */
    private fun hidePrompt() {
        promptShown = null
        promptView.isVisible = false
        backKeepsWatching.isEnabled = false
    }

    /**
     * The slow-stream banner ([DipDetector], L7), on every tick: whether the player plays, or stalls for want of bytes, its
     * buffer ahead and where it is, read from the player itself (the controls' position is a burst's target), and the
     * bytes the streams have handed it ([AppContainer.streamTraffic]), against the file's bitrate: its size over its
     * length, as File details says it. One overlay at a time (L6): it waits while "Playback stopped", the next-episode
     * card, the skip button or the missing-parts prompt has the slot, and under a side panel it stays, without the focus;
     * the detector doesn't count either as shown. The hint goes by the maximum bitrate set as the play began ([maxMbps],
     * null: not set). Logged as a dip begins and ends. The buffer ahead goes to [stalls] too, for its stall line.
     */
    private suspend fun watchDips(p: ExoPlayer, detector: DipDetector, stalls: StallLog, uri: String, maxMbps: Int?) {
        val size = PlayUris.parse(uri)?.size ?: launch?.release?.fileSize
        var logged: DipDetector.Dip? = null
        while (true) {
            val duration = p.duration.takeIf { it > 0 } ?: launch?.video?.durationMs
            val bitrate = size?.takeIf { it > 0 }?.let { s -> duration?.takeIf { it > 0 }?.let { s * 8_000 / it } }
            val state = p.playbackState
            val playing = failure == null && p.playWhenReady && p.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
                (state == Player.STATE_READY || state == Player.STATE_BUFFERING)
            val held = slotHeld()
            val sample = DipDetector.Sample(
                playing, stalled = state == Player.STATE_BUFFERING, p.totalBufferedDuration, p.currentPosition,
                container.streamTraffic.snapshot().totalBytes, bitrate, covered = held || windowScene().unseen,
            )
            val dip = detector.tick(sample)
            // What a stall's line says the play had buffered ahead before it.
            stalls.buffered(sample.bufferedAheadMs)
            if (dip != logged) {
                if (dip != null) {
                    log.info("Slow stream: {} Mbps read while playing, where the film needs {} Mbps", mbps(dip.rateBps), mbps(dip.bitrateBps))
                } else {
                    log.info("Slow stream: over")
                }
                logged = dip
            }
            val shown = dip?.takeIf { !held && windowScene().overlays }
            // Asked once as it shows: a smaller release known to be missing is none to offer.
            val canSwitch = shown?.let { d ->
                dipShown?.takeIf { it.dip == d }?.canSwitch ?: launch?.let { container.playback.canTrySmaller(it, d.rateBps, d.bitrateBps) }
            } == true
            showDip(shown, maxMbps, canSwitch)
            delay(DIP_TICK_MS)
        }
    }

    /**
     * Android's "becoming noisy" for [shown]'s player, as its watcher judged it ([NoisyPolicy]): a phone's headphones
     * unplugged, or on a TV only headphones, never the HDMI re-syncing. Logged by the output's kind, and paused through
     * the controls' player, which runs a waiting burst of rewind and fast-forward taps first; only while the play would
     * play.
     */
    private fun becomingNoisy(shown: ControlsPlayer, decision: NoisyPolicy.Decision) {
        if (controlsPlayer !== shown) return
        if (!shown.playWhenReady) {
            log.debug("Audio output going away: not playing")
            return
        }
        log.info(decision.line)
        if (decision.pauses) shown.pause()
    }

    /** Whether "Playback stopped", the next-episode card, the skip button or the missing-parts prompt has the bottom-end slot, which the banner waits for (L6). */
    private fun slotHeld() = failure != null || nextCard != null || skipShown != null || asking != null || promptShown != null

    /**
     * Shows the slow-stream banner for [dip], or hides it, with Try a smaller release when [canSwitch]. It never takes the
     * focus; as it hides with the focus (a remote reached its button from the controls) and the controls up, the focus
     * goes back to play/pause.
     */
    private fun showDip(dip: DipDetector.Dip?, maxMbps: Int?, canSwitch: Boolean) {
        val shown = dip?.let { d -> dipShown?.takeIf { it.dip == d } ?: DipShown(d, SlowStream.shown(d, maxMbps), canSwitch) }
        val changed = (shown != null) != (dipShown != null)
        val hadFocus = dipView.hasFocus()
        dipShown = shown
        if (!changed) return
        dipView.isVisible = shown != null
        if (shown == null && hadFocus && controlsShown) playerView.findViewById<View>(androidx.media3.ui.R.id.exo_play_pause)?.requestFocus()
    }

    /** Hides the banner at once, as another overlay takes the slot or the player goes: it shows again on its next tick if it may. */
    private fun hideDip() {
        dipShown = null
        dipView.isVisible = false
    }

    /**
     * Try a smaller release on the slow-stream banner (L8): saves where this one is, keeping it as the release that played
     * (it still plays, and is the way back if nothing smaller does), closes, and has the coordinator play the request's
     * smaller releases from there, those the stream carries first; the play sheet shows how that goes.
     */
    private fun trySmaller() {
        val l = launch ?: return
        val dip = dipShown?.dip ?: return
        if (isFinishing) return
        val position = controlsPlayer?.currentPosition ?: resumePosition.takeIf { it != C.TIME_UNSET } ?: 0
        log.info("Slow stream: trying a smaller release from {}", format(position))
        player?.let { saveProgress(position, it.duration) }
        val handedOver = handOverMode()
        container.playback.trySmaller(l, position, dip.rateBps, dip.bitrateBps)
        finishHandingOver(handedOver)
    }

    /** Keep watching, or Back, on the missing-parts prompt: it hides, and asks again at the next count ([MissingParts.keepWatching]). */
    private fun keepWatching() {
        missingParts.keepWatching()
        log.info("Missing parts: keep watching ({} so far)", missingParts.count)
        showPrompt(null)
    }

    /**
     * Try another release on the missing-parts prompt (L10): saves where this one is, keeping it as the release that
     * played (it still plays), closes, and has the coordinator play the next release from there; the play sheet shows how
     * that goes. This one is remembered as missing only once another plays, and played again from here if none does (W3).
     */
    private fun tryAnotherForMissingParts() {
        val l = launch ?: return
        if (isFinishing) return
        val position = controlsPlayer?.currentPosition ?: resumePosition.takeIf { it != C.TIME_UNSET } ?: 0
        log.info("Missing parts: trying another release from {} ({} so far)", format(position), missingParts.count)
        player?.let { saveProgress(position, it.duration) }
        // Saved once with it; the saves as the player closes leave the release remembered as they find it, as the play
        // that follows decides: forgotten once another plays, kept if none does.
        releaseFailed = true
        val handedOver = handOverMode()
        container.playback.tryAnotherForMissingParts(l, position, providers)
        finishHandingOver(handedOver)
    }

    /**
     * Where the moment label would touch a full-size overlay in the bottom-end slot (the missing-parts prompt, the
     * slow-stream banner, the next-episode card or "Playback stopped" on a short landscape phone), it centres on the room
     * beside the overlay instead, no wider than that room (L6, [MomentLabelPlace]); elsewhere, on the screen. Worked out
     * as the player's views are laid out.
     */
    private fun centreMomentLabel() {
        fun View.box() = MomentLabelPlace.Box(left, top, right, bottom)
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val overlay = listOf(promptView, dipView, cardView, failureView).firstOrNull { it.isVisible && it.width > 0 }?.takeIf { momentLabel.isVisible }
            // Judged as wide as its words, which a width it was cut to before doesn't change: centred on the screen, it
            // keeps its centre.
            val label = momentLabel.run {
                val words = ceil(Layout.getDesiredWidth(text, paint)).toInt() + compoundPaddingLeft + compoundPaddingRight
                val centre = (left + right) / 2
                MomentLabelPlace.Box(centre - words / 2, top, centre + words - words / 2, bottom)
            }
            val place = MomentLabelPlace.place(label, overlay?.box(), momentLabelSafeLeft, dp(8))
            momentLabel.translationX = place.shift
            // Laid out again once, in this pass, at the width it is cut to; the same width then, so no more.
            val maxWidth = place.maxWidth ?: Int.MAX_VALUE
            if (momentLabel.maxWidth != maxWidth) momentLabel.maxWidth = maxWidth
        }
    }

    private fun pressSkip() {
        // A burst of rewind and fast-forward taps seeks first.
        controlsPlayer?.flush()
        if (skipButton?.press() == true) showSkip(null)
    }

    /**
     * Offers the episode after [playing] near the end of this one: looks it up once per setup, then
     * feeds [AutoAdvance] what the player reports until it says to move on. The controls' Next plays
     * it at once, as Play now on the card does. Nothing shows, and Next stays disabled, when there
     * is no next episode (the end of what has aired, no TMDB key, TMDB failing): at the end, which
     * may have come while it was looked up, the player leaves ([followTheEnd]).
     */
    private suspend fun offerNextEpisode(p: ControlsPlayer, playing: AutoAdvance.Episode, autoPlayNext: Boolean) {
        val next = nextEpisode(container, playing)
        // A lookup deaf to cancellation can answer after onStop, for a setup that has gone.
        if (!currentCoroutineContext().isActive) return
        if (next == null) {
            nextLookup = EndOfPlay.Next.None
            return followTheEnd()
        }
        nextLookup = EndOfPlay.Next.Offered
        p.playNext = { switchTo(next) }
        val showStill = container.settings.metadata.first().showArtwork
        val machine = AutoAdvance(autoPlayNext, SystemClock::elapsedRealtime, timings.cardBeforeEndMs, timings.countdownMs).also { if (cardStopped) it.stop() }
        autoAdvance = machine
        while (true) {
            // A release found too long ago to be handed on by the end of the countdown counts as no
            // search: the card doesn't call it ready, and searches again as it shows or while it
            // shows (once at most while it waits on the user, see AutoAdvance).
            val status = prefetch?.takeIf { it.first == next }?.second?.value
                ?.takeUnless { it is Prefetch.Ready && !container.playback.holdsPrefetched(next, timings.countdownMs) }
            // The last credits, if a file has several (a scene between them).
            val credits = segments?.filter { it.kind == SegmentKind.Credits }?.maxOfOrNull { it.startMs }
            val step = machine.update(
                AutoAdvance.Playback(
                    p.currentPosition,
                    p.duration,
                    paused = !p.playWhenReady,
                    ended = p.playbackState == Player.STATE_ENDED,
                    creditsStartMs = credits,
                    stalled = p.playWhenReady && !p.isPlaying,
                    // Under a side panel or in the window the card's Stop can't be reached (W1), and it gives
                    // way to the "Playback stopped" panel: its countdown waits.
                    hidden = windowScene().unseen || failure != null,
                ),
                status,
            )
            if (step.startPrefetch) prefetch = next to prefetchNext(container, next, launch?.release)
            // Play now takes the focus once no burst's seek waits, as the skip button does, and not under a side panel,
            // which keeps it.
            showCard(step.card?.takeIf { failure == null }?.let { NextCard(next, it, showStill, takeFocus = !p.seeking && panel == null) })
            if (step.advance) return switchTo(next)
            delay(if (step.card != null) CARD_TICK_MS else TICK_MS)
        }
    }

    /**
     * Looks up the episode before [playing] once per setup, for the controls' Previous within the
     * episode's first seconds. Without one (a show's first episode, no TMDB key, TMDB failing),
     * Previous only starts this one again.
     */
    private suspend fun offerPreviousEpisode(p: ControlsPlayer, playing: AutoAdvance.Episode) {
        val previous = previousEpisode(container, playing) ?: return
        p.playPrevious = { switchTo(previous) }
    }

    private fun showCard(card: NextCard?) {
        val showing = card != null
        val changed = showing != (nextCard != null)
        nextCard = card
        if (changed) {
            // The skip button and the prompt give way at once, not on their next update: they share a place and Back.
            if (showing) {
                showSkip(null)
                hidePrompt()
                hideDip()
            }
            backStopsCard.isEnabled = showing
            // Not in the window, where the machine still says what it would show (M3).
            cardView.isVisible = showing && windowScene().overlays
            updateControls(cardGone = !showing)
        }
    }

    /**
     * media3 brings the controls up by itself when playback pauses or ends, and keeps them up,
     * moving a TV remote's focus to play/pause as they appear. While the card shows they must not
     * come up as the file ends, which would take that focus from Play now; a pause still keeps them
     * up. media3 reads the flag only on its own player events, so controls already up are shown
     * again under it, and as the card goes the controls come back if media3 would have shown them.
     */
    private fun updateControls(cardGone: Boolean = false) {
        val p = player
        val paused = p != null && !p.playWhenReady
        val ended = p?.playbackState == Player.STATE_ENDED
        playerView.controllerAutoShow = nextCard == null || paused && !ended
        val leaving = isFinishing || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        if (!leaving && (controlsShown || cardGone && (paused || ended))) playerView.showController()
    }

    /**
     * The screen kept on only while this play runs or is about to, not with the "Playback stopped" panel up ([KeepAwake]);
     * the stream-cost line in the log runs just as long, while its stream plays.
     */
    private fun keepAwake() {
        val awake = player?.let { p -> KeepAwake.of(p.playWhenReady, p.playbackState, failed = failure != null) } == true
        playerView.keepScreenOn = awake
        container.streamCost.playing(awake)
    }

    /** Whether the picture-in-picture window opens as the user leaves the play as it is now, and how ([PictureInPicturePolicy]). */
    private fun arming(): PictureInPicturePolicy.Arming {
        val scene = player?.let { p ->
            PictureInPicturePolicy.Scene(
                p.playWhenReady, p.playbackState, pictureShown, failed = failure != null, leaving = leaving || isFinishing || switched, matching = matching != null,
            )
        }
        return PictureInPicturePolicy.arming(pictureInPicture, pictureInPictureSdk(), scene)
    }

    /**
     * The picture-in-picture window as the player now has it (L5): the picture's shape ([PipShape.aspect], the last one
     * known until the next player knows its own), where it opens from, and whether it opens as the user leaves. Where it
     * opens from is the picture full screen: kept as it was while in the window, which Android closes back to it.
     */
    private fun windowParams(): WindowParams {
        val aspect = player?.let(videoSizeOf)?.let { PipShape.aspect(it.width, it.height, it.pixelWidthHeightRatio) } ?: windowTold?.aspect
        val hint = if (pinned || isInPictureInPictureMode) windowTold?.hint else PipShape.hint(areaInWindow(contentFrame), areaInWindow(playerView), aspect)
        return WindowParams(aspect, hint, arming().autoEnter, windowActions())
    }

    /** The window's own buttons for the play as it is now ([PipActions]); before the player is made, those of the one to come. */
    private fun windowActions(): List<PipActions.Action> {
        val p = player
        return PipActions.of(playWhenReady = p?.playWhenReady ?: held.playWhenReady(otherwise = playsWhenMade), ended = p?.playbackState == Player.STATE_ENDED, failed = failure != null)
    }

    /** [params] as Android takes them, its buttons made ([windowButton]); the player keeps them as told. */
    private fun tell(params: WindowParams): PictureInPictureParams {
        windowTold = params
        windowButtons = params.buttons.map(::windowButton)
        return params.platform(windowButtons, hintFrom = hintOrigin())
    }

    /**
     * Where Android measures the hint the player tells it from: Android 14 (and later updates of 13, which this can't tell
     * apart) takes it as from the app's window and adds the window's place on the screen, the small window's while in it,
     * where the hint is the picture full screen ([windowParams]). Told as it is, Android would keep it moved down to the
     * small window's corner, off the screen it opens out to, and open the window out without it. Earlier Androids keep it
     * as told.
     */
    private fun hintOrigin(): Point =
        if (Build.VERSION.SDK_INT >= 34 && (pinned || isInPictureInPictureMode)) windowManager.currentWindowMetrics.bounds.let { Point(it.left, it.top) } else Point(0, 0)

    /**
     * One of the window's buttons (PipWindowMenu): its icon, its title, which TalkBack reads as its description too, and the
     * broadcast it sends to [windowPresses]; greyed, it isn't [PipActions.Action.enabled].
     */
    private fun windowButton(action: PipActions.Action): RemoteAction {
        val icon = when (action.kind) {
            PipActions.Kind.Rewind -> R.drawable.ic_window_rewind
            PipActions.Kind.Play -> R.drawable.ic_window_play
            PipActions.Kind.Pause -> R.drawable.ic_window_pause
            PipActions.Kind.FastForward -> R.drawable.ic_window_forward
        }
        // One for each button, by its request code: Android tells PendingIntents apart by their intents, not their extras.
        val press = Intent(WINDOW_BUTTON).setPackage(packageName).putExtra(EXTRA_WINDOW_BUTTON, action.kind.name)
        val sent = PendingIntent.getBroadcast(this, action.kind.ordinal, press, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return RemoteAction(Icon.createWithResource(this, icon), action.kind.words, action.kind.words, sent).apply { isEnabled = action.enabled }
    }

    /**
     * A button of the window's pressed (W2): 10 s back or on, into the controls' burst, so taps in quick succession make one
     * seek, or play or pause. Only as the window offers it now: not Play at the end, nor a greyed seek under "Playback stopped".
     */
    private fun pressInWindow(kind: PipActions.Kind) {
        val c = controlsPlayer ?: return
        if (windowActions().none { it.kind == kind && it.enabled }) return
        when (kind) {
            PipActions.Kind.Rewind -> c.seekBack()
            PipActions.Kind.FastForward -> c.seekForward()
            PipActions.Kind.Play -> c.play()
            PipActions.Kind.Pause -> c.pause()
        }
    }

    /** [view]'s bounds in this window, in px. */
    private fun areaInWindow(view: View): Area {
        val at = IntArray(2).also(view::getLocationInWindow)
        return Area(at[0], at[1], at[0] + view.width, at[1] + view.height)
    }

    /**
     * Keeps Android told of the picture-in-picture window ([windowParams]), as the play, the picture or its place changes;
     * only of a change, as Android allows the shape to change only so often. Never where the player offers no window
     * ([pictureInPicture]): Android 8.0 throws for it on a device without picture-in-picture.
     */
    private fun updatePictureInPicture() {
        if (!pictureInPicture || isDestroyed) return
        val wanted = windowParams()
        if (wanted == windowTold) return
        if (wanted.autoEnter != windowTold?.autoEnter) log.debug("Picture-in-picture as the user leaves: {}", if (wanted.autoEnter) "on" else "off")
        setPictureInPictureParams(tell(wanted))
    }

    /**
     * The user leaving the player (Home, Recents, another app): Android 8 to 11 open no window themselves, so the player
     * asks for it here, where it may still (the activity is about to pause), for a film playing as [arming] says. Later
     * Androids open it themselves ([WindowParams.autoEnter]). Nothing here says what comes over the player: another app's
     * floating activity in a task of its own, which leaves it in sight, still puts it in the window, where Android 12 and
     * later open none. A known limit; the user expands the window again.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!arming().onLeaveHint) return
        log.info("Picture-in-picture: asked for as the user leaves")
        enterPictureInPictureMode(tell(windowParams()))
    }

    /**
     * In the window, or out of it: expanded, or closed (L6). Closing it, Android stops the player first, before it says so
     * (it makes sure of that order, WOC:560-568), where an expanded one is still started; the screen going off in the
     * window stops it with no change at all.
     */
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        when {
            isInPictureInPictureMode -> log.info("Picture-in-picture: in the window")
            !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) -> {
                log.info("Picture-in-picture: closed")
                return closeWindow()
            }
            else -> {
                log.info("Picture-in-picture: expanded")
                dropTheNewPlay()
            }
        }
        showPinned(isInPictureInPictureMode)
    }

    /**
     * The window closed (its ✕, or dragged away, L6): Android leaves the player stopped behind the home screen, where only
     * Recents would bring it back, playing its launch again. It saved where the play was and let it go as it stopped
     * ([endPlay]); now it closes, and its task with it when it is the task's root, as a player that was in the window is
     * (the app's screens stay where they were). So it closes too as the play ends in the window with nothing next
     * ([followTheEnd]), stopping as it goes.
     */
    private fun closeWindow() {
        if (isFinishing) return
        letGo()
        if (isTaskRoot) finishAndRemoveTask() else super.finish()
    }

    /** Android 15 says so as its animation into the window begins: the controls and the overlays go before it shrinks them. */
    @RequiresApi(31)
    override fun onPictureInPictureUiStateChanged(pipState: PictureInPictureUiState) {
        super.onPictureInPictureUiStateChanged(pipState)
        if (Build.VERSION.SDK_INT >= 35 && pipState.isTransitioningToPip) showPinned(true)
    }

    /** What the player shows now, in the window or full screen ([PipScene]). */
    private fun windowScene() = PipScene(pinned, panel = panel != null, failed = failure != null)

    /** media3's controls usable as [PipScene.controls] says: the one place they are switched on or off (M1). */
    private fun updateControlsUse() {
        playerView.useController = windowScene().controls
    }

    /** "Playback stopped" as its panel full screen, as one line on black in the window ([PipScene]); neither without a failure. */
    private fun showStopped() {
        val scene = windowScene()
        failureView.isVisible = scene.failurePanel
        stoppedLine.isVisible = scene.stoppedLine
    }

    /** The subtitles larger in the window, the user's caption size full screen ([PipScene.subtitles]). */
    private fun showSubtitles() {
        val view = playerView.subtitleView ?: return
        val size = windowScene().subtitles
        if (size != null) {
            view.setFractionalTextSize(size.textFraction)
            view.setBottomPaddingFraction(size.bottomFraction)
        } else {
            view.setUserDefaultTextSize()
            view.setBottomPaddingFraction(SubtitleView.DEFAULT_BOTTOM_PADDING_FRACTION)
        }
    }

    /**
     * The player in the picture-in-picture window, or going in ([now]), or out of it: in it only the picture shows
     * ([PipScene]), in Fit and with its subtitles larger. What showed over it goes: a side panel closes, the controls,
     * the top bar and the words go, a waiting tap or a pinch ends; the next-episode card, the skip button, the
     * missing-parts prompt and the slow-stream banner wait, "Playback stopped" is one line. Out of it the player is as it
     * was left: the size chosen, the user's subtitles, the controls down until a tap, the card or the panel back where
     * due; nothing in the window was for TalkBack, which reaches it only through Android's menu.
     */
    private fun showPinned(now: Boolean) {
        if (now == pinned) return
        pinned = now
        if (now) wasPinned = true
        if (now) {
            closePanel(backToButton = false)
            // Nothing asked over the picture in the window: the speaker question and the pre-check go, as their Cancel does.
            judging?.cancel()
            castAsking = null
            castQuestion = null
            hideMoment()
            hideWindowOff()
            hideMark()
            // At once, not with media3's fade, whose start says the controls show and would bring the top bar back.
            hideControlsAtOnce()
            topBar.visibility = View.GONE
            edgeTaps.reset()
            playerView.removeCallbacks(edgeTimeUp)
            pinchScale = null
            showSkip(null)
            hidePrompt()
            hideDip()
        }
        val scene = windowScene()
        sessionPlayer?.pinned = now
        updateControlsUse()
        showStopped()
        cardView.isVisible = nextCard != null && scene.overlays
        root.importantForAccessibility = if (scene.accessible) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        showSubtitles()
        applyZoom()
        updatePictureInPicture()
    }

    /** media3's controls down without their fade: gone in this turn, which the visibility listener hears. */
    private fun hideControlsAtOnce() {
        playerView.setControllerAnimationEnabled(false)
        playerView.hideController()
        playerView.setControllerAnimationEnabled(true)
    }

    /**
     * Another play started from the app's screens while this one plays in the window over them (L9, PipWindowNewPlay):
     * this one pauses as the play sheet starts the other, which frees its connections and the sound for it, and its place
     * is saved. The window stays, paused, until the new play comes (onNewIntent, as Android expands the window with it), or
     * the user plays on, expands or closes it; a play cancelled in the sheet leaves it so.
     */
    private fun pauseForANewPlay() {
        newPlayComing = true
        val p = controlsPlayer ?: return
        if (!p.playWhenReady || p.playbackState == Player.STATE_ENDED) return
        log.info("Picture-in-picture: another play started from the app; this one paused")
        p.pause()
        saveProgress(p.currentPosition, p.duration)
    }

    /**
     * The user went back to this play while the one the app's screens started was on its way: expanded the window, or
     * played on in it ([newPlayComing]). That one is dropped, as the sheet's Cancel drops it, with any launch of it the
     * app's screens haven't taken yet: it would take this player over as the user next leaves it or opens the app.
     */
    private fun dropTheNewPlay() {
        if (!newPlayComing) return
        newPlayComing = false
        log.info("Picture-in-picture: back to the play in the window; the play started from the app dropped")
        container.playback.withdraw()
    }

    /** Stop, Close or Back on the card: no card and no auto-advance for the rest of this episode; at the end, the player leaves ([followTheEnd]). */
    private fun stopCard() {
        dropCard()
        followTheEnd()
    }

    /** No card and no auto-advance for the rest of this episode. */
    private fun dropCard() {
        cardStopped = true
        autoAdvance?.stop()
        showCard(null)
    }

    /**
     * The play at its end, as [EndOfPlay] has it: with nothing to move on to, the player leaves as its back arrow does, or in
     * the window closes it as its ✕ does, the progress saved as watched as the play ended. Asked as the play ends, as the
     * next episode's lookup answers, and as the card is stopped.
     */
    private fun followTheEnd() {
        val p = player ?: return
        val facts = EndOfPlay.Facts(
            ended = p.playbackState == Player.STATE_ENDED, playWhenReady = p.playWhenReady, next = nextLookup, cardStopped = cardStopped,
            failed = failure != null, leaving = leaving || isFinishing || switched, started = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
            seekToCome = controlsPlayer?.seekToCome == true, pinned = pinned,
        )
        when (EndOfPlay.of(facts)) {
            EndOfPlay.Step.Leave -> {
                log.info("The play ended with nothing next: leaving the player")
                leave()
            }
            EndOfPlay.Step.CloseWindow -> {
                log.info("Picture-in-picture: the play ended with nothing next; the window closed")
                closeWindow()
            }
            EndOfPlay.Step.Stay, EndOfPlay.Step.Wait -> Unit
        }
    }

    private fun playNow(next: EpisodePlay) {
        if (autoAdvance?.playNow() == true) switchTo(next)
    }

    /**
     * Hands [episode] to the coordinator and closes: the play sheet below shows its search and
     * starts the player again, with the release found ahead for the next episode if there is one,
     * or from the release playing when that is a season pack that holds it. The next episode, by
     * the card or Next, or by Previous the one before. Progress is saved by onStop as for any exit.
     */
    private fun switchTo(episode: EpisodePlay) {
        // Once leaving, the user didn't ask for another episode: finish() leaves the countdown
        // ticking until onStop.
        if (switched || failure != null || isFinishing || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        switched = true
        val handedOver = handOverMode()
        // In the session of the phone that started this play, if one did (L33).
        container.playback.playEpisode(episode, launch?.release, PlayOrigin.after(launch))
        // The card's machine stops too: Next and Previous don't go through it, and its loop ticks on
        // until onStop, which would show the card again as the player closes.
        autoAdvance?.stop()
        finishHandingOver(handedOver)
        showCard(null)
    }

    /**
     * Play on TV's hand-over (L8, L18): a paired phone asked for [play] while this player is open, playing or showing
     * "Playback stopped", where [switchTo] does nothing. As [switchTo] does it: the TV's mode handed on, the coordinator
     * asked (the play sheet below starts it, saying what stopped here and where, [origin]'s session kept), the countdown
     * stopped, and the player closes, its progress saved by onStop as for any exit.
     */
    private fun handOver(play: AskedPlay, origin: PlayOrigin) {
        if (isFinishing) return
        switched = true
        val position = controlsPlayer?.currentPosition ?: resumePosition.takeIf { it != C.TIME_UNSET } ?: 0
        val from = origin.copy(stopped = StoppedPlay.of(stoppedTitle(), position, handle.durationMs, kept = launch != null))
        log.info("Play on TV: a phone's play takes this player's place")
        val handedOver = handOverMode()
        when (play) {
            is AskedPlay.Episode -> container.playback.playEpisode(play.episode, origin = from)
            is AskedPlay.Movie -> container.playback.playMovie(play.movie, from)
        }
        autoAdvance?.stop()
        finishHandingOver(handedOver)
        showCard(null)
    }

    /** What the sheet names the play that stopped for a phone's: an episode by its show and number, else its title. */
    private fun stoppedTitle(): String =
        launch?.let { if (it.watch.kind == WatchKind.Episode) it.watch.title else it.title } ?: intent.getStringExtra(EXTRA_TITLE).orEmpty()

    /** Play on TV's commands that came before the player was made, which it takes as it is ([startPlay]). */
    private val held = HeldCommands()

    /**
     * This player as Play on TV's commands reach it while it is started (L8): Pause, Resume and SeekTo through its media
     * session's player, SeekBy as a step of the controls' burst (its label says how far, as a remote's keys do), Stop as
     * the remote's Stop, and the hand-over. Before the player is made (its setup, a frame-rate switch) they are held.
     */
    private val handle = object : PlayerHandle {
        override val remote: RemotePlay? get() = launch?.remote
        override val durationMs: Long? get() = player?.duration?.takeIf { it != C.TIME_UNSET && it > 0 }

        override fun pause() {
            val p = sessionPlayer
            if (p != null) p.pause() else held.pause()
        }

        override fun resume() {
            val p = sessionPlayer
            if (p != null) p.play() else held.resume()
        }

        override fun seekTo(positionMs: Long) {
            val p = sessionPlayer
            if (p != null) p.seekTo(positionMs) else held.seekTo(positionMs)
        }

        override fun seekBy(deltaMs: Long) {
            val controls = controlsPlayer
            if (controls != null) controls.step(deltaMs, say = ::burstSaid) else held.seekBy(deltaMs)
        }

        override fun stop() = leave()

        override fun handOver(play: AskedPlay, origin: PlayOrigin) = this@PlayerActivity.handOver(play, origin)
    }

    /** Saying what plays each second while started; null otherwise. */
    private var nowJob: Job? = null

    /** Play on TV's commands no longer reach this player, nor does it say what plays: it stops or leaves. */
    private fun notInFront() {
        nowJob?.cancel()
        nowJob = null
        container.playerInFront.stopped(handle)
    }

    /**
     * What this player plays now (L34): its state ("Playback stopped" as failed; buffering until the player is made; paused
     * at the end of its file, which it may stay at, [NowPlaying.state]), where the controls show it is, its length, its
     * titles, the watch key (none for an opened file) and the panel's words, as a phone is told them (L37). No art.
     */
    private fun nowPlaying(): NowPlaying {
        val p = player
        val state = NowPlaying.state(
            failed = failure != null, made = p != null, ended = p?.playbackState == Player.STATE_ENDED, isPlaying = p?.isPlaying == true,
            playWhenReady = p?.playWhenReady != false,
        )
        val position = controlsPlayer?.currentPosition ?: resumePosition.takeIf { it != C.TIME_UNSET } ?: 0
        return NowPlaying(
            state, position, handle.durationMs, launch?.title ?: intent.getStringExtra(EXTRA_TITLE).orEmpty(), launch?.subtitle,
            launch?.watchKey, failure?.remote, launch?.remote,
        )
    }

    /** Where the user left off, unless finished or under a minute in. */
    private suspend fun savedPosition(): Long {
        val key = launch?.watchKey ?: return 0
        val state = container.library.state(key).first() ?: return 0
        return state.positionMs.takeIf { !state.watched && it >= 60_000 } ?: 0
    }

    private fun saveProgress(position: Long, duration: Long) {
        val save = launch?.let { WatchSaver.of(it, position, duration, releaseFailed) } ?: return
        WatchSaver.save(container, save)
    }

    /**
     * Where the play ended, as its player closes ([endPlay]) or as it reaches the end: saved as any progress is, and for a
     * play a paired phone started, owed to that phone as a Final (L33), the TV's own plays making none.
     */
    private fun saveEnd(position: Long, duration: Long) {
        saveProgress(position, duration)
        launch?.takeIf { it.remote != null }?.let { container.tvFinals.ended(it, position, duration) }
    }

    /**
     * However the player is left: a burst of rewind and fast-forward taps seeks nowhere any more, and endPlay keeps where
     * it was going. A setup still under way stops here (Back as the TV switches its frame rate), as onStop comes only once
     * the screen below shows: a wait ending in between would make and start a player for the play just left.
     */
    override fun finish() {
        letGo()
        if (wasPinned && !pinned && isTaskRoot) {
            leaveForTheApp()
        } else {
            super.finish()
        }
    }

    /**
     * Leaving a player alone in its task after the window (L7: Android 8 to 11 always, later ones once the app's own task
     * has gone): Back, Close and the hand-overs to another play would show whatever was behind the window, and a hand-over
     * needs the app's screens, whose play sheet starts the next player. So they come forward first: the app's own task as
     * it was, or the app started anew without one. Then the player goes, with its task, which Recents would otherwise keep
     * to play its launch again. Never for a player that was never in the window, which tests start alone in a task of its
     * own.
     */
    private fun leaveForTheApp() {
        val app = ComponentName(this, MainActivity::class.java)
        // A task can go between the listing and the read.
        val task = getSystemService(ActivityManager::class.java).appTasks.firstOrNull { t -> runCatching { t.taskInfo.baseActivity }.getOrNull() == app }
        log.info("Picture-in-picture: leaving alone in its task, the app's screens {}", if (task != null) "brought forward" else "started")
        if (task != null) {
            task.moveToFront()
        } else {
            // In a task of their own: Android would find the player's task by its affinity, the app's, and put them in it,
            // to go with it. As the launcher starts them, so its icon brings that task back rather than start them again.
            startActivity(Intent.makeMainActivity(app).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
        }
        finishAndRemoveTask()
    }

    /**
     * What every way of closing the player does first ([finish]): the setup stops, a burst's seek is dropped, and the
     * picture-in-picture window no longer opens as the user leaves.
     */
    private fun letGo() {
        setupJob?.cancel()
        controlsPlayer?.hold()
        leaving = true
        // The player closes for good: the grace of what its debrid play added begins (L234, L245).
        container.debridAdds.release(lease)
        // A play a phone asks for from now on isn't this one's to hand over.
        notInFront()
        updatePictureInPicture()
        // At once, not as the player stops: the next play's player can be made before then (a hand-over the sheet finds
        // at once), and its session would take this one's id.
        endSession()
    }

    /** The media session goes: what is outside the app no longer reaches this play. */
    private fun endSession() {
        session?.release()
        session = null
        sessionPlayer = null
    }

    /** Leaves the player: the card's countdown stopped first. The top bar's back arrow, the remote's Stop, and a session's. */
    private fun leave() {
        dropCard()
        finish()
    }

    override fun onDestroy() {
        if (pictureInPicture) unregisterReceiver(windowPresses)
        // Finished without closing itself (its task removed): its lease goes as letGo's would. One made again keeps it.
        if (isFinishing) container.debridAdds.release(lease)
        super.onDestroy()
    }

    /** This player's lease on its launch's debrid listing (L234), held again changing nothing; none for any other launch. */
    private fun holdLaunch() {
        val uri = launch?.uri ?: intent.getStringExtra(EXTRA_URI) ?: return
        (PlayUris.parse(uri) as? PlayUris.Target.Service)?.let { container.debridAdds.hold(lease, it.listingId) }
    }

    /**
     * The player made again while its film moves (L124): the new one plays the film here from where this one paused, so
     * the move is called off, and only one side plays. What it started on the device stops, as Cancel stops a cast play
     * on its way ([PlaybackCoordinator.cancel], L82); then a session the move connected itself ends, This phone chosen
     * (L85), in that order, as a move's failure ends one; a session that was there before stays as it was.
     */
    private fun callOffMove() {
        if (moveJob?.isActive != true) return
        val loading = moveOutcome?.isActive == true
        val connected = moveConnects
        dropMove()
        if (loading) container.playback.cancel()
        if (connected) container.playOn.choose(PlayChoice.Phone)
    }

    override fun onStop() {
        super.onStop()
        // What the next player does (W106), as this one stops: before the move that may have paused it is called off.
        playsWhenMade = PlayWhenMade.afterStop(playsWhenMade, stoppedPlayer(), isChangingConfigurations)
        // Being made again (a change of dark mode, the font or the language) while its film moves: the new player knows
        // nothing of the move, and plays the film here. Here, not as it is destroyed, when its move's wait is gone already.
        if (isChangingConfigurations) callOffMove()
        notInFront()
        endPlay()
        // Hidden, the window's mode counts no more: given up, so that a start again asks anew and waits from its own ask.
        // A play started here (onNewIntent) keeps it instead, until its own plan.
        leaveMode()
    }

    /** The player as the activity stops, for what the next one does ([PlayWhenMade]); null before one is made. */
    private fun stoppedPlayer(): PlayWhenMade.Stopped? = player?.let { p ->
        PlayWhenMade.Stopped(p.playWhenReady, ended = p.playbackState == Player.STATE_ENDED, moving = moveJob?.isActive == true)
    }

    /** Saves where the play is and releases its player, its overlays and what runs for it. */
    private fun endPlay() {
        setupJob?.cancel()
        progressJob?.cancel()
        nextJob?.cancel()
        previousJob?.cancel()
        segmentsJob?.cancel()
        gapsJob?.cancel()
        dipJob?.cancel()
        dips = null
        // Before the player goes: a broadcast or a removal on its way is dropped.
        noisy?.stop()
        noisy = null
        gapsListening?.close()
        gapsListening = null
        playGaps = null
        foundGaps.clear()
        gapJumps.clear()
        autoAdvance = null
        segments = null
        skipButton = null
        // A tap waiting for a second, or taps adding up, end with the play.
        edgeTaps.reset()
        playerView.removeCallbacks(edgeTimeUp)
        closePanel(backToButton = false)
        showCard(null)
        showSkip(null)
        hidePrompt()
        hideDip()
        hideMark()
        hideWindowOff()
        hideFailure()
        player?.let { p ->
            // Where the controls show the play: where a burst of rewind and fast-forward taps was
            // going, for the next player and the progress saved, without this one seeking first.
            controlsPlayer?.hold()
            val position = controlsPlayer?.currentPosition ?: p.currentPosition
            resumePosition = position
            if (p.playbackState != Player.STATE_ENDED) saveEnd(position, p.duration)
            // Before the player: a session left over a released player would take media keys for nothing.
            endSession()
            p.release()
        }
        player = null
        controlsPlayer = null
        playerView.player = null
        keepAwake()
        // media3 closes its shutter, and the next player opens it with its first frame.
        pictureShown = false
        applyZoom()
        // No window as the user leaves until the next player shows a picture.
        updatePictureInPicture()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(KEY_POSITION, controlsPlayer?.currentPosition ?: resumePosition)
        // Saved after onStop from Android 9, before it on 8: then from the player, as onStop will judge it.
        outState.putBoolean(KEY_PLAYING, PlayWhenMade.afterStop(playsWhenMade, stoppedPlayer(), isChangingConfigurations))
        outState.putString(KEY_TRACKS, TrackChoices.encode(picked))
        outState.putBoolean(KEY_WAS_PINNED, wasPinned)
        outState.putString(KEY_LEASE, lease)
        // Not before the preferences have been read: then the next setup reads them.
        if (zoomKnown) {
            zoom.saveIn(PlaybackPrefs()).let {
                outState.putString(KEY_ASPECT, it.aspectMode)
                outState.putFloat(KEY_ZOOM, it.zoomScale)
            }
        }
    }

    /**
     * Playback stopped for good: the panel says why and offers another release, and File details
     * with what the player read. A side panel, the skip button and the next-episode card give way
     * (its countdown holds), and the controls stay down, so media3 can't bring them up with a TV
     * remote's focus on play/pause; keys go to the panel. A release at fault is no longer the one
     * Resume and replay choose for the watch key. [stops]: the routes the failure stopped (a debrid
     * account's), whose releases Try another release leaves out.
     */
    private fun showFailure(failed: PlaybackFailure, stops: Set<String> = emptySet()) {
        val l = launch
        if (failed.releaseAtFault) {
            releaseFailed = true
            l?.let(container.playback::forgetFailed)
        }
        closePanel(backToButton = false)
        showSkip(null)
        showCard(null)
        hidePrompt()
        hideDip()
        hideMark()
        fileDetails.stopped()
        failure = Failure(failed.reason, failed.remote, canTryAnother = l != null && container.playback.canTryAnother(l, stops), stops)
        sessionPlayer?.failed = true
        updateControlsUse()
        backLeavesFailure.isEnabled = true
        showStopped()
        keepAwake()
        updatePictureInPicture()
    }

    private fun hideFailure() {
        if (failure == null) return
        failure = null
        sessionPlayer?.failed = false
        backLeavesFailure.isEnabled = false
        showStopped()
        updateControlsUse()
        keepAwake()
        updatePictureInPicture()
    }

    /**
     * Try another release: saves where this one stopped (without keeping it as the release that
     * played), closes, and has the coordinator play the next release from there; the play sheet
     * shows how that goes.
     */
    private fun tryAnother() {
        val l = launch ?: return
        if (isFinishing) return
        val p = player
        val position = controlsPlayer?.currentPosition ?: resumePosition.takeIf { it != C.TIME_UNSET } ?: 0
        releaseFailed = true
        p?.let { saveProgress(position, it.duration) }
        val handedOver = handOverMode()
        container.playback.tryAnother(l, position, stopped = failure?.stops.orEmpty())
        finishHandingOver(handedOver)
    }

    /** The panel's [reason], and [remote], the same in the words a paired phone is told it (L37); [stops], the routes it stopped. */
    private data class Failure(val reason: String, val remote: String, val canTryAnother: Boolean, val stops: Set<String> = emptySet())

    /**
     * The picture-in-picture window as the player tells Android of it: its shape ([aspect], null: not known yet), where it
     * opens from ([hint], in the window), whether Android opens it as the user leaves ([autoEnter], Android 12 and later),
     * and its own [buttons]. No title: only a TV's window menu shows one.
     */
    private data class WindowParams(val aspect: PipShape.Ratio?, val hint: Area?, val autoEnter: Boolean, val buttons: List<PipActions.Action>) {
        /** As Android takes them, with the [buttons] made as [made] and the [hint] as from [hintFrom] ([hintOrigin]). */
        fun platform(made: List<RemoteAction>, hintFrom: Point): PictureInPictureParams = PictureInPictureParams.Builder().apply {
            aspect?.let { setAspectRatio(Rational(it.width, it.height)) }
            hint?.let { setSourceRectHint(Rect(it.left, it.top, it.right, it.bottom).apply { offset(-hintFrom.x, -hintFrom.y) }) }
            setActions(made)
            if (Build.VERSION.SDK_INT >= 31) {
                setAutoEnterEnabled(autoEnter)
                // The picture resized as the window is, not faded: it has no text to lay out again.
                setSeamlessResizeEnabled(true)
            }
        }.build()
    }

    private data class NextCard(val next: EpisodePlay, val card: AutoAdvance.Card, val showStill: Boolean, val takeFocus: Boolean)

    private data class PromptShown(val prompt: MissingParts.Prompt, val takeFocus: Boolean)

    /** The slow-stream banner as it shows: the dip, its words, and whether it offers a smaller release (W2). */
    private data class DipShown(val dip: DipDetector.Dip, val words: SlowStream.Shown, val canSwitch: Boolean)

    /** A new count of missing parts, for a new launch: a new release starts its own. */
    private fun newMissingParts() = MissingParts(SystemClock::elapsedRealtime, timings.missingPartsAnswerForMs, timings.missingPartsCountOnlyForMs)

    /** Whether [this] stretch was found in the file [target] names: by its name where the URI says, else by its place in the NZB. */
    private fun GapReports.Found.isOf(target: UsenetUris.Target): Boolean = target.name?.let { it == fileName } ?: (fileIndex == target.fileIndex)

    private data class HeldFocus(val view: ViewGroup, val descendants: Int, val focusable: Int, val accessibility: Int)

    /** The side panels over the player, one at a time: the audio and subtitles sheet, File details, Play on's list. */
    private enum class Panel { Tracks, Details, PlayOn }

    /**
     * Where the file playing comes from, for File details: the launch's release (or the opened NZB's), its file, the
     * [indexers] (null until read), and the providers in use now with the connections a stream takes, or a debrid play's
     * service by the account its listing came from.
     */
    private fun detailsOrigin(indexers: SourceNames?): FileOrigin {
        val uri = launch?.uri ?: intent.getStringExtra(EXTRA_URI).orEmpty()
        // The release's files as its source keeps them: a Usenet play's, its prepared NZB in the store; a debrid play's, the
        // service's listing the store keeps.
        val target = PlayUris.parse(uri)
        val entry = (target as? PlayUris.Target.Service)?.let { container.debridListings.keptAs(it.listingId) }
        val listing = (target as? PlayUris.Target.Usenet)?.let { container.store.get(it.nzb.nzbId) }
            ?: entry?.let { ServiceFiles(it.prepared.hash, it.title, it.prepared.listing) }
        return FileOrigin.of(launch, uri, listing, indexers, container.engine.inUse, container.engine.streamConcurrency, account = entry?.account)
    }

    /** Bits per second as the log says them: "12.3". */
    private fun mbps(bitsPerSecond: Long) = "%.1f".format(Locale.ROOT, bitsPerSecond / 1_000_000.0)

    private fun format(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    /**
     * How long before the end the next-episode card shows and how long it counts down ([AutoAdvance]), how long the skip
     * button shows ([SkipButton]) and the missing-parts prompt, and the slow-stream banner's waits: the app's own, unless a
     * test shortens them ([timings]).
     */
    @VisibleForTesting
    internal data class Timings(
        val cardBeforeEndMs: Long = AutoAdvance.CARD_BEFORE_END_MS,
        val countdownMs: Long = AutoAdvance.COUNTDOWN_MS,
        val skipShowForMs: Long = SkipButton.SHOW_FOR_MS,
        /** The waits of a TV's switch to the video's frame rate ([DisplayModeSwitcher]). */
        val switching: DisplayModeSwitcher.Timings = DisplayModeSwitcher.Timings(),
        /** How long the missing-parts prompt shows unanswered, and the count alone ([MissingParts]). */
        val missingPartsAnswerForMs: Long = MissingParts.ANSWER_FOR_MS,
        val missingPartsCountOnlyForMs: Long = MissingParts.COUNT_ONLY_FOR_MS,
        /** The slow-stream banner's waits ([DipDetector]). */
        val dip: DipDetector.Timings = DipDetector.Timings(),
    )

    companion object {
        @VisibleForTesting
        internal const val EXTRA_URI = "uri"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_LAUNCH = "launch"
        private const val KEY_POSITION = "position"
        @VisibleForTesting
        internal const val KEY_PLAYING = "playing"
        private const val KEY_TRACKS = "tracks"
        private const val KEY_ASPECT = "aspect"
        private const val KEY_ZOOM = "zoom"
        private const val KEY_WAS_PINNED = "wasPinned"
        private const val KEY_LEASE = "lease"
        /** How often the next-episode card is updated: often while it shows, for its countdown. */
        private const val TICK_MS = 500L
        private const val CARD_TICK_MS = 100L
        /** How often the skip button is updated. */
        private const val SKIP_TICK_MS = 250L
        /** How often the slow-stream detector looks at the player. */
        private const val DIP_TICK_MS = 500L
        /**
         * Above media3's time bar, whose 48 dp touch strip sits 52 dp above the controls' bottom (the
         * gesture area's inset above the screen's, see applyInsets), so the card never covers it,
         * whether or not the controls show. The compact card, 52 dp tall at the default font size,
         * then ends 152 dp up: below the controls' centre buttons, which reach 26 dp under the
         * middle of the room above that inset, and on a screen too short for that go up clear of
         * it while it shows (liftCentreControls), as on a 360 dp tall phone with a gesture area or
         * navigation bar.
         */
        private const val CARD_BOTTOM_DP = 100
        /** How long a new size of the picture, or how far a burst of rewind and fast-forward taps goes, is said. */
        private const val MOMENT_LABEL_MS = 1_500L
        /** How often the player says what it plays while started (L34). */
        private const val NOW_PLAYING_MS = 1_000L
        /** How long the moment label says a missing part was skipped: longer, as it says more. */
        private const val NOTICE_MS = 3_000L
        @VisibleForTesting
        internal const val MISSING_PART_SKIPPED = "A missing part was skipped"
        /** Under the top bar (its 48 dp buttons and 8 dp above and below), whose insets it shares. */
        private const val MOMENT_LABEL_TOP_DP = 72
        /** How far from the screen's sides the words that picture-in-picture is off keep, at the most (PipPlayerOff). */
        private const val WINDOW_OFF_SIDES_DP = 48
        /**
         * Android Settings' picture-in-picture page for one app, by a package: URI (AOSP's AppPictureInPictureSettingsActivity);
         * not in the public SDK.
         */
        private const val PICTURE_IN_PICTURE_SETTINGS = "android.settings.PICTURE_IN_PICTURE_SETTINGS"
        /** The broadcast the window's buttons send ([windowPresses]), and which button it was. */
        private const val WINDOW_BUTTON = "io.skjaere.usenet.app.action.WINDOW_BUTTON"
        private const val EXTRA_WINDOW_BUTTON = "button"
        /** A TV's safe area: nothing to read or focus nearer its sides, or its top and bottom (TV-OV). */
        private const val TV_SIDES_DP = 48
        private const val TV_ENDS_DP = 27
        /** How long a round button takes to grow as it takes a TV remote's focus, as the app's Compose buttons do. */
        private const val FOCUS_SCALE_MS = 120L
        /** media3's own time bar step, a twentieth of the file, which the bar keeps in touch mode. */
        private const val TIME_BAR_STEPS = 20
        /** A 4K screen's height, for the budget of a low-RAM box with one. */
        private const val UHD_HEIGHT = 2160
        /** Behind the navigation buttons as they show for a moment, up to Android 9: androidx.activity's dark scrim. */
        private val NAVIGATION_SCRIM = android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b)

        private fun launchOf(intent: Intent): PlayerLaunch? = intent.getStringExtra(EXTRA_LAUNCH)?.let(PlayerLaunch::decode)

        /** Test seam: the settings read setup starts with, which a test can make slow, instant or deaf to cancellation. */
        @VisibleForTesting
        @Volatile
        internal var readPlayback: suspend (AppContainer) -> PlaybackPrefs = { it.settings.playback.first() }

        /** Test seam: the settings' indexers and service accounts File details name the release's source by, which a test can hold up. */
        @VisibleForTesting
        @Volatile
        internal var readIndexers: suspend (AppContainer) -> SourceNames = {
            SourceNames(it.settings.indexers.first(), it.settings.torrentIndexers.first(), it.settings.services.first())
        }

        fun start(context: Context, uri: String, title: String) {
            context.startActivity(Intent(context, PlayerActivity::class.java).putExtra(EXTRA_URI, uri).putExtra(EXTRA_TITLE, title))
        }

        /** Test seam: where the player reads the file from, which a test can hold up. */
        @VisibleForTesting
        @Volatile
        internal var dataSources: (AppContainer) -> DataSource.Factory = { it.playerDataSources }

        /** Test seam: the load control a player is made with for the play's budget, which a test can watch the player use. */
        @VisibleForTesting
        @Volatile
        internal var loadControlFor: (PlaybackBudget) -> LoadControl = PlayerFactory::loadControl

        /** Test seam: the audio outputs a "becoming noisy" finds listed, by their AudioDeviceInfo types, which a test can make a TV's with headphones. */
        @VisibleForTesting
        @Volatile
        internal var audioOutputs: (Context) -> List<Int> = BecomingNoisy::listOutputs

        /** Test seam: the device's memory, which a test can make a low-RAM box's. */
        @VisibleForTesting
        @Volatile
        internal var readMemory: (Context) -> DeviceMemory = DeviceMemory::of

        /** Test seam: the chapters of the file playing, which a test can make up to place segments where no file has them. */
        @VisibleForTesting
        @Volatile
        internal var readChapters: (Tracks) -> List<FileChapter> = { it.fileChapters() }

        /** Test seam: TheIntroDB's segments for the episode playing in a file of the length given (none unless the user turned the lookup on). */
        @VisibleForTesting
        @Volatile
        internal var lookUpSegments: suspend (AppContainer, AutoAdvance.Episode, Long) -> List<Segment> = { c, e, durationMs ->
            c.introDb.episode(e.showTmdbId, e.season, e.episode, durationMs)
        }

        /** Test seam: the episode after the one playing, which a test can answer without TMDB. */
        @VisibleForTesting
        @Volatile
        internal var nextEpisode: suspend (AppContainer, AutoAdvance.Episode) -> EpisodePlay? = { c, e ->
            nextAiredEpisode(c.tmdb, e.showTmdbId, e.season, e.episode)
        }

        /** Test seam: the next episode's release search as the card starts it, which a test can leave searching for good. */
        @VisibleForTesting
        @Volatile
        internal var prefetchNext: (AppContainer, EpisodePlay, SavedRelease?) -> StateFlow<Prefetch> = { c, next, playing -> c.playback.prefetchEpisode(next, playing) }

        /** Test seam: the next-episode card's and the skip button's timings, which a test can shorten rather than wait them out. */
        @VisibleForTesting
        @Volatile
        internal var timings = Timings()

        /**
         * Test seam: whether this is a TV, where the player matches the TV to the video's frame rate when the setting is
         * on: the device's kind, not its layouts. A test on a phone emulator makes it one, with a TV's display (DisplayPorts).
         */
        @VisibleForTesting
        @Volatile
        internal var isTelevision: (AppContainer) -> Boolean = { it.television }

        /** Test seam: whether the device has Android's picture-in-picture, which a test on a phone emulator takes away. */
        @VisibleForTesting
        @Volatile
        internal var hasPictureInPicture: (Context) -> Boolean = { it.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) }

        /**
         * Test seam: the Android version picture-in-picture goes by ([PictureInPicturePolicy]): a test on a newer emulator
         * takes Android 8 to 11's way into the window, the player asking for it as the user leaves.
         */
        @VisibleForTesting
        @Volatile
        internal var pictureInPictureSdk: () -> Int = { Build.VERSION.SDK_INT }

        /** Test seam: the size of the video playing, which the window takes its shape from; a test gives a wider film's. */
        @VisibleForTesting
        @Volatile
        internal var videoSizeOf: (Player) -> VideoSize = { it.videoSize }

        /**
         * Test seam: what the track probe read of a launch's video (its frame rate and length), which a test sets for files
         * whose own it can't choose (the mock server's episodes).
         */
        @VisibleForTesting
        @Volatile
        internal var videoOf: (PlayerLaunch) -> VideoTiming? = { it.video }

        /**
         * Test seam: whether an accessibility service explores the screen by touch (TalkBack), when its double-tap is its
         * own and the picture's double-tap to seek is off (L10). A test makes it so without TalkBack.
         */
        @VisibleForTesting
        @Volatile
        internal var touchExploration: (Context) -> Boolean = { it.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled == true }

        /** Test seam: the episode before the one playing, which a test can answer without TMDB. */
        @VisibleForTesting
        @Volatile
        internal var previousEpisode: suspend (AppContainer, AutoAdvance.Episode) -> EpisodePlay? = { c, e ->
            previousAiredEpisode(c.tmdb, e.showTmdbId, e.season, e.episode)
        }

        @VisibleForTesting
        internal fun intent(context: Context, launch: PlayerLaunch): Intent =
            Intent(context, PlayerActivity::class.java).putExtra(EXTRA_LAUNCH, launch.encode())

        /** Starts [launch]; without the transition when the TV's mode is held for it ([animate] false: see DisplayModeHold). */
        fun start(context: Context, launch: PlayerLaunch, animate: Boolean = true) {
            context.startActivity(intent(context, launch).apply { if (!animate) addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION) })
        }
    }
}
