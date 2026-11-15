package io.skjaere.usenet.app.download

import io.skjaere.usenet.app.ui.FormFactor

/**
 * Where downloads show (W108, L369): on phones and tablets only, by one rule every way in asks (the episode ⋮, the Movie
 * page's button, Search's movie card, Home's section, the Settings hub's row, the notification permission's ask and the
 * activity's download extras). Never on a TV, whatever its layout, nor in the TV's layout on a phone.
 */
object DownloadPlaces {
    /** The [layout] (LocalFormFactor) is the phone's, on a device that isn't a [television] (the app's container's). */
    fun shown(layout: FormFactor, television: Boolean): Boolean = layout == FormFactor.Phone && !television
}
