package io.skjaere.usenet.app.setup

import io.skjaere.usenet.app.debrid.DebridGate
import io.skjaere.usenet.app.debrid.TorrentIndexerForm
import io.skjaere.usenet.app.debrid.USE_WITH_SERVICE
import io.skjaere.usenet.app.debrid.kindOf
import io.skjaere.usenet.app.debrid.nameOf
import io.skjaere.usenet.app.settings.INDEXER_LOCAL_HTTP
import io.skjaere.usenet.app.settings.INDEXER_PUBLIC_HTTP
import io.skjaere.usenet.app.settings.IndexerForm
import io.skjaere.usenet.app.settings.ProviderForm
import io.skjaere.usenet.app.settings.ProviderLogin
import io.skjaere.usenet.app.settings.displayName
import io.skjaere.usenet.app.settings.newIndexerForm
import io.skjaere.usenet.app.settings.newProviderForm
import io.skjaere.usenet.app.settings.providerForm
import io.skjaere.usenet.data.IndexerWithKey
import io.skjaere.usenet.data.ProviderWithPassword
import io.skjaere.usenet.data.ServiceWithKey
import io.skjaere.usenet.data.SettingsRepository
import io.skjaere.usenet.data.TorrentIndexerWithKey
import io.skjaere.usenet.debrid.ServiceKind
import io.skjaere.usenet.handoff.IndexerDraft
import io.skjaere.usenet.handoff.ProviderDraft
import io.skjaere.usenet.handoff.ServiceDraft
import io.skjaere.usenet.handoff.SetupChange
import io.skjaere.usenet.handoff.SetupChanges
import io.skjaere.usenet.handoff.SetupMode
import io.skjaere.usenet.handoff.TorrentIndexerDraft
import io.skjaere.usenet.newznab.IndexerEndpoint
import io.skjaere.usenet.newznab.UrlSecurity
import kotlinx.coroutines.flow.first

/** A provider's port out of range: the words the review and a test from the phone's page both use. */
const val PROVIDER_PORT_RANGE = "The port must be from 1 to 65535."

/** A provider's connections out of range. */
const val PROVIDER_CONNECTIONS_RANGE = "Connections must be from 1 to 100."

/** An indexer's address that isn't http or https. */
const val INDEXER_URL_SCHEME = "The API URL must start with http:// or https://."

/** A saved provider's card whose server changed with no password typed: the saved one stays with its own server. */
const val PROVIDER_SERVER_CHANGED = "The server changed: send the password too."

/** A saved provider's password this TV can't read, with none typed. */
const val PROVIDER_PASSWORD_UNREADABLE = "The saved password can't be read on this TV: send it again."

/** A saved indexer's card whose address changed with no key typed. */
const val INDEXER_ADDRESS_CHANGED = "The address changed: send the API key too."

/** A saved indexer's key this TV can't read, with none typed. */
const val INDEXER_KEY_UNREADABLE = "The saved API key can't be read on this TV: send it again."

/** A new indexer with no key, or a saved one with none saved. */
const val INDEXER_NO_KEY = "No API key was sent, and none is saved for it."

/** A card for a record deleted on the TV since the phone's page opened: never brought back by what the phone sent. */
const val RECORD_GONE = "It was deleted on this TV meanwhile, so it isn't saved."

/** An item that passed a test from the phone with exactly what would be saved: Save doesn't test it again. */
const val PHONE_TESTED = "Tested from your phone"

/** An item Save tests on the TV first. */
const val TESTED_AT_SAVE = "Tested when you save"

/** What arrived leaves no provider. */
const val ONLY_PROVIDER_REMOVED = "This removes your only provider: nothing will play until you add one."

/** A new key for a saved debrid account, or for one of its service signed in to on the TV meanwhile: Save replaces its key. */
const val SERVICE_KEY_REPLACED = "Replaces the saved key"

/** A torrent indexer sent with "Use with your debrid service" unticked. */
const val NOT_USED_WITH_SERVICE = "Not used with your debrid service"

/** What arrived leaves no indexer. */
const val ONLY_INDEXER_REMOVED = "This removes your only indexer: searching won't work until you add one."

/** What arrived leaves no debrid service, and no provider plays in its place. */
const val ONLY_SERVICE_REMOVED = "This removes your only debrid service: nothing will play until you add one."

/** What arrived leaves no torrent indexer, and no indexer is searched in its place. */
const val ONLY_TORRENT_INDEXER_REMOVED = "This removes your only torrent indexer: searching won't work until you add one."

