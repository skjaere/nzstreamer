package io.skjaere.usenet.app.collection

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import io.skjaere.usenet.app.tv.TvChip
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.EdgeScrolling
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.ui.LocalFormFactor
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.metadata.Episode
import io.skjaere.usenet.metadata.EpisodeRef
import io.skjaere.usenet.metadata.NextUp
import io.skjaere.usenet.metadata.SeasonSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A season's chip: "Specials", "S1 ✓" once watched, "Season 2 · 4/8" selected or begun, else "S3". */
internal fun seasonChipLabel(season: SeasonSummary, watched: Int, selected: Boolean): String = when {
    season.isSpecials -> "Specials"
    season.episodeCount > 0 && watched >= season.episodeCount -> "S${season.number} ✓"
    selected || watched > 0 -> "Season ${season.number} · $watched/${season.episodeCount}"
    else -> "S${season.number}"
}

/** The seasons in their chips' order: the specials last. */
internal fun chipOrder(seasons: List<SeasonSummary>): List<SeasonSummary> = seasons.sortedBy { if (it.isSpecials) Int.MAX_VALUE else it.number }

/**
 * The Show page's seasons, one chip each, specials last ([chipOrder]); OK or a tap selects one ([onSelect]). Sideways,
 * they scroll only as far as a chip needs to show: a TV's list pivot is for the list. [modifier] goes on the row (a TV's
 * focus memory), [chipModifier] on each chip (its focus requester); [state] is the row's place, for a chip off its end
 * to be brought into view. On a TV a chip is a pill: the selected season's tinted in the
 * accent, a focused one turned over, the selected one then with a 2 dp amber line under its words; the focus on each
 * ([onFocused]) and its leaving the row ([onLeft]) are reported, for [SeasonDwell]. Taller with a larger text size, so
 * their words aren't cut off.
 */
@Composable
internal fun SeasonChips(
    seasons: List<SeasonSummary>,
    selected: Int?,
    watched: (season: Int) -> Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
    state: LazyListState = rememberLazyListState(),
    chipModifier: (season: Int) -> Modifier = { Modifier },
    onFocused: (Int) -> Unit = {},
    onLeft: () -> Unit = {},
) {
    val tv = LocalFormFactor.current == FormFactor.Tv
    var hadFocus by remember { mutableStateOf(false) }
    EdgeScrolling {
        LazyRow(
            modifier.onFocusChanged {
                if (hadFocus && !it.hasFocus) onLeft()
                hadFocus = it.hasFocus
            },
            state = state, contentPadding = contentPadding, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(chipOrder(seasons), key = { it.number }) { s ->
                val isSel = s.number == selected
                // On a TV the dwell selects the chip the focus rests on: its words don't change with that, or it would
                // grow under the focus and the chips after it move.
                val label = seasonChipLabel(s, watched(s.number), isSel && !tv)
                val chip = chipModifier(s.number).onFocusChanged { if (it.isFocused) onFocused(s.number) }
                if (tv) {
                    TvChip(label, isSel, chip) { onSelect(s.number) }
                } else {
                    Text(
                        label, style = if (isSel) AppType.bodyStrong.copy(fontSize = AppType.secondary.fontSize) else AppType.secondary,
                        color = if (isSel) colors.accent else colors.textSecondary,
                        modifier = chip.heightIn(min = 34.dp).clip(RoundedCornerShape(8.dp))
                            .then(if (isSel) Modifier.background(colors.accentTint) else Modifier.border(1.dp, colors.chip, RoundedCornerShape(8.dp)))
                            .clickable { onSelect(s.number) }.padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
        }
    }
}

/**
 * A TV's season chips select the season the focus settles on ([focused]), [delayMs] after it came: moving along the
 * chips loads only the season the user stops at, not each one passed (TV-3 D6). OK selects at once
 * ([pressed]); leaving the chips ([left]) drops a pending selection, so the episodes don't change under the focus just
 * after Down. The [selected] season's chip asks for nothing; OK on it selects it again, which retries one that failed.
 */
class SeasonDwell(private val scope: CoroutineScope, private val selected: () -> Int?, private val delayMs: Long = DWELL_MS, private val select: (Int) -> Unit) {
    private var pending: Job? = null

    fun focused(number: Int) {
        pending?.cancel()
        pending = if (number == selected()) {
            null
        } else {
            scope.launch {
                delay(delayMs)
                select(number)
            }
        }
    }

    fun left() {
        pending?.cancel()
        pending = null
    }

    fun pressed(number: Int) {
        left()
        select(number)
    }

    companion object {
        const val DWELL_MS = 300L
    }
}

/**
 * Where Down from a TV's season chips enters the shown season's [episodes] (TV-3 D7): the next-up episode
 * when the season holds it; else, up to date, the episode the show returns with; else the first not [watched]; else
 * the first (0 too for a season with none).
 */
internal fun episodeEntry(episodes: List<Episode>, nextUp: NextUp, watched: (Episode) -> Boolean): Int {
    fun at(ref: EpisodeRef?) = ref?.let { r -> episodes.indexOfFirst { it.season == r.season && it.episode == r.episode } } ?: -1
    val target = when (nextUp) {
        is NextUp.Resume -> at(nextUp.episode)
        is NextUp.Play -> at(nextUp.episode)
        is NextUp.UpToDate -> at(nextUp.returningEpisode)
    }
    if (target >= 0) return target
    return episodes.indexOfFirst { !watched(it) }.coerceAtLeast(0)
}
