package io.skjaere.usenet.app.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import io.skjaere.usenet.app.play.SlowStream
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.ChipKind
import io.skjaere.usenet.app.ui.ChipRow
import io.skjaere.usenet.app.ui.InfoChip
import io.skjaere.usenet.app.ui.PrimaryButton
import io.skjaere.usenet.app.ui.SecondaryButton
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.focusOutline
import io.skjaere.usenet.core.formatSize
import io.skjaere.usenet.metadata.TmdbImages
import io.skjaere.usenet.releases.RankedRelease
import io.skjaere.usenet.releases.ReleaseReason
import io.skjaere.usenet.releases.Severity
import io.skjaere.usenet.releases.Source
import io.skjaere.usenet.search.RankedItem
import io.skjaere.usenet.search.ResultGroup
import java.time.Duration
import java.time.Instant

fun sourceLabel(s: Source?): String? = when (s) {
    Source.Remux -> "Remux"; Source.BluRay -> "BluRay"; Source.WebDl -> "WEB-DL"; Source.WebRip -> "WEBRip"
    Source.Hdtv -> "HDTV"; Source.Dvd -> "DVD"; Source.Telesync, Source.Cam -> "CAM"; null -> null
}

/** The short chips for a release: "1080p · WEB-DL · 2.1 GB · ≈ 8.0 Mbps". */
fun summaryChips(item: RankedItem): List<String> {
    val p = item.parsed
    return listOfNotNull(
        p.resolution?.let { "${it}p" },
        sourceLabel(p.source),
        p.videoCodec?.takeIf { it.name == "Hevc" || it.name == "Av1" }?.let { it.label + if (p.tenBit) " 10-bit" else "" },
        item.item.sizeBytes.takeIf { it > 0 }?.let(::formatSize),
        bitrateChip(item.ranked),
    )
}

/**
 * The ranker's own estimate of the release's bitrate, from the ranking its list was made with (so the chip and the order
 * agree): "≈ 12 Mbps", "≈ 8.4 Mbps" under 10; null without one (a pack whose season's episodes aren't counted). Rounded
 * half up, as "May buffer: ~N Mbps" and "Above your N Mbps" are; but one above a maximum (the maximum bitrate, or the
 * maximum when casting, L132) that would round to it rounds up instead, so "≈ 20 Mbps" never sits beside "Above your 20
 * Mbps" or "Above your 20 Mbps for casting".
 */
fun bitrateChip(ranked: RankedRelease): String? {
    val bits = ranked.estimatedBitrate?.takeIf { it > 0 } ?: return null
    // The highest maximum it is above: a chip above that one is above any other beside it.
    val max = ranked.reasons.mapNotNull {
        when (it) {
            is ReleaseReason.AboveMaxBitrate -> it.megabitsPerSecond
            is ReleaseReason.AboveCastMax -> it.megabitsPerSecond
            else -> null
        }
    }.maxOrNull()
    // In steps of [step] bits per second: the nearest, or up where the nearest would read the maximum it is above.
    fun steps(step: Long): Long {
        val nearest = (bits + step / 2) / step
        return if (max != null && nearest * step <= max * 1_000_000L) (bits + step - 1) / step else nearest
    }
    val tenths = steps(100_000)
    return when {
        tenths == 0L -> "< 0.1 Mbps"
        tenths < 100 -> "≈ %.1f Mbps".format(tenths / 10.0)
        else -> "≈ %d Mbps".format(steps(1_000_000))
    }
}

fun age(posted: Instant?): String? = posted?.let {
    val d = Duration.between(it, Instant.now())
    when {
        d.toDays() >= 365 -> "${d.toDays() / 365} y"
        d.toDays() >= 1 -> "${d.toDays()} day${if (d.toDays() == 1L) "" else "s"}"
        else -> "${d.toHours().coerceAtLeast(1)} h"
    }
}

@Composable
fun Poster(path: String?, showArtwork: Boolean, width: Int, height: Int, modifier: Modifier = Modifier, imageWidth: Int = 185) {
    Box(modifier.size(width.dp, height.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF2E4A5C))) {
        if (showArtwork && path != null) {
            AsyncImage(TmdbImages.poster(path, imageWidth), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        }
    }
}

