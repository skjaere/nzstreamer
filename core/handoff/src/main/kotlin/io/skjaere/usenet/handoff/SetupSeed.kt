package io.skjaere.usenet.handoff

/** Which page the phone gets: the wizard, step by step, while the TV can't play yet; the edit page once it can. */
enum class SetupMode { Wizard, Edit }

/** Where the phone's page is: one of the wizard's four steps, or the edit page. */
enum class SetupStep { Providers, Indexers, Tmdb, Check, Edit }

/** Whether a saved item has a password or key: none, one, or one this TV can't read. Never the secret itself. */
enum class SavedSecret { None, Saved, Unreadable }

/**
 * A saved provider as the page may show it: its non-secret fields as an untouched card sends them back, whether it has
 * a login ([login]), and [ref], the record's id, which stays on the TV: a page names the card by its slot alone. It has
 * no field that could hold a password.
 */
data class SavedProvider(
    val ref: String,
    val name: String,
    val host: String,
    val port: Int,
    val useTls: Boolean,
    val username: String,
    val connections: Int,
    val enabled: Boolean,
    val login: SavedSecret,
) {
    override fun toString() =
        "SavedProvider(ref=$ref, name=${shown(name)}, host=${shown(host)}, port=$port, useTls=$useTls, username=${shown(username)}, " +
            "connections=$connections, enabled=$enabled, login=$login)"
}

/**
 * A saved indexer as the page may show it: [shownUrl] is its address without a query, a fragment or a login
 * (`scheme://host[:port]/path`), and [hiddenParts] says the saved one had some, kept on the TV. An address posted back
 * exactly as shown is the saved one. No field could hold an API key.
 */
data class SavedIndexer(
    val ref: String,
    val name: String,
    val shownUrl: String,
    val hiddenParts: Boolean,
    val enabled: Boolean,
    val key: SavedSecret,
) {
    override fun toString() =
        "SavedIndexer(ref=$ref, name=${shown(name)}, shownUrl=${shown(shownUrl)}, hiddenParts=$hiddenParts, enabled=$enabled, key=$key)"
}

/**
 * A debrid service the page offers (L209): [value] is what its radio button sends, [name] what the page calls it, and
 * [keyPlace] where the service keeps the API key ("In TorBox: Settings › API key"). The app's, as data: the page names
 * no service of its own (W80).
 */
data class ServiceChoice(val value: String, val name: String, val keyPlace: String)

/**
 * A saved debrid service account as the page may show it: its service ([kind], a [ServiceChoice.value]), whose account
 * it is as the service named it ([account], "" when no test named it yet), whether it is on, and whether its API key is
 * saved and can be read here. No field could hold the key.
 */
data class SavedService(
    val ref: String,
    val kind: String,
    val account: String,
    val enabled: Boolean,
    val key: SavedSecret,
) {
    override fun toString() = "SavedService(ref=$ref, kind=$kind, account=${shown(account)}, enabled=$enabled, key=$key)"
}

/**
 * A saved torrent indexer as the page may show it: as [SavedIndexer], and whether it is used with the debrid service
 * ([useWithService], W75). No field could hold an API key.
 */
data class SavedTorrentIndexer(
    val ref: String,
    val name: String,
    val shownUrl: String,
    val hiddenParts: Boolean,
    val enabled: Boolean,
    val useWithService: Boolean,
    val key: SavedSecret,
) {
    override fun toString() =
        "SavedTorrentIndexer(ref=$ref, name=${shown(name)}, shownUrl=${shown(shownUrl)}, hiddenParts=$hiddenParts, enabled=$enabled, " +
            "useWithService=$useWithService, key=$key)"
}

/**
 * What is saved on the TV as a session's page starts from it, providers in the order they are tried. Secret-free by type.
 * [serviceKinds] are the debrid services the page offers, A to Z: none while the app hides debrid (L192), and then the
 * page has no Debrid service or Torrent indexer section, whatever [services] and [torrentIndexers] hold.
 */
data class SetupSeed(
    val providers: List<SavedProvider> = emptyList(),
    val indexers: List<SavedIndexer> = emptyList(),
    val tmdbKey: SavedSecret = SavedSecret.None,
    val serviceKinds: List<ServiceChoice> = emptyList(),
    val services: List<SavedService> = emptyList(),
    val torrentIndexers: List<SavedTorrentIndexer> = emptyList(),
) {
    /** Whether the page has its Debrid service and Torrent indexer sections: only while the app shows debrid. */
    val debrid: Boolean get() = serviceKinds.isNotEmpty()
}

/**
 * A card's place on the page: the TV numbers each kind's cards in page order, so "p2" is the second provider. A page
 * names a card by its slot, never by a record's id, and the TV by its [label], never by anything typed.
 */
data class SetupSlot(val section: SetupSection, val number: Int) {
    /** As a page writes it, in a button's value and an anchor: "p2", "i1", "s1", "r3", "t". */
    val key: String
        get() = when (section) {
            SetupSection.Provider -> "p$number"
            SetupSection.Indexer -> "i$number"
            SetupSection.Service -> "s$number"
            SetupSection.TorrentIndexer -> "r$number"
            SetupSection.Tmdb -> "t"
        }

    /** "Provider 2", "Indexer 1", "Debrid service 1", "Torrent indexer 3", "TMDB key": by place alone. */
    val label: String
        get() = when (section) {
            SetupSection.Provider -> "Provider $number"
            SetupSection.Indexer -> "Indexer $number"
            SetupSection.Service -> "Debrid service $number"
            SetupSection.TorrentIndexer -> "Torrent indexer $number"
            SetupSection.Tmdb -> "TMDB key"
        }

    override fun toString() = key

    companion object {
        val Tmdb = SetupSlot(SetupSection.Tmdb, 1)

        /** The slot [key] names ("p2", "i10", "s1", "r1", "t"), or null: a number with no leading zero and at most three digits. */
        fun of(key: String): SetupSlot? {
            if (key == "t") return Tmdb
            val section = when (key.firstOrNull()) {
                'p' -> SetupSection.Provider
                'i' -> SetupSection.Indexer
                's' -> SetupSection.Service
                'r' -> SetupSection.TorrentIndexer
                else -> return null
            }
            val digits = key.drop(1)
            if (digits.isEmpty() || digits.length > 3 || digits[0] == '0' || !digits.all { it in '0'..'9' }) return null
            return SetupSlot(section, digits.toInt())
        }
    }
}

/** What happens to one saved or new item at Send: [ref] null for a new one; a removed one's draft is as saved. */
data class SetupChange<out D>(val ref: String?, val draft: D, val removed: Boolean)

/**
 * What a session hands over at Send: every provider, indexer, debrid service and torrent indexer card in page order,
 * saved ones (changed or not) with their [SetupChange.ref], new ones without, blank new ones left out; and the TMDB key
 * typed, or null. Absence never removes anything: only [SetupChange.removed] does. In a draft an empty password or key
 * keeps the saved one.
 */
data class SetupChanges(
    val providers: List<SetupChange<ProviderDraft>>,
    val indexers: List<SetupChange<IndexerDraft>>,
    val tmdbKey: String?,
    val services: List<SetupChange<ServiceDraft>> = emptyList(),
    val torrentIndexers: List<SetupChange<TorrentIndexerDraft>> = emptyList(),
) {
    override fun toString() =
        "SetupChanges(providers=$providers, indexers=$indexers, tmdbKey=${shown(tmdbKey)}, services=$services, torrentIndexers=$torrentIndexers)"
}

/** How a value prints: whether it's there, never what it is. */
internal fun shown(value: String?) = if (value.isNullOrEmpty()) "none" else "set"
