package io.skjaere.usenet.handoff

/**
 * A button on the page, by the [value] it posts under [SetupForm.INTENT]. Which ones a page has is the flow's to say
 * ([SetupFlow.layout]); this is the grammar alone.
 */
internal sealed class SetupAction(val value: String) {
    /** The card it acts on, for those that act on one. */
    open val slot: SetupSlot? get() = null

    /** The off-screen first button, which Enter presses: tests the open card, or with nothing to test shows the page again. */
    data object Enter : SetupAction("enter")

    /** The open card's Test. */
    data object Test : SetupAction("test")

    /** The open indexer's Test anyway, after the page warned that its address isn't encrypted. */
    data object TestHttp : SetupAction("test-http")

    /** A summary's Test: that card, with the values the TV holds for it. */
    data class TestCard(override val slot: SetupSlot) : SetupAction("test:${slot.key}")

    /** Edit: that card opens, the open one's values kept. */
    data class Open(override val slot: SetupSlot) : SetupAction("open:${slot.key}")

    /** A new card: Don't add this one. A saved one: Remove, which the TV's review confirms. */
    data class Drop(override val slot: SetupSlot) : SetupAction("drop:${slot.key}")

    /** Keep it: a card marked for removal stays after all. */
    data class Keep(override val slot: SetupSlot) : SetupAction("keep:${slot.key}")

    /** Undo changes: a saved card back to its saved values, the TMDB card back to no key typed. */
    data class Undo(override val slot: SetupSlot) : SetupAction("undo:${slot.key}")

    /** Add another provider, indexer, debrid service or torrent indexer: a blank card at the end, open. */
    data class Add(val section: SetupSection) : SetupAction(addValue(section)) {
        init {
            require(section != SetupSection.Tmdb) { "there is one TMDB card" }
        }
    }

    data object Next : SetupAction("next")

    data object Back : SetupAction("back")

    data object Send : SetupAction("send")

    companion object {
        /** The action [value] names, or null when it names none. */
        fun of(value: String): SetupAction? = when (value) {
            Enter.value -> Enter
            Test.value -> Test
            TestHttp.value -> TestHttp
            Next.value -> Next
            Back.value -> Back
            Send.value -> Send
            "add:provider" -> Add(SetupSection.Provider)
            "add:indexer" -> Add(SetupSection.Indexer)
            "add:service" -> Add(SetupSection.Service)
            "add:torrent-indexer" -> Add(SetupSection.TorrentIndexer)
            else -> {
                val colon = value.indexOf(':')
                val slot = if (colon < 0) null else SetupSlot.of(value.substring(colon + 1))
                when (slot?.let { value.substring(0, colon) }) {
                    "test" -> TestCard(slot)
                    "open" -> Open(slot)
                    "drop" -> Drop(slot)
                    "keep" -> Keep(slot)
                    "undo" -> Undo(slot)
                    else -> null
                }
            }
        }

        private fun addValue(section: SetupSection) = when (section) {
            SetupSection.Provider -> "add:provider"
            SetupSection.Indexer -> "add:indexer"
            SetupSection.Service -> "add:service"
            SetupSection.TorrentIndexer -> "add:torrent-indexer"
            SetupSection.Tmdb -> "add:tmdb"
        }
    }
}

/**
 * A form as read against the page it came from: the page's render [version], the button pressed, and the open card's
 * [fields] that could be kept, cleaned (a field missing is blank; the TLS box is "on" when ticked; an empty password or
 * key keeps the one the TV holds). [problems] are fields whose value the TV can't hold: each keeps its earlier value.
 */
internal data class SetupPost(
    val version: Int,
    val action: SetupAction,
    val fields: Map<SetupField, String> = emptyMap(),
    val problems: Map<SetupField, SetupRejection.Reason> = emptyMap(),
) : SetupParse {
    // The names alone: the values may hold a password or a key.
    override fun toString() = "SetupPost(version=$version, action=${action.value}, fields=${fields.keys}, problems=$problems)"
}

/**
 * What a page's form may send: its render [version], the card open on it ([open]: its [fields] are the form's), the
 * buttons it has ([actions]), and the values the open card's choice may take ([choices]: a debrid service card's
 * services). Taken from the flow as it is, so it says what the page the phone has shows.
 */
internal class SetupLayout(val version: Int, val open: SetupSlot?, val actions: Set<SetupAction>, val choices: Set<String> = emptySet()) {
    val fields: List<SetupField> get() = open?.let { slot -> SetupField.entries.filter { it.section == slot.section } }.orEmpty()

    fun offers(action: SetupAction) = action in actions
}

/** A one-shot line for the page the phone gets next: about the card at [slot], which the page opens at, or the page. */
internal sealed interface SetupNotice {
    val slot: SetupSlot? get() = null

    /** A form from a page the phone had before the latest: nothing from it was kept. */
    data object Stale : SetupNotice

    /** Test on the card at [slot], with nothing in it to test. */
    data class NothingToTest(override val slot: SetupSlot) : SetupNotice