/** What the review is about: a provider, an indexer, a debrid service, a torrent indexer or the TMDB key, as its rows are titled. */
enum class ReviewKind(val title: String) {
    Provider("Usenet provider"),
    Indexer("Indexer"),
    Service("Debrid service"),
    TorrentIndexer("Torrent indexer"),
    Tmdb("TMDB key"),
}

/** What an item of what arrived does to what is saved. */
enum class ReviewAction { New, Change, Remove }

/** A line of an item on the review; [warn] lines show in the warning colour. */
data class ReviewLine(val text: String, val warn: Boolean = false)

/**
 * One item of what the phone sent, as the TV shows it before anything is saved: what it is ([kind], [action],
 * [heading]: "New", "Changes “X”", "Removes “X”"), its [lines] (secrets masked), why it can't be saved as sent
 * ([problem]; null when it can), and whether it passed a test from the phone with exactly what would be saved
 * ([tested]). [key] is its record's id, or a new one's own.
 */
sealed interface ReviewItem {
    val key: String
    val kind: ReviewKind
    val action: ReviewAction
    val heading: String
    val lines: List<ReviewLine>
    val problem: String?
    val tested: Boolean

    val title: String get() = kind.title

    /** The whole row in the warning colour: a removal. */
    val warn: Boolean get() = action == ReviewAction.Remove

    /** What the page's parts call it, for the tests: its kind and its place among them, "provider 2". */
    val part: String
}

/**
 * A provider added or changed: [form] as it would be saved (over [before], the saved one as it was read, for a change),
 * from what the phone sent ([draft]). [testPassword] is what it logs in with, the one typed or the saved one under
 * [SavedSecretRule], null when it can't be saved as sent. Passwords stay in memory only; [toString] has none.
 */
data class ProviderReview(
    override val key: String,
    override val action: ReviewAction,
    val form: ProviderForm,
    val draft: ProviderDraft,
    val before: ProviderWithPassword?,
    val testPassword: String?,
    override val heading: String,
    override val lines: List<ReviewLine>,
    override val problem: String?,
    override val tested: Boolean,
    override val part: String,
) : ReviewItem {
    override val kind get() = ReviewKind.Provider

    /** What its test logs in with, as the pass log keeps it. */
    val login: ProviderLogin? get() = testPassword?.let { ProviderLogin.of(form.host, form.portValue ?: 0, form.username, form.useTls, it) }

    override fun toString() = "ProviderReview($action, problem=${problem != null}, tested=$tested)"
}

/** An indexer added or changed, as [ProviderReview]: [form] as it would be saved, [testKey] the key it would use. */
data class IndexerReview(
    override val key: String,
    override val action: ReviewAction,
    val form: IndexerForm,
    val draft: IndexerDraft,
    val testKey: String?,
    override val heading: String,
    override val lines: List<ReviewLine>,
    override val problem: String?,
    override val tested: Boolean,
    override val part: String,
) : ReviewItem {
    override val kind get() = ReviewKind.Indexer

    override fun toString() = "IndexerReview($action, problem=${problem != null}, tested=$tested)"
}

/**
 * A debrid service account signed in to with a key typed on the phone (L209): a new account of [service], or a new key
 * for its saved one ([before]: the card's own, or one signed in to on the TV since the page opened, as Save keeps one
 * account a service). Its row reads "Debrid service · New" over "TorBox · API key •••• f318". [apiKey] stays in memory
 * only; [toString] has none.
 */
data class ServiceReview(
    override val key: String,
    override val action: ReviewAction,
    val service: ServiceKind,
    val apiKey: String,
    val before: ServiceWithKey?,
    override val heading: String,
    override val lines: List<ReviewLine>,
    override val problem: String?,
    override val tested: Boolean,
    override val part: String,
) : ReviewItem {
    override val kind get() = ReviewKind.Service

    override fun toString() = "ServiceReview($action, problem=${problem != null}, tested=$tested)"
}

/** A torrent indexer added or changed, as [IndexerReview], with its "Use with your debrid service". */
data class TorrentIndexerReview(
    override val key: String,
    override val action: ReviewAction,
    val form: TorrentIndexerForm,
    val draft: TorrentIndexerDraft,
    val testKey: String?,
    override val heading: String,
    override val lines: List<ReviewLine>,
    override val problem: String?,
    override val tested: Boolean,
    override val part: String,
) : ReviewItem {
    override val kind get() = ReviewKind.TorrentIndexer

    override fun toString() = "TorrentIndexerReview($action, problem=${problem != null}, tested=$tested)"
}

