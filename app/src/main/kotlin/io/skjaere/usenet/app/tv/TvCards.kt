package io.skjaere.usenet.app.tv

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.skjaere.usenet.app.collection.HIGHLIGHT_FADE_MS
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.EdgeScrolling
import io.skjaere.usenet.app.ui.LocalFocusStyle
import io.skjaere.usenet.app.ui.SectionHeader
import io.skjaere.usenet.app.ui.TvDimens
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.focusScale
import io.skjaere.usenet.app.ui.showsFocus
import io.skjaere.usenet.metadata.TmdbImages

/*
 * The TV's cards, for its rows and grids: a title's poster (PosterCard), an episode's or a film's still (StillCard),
 * a row's way on to the rest (MoreCard), a row of them (CardRow) and a page's section (TvSection). TV-3's Home, and
 * TV-4's collection grid, build on them; Search's Titles strip keeps its compact cards. A focused card grows
 * (FocusStyle.cardScale) inside a ring in the accent, and its name turns the text's colour and may take a second
 * line; without artwork a card is a tinted tile with the name on it, never an empty box. NEW sits on the picture's
 * top right, how much was watched along its foot. OK on a card does what its [clickLabel] says to TalkBack: Open by
 * default, Play for one that plays.
 */

/** The colour of a poster's line under its title: the muted text's, or the collection's for in progress and watched. */
enum class PosterTone { Muted, Accent, Good }

/**
 * A title's poster, 2:3, its name and [subtitle] under it. OK opens it ([onClick]).
 *
 * What a grid's posters add (TV-4's L4; TV Home's cards have none of it): a long OK ([onLongClick]); what TalkBack hears
 * of the whole card ([spoken], in place of its words), with [actions]; select mode's mark at the picture's top left
 * ([selected], null outside select mode), which TalkBack hears as a checkbox; a jump's highlight ([highlighted]), an
 * accent ring fading over 2 s, "Just added" to TalkBack when it was ([justAdded]); the line's [tone]; without its
 * picture no name on the tile ([nameOnTile] false), the one under it being enough; and a line that must show whole
 * (a mark, not a detail) wrapping to [subtitleLines] at a large text size rather than ending in an ellipsis.
 */
