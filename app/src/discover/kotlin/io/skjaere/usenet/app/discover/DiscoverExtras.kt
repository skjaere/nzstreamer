package io.skjaere.usenet.app.discover

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import io.skjaere.usenet.app.AppContainer
import io.skjaere.usenet.app.AppExtras
import io.skjaere.usenet.app.ExtraTab
import io.skjaere.usenet.app.HomeRoute
import io.skjaere.usenet.app.MetadataRoute
import io.skjaere.usenet.app.MovieRoute
import io.skjaere.usenet.app.ShowRoute
import io.skjaere.usenet.app.Tab
import io.skjaere.usenet.app.ifResumed
import io.skjaere.usenet.app.tv.TopLevelBack
import io.skjaere.usenet.app.ui.FormFactor

/** In every build that has Discover's screens, for the release guard (checkNoDiscover) to look for; never in a Play build. */
private const val CANARY = "nzs.discover.canary.app"

/**
 * Discover in the debug, bench and beta builds: TMDB's lists of titles (:core:discover), on a tab after Home, and each list's
 * See all page. A card opens the title's page on the tab's own stack, so Back comes back to Discover; nothing it opens
 * is added to the recent searches, which are the user's own.
 */
internal object DiscoverExtras : AppExtras {
    /** Read by the entry point, so it stays in every build Discover is in. */
    val canary: String = CANARY

    private val tab = Tab(DISCOVER, CompassIcon, DiscoverRoute, DiscoverRoute::class)

    /** After Home in a phone's bar and a TV's rail alike (W3). */
    override fun tabs(formFactor: FormFactor): List<ExtraTab> = listOf(ExtraTab(tab, after = HomeRoute::class))

    override fun NavGraphBuilder.screens(nav: NavController, container: AppContainer, formFactor: FormFactor) {
        composable<DiscoverRoute> { screen ->
            TopLevelBack(nav, screen)
            DiscoverScreen(
                onShow = { id -> screen.ifResumed { nav.navigate(ShowRoute(id)) } },
                onMovie = { id -> screen.ifResumed { nav.navigate(MovieRoute(id)) } },
                onList = { query -> screen.ifResumed { nav.navigate(DiscoverListRoute.of(query)) } },
                onMetadata = { screen.ifResumed { nav.navigate(MetadataRoute) } },
            )
        }
        composable<DiscoverListRoute> { screen ->
            DiscoverListScreen(
                screen.toRoute<DiscoverListRoute>().query(),
                onBack = { screen.ifResumed { nav.popBackStack() } },
                onShow = { id -> screen.ifResumed { nav.navigate(ShowRoute(id)) } },
                onMovie = { id -> screen.ifResumed { nav.navigate(MovieRoute(id)) } },
                onMetadata = { screen.ifResumed { nav.navigate(MetadataRoute) } },
            )
        }
    }
}

/**
 * The tab's icon, a compass: Material's "explore", drawn here because the app has only Material's core icons, and the
 * extended set is large. It is Google's, under the Apache License 2.0 (THIRD_PARTY_NOTICES.md).
 */
internal val CompassIcon: ImageVector = ImageVector.Builder("Compass", 24.dp, 24.dp, 24f, 24f).addPath(
    addPathNodes(
        "M12,10.9c-0.61,0 -1.1,0.49 -1.1,1.1s0.49,1.1 1.1,1.1c0.61,0 1.1,-0.49 1.1,-1.1s-0.49,-1.1 -1.1,-1.1z" +
            "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2z" +
            "M14.19,14.19L6,18l3.81,-8.19L18,6l-3.81,8.19z",
    ),
    fill = SolidColor(Color.Black),
).build()