/** The TMDB key typed: new, or in place of the saved one. Saved only when TMDB takes it. */
data class TmdbReview(val apiKey: String, val replacesSaved: Boolean, override val tested: Boolean) : ReviewItem {
    override val key get() = TMDB
    override val kind get() = ReviewKind.Tmdb
    override val action get() = if (replacesSaved) ReviewAction.Change else ReviewAction.New
    override val heading get() = if (replacesSaved) "Changes the saved key" else "New"
    override val lines get() = listOf(ReviewLine("Key ${maskKey(apiKey)}"))
    override val problem: String? get() = null
    override val part get() = "tmdb 1"

    override fun toString() = "TmdbReview($action, tested=$tested)"

    companion object {
        const val TMDB = "tmdb"
    }
}

/**
 * A saved provider, indexer, debrid account or torrent indexer to remove, its secret with it (an account is signed out
 * of on this TV): [name] as the app calls it, [line] what it was.
 */
data class Removal(override val kind: ReviewKind, override val key: String, val name: String, val line: String, override val part: String) : ReviewItem {
    override val action get() = ReviewAction.Remove
    override val heading get() = "Removes “$name”"
    override val lines get() = listOf(ReviewLine(line, warn = true))
    override val problem: String? get() = null
    override val tested get() = false
}

/** A card for a record gone from the TV since the page opened: shown, never saved. */
data class Gone(override val kind: ReviewKind, override val key: String, val name: String, override val part: String) : ReviewItem {
    override val action get() = ReviewAction.Change
    override val heading get() = "Changes “$name”"
    override val lines: List<ReviewLine> get() = emptyList()
    override val problem get() = RECORD_GONE
    override val tested get() = false
}

/**
 * What is saved, as the review reads it when what the phone sent arrives: providers with their passwords in the order
 * they are tried, indexers with their keys, the records (indexers, debrid accounts, torrent indexers) whose key is stored
 * whether or not it can be read ([storedKeys]), whether a TMDB key is stored, and while debrid shows the debrid accounts
 * of services this version knows and the torrent indexers, with their keys.
 */
data class SavedSources(
    val providers: List<ProviderWithPassword>,
    val indexers: List<IndexerWithKey>,
    val storedKeys: Set<String>,
    val hasTmdbKey: Boolean,
    val services: List<ServiceWithKey> = emptyList(),
    val torrentIndexers: List<TorrentIndexerWithKey> = emptyList(),
) {
    override fun toString() = "SavedSources(providers=${providers.size}, indexers=${indexers.size}, services=${services.size}, torrentIndexers=${torrentIndexers.size})"

    companion object {
        suspend fun read(settings: SettingsRepository, shown: Boolean = DebridGate.shown): SavedSources {
            val indexers = settings.indexers.first().mapNotNull { settings.indexer(it.id) }
            // Hidden, nothing of debrid's can have come from the page (L192).
            val services = if (shown) settings.servicesWithKeys().filter { kindOf(it.record) != null } else emptyList()
            val torrentIndexers = if (shown) settings.torrentIndexers.first().mapNotNull { settings.torrentIndexer(it.id) } else emptyList()
            val stored = indexers.filter { settings.indexerKeyStored(it.record.id) }.map { it.record.id } +
                services.filter { settings.serviceKeyStored(it.record.id) }.map { it.record.id } +
                torrentIndexers.filter { settings.torrentIndexerKeyStored(it.record.id) }.map { it.record.id }
            return SavedSources(settings.providersWithPasswords.first(), indexers, stored.toSet(), settings.tmdbKeyStored(), services, torrentIndexers)
        }
    }
}

/**
 * What arrived from the phone, as the TV reviews it: its [items] (providers, then indexers, in the page's order, then
 * the TMDB key), the saved ones it leaves as they are ([unchanged], by name), what it would leave with nothing to play
 * from or nothing to search ([warnings]; as Save's tests and choices leave it, [warnings] of the statuses), where it came
 * from ([sentFrom]) and which page ([mode]). [changes] is what arrived, kept for Send again's page to start from, and
 * [passes] the session's tests from the phone. [savedProviders], [savedIndexers], [savedServices] and
 * [savedTorrentIndexers]: how many were saved as it arrived (the debrid kinds while debrid shows, else none).
 */