    /** Next or Send refused: the card at [slot] isn't ready. */
    data class NotReady(override val slot: SetupSlot) : SetupNotice

    /** Send with nothing that would change on the TV. */
    data object NothingToSend : SetupNotice
}

/** What a form did, for the server to answer. */
internal sealed interface Applied {
    /** Show the page again, scrolled to [anchor]: a card's slot, or [SetupFlow.TOP]. */
    data class Shown(val anchor: String) : Applied

    /** Test what [ticket] names, then show the page at its card. */
    data class Testing(val ticket: SetupTicket) : Applied

    /** Hand [changes] to the TV: the session ends. */
    data class Sent(val changes: SetupChanges) : Applied
}

/** A test under way: the card it is of ([card], the flow's own id), its [item] for the tester, and the values it tests. */
internal class SetupTicket(
    val card: Int,
    val slot: SetupSlot,
    val item: SetupTestItem,
    val values: Map<SetupField, String>,
    val secret: String?,
)

/** What a card holds for its password or key, as a page may say it: never the secret. */
internal enum class SecretState { None, Typed, Saved, Unreadable }

/** Why a card may go on untested: the code has no test left, or too little time for one. */
internal enum class Untested { NoTestsLeft, ClosingSoon }

/** What a card needs before Next or Send: a field fixed, a test that passes, or its password or key typed. */
internal enum class Need { Fix, Test, Secret }

/**
 * A card as a page shows it. Its [values] are the non-secret fields, as its inputs hold them; of its password or key
 * only [secret], what kind of one it has, and [savedSecret], what the TV has saved for it whatever is typed over it.
 * [result] is its last test's while the values are those tested; [changedSinceTest] once they aren't.
 */
internal class CardView(
    val slot: SetupSlot,
    val saved: Boolean,
    /** The saved name as the TV has it, for a saved card's legend. */
    val savedName: String?,
    val values: Map<SetupField, String>,
    val secret: SecretState,
    val removed: Boolean,
    val enabled: Boolean,
    val hiddenParts: Boolean,
    val readOnly: Boolean,
    val open: Boolean,
    val blank: Boolean,
    val changed: Boolean,
    val ready: Boolean,
    val result: SetupResult?,
    val changedSinceTest: Boolean,
    val untested: Untested?,
    val problems: Map<SetupField, SetupRejection.Reason>,
    val need: Need?,
    val savedSecret: SavedSecret = SavedSecret.None,
    /** A saved debrid service's account, as the service named it: shown under its name. */
    val account: String? = null,
    /** The services a debrid service card's radio offers: those no other card holds, a saved one's own alone. */
    val choices: List<ServiceChoice> = emptyList(),
) {
    init {
        require(values.keys.none { it.isSecret }) { "a card's view holds no password or key" }
    }
}

/**
 * A page as it is now: the [step], the cards by kind, which can be added, whether Next or Send may be taken ([ready],
 * else [blocking] is the first card in the way), whether anything would change ([changed]), the code's tests and time.
 */
internal class SetupView(
    val mode: SetupMode,
    val step: SetupStep,
    val version: Int,
    val providers: List<CardView>,
    val indexers: List<CardView>,
    val tmdb: CardView,
    val notice: SetupNotice?,
    val canAddProvider: Boolean,
    val canAddIndexer: Boolean,
    val ready: Boolean,
    val blocking: CardView?,
    val changed: Boolean,
    val testsLeft: Int,
    val canTest: Boolean,
    /** Minutes left before the code's hard cap, only in its last minutes. */
    val closesIn: Int?,
    /** Whether the page has its debrid sections ([SetupSeed.debrid]), with their cards and whether more can be added. */
    val debrid: Boolean = false,
    val services: List<CardView> = emptyList(),
    val torrentIndexers: List<CardView> = emptyList(),
    val canAddService: Boolean = false,
    val canAddTorrentIndexer: Boolean = false,
) {
    /** Every card, in the edit page's order. */
    val cards: List<CardView> get() = providers + indexers + services + torrentIndexers + tmdb

    val open: CardView? get() = cards.firstOrNull { it.open }
}

internal val SetupField.isSecret: Boolean get() = kind == SetupField.Kind.Password || kind == SetupField.Kind.Key

/**
 * One session's page on the TV, with no sockets or threads: the [mode], the wizard's step, the cards (saved ones from
 * the seed, new ones the phone added), what was typed into each, their tests, and the render [version]. The server
 * changes it under its lock: [layout] says what the page the phone has may send, [apply] applies a form read against it,
 * [finish] writes a test's result, [show] is the page to render.
 *
 * One card at most is open, with inputs; the rest are summaries. A password or key typed stays here, on its card, and
 * never goes back into a page. A card is ready for Next or Send when it is blank and new (it is left out), saved and
 * unchanged but for its name (and a torrent indexer's box, which no test reads) unless its saved password or key can't
 * be read, marked for removal, or tested with exactly its current values with a pass; with no test left or too little
 * time, untested cards may go on too.
 *
 * While the seed offers debrid services ([SetupSeed.debrid], L209) the page has a Debrid service section and a Torrent
 * indexer one, in the wizard on the providers' and the indexers' steps: a service card is one of those services, at
 * most one card each (its radio offers only the services no other card holds, a saved account's its own alone); a
 * torrent indexer card is an indexer's with "Use with your debrid service". Without them the page has neither, and no
 * form may name their cards.
 */
