package io.skjaere.usenet.app

import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.settings.MetadataScreen
import io.skjaere.usenet.app.ui.AppTheme
import io.skjaere.usenet.app.ui.FormFactor
import io.skjaere.usenet.metadata.KeyCheck
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings › Metadata's Check and save asks TMDB through TmdbLookup's seam, which the phone setup's device tests answer
 * for invented keys too: a key the fake takes is saved, as TMDB's own answer would have it. The key saved before is put
 * back afterwards.
 */
@RunWith(AndroidJUnit4::class)
class MetadataKeyDeviceTest {
    @get:Rule
    val compose = createComposeRule()

    private val settings = InstrumentationRegistry.getInstrumentation().targetContext.container.settings
    private var before: String? = null

    @Before
    fun remember() = runBlocking {
        before = settings.tmdbKey()
    }

    @After
    fun putBack() = runBlocking {
        settings.setTmdbKey(before)
    }

    private fun exists(matcher: SemanticsMatcher) = compose.onAllNodes(matcher).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun checkAndSaveAsksThroughTheSeam() {
        FakeTmdb(keys = { if (it == INVENTED_KEY) KeyCheck.Valid else null }).use {
            compose.setContent { AppTheme(FormFactor.Phone) { MetadataScreen(onBack = {}) } }
            compose.waitUntil(5_000) { exists(hasSetTextAction()) }
            compose.onNode(hasSetTextAction()).performTextInput(INVENTED_KEY)
            compose.onNode(hasText("Check and save") and hasClickAction()).performClick()
            compose.waitUntil(10_000) { exists(hasText("Key works")) || exists(hasText("TMDB rejected", substring = true)) || exists(hasText("Couldn't check", substring = true)) }
            assertEquals("the key's check", true, exists(hasText("Key works")))
            assertEquals(INVENTED_KEY, runBlocking { settings.tmdbKey() })
        }
    }

    private companion object {
        const val INVENTED_KEY = "0000invented0000key0000for0tests"
    }
}
