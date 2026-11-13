package io.skjaere.usenet.app

import io.skjaere.usenet.app.settings.NoUsableProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The import screen's work: reads a picked file, prepares it with [prepare] and unlocks it with a
 * password, or prepares it again once a provider is set up. One file at a time: picking another
 * drops the one still being read or prepared.
 */
class NzbImporter(
    private val scope: CoroutineScope,
    private val prepare: suspend (bytes: ByteArray, title: String, password: String?) -> ImportUi,
) {
    private val _state = MutableStateFlow<ImportUi>(ImportUi.Idle)
    val state: StateFlow<ImportUi> = _state.asStateFlow()
    private var job: Job? = null

    /**
     * The NZB (bytes and title) the screen asks a password or a provider for: what [unlock] and
     * [retry] prepare again.
     */
    private var locked: Pair<ByteArray, String>? = null

    /** Opens the file [read] returns (its bytes and title). */
    fun open(read: suspend () -> Pair<ByteArray, String>) = start(ImportUi.Working(null)) {
        val (bytes, title) = try {
            read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ensureActive()
            _state.value = ImportUi.Error(describeReadFailure(e))
            return@start
        }
        // A read that finishes without noticing it was dropped mustn't prepare its file.
        ensureActive()
        _state.value = ImportUi.Working(title)
        prepareAndShow(bytes, title, null)
    }

    fun unlock(password: String) {
        val (bytes, title) = locked ?: return
        start(ImportUi.Working(title)) { prepareAndShow(bytes, title, password) }
    }

    /**
     * The saved providers changed, and [missing] says why none is usable (null while one is): the file
     * waiting for one is prepared once one is, and until then says why none is now (S9-9).
     */
    fun providersChanged(missing: NoUsableProvider?) {
        val waiting = _state.value as? ImportUi.NeedsProvider ?: return
        if (missing == null) retry() else _state.value = waiting.copy(reason = missing)
    }

    /** Prepares again the file that waits for a usable Usenet provider, once there is one; nothing otherwise. */
    fun retry() {
        if (_state.value !is ImportUi.NeedsProvider) return
        val (bytes, title) = locked ?: return
        start(ImportUi.Working(title)) { prepareAndShow(bytes, title, null) }
    }

    fun reset() {
        job?.cancel()
        job = null
        locked = null
        _state.value = ImportUi.Idle
    }

    /** Drops the running import and shows [working] at once, then runs [block]. */
    private fun start(working: ImportUi.Working, block: suspend CoroutineScope.() -> Unit) {
        job?.cancel()
        locked = null
        _state.value = working
        job = scope.launch(block = block)
    }

    private suspend fun prepareAndShow(bytes: ByteArray, title: String, password: String?) {
        val next = prepare(bytes, title, password)
        currentCoroutineContext().ensureActive()
        locked = (bytes to title).takeIf { next is ImportUi.NeedsPassword || next is ImportUi.NeedsProvider }
        _state.value = next
    }
}