@Composable
fun PlayCircle(primary: Boolean, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    IconButton(
        onClick = onClick, modifier = modifier.size(48.dp), enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (primary) colors.accent else colors.chip, contentColor = if (primary) colors.onAccent else colors.accent,
            // Greyed as Material's disabled buttons are, from the text colour (Play on a TV not open, PtPhoneWhere B).
            disabledContainerColor = colors.text.copy(alpha = 0.12f), disabledContentColor = colors.text.copy(alpha = 0.38f),
        ),
    ) { Icon(Icons.Filled.PlayArrow, contentDescription = label) }
}

/**
 * One episode / pack / movie row inside a section card: OK, or a tap, on the row plays its best release; "N releases ›"
 * beside it, at its end, opens the list of them. Side by side, not one inside the other, so a remote reaches both (#160).
 * [modifier] goes on the row, [releasesModifier] on its releases (the focus back on what opened the list). [tag]: where its
 * best release plays from, with its "+n", beside the episode where the line has room, else leading its chips ([RowWords];
 * L201, L247; none while no debrid service is on), and TalkBack then reads the row in one go ([groupRowSpoken]).
 */
@Composable
fun GroupRow(
    group: ResultGroup,
    name: String?,
    primary: Boolean,
    onPlay: () -> Unit,
    onReleases: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    releasesModifier: Modifier = Modifier,
    tag: SourceTag? = null,
) {
    val best = group.best
    val title = label ?: group.label
    val sub = name ?: if (group.kind == io.skjaere.usenet.search.GroupKind.SeasonPack) "pack" else null
    val chips = if (best != null) groupChips(best) else listOf(ChipLook("Can't play on this device", ChipKind.Warn))
    Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            // Nothing to play (none of its releases plays here): OK shows them instead.
            modifier.weight(1f).focusOutline().clickable(onClickLabel = if (best != null) "Play" else "Releases") { if (best != null) onPlay() else onReleases() }
                // With a tag, read in one go: the tag and its "+n" as one, wherever it sits (L251).
                .then(if (tag != null) Modifier.semantics { contentDescription = groupRowSpoken(title, sub, tag, chips) } else Modifier)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (best != null) PlayMark(primary)
            RowWords(title, sub, tag, chips, Modifier.weight(1f))
        }
        val releases = "${group.size} release${if (group.size == 1) "" else "s"}"
        Box(
            releasesModifier.heightIn(min = 48.dp).focusOutline(RoundedCornerShape(24.dp))
                // TalkBack hears which group's they are.
                .semantics(mergeDescendants = true) { contentDescription = "$releases for $title" }
                .clickable(role = Role.Button, onClick = onReleases).padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("$releases ›", style = AppType.secondary, color = colors.accent, maxLines = 1)
        }
    }
}

/**
 * What TalkBack reads of a row with a tag (L251): its label and "pack", the tag and its "+n" as one, then its chips as each
 * is read: "S02E05, From Premiumize, also cached on 1 more service, 1080p, …", wherever the tag sits.
 */
fun groupRowSpoken(title: String, sub: String?, tag: SourceTag?, chips: List<ChipLook>): String =
    (listOfNotNull(title, sub, tag?.spoken) + chips.map { it.spoken ?: it.text }).joinToString(", ")

/**
 * A row's words (L247, choice 5; D2PhoneSearchTags): [title] and [sub] ("pack") on the first line, cut only where the
 * title doesn't fit alone; "pack" under it where the two don't fit side by side; [tag] beside them, 8 dp on, where the
 * line has room for all three, else leading the [chips] under them, which wrap as a [ChipRow]'s do. So the episode and
 * "pack" are never cut for the tag.
 */
