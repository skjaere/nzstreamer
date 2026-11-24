package io.skjaere.usenet.app.plugins

import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.contentLength
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest

/** A plugin's file fetched as the list gives it, for the installer (L633). */
fun interface PluginDownloads {
    /**
     * Plugin [id]'s [version], downloaded into the download folder; [progress] hears the bytes read so far. The file of a
     * [Download.Done] is the caller's, to delete once it is installed or refused; nothing of any other outcome is left.
     */
    suspend fun download(id: String, version: ListedVersion, progress: (Long) -> Unit): Download
}

/** What a download came to. */
sealed interface Download {
    /** The whole [file], its SHA-256 the list's: nothing has parsed it yet. */
    class Done(val file: File) : Download

    /** Too little space for the download and the stored copy: [toFree] bytes more are needed. */
    class NoSpace(val toFree: Long) : Download

    /** It didn't come, for [kind]. */
    class Failed(val kind: DownloadFailure) : Download
}

/** Why a download failed. Logs name it by its name alone, never an address. */
enum class DownloadFailure {
    /** The list's address isn't the plugin's own release file (checked again as it becomes a request). */
    Address,

    /** A hop that isn't https (or http to the loopback address a test's source allows). */
    Insecure,

    /** More than [PluginDownloader.MAX_REDIRECTS] redirects, or one that says nowhere to go. */
    Redirects,

    /** An answer that is neither the file nor a redirect. */
    Status,

    /** A body longer than the list's size. */
    TooLarge,

    /** Its SHA-256 isn't the list's. */
    Mismatch,

    /** No answer, or one cut short. */
    Network,

    /** It couldn't be written, and not for want of space. */
    Write,
}

/**
 * Downloads a plugin's file from [source]'s releases (L633, L620), into [folder] (cacheDir/plugins/.download): first a
 * look at the space [space] says is free where plugins are kept, twice the list's size (the download and the stored
 * copy), before anything is asked; then the list's address, checked whole again, and at most [MAX_REDIRECTS] redirects,
 * every hop https (GitHub's release files are a redirect or two away, on another host), or http to the loopback address
 * where [source] allows it; at most the list's size read, the SHA-256 taken as the bytes come. A file whose hash isn't the
 * list's is deleted before anything reads it. Through [http], the app's service client, which follows no redirect itself,
 * as [PluginSource.USER_AGENT]. A Cancel (the caller's job cancelled) stops it and deletes what came.
 */
class PluginDownloader(
    private val source: PluginSource,
    private val http: HttpClient,
    private val folder: File,
    private val space: () -> Long,
) : PluginDownloads {
    override suspend fun download(id: String, version: ListedVersion, progress: (Long) -> Unit): Download {
        val needed = 2 * version.size
        val free = space()
        if (free < needed) return Download.NoSpace(needed - free)
        // The list checked it as it read it; this is where it becomes a request.
        if (!source.apkAddress(id, version.url)) return Download.Failed(DownloadFailure.Address)
        val file = File(folder, "$id-${version.versionCode}.apk")
        begin(file)
        var kept = false
        try {
            var address = version.url
            repeat(MAX_REDIRECTS + 1) {
                if (!source.allows(address)) return Download.Failed(DownloadFailure.Insecure)
                when (val hop = hop(address, file, version, progress)) {
                    is Hop.Redirect -> address = hop.to
                    is Hop.Ended -> return hop.download.also { kept = it is Download.Done }
                }
            }
            return Download.Failed(DownloadFailure.Redirects)
        } finally {
            if (!kept) file.delete()
            end(file)
        }
    }

    /** One request: a redirect's next address, or how the download ended. */
    private suspend fun hop(address: String, file: File, version: ListedVersion, progress: (Long) -> Unit): Hop = try {
        http.prepareGet(address) {
            header(HttpHeaders.UserAgent, PluginSource.USER_AGENT)
            timeout { requestTimeoutMillis = DOWNLOAD_TIMEOUT_MS }
        }.execute { response ->
            when (response.status.value) {
                in REDIRECTS -> response.headers[HttpHeaders.Location]?.let { next(address, it) }?.let(Hop::Redirect)
                    ?: Hop.Ended(Download.Failed(DownloadFailure.Redirects))
                OK -> Hop.Ended(save(response, file, version, progress))
                else -> Hop.Ended(Download.Failed(DownloadFailure.Status))
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: WriteFailed) {
        // A disk that filled up says so, as the space check before it would have.
        val free = space()
        Hop.Ended(if (free < 2 * version.size) Download.NoSpace(2 * version.size - free) else Download.Failed(DownloadFailure.Write))
    } catch (e: Exception) {
        // A timeout, a refused connection, a broken TLS, a body cut short: only its kind matters, and it is logged no more.
        Hop.Ended(Download.Failed(DownloadFailure.Network))
    }

    /**
     * The body into [file], no further than the list's size, its SHA-256 taken as it comes: a file whose hash isn't the
     * list's is a mismatch, and the caller deletes it before anything parses it.
     */
    private suspend fun save(response: HttpResponse, file: File, version: ListedVersion, progress: (Long) -> Unit): Download {
        if ((response.contentLength() ?: 0) > version.size) return Download.Failed(DownloadFailure.TooLarge)
        val body = response.bodyAsChannel()
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_BYTES)
        var read = 0L
        writing { FileOutputStream(file) }.use { out ->
            while (true) {
                val n = body.readAvailable(buffer, 0, buffer.size)
                if (n < 0) break
                read += n
                if (read > version.size) return Download.Failed(DownloadFailure.TooLarge)
                digest.update(buffer, 0, n)
                writing { out.write(buffer, 0, n) }
                progress(read)
            }
        }
        return if (Sha256.hex(digest.digest()) == version.sha256) Download.Done(file) else Download.Failed(DownloadFailure.Mismatch)
    }

    /** [step] on the download's file: what it throws is the disk's, never the network's. */
    private inline fun <T> writing(step: () -> T): T = try {
        step()
    } catch (e: IOException) {
        throw WriteFailed()
    }

    /** The file is being written: anything else in the folder that no download of this process holds is a death's leftover. */
    private fun begin(file: File) {
        synchronized(running) {
            running += file.path
            if (!folder.isDirectory) folder.mkdirs()
            folder.listFiles().orEmpty().filter { it.path !in running }.forEach { it.delete() }
        }
    }

    private fun end(file: File) {
        synchronized(running) { running -= file.path }
    }

    private sealed interface Hop {
        class Redirect(val to: String) : Hop

        class Ended(val download: Download) : Hop
    }

    /** The disk refused a write. */
    private class WriteFailed : Exception(null, null, false, false)

    companion object {
        /** Redirects followed at most: GitHub's release file is two away, on its assets' host. */
        const val MAX_REDIRECTS = 3

        /** A download's whole time, its body's included: a plugin is a few hundred KB, and a slow line may take a while. */
        const val DOWNLOAD_TIMEOUT_MS = 120_000L

        private const val OK = 200
        private val REDIRECTS = setOf(301, 302, 303, 307, 308)
        private const val BUFFER_BYTES = 64 * 1024

        /** The downloads this process writes now, by path: every downloader's, as they share one folder. */
        private val running = mutableSetOf<String>()

        /** A redirect's [location] against the [address] that gave it; null where it doesn't read as an address. */
        private fun next(address: String, location: String): String? = try {
            URI(address).resolve(location.trim()).toString()
        } catch (e: URISyntaxException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
