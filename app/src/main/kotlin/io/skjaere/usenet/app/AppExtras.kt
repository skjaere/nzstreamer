package io.skjaere.usenet.app

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import io.skjaere.usenet.app.ui.FormFactor
import kotlin.reflect.KClass

/**
 * What a build adds to main's tabs and screens, from a source directory of its own: [appExtras], which the release
 * (Play) build answers with null, so it has main's alone. Main names no feature here, as it ships in every build.
 */
interface AppExtras {
    /** The tabs to add on [formFactor], each placed after a tab of main's ([tabsFor]). */
    fun tabs(formFactor: FormFactor): List<ExtraTab>

    /** Those tabs' screens and the pages they open, in the app's graph beside main's ([appGraph]). */
    fun NavGraphBuilder.screens(nav: NavController, container: AppContainer, formFactor: FormFactor)
}

/** [tab], placed after the tab whose route is [after]; without one there, last of the tabs not pinned. */
data class ExtraTab(val tab: Tab, val after: KClass<*>)