internal class SetupFlow(val mode: SetupMode, seed: SetupSeed, private val limits: SetupLimits = SetupLimits(), from: SetupChanges? = null) {
    /** The page's render counter: up whenever the cards, the open one or the step change, so an older page's form is stale. */
    var version = 1
        private set

    var step: SetupStep = SetupStep.Edit
        private set

    /** Tests started, one per card tested. */
    var testsStarted = 0
        private set

    private var nextId = 0
    /** Whether the page has its debrid sections, and the services a service card may be, A to Z. */
    private val debrid = seed.debrid
    private val serviceKinds = seed.serviceKinds
    private val providers = seed.providers.mapTo(mutableListOf()) { Card(SetupSection.Provider, saved(it)) }
    private val indexers = seed.indexers.mapTo(mutableListOf()) { Card(SetupSection.Indexer, saved(it)) }
    // One account a service, of a service the page offers: no card the radio couldn't show.
    private val services = seed.services.filter { debrid && serviceKinds.any { kind -> kind.value == it.kind } }.distinctBy { it.kind }
        .mapTo(mutableListOf()) { Card(SetupSection.Service, saved(it)) }
    private val torrentIndexers = seed.torrentIndexers.filter { debrid }.mapTo(mutableListOf()) { Card(SetupSection.TorrentIndexer, saved(it)) }
    private val tmdb = Card(SetupSection.Tmdb, null, seed.tmdbKey)
    private var open: Card? = null
    private var notice: SetupNotice? = null

    init {
        from?.let(::resume)
        enter(if (mode == SetupMode.Wizard) SetupStep.Providers else SetupStep.Edit)
    }

    /** What the page as it is now may send. */
    fun layout(): SetupLayout {
        val possible = buildList {
            addAll(listOf(SetupAction.Enter, SetupAction.Test, SetupAction.TestHttp, SetupAction.Next, SetupAction.Back, SetupAction.Send))
            SetupSection.entries.filter { it != SetupSection.Tmdb }.forEach { add(SetupAction.Add(it)) }
            for (card in onPage()) {
                val slot = slotOf(card)
                addAll(listOf(SetupAction.TestCard(slot), SetupAction.Open(slot), SetupAction.Drop(slot), SetupAction.Keep(slot), SetupAction.Undo(slot)))
            }
        }
        val open = open
        return SetupLayout(version, open?.let(::slotOf), possible.filter(::offers).toSet(), open?.let { choicesFor(it).map(ServiceChoice::value).toSet() }.orEmpty())
    }

    /**
     * Applies [post]: from an older page, nothing (it says so); else the open card takes what was posted for it, then
     * the button acts. Fields the TV couldn't hold keep their earlier values and hold the card up (not tested, not ready)
     * until it is posted again; the other cards' buttons still act. [closingSoon]: too little time is left for a test.
     */
    fun apply(post: SetupPost, closingSoon: Boolean = false): Applied {
        // The newest form decides the next page's line: one sent before the phone fetched the page after an earlier
        // form leaves nothing of that one's, whose card may have gone since.
        notice = null
        // A page with another render counter, or (never from the page itself) a button it doesn't have.
        if (post.version != version || !offers(post.action)) {
            notice = SetupNotice.Stale
            return Applied.Shown(TOP)
        }
        val action = post.action
        val target = action.slot?.let(::card)
        val current = open
        if (current != null && !(target === current && (action is SetupAction.Drop || action is SetupAction.Undo))) {
            current.take(post)
            // What couldn't be held stays on the card, which isn't ready until it is posted again: its own test waits
            // for that, the other cards' buttons don't.
            val testsIt = action is SetupAction.Enter || action is SetupAction.Test || action is SetupAction.TestHttp || target === current
            if (post.problems.isNotEmpty() && testsIt) return Applied.Shown(slotOf(current).key)
        }
        return when (action) {
            SetupAction.Enter -> test(current, plainHttp = false, quiet = true, closingSoon)
            SetupAction.Test -> test(current, plainHttp = false, quiet = false, closingSoon)
            SetupAction.TestHttp -> test(current, plainHttp = true, quiet = false, closingSoon)
            is SetupAction.TestCard -> test(target, plainHttp = false, quiet = false, closingSoon)
            is SetupAction.Open -> {
                val card = target!!
                close()
                open = card
                changed(slotOf(card).key)
            }
            is SetupAction.Drop -> drop(target!!)
            is SetupAction.Keep -> {
                target!!.removed = false
                changed(slotOf(target).key)
            }
            is SetupAction.Undo -> {
                target!!.reset()
                changed(slotOf(target).key)
            }
            is SetupAction.Add -> {
                close()
                val card = Card(action.section, null)
                list(action.section) += card
                open = card
                changed(slotOf(card).key)
            }
            SetupAction.Next -> next(closingSoon)
            SetupAction.Back -> {
                leave()
                enter(SetupStep.entries[step.ordinal - 1])
                changed(TOP)
            }
            SetupAction.Send -> send(closingSoon)
        }
    }

