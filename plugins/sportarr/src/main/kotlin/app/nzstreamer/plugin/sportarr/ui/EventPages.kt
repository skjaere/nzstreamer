package app.nzstreamer.plugin.sportarr.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.nzstreamer.plugin.sportarr.Session
import io.skjaere.usenet.app.play.OkGuard
import io.skjaere.usenet.app.play.pageOkGuard
import io.skjaere.usenet.app.play.rememberPageOkGuard
import io.skjaere.usenet.app.tv.tvFullWidth
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.EdgeScrolling
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.ui.LocalFormFactor
import io.skjaere.usenet.app.ui.PaneMemory
import io.skjaere.usenet.app.ui.PrimaryButton
import io.skjaere.usenet.app.ui.ReturnFocus
import io.skjaere.usenet.app.ui.SecondaryButton
import io.skjaere.usenet.app.ui.TopBar
import io.skjaere.usenet.app.ui.TvDimens
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.focusOutline
import io.skjaere.usenet.app.ui.focusPane
import io.skjaere.usenet.app.ui.paneElement
import io.skjaere.usenet.app.ui.rememberInitialFocus
import io.skjaere.usenet.app.ui.rememberReturnFocus
import io.skjaere.usenet.app.ui.tryFocus
import io.skjaere.usenet.plugin.Offered
import io.skjaere.usenet.plugin.PlayItem
import io.skjaere.usenet.plugin.PluginNav
import io.skjaere.usenet.plugin.PluginPlay
import io.skjaere.usenet.plugin.ReleaseHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The event pages (L652): an event's or a weekend's page, opened from a row of the list by its key, drawn as its kind
 * says (GamePage.kt, GenericPage.kt, WeekendPage.kt, CardPage.kt), and the pieces they share: the actions, the TV's two
 * panes and first focus, the status lines and the buttons, the page whose event left the list.
 */

/**
 * The page [page] ([Pages.EVENT] or [Pages.WEEKEND]) with [key], over its model for as long as it shows. The kept list
 * comes at once, read only when none is kept (a process made again with this page on top); the time moves on each minute
 * while it shows, so an event that starts shows its buttons. On a TV its content takes the page's OK guard: the OK that
 * opened it, pressed twice or held, never starts a play (L660). [play] draws NZStreamer's Cast button on a phone.
 */
@Composable
internal fun EventPage(session: Session, play: PluginPlay, nav: PluginNav, page: String, key: String) {
    val tv = LocalFormFactor.current == FormFactor.Tv
    val model = remember(session, page, key, tv) { session.page(page, key, tv) }
    LaunchedEffect(session) { session.refresh.pageShown() }
    LaunchedEffect(model) { model.run() }
    LaunchedEffect(model) {
        while (true) {
            delay(MINUTE_MS)
            model.tick()
        }
    }
    val screen by model.screen.collectAsState()
    val focus = rememberReturnFocus()
    val okGuard = rememberPageOkGuard()
    val scope = rememberCoroutineScope()
    val actions = remember(model, nav, scope, focus) { PageActions(model, nav, scope, focus) }
    val bar = @Composable { if (!tv) TopBar("", actions::back) { play.PlayOnButton(Modifier) } }
    when (val shown = screen) {
        null -> Column(Modifier.fillMaxSize()) {
            bar()
            CircularProgressIndicator(Modifier.padding(24.dp), color = colors.accent)
        }
        is GoneScreen -> GonePage(shown, tv, actions, okGuard, bar)
        is EventScreen -> when (shown.kind) {
            PageKind.Game -> GamePage(shown, tv, actions, okGuard, bar)
            PageKind.Generic -> GenericPage(shown, tv, actions, okGuard, bar)
            PageKind.Weekend -> WeekendPage(shown, tv, actions, okGuard, bar)
            PageKind.Card -> CardPage(shown, tv, actions, okGuard, bar)
        }
    }
}

/**
 * What a page's elements do, each saying where the focus comes back to ([focus], by the element's key) as what it opens
 * covers the page: the player, Choose release, Search. Choose release and Search run in the page's [scope], so a page
 * left meanwhile opens nothing; the focus is kept for them only as they open, as Choose release with nothing at all
 * opens nothing.
 */
internal class PageActions(private val model: PageModel, private val nav: PluginNav, private val scope: CoroutineScope, val focus: ReturnFocus) {
    fun play(part: String, from: String) {
        focus.leaving(from)
        model.play(part)
    }

