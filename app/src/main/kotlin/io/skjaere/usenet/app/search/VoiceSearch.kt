package io.skjaere.usenet.app.search

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.VisibleForTesting
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Searching by voice on a TV: the system's speech recognizer (RecognizerIntent), free-form, in the
 * device's language, one result. The recognizer listens, not the app, so there is no RECORD_AUDIO and the microphone
 * isn't required; where no recognizer resolves (many AOSP boxes) there is no mic.
 */
object VoiceSearch {
    const val PROMPT = "Say a show, a movie or a release name"

    /** Whether a recognizer takes RECOGNIZE_SPEECH (the manifest's <queries> lets the app see one): a seam for the tests. */
    @VisibleForTesting
    var resolves: (Context) -> Boolean = { context ->
        context.packageManager.queryIntentActivities(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH), 0).isNotEmpty()
    }

    /** Whether a recognizer resolves; asked off the main thread, as the package manager may read the disk. */
    suspend fun available(context: Context): Boolean = withContext(Dispatchers.IO) { resolves(context) }

    /** What the recognizer is asked for: free-form words in [locale]'s language, one result, with [prompt] shown. */
    fun extras(prompt: String = PROMPT, locale: Locale = Locale.getDefault()): Map<String, Any> = mapOf(
        RecognizerIntent.EXTRA_LANGUAGE_MODEL to RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
        RecognizerIntent.EXTRA_LANGUAGE to locale.toLanguageTag(),
        RecognizerIntent.EXTRA_MAX_RESULTS to 1,
        RecognizerIntent.EXTRA_PROMPT to prompt,
    )

    fun intent(prompt: String = PROMPT, locale: Locale = Locale.getDefault()): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            extras(prompt, locale).forEach { (key, value) ->
                when (value) {
                    is Int -> putExtra(key, value)
                    else -> putExtra(key, value.toString())
                }
            }
        }

    /** The words said, from the recognizer's answer ([resultCode], [data]); null when cancelled or nothing was said. */
    fun result(resultCode: Int, data: Intent?): String? =
        if (resultCode == Activity.RESULT_OK) first(data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)) else null

    /** The first of [results], trimmed; null for none or blank. */
    fun first(results: List<String>?): String? = results?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
}

/**
 * The mic's action: the recognizer started, [onText] given the words said (nothing when cancelled or nothing was said:
 * the query stays). Null while it isn't known yet whether a recognizer resolves, and where none does.
 */
@Composable
fun rememberVoiceSearch(onText: (String) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    val available by produceState<Boolean?>(null) { value = VoiceSearch.available(context) }
    val latest by rememberUpdatedState(onText)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { answer ->
        VoiceSearch.result(answer.resultCode, answer.data)?.let { latest(it) }
    }
    if (available != true) return null
    return remember(launcher) {
        {
            try {
                launcher.launch(VoiceSearch.intent())
            } catch (e: ActivityNotFoundException) {
                // The recognizer went since it was asked for: nothing to say to.
            }
        }
    }
}

/**
 * The remote's search key asking for voice, once: set as the key switches to Search ([request]), taken by Search as it
 * shows ([consume]). Kept in saved state, not as a route argument (CollectionJumpState's reasoning): a restore keeps a
 * request still to be taken, and never brings back one that was.
 */
@Stable
class VoiceRequest(pending: Boolean = false) {
    var pending by mutableStateOf(pending)
        private set

    fun request() {
        pending = true
    }

    /** True once per [request]. */
    fun consume(): Boolean {
        if (!pending) return false
        pending = false
        return true
    }

    companion object {
        val Saver: Saver<VoiceRequest, Boolean> = Saver(save = { it.pending }, restore = { VoiceRequest(it) })
    }
}

/**
 * Material's mic, which the icons the app ships (the core set) don't have. It is Google's, under the Apache License 2.0
 * (THIRD_PARTY_NOTICES.md).
 */
internal val MicIcon: ImageVector by lazy {
    materialIcon(name = "Filled.Mic") {
        materialPath {
            moveTo(12f, 14f)
            curveToRelative(1.66f, 0f, 2.99f, -1.34f, 2.99f, -3f)
            lineTo(15f, 5f)
            curveToRelative(0f, -1.66f, -1.34f, -3f, -3f, -3f)
            reflectiveCurveTo(9f, 3.34f, 9f, 5f)
            verticalLineToRelative(6f)
            curveToRelative(0f, 1.66f, 1.34f, 3f, 3f, 3f)
            close()
            moveTo(17.3f, 11f)
            curveToRelative(0f, 3f, -2.54f, 5.1f, -5.3f, 5.1f)
            reflectiveCurveTo(6.7f, 14f, 6.7f, 11f)
            lineTo(5f, 11f)
            curveToRelative(0f, 3.41f, 2.72f, 6.23f, 6f, 6.72f)
            lineTo(11f, 21f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(-3.28f)
            curveToRelative(3.28f, -0.48f, 6f, -3.3f, 6f, -6.72f)
            horizontalLineToRelative(-1.7f)
            close()
        }
    }
}