    /** Writes [result] onto the card [ticket] tested, with the values it tested; the card's slot now, or null when it has gone. */
    fun finish(ticket: SetupTicket, result: SetupResult): SetupSlot? {
        val card = all().firstOrNull { it.id == ticket.card } ?: return null
        card.tested = Tested(ticket.values, ticket.secret, result)
        return slotOf(card)
    }

    /** The page to render now, with the one-shot notice, which this takes. [closesIn]: minutes left, in the code's last ones. */
    fun show(closingSoon: Boolean = false, closesIn: Int? = null): SetupView {
        val canTest = canTest(closingSoon)
        val considered = when (step) {
            SetupStep.Check, SetupStep.Edit -> all()
            else -> onPage()
        }
        val blocking = considered.firstOrNull { !it.ready(canTest) }
        val changed = all().any { it.changed }
        val view = SetupView(
            mode = mode,
            step = step,
            version = version,
            providers = providers.map { it.view(closingSoon) },
            indexers = indexers.map { it.view(closingSoon) },
            tmdb = tmdb.view(closingSoon),
            notice = notice,
            canAddProvider = canAdd(SetupSection.Provider),
            canAddIndexer = canAdd(SetupSection.Indexer),
            ready = blocking == null && (changed || step != SetupStep.Check && step != SetupStep.Edit),
            blocking = blocking?.view(closingSoon),
            changed = changed,
            testsLeft = (limits.tests - testsStarted).coerceAtLeast(0),
            canTest = canTest,
            closesIn = closesIn,
            debrid = debrid,
            services = services.map { it.view(closingSoon) },
            torrentIndexers = torrentIndexers.map { it.view(closingSoon) },
            canAddService = canAdd(SetupSection.Service),
            canAddTorrentIndexer = canAdd(SetupSection.TorrentIndexer),
        )
        notice = null
        return view
    }

    /**
     * Lays what an earlier session of this page sent ([from]: Send again on the TV) over the saved cards: each saved one
     * it named as it was sent, changed or marked for removal, with the password or key typed for it, and the new ones
     * after them. One whose record went meanwhile comes back new, unless it was to be removed. None is tested: the TV
     * keeps no earlier page's results. A password or key typed then holds only while its card stays on the server it was
     * sent for: this session's owner, the first device to open its code, may not be the one that typed it. The debrid
     * cards come back only while the page has their sections, a service card only for a service it offers that no other
     * card holds now (one signed in to on the TV meanwhile, say).
     */
    private fun resume(from: SetupChanges) {
        for (change in from.providers) {
            val d = change.draft
            val values = mapOf(
                SetupField.ProviderName to d.name,
                SetupField.ProviderHost to d.host,
                SetupField.ProviderPort to d.port?.toString().orEmpty(),
                SetupField.ProviderTls to if (d.useTls) ON else "",
                SetupField.ProviderUsername to d.username,
                SetupField.ProviderConnections to d.connections?.toString().orEmpty(),
            )
            place(SetupSection.Provider, change.ref, values, d.password, change.removed)
        }
        for (change in from.indexers) {
            val values = mapOf(SetupField.IndexerName to change.draft.name, SetupField.IndexerUrl to change.draft.url)
            place(SetupSection.Indexer, change.ref, values, change.draft.apiKey, change.removed)
        }
        if (debrid) {
            for (change in from.services) {
                val kind = change.draft.kind
                val own = change.ref?.let { r -> services.firstOrNull { it.saved?.ref == r } }
                if (serviceKinds.none { it.value == kind } || own == null && services.any { it.values[SetupField.ServiceKind] == kind }) continue
                place(SetupSection.Service, change.ref, mapOf(SetupField.ServiceKind to kind), change.draft.key, change.removed)
            }
            for (change in from.torrentIndexers) {
                val d = change.draft
                val values = mapOf(
                    SetupField.TorrentIndexerName to d.name,
                    SetupField.TorrentIndexerUrl to d.url,
                    SetupField.TorrentIndexerDebrid to if (d.useWithService) ON else "",
                )
                place(SetupSection.TorrentIndexer, change.ref, values, d.key, change.removed)
            }
        }
        tmdb.secret = from.tmdbKey?.takeIf { it.isNotEmpty() }
    }

    /** One card of what was sent: the saved card [ref] names, else a new one (below the cap); [secret] as typed, if it was. */
    private fun place(section: SetupSection, ref: String?, values: Map<SetupField, String>, secret: String, removed: Boolean) {
        val list = list(section)
        val card = ref?.let { r -> list.firstOrNull { it.saved?.ref == r } } ?: if (removed || !canAdd(section)) return else Card(section, null).also { list += it }
        if (removed) {
            card.reset()
            card.removed = true
        } else if (!card.readOnly) {
            // A saved account's service is its own, whatever was sent.
            card.values = if (section == SetupSection.Service) card.saved?.values ?: values else values
            card.secret = secret.ifEmpty { null }
            card.resumedFor = card.secret?.let { card.server(values) }
        }
    }

