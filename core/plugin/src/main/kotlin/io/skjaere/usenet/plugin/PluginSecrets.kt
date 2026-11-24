package io.skjaere.usenet.plugin

import kotlinx.coroutines.flow.Flow

/**
 * The plugin's own secrets, encrypted in NZStreamer's secret store as plugin:<plugin id>:<name>, never backed up or
 * copied to a new device, and deleted on Remove. A [name] is 1 to 40 lower-case letters, digits, dots, underscores and
 * dashes ([PluginKeys.validSecretName]); another is refused with an [IllegalArgumentException]. Kept apart for tidiness,
 * not as a wall: any code inside NZStreamer could read them.
 */
interface PluginSecrets {
    /** The secret, or null when none is stored or it can't be decrypted (after a restore onto another device). */
    suspend fun get(name: String): String?

    /** Stores [value]; null removes the secret. */
    suspend fun put(name: String, value: String?)

    /** Whether [get] would give the secret, as it changes. */
    fun has(name: String): Flow<Boolean>
}
