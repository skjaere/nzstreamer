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