    /** What Send hands over now: every card but blank new ones, in page order, and the key typed. */
    fun changes() = SetupChanges(
        providers = providers.filterNot { it.blank }.map { SetupChange(it.saved?.ref, it.providerDraft(), it.removed) },
        indexers = indexers.filterNot { it.blank }.map { SetupChange(it.saved?.ref, it.indexerDraft(), it.removed) },
        tmdbKey = tmdb.secret,
        services = services.filterNot { it.blank }.map { SetupChange(it.saved?.ref, it.serviceDraft(), it.removed) },
        torrentIndexers = torrentIndexers.filterNot { it.blank }.map { SetupChange(it.saved?.ref, it.torrentIndexerDraft(), it.removed) },
    )

    private fun canTest(closingSoon: Boolean) = testsStarted < limits.tests && !closingSoon

    /** Below the cap: a card for each service the page offers, the limits' for the others; none of debrid's while hidden. */
    private fun canAdd(section: SetupSection) = when (section) {
        SetupSection.Provider -> providers.size < limits.providers
        SetupSection.Indexer -> indexers.size < limits.indexers
        SetupSection.Service -> debrid && services.size < serviceKinds.size
        SetupSection.TorrentIndexer -> debrid && torrentIndexers.size < limits.torrentIndexers
        SetupSection.Tmdb -> false
    }

    /**
     * The services [card]'s radio offers: a saved account's own service alone (an account is of one service), else its
     * own choice and those no other card holds, so two cards are never of one service.
     */
    private fun choicesFor(card: Card): List<ServiceChoice> {
        if (card.section != SetupSection.Service) return emptyList()
        card.saved?.let { saved -> return serviceKinds.filter { it.value == saved.values[SetupField.ServiceKind] } }
        val own = card.values[SetupField.ServiceKind].orEmpty()
        val held = services.filter { it !== card }.mapNotNull { it.values[SetupField.ServiceKind]?.takeIf(String::isNotEmpty) }.toSet()
        return serviceKinds.filter { it.value == own || it.value !in held }
    }

    /** Every card, in the edit page's order. */
    private fun all(): List<Card> = providers + indexers + services + torrentIndexers + tmdb

    private fun offers(action: SetupAction): Boolean {
        val card = action.slot?.let(::card)
        return when (action) {
            SetupAction.Enter -> true
            SetupAction.Test -> open != null
            SetupAction.TestHttp -> open?.section?.let(::urlField) != null
            is SetupAction.TestCard -> card != null && !card.removed && !card.readOnly
            is SetupAction.Open -> card != null && card !== open && !card.removed && !card.readOnly
            // The TMDB key isn't removed from the phone: the Metadata screen does that.
            is SetupAction.Drop -> card != null && card.section != SetupSection.Tmdb && !card.removed
            is SetupAction.Keep -> card != null && card.removed
            is SetupAction.Undo -> card != null && (card.section == SetupSection.Tmdb || card.saved != null && !card.removed && !card.readOnly)
            // In the wizard a debrid service goes with the providers, a torrent indexer with the indexers (L209).
            is SetupAction.Add -> canAdd(action.section) && when (step) {
                SetupStep.Edit -> true
                SetupStep.Providers -> action.section == SetupSection.Provider || action.section == SetupSection.Service
                SetupStep.Indexers -> action.section == SetupSection.Indexer || action.section == SetupSection.TorrentIndexer
                else -> false
            }
            SetupAction.Next -> step == SetupStep.Providers || step == SetupStep.Indexers || step == SetupStep.Tmdb
            SetupAction.Back -> step == SetupStep.Indexers || step == SetupStep.Tmdb || step == SetupStep.Check
            SetupAction.Send -> step == SetupStep.Check || step == SetupStep.Edit
        }
    }

    private fun test(card: Card?, plainHttp: Boolean, quiet: Boolean, closingSoon: Boolean): Applied {
        val anchor = card?.let { slotOf(it).key } ?: TOP
        // Test anyway holds for the address it was chosen for: a later test of that one (Go on the keyboard, say) keeps it.
        val address = card?.section?.let(::urlField)?.let { card.values[it] }
        val anyway = plainHttp || card != null && card.plainHttpAt != null && card.plainHttpAt == address
        val item = card?.let { item(it, anyway) }
        if (card == null || item == null) {
            if (!quiet && card != null) notice = SetupNotice.NothingToTest(slotOf(card))
            return Applied.Shown(anchor)
        }
        if (plainHttp) card.plainHttpAt = address
        // No test left, or too little time for one: nothing runs, and the card may go on untested, as it says.
        if (!canTest(closingSoon)) return Applied.Shown(anchor)
        testsStarted++
        return Applied.Testing(SetupTicket(card.id, slotOf(card), item, card.testValues, card.secret))
    }

