package io.skjaere.usenet.app.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.skjaere.usenet.app.tv.PosterTone
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.PrimaryButton
import io.skjaere.usenet.app.ui.SecondaryButton
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.focusOutline
import io.skjaere.usenet.discover.Genre
import io.skjaere.usenet.metadata.MediaType
import io.skjaere.usenet.metadata.TmdbImages
import java.time.Duration

/*
 * The phone's pieces of Discover (the DcPhone mockups): the switch, the genre chips, a poster card and the cards that
 * stand in for a list (no key, a key refused, TMDB failing, nothing matching).
 */

/**
 * Shows | Movies: the selected side tinted in the accent. Each side is a 48 dp target, drawn 32 dp tall, on a track drawn
 * as the mockup's 38 dp pill around them: drawn behind, so the targets reach past it rather than stretch it.
 */
@Composable
internal fun PhoneSideSwitch(side: MediaType, onSide: (MediaType) -> Unit) {
    val track = colors.surface
    Row(
        Modifier.selectableGroup().drawBehind {
            val height = TRACK_HEIGHT.toPx()
            drawRoundRect(track, Offset(0f, (size.height - height) / 2), Size(size.width, height), CornerRadius(height / 2))
        }.padding(horizontal = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(MediaType.Tv, MediaType.Movie).forEach { s ->
            val on = s == side
            Box(
                Modifier.minimumInteractiveComponentSize().focusOutline(RoundedCornerShape(16.dp), fill = false)
                    .selectable(on, role = Role.Tab) { onSide(s) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    sideName(s), style = if (on) AppType.bodyStrong else AppType.body, color = if (on) colors.accent else colors.textSecondary, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) colors.accentTint else Color.Transparent)
                        .heightIn(min = 32.dp).padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** All genres, then the side's, sideways: the one picked tinted in the accent. Each a 48 dp target, drawn 32 dp tall. */
@Composable
internal fun PhoneGenreChips(genres: List<Genre>, picked: Int?, onPick: (Int?) -> Unit) {
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 4.dp)) {
        item(key = "all") { PhoneChip(ALL_GENRES, picked == null) { onPick(null) } }
        items(genres, key = { it.id }) { genre -> PhoneChip(genre.name, genre.id == picked) { onPick(genre.id) } }
    }
}

@Composable
private fun PhoneChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(Modifier.heightIn(min = 48.dp).focusOutline(shape, fill = false).selectable(selected, role = Role.RadioButton, onClick = onClick), contentAlignment = Alignment.Center) {
        Text(
            label, style = if (selected) AppType.bodyStrong.copy(fontSize = AppType.secondary.fontSize) else AppType.secondary,
            color = if (selected) colors.accent else colors.textSecondary, maxLines = 1,
            modifier = Modifier.heightIn(min = 32.dp).clip(shape)
                .then(if (selected) Modifier.background(colors.accentTint) else Modifier.border(1.dp, colors.chip, shape))
                .padding(horizontal = 14.dp, vertical = 7.dp),
        )
    }
}

/**
 * A title's card on a phone, as wide as [modifier] makes it: its poster (2:3), its name and its line ("2021", or
 * "✓ In collection" in the Good tone). Without artwork, its name on the tile. One element for TalkBack: [DiscoverCard.spoken].
 */
@Composable
internal fun DiscoverPoster(card: DiscoverCard, artwork: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier.focusOutline(RoundedCornerShape(10.dp), fill = false)
            .clickable(onClickLabel = OPEN, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = card.spoken },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp)).background(TILE)) {
            if (artwork && card.title.posterPath != null) {
                AsyncImage(TmdbImages.poster(card.title.posterPath, PHONE_POSTER_PX), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            } else {
                Text(
                    card.title.title, style = AppType.secondary, color = colors.textSecondary, textAlign = TextAlign.Center, maxLines = 4,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.Center).padding(8.dp),
                )
            }
        }
        Text(card.title.title, style = AppType.bodyStrong.copy(fontSize = 13.sp), color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        card.line?.let {
            Text(it, style = AppType.small, color = if (card.tone == PosterTone.Good) colors.good else colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A card in a list's place: [title], [line] under it, and its one way on ([button]: primary for the key's, else secondary). */
@Composable
internal fun PhoneStateCard(title: String, line: String?, button: String, primary: Boolean, onClick: () -> Unit) {
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 18.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surface)
            .padding(horizontal = 18.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = AppType.cardTitle, color = colors.text)
        line?.let { Text(it, style = AppType.body, color = colors.textSecondary) }
        val at = Modifier.padding(top = 4.dp).focusOutline(RoundedCornerShape(24.dp), color = colors.text, fill = false)
        if (primary) PrimaryButton(button, onClick, at) else SecondaryButton(button, onClick, at)
    }
}

/** "Offline · lists from 3 h ago". */
@Composable
internal fun OfflineLine(age: Duration, modifier: Modifier = Modifier) {
    Text(offlineLine(age), style = AppType.small, color = colors.muted, modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp))
}

/** The switch's track: 3 dp round its 32 dp sides. */
private val TRACK_HEIGHT = 38.dp

/** The tile under a poster, and in its place without one: the TV cards' tint. */
private val TILE = Color(0xFF2E4A5C)

/** A phone's 104 to 120 dp poster at its density: TMDB's size nearest above. */
private const val PHONE_POSTER_PX = 342

/** What OK on a card does, said to TalkBack. */
internal const val OPEN = "Open"
