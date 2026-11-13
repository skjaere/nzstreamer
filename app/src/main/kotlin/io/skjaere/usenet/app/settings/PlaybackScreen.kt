package io.skjaere.usenet.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.container
import io.skjaere.usenet.app.tv.searchKeyOpensSearch
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.Footnote
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.ui.GroupCard
import io.skjaere.usenet.app.ui.InsetDivider
import io.skjaere.usenet.app.ui.LocalFormFactor
import io.skjaere.usenet.app.ui.SectionHeader
import io.skjaere.usenet.app.ui.SwitchRow
import io.skjaere.usenet.app.ui.TopBar
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.data.AudioLanguages
import io.skjaere.usenet.data.PlaybackPrefs
import io.skjaere.usenet.releases.QualityPreference
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Locale

class PlaybackViewModel(private val container: AppContainer) : ViewModel() {
    val prefs = container.settings.playback.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlaybackPrefs())
    val deviceLanguage: String = Locale.getDefault().language

    fun update(change: (PlaybackPrefs) -> PlaybackPrefs) = viewModelScope.launch { container.settings.updatePlayback(change) }

    /** Editing starts from what's in effect, so the defaults become explicit on first change. */
    fun languages(change: (List<String>) -> List<String>) =
        update { it.copy(audioLanguages = change(it.effectiveAudioLanguages(deviceLanguage)).distinct()) }
}

/**
 * Quality and languages' "Maximum bitrate when casting" (W52, L132; CsPhoneCastBitrate), under Maximum bitrate, on phones
 * and tablets alone: Maximum bitrate's twelve choices, saved apart from it, and under them the explanation of why a
 * cast needs about three times the video's bitrate over Wi-Fi. Its words and what a pick saves, where a JVM test reads them.
 */
object CastBitrateSetting {
    const val TITLE = "Maximum bitrate when casting"

    /** Under the choices, in two paragraphs to read easier (the design's typography). */
    val EXPLANATION = listOf(
        "When you cast, the video comes to this phone first, and the phone sends it on to the Cast device. So over Wi-Fi it " +
            "travels three times: from the router to the phone, from the phone back to the router, and from the router to the " +
            "Cast device. That needs about three times the video’s bitrate.",
        "A Cast device or TV box connected by network cable takes one of those trips off the Wi-Fi, so use cables where you " +
            "can. Releases above this limit are tried last when you cast.",
    )

    /** The choices in their cells' order: Not set (null), then Maximum bitrate's own. */
    val CHOICES: List<Int?> = listOf<Int?>(null) + PlaybackPrefs.MAX_BITRATES_MBPS

    /** How TalkBack reads [mbps]'s cell: "Maximum bitrate when casting 40 Mbps", "No maximum bitrate when casting". */
    fun label(mbps: Int?): String = if (mbps == null) "No maximum bitrate when casting" else "$TITLE $mbps Mbps"

    /** The choice the cells show as picked: the saved maximum, as the ranking reads it (what can't be a speed, Not set). */
    fun chosen(prefs: PlaybackPrefs): Int? = prefs.castMaxBitrate?.let { (it / 1_000_000).toInt() }

    /** What a tap on [mbps]'s cell saves: the casting maximum alone, Maximum bitrate as it was. */
    fun picked(prefs: PlaybackPrefs, mbps: Int?): PlaybackPrefs = prefs.copy(castMaxBitrateMbps = mbps)

    /** Whether Quality and languages has the section: never on a TV, which never casts (W28, L110). */
    fun shows(television: Boolean): Boolean = !television
}

/**
 * Quality and languages' footnote under Maximum bitrate (L308): what the setting is, and what it does for larger releases,
 * which differs by device (W101): on a TV the larger releases of a quality go first whatever is set; on a phone or tablet
 * they rank higher only once a maximum is set. Its words, where a JVM test reads them.
 */
object MaxBitrateSetting {
    /** The footnote on a TV ([television]), or on a phone or tablet. */
    fun footnote(television: Boolean): String = "The speed this device can stream at. Releases above it are tried last. " +
        if (television) "Of the same quality, larger releases go first." else "Once it is set, larger releases of the same quality rank higher."
}

