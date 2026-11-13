package io.skjaere.usenet.app.tv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.skjaere.usenet.app.play.okGuard
import io.skjaere.usenet.app.ui.AppType
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.app.ui.LocalFormFactor
import io.skjaere.usenet.app.ui.OwnedRipple
import io.skjaere.usenet.app.ui.colors
import io.skjaere.usenet.app.ui.focusScale
import io.skjaere.usenet.app.ui.tryFocus
import io.skjaere.usenet.app.ui.turnsOver

/**
 * A menu over what opened it (TV-3's episode menu, shared): on a TV its first item takes the focus as it opens (a remote
 * has nothing to point at it with), asked again a frame at a time until it has it, since the menu's window may not take
 * the focus in the frame it opens (#243); it is [width] wide, so the focused item's bold words never change its width
 * or its left edge (TVP-2); its items turn over when focused. On a phone it is material's, as wide as its widest item.
 * The OK that opened it, held, picks nothing in it: its repeats are ignored (OkGuard with no window, TVF-1), an OK
 * pressed anew however soon is the user's. The remote's search key closes it and opens Search. As it closes, the focus
 * is where it was before it opened. [content] gets the modifier for its first item.
 */
@Composable
internal fun TvMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = TV_MENU_WIDTH,
    content: @Composable ColumnScope.(first: Modifier) -> Unit,
) {
    val tv = LocalFormFactor.current == FormFactor.Tv
    val first = remember { FocusRequester() }
    var firstFocused by remember { mutableStateOf(false) }
    LaunchedEffect(expanded, tv) {
        if (!expanded || !tv) return@LaunchedEffect
        repeat(MENU_FOCUS_FRAMES) {
            withFrameNanos {}
            if (firstFocused) return@LaunchedEffect
            first.tryFocus()
        }
    }
    DropdownMenu(
        expanded = expanded, onDismissRequest = onDismiss,
        modifier = modifier.okGuard(windowMs = 0).searchKeyOpensSearch(onDismiss).then(if (tv) Modifier.width(width) else Modifier),
        containerColor = if (tv) colors.chip else colors.surface,
        shape = if (tv) RoundedCornerShape(12.dp) else MenuDefaults.shape,
        border = if (tv) BorderStroke(1.dp, colors.outline) else null,
    ) {
        content(Modifier.focusRequester(first).onFocusChanged { firstFocused = it.isFocused })
    }
}

/**
 * A menu's item; on a TV it turns over while focused, as the app's own rows and buttons do, inset from the menu's sides,
 * its words in bold then ([selected] ones always, in the accent while not focused). A phone's is as it was, as wide as
 * the menu.
 */
@Composable
internal fun TvMenuItem(text: String, modifier: Modifier = Modifier, selected: Boolean = false, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val turned = interaction.turnsOver()
    val inset = if (LocalFormFactor.current == FormFactor.Tv) Modifier.padding(horizontal = 6.dp) else Modifier
    OwnedRipple {
        DropdownMenuItem(
            text = { Text(text, style = if (turned || selected) AppType.bodyStrong else AppType.body) }, onClick = onClick,
            modifier = modifier.focusScale(interaction, { it.rowScale }).then(inset).then(
                if (turned) Modifier.clip(RoundedCornerShape(8.dp)).background(colors.text) else Modifier,
            ),
            colors = MenuDefaults.itemColors(
                textColor = when {
                    turned -> colors.background
                    selected -> colors.accent
                    else -> colors.text
                },
            ),
            interactionSource = interaction,
        )
    }
}

private const val MENU_FOCUS_FRAMES = 10

/** A TV's menu: the mockup's width, room for its items' words in bold at a larger text size. */
private val TV_MENU_WIDTH = 240.dp