    fun choose(part: String, from: String) {
        scope.launch { model.choose(part, Leaving(nav) { focus.leaving(from) }) }
    }

    fun search(part: String, from: String) {
        scope.launch { model.search(part, Leaving(nav) { focus.leaving(from) }) }
    }

    fun mark(part: String, watched: Boolean) = model.mark(part, watched)

    fun back() = nav.back()

    /** A page's button. */
    fun press(button: PageButton) = when (button.action) {
        PageAction.Play -> play(button.part, button.key)
        PageAction.Choose -> choose(button.part, button.key)
        PageAction.Search -> search(button.part, button.key)
    }

    /** An item of [part]'s ⋮, the focus coming back to the ⋮. */
    fun menu(part: String, action: MenuAction) = when (action) {
        MenuAction.Choose -> choose(part, PageKeys.more(part))
        MenuAction.Search -> search(part, PageKeys.more(part))
        MenuAction.MarkWatched -> mark(part, true)
        MenuAction.MarkUnwatched -> mark(part, false)
    }

    /** [nav], saying where the focus comes back to ([leave]) just before it opens Choose release or Search. */
    private class Leaving(private val nav: PluginNav, private val leave: () -> Unit) : PluginNav by nav {
        override fun openReleases(item: PlayItem, accepted: List<ReleaseHandle>, offered: List<Offered>, leftOut: String?) {
            leave()
            nav.openReleases(item, accepted, offered, leftOut)
        }

        override fun openSearch(words: String, note: String?) {
            leave()
            nav.openSearch(words, note)
        }
    }
}

/**
 * The focus as the page shows: back on what opened what covered it (Choose release, a ⋮, a row played); else, on a TV,
 * the page's first focus ([first]), asked a frame at a time until it takes, as an element may come a frame late. Once
 * for each [kind] of screen, so a change under the user's focus never moves it. A phone takes none of its own.
 */
@Composable
internal fun PageFocus(kind: String, first: String, tv: Boolean, focus: ReturnFocus) {
    val initial = rememberInitialFocus()
    LaunchedEffect(kind) {
        if (focus.restore() || !tv) return@LaunchedEffect
        initial.awaitTurn()
        repeat(FOCUS_FRAMES) {
            withFrameNanos {}
            if (focus.requester(first).tryFocus()) return@LaunchedEffect
        }
    }
}

/**
 * A TV page's two panes across the page (as the Movie page's): [left], [TvDimens.pagePane] wide, scrolling as far as the
 * focused element needs, its [leftFoot] at the pane's foot; and [right], the rest. Left and Right go back to where the
 * focus was in each pane ([panes]).
 */
@Composable
internal fun TvPanes(
    panes: PaneMemory,
    okGuard: OkGuard,
    left: @Composable ColumnScope.() -> Unit,
    leftFoot: @Composable () -> Unit = {},
    right: @Composable ColumnScope.() -> Unit,
) {
    Row(Modifier.fillMaxHeight().tvFullWidth().pageOkGuard(okGuard), horizontalArrangement = Arrangement.spacedBy(TvDimens.paneGap)) {
        Column(Modifier.width(TvDimens.pagePane).fillMaxHeight().focusPane(panes, LEFT), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            EdgeScrolling {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp), content = left)
            }
            leftFoot()
        }
        Column(Modifier.weight(1f).fillMaxHeight().focusPane(panes, RIGHT), content = right)
    }
}

/** A page's title, the page's heading; on a phone a little tighter, as the Movie page's. */
@Composable
internal fun PageTitle(title: String, tv: Boolean) {
    Text(
        title, style = if (tv) AppType.hero else AppType.hero.copy(letterSpacing = (-0.4).sp), color = colors.text,
        modifier = Modifier.semantics { heading() },
    )
}

/** A status line in its tone's colour, its bar under it where it has one ([bar] tall, on [track]). */
@Composable
internal fun Status(line: StatusLine, style: TextStyle, bar: Dp, track: Color, modifier: Modifier = Modifier) {
    Text(line.text, style = style, color = line.tone.color(), modifier = modifier)
    line.progress?.let { ProgressBar(it, bar, track) }
}

@Composable
internal fun Tone.color(): Color = when (this) {
    Tone.Muted -> colors.muted
    Tone.Secondary -> colors.textSecondary
    Tone.Accent -> colors.accent
    Tone.Good -> colors.good
}

