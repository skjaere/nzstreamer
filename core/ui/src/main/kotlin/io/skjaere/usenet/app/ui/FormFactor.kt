package io.skjaere.usenet.app.ui

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * What the app runs on, for layouts: a TV, or anything else. It doesn't say how the user moves
 * around: a phone with a D-pad keeps the phone's layouts, and the input mode decides focus.
 */
enum class FormFactor {
    Phone, Tv;

    companion object {
        /** The launch extra a debug build takes for another form factor: "tv" or "phone", as on a phone emulator showing the TV's layouts. */
        const val EXTRA = "formFactor"

        /**
         * Whether [EXTRA] is read: the app sets it from its own build as it starts, on in its debug builds alone. The kit has
         * no build type of its own to read; until it is set, a launch's [EXTRA] is no override.
         */
        @Volatile
        var debugOverride: Boolean = false

        /** An Android TV or Google TV: in its TV mode, or one that declares Leanback. With [debugOverride], [intent]'s [EXTRA] wins. */
        fun of(context: Context, intent: Intent? = null): FormFactor = of(
            uiModeType = context.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK,
            leanback = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK),
            override = intent?.getStringExtra(EXTRA),
        )

        internal fun of(uiModeType: Int, leanback: Boolean, override: String?): FormFactor = when (override?.takeIf { debugOverride }?.lowercase()) {
            "tv" -> Tv
            "phone" -> Phone
            else -> if (uiModeType == Configuration.UI_MODE_TYPE_TELEVISION || leanback) Tv else Phone
        }
    }
}

/** The form factor the activity's layouts are for, read once as it is created ([FormFactor.of]); [AppTheme] provides it. */
val LocalFormFactor = staticCompositionLocalOf { FormFactor.Phone }
