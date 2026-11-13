package io.skjaere.usenet.app.play

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.colors

/**
 * A panel at the end edge over the player, under its [title]: the audio and subtitles sheet's ([TrackSheetPanel]),
 * File details' ([FileDetailsPanel]) and Play on's list ([PlayOnPanel]). As tall as the screen, 400 dp wide at most, and
 * scrolling when what it holds doesn't fit. Back, or a tap beside the panel, closes it ([onClose]); taps on it don't.
 * TalkBack takes it for a pane named [title], announced as it opens; PlayerActivity hides what it covers. Like the
 * player's controls it keeps clear of the display cutout and of the system bars as if they showed: the player hides
 * them. On a TV what it holds keeps inside [safeArea] too, which the panel's background goes past to the edge. Not
 * [titled], what it holds draws the title itself ([SidePanelTitle]), where something goes above it (a Cast device's
 * block, over Play on's list).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SidePanel(title: String, onClose: () -> Unit, safeArea: WindowInsets, titled: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    // Composed only while the panel shows: Back is the panel's then, before anything else in the player sees it.
    BackHandler(onBack = onClose)
    var room by remember { mutableIntStateOf(Int.MAX_VALUE) }
    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onClose() } }) {
        Column(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().widthIn(max = 400.dp).fillMaxWidth()
                // A pane for TalkBack, announced as it opens; PlayerActivity hides what it covers.
                .semantics { paneTitle = title }
                .background(colors.surface.copy(alpha = 0.94f))
                // Taps on the panel itself don't close it.
                .pointerInput(Unit) { detectTapGestures { } }
                .windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout).union(safeArea).only(WindowInsetsSides.Vertical + WindowInsetsSides.End))
                .onSizeChanged { room = it.height }
                .verticalScroll(rememberScrollState())
                .padding(vertical = 12.dp),
        ) {
            if (titled) SidePanelTitle(title)
            CompositionLocalProvider(LocalSidePanelRoom provides room) { content() }
        }
    }
}

/** A [SidePanel]'s title, a heading in the card title's style, as the panel draws it, or what it holds where it doesn't. */
@Composable
fun SidePanelTitle(title: String) {
    Text(title, style = AppType.cardTitle, color = colors.text, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp).semantics { heading() })
}

/** The height a [SidePanel] shows what it holds in, in px, as it scrolls; what is taller can't be shown whole. */
internal val LocalSidePanelRoom = compositionLocalOf { Int.MAX_VALUE }
