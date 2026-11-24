package io.skjaere.usenet.app.collection

import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager

/** No touch screen (a TV), or a remote or keyboard in use. */
@Composable
fun rememberDpad(): Boolean {
    val context = LocalContext.current
    val touch = remember(context) { context.packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN) }
    return !touch || LocalInputModeManager.current.inputMode == InputMode.Keyboard
}
