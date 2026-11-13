package io.skjaere.usenet.app

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import io.skjaere.usenet.app.ui.FormFactor

/**
 * Opens [url] in whatever app handles web links. False when nothing can: some locked-down phones
 * have no browser, so callers then show the address instead. On a TV ([formFactor]) always false,
 * without trying: many have no browser, and one that has is no way to read a page with a remote
 * (TV-WB), so the address shows for the user's phone or computer.
 */
fun Context.openExternalUrl(url: String, formFactor: FormFactor): Boolean {
    if (formFactor == FormFactor.Tv) return false
    return try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