private val COMMON_LANGUAGES = listOf("de", "en", "fr", "es", "it", "nl", "pl", "pt", "sv", "da", "no", "fi", "tr", "ru", "ja", "ko", "zh", "hi")

@Composable
fun PlaybackScreen(onBack: () -> Unit) {
    val container = LocalContext.current.container
    val vm: PlaybackViewModel = viewModel { PlaybackViewModel(container) }
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    val languages = prefs.effectiveAudioLanguages(vm.deviceLanguage)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar("Quality and languages", onBack)
        SectionHeader("Preferred quality", Modifier.padding(top = 0.dp))
        Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QualityPreference.entries.forEach { q ->
                val selected = prefs.qualityPreference == q
                Column(
                    Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (selected) colors.accentTint else colors.surface)
                        .border(if (selected) 2.dp else 1.dp, if (selected) colors.accent else colors.chip, RoundedCornerShape(12.dp))
                        .selectable(selected, role = Role.RadioButton) { vm.update { it.copy(quality = q.name) } },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(qualityLabel(q), style = if (selected) AppType.bodyStrong else AppType.body, color = colors.text, textAlign = TextAlign.Center)
                }
            }
        }
        Footnote("Auto picks the highest quality that fits this screen and your connection.")

        SectionHeader("Audio languages, in order")
        GroupCard {
            languages.forEachIndexed { i, code ->
                if (i > 0) InsetDivider()
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}", style = AppType.secondary, color = colors.muted, modifier = Modifier.width(22.dp))
                    Text(languageName(code), style = AppType.body, color = colors.text, modifier = Modifier.weight(1f))
                    if (i > 0) TextButton(onClick = { vm.languages { l -> AudioLanguages.moveUp(l, code) } }) { Text("↑", color = colors.muted) }
                    if (languages.size > 1) TextButton(onClick = { vm.languages { l -> AudioLanguages.remove(l, code) } }) { Text("✕", color = colors.muted) }
                }
            }
            InsetDivider()
            Text(
                "+ Add language", style = AppType.bodyStrong, color = colors.accent,
                modifier = Modifier.fillMaxWidth().clickable { adding = true }.padding(horizontal = 12.dp, vertical = 14.dp),
            )
        }
        Footnote("Releases in these languages rank higher. “DL” releases count as German and English.")

        SectionHeader("Releases to try")
        Row(Modifier.padding(horizontal = 16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlaybackPrefs.RELEASES_TO_TRY.forEach { count ->
                val selected = prefs.attempts == count
                Column(
                    Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (selected) colors.accentTint else colors.surface)
                        .border(if (selected) 2.dp else 1.dp, if (selected) colors.accent else colors.chip, RoundedCornerShape(12.dp))
                        .semantics { contentDescription = if (count == 1) "Try 1 release" else "Try up to $count releases" }
                        .selectable(selected, role = Role.RadioButton) { vm.update { it.copy(releasesToTry = count) } },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("$count", style = if (selected) AppType.bodyStrong else AppType.body, color = colors.text, textAlign = TextAlign.Center)
                }
            }
        }
        Footnote("When a release turns out to be incomplete or unplayable, the next-best one is tried, up to this many in all. Each try downloads its NZB, which counts against your indexer's daily downloads; a play stops early when an indexer says its limit is reached.")

        SectionHeader("Maximum bitrate")
        BitrateChoices(
            chosen = prefs.maxBitrate?.let { it / 1_000_000 }?.toInt(),
            label = { mbps -> if (mbps == null) "No maximum bitrate" else "Maximum bitrate $mbps Mbps" },
            onPick = { mbps -> vm.update { it.copy(maxBitrateMbps = mbps) } },
        )
        Footnote(MaxBitrateSetting.footnote(container.television))

        if (CastBitrateSetting.shows(container.television)) {
            SectionHeader(CastBitrateSetting.TITLE)
            BitrateChoices(
                chosen = CastBitrateSetting.chosen(prefs),
                label = CastBitrateSetting::label,
                onPick = { mbps -> vm.update { CastBitrateSetting.picked(it, mbps) } },
            )
            Footnote(CastBitrateSetting.EXPLANATION)
        }

        SectionHeader("Behaviour")
        GroupCard {
            SwitchRow("Subtitles when audio isn't your first language", prefs.subtitlesWhenForeign, { v -> vm.update { it.copy(subtitlesWhenForeign = v) } })
            InsetDivider()
            SwitchRow("Play next episode automatically", prefs.autoPlayNext, { v -> vm.update { it.copy(autoPlayNext = v) } })
            InsetDivider()
            SwitchRow(
                "Look up intro and credits times online", prefs.lookUpSegmentsOnline, { v -> vm.update { it.copy(lookUpSegmentsOnline = v) } },
                detail = "Sends the show's TMDB id, season and episode, and the file's length, to TheIntroDB (theintrodb.org) when an episode plays.",
            )
            InsetDivider()
            SwitchRow(
                "Check every article before playing", prefs.checkAllArticles, { v -> vm.update { it.copy(checkAllArticles = v) } },
                detail = "Off: a quick check of 256 articles (about a second). On: every article, so a release with gaps is skipped before it starts. Takes seconds for an episode, up to a minute for a large film.",
            )
            // A TV's (by the layouts, as the TV's other rows are): the player switches the TV's refresh rate only on a TV
            // device, whose HDMI output can change mode; a phone's panel follows the video seamlessly already.
            if (LocalFormFactor.current == FormFactor.Tv) {
                InsetDivider()
                SwitchRow(
                    "Match the TV to the video’s frame rate", prefs.matchFrameRate, { v -> vm.update { it.copy(matchFrameRate = v) } },
                    detail = "Before a film or episode plays, the TV switches to the video’s frame rate (such as 23.976 Hz) for smoother motion. " +
                        "The screen goes dark for a second or two while it switches.",
                )
            }
        }
    }

    if (adding) {
        AlertDialog(
            onDismissRequest = { adding = false },
            modifier = Modifier.searchKeyOpensSearch { adding = false },
            confirmButton = { TextButton(onClick = { adding = false }) { Text("Close") } },
            title = { Text("Add language", style = AppType.cardTitle) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    COMMON_LANGUAGES.filterNot { it in languages }.forEach { code ->
                        Text(
                            languageName(code), style = AppType.body, color = colors.text,
                            modifier = Modifier.fillMaxWidth().clickable { vm.languages { it + code }; adding = false }.padding(vertical = 12.dp),
                        )
                    }
                }
            },
            containerColor = colors.surface,
        )
    }
}

