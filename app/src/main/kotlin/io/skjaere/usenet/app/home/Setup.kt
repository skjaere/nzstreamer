package io.skjaere.usenet.app.home

/** The first-run screen's steps, in order: Usenet's way, the torrent way (while debrid shows), then the TMDB key. */
enum class SetupStep(val title: String, val need: String) {
    Provider("Usenet provider", "Required"),
    Indexer("Newznab indexer", "Required"),
    Service("Debrid service", "Required"),
    TorrentIndexer("Torrent indexer", "Required"),
    Metadata("TMDB API key", "Optional"),
}

/** One step's row: its number while to do, a check mark once [done]; [need] beside it, none where it says nothing. */
data class SetupRow(val step: SetupStep, val number: Int, val done: Boolean, val subtitle: String, val need: String = step.need)

/** A group of the first run's rows, under its [heading] (none for today's single group, or the TMDB key's). */
data class SetupGroup(val heading: String?, val rows: List<SetupRow>)

/**
 * What the first-run screen shows, from what is saved: nothing is filled in for the user. Home shows it instead of its
 * content until [complete]: one of two pairs is saved (L165), Usenet's (a provider, and an indexer or "Not now",
 * [indexerSkipped]) or the torrent pair (a service account and a torrent indexer), which counts only while debrid is
 * [shown] (L192). The TMDB key is optional. Hidden, the screen is today's; shown, it offers the two ways, Usenet's first
 * (DbPhoneFirstRun, W81).
 */
data class SetupState(
    /** The saved providers' names (their hosts without one), in priority order; switched off or not. */
    val providers: List<String>,
    /** A Newznab indexer is saved, switched off or not: Usenet's half of a pair alone. */
    val hasIndexer: Boolean,
    val hasTmdbKey: Boolean,
    val indexerSkipped: Boolean,
    /** The saved service accounts' names (their services'), A to Z, switched off or not. */
    val services: List<String> = emptyList(),
    /** A torrent indexer is saved, switched off or not: a service's half of a pair, as a Newznab indexer is never. */
    val hasTorrentIndexer: Boolean = false,
    /** Whether this build shows debrid ([io.skjaere.usenet.app.debrid.DebridGate.shown]); hidden, the screen is today's. */
    val shown: Boolean = false,
) {
    private val hasProvider: Boolean get() = providers.isNotEmpty()
    private val hasService: Boolean get() = services.isNotEmpty()

    /** A service account and a torrent indexer, while debrid shows: the torrent pair, which "Not now" never stands in for. */
    private val torrentPair: Boolean get() = shown && hasService && hasTorrentIndexer

    /** Usenet's way is under way: a provider or a Newznab indexer saved. */
    private val usenetStarted: Boolean get() = hasProvider || hasIndexer

    /** The torrent way is under way: a service or a torrent indexer saved, while debrid shows. */
    private val torrentStarted: Boolean get() = shown && (hasService || hasTorrentIndexer)

    val complete: Boolean get() = hasProvider && (hasIndexer || indexerSkipped) || torrentPair

    /**
     * A pair is saved (switched off or not), a provider and an indexer or the torrent pair: the phone setup's page is then
     * the edit page, else the wizard. Not [complete]: "Not now" lets Home show without an indexer, but searching still
     * needs one.
     */
    val canPlay: Boolean get() = hasProvider && hasIndexer || torrentPair

    /**
     * The first required step still to do: what the primary button adds. Usenet's way while it is under way, or while
     * neither is (Usenet first, W81); the torrent way's once it alone is.
     */
    val next: SetupStep?
        get() = when {
            usenetStarted || !torrentStarted -> when {
                !hasProvider -> SetupStep.Provider
                !hasIndexer -> SetupStep.Indexer
                else -> null
            }
            !hasService -> SetupStep.Service
            !hasTorrentIndexer -> SetupStep.TorrentIndexer
            else -> null
        }

    val action: String? get() = next?.let(::actionOf)

    /** The second way in, under the first (DbPhoneFirstRun 2): the debrid service, while debrid shows and nothing is under way. */
    val alternative: SetupStep? get() = SetupStep.Service.takeIf { shown && !usenetStarted && !torrentStarted }

    val alternativeAction: String? get() = alternative?.let(::actionOf)

    private fun actionOf(step: SetupStep): String = when (step) {
        SetupStep.Provider -> "Add a Usenet provider"
        SetupStep.Indexer -> "Add an indexer"
        SetupStep.Service -> "Add a debrid service"
        SetupStep.TorrentIndexer -> "Add a torrent indexer"
        SetupStep.Metadata -> "Add a TMDB key"
    }

    /** "Not now": Home without an indexer, once there is a provider to play imported NZBs with. */
    val canSkip: Boolean get() = hasProvider && !hasIndexer

    /** "news.example.net", or with several "Main provider and 2 more". */
    private val providerLine: String?
        get() = providers.firstOrNull()?.let { first -> if (providers.size > 1) "$first and ${providers.size - 1} more" else first }

    private val metadataRow: SetupRow get() = SetupRow(SetupStep.Metadata, 3, hasTmdbKey, "Enables your collection, posters and episode lists")

    /**
     * The screen's groups. Hidden, today's one group. Shown, Usenet's way and the torrent way under their headings, each
     * step's "Required" only once its way is under way (either way finishes), then the TMDB key's.
     */
    val groups: List<SetupGroup>
        get() {
            if (!shown) {
                return listOf(
                    SetupGroup(
                        null,
                        listOf(
                            SetupRow(SetupStep.Provider, 1, hasProvider, providerLine ?: "Your Usenet account, to stream from"),
                            SetupRow(SetupStep.Indexer, 2, hasIndexer, "To search for releases"),
                            metadataRow,
                        ),
                    ),
                )
            }
            fun need(started: Boolean, done: Boolean) = if (started && !done) "Required" else ""
            return listOf(
                SetupGroup(
                    "Usenet",
                    listOf(
                        SetupRow(SetupStep.Provider, 1, hasProvider, providerLine ?: "Your Usenet account, to stream from", need(usenetStarted, hasProvider)),
                        SetupRow(SetupStep.Indexer, 2, hasIndexer, "To search for Usenet releases", need(usenetStarted, hasIndexer)),
                    ),
                ),
                SetupGroup(
                    "Or torrents, through a debrid service",
                    listOf(
                        SetupRow(SetupStep.Service, 1, hasService, services.joinToString().ifEmpty { "Your debrid account, with its API key" }, need(torrentStarted, hasService)),
                        SetupRow(SetupStep.TorrentIndexer, 2, hasTorrentIndexer, "Prowlarr or Jackett, to search for torrents", need(torrentStarted, hasTorrentIndexer)),
                    ),
                ),
                SetupGroup(null, listOf(metadataRow)),
            )
        }

    val rows: List<SetupRow> get() = groups.flatMap { it.rows }
}