@Composable
fun PosterCard(
    title: String,
    posterPath: String?,
    showArtwork: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    new: Boolean = false,
    progress: Float? = null,
    clickLabel: String = OPEN,
    onLongClick: (() -> Unit)? = null,
    spoken: String? = null,
    actions: List<CustomAccessibilityAction> = emptyList(),
    selected: Boolean? = null,
    highlighted: Boolean = false,
    justAdded: Boolean = false,
    tone: PosterTone = PosterTone.Muted,
    nameOnTile: Boolean = true,
    subtitleLines: Int = 1,
) {
    val said: (SemanticsPropertyReceiver.() -> Unit)? = spoken?.let { words ->
        {
            contentDescription = words
            when {
                selected != null -> {
                    role = Role.Checkbox
                    this.selected = selected
                    stateDescription = if (selected) SELECTED else NOT_SELECTED
                }
                justAdded -> stateDescription = JUST_ADDED
            }
            if (actions.isNotEmpty()) customActions = actions
        }
    }
    val line = when (tone) {
        PosterTone.Muted -> colors.muted
        PosterTone.Accent -> colors.accent
        PosterTone.Good -> colors.good
    }
    Card(title, subtitle, TvDimens.poster, onClick, modifier, clickLabel, onLongClick, said, line, highlighted, subtitleLines) { lit ->
        if (showArtwork && posterPath != null) {
            AsyncImage(TmdbImages.poster(posterPath, POSTER_PX), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        } else if (nameOnTile) {
            Tile(title, lit)
        }
        Marks(new, progress, inset = 6.dp)
        selected?.let { SelectMark(it) }
    }
}

/**
 * An episode's or a film's still, 16:9, with how far it was watched ([progress], 0 to 1) along its foot. Without the
 * still, the title's poster ([posterPath]) letterboxed on the surface: a show's own backdrop isn't always at hand. A long
 * OK does [onLongClick]'s (TV Home's Up next menu), and TalkBack has the same as the card's [actions].
 */
@Composable
fun StillCard(
    title: String,
    stillPath: String?,
    showArtwork: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    progress: Float? = null,
    posterPath: String? = null,
    new: Boolean = false,
    clickLabel: String = OPEN,
    onLongClick: (() -> Unit)? = null,
    actions: List<CustomAccessibilityAction> = emptyList(),
) {
    val withActions = if (actions.isEmpty()) modifier else modifier.semantics { customActions = actions }
    Card(title, subtitle, TvDimens.landscape, onClick, withActions, clickLabel, onLongClick) { lit ->
        when {
            showArtwork && stillPath != null ->
                AsyncImage(TmdbImages.still(stillPath, STILL_PX), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            showArtwork && posterPath != null -> {
                Box(Modifier.matchParentSize().background(colors.surface))
                AsyncImage(TmdbImages.poster(posterPath, POSTER_PX), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.matchParentSize())
            }
            else -> Tile(title, lit)
        }
        Marks(new, progress, inset = 8.dp)
    }
}

/** What a card's picture says on it: NEW at its top right, [inset] in; how much was watched along its foot. */
@Composable
private fun BoxScope.Marks(new: Boolean, progress: Float?, inset: Dp) {
    if (new) {
        Text(
            NEW, style = AppType.tag, color = colors.accent,
            modifier = Modifier.align(Alignment.TopEnd).padding(top = inset, end = inset).background(colors.accentTint, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 1.dp),
        )
    }
    progress?.let { p ->
        LinearProgressIndicator(
            progress = { p }, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
            color = colors.accent, trackColor = colors.chip.copy(alpha = 0.8f), drawStopIndicator = {}, gapSize = 0.dp,
        )
    }
}

/** Select mode's 32 dp mark at a poster's top left: the accent with a check when selected, a ring when not; NEW keeps the top right. */
@Composable
private fun BoxScope.SelectMark(selected: Boolean) {
    Box(
        Modifier.align(Alignment.TopStart).padding(6.dp).size(32.dp).clip(CircleShape)
            .then(if (selected) Modifier.background(colors.accent) else Modifier.border(2.dp, colors.markOutline, CircleShape)),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = colors.onAccent, modifier = Modifier.size(18.dp))
    }
}

/**
 * The end of a row that shows only some of what there is: "[label] ›", a card of [size] to take the focus like the rest.
 * [spoken] is what TalkBack hears in place of its words, where several rows each end in one (which list it opens).
 */
@Composable
fun MoreCard(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: DpSize = TvDimens.poster, spoken: String? = null) {
    val said: (SemanticsPropertyReceiver.() -> Unit)? = spoken?.let { words -> { contentDescription = words } }
    Card(null, null, size, onClick, modifier, OPEN, said = said) { lit ->
        Text(
            "$label ›", style = AppType.bodyStrong, color = if (lit) colors.text else colors.textSecondary, textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center).padding(8.dp),
        )
    }
}

/**
 * A row of cards, scrolled only as far as the focused one needs ([EdgeScrolling]), [end] (a [MoreCard]) after them. The
 * padding leaves room at its ends for a focused card's growth and ring. Which card had the focus, for the way back from
 * the rail or from a page, is the screen's to keep (TV Home's keys): Compose's focus restorer would turn a request for a
 * card into one for the card it kept.
 */
@Composable
fun <T> CardRow(
    items: List<T>,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    gutter: Dp = TvDimens.posterGutter,
    end: (@Composable LazyItemScope.() -> Unit)? = null,
    card: @Composable LazyItemScope.(T) -> Unit,
) {
    EdgeScrolling {
        LazyRow(
            modifier.fillMaxWidth(), state = state,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(gutter),
        ) {
            items(items, key = key) { card(it) }
            end?.let { item(key = END_KEY, content = it) }
        }
    }
}

/** The key of a row's [CardRow] end, which no card's is. */
private const val END_KEY = "card-row-end"

/** A section of a TV page: its header, as the phone's in capitals, and what it holds. */
@Composable
fun TvSection(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth()) {
        // Nothing above it: the sections are apart by their list's gap, the first on the safe area's line.
        SectionHeader(title, top = 0.dp)
        content()
    }
}

