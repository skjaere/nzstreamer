package io.skjaere.usenet.app.plugins.sportarr

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import io.skjaere.usenet.app.plugins.InstallRequest
import io.skjaere.usenet.app.plugins.InstallResult
import io.skjaere.usenet.app.plugins.PluginStateRule
import io.skjaere.usenet.app.plugins.PluginStore
import io.skjaere.usenet.app.plugins.Sha256
import java.io.File

/** The Sportarr plugin's id, as its builds carry it (L639). */
const val SPORTARR = "sportarr"

/** Its entry class, made by its own loader. */
const val SPORTARR_ENTRY = "app.nzstreamer.plugin.sportarr.SportarrPlugin"

/**
 * The Sportarr plugin's debug build in the test APK's assets (L657): sportarr.apk, version 0.1.0 (versionCode 10000, its
 * version.txt's by the plugin convention), signed as the app under test is, so a debug run trusts it through the test
 * signer (L622).
 */
object SportarrBuild {
    const val ASSET = "sportarr.apk"
    const val NAME = "Sportarr"
    const val VERSION_CODE = 10_000L

    /** The build as a download leaves it in cacheDir/plugins/.download: a writable file nothing has checked yet. */
    fun download(context: Context): File {
        val file = File(File(File(context.cacheDir, PluginStore.DIR), DOWNLOADS), ASSET)
        file.parentFile!!.mkdirs()
        InstrumentationRegistry.getInstrumentation().context.assets.open(ASSET).use { input -> file.outputStream().use { input.copyTo(it) } }
        return file
    }

    /** What the plugin list would say of this build: its id, name and version, and its file's [sha256]. */
    fun request(sha256: String) = InstallRequest(SPORTARR, NAME, VERSION_CODE, sha256)

    private const val DOWNLOADS = ".download"
}

/**
 * The real plugin installed in the app's own registry, as the Plugins page's installer does after a download (L657): its
 * hosts are the app's, with its http, search, play and watch. Never a [PluginStateRule.registry], whose hosts have none of
 * them. The rule removes it after the test, with its files and secrets.
 */
suspend fun PluginStateRule.installSportarr(): InstallResult {
    val download = SportarrBuild.download(context)
    return app.install(download, SportarrBuild.request(Sha256.of(download)))
}
