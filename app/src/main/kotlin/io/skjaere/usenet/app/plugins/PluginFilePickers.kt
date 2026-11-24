package io.skjaere.usenet.app.plugins

import io.skjaere.usenet.core.AttemptFailure
import io.skjaere.usenet.core.FileListing
import io.skjaere.usenet.core.FilePicker
import io.skjaere.usenet.core.ListedFile
import io.skjaere.usenet.plugin.FileInfo
import io.skjaere.usenet.plugin.PluginFilePicker

/**
 * A plugin's file picker as the host's (L627): called while the play waits, as the host's own pickers are. It is handed a
 * release's files in their listed order (each its path, its size and whether the host takes it for a video) and answers an
 * index into that list; none, or one outside it, picks nothing, and the attempt fails with the plugin's no-match
 * ([AttemptFailure.NoMatchingFile]). Every call runs guarded ([PluginCalls], L619): one that throws turns the version off
 * as an error, and the picker is then asked nothing more, so this play's attempts find nothing and fail with the no-match,
 * marked as the picker's failure, not the release's (the release that played the item before stays remembered), and none
 * is checked whole.
 */
object PluginFilePickers {
    /** The no-match's words where the plugin's give none: its noMatch threw, or is blank. */
    const val NO_MATCH = "No matching file"

    /** [picker], of [version]'s instance, as a [FilePicker] whose calls into it [calls] guards. Reads its no-match once. */
    fun of(picker: PluginFilePicker, version: PluginVersion, calls: PluginCalls): FilePicker = Guarded(picker, version, calls)

    private class Guarded(private val picker: PluginFilePicker, private val version: PluginVersion, private val calls: PluginCalls) : FilePicker {
        /** Its code threw: it isn't asked again. */
        @Volatile
        private var broken = false

        private val label: String = (asked("noMatch") { picker.noMatch } as? String)?.takeIf(String::isNotBlank) ?: NO_MATCH

        override val noMatch: AttemptFailure
            get() = AttemptFailure.NoMatchingFile(label, pickerFailed = broken)

        override fun <F : ListedFile> pick(listing: FileListing<F>): F? {
            if (broken) return null
            val files = listing.files
            val index = asked("pick") { picker.pick(files.map { FileInfo(it.path, it.size, it.isVideo) }) } as? Int ?: return null
            return files.getOrNull(index)
        }

        override fun takesWhole(title: String): Boolean = !broken && asked("takesWhole") { picker.takesWhole(title) } == true

        /** What the plugin's [block] answers, or [Threw] where it threw, which turns the version off and the picker with it. */
        private fun asked(what: String, block: () -> Any?): Any? =
            calls.call(version, what, nothing = Threw, block).also { if (it === Threw) broken = true }
    }

    /** The answer of a call into the picker that threw. */
    private object Threw
}