/**
 * A card: its picture of [size], which grows and takes the ring while the card shows the focus, then its [title]
 * (the text's colour then, on up to two lines; muted on one otherwise, the room for two kept so the row doesn't
 * move) and [subtitle], in the mockups' sizes. One element for the remote and TalkBack, named by its words, or by
 * what [said] sets in their place; OK does what [clickLabel] says, a long OK [onLongClick]'s. [highlighted]: the
 * picture's accent ring, fading.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Card(
    title: String?,
    subtitle: String?,
    size: DpSize,
    onClick: () -> Unit,
    modifier: Modifier,
    clickLabel: String,
    onLongClick: (() -> Unit)? = null,
    said: (SemanticsPropertyReceiver.() -> Unit)? = null,
    line: Color = colors.muted,
    highlighted: Boolean = false,
    subtitleLines: Int = 1,
    art: @Composable BoxScope.(lit: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val lit = interaction.showsFocus()
    val click = if (onLongClick == null) Modifier.clickable(interaction, indication = null, onClickLabel = clickLabel, onClick = onClick)
    else Modifier.combinedClickable(interaction, indication = null, onClickLabel = clickLabel, onLongClick = onLongClick, onClick = onClick)
    Column(
        // The click's semantics stay with the card's own words set in place of its texts'.
        modifier.width(size.width).then(click).then(said?.let { Modifier.clearAndSetSemantics(it) } ?: Modifier),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Art(interaction, lit, size, highlighted, art)
        title?.let {
            val style = AppType.bodyStrong
            Text(
                it, style = style, color = if (lit) colors.text else colors.textSecondary, maxLines = if (lit) 2 else 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.heightIn(min = twoLines(style)),
            )
        }
        subtitle?.let { Text(it, style = AppType.secondary, color = line, maxLines = subtitleLines, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun Art(interaction: InteractionSource, lit: Boolean, size: DpSize, highlighted: Boolean, art: @Composable BoxScope.(lit: Boolean) -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    // A jump's highlight: the ring in the accent, gone over slice 12's 2 s; nothing over the artwork.
    val glow = remember { Animatable(0f) }
    LaunchedEffect(highlighted) {
        if (highlighted) {
            glow.snapTo(1f)
            glow.animateTo(0f, tween(HIGHLIGHT_FADE_MS))
        }
    }
    val accent = colors.accent
    val outline = LocalFocusStyle.current.outline
    Box(
        Modifier.size(size).focusScale(interaction, { it.cardScale }, { it.cardPressedScale }).clip(shape).background(TILE)
            .border(outline, if (lit) colors.accent else Color.Transparent, shape)
            .drawWithContent {
                drawContent()
                // Read as it is drawn: the fade redraws the card, it doesn't compose it again.
                val a = glow.value
                if (!lit && a > 0f) {
                    val w = outline.toPx()
                    drawRoundRect(
                        accent.copy(alpha = a), topLeft = Offset(w / 2, w / 2), size = Size(this.size.width - w, this.size.height - w),
                        cornerRadius = CornerRadius(8.dp.toPx() - w / 2), style = Stroke(w),
                    )
                }
            },
    ) { art(lit) }
}

/** No artwork: the name on a tinted tile. */
@Composable
private fun BoxScope.Tile(title: String, lit: Boolean) {
    Text(
        title, style = AppType.cardTitle.copy(fontSize = AppType.body.fontSize), color = if (lit) colors.text else colors.textSecondary,
        textAlign = TextAlign.Center, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.Center).padding(8.dp),
    )
}

@Composable
private fun twoLines(style: TextStyle): Dp = with(LocalDensity.current) { (style.lineHeight * 2).toDp() }

/** The tinted tile under a card's artwork, and in its place without. */
private val TILE = Color(0xFF2E4A5C)

/** A 98 dp poster at 2x, and a 176 dp still: the image sizes TMDB has nearest above them. */
private const val POSTER_PX = 185
private const val STILL_PX = 300

/** What OK on a card does, said to TalkBack, unless the card says otherwise. */
private const val OPEN = "Open"
private const val NEW = "NEW"

/** What TalkBack hears of a poster in select mode, and of one a jump just added. */
const val SELECTED = "Selected"
const val NOT_SELECTED = "Not selected"
const val JUST_ADDED = "Just added"
