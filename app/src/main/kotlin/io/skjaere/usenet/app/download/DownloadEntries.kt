package io.skjaere.usenet.app.download

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.collection.UndoBar
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.play.MovieTarget
import io.skjaere.usenet.app.tv.TvMenu
import io.skjaere.usenet.app.tv.TvMenuItem
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.DownloadIcon
import io.skjaere.usenet.app.ui.DownloadedIcon
import io.skjaere.usenet.app.ui.LocalFormFactor
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.focusOutline

/**
 * Whether downloads show where this composes (W108, L369): in the phone's layout, on a device that isn't a TV. Every way
 * in asks it: the episode ⋮'s items and the row's mark, the Movie page's button, Search's movie card and Home's section.
 */
@Composable
fun downloadsShown(): Boolean = DownloadPlaces.shown(LocalFormFactor.current, LocalContext.current.container.television)

/** A download's line ([TitleDownloads.line]), each part in its colour, in the row's small type; it wraps at large sizes. */
@Composable
internal fun DownloadLine(parts: List<PageLine>, modifier: Modifier = Modifier) {
    val tones = parts.map { it.tone.color() }
    Text(
        buildAnnotatedString { parts.forEachIndexed { i, part -> withStyle(SpanStyle(color = tones[i])) { append(part.text) } } },
        style = AppType.small, modifier = modifier,
    )
}

/**
 * A download's mark ([DownloadMark], DlPhoneEpisodeRow): the drawn icons, a ring, or ⚠, 18 dp, or [size] where a button
 * draws it larger (Import's rows, 24 dp). The same size at any font size, as the ⋮ beside it; TalkBack hears the row's
 * state or the button's words instead. The arrow offered is in the text's colour, or [offered]'s where a button's is the
 * accent (Import's).
 */
@Composable
internal fun DownloadMarkIcon(mark: DownloadMark, modifier: Modifier = Modifier, size: Dp = MARK, offered: Color = colors.text) {
    // Silent itself: the ⚠ and the rings say nothing of their own.
    val box = modifier.size(size).clearAndSetSemantics { this[DownloadMarkLook] = mark }
    when (mark) {
        DownloadMark.Download -> Icon(DownloadIcon, contentDescription = null, tint = offered, modifier = box)
        DownloadMark.Queued -> Icon(DownloadIcon, contentDescription = null, tint = colors.muted, modifier = box)
        DownloadMark.Finding -> CircularProgressIndicator(box.padding(1.dp), color = colors.accent, trackColor = colors.outline, strokeWidth = 2.dp)
        is DownloadMark.Progress -> Ring(mark, box)
        DownloadMark.Done -> Icon(DownloadedIcon, contentDescription = null, tint = colors.textSecondary, modifier = box)
        DownloadMark.Failed -> Box(box, contentAlignment = Alignment.Center) {
            // As drawn at 18 dp, scaled with the mark.
            val glyph = with(LocalDensity.current) { (WARN_GLYPH * (size / MARK)).toSp() }
            Text("⚠", style = AppType.body.copy(fontSize = glyph, lineHeight = glyph), color = colors.warnStrong)
        }
    }
}

/** The ring of a file being written (the accent), or held (muted, with two bars in it), as Material's determinate one draws it. */
@Composable
private fun Ring(mark: DownloadMark.Progress, modifier: Modifier) {
    val color = if (mark.held) colors.muted else colors.accent
    val track = colors.outline
    Canvas(modifier) {
        val stroke = 2.dp.toPx()
        val inset = 2.dp.toPx()
        val oval = Size(size.width - inset * 2, size.height - inset * 2)
        drawArc(track, 0f, 360f, useCenter = false, topLeft = Offset(inset, inset), size = oval, style = Stroke(stroke))
        if (mark.fraction > 0f) {
            drawArc(color, -90f, 360f * mark.fraction, useCenter = false, topLeft = Offset(inset, inset), size = oval, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        if (mark.held) {
            val c = size.width / 2
            val height = size.width * 0.34f
            val width = size.width * 0.1f
            listOf(c - width * 1.45f, c + width * 0.45f).forEach { x ->
                drawRoundRect(color, Offset(x, c - height / 2), Size(width, height), CornerRadius(width / 3))
            }
        }
    }
}

/**
 * The Movie page's and Search's movie card's Download (DlPhoneMovie, DlPhoneSearchMovie): one outlined button whose words
 * follow the film's [download] ([TitleDownloads.button]), its mark before them and the detail on a second line. Not
 * started, a press is Download ([onEntry] with [DownloadEntry.Download]: the sheet); once started it opens a menu of what
 * can be done with it ([TitleDownloads.menu]), each done by [onEntry]. As wide as its words, or as [modifier] makes it.
 */
@Composable
fun TitleDownloadButton(download: TitleDownload?, onEntry: (DownloadEntry) -> Unit, modifier: Modifier = Modifier) {
    val words = TitleDownloads.button(download)
    var menu by remember { mutableStateOf(false) }
    Box(modifier, propagateMinConstraints = true) {
        OutlinedButton(
            onClick = { if (download == null) onEntry(DownloadEntry.Download) else menu = true },
            modifier = Modifier.heightIn(min = 48.dp).focusOutline(RoundedCornerShape(24.dp), fill = false),
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, colors.outline),
            colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.Transparent, contentColor = colors.text),
            contentPadding = PaddingValues(start = 16.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
        ) {
            DownloadMarkIcon(words.mark)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f, fill = false)) {
                Text(words.text, style = AppType.body)
                words.second?.let { Text(it.text, style = AppType.small, color = it.tone.color()) }
            }
        }
        if (download != null) {
            TvMenu(menu, onDismiss = { menu = false }) { first ->
                TitleDownloads.menu(download).forEachIndexed { i, entry ->
                    TvMenuItem(entry.label, if (i == 0) first else Modifier) {
                        menu = false
                        onEntry(entry)
                    }
                }
            }
        }
    }
}