@Composable
private fun RowWords(title: String, sub: String?, tag: SourceTag?, chips: List<ChipLook>, modifier: Modifier = Modifier) {
    Layout(
        content = {
            Text(title, style = AppType.bodyStrong, color = colors.text, maxLines = 1)
            sub?.let { Text(it, style = AppType.secondary, color = colors.muted, maxLines = 1) }
            tag?.let { SourceTagChip(it) }
            chips.forEach { InfoChip(it.text, it.kind, it.spoken) }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val gap = 8.dp.roundToPx()
        val chipGap = 6.dp.roundToPx()
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else Constraints.Infinity
        fun upTo(room: Int) = Constraints(maxWidth = room.coerceAtLeast(0))
        val titleM = measurables[0]
        val subM = if (sub != null) measurables[1] else null
        val tagM = if (tag != null) measurables[if (sub != null) 2 else 1] else null
        val chipMs = measurables.drop(1 + (if (sub != null) 1 else 0) + (if (tag != null) 1 else 0))
        // Decided by the words' whole widths, before anything is measured to be placed.
        val titleWidth = titleM.maxIntrinsicWidth(Constraints.Infinity)
        val subOnLine = subM == null || titleWidth + gap + subM.maxIntrinsicWidth(Constraints.Infinity) <= width
        val line = titleWidth + (subM?.let { gap + it.maxIntrinsicWidth(Constraints.Infinity) } ?: 0)
        val beside = tagM != null && subOnLine && line + gap + tagM.maxIntrinsicWidth(Constraints.Infinity) <= width
        val titleP = titleM.measure(upTo(width))
        val tagP = tagM?.measure(upTo(if (beside) width - titleP.width - gap else width))
        val subP = subM?.measure(upTo(if (subOnLine) width - titleP.width - gap - (if (beside && tagP != null) tagP.width + gap else 0) else width))
        val lineHeight = maxOf(titleP.height, if (subOnLine) subP?.height ?: 0 else 0, if (beside) tagP?.height ?: 0 else 0)
        // "pack" on a line of its own, where it didn't fit beside the title.
        val wordsHeight = lineHeight + if (subOnLine) 0 else subP?.height ?: 0
        // The chips, the tag first when it leads them, in lines as wide as the row, 6 dp apart each way.
        val flow = listOfNotNull(tagP.takeUnless { beside }) + chipMs.map { it.measure(upTo(width)) }
        val placed = ArrayList<Triple<Placeable, Int, Int>>()
        var x = 0
        var y = wordsHeight + if (flow.isEmpty()) 0 else chipGap
        var rowHeight = 0
        var widest = titleP.width + (if (subOnLine) subP?.let { gap + it.width } ?: 0 else 0) + (if (beside && tagP != null) gap + tagP.width else 0)
        if (!subOnLine) widest = maxOf(widest, subP?.width ?: 0)
        flow.forEach { p ->
            if (x > 0 && x + p.width > width) {
                y += rowHeight + chipGap
                x = 0
                rowHeight = 0
            }
            placed += Triple(p, x, y)
            widest = maxOf(widest, x + p.width)
            x += p.width + chipGap
            rowHeight = maxOf(rowHeight, p.height)
        }
        val height = y + rowHeight
        layout(if (constraints.hasBoundedWidth) width else widest, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            // Bottom-aligned, as the first line always was; the tag beside them in its middle.
            titleP.place(0, lineHeight - titleP.height)
            var end = titleP.width
            if (beside && tagP != null) {
                tagP.place(end + gap, (lineHeight - tagP.height) / 2)
                end += gap + tagP.width
            }
            if (subOnLine) subP?.place(end + gap, lineHeight - subP.height) else subP?.place(0, lineHeight)
            placed.forEach { (p, px, py) -> p.place(px, py) }
        }
    }
}

/** A row's play mark, at its start: the row plays (the first one's in the accent, as the best on offer). */
@Composable
private fun PlayMark(primary: Boolean) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(if (primary) colors.accent else colors.chip),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = if (primary) colors.onAccent else colors.accent, modifier = Modifier.size(20.dp)) }
}

/**
 * Card for a picked TV title: Open goes to its page ([openFocus] on it); "+ Collection" adds it and
 * goes to it, "In collection ›" goes to it ([collectionFocus] on that action).
 */
