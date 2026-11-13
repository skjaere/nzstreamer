package io.skjaere.usenet.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** The import screen opens the system file picker by itself, once per visit. */
@RunWith(AndroidJUnit4::class)
class ImportPickerTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** Stands in for the system picker: counts its launches and answers as if the user backed out. */
    private val picker = object : Instrumentation.ActivityMonitor() {
        val launches = AtomicInteger()

        override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? =
            if (intent.action == Intent.ACTION_OPEN_DOCUMENT) {
                launches.incrementAndGet()
                Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            } else {
                null
            }
    }

    private val home = HomeContentShown()
    private val realContract = ImportPicker.contract

    /** What the picker's launches start: on TV builds without one, nothing answers it. */
    private val noPicker: () -> ActivityResultContract<Array<String>, Uri?> = {
        object : ActivityResultContract<Array<String>, Uri?>() {
            override fun createIntent(context: Context, input: Array<String>) = Intent("io.skjaere.usenet.test.NO_DOCUMENT_PICKER")
            override fun parseResult(resultCode: Int, intent: Intent?): Uri? = null
        }
    }

    @Before
    fun stubPicker() {
        instrumentation.addMonitor(picker)
        home.show()
    }

    @After
    fun removePicker() {
        instrumentation.removeMonitor(picker)
        ImportPicker.contract = realContract
        home.restore()
    }

    /** At the end of Home, past whatever Up next holds. */
    private fun openImport() {
        compose.waitUntil(5_000) { compose.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasScrollAction())[0].performScrollToNode(hasText("Open a .nzb file"))
        compose.onNodeWithText("Open a .nzb file").performClick()
    }

    @Test
    fun recreatingTheActivityDoesNotOpenThePickerAgain() {
        openImport()
        compose.waitUntil(5_000) { picker.launches.get() == 1 }
        compose.onNodeWithText("Choose a .nzb file").assertIsDisplayed()

        // A rotation, dark-mode or locale change.
        compose.activityRule.scenario.recreate()

        compose.onNodeWithText("Choose a .nzb file").assertIsDisplayed()
        assertEquals("picker launches", 1, picker.launches.get())
    }

    /**
     * Many TV builds have no document picker (#121): Import neither crashes nor tries again and
     * again, and says how else a .nzb gets here.
     */
    @Test
    fun withoutAPickerImportSaysHowElseToOpenAFile() {
        ImportPicker.contract = noPicker
        openImport()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText(NO_PICKER, substring = true)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Open a .nzb from a file manager, or share it to this app", substring = true).assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasText("Choose a .nzb file")).fetchSemanticsNodes().size)

        compose.activityRule.scenario.recreate()

        compose.onNodeWithText(NO_PICKER, substring = true).assertIsDisplayed()
        assertEquals(0, picker.launches.get())
    }

    private companion object {
        const val NO_PICKER = "This device has no file picker."
    }
}
