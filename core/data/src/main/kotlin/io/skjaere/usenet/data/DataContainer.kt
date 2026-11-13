package io.skjaere.usenet.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import kotlinx.coroutines.CoroutineScope
import java.io.File

/** Builds the stores; one instance per process. */
class DataContainer(context: Context, scope: CoroutineScope, clock: Clock = Clock.System) {
    private val dir = File(context.filesDir, "datastore")

    private val settingsStore = settingsStore(scope) { File(dir, "settings.json") }
    private val secretsStore = DataStoreFactory.create(
        serializer = JsonFileSerializer(SecretsFile.serializer(), SecretsFile()),
        corruptionHandler = ReplaceFileCorruptionHandler { SecretsFile() },
        scope = scope,
        produceFile = { File(context.filesDir, SECRETS_PATH) },
    )

    // Device state: an unreadable file starts over, its pairings gone with it, as a lost key's are (L20).
    private val linkStore = DataStoreFactory.create(
        serializer = JsonFileSerializer(LinkFile.serializer(), LinkFile()),
        corruptionHandler = ReplaceFileCorruptionHandler { LinkFile() },
        scope = scope,
        produceFile = { File(context.filesDir, LINK_PATH) },
    )

    val secrets = SecretStore(secretsStore, KeystoreCipher())
    val settings = SettingsRepository(settingsStore, secrets, clock)
    val library = LibraryRepository(LibraryDatabase.create(context), clock)
    val link = LinkStore(linkStore, clock)

    companion object {
        /** The secrets file under filesDir; the app's backup and data extraction rules exclude it by this path. */
        const val SECRETS_PATH = "datastore/secrets.json"

        /** Play on TV's file under filesDir (L29); excluded from backups and transfers as the secrets are, by this path. */
        const val LINK_PATH = "datastore/link.json"
    }
}

/**
 * The settings' store over [file], bringing a file an earlier version wrote up to date as it is first read
 * ([ServicesInOrder]). An unreadable file would otherwise fail every read; it starts over instead.
 */
internal fun settingsStore(scope: CoroutineScope, file: () -> File): DataStore<AppSettings> = DataStoreFactory.create(
    serializer = JsonFileSerializer(AppSettings.serializer(), AppSettings()),
    corruptionHandler = ReplaceFileCorruptionHandler { AppSettings() },
    migrations = listOf(ServicesInOrder),
    scope = scope,
    produceFile = file,
)