    /** What testing [card] asks of the app; null when it has nothing to test. */
    private fun item(card: Card, plainHttp: Boolean): SetupTestItem? {
        val slot = slotOf(card)
        return when (card.section) {
            SetupSection.Provider -> SetupTestItem.Provider(slot, card.saved?.ref, card.providerDraft()).takeUnless { card.blank }
            SetupSection.Indexer -> SetupTestItem.Indexer(slot, card.saved?.ref, card.indexerDraft(), plainHttp).takeUnless { card.blank }
            // A key with no service chosen is nothing to test yet: which service would it go to?
            SetupSection.Service -> SetupTestItem.Service(slot, card.saved?.ref, card.serviceDraft()).takeUnless { card.blank || card.serviceDraft().kind.isEmpty() }
            SetupSection.TorrentIndexer -> SetupTestItem.TorrentIndexer(slot, card.saved?.ref, card.torrentIndexerDraft(), plainHttp).takeUnless { card.blank }
            // The saved key may be tested where the wizard asks for one; on the edit page only a key typed is new.
            SetupSection.Tmdb -> card.secret?.let { SetupTestItem.Tmdb(it) }
                ?: SetupTestItem.Tmdb("").takeIf { mode == SetupMode.Wizard && card.savedSecret == SavedSecret.Saved }
        }
    }

    private fun drop(card: Card): Applied {
        if (open === card) open = null
        if (card.saved == null) {
            list(card.section).remove(card)
            return changed(TOP)
        }
        card.reset()
        card.removed = true
        return changed(slotOf(card).key)
    }

    private fun next(closingSoon: Boolean): Applied {
        val waiting = onPage().firstOrNull { !it.ready(canTest(closingSoon)) }
        if (waiting != null) {
            notice = SetupNotice.NotReady(slotOf(waiting))
            return Applied.Shown(slotOf(waiting).key)
        }
        leave()
        enter(SetupStep.entries[step.ordinal + 1])
        return changed(TOP)
    }

    private fun send(closingSoon: Boolean): Applied {
        val all = all()
        val waiting = all.firstOrNull { !it.ready(canTest(closingSoon)) }
        if (waiting != null) {
            notice = SetupNotice.NotReady(slotOf(waiting))
            return Applied.Shown(if (step == SetupStep.Edit) slotOf(waiting).key else TOP)
        }
        if (all.none { it.changed }) {
            notice = SetupNotice.NothingToSend
            return Applied.Shown(TOP)
        }
        return Applied.Sent(changes())
    }

    private fun changed(anchor: String): Applied {
        version++
        return Applied.Shown(anchor)
    }

    /** Closes the open card; a new one left blank goes, as it would at Next. */
    private fun close() {
        val card = open ?: return
        open = null
        if (card.blank) list(card.section).remove(card)
    }

    /** Leaves the step: its open card closes, and new cards left blank go (an empty step is allowed). */
    private fun leave() {
        open = null
        if (step == SetupStep.Providers) (providers + services).filter { it.blank }.forEach { list(it.section).remove(it) }
        if (step == SetupStep.Indexers) (indexers + torrentIndexers).filter { it.blank }.forEach { list(it.section).remove(it) }
    }

    /** Goes to [step]: a providers' or indexers' step with no card starts with a blank one open; the TMDB step's card is open. */
    private fun enter(step: SetupStep) {
        this.step = step
        open = when (step) {
            SetupStep.Providers, SetupStep.Indexers -> {
                val section = if (step == SetupStep.Providers) SetupSection.Provider else SetupSection.Indexer
                if (list(section).isEmpty()) Card(section, null).also { list(section) += it } else null
            }
            SetupStep.Tmdb -> tmdb
            SetupStep.Check, SetupStep.Edit -> null
        }
    }

    private fun list(section: SetupSection): MutableList<Card> = when (section) {
        SetupSection.Provider -> providers
        SetupSection.Indexer -> indexers
        SetupSection.Service -> services
        SetupSection.TorrentIndexer -> torrentIndexers
        SetupSection.Tmdb -> mutableListOf(tmdb)
    }

    /** The cards the page shows as cards, in its order. */
    private fun onPage(): List<Card> = when (step) {
        SetupStep.Providers -> providers + services
        SetupStep.Indexers -> indexers + torrentIndexers
        SetupStep.Tmdb -> listOf(tmdb)
        SetupStep.Check -> emptyList()
        SetupStep.Edit -> all()
    }

    private fun card(slot: SetupSlot): Card? = onPage().firstOrNull { slotOf(it) == slot }

    private fun slotOf(card: Card): SetupSlot =
        if (card.section == SetupSection.Tmdb) SetupSlot.Tmdb else SetupSlot(card.section, list(card.section).indexOf(card) + 1)

