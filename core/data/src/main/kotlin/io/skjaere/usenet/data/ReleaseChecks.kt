package io.skjaere.usenet.data

import io.skjaere.usenet.releases.ReleaseKey

/**
 * What a play does with a release by what is remembered of it ([TriedRelease]): skip it without
 * grabbing its NZB while its files are a copy of a disc, while its NZB lacks files, while it needs a
 * password it doesn't come with, or while every provider in use lacks its articles; else try it. A
 * provider is checked again once it has been 30 days ([EXPIRY_MS]), so a false
 * "missing" from a provider's bad day heals by itself, or sooner once the release plays ([found]),
 * and is told by its settings id and server ([provider]), so one added or edited since is tried.
 */
object ReleaseChecks {
    /** How long a check counts. */
    const val EXPIRY_MS: Long = 30L * 24 * 60 * 60 * 1000

    /** The most releases remembered; the least recently checked go first. */
    const val MAX_ENTRIES = 2_000

    /** What a release is remembered by: its indexer and guid, as a search result's key ([ReleaseKey]); null for one without (an imported NZB). */
    fun key(indexerId: String?, guid: String?): String? =
        if (indexerId.isNullOrBlank() || guid.isNullOrBlank()) null else ReleaseKey.of(indexerId, guid)

    /** A provider as the checks remember it: its settings [id] with its server, so the provider edited to another is another. */
    fun provider(id: String, host: String, port: Int): String = "$id@${host.trim().lowercase()}:$port"

    /**
     * Why a play skips [entry]'s release at [now] without grabbing it, with the providers [inUse]
     * ([provider]s); null to try it: nothing is known of it for them, or the user chose it ([chosen]).
     */
    fun skip(entry: TriedRelease?, inUse: Collection<String>, now: Long, chosen: Boolean): KnownMissing? =
        if (chosen) null else known(entry, inUse, now)

    /**
     * What [entry] says of its release at [now] with the providers [inUse], chosen or not (what the
     * release list shows): its files are a copy of a disc, its NZB lacks files, it needs a password it
     * doesn't come with, or every one of them lacks its articles, last checked then; null while one in
     * use wasn't checked, or not in the last [EXPIRY_MS], or none is in use. The first three hold
     * whatever the providers, a disc copy first: it is what the release is, not what was found of it.
     */
    fun known(entry: TriedRelease?, inUse: Collection<String>, now: Long): KnownMissing? {
        entry ?: return null
        entry.discAt?.takeIf { now - it < EXPIRY_MS }?.let { return KnownMissing(it, incompleteNzb = false, discCopy = true) }
        entry.incompleteAt?.takeIf { now - it < EXPIRY_MS }?.let { return KnownMissing(it, incompleteNzb = true) }
        entry.passwordAt?.takeIf { now - it < EXPIRY_MS }?.let { return KnownMissing(it, incompleteNzb = false, passwordNeeded = true) }
        if (inUse.isEmpty()) return null
        val checked = entry.missingOn.filter { now - it.checkedAt < EXPIRY_MS }.associate { it.provider to it.checkedAt }
        return if (inUse.all { it in checked }) KnownMissing(inUse.maxOf { checked.getValue(it) }, incompleteNzb = false) else null
    }

    /**
     * [existing] (none: a new entry for [indexerId]/[guid]) with each of [providers] found lacking its
     * articles at [now], or its NZB lacking files ([incomplete]), or it needing a password it doesn't
     * come with ([needsPassword]), or its files a copy of a disc ([discCopy]); the checks expired by
     * then are dropped.
     */
    fun record(
        existing: TriedRelease?, indexerId: String, guid: String, providers: Collection<String>, incomplete: Boolean, now: Long,
        needsPassword: Boolean = false, discCopy: Boolean = false,
    ): TriedRelease {
        val kept = existing?.missingOn.orEmpty().filter { now - it.checkedAt < EXPIRY_MS && it.provider !in providers }
        val added = providers.distinct().map { ProviderCheck(it, now) }
        val checks = (kept + added).sortedBy { it.provider }
        return TriedRelease(
            id = ReleaseKey.of(indexerId, guid),
            indexerId = indexerId,
            guid = guid,
            missingOn = checks,
            incompleteAt = if (incomplete) now else existing?.incompleteAt?.takeIf { now - it < EXPIRY_MS },
            updatedAt = now,
            passwordAt = if (needsPassword) now else existing?.passwordAt?.takeIf { now - it < EXPIRY_MS },
            discAt = if (discCopy) now else existing?.discAt?.takeIf { now - it < EXPIRY_MS },
        )
    }

    /**
     * [existing] once its release passed a play's checks at [now] with the providers [inUse]: it isn't
     * missing on them, whatever was found before (a provider's bad day, a post still propagating), its
     * NZB doesn't lack what plays, it came with the password it needs, and it is no copy of a disc.
     * Their checks go, with those expired by then; null once none is left.
     */
    fun found(existing: TriedRelease, inUse: Collection<String>, now: Long): TriedRelease? {
        val kept = existing.missingOn.filter { now - it.checkedAt < EXPIRY_MS && it.provider !in inUse }
        return if (kept.isEmpty()) null else existing.copy(missingOn = kept, incompleteAt = null, passwordAt = null, discAt = null, updatedAt = kept.maxOf { it.checkedAt })
    }
}

/**
 * A release known to be missing: its files are a copy of a disc ([discCopy]), its NZB lacks files ([incompleteNzb]), or
 * it needs a password it doesn't come with ([passwordNeeded]), else every provider in use lacks its articles; last
 * checked at [checkedAt].
 */
data class KnownMissing(val checkedAt: Long, val incompleteNzb: Boolean, val passwordNeeded: Boolean = false, val discCopy: Boolean = false)
