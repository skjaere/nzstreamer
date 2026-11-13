package io.skjaere.usenet.app.collection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.skjaere.usenet.app.search.Poster
import io.skjaere.usenet.app.tv.tvFullWidth
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.play.OkGuard
import io.skjaere.usenet.app.play.pageOkGuard
import io.skjaere.usenet.app.ui.PaneMemory
import io.skjaere.usenet.app.ui.focusPane
import io.skjaere.usenet.app.ui.paneElement
import io.skjaere.usenet.app.ui.PrimaryButton
import io.skjaere.usenet.app.ui.ReturnFocus
import io.skjaere.usenet.app.ui.SecondaryButton
import io.skjaere.usenet.app.ui.TvDimens
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.rememberInitialFocus
import io.skjaere.usenet.app.ui.tryFocus

/**
 * The Movie page on a TV (TV-3 D4, D8, D10, D11): two panes across the page's 864 dp. On
 * the left the poster beside the title (the page's heading), its year and length and how far it was watched, then Play
 * (Resume says where it goes on from), Choose release and the collection toggle, and the note of a movie still to come;
 * on the right the overview, Mark watched or unwatched, and the footnote. The focus starts on Play, and comes back from
 * Choose release's list on Choose release ([focus]); Left and Right go back to where it was in each pane.
 */
@Composable
internal fun TvMovieContent(ui: MoviePageUi, actions: MoviePageActions, focus: ReturnFocus, okGuard: OkGuard) {
    val row = ui.row
    val text = ui.text
    if (row == null || text == null) {
        Column(Modifier.fillMaxSize().pageOkGuard(okGuard)) {
            ui.error?.let { PageLoadFailure(MOVIE_LOAD_FAILED + it, actions.retry) } ?: CircularProgressIndicator(Modifier.padding(24.dp), color = colors.accent)
        }
        return
    }
    val panes = remember(focus) { PaneMemory(focus) }
    val initial = rememberInitialFocus()
    LaunchedEffect(Unit) {
        if (focus.restore()) return@LaunchedEffect
        initial.awaitTurn()
        withFrameNanos {}
        focus.requester(MOVIE_PLAY).tryFocus()
    }
    Row(Modifier.fillMaxHeight().tvFullWidth().pageOkGuard(okGuard), horizontalArrangement = Arrangement.spacedBy(TvDimens.paneGap)) {
        // Taller than the page (a long title at a larger text size, the play bar under the page), the pane scrolls.
        TvPagePane(Modifier.width(TvDimens.pagePane).fillMaxHeight().focusPane(panes, LEFT), gap = 16.dp, top = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Poster(row.posterPath, ui.artwork, TvDimens.pagePoster.width.value.toInt(), TvDimens.pagePoster.height.value.toInt(), imageWidth = 342)
                    Column(Modifier.weight(1f).padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        // Two lines at most, beside the poster: a long title doesn't push the buttons down the page.
                        Text(row.text.name, style = AppType.hero, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                        if (row.meta.isNotEmpty()) Text(row.meta, style = AppType.secondary, color = colors.muted)
                        Text(text.line, style = AppType.secondary, color = row.statusColor(), modifier = Modifier.padding(top = 6.dp))
                        row.progressFraction?.let { fraction ->
                            LinearProgressIndicator(
                                progress = { fraction }, modifier = Modifier.fillMaxWidth().height(4.dp),
                                color = colors.accent, trackColor = colors.chip, drawStopIndicator = {},
                            )
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PrimaryButton(
                        tvMoviePlayLabel(row, text, ui.watch), actions.play, Modifier.fillMaxWidth().paneElement(panes, LEFT, MOVIE_PLAY),
                        leading = { Icon(Icons.Filled.PlayArrow, null, Modifier.padding(end = 6.dp)) },
                    )
                    SecondaryButton("Choose release", actions.chooseRelease, Modifier.fillMaxWidth().paneElement(panes, LEFT, MOVIE_CHOOSE))
                    SecondaryButton(if (ui.collected) "In collection ✓" else "+ Collection", actions.toggleCollection, Modifier.fillMaxWidth().paneElement(panes, LEFT, COLLECTION))
                }
                text.note?.let { Text(it, style = AppType.small, color = colors.muted) }
            }
        })

        Column(Modifier.weight(1f).fillMaxHeight().focusPane(panes, RIGHT), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                when {
                    ui.overview != null -> {
                        Text(OVERVIEW.uppercase(), style = AppType.section, color = colors.muted)
                        // The room left: the overview gives way, line by line, to a larger text size.
                        Text(
                            ui.overview, style = AppType.body, color = colors.textSecondary, maxLines = OVERVIEW_LINES, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    ui.error != null -> Text(DETAILS_LOAD_FAILED + ui.error, style = AppType.secondary, color = colors.textSecondary)
                }
                SecondaryButton(if (ui.watched) "Mark unwatched" else "Mark watched", { actions.mark(!ui.watched) }, Modifier.paneElement(panes, RIGHT, MARK))
            }
            Text(MOVIE_FOOTNOTE, style = AppType.small, color = colors.muted, modifier = Modifier.widthIn(max = FOOTNOTE_WIDTH))
        }
    }
}

private const val OVERVIEW = "Overview"
private const val OVERVIEW_LINES = 8
private const val COLLECTION = "collection"
private const val MARK = "mark"

/** The panes, for [PaneMemory]. */
private const val LEFT = "left"
private const val RIGHT = "right"

/** The footnote's lines, as the mockup has them: not across the whole pane. */
private val FOOTNOTE_WIDTH = 420.dp