/** How far a play went, or how much is watched: [progress] of the track, [height] tall. */
@Composable
internal fun ProgressBar(progress: Float, height: Dp, track: Color, color: Color = colors.accent) {
    LinearProgressIndicator(
        progress = { progress }, modifier = Modifier.fillMaxWidth().height(height), color = color, trackColor = track, drawStopIndicator = {},
        gapSize = 0.dp,
    )
}

/**
 * A page's button: the amber [PageButton.primary] one (with the play arrow where it plays), else outlined; [modifier] its
 * focus and width.
 */
@Composable
internal fun PageButtonView(button: PageButton, actions: PageActions, modifier: Modifier) {
    if (button.primary) {
        PrimaryButton(
            button.label, { actions.press(button) }, modifier,
            leading = if (button.action == PageAction.Play) ({ Icon(Icons.Filled.PlayArrow, null, Modifier.padding(end = 6.dp)) }) else null,
        )
    } else {
        SecondaryButton(button.label, { actions.press(button) }, modifier)
    }
}

/** A phone's page button: its focus for a remote, the outline round it while focused. */
@Composable
internal fun PhoneButton(button: PageButton, actions: PageActions) {
    PageButtonView(
        button, actions,
        Modifier.focusRequester(actions.focus.requester(button.key))
            .focusOutline(RoundedCornerShape(24.dp), color = if (button.primary) colors.text else null, fill = false),
    )
}

/** A TV's buttons, across the left pane, one under another. */
@Composable
internal fun TvButtons(buttons: List<PageButton>, panes: PaneMemory, actions: PageActions) {
    if (buttons.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        buttons.forEach { button -> PageButtonView(button, actions, Modifier.fillMaxWidth().paneElement(panes, LEFT, button.key)) }
    }
}

/**
 * On a TV, the line in the buttons' place before an event starts: it holds the focus, as Search's empty states do, so the
 * page has something to focus (L660).
 */
@Composable
internal fun HeldLine(text: String, panes: PaneMemory) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.fillMaxWidth().paneElement(panes, LEFT, PageKeys.HELD).clip(shape).background(colors.chip).focusOutline(shape, fill = false).focusable()
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) { Text(text, style = AppType.secondary, color = colors.textSecondary) }
}

/** A page's small muted line: the line before the start on a phone, the line kept after Play found nothing. */
@Composable
internal fun SmallLine(text: String) {
    Text(text, style = AppType.small, color = colors.muted)
}

/** A TV page's footnote, not across the whole pane, as the Movie page's. */
@Composable
internal fun TvFoot(text: String, width: Dp = FOOTNOTE_WIDTH) {
    Text(text, style = AppType.small, color = colors.muted, modifier = Modifier.widthIn(max = width))
}

/**
 * The page whose event left the list after a read (L652): the line that says so, and Back, which takes a TV's focus.
 */
@Composable
private fun GonePage(screen: GoneScreen, tv: Boolean, actions: PageActions, okGuard: OkGuard, bar: @Composable () -> Unit) {
    val focus = actions.focus
    PageFocus("gone", screen.firstFocus, tv, focus)
    Column(Modifier.fillMaxSize().then(if (tv) Modifier.tvFullWidth() else Modifier).pageOkGuard(okGuard)) {
        bar()
        Column(Modifier.padding(horizontal = 16.dp, vertical = if (tv) 24.dp else 8.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(screen.line, style = AppType.body, color = colors.textSecondary)
            SecondaryButton(
                screen.back, actions::back,
                Modifier.focusRequester(focus.requester(PageKeys.BACK)).focusOutline(RoundedCornerShape(24.dp), fill = false),
            )
        }
    }
}

/** A phone's Mark watched or unwatched, at the right under the buttons, as the Movie page's. */
@Composable
internal fun PhoneMark(mark: MarkButton, actions: PageActions) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
        TextButton(
            onClick = { actions.mark(mark.part, mark.watched) },
            modifier = Modifier.focusRequester(actions.focus.requester(PageKeys.MARK)).focusOutline(RoundedCornerShape(20.dp), fill = false),
        ) { Text(mark.label, style = AppType.secondary.copy(fontSize = 14.sp), color = colors.accent) }
    }
}

/** The panes, for [PaneMemory]. */
internal const val LEFT = "left"
internal const val RIGHT = "right"

/** A TV footnote's widest, as the Movie page's. */
private val FOOTNOTE_WIDTH = 420.dp

/** How often a page's time moves on while it shows. */
private const val MINUTE_MS = 60_000L

/** Frames the first focus is asked again for, as an element may come a frame late. */
private const val FOCUS_FRAMES = 10
