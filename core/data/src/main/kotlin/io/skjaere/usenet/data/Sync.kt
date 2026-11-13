package io.skjaere.usenet.data

/**
 * Conventions that keep the stored data mergeable across devices, should sync be added:
 *
 * - Every record that could sync has a stable `id` (a natural key such as `tmdb:123` where one
 *   exists, else a random UUID), an `updatedAt` (epoch ms, set on every change) and a
 *   `deleted` tombstone instead of being removed. Merge rule: newest `updatedAt` wins per record.
 * - Secrets (API keys, passwords) never live in those records; they are in [SecretStore],
 *   encrypted with a key that never leaves this device.
 * - Device-specific state (providers' connection counts, caches, decoder support) is kept out
 *   of synced records.
 */
fun interface Clock {
    fun now(): Long

    companion object {
        val System = Clock { java.lang.System.currentTimeMillis() }
    }
}