@Composable
fun TvTitleCard(
    title: TitleInfo,
    showArtwork: Boolean,
    inCollection: Boolean,
    adding: Boolean,
    onAdd: () -> Unit,
    onOpen: () -> Unit,
    onJump: () -> Unit,
    collectionFocus: FocusRequester? = null,
    openFocus: FocusRequester? = null,
) {
    Row(
        Modifier.padding(horizontal = 16.dp).padding(bottom = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surface).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Poster(title.posterPath, showArtwork, 52, 78)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title.name, style = AppType.cardTitle, color = colors.text)
            Text(title.meta, style = AppType.secondary, color = colors.muted)
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SecondaryButton("Open", onOpen, openFocus?.let { Modifier.focusRequester(it) } ?: Modifier)
            CollectionAction(title.name, inCollection, adding, onAdd, onJump, collectionFocus)
        }
    }
}

/**
 * Hero card for a picked movie: backdrop, poster, overview, best release, Play; Open goes to its page, and the collection as on the TV card.
 * [openFocus] and [releasesFocus] go on Open and on the releases button. [tag]: where the best release plays from, first
 * among its chips (L201; none while no debrid service is on).
 */
@Composable
fun MovieHeroCard(
    title: TitleInfo,
    showArtwork: Boolean,
    group: ResultGroup?,
    inCollection: Boolean,
    adding: Boolean,
    onPlay: () -> Unit,
    onReleases: () -> Unit,
    onAdd: () -> Unit,
    onOpen: () -> Unit,
    onJump: () -> Unit,
    collectionFocus: FocusRequester? = null,
    openFocus: FocusRequester? = null,
    releasesFocus: FocusRequester? = null,
    tag: SourceTag? = null,
) {
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(colors.surface)) {
        Box(Modifier.fillMaxWidth().height(110.dp).background(Brush.linearGradient(listOf(Color(0xFF4A3420), colors.surface)))) {
            if (showArtwork && title.backdropPath != null) {
                AsyncImage(TmdbImages.backdrop(title.backdropPath), null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                Box(Modifier.matchParentSize().background(Brush.verticalGradient(listOf(Color.Transparent, colors.surface))))
            }
        }
        Row(Modifier.padding(horizontal = 14.dp).padding(top = 0.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
            Poster(title.posterPath, showArtwork, 72, 108, Modifier.padding(top = 0.dp))
            Column(Modifier.weight(1f).padding(bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title.name, style = AppType.cardTitle, color = colors.text)
                Text(title.meta, style = AppType.secondary, color = colors.muted)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SecondaryButton("Open", onOpen, openFocus?.let { Modifier.focusRequester(it) } ?: Modifier)
                CollectionAction(title.name, inCollection, adding, onAdd, onJump, collectionFocus)
            }
        }
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            title.overview?.let { Text(it, style = AppType.secondary, color = colors.textSecondary, maxLines = 4) }
            group?.best?.let { best ->
                ChipRow {
                    // Where the best release plays from leads its chips (L201).
                    tag?.let { SourceTagChip(it) }
                    heroChips(best).forEach { InfoChip(it.text, it.kind, it.spoken) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    PrimaryButton("Play", onPlay, Modifier.weight(1f), leading = { Icon(Icons.Filled.PlayArrow, null, Modifier.padding(end = 6.dp)) })
                    SecondaryButton("${group.size} releases", onReleases, releasesFocus?.let { Modifier.focusRequester(it) } ?: Modifier)
                }
            }
        }
    }
}

/** A title card's "+ Collection" ("Adding…" while it is written), then "In collection ›", which goes to it. */
@Composable
private fun CollectionAction(name: String, inCollection: Boolean, adding: Boolean, onAdd: () -> Unit, onJump: () -> Unit, focus: FocusRequester?) {
    CollectionTarget(name, inCollection, adding, onAdd, onJump, focus) {
        Text(
            when {
                inCollection -> "In collection ›"
                adding -> "Adding…"
                else -> "+ Collection"
            },
            style = AppType.secondary, color = if (adding && !inCollection) colors.muted else colors.accent, maxLines = 1,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
}

/** A suggestion's "+ Collection" (DenseSuggest's amber pill; "Adding…" while it is written), then "In collection ›", which goes to it. */
@Composable
fun SuggestionCollectionAction(name: String, inCollection: Boolean, adding: Boolean, onAdd: () -> Unit, onJump: () -> Unit, focus: FocusRequester? = null) {
    CollectionTarget(name, inCollection, adding, onAdd, onJump, focus, outline = if (inCollection) null else colors.text) {
        if (inCollection) {
            Text("In collection ›", style = AppType.secondary, color = colors.accent, maxLines = 1, modifier = Modifier.padding(horizontal = 12.dp))
        } else {
            Box(Modifier.heightIn(min = 40.dp).background(if (adding) colors.chip else colors.accent, RoundedCornerShape(20.dp)).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (adding) "Adding…" else "+ Collection", style = AppType.secondary.copy(fontWeight = FontWeight.SemiBold),
                    color = if (adding) colors.muted else colors.onAccent, maxLines = 1,
                )
            }
        }
    }
}

/**
 * The 48 dp target of a title's collection action, one node whatever it says, and never disabled (a
 * press while adding does nothing): a remote's focus stays on it as the title is added. A disabled or
 * replaced button would drop the focus, which a remote hands to the search field: its keyboard opens
 * and Search turns to suggestions.
 */
@Composable
private fun CollectionTarget(
    name: String,
    inCollection: Boolean,
    adding: Boolean,
    onAdd: () -> Unit,
    onJump: () -> Unit,
    focus: FocusRequester?,
    outline: Color? = null,
    content: @Composable () -> Unit,
) {
    Box(
        Modifier.then(focus?.let { Modifier.focusRequester(it) } ?: Modifier)
            .heightIn(min = 48.dp).focusOutline(RoundedCornerShape(24.dp), color = outline, fill = false)
            // The words shown, then the title (the row's pick area or the card says the rest).
            .semantics {
                contentDescription = when {
                    inCollection -> "$name, in your collection. Open in Collection"
                    adding -> "Adding $name…"
                    else -> "+ Collection, $name"
                }
            }
            .clickable(role = Role.Button) { if (inCollection) onJump() else if (!adding) onAdd() },
        contentAlignment = Alignment.Center,
    ) { content() }
}

fun languageChip(item: RankedItem): String? =
    item.ranked.reasons.filterIsInstance<ReleaseReason.Language>().firstOrNull()?.text

/** A chip as [InfoChip] draws it: its words and its look, and what TalkBack reads where its words need more ([spoken]). */
data class ChipLook(val text: String, val kind: ChipKind = ChipKind.Plain, val spoken: String? = null)

/**
 * A warning's chip as TalkBack reads it (L150; CsPhoneTalkBackC2 17, 18): ⚠ as "Warning", the colon as a pause, and a
 * maximum's megabits in words, as the slow-stream banner says them: "Warning: VC-1, no picture on Kitchen display",
 * "Warning: above your 40 megabits per second for casting".
 */
fun spokenWarning(reason: ReleaseReason): String = "Warning: " + when (reason) {
    is ReleaseReason.AboveMaxBitrate -> "above your ${reason.megabitsPerSecond} ${SlowStream.SPOKEN_UNIT}"
    is ReleaseReason.AboveCastMax -> "above your ${reason.megabitsPerSecond} ${SlowStream.SPOKEN_UNIT} for casting"
    else -> reason.text.replace(": ", ", ")
}

/**
 * "✓ Plays", where the ranking says [best] plays on this device: never where it was ranked for a Cast device, whose player
 * the phone can't check (L126).
 */
private fun playsChip(best: RankedItem): ChipLook? =
    ChipLook("✓ Plays", ChipKind.Good).takeIf { ReleaseReason.PlaysOnDevice in best.ranked.reasons }

/** "✓ Cached", read as "Cached" (L214), where [item] is a torrent one of the user's services has cached (L201). */
private fun cachedChip(item: RankedItem): ChipLook? = CACHED.takeIf { ReleaseReason.Cached in item.ranked.reasons }

private val CACHED = ChipLook("✓ ${ReleaseReason.Cached.text}", ChipKind.Good, ReleaseReason.Cached.text)

/** The chips under a search group's row for its [best] release: its summary, "✓ Cached", then "⚠ May buffer" or [playsChip]. */
fun groupChips(best: RankedItem): List<ChipLook> {
    val buffer = best.ranked.reasons.filterIsInstance<ReleaseReason.MayBuffer>().firstOrNull()
    return summaryChips(best).map { ChipLook(it) } + listOfNotNull(cachedChip(best), if (buffer != null) ChipLook("⚠ May buffer", ChipKind.Warn) else playsChip(best))
}

/** The chips over a picked movie's Play for its [best] release: its summary and language, "✓ Cached", then [playsChip]. */
fun heroChips(best: RankedItem): List<ChipLook> =
    summaryChips(best).map { ChipLook(it) } + listOfNotNull(languageChip(best)?.let { ChipLook(it, ChipKind.Lang) }, cachedChip(best), playsChip(best))

/**
 * A release's chips in a release list ([ReasonChips]): with [bitrate], its estimate first ([bitrateChip]), where its size
 * shows beside them; then its reasons, a blocking or warning one after "⚠" (read as words, [spokenWarning]), a positive
 * one after "✓", a neutral one plain; then its first audio.
 */
fun reasonChips(item: RankedItem, bitrate: Boolean = false): List<ChipLook> =
    listOfNotNull(bitrateChip(item.ranked)?.takeIf { bitrate }?.let { ChipLook(it) }) +
        item.ranked.reasons.map { reason ->
            when {
                reason is ReleaseReason.PlaysOnDevice -> ChipLook("✓ ${reason.text}", ChipKind.Good)
                reason is ReleaseReason.Cached -> CACHED
                reason is ReleaseReason.Language -> ChipLook(reason.text, ChipKind.Lang)
                reason.severity == Severity.Blocking || reason.severity == Severity.Warning -> ChipLook("⚠ ${reason.text}", ChipKind.Warn, spokenWarning(reason))
                reason.severity == Severity.Positive -> ChipLook("✓ ${reason.text}", ChipKind.Good)
                else -> ChipLook(reason.text)
            }
        } +
        listOfNotNull(item.parsed.audio.firstOrNull()?.let { ChipLook(it.label + (item.parsed.channels?.let { c -> " $c" } ?: "")) })

/** Reason chips for one release in the release list ([reasonChips]). */
@Composable
fun ReasonChips(item: RankedItem, bitrate: Boolean = false) {
    ChipRow { reasonChips(item, bitrate).forEach { InfoChip(it.text, it.kind, it.spoken) } }
}

/**
 * Where a release plays from ([SourceTag], L201, L247; DbPhoneReleases, D2PhoneReleaseTags): an outlined pill as tall as
 * a chip, in small semibold type, the same look for every source, so it isn't read as one of the release's facts; muted
 * on a hidden release. "+n" 4 dp after it when more services have it cached, drawn as the tag is with muted words. One
 * piece for TalkBack, which reads [SourceTag.spoken] and never the "+n" alone (L251).
 */
@Composable
fun SourceTagChip(tag: SourceTag, modifier: Modifier = Modifier) {
    Row(
        modifier.clearAndSetSemantics { contentDescription = tag.spoken },
        horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        TagWords(tag.text, if (tag.dim) colors.muted else colors.textSecondary, inset = 8.dp)
        if (tag.more > 0) TagWords("+${tag.more}", colors.muted, inset = 6.dp)
    }
}

/** A tag's outlined words: the border inside its 1 dp, as the boards': 2 dp over and under the words, as a chip's. */
@Composable
private fun TagWords(text: String, color: Color, inset: Dp) {
    val shape = RoundedCornerShape(6.dp)
    Text(
        text, style = AppType.small.copy(fontWeight = FontWeight.SemiBold), color = color, maxLines = 1,
        modifier = Modifier.clip(shape).border(1.dp, colors.outline, shape).padding(horizontal = inset, vertical = 2.dp),
    )
}

@Composable
fun Dot(color: Color) = Box(Modifier.size(8.dp).clip(CircleShape).background(color))
