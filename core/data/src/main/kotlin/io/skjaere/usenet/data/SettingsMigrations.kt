package io.skjaere.usenet.data

import androidx.datastore.core.DataMigration

/**
 * Version 2 (L228, W85): the debrid services' order is their place in [AppSettings.services], moved by the user as Usenet
 * providers are. Version 1 kept them in the order they were signed in to, and played them A to Z by service, so a file
 * it wrote is sorted A to Z once, as it is first read: nobody's first service changes by itself. A file of version 2 or
 * later, a new one included, is left as it is.
 */
internal object ServicesInOrder : DataMigration<AppSettings> {
    override suspend fun shouldMigrate(currentData: AppSettings): Boolean = currentData.schemaVersion < 2

    // By the kind's name, which for the services version 1 knew is the name it sorted by.
    override suspend fun migrate(currentData: AppSettings): AppSettings =
        currentData.copy(schemaVersion = 2, services = currentData.services.sortedBy { it.kind })

    override suspend fun cleanUp() = Unit
}

/**
 * Version 3: a play tries up to 10 releases by default, where it tried 3. Every field is written, defaults too, so a file
 * saved while the default was 3 holds 3 whether or not it was chosen. Any change to a playback setting stamps playback's
 * [PlaybackPrefs.updatedAt], so one still at 0 never had one chosen: its 3 is the old default, and moves to 10. A 3 with a
 * playback setting chosen stays, as the file can't tell it from a choice; so does any other count.
 */
internal object DefaultReleasesToTry : DataMigration<AppSettings> {
    override suspend fun shouldMigrate(currentData: AppSettings): Boolean = currentData.schemaVersion < 3

    override suspend fun migrate(currentData: AppSettings): AppSettings {
        val playback = currentData.playback
        val unchosen = playback.updatedAt == 0L && playback.releasesToTry == 3
        return currentData.copy(schemaVersion = 3, playback = if (unchosen) playback.copy(releasesToTry = 10) else playback)
    }

    override suspend fun cleanUp() = Unit
}