    private fun Card.view(closingSoon: Boolean): CardView {
        val ready = ready(canTest(closingSoon))
        return CardView(
            slot = slotOf(this),
            saved = saved != null,
            savedName = saved?.name,
            values = values,
            secret = when {
                secret != null -> SecretState.Typed
                savedSecret == SavedSecret.Saved -> SecretState.Saved
                savedSecret == SavedSecret.Unreadable -> SecretState.Unreadable
                else -> SecretState.None
            },
            removed = removed,
            enabled = saved?.enabled ?: true,
            // Kept on the TV only while the address is the one shown.
            hiddenParts = saved?.hiddenParts == true && urlField(section)?.let { values[it] == saved.values[it] } == true,
            readOnly = readOnly,
            open = this === open,
            blank = blank,
            changed = changed,
            ready = ready,
            result = result,
            // A saved card back to its saved values (its name aside) is as saved, whatever was tested in between.
            changedSinceTest = tested != null && result == null && !renamedOnly,
            // Ready only because no test can run: what it would need is the TV's, at Save.
            untested = (if (closingSoon) Untested.ClosingSoon else Untested.NoTestsLeft).takeIf { ready && !ready(canTest = true) },
            problems = problems,
            need = when {
                ready -> null
                problems.isNotEmpty() -> Need.Fix
                renamedOnly && savedSecret == SavedSecret.Unreadable -> Need.Secret
                else -> Need.Test
            },
            savedSecret = savedSecret,
            account = saved?.account,
            choices = choicesFor(this),
        )
    }

    /** A saved record as a card holds it. */
    private class Saved(
        val ref: String,
        val name: String,
        val values: Map<SetupField, String>,
        val secret: SavedSecret,
        val enabled: Boolean,
        val hiddenParts: Boolean,
        /** A value the page's own parser would refuse: shown, never put in an input, or every form of the page would be. */
        val readOnly: Boolean,
        /** A debrid service's account, as the service named it. */
        val account: String? = null,
    )

    /** A test's result, with the values (all but the name) and the secret typed (null: the saved one) it was of. */
    private class Tested(val values: Map<SetupField, String>, val secret: String?, val result: SetupResult)

    private fun saved(p: SavedProvider): Saved {
        val values = mapOf(
            SetupField.ProviderName to p.name,
            SetupField.ProviderHost to p.host,
            SetupField.ProviderPort to p.port.toString(),
            SetupField.ProviderTls to if (p.useTls) ON else "",
            SetupField.ProviderUsername to p.username,
            SetupField.ProviderConnections to p.connections.toString(),
        )
        // A host with a login or a scheme in it predates the form's check, which the app makes when it saves.
        val odd = refused(values) || '@' in p.host || "://" in p.host
        return Saved(p.ref, p.name, values, p.login, p.enabled, hiddenParts = false, readOnly = odd)
    }

    private fun saved(i: SavedIndexer): Saved {
        val values = mapOf(SetupField.IndexerName to i.name, SetupField.IndexerUrl to i.shownUrl)
        return Saved(i.ref, i.name, values, i.key, i.enabled, i.hiddenParts, readOnly = refused(values))
    }

    /** A service account's card: named after its service, which a page can't change. */
    private fun saved(s: SavedService): Saved {
        val name = serviceKinds.first { it.value == s.kind }.name
        return Saved(s.ref, name, mapOf(SetupField.ServiceKind to s.kind), s.key, s.enabled, hiddenParts = false, readOnly = false, account = s.account)
    }

    private fun saved(r: SavedTorrentIndexer): Saved {
        val values = mapOf(
            SetupField.TorrentIndexerName to r.name,
            SetupField.TorrentIndexerUrl to r.shownUrl,
            SetupField.TorrentIndexerDebrid to if (r.useWithService) ON else "",
        )
        return Saved(r.ref, r.name, values, r.key, r.enabled, r.hiddenParts, readOnly = refused(values))
    }

    private fun refused(values: Map<SetupField, String>) = values.any { (field, value) -> field.kind != SetupField.Kind.Checkbox && SetupForm.check(field, value) != null }

