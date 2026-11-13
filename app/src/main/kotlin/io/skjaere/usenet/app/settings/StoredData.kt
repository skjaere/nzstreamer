package io.skjaere.usenet.app.settings

import android.content.Context
import coil3.SingletonImageLoader
import io.skjaere.usenet.core.PreparedNzbStore
import io.skjaere.usenet.data.LibraryRepository
import io.skjaere.usenet.metadata.MetadataCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What the app keeps on the device besides the video cache, each with its size and a Clear: posters
 * and images (the image loader's disk cache), the TMDB responses kept by [metadata] (Settings ›
 * Metadata clears them too, through this), the NZB files [nzbs] keeps for resume, and the releases
 * the [library] remembers as found incomplete. Sizes are walked and files deleted on the io dispatcher.
 */
class StoredData(private val context: Context, private val metadata: MetadataCache, private val nzbs: PreparedNzbStore, private val library: LibraryRepository) {
    suspend fun imageBytes(): Long = withContext(Dispatchers.IO) { images()?.size ?: 0 }

    suspend fun clearImages() = withContext(Dispatchers.IO) { images()?.clear() ?: Unit }

    suspend fun metadataBytes(): Long = withContext(Dispatchers.IO) { metadata.sizeBytes() }

    suspend fun clearMetadata() = withContext(Dispatchers.IO) { metadata.clear() }

    suspend fun nzbFiles(): PreparedNzbStore.StoredFiles = nzbs.storedFiles()

    /** Resuming a release whose NZB this deleted grabs it from the indexer again. */
    suspend fun clearNzbFiles() = nzbs.clearStored()

    suspend fun triedReleases(): Int = library.triedReleaseCount()

    /** Plays grab and check each of them again. */
    suspend fun clearTriedReleases() = library.clearTriedReleases()

    /** The app's image loader's disk cache, opened (its journal read) on first use: call on the io dispatcher. */
    private fun images() = SingletonImageLoader.get(context).diskCache
}