data class SetupReview(
    val items: List<ReviewItem>,
    val unchanged: List<String>,
    val warnings: List<String>,
    val sentFrom: String?,
    val mode: SetupMode,
    val changes: SetupChanges,
    val passes: PassLog,
    val savedProviders: Int = 0,
    val savedIndexers: Int = 0,
    val savedServices: Int = 0,
    val savedTorrentIndexers: Int = 0,
) {
    /** "2 new · 1 changed · 1 to remove", or "Nothing changed". */
    val summary: String
        get() {
            val counts = listOf(ReviewAction.New to "new", ReviewAction.Change to "changed", ReviewAction.Remove to "to remove")
                .map { (action, words) -> items.count { it.action == action } to words }.filter { it.first > 0 }
            return if (counts.isEmpty()) NOTHING_CHANGED else counts.joinToString(" · ") { (n, words) -> "$n $words" }
        }

    fun item(key: String): ReviewItem? = items.firstOrNull { it.key == key }

    /**
     * [warnings] as Save's [statuses] leave it: a new item failing on the TV or left out takes no place of what goes,
     * one saved anyway does.
     */
    fun warnings(statuses: Map<String, ItemStatus>): List<String> {
        val unsaved = { status: ItemStatus? -> status is ItemStatus.Failed || status == ItemStatus.LeftOut || status == ItemStatus.Done(PHONE_SETUP_LEFT_OUT, ok = false) }
        return warnings(items.filterNot { it.action == ReviewAction.New && unsaved(statuses[it.key]) }, Saved(savedProviders, savedIndexers, savedServices, savedTorrentIndexers))
    }

    override fun toString() = "SetupReview(${items.joinToString()})"

    companion object {
        const val NOTHING_CHANGED = "Nothing changed"

        /**
         * The review of [changes] against what is [saved] now: each card by the record the TV bound it to (absence never
         * removes), a saved secret kept only under [SavedSecretRule], what passed from the phone by [passes].
         */
        fun of(changes: SetupChanges, saved: SavedSources, passes: PassLog, mode: SetupMode, sentFrom: String?): SetupReview {
            val items = mutableListOf<ReviewItem>()
            val touched = mutableSetOf<String>()
            changes.providers.forEach { change ->
                val part = "provider ${items.count { it.kind == ReviewKind.Provider } + 1}"
                provider(change, saved, passes, part)?.let(items::add)
                change.ref?.let(touched::add)
            }
            changes.indexers.forEach { change ->
                val part = "indexer ${items.count { it.kind == ReviewKind.Indexer } + 1}"
                indexer(change, saved, passes, part)?.let(items::add)
                change.ref?.let(touched::add)
            }
            changes.services.forEach { change ->
                val part = "service ${items.count { it.kind == ReviewKind.Service } + 1}"
                service(change, saved, passes, part)?.let(items::add)
            }
            changes.torrentIndexers.forEach { change ->
                val part = "torrent indexer ${items.count { it.kind == ReviewKind.TorrentIndexer } + 1}"
                torrentIndexer(change, saved, passes, part)?.let(items::add)
            }
            changes.tmdbKey?.trim()?.takeIf { it.isNotEmpty() }?.let { items += TmdbReview(it, saved.hasTmdbKey, passes.tmdb(it)) }
            val unchanged = saved.providers.filter { p -> items.none { it.key == p.record.id } }.map { it.record.displayName } +
                saved.indexers.filter { i -> items.none { it.key == i.record.id } }.map { it.record.name } +
                saved.services.filter { a -> items.none { it.key == a.record.id } }.map { nameOf(it.record) } +
                saved.torrentIndexers.filter { r -> items.none { it.key == r.record.id } }.map { it.record.name } +
                listOfNotNull("the TMDB key".takeIf { saved.hasTmdbKey && items.none { it is TmdbReview } })
            val counts = Saved(saved.providers.size, saved.indexers.size, saved.services.size, saved.torrentIndexers.size)
            return SetupReview(
                duplicates(items, saved), unchanged, warnings(items, counts), sentFrom, mode, changes, passes,
                counts.providers, counts.indexers, counts.services, counts.torrentIndexers,
            )
        }

        private fun provider(change: SetupChange<ProviderDraft>, saved: SavedSources, passes: PassLog, part: String): ReviewItem? {
            val draft = change.draft
            val ref = change.ref
            val before = ref?.let { r -> saved.providers.firstOrNull { it.record.id == r } }
            if (ref != null && before == null) return if (change.removed) null else Gone(ReviewKind.Provider, ref, draft.name.ifBlank { draft.host }.trim(), part)
            if (before != null && change.removed) {
                val r = before.record
                val line = "${r.host}:${r.port}" + if (r.username.isNotBlank() && !before.password.isNullOrEmpty()) " · its saved password is deleted too" else ""
                return Removal(ReviewKind.Provider, r.id, r.displayName, line, part)
            }
            val base = before?.let(::providerForm) ?: newProviderForm()
            val form = base.copy(
                name = draft.name.trim(), host = draft.host.trim(), port = draft.portOrDefault.toString(), useTls = draft.useTls,
                username = draft.username.trim(), password = draft.password, connections = draft.connectionsOrDefault.toString(),
            )
            if (before != null && draft.password.isEmpty() && form.copy(id = "") == base.copy(id = "")) return null
            val use = if (draft.password.isEmpty() && draft.username.isNotBlank() && before != null) SavedSecretRule.password(before, draft) else null
            val problem = when {
                form.host.isBlank() -> "No host was sent: fill it in on your phone and send again."
                form.hostProblem != null -> form.hostProblem
                form.portValue == null -> PROVIDER_PORT_RANGE
                form.connectionsValue == null -> PROVIDER_CONNECTIONS_RANGE
                use == SavedUse.No(SavedRefusal.ServerChanged) -> PROVIDER_SERVER_CHANGED
                use == SavedUse.No(SavedRefusal.Unreadable) -> PROVIDER_PASSWORD_UNREADABLE
                else -> null
            }
            val password = when {
                problem != null -> null
                draft.password.isNotEmpty() -> draft.password
                use is SavedUse.Use -> use.secret
                else -> ""
            }
            val tested = password != null && passes.provider(ProviderLogin.of(form.host, form.portValue ?: 0, form.username, form.useTls, password), form.connectionsValue ?: 0) != null
            val login = when {
                form.username.isBlank() -> null
                draft.password.isNotEmpty() -> "password typed"
                use is SavedUse.Use -> "saved password kept"
                else -> "no password"
            }
            return if (before == null) {
                val lines = listOfNotNull(
                    ReviewLine(listOfNotNull("${form.host}:${form.port}", "TLS".takeIf { form.useTls }, form.username.ifBlank { null }, login ?: "no login").joinToString(" · ")),
                    form.name.takeIf { it.isNotEmpty() }?.let { ReviewLine("Named “$it”") },
                )
                ProviderReview(form.id, ReviewAction.New, form, draft, null, password, "New", lines, problem, tested, part)
            } else {
                val r = before.record
                val lines = listOfNotNull(
                    named(base.name, form.name, cleared = "Name cleared: the host shows"),
                    ReviewLine("Host: ${r.host} → ${form.host}", warn = true).takeIf { form.host != r.host.trim() },
                    ReviewLine("Port: ${r.port} → ${form.port}").takeIf { form.portValue != r.port },
                    (if (form.useTls) ReviewLine("TLS switched on") else ReviewLine("TLS switched off", warn = true)).takeIf { form.useTls != r.useTls },
                    ReviewLine("Username: ${r.username.ifBlank { "none" }} → ${form.username.ifBlank { "none" }}").takeIf { form.username != r.username.trim() },
                    ReviewLine("Connections: ${r.connections} → ${form.connections}").takeIf { form.connectionsValue != r.connections },
                    when (login) {
                        "password typed" -> ReviewLine("New password typed")
                        "saved password kept" -> ReviewLine("Saved password kept")
                        else -> null
                    },
                )
                val heading = "Changes “${r.displayName}”" + if (!r.enabled) " · switched off" else ""
                ProviderReview(r.id, ReviewAction.Change, form, draft, before, password, heading, lines, problem, tested, part)
            }
        }

        private fun indexer(change: SetupChange<IndexerDraft>, saved: SavedSources, passes: PassLog, part: String): ReviewItem? {
            val draft = change.draft
            val ref = change.ref
            val before = ref?.let { r -> saved.indexers.firstOrNull { it.record.id == r } }
            if (ref != null && before == null) return if (change.removed) null else Gone(ReviewKind.Indexer, ref, draft.name.ifBlank { draft.url }.trim(), part)
            if (before != null && change.removed) {
                val r = before.record
                val line = shownAddress(r.baseUrl).url + if (ref in saved.storedKeys) " · its saved API key is deleted too" else ""
                return Removal(ReviewKind.Indexer, r.id, r.name, line, part)
            }
            // A card left at the address the page showed is the saved address, whole.
            val url = before?.let { SavedSecretRule.indexerAddress(it.record.baseUrl, draft.url) } ?: draft.url.trim()
            val typedKey = draft.apiKey.trim()
            // Named after its host when the name is left blank, as its form saves it.
            val name = draft.name.trim().ifEmpty { runCatching { java.net.URI(url).host }.getOrNull() ?: "Indexer" }
            if (before != null && typedKey.isEmpty() && url == before.record.baseUrl && name == before.record.name) return null
            val form = before?.let { IndexerForm(it.record.id, draft.name.trim(), url, typedKey, hasSavedKey = it.apiKey != null, enabled = it.record.enabled) }
                ?: newIndexerForm().copy(name = draft.name.trim(), url = url, apiKey = typedKey)
            val use = if (typedKey.isEmpty()) SavedSecretRule.key(before, ref in saved.storedKeys, draft.url) else null
            val problem = when {
                url.isBlank() -> "No API URL was sent: fill it in on your phone and send again."
                form.security == UrlSecurity.Invalid -> INDEXER_URL_SCHEME
                use == SavedUse.No(SavedRefusal.ServerChanged) -> INDEXER_ADDRESS_CHANGED
                use == SavedUse.No(SavedRefusal.Unreadable) -> INDEXER_KEY_UNREADABLE
                use == SavedUse.No(SavedRefusal.NoneSaved) -> INDEXER_NO_KEY
                else -> null
            }
            val key = when {
                problem != null -> null
                typedKey.isNotEmpty() -> typedKey
                use is SavedUse.Use -> use.secret
                else -> null
            }
            val tested = key != null && passes.indexer(url, key) != null
            val keyLine = when {
                typedKey.isNotEmpty() -> (if (before == null) "API key " else "New API key ") + maskKey(typedKey)
                use is SavedUse.Use -> "Saved API key kept"
                else -> null
            }
            val http = when (form.security) {
                UrlSecurity.PublicHttp -> ReviewLine(INDEXER_PUBLIC_HTTP, warn = true)
                UrlSecurity.LocalHttp -> ReviewLine(INDEXER_LOCAL_HTTP)
                else -> null
            }
            // A login before the host hides where the key goes ("https://indexer.example.org%2Fapi@elsewhere.example/").
            val login = runCatching { java.net.URI(url) }.getOrNull()?.takeIf { it.rawUserInfo != null }?.host
            return if (before == null) {
                val lines = listOfNotNull(
                    ReviewLine(listOfNotNull(url, keyLine).joinToString(" · ")),
                    login?.let { ReviewLine("Host $it", warn = true) },
                    form.name.takeIf { it.isNotEmpty() }?.let { ReviewLine("Named “$it”") },
                    http,
                )
                IndexerReview(form.id, ReviewAction.New, form, draft, key, "New", lines, problem, tested, part)
            } else {
                val r = before.record
                // The old address as the phone's page showed it: no more of it than that.
                val lines = listOfNotNull(
                    named(r.name, name, cleared = null),
                    ReviewLine("Address: ${shownAddress(r.baseUrl).url} → $url", warn = true).takeIf { url != r.baseUrl },
                    login?.takeIf { url != r.baseUrl }?.let { ReviewLine("Host $it", warn = true) },
                    keyLine?.let(::ReviewLine),
                    http.takeIf { url != r.baseUrl },
                )
                val heading = "Changes “${r.name}”" + if (!r.enabled) " · switched off" else ""
                IndexerReview(r.id, ReviewAction.Change, form, draft, key, heading, lines, problem, tested, part)
            }
        }

        /**
         * A debrid service card (L209): a key typed signs in to [ServiceDraft.kind]'s service, as a new account or, when
         * that service has one saved (the card's own, or one signed in to on the TV meanwhile), as its new key; a saved
         * card left with its key empty is unchanged; Remove signs the account out on this TV.
         */
        private fun service(change: SetupChange<ServiceDraft>, saved: SavedSources, passes: PassLog, part: String): ReviewItem? {
            val draft = change.draft
            val ref = change.ref
            val own = ref?.let { r -> saved.services.firstOrNull { it.record.id == r } }
            // The page offers only the services this version knows, and a saved account's card only its own.
            val kind = (own?.record?.let(::kindOf) ?: serviceKindOf(draft.kind)) ?: return null
            if (ref != null && own == null) return if (change.removed) null else Gone(ReviewKind.Service, ref, kind.displayName, part)
            if (own != null && change.removed) {
                val line = own.record.accountName.ifBlank { "Key saved" } + if (ref in saved.storedKeys) " · its saved API key is deleted too" else ""
                return Removal(ReviewKind.Service, own.record.id, kind.displayName, line, part)
            }
            val key = draft.key.trim()
            if (own != null && key.isEmpty()) return null
            val before = own ?: saved.services.firstOrNull { kindOf(it.record) == kind }
            val lines = listOf(ReviewLine(if (key.isEmpty()) kind.displayName else "${kind.displayName} · API key ${maskKey(key)}"))
            val problem = INDEXER_NO_KEY.takeIf { key.isEmpty() }
            val tested = key.isNotEmpty() && passes.service(kind, key) != null
            return if (before == null) {
                ServiceReview(java.util.UUID.randomUUID().toString(), ReviewAction.New, kind, key, null, "New", lines, problem, tested, part)
            } else {
                ServiceReview(before.record.id, ReviewAction.Change, kind, key, before, SERVICE_KEY_REPLACED, lines, problem, tested, part)
            }
        }

        /**
         * A torrent indexer card (L209), as [indexer]: its address the saved one whole while the card shows it, its key
         * typed or the saved one for the same address only, with "Use with your debrid service" said when it is off or
         * changes.
         */
        private fun torrentIndexer(change: SetupChange<TorrentIndexerDraft>, saved: SavedSources, passes: PassLog, part: String): ReviewItem? {
            val draft = change.draft
            val ref = change.ref
            val before = ref?.let { r -> saved.torrentIndexers.firstOrNull { it.record.id == r } }
            if (ref != null && before == null) return if (change.removed) null else Gone(ReviewKind.TorrentIndexer, ref, draft.name.ifBlank { draft.url }.trim(), part)
            if (before != null && change.removed) {
                val r = before.record
                val line = shownAddress(r.baseUrl).url + if (ref in saved.storedKeys) " · its saved API key is deleted too" else ""
                return Removal(ReviewKind.TorrentIndexer, r.id, r.name, line, part)
            }
            val url = before?.let { SavedSecretRule.indexerAddress(it.record.baseUrl, draft.url) } ?: draft.url.trim()
            val typedKey = draft.key.trim()
            // Named after its host when the name is left blank, as its form saves it.
            val name = draft.name.trim().ifEmpty { runCatching { java.net.URI(url).host }.getOrNull() ?: "Torrent indexer" }
            if (before != null && typedKey.isEmpty() && url == before.record.baseUrl && name == before.record.name && draft.useWithService == before.record.useWithService) return null
            val fields = before?.let { IndexerForm(it.record.id, draft.name.trim(), url, typedKey, hasSavedKey = it.apiKey != null, enabled = it.record.enabled) }
                ?: newIndexerForm().copy(name = draft.name.trim(), url = url, apiKey = typedKey)
            val form = TorrentIndexerForm(fields, draft.useWithService)
            val use = if (typedKey.isEmpty()) SavedSecretRule.torrentIndexerKey(before, ref in saved.storedKeys, draft.url) else null
            val problem = when {
                url.isBlank() -> "No API URL was sent: fill it in on your phone and send again."
                fields.security == UrlSecurity.Invalid -> INDEXER_URL_SCHEME
                use == SavedUse.No(SavedRefusal.ServerChanged) -> INDEXER_ADDRESS_CHANGED
                use == SavedUse.No(SavedRefusal.Unreadable) -> INDEXER_KEY_UNREADABLE
                use == SavedUse.No(SavedRefusal.NoneSaved) -> INDEXER_NO_KEY
                else -> null
            }
            val key = when {
                problem != null -> null
                typedKey.isNotEmpty() -> typedKey
                use is SavedUse.Use -> use.secret
                else -> null
            }
            val tested = key != null && passes.torrentIndexer(url, key) != null
            val keyLine = when {
                typedKey.isNotEmpty() -> (if (before == null) "API key " else "New API key ") + maskKey(typedKey)
                use is SavedUse.Use -> "Saved API key kept"
                else -> null
            }
            val http = when (fields.security) {
                UrlSecurity.PublicHttp -> ReviewLine(INDEXER_PUBLIC_HTTP, warn = true)
                UrlSecurity.LocalHttp -> ReviewLine(INDEXER_LOCAL_HTTP)
                else -> null
            }
            // A login before the host hides where the key goes, as for an indexer.
            val login = runCatching { java.net.URI(url) }.getOrNull()?.takeIf { it.rawUserInfo != null }?.host
            return if (before == null) {
                val lines = listOfNotNull(
                    ReviewLine(listOfNotNull(url, keyLine).joinToString(" · ")),
                    login?.let { ReviewLine("Host $it", warn = true) },
                    fields.name.takeIf { it.isNotEmpty() }?.let { ReviewLine("Named “$it”") },
                    ReviewLine(NOT_USED_WITH_SERVICE).takeIf { !draft.useWithService },
                    http,
                )
                TorrentIndexerReview(fields.id, ReviewAction.New, form, draft, key, "New", lines, problem, tested, part)
            } else {
                val r = before.record
                val lines = listOfNotNull(
                    named(r.name, name, cleared = null),
                    ReviewLine("Address: ${shownAddress(r.baseUrl).url} → $url", warn = true).takeIf { url != r.baseUrl },
                    login?.takeIf { url != r.baseUrl }?.let { ReviewLine("Host $it", warn = true) },
                    keyLine?.let(::ReviewLine),
                    ReviewLine(if (draft.useWithService) "$USE_WITH_SERVICE switched on" else "$USE_WITH_SERVICE switched off").takeIf { draft.useWithService != r.useWithService },
                    http.takeIf { url != r.baseUrl },
                )
                val heading = "Changes “${r.name}”" + if (!r.enabled) " · switched off" else ""
                TorrentIndexerReview(r.id, ReviewAction.Change, form, draft, key, heading, lines, problem, tested, part)
            }
        }

        /** A name's change: named anew, or cleared ([cleared], when a blank name means something). */
        private fun named(was: String, now: String, cleared: String?): ReviewLine? = when {
            was.trim() == now -> null
            now.isEmpty() -> cleared?.let(::ReviewLine)
            else -> ReviewLine("Named “$now”")
        }

        /**
         * A new provider with a saved one's server and login, or a new indexer or torrent indexer at a saved one's
         * address, said on it: two cards for one account log in twice. Not when that saved one goes.
         */
        private fun duplicates(items: List<ReviewItem>, saved: SavedSources): List<ReviewItem> {
            val staying = { id: String -> items.none { it.key == id && it.action == ReviewAction.Remove } }
            return items.map { item ->
                when {
                    item is ProviderReview && item.action == ReviewAction.New -> saved.providers.firstOrNull {
                        staying(it.record.id) && it.record.host.trim().equals(item.form.host, ignoreCase = true) && it.record.username.trim() == item.form.username
                    }?.let { item.copy(lines = item.lines + ReviewLine("Same server and login as the saved “${it.record.displayName}”", warn = true)) } ?: item
                    item is IndexerReview && item.action == ReviewAction.New -> {
                        val at = IndexerEndpoint.of(item.form.url)?.copy(https = false)
                        saved.indexers.firstOrNull { staying(it.record.id) && at != null && IndexerEndpoint.of(it.record.baseUrl)?.copy(https = false) == at }
                            ?.let { item.copy(lines = item.lines + ReviewLine("Same address as the saved “${it.record.name}”", warn = true)) } ?: item
                    }
                    item is TorrentIndexerReview && item.action == ReviewAction.New -> {
                        val at = IndexerEndpoint.of(item.form.fields.url)?.copy(https = false)
                        saved.torrentIndexers.firstOrNull { staying(it.record.id) && at != null && IndexerEndpoint.of(it.record.baseUrl)?.copy(https = false) == at }
                            ?.let { item.copy(lines = item.lines + ReviewLine("Same address as the saved “${it.record.name}”", warn = true)) } ?: item
                    }
                    else -> item
                }
            }
        }

        /** How many of each kind were saved as what the phone sent arrived. */
        private class Saved(val providers: Int, val indexers: Int, val services: Int, val torrentIndexers: Int)

        /**
         * What would stop working, said of each kind whose every one [saved] goes with none of [items] in place: nothing
         * left to play from (a provider, or a debrid service, which plays without one, L165), or nothing left to search
         * (an indexer, or a torrent indexer while a service is left, as it is searched only then, L166). The torrent
         * indexers' own line shows only while a service stays: without one they aren't searched anyway. A new item that
         * can't be saved as sent fails at Save, and can only be left out: it takes no place.
         */
        private fun warnings(items: List<ReviewItem>, saved: Saved): List<String> {
            fun left(kind: ReviewKind, saved: Int) = saved - items.count { it.kind == kind && it.action == ReviewAction.Remove } +
                items.count { it.kind == kind && it.action == ReviewAction.New && it.problem == null }
            val services = left(ReviewKind.Service, saved.services)
            val plays = left(ReviewKind.Provider, saved.providers) > 0 || services > 0
            val searches = left(ReviewKind.Indexer, saved.indexers) > 0 || left(ReviewKind.TorrentIndexer, saved.torrentIndexers) > 0 && services > 0
            return listOfNotNull(
                ONLY_PROVIDER_REMOVED.takeIf { saved.providers > 0 && !plays },
                ONLY_SERVICE_REMOVED.takeIf { saved.services > 0 && !plays },
                ONLY_INDEXER_REMOVED.takeIf { saved.indexers > 0 && !searches },
                ONLY_TORRENT_INDEXER_REMOVED.takeIf { saved.torrentIndexers > 0 && services > 0 && !searches },
            )
        }
    }
}

/** A key as the review shows it: its last four characters, and those only when they are few of its whole. */
internal fun maskKey(key: String): String = if (key.length > 8) "•••• ${key.takeLast(4)}" else "••••"