    private inner class Card(val section: SetupSection, val saved: Saved?, val savedSecret: SavedSecret = saved?.secret ?: SavedSecret.None) {
        val id = nextId++
        var values: Map<SetupField, String> = saved?.values ?: blankValues(section)
        /** The password or key typed this session: kept on the TV, never sent back to the phone. */
        var secret: String? = null
        var removed = false
        var tested: Tested? = null
        var problems: Map<SetupField, SetupRejection.Reason> = emptyMap()
        /** The address the phone chose Test anyway for, after the page warned it isn't encrypted. */
        var plainHttpAt: String? = null
        /** For a [secret] an earlier page typed: the server it was sent for, which it goes to alone. */
        var resumedFor: Map<SetupField, String>? = null

        val readOnly: Boolean get() = saved?.readOnly == true

        val blank: Boolean
            get() = saved == null && section != SetupSection.Tmdb && secret == null &&
                values.all { (field, value) -> field.kind == SetupField.Kind.Checkbox || value.isEmpty() }

        /** What a test is of: every value but the name, and a torrent indexer's box, which no test reads. */
        val testValues: Map<SetupField, String> get() = values.filterKeys(::tested)

        /** Saved, and as saved but perhaps for its name (and box). */
        val renamedOnly: Boolean get() = saved != null && secret == null && saved.values.filterKeys(::tested) == testValues

        /** Different from what the TV has: anything new and filled in, a saved one changed or marked for removal, a key typed. */
        val changed: Boolean
            get() = when {
                section == SetupSection.Tmdb -> secret != null
                saved == null -> !blank
                else -> removed || secret != null || values != saved.values
            }

        /** The last test's result, while the card holds exactly what it tested. */
        val result: SetupResult? get() = tested?.takeIf { it.values == testValues && it.secret == secret }?.result

        fun ready(canTest: Boolean): Boolean {
            // Untested, and no test can run: it may go on, unless a test already failed with these values.
            val mayGoUntested = !canTest && result?.outcome != SetupOutcome.Failed
            return when {
                removed -> true
                problems.isNotEmpty() -> false
                section == SetupSection.Tmdb -> secret == null || result?.outcome == SetupOutcome.Passed || mayGoUntested
                blank -> true
                // A read-only card can't take a password typed (it can't be opened): the TV sorts out one it can't read.
                renamedOnly -> readOnly || savedSecret != SavedSecret.Unreadable
                result?.outcome == SetupOutcome.Passed -> true
                else -> mayGoUntested
            }
        }

        /**
         * Takes what [post] has for this card: a field that couldn't be held keeps its value, a secret left empty the one
         * held, unless an earlier page typed that one and the card's server is now another.
         */
        fun take(post: SetupPost) {
            val next = values.toMutableMap()
            // A saved account's service is its own: its radio has no other to send.
            val fixed = setOfNotNull(SetupField.ServiceKind.takeIf { saved != null })
            for (field in SetupField.entries.filter { it.section == section && it !in post.problems && it !in fixed }) {
                val value = post.fields[field].orEmpty()
                if (field.isSecret) {
                    if (value.isNotEmpty()) {
                        secret = value
                        resumedFor = null
                    }
                } else {
                    next[field] = value
                }
            }
            values = next
            problems = post.problems
            if (resumedFor != null && server(next) != resumedFor) {
                secret = null
                resumedFor = null
            }
        }

        /**
         * Where [values] send this card's password or key: a provider's host, port, TLS and username, an indexer's or a
         * torrent indexer's address, a debrid account's service.
         */
        fun server(values: Map<SetupField, String>): Map<SetupField, String> = values.filterKeys {
            it == SetupField.ProviderHost || it == SetupField.ProviderPort || it == SetupField.ProviderTls || it == SetupField.ProviderUsername ||
                it == SetupField.IndexerUrl || it == SetupField.TorrentIndexerUrl || it == SetupField.ServiceKind
        }

        /** Back to what is saved: no change, nothing typed, no test. */
        fun reset() {
            values = saved?.values ?: blankValues(section)
            secret = null
            tested = null
            problems = emptyMap()
            plainHttpAt = null
            resumedFor = null
        }

        fun providerDraft() = ProviderDraft(
            name = values[SetupField.ProviderName].orEmpty(),
            host = values[SetupField.ProviderHost].orEmpty(),
            port = values[SetupField.ProviderPort]?.takeIf { it.isNotEmpty() }?.toInt(),
            useTls = values[SetupField.ProviderTls] == ON,
            username = values[SetupField.ProviderUsername].orEmpty(),
            password = secret.orEmpty(),
            connections = values[SetupField.ProviderConnections]?.takeIf { it.isNotEmpty() }?.toInt(),
        )

        fun indexerDraft() = IndexerDraft(values[SetupField.IndexerName].orEmpty(), values[SetupField.IndexerUrl].orEmpty(), secret.orEmpty())

        fun serviceDraft() = ServiceDraft(values[SetupField.ServiceKind].orEmpty(), secret.orEmpty())

        fun torrentIndexerDraft() = TorrentIndexerDraft(
            values[SetupField.TorrentIndexerName].orEmpty(),
            values[SetupField.TorrentIndexerUrl].orEmpty(),
            secret.orEmpty(),
            useWithService = values[SetupField.TorrentIndexerDebrid] == ON,
        )
    }

    companion object {
        /** The anchor at the top of a page. */
        const val TOP = "top"

        /** A ticked box's value, as a browser sends it. */
        private const val ON = "on"

        /** Whether a test reads [field]: not a card's name, nor a torrent indexer's box (the search it tests is the same either way). */
        private fun tested(field: SetupField) =
            field != SetupField.ProviderName && field != SetupField.IndexerName && field != SetupField.TorrentIndexerName && field != SetupField.TorrentIndexerDebrid

        /** An indexer's or a torrent indexer's address field, the one Test anyway and hidden parts are about; null for others. */
        private fun urlField(section: SetupSection) = when (section) {
            SetupSection.Indexer -> SetupField.IndexerUrl
            SetupSection.TorrentIndexer -> SetupField.TorrentIndexerUrl
            else -> null
        }

        /** A new card's inputs: all blank, no service chosen, the TLS box and "Use with your debrid service" ticked. */
        private fun blankValues(section: SetupSection): Map<SetupField, String> =
            SetupField.entries.filter { it.section == section && !it.isSecret }.associateWith { if (it.kind == SetupField.Kind.Checkbox) ON else "" }
    }
}
