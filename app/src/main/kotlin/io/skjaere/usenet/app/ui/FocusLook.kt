package io.skjaere.usenet.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.RippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.scale

/**
 * Whether the element of [interactionSource] shows as focused: a remote's focus on it, or, where hover looks the same
 * ([FocusStyle.hover], a TV's pointer remotes), a pointer over it.
 */
@Composable
fun InteractionSource.showsFocus(): Boolean {
    val focused by collectIsFocusedAsState()
    val hovered by collectIsHoveredAsState()
    return focused || (LocalFocusStyle.current.hover && hovered)
}

/**
 * Whether a component the app owns shows turned over ([FocusStyle.invert]): on a TV while it [showsFocus]. Its
 * container then takes the text's colour and its words and icons the background's.
 */
@Composable
fun InteractionSource.turnsOver(): Boolean = LocalFocusStyle.current.invert && showsFocus()

/**
 * The element of [interactionSource] grows to [scale] while it shows as focused and shrinks to [pressed] while
 * pressed (on a TV; a phone's style keeps both at 1, and nothing moves). Only as it is drawn: its bounds stay, so the
 * remote's next move (which goes by where elements are) and the layout around it are as they were.
 */
fun Modifier.focusScale(interactionSource: InteractionSource, scale: (FocusStyle) -> Float, pressed: (FocusStyle) -> Float = { it.pressedScale }): Modifier = composed {
    val style = LocalFocusStyle.current
    if (scale(style) == 1f && pressed(style) == 1f) return@composed Modifier
    val isPressed by interactionSource.collectIsPressedAsState()
    val lit = interactionSource.showsFocus()
    val target = when {
        isPressed -> pressed(style)
        lit -> scale(style)
        else -> 1f
    }
    val shown by animateFloatAsState(target, tween(SCALE_MS), label = "focusScale")
    drawWithContent { scale(shown) { this@drawWithContent.drawContent() } }
}

/**
 * Inside a component the app owns on a TV: material's focus and hover layers off, its press kept. Turned over, the
 * component shows its focus itself; the accent's layer of material's controls ([FocusStyle.ripple]) would tint its cream.
 */
@Composable
fun OwnedRipple(content: @Composable () -> Unit) {
    if (!LocalFocusStyle.current.invert) return content()
    CompositionLocalProvider(LocalRippleConfiguration provides RippleConfiguration(Color.Unspecified, OWNED_RIPPLE), content = content)
}

private val OWNED_RIPPLE = RippleAlpha(draggedAlpha = 0.16f, focusedAlpha = 0f, hoveredAlpha = 0f, pressedAlpha = 0.12f)

private const val SCALE_MS = 120