/**
 * A maximum bitrate's twelve choices, Not set and [PlaybackPrefs.MAX_BITRATES_MBPS]: three full rows of four, a true grid
 * that a remote's Up and Down go straight through (L309), each as wide as the others, and as tall as the tallest a large
 * font makes. [chosen] is ticked; TalkBack reads each by [label].
 */
@Composable
private fun BitrateChoices(chosen: Int?, label: (Int?) -> String, onPick: (Int?) -> Unit) {
    (listOf<Int?>(null) + PlaybackPrefs.MAX_BITRATES_MBPS).chunked(4).forEachIndexed { row, choices ->
        Row(
            Modifier.padding(horizontal = 16.dp).padding(top = if (row > 0) 8.dp else 0.dp).fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            choices.forEach { mbps ->
                val selected = chosen == mbps
                Column(
                    Modifier.weight(1f).heightIn(min = 56.dp).fillMaxHeight().clip(RoundedCornerShape(12.dp))
                        .background(if (selected) colors.accentTint else colors.surface)
                        .border(if (selected) 2.dp else 1.dp, if (selected) colors.accent else colors.chip, RoundedCornerShape(12.dp))
                        .semantics { contentDescription = label(mbps) }
                        .selectable(selected, role = Role.RadioButton) { onPick(mbps) }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(mbps?.toString() ?: "Not set", style = if (selected) AppType.bodyStrong else AppType.body, color = colors.text, textAlign = TextAlign.Center)
                    if (mbps != null) Text("Mbps", style = AppType.small, color = colors.muted)
                }
            }
            repeat(4 - choices.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}