/**
 * The Undo bar of a page's download entries ([actions]), at its foot while its offer lasts (L379): TalkBack's time where
 * longer. [onRoom] is told how much of the page's foot it covers, none once it has gone, so the page's list can end above
 * it, its last row and footnote scrolled clear of it at any font size.
 */
@Composable
fun DownloadUndoBar(actions: TitleDownloadActions, modifier: Modifier = Modifier, onRoom: (Dp) -> Unit = {}) {
    val offer by actions.offer.collectAsStateWithLifecycle()
    val keepFor = undoTimeout(actions.undoMillis)
    val density = LocalDensity.current
    val o = offer
    if (o == null) {
        LaunchedEffect(Unit) { onRoom(0.dp) }
        return
    }
    UndoBar(o.message, null, onUndo = actions::undo, modifier = modifier.onSizeChanged { onRoom(with(density) { it.height.toDp() }) })
    LaunchedEffect(o.id) { actions.shown(keepFor) }
}

/**
 * What a page does with an entry of its download's: Download opens its sheet ([onDownload]), Open Downloads the page
 * ([onDownloads]); the rest are [actions]', a Remove's files going once the Undo bar's own time has gone.
 */
@Composable
fun downloadEntryHandler(actions: TitleDownloadActions, onDownload: () -> Unit, onDownloads: (() -> Unit)?): (DownloadEntry, TitleDownload?) -> Unit {
    val keepFor = undoTimeout(actions.undoMillis)
    return { entry, download ->
        when (entry) {
            DownloadEntry.Download -> onDownload()
            DownloadEntry.OpenDownloads -> onDownloads?.invoke()
            else -> download?.let { actions.take(entry, it, keepFor) }
        }
    }
}

/**
 * Search's movie card's Download (DlPhoneSearchMovie): the Movie page's button for [movie], whose sheet [onSheet] opens;
 * null where it doesn't show ([TitleDownloads.offered]), so the card is as it was.
 */
@Composable
fun movieDownloadSlot(
    movie: MovieTarget,
    posterPath: String?,
    actions: TitleDownloadActions,
    onSheet: (DownloadRequest) -> Unit,
    onDownloads: (() -> Unit)?,
): (@Composable (Modifier) -> Unit)? {
    if (!downloadsShown()) return null
    val container = LocalContext.current.container
    val download by remember(container, movie.tmdbId) { movieDownload(container, movie.tmdbId) }.collectAsStateWithLifecycle(null)
    val source by remember(container) { downloadSource(container) }.collectAsStateWithLifecycle(false)
    if (!TitleDownloads.offered(shown = true, source, download)) return null
    val handle = downloadEntryHandler(actions, onDownload = { onSheet(DownloadRequest.Movie(movie, posterPath)) }, onDownloads)
    return { modifier -> TitleDownloadButton(download, { entry -> handle(entry, download) }, modifier) }
}

/** The download entries of a page with no model of its own (Search): their runner's calls and the Undo bar's offer. */
class DownloadEntriesViewModel(container: AppContainer) : ViewModel() {
    val actions = TitleDownloadActions(TitleDownloadActions.Deps.of(container), viewModelScope, container.appScope)
}

/** A test's view of a download's mark: which it is. Nothing for TalkBack, which hears the row's state or the button's words. */
val DownloadMarkLook = SemanticsPropertyKey<DownloadMark>("DownloadMarkLook")

/** The mark's size, as drawn: 18 dp, whatever the font size. */
private val MARK = 18.dp

/** ⚠ in the mark's box, at 15 px as drawn. */
private val WARN_GLYPH = 15.dp
