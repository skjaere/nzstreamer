package io.skjaere.usenet.handoff

import java.security.MessageDigest
import java.util.Base64

/** The warning on the TV's setup page and on the phone's: the form crosses the home network unencrypted. */
const val SETUP_NOT_ENCRYPTED = "⚠ Not encrypted: what you send crosses your home network as plain text. Use this on your own network."

/**
 * The pages a TV serves to the phone: the wizard's steps and the edit page, and fixed answers for what can happen to
 * them. Plain HTML with one inline style, no script and nothing from elsewhere. A page is made from the flow's view:
 * the cards the TV holds, saved ones' names, hosts, usernames, addresses and debrid accounts (never a password or key,
 * which the view can't hold), what the phone typed (never a password or key either), and the TV's words for each test,
 * all escaped. The fixed answers echo nothing.
 */
object SetupPage {
    /** A card's words when the TV gave up waiting on its test. */
    const val TOOK_TOO_LONG = "The test took too long."

    /** A card's words when the TV's test of it couldn't run at all. */
    const val COULDNT_RUN = "The test couldn't run on the TV."

    /** From how many tests left the page says how many. */
    private const val FEW_TESTS = 3

    /** The most of a result's words a page shows. */
    private const val WORDS_MAX = 200

    /**
     * The one style every page has; the policy allows exactly this text by its hash. The app's own colours, always
     * dark; the phone's own font, since one of the app's would have to come from the TV, which the policy keeps out.
     */
    private const val STYLE =
        ":root{color-scheme:dark}" +
            "body{margin:0;background:#0E0F12;color:#F3F1EC;font:16px/1.45 system-ui,-apple-system,\"Segoe UI\",Roboto,sans-serif}" +
            "main{max-width:34rem;margin:0 auto;padding:4px 16px 20px}" +
            "h1{font-size:1.4rem;margin:14px 0 4px}" +
            "h2{font-size:.8rem;margin:24px 0 8px;color:#A3A6AE;text-transform:uppercase;letter-spacing:.8px;font-weight:600}" +
            "p{color:#C9CBD1}" +
            ".step{margin:0 0 12px;font-weight:600}" +
            ".dots{display:flex;gap:6px;margin:8px 0 12px}" +
            ".dots span{flex:1;height:4px;border-radius:2px;background:#2A2D34}" +
            ".dots span.on{background:#F2A33A}" +
            ".warn{background:#3A2412;border-radius:12px;padding:10px 14px;margin:0 0 14px;font-size:.95rem;color:#FFC9A3}" +
            ".notice{background:#2A2D34;border-radius:12px;padding:10px 14px;color:#F3F1EC;font-weight:600}" +
            "fieldset{border:1px solid #3A3D44;border-radius:16px;margin:0 0 14px;padding:4px 14px 14px;background:#1A1C21}" +
            "legend{font-weight:600;padding:0 6px;background:#1A1C21;border-radius:6px}" +
            "label{display:block;margin:12px 0 4px;color:#C9CBD1;font-size:.92rem}" +
            "input{box-sizing:border-box;width:100%;font:inherit;padding:10px 12px;border:1px solid #2A2D34;border-radius:12px;min-height:46px;" +
            "color:#F3F1EC;background:#0E0F12}" +
            "input:focus{outline:0;border-color:#F2A33A}" +
            "input::placeholder{color:#A3A6AE}" +
            ".check{display:flex;gap:10px;align-items:center;color:#F3F1EC}" +
            ".check input{width:20px;height:20px;min-height:0;margin:0;accent-color:#F2A33A}" +
            ".fh{font-size:.85rem;color:#A3A6AE;margin:4px 0 0}" +
            "button{font:inherit;font-weight:600;padding:12px 26px;border:0;border-radius:24px;background:#F2A33A;color:#1A1206}" +
            "button.second{padding:11px 25px;background:transparent;color:#F3F1EC;border:1px solid #3A3D44}" +
            "button.small{padding:7px 16px;font-size:.92rem}" +
            "button.second.small{padding:6px 15px}" +
            "button.wide{display:block;width:100%;margin:0 0 14px}" +
            "button:disabled{opacity:.38}" +
            ".buttons{display:flex;flex-wrap:wrap;gap:10px;margin:12px 0 0}" +
            ".hint{font-size:.9rem;margin:8px 0;color:#A3A6AE}" +
            ".hint strong{color:#F3F1EC}" +
            ".result{margin:10px 0 2px;padding:8px 12px;border-radius:10px;font-size:.95rem;background:#2A2D34;color:#C9CBD1}" +
            ".result.ok{background:#1F3328;color:#A8E0A4}" +
            ".result.fail{background:#3A2412;color:#FFB27A}" +
            ".result.http{background:#3A2412;color:#FFC9A3}" +
            ".card{background:#1A1C21;border-radius:16px;padding:12px 14px;margin:0 0 12px}" +
            ".card.gone{background:transparent;border:1px dashed #3A3D44}" +
            ".ct,.cm,.st{margin:0;overflow-wrap:anywhere}" +
            ".ct{font-weight:600;color:#F3F1EC}" +
            ".cm{font-size:.9rem;color:#A3A6AE}" +
            ".st{font-size:.9rem;margin-top:4px;font-weight:600;color:#A3A6AE}" +
            ".st.good{color:#A8E0A4}" +
            ".st.bad{color:#FFB27A}" +
            ".tag{display:inline-block;font-size:.72rem;font-weight:700;letter-spacing:.4px;text-transform:uppercase;color:#F2A33A;margin-right:6px}" +
            ".tag.rm{color:#FFB27A}" +
            ".list{background:#1A1C21;border-radius:16px;padding:2px 14px;margin:0 0 12px}" +
            ".li{padding:10px 0;border-bottom:1px solid #2A2D34}" +
            ".li:last-child{border-bottom:0}" +
            ":target{scroll-margin-top:12px}" +
            ".offscreen{position:absolute;left:-10000px}"

    /**
     * No script, style only by hash, a form only to this page, no framing, no base. No sandbox: it
     * would make the page's origin opaque, and the form's Origin "null", which the server refuses.
     */
    val contentSecurityPolicy: String =
        "default-src 'none'; style-src 'sha256-${sha256(STYLE)}'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'"

    /**
     * What every page goes out with, besides its length and Connection: close. same-origin, not
     * no-referrer: with no-referrer a browser posts the form with Origin "null", which can't be told
     * from a sandboxed page elsewhere.
     */
    val headers: List<Pair<String, String>> = listOf(
        "Content-Type" to "text/html; charset=utf-8",
        "Cache-Control" to "no-store",
        "Referrer-Policy" to "same-origin",
        "X-Frame-Options" to "DENY",
        "X-Content-Type-Options" to "nosniff",
        "Content-Security-Policy" to contentSecurityPolicy,
    )

    /**
     * A result's words as a page shows them: without controls, bidi or zero-width characters (a line break or a tab
     * as a space), at most [WORDS_MAX] characters. They are the TV's, but may hold what a server answered.
     */
    private fun words(text: String): String {
        val plain = buildString(text.length) {
            for (c in text) {
                when {
                    c in BREAKS -> append(' ')
                    SetupForm.spoofs(c) -> Unit
                    else -> append(c)
                }
            }
        }.replace(SPACES, " ").trim()
        if (plain.length <= WORDS_MAX) return plain
        // One short, for the "…", and never half of a character outside the BMP.
        val cut = plain.take(WORDS_MAX - 1).let { if (it.last().isHighSurrogate()) it.dropLast(1) else it }
        return "$cut…"
    }

    private const val BREAKS = "\t\n\r\u000b\u000c\u0085\u2028\u2029"
    private val SPACES = Regex(" {2,}")

    /** [text] safe between tags and inside an attribute's quotes: & < > " and ' always escaped, whatever the field. */
    private fun escape(text: String): String = buildString(text.length) {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }

    // The wizard's steps and the edit page, from the flow.

    const val TITLE = "Set up your TV"

    /** On a page that lists anything saved, after the warning. */
    const val SAVED_SHOWN = "This page shows your saved servers and usernames, never passwords or keys."

    const val EDIT_INTRO = "Change, add or remove, then send. Nothing is saved until you choose Save on the TV."
    const val TMDB_WHY = "Posters, episode lists and your collection need a TMDB key of your own. You can add it later."
    const val TMDB_SKIP_HINT = "Skip goes on without a key. A key you type is tested before you go on."
    /** Step 3 with a key saved on the TV: going on keeps it. */
    const val TMDB_KEEP_HINT = "Next keeps the saved key. A key you type is tested before you go on."
    const val CHECK_INTRO = "Send hands this to the TV. Nothing is saved until you choose Save there."
    const val TRY_ON_TV = "Test asks the TV to try it. Nothing is saved until you choose Save on the TV."
    const val IDLE_LIFETIME = "This page closes after 10 minutes without use."
    const val NO_PROVIDER = "No provider added: nothing plays until you add one, here or on the TV."
    const val NO_INDEXER = "No indexer added: you can still play .nzb files you open."
    const val NO_TESTS_LEFT = "No tests left with this code: the TV tests what you send when you save."
    /** Step 4's empty Usenet list while a debrid card plays or searches in its place. */
    const val NONE_ADDED = "None added."

    const val STALE = "This page was out of date, so nothing from it was kept."
    const val NOTHING_TO_TEST = "Nothing to test yet: fill this in first."
    const val NOTHING_TO_SEND = "Nothing to send yet: nothing here would change on the TV."

    /** Under a password or key's field, by what the card holds: never the secret, and the field stays empty. */
    const val TYPED_KEPT = "Typed · kept on the TV. Leave empty to keep it."
    const val SAVED_KEPT = "Saved on the TV · leave empty to keep it (for the same server)."
    const val TMDB_SAVED_KEPT = "Saved on the TV · leave empty to keep it."
    /** A saved debrid service's key: its card's service can't change, so the same server goes without saying. */
    const val SERVICE_SAVED_KEPT = "Saved on the TV · leave empty to keep it."
    const val PASSWORD_UNREADABLE = "The password saved on the TV can't be read: type it."
    const val KEY_UNREADABLE = "The API key saved on the TV can't be read: type it."
    const val TMDB_UNREADABLE = "The key saved on the TV can't be read: type a new one, or leave it."

    /** A card's line once what it holds isn't what it was tested with. */
    const val CHANGED_SINCE_TEST = "Changed since its test: test it again, or don't add it."
    const val CHANGED_SINCE_TEST_SAVED = "Changed since its test: test it again, or undo the changes."
    const val CHANGED_SUMMARY = "Changed since its test"

    /** A card that may go on untested: no test can run. */
    const val UNTESTED_NO_TESTS = "Not tested: the TV tests it when you save."
    const val UNTESTED_CLOSING = "Not tested: this code closes in less than a minute. Send now; Save on the TV tests it."

    /** An indexer the app wouldn't test: plain http over the internet. Test anyway does. */
    const val PLAIN_HTTP = "This address isn't encrypted: your API key would cross the internet readable."
    const val HIDDEN_PARTS = "Some parts of this address are kept on the TV and not shown."
    /** The same under the address on its open card, where changing it gives them up. */
    const val HIDDEN_PARTS_KEPT = "Some parts of this address are kept on the TV and not shown. Change it and they are left out."
    const val READ_ONLY = "Edit this one on the TV."

    /** Under a torrent indexer's "Use with your debrid service" (DbPhoneSetupService 4: a box, the page having no script). */
    const val TORRENT_DEBRID_HELP = "Some private trackers don’t allow debrid services. Untick it for those."
    /** A torrent indexer's summary when its box is unticked, as its Settings row says it. */
    const val NOT_WITH_SERVICE = "not used with your debrid service"
    /** A debrid service card's summary with no key typed or saved. */
    const val NO_KEY_YET = "No key yet"

    /** A closed card with fields the TV couldn't hold, on its summary. */
    const val PROBLEMS_SUMMARY = "Something typed couldn't be kept: edit it to check."

    /** Inert hints to password managers not to fill or save a field; they run nothing. */
    private const val NOT_A_LOGIN = "data-1p-ignore data-lpignore=\"true\" data-bwignore data-form-type=\"other\""

    /**
     * A page of the wizard or the edit page, as [view] has it. Values typed go back into their inputs, escaped; a
     * password or key never does (the view has none to give). Saved names, hosts, usernames and addresses are shown,
     * cleaned of controls and escaped. One form, with no validation (one bad field mustn't block another card's
     * buttons; the TV checks); its first button is Enter's, which tests the open card and never sends or drops.
     */
    internal fun render(view: SetupView): String = page(
        buildString {
            append("<h1 id=\"").append(SetupFlow.TOP).append("\">").append(TITLE).append("</h1>\n")
            if (view.step == SetupStep.Edit) {
                append("<p>").append(EDIT_INTRO).append("</p>\n")
            } else {
                append("<div class=\"dots\">")
                for (n in 0 until WIZARD_STEPS) append(if (n <= view.step.ordinal) "<span class=\"on\"></span>" else "<span></span>")
                append("</div>\n<p class=\"step\">").append(stepLine(view.step)).append("</p>\n")
            }
            val listsSaved = when (view.step) {
                SetupStep.Providers -> (view.providers + view.services).any { it.saved }
                SetupStep.Indexers -> (view.indexers + view.torrentIndexers).any { it.saved }
                SetupStep.Tmdb -> false
                SetupStep.Check, SetupStep.Edit -> (view.providers + view.indexers + view.services + view.torrentIndexers).any { it.saved }
            }
            append("<p class=\"warn\">").append(SETUP_NOT_ENCRYPTED).append(if (listsSaved) " $SAVED_SHOWN" else "").append("</p>\n")
            // A line about a card goes on it: the page opens scrolled to that card, and the top is then out of sight.
            view.notice?.takeIf { cardWith(view, it) == null }?.let { append("<p class=\"notice\">").append(notice(it, view)).append("</p>\n") }
            append("<form method=\"post\" accept-charset=\"UTF-8\" autocomplete=\"off\" novalidate>\n")
            append("<input type=\"hidden\" name=\"").append(SetupForm.VERSION).append("\" value=\"").append(view.version).append("\">\n")
            // First, for Enter: it tests the open card. Not disabled: on a disabled first button some browsers press the next.
            append(button(SetupAction.Enter, "Test", "offscreen\" tabindex=\"-1\" aria-hidden=\"true")).append('\n')
            // The debrid sections follow Usenet's, as Settings orders them; in the wizard, each beside its Usenet half (L209).
            when (view.step) {
                SetupStep.Providers -> {
                    section(view, view.providers, SetupSection.Provider)
                    if (view.debrid) debridSection(view, SetupSection.Service)
                }
                SetupStep.Indexers -> {
                    section(view, view.indexers, SetupSection.Indexer)
                    if (view.debrid) debridSection(view, SetupSection.TorrentIndexer)
                }
                SetupStep.Tmdb -> {
                    append("<p>").append(TMDB_WHY).append("</p>\n")
                    card(view, view.tmdb)
                }
                SetupStep.Check -> check(view)
                SetupStep.Edit -> {
                    append("<h2>Usenet providers · tried in this order</h2>\n")
                    section(view, view.providers, SetupSection.Provider)
                    append("<h2>Indexers</h2>\n")
                    section(view, view.indexers, SetupSection.Indexer)
                    if (view.debrid) {
                        debridSection(view, SetupSection.Service)
                        debridSection(view, SetupSection.TorrentIndexer)
                    }
                    append("<h2>TMDB</h2>\n")
                    card(view, view.tmdb)
                }
            }
            append("<div class=\"buttons\">")
            if (view.step == SetupStep.Indexers || view.step == SetupStep.Tmdb || view.step == SetupStep.Check) append(button(SetupAction.Back, "Back", "second"))
            when (view.step) {
                SetupStep.Check, SetupStep.Edit -> append(button(SetupAction.Send, "Send to TV", disabled = !view.ready))
                // Skip only with no key at all: a saved one goes on with Next, kept.
                SetupStep.Tmdb -> append(button(SetupAction.Next, if (view.tmdb.secret == SecretState.None) "Skip" else "Next", disabled = !view.ready))
                else -> append(button(SetupAction.Next, "Next", disabled = !view.ready))
            }
            append("</div>\n")
            hints(view)
            append("</form>\n")
        },
    )

    /** A debrid section under its heading: its cards and Add. */
    private fun StringBuilder.debridSection(view: SetupView, section: SetupSection) {
        append(if (section == SetupSection.Service) "<h2>Debrid service</h2>\n" else "<h2>Torrent indexers</h2>\n")
        section(view, if (section == SetupSection.Service) view.services else view.torrentIndexers, section)
    }

    /**
     * A kind's cards, then Add (below the cap) and, in the wizard, what an empty step means: nothing plays without a
     * provider, or a debrid service in its place; nothing is searched without an indexer, or a torrent indexer.
     */
    private fun StringBuilder.section(view: SetupView, cards: List<CardView>, section: SetupSection) {
        cards.forEach { card(view, it) }
        val none = cards.none { !it.blank && !it.removed }
        val canAdd = when (section) {
            SetupSection.Provider -> view.canAddProvider
            SetupSection.Indexer -> view.canAddIndexer
            SetupSection.Service -> view.canAddService
            SetupSection.TorrentIndexer -> view.canAddTorrentIndexer
            SetupSection.Tmdb -> false
        }
        if (canAdd) {
            val what = when (section) {
                SetupSection.Provider -> "provider"
                SetupSection.Indexer -> "indexer"
                SetupSection.Service -> "debrid service"
                else -> "torrent indexer"
            }
            val label = if (view.step == SetupStep.Edit || none) (if (section == SetupSection.Indexer) "Add an $what" else "Add a $what") else "Add another $what"
            append(button(SetupAction.Add(section), label, "second wide")).append('\n')
        }
        val stands = { other: List<CardView> -> other.any { !it.blank && !it.removed } }
        val empty = when (section) {
            SetupSection.Provider -> NO_PROVIDER.takeUnless { stands(view.services) }
            SetupSection.Indexer -> NO_INDEXER.takeUnless { stands(view.torrentIndexers) }
            else -> null
        }
        if (view.step != SetupStep.Edit && none && empty != null) append("<p class=\"hint\">").append(empty).append("</p>\n")
    }

    private fun StringBuilder.card(view: SetupView, card: CardView) = if (card.open) open(view, card) else summary(view, card)

    /** The card [notice] is about, when this page shows it as a card; null for a line about the page. */
    private fun cardWith(view: SetupView, notice: SetupNotice): CardView? {
        val slot = notice.slot ?: return null
        val shown = when (view.step) {
            SetupStep.Providers -> view.providers + view.services
            SetupStep.Indexers -> view.indexers + view.torrentIndexers
            SetupStep.Tmdb -> listOf(view.tmdb)
            SetupStep.Edit -> view.cards
            SetupStep.Check -> emptyList()
        }
        return shown.firstOrNull { it.slot == slot }
    }

    /** The one-shot line, on the card it is about. */
    private fun StringBuilder.noticeOn(view: SetupView, card: CardView) {
        val notice = view.notice ?: return
        if (cardWith(view, notice) === card) append("<p class=\"notice\">").append(notice(notice, view)).append("</p>\n")
    }

    /** A card with its inputs: its legend, its lines, its fields, its buttons. */
    private fun StringBuilder.open(view: SetupView, card: CardView) {
        append("<fieldset id=\"").append(card.slot.key).append("\">\n<legend>").append(escape(clean(legend(card)))).append("</legend>\n")
        noticeOn(view, card)
        for ((field, reason) in card.problems.entries.sortedBy { it.key.ordinal }) {
            val why = if (reason == SetupRejection.Reason.NotANumber) "it isn't a whole number" else "it has a character it can't hold"
            // Its field points at it, for a screen reader.
            append("<p id=\"").append(problemId(field)).append("\" class=\"result fail\">").append(field.label).append(": ").append(why).append(", so it wasn't kept.</p>\n")
        }
        card.result?.let { append(resultLine(it)) }
        when {
            card.untested != null -> append("<p class=\"result\">").append(untested(card.untested)).append("</p>\n")
            card.result == null && card.changedSinceTest ->
                append("<p class=\"result\">").append(if (card.saved) CHANGED_SINCE_TEST_SAVED else CHANGED_SINCE_TEST).append("</p>\n")
        }
        for (field in SetupField.entries.filter { it.section == card.slot.section }) append(input(field, card))
        append("<div class=\"buttons\">")
        if (view.canTest) {
            val tested = card.result != null || card.changedSinceTest
            append(button(SetupAction.Test, if (tested) "Test again" else "Test", if (card.need == Need.Test) "" else "second"))
            if (card.result?.plainHttp == true) append(button(SetupAction.TestHttp, "Test anyway", "second"))
        }
        dropButtons(card, "second")
        append("</div>\n</fieldset>\n")
    }

    /** A card in one line or three: what it is, how it went, what can be done with it. */
    private fun StringBuilder.summary(view: SetupView, card: CardView) {
        append("<div class=\"card").append(if (card.removed) " gone" else "").append("\" id=\"").append(card.slot.key).append("\">\n")
        append("<p class=\"ct\">")
        if (view.step == SetupStep.Edit && !card.saved && card.slot.section != SetupSection.Tmdb) append("<span class=\"tag\">New</span>")
        append(escape(clean(title(card)))).append("</p>\n")
        noticeOn(view, card)
        meta(card, kept = false)
        state(card)?.let { (style, words) -> append("<p class=\"st").append(style).append("\">").append(words).append("</p>\n") }
        append("<div class=\"buttons\">")
        val small = "second small"
        // Each button with its card's name, for a screen reader moving by controls: summaries' buttons read alike.
        val of = escape(clean(title(card)))
        when {
            card.removed -> append(button(SetupAction.Keep(card.slot), "Keep it", small, of = of))
            card.readOnly -> append(button(SetupAction.Drop(card.slot), "Remove", small, of = of))
            else -> {
                if (view.canTest && card.need == Need.Test) {
                    append(button(SetupAction.TestCard(card.slot), if (card.result != null || card.changedSinceTest) "Test again" else "Test", small, of = of))
                }
                append(button(SetupAction.Open(card.slot), if (card.slot.section == SetupSection.Tmdb) "Change" else "Edit", small, of = of))
                dropButtons(card, small, of)
            }
        }
        append("</div>\n</div>\n")
    }

    /** Undo changes and Remove for a saved card, Don't add this one for a new one (or a key typed); [of]: as [button]'s. */
    private fun StringBuilder.dropButtons(card: CardView, style: String, of: String? = null) {
        when {
            card.slot.section == SetupSection.Tmdb -> if (card.secret == SecretState.Typed) append(button(SetupAction.Undo(card.slot), "Don't add this one", style, of = of))
            card.saved -> {
                if (card.changed) append(button(SetupAction.Undo(card.slot), "Undo changes", style, of = of))
                append(button(SetupAction.Drop(card.slot), "Remove", style, of = of))
            }
            else -> append(button(SetupAction.Drop(card.slot), "Don't add this one", style, of = of))
        }
    }

    /** Step 4: what Send would do, kind by kind, with no inputs; the debrid kinds only when there is something of them. */
    private fun StringBuilder.check(view: SetupView) {
        val services = view.services.filterNot { it.blank }
        val torrentIndexers = view.torrentIndexers.filterNot { it.blank }
        // Listed whether they stay or go; only one that stays plays or searches in Usenet's place, as on steps 1 and 2.
        val stands = { cards: List<CardView> -> cards.any { !it.removed } }
        append("<h2>Usenet providers</h2>\n")
        list(view.providers.filterNot { it.blank }, if (stands(services)) NONE_ADDED else NO_PROVIDER)
        append("<h2>Indexers</h2>\n")
        list(view.indexers.filterNot { it.blank }, if (stands(torrentIndexers)) NONE_ADDED else NO_INDEXER)
        if (services.isNotEmpty()) {
            append("<h2>Debrid service</h2>\n")
            list(services, NONE_ADDED)
        }
        if (torrentIndexers.isNotEmpty()) {
            append("<h2>Torrent indexers</h2>\n")
            list(torrentIndexers, NONE_ADDED)
        }
        append("<h2>TMDB</h2>\n<div class=\"list\">\n<div class=\"li\">\n")
        val tmdb = view.tmdb
        when {
            tmdb.secret == SecretState.Typed -> {
                // Over a key saved on the TV, one typed replaces it.
                append("<p class=\"ct\"><span class=\"tag\">").append(if (tmdb.savedSecret != SavedSecret.None) "Changes" else "New").append("</span>TMDB key</p>\n")
                meta(tmdb, kept = true)
                state(tmdb)?.let { (style, words) -> append("<p class=\"st").append(style).append("\">").append(words).append("</p>\n") }
            }
            tmdb.secret == SecretState.None -> append("<p class=\"ct\">No key</p>\n<p class=\"cm\">Skipped. Add one later on the TV or here.</p>\n")
            else -> {
                append("<p class=\"ct\"><span class=\"tag\">Saved</span>TMDB key</p>\n")
                meta(tmdb, kept = true)
            }
        }
        append("</div>\n</div>\n<p>").append(CHECK_INTRO).append("</p>\n")
    }

    private fun StringBuilder.list(cards: List<CardView>, none: String) {
        if (cards.isEmpty()) {
            append("<p class=\"hint\">").append(none).append("</p>\n")
            return
        }
        append("<div class=\"list\">\n")
        for (card in cards) {
            val (tag, style) = when {
                !card.saved -> "New" to ""
                card.removed -> "Removes" to " rm"
                card.changed -> "Changes" to ""
                else -> "Saved" to ""
            }
            append("<div class=\"li\">\n<p class=\"ct\"><span class=\"tag").append(style).append("\">").append(tag).append("</span>")
            append(escape(clean(title(card)))).append("</p>\n")
            meta(card, kept = true)
            if (!card.removed && (card.changed || !card.saved)) {
                state(card)?.let { (s, words) -> append("<p class=\"st").append(s).append("\">").append(words).append("</p>\n") }
            }
            append("</div>\n")
        }
        append("</div>\n")
    }

    /** Under the buttons: why Next or Send waits, what would change, the tests and the time left. */
    private fun StringBuilder.hints(view: SetupView) {
        val blocking = view.blocking
        when {
            blocking != null -> append("<p class=\"hint\"><strong>").append(waits(blocking, view)).append("</strong></p>\n")
            (view.step == SetupStep.Check || view.step == SetupStep.Edit) && !view.changed ->
                append("<p class=\"hint\"><strong>").append(NOTHING_TO_SEND).append("</strong></p>\n")
        }
        if (view.step == SetupStep.Edit) counts(view)?.let { append("<p class=\"hint\">").append(it).append("</p>\n") }
        if (view.step == SetupStep.Tmdb) {
            // A key typed has its own lines on the card: what is held, and its test.
            when (view.tmdb.secret) {
                SecretState.None -> TMDB_SKIP_HINT
                SecretState.Saved, SecretState.Unreadable -> TMDB_KEEP_HINT
                SecretState.Typed -> null
            }?.let { append("<p class=\"hint\">").append(it).append("</p>\n") }
        }
        val cardPage = view.step != SetupStep.Check
        val tries = view.step == SetupStep.Providers || view.step == SetupStep.Indexers || view.step == SetupStep.Edit && view.open != null
        if (tries && view.canTest) append("<p class=\"hint\">").append(TRY_ON_TV).append("</p>\n")
        if (cardPage) {
            when (val left = view.testsLeft) {
                0 -> append("<p class=\"hint\">").append(NO_TESTS_LEFT).append("</p>\n")
                in 1..FEW_TESTS -> append("<p class=\"hint\">").append(if (left == 1) "1 test" else "$left tests").append(" left with this code.</p>\n")
            }
        }
        append("<p class=\"hint\">").append(IDLE_LIFETIME).append("</p>\n")
        view.closesIn?.let { append("<p class=\"hint\">This code closes in about ").append(if (it == 1) "1 minute" else "$it minutes").append(".</p>\n") }
    }

    /** "1 new · 1 changed · 1 to remove", or null when nothing would change. */
    private fun counts(view: SetupView): String? {
        val cards = view.providers + view.indexers + view.services + view.torrentIndexers
        val tmdbTyped = view.tmdb.secret == SecretState.Typed
        val tmdbSaved = view.tmdb.savedSecret != SavedSecret.None
        val new = cards.count { !it.saved && !it.blank } + if (tmdbTyped && !tmdbSaved) 1 else 0
        val changed = cards.count { it.saved && it.changed && !it.removed } + if (tmdbTyped && tmdbSaved) 1 else 0
        val removed = cards.count { it.removed }
        return listOfNotNull("$new new".takeIf { new > 0 }, "$changed changed".takeIf { changed > 0 }, "$removed to remove".takeIf { removed > 0 })
            .joinToString(" · ").ifEmpty { null }
    }

    /** Why Next or Send waits on [card], as markup: its name escaped. */
    private fun waits(card: CardView, view: SetupView): String {
        val name = if (card.slot.section == SetupSection.Tmdb) "the key" else escape(clean(title(card)))
        val again = if (card.result != null || card.changedSinceTest) " again" else ""
        val secret = if (card.slot.section == SetupSection.Provider) "the password" else "the API key"
        val orElse = when {
            card.slot.section == SetupSection.Tmdb || !card.saved -> "don't add it"
            else -> "undo its changes"
        }
        return if (view.step == SetupStep.Check || view.step == SetupStep.Edit) {
            when (card.need) {
                Need.Secret -> "Send to TV waits until you type $secret for $name or remove it."
                Need.Fix -> "Send to TV waits until you fix what $name says."
                else -> if (orElse == "don't add it") "Send to TV waits until $name is tested or not added." else "Send to TV waits until $name is tested or its change is undone."
            }
        } else {
            when (card.need) {
                Need.Secret -> "To go on, type $secret for $name or remove it."
                Need.Fix -> "To go on, fix what $name says."
                else -> "To go on, test $name$again or $orElse."
            }
        }
    }

    /** A notice's line, as markup. */
    private fun notice(notice: SetupNotice, view: SetupView): String = when (notice) {
        SetupNotice.Stale -> STALE
        is SetupNotice.NothingToTest -> NOTHING_TO_TEST
        SetupNotice.NothingToSend -> NOTHING_TO_SEND
        is SetupNotice.NotReady -> view.cards.firstOrNull { it.slot == notice.slot }?.let { waits(it, view) } ?: STALE
    }

    /** A summary's line: how its test went, or what it is now; null for nothing to say. */
    private fun state(card: CardView): Pair<String, String>? {
        val result = card.result
        return when {
            card.removed -> " bad" to "Will be removed"
            card.readOnly -> "" to READ_ONLY
            card.need == Need.Fix -> " bad" to PROBLEMS_SUMMARY
            card.need == Need.Secret -> " bad" to if (card.slot.section == SetupSection.Provider) PASSWORD_UNREADABLE else KEY_UNREADABLE
            card.untested != null -> "" to untested(card.untested)
            result?.outcome == SetupOutcome.Passed -> " good" to if (card.saved && card.changed) "Changed · ✓ Works" else "✓ Works"
            result?.outcome == SetupOutcome.Failed -> " bad" to "✗ Failed"
            result != null -> "" to "Not tested"
            card.changedSinceTest -> "" to CHANGED_SUMMARY
            card.slot.section == SetupSection.Tmdb -> if (card.secret == SecretState.Typed) "" to "Not tested" else null
            card.saved && !card.changed -> "" to if (card.enabled) "Saved" else "Saved · switched off"
            card.saved -> "" to "Changed"
            else -> "" to "Not tested"
        }
    }

    /**
     * What a card is: "news.example.net:563 · TLS · me · password typed", an address and its key, a debrid account and
     * its key, or the TMDB key's state. A card to be removed says nothing of its password or key: it goes with the card.
     */
    private fun StringBuilder.meta(card: CardView, kept: Boolean) {
        val v = card.values
        fun secretOf(what: String) = if (card.removed) null else secretWords(card, what, kept)
        val line = when (card.slot.section) {
            SetupSection.Provider -> {
                val tls = v[SetupField.ProviderTls] == "on"
                val host = v[SetupField.ProviderHost].orEmpty()
                val port = v[SetupField.ProviderPort].orEmpty().ifEmpty { if (tls) "${ProviderDraft.TLS_PORT}" else "${ProviderDraft.PLAIN_PORT}" }
                val user = v[SetupField.ProviderUsername].orEmpty()
                listOfNotNull(
                    if (host.isEmpty()) "No host yet" else "$host:$port",
                    "TLS".takeIf { tls },
                    user.ifEmpty { null },
                    secretOf("password").takeIf { user.isNotEmpty() || card.secret == SecretState.Typed },
                ).joinToString(" · ")
            }
            SetupSection.Indexer -> listOfNotNull(v[SetupField.IndexerUrl].orEmpty().ifEmpty { "No address yet" }, secretOf("key")).joinToString(" · ")
            SetupSection.Tmdb -> when (card.secret) {
                SecretState.Typed -> "Key typed"
                SecretState.Saved -> "A key is saved."
                SecretState.Unreadable -> "The key saved on the TV can't be read."
                SecretState.None -> "No key."
            }
            SetupSection.Service -> listOfNotNull(card.account?.takeIf { it.isNotEmpty() }, secretOf("key")).joinToString(" · ").ifEmpty { NO_KEY_YET }
            SetupSection.TorrentIndexer -> listOfNotNull(
                v[SetupField.TorrentIndexerUrl].orEmpty().ifEmpty { "No address yet" },
                secretOf("key"),
                NOT_WITH_SERVICE.takeIf { v[SetupField.TorrentIndexerDebrid] != "on" },
            ).joinToString(" · ")
        }
        append("<p class=\"cm\">").append(escape(clean(line))).append("</p>\n")
        if (card.hiddenParts) append("<p class=\"cm\">").append(HIDDEN_PARTS).append("</p>\n")
    }

    private fun secretWords(card: CardView, what: String, kept: Boolean): String? = when (card.secret) {
        SecretState.Typed -> "$what typed"
        SecretState.Saved -> if (kept) "saved $what kept" else "$what saved"
        SecretState.Unreadable -> "saved $what can't be read"
        SecretState.None -> null
    }

    /** A card's name: what is typed in its name, else a provider's host, a debrid card's service, else its place ("Provider 2"). */
    private fun title(card: CardView): String {
        val name = when (card.slot.section) {
            SetupSection.Provider -> card.values[SetupField.ProviderName].orEmpty().ifBlank { card.values[SetupField.ProviderHost].orEmpty().takeIf { card.saved }.orEmpty() }
            SetupSection.Indexer -> card.values[SetupField.IndexerName].orEmpty()
            SetupSection.Tmdb -> "TMDB key"
            // Its service, as chosen; a saved account's is its own.
            SetupSection.Service -> card.choices.firstOrNull { it.value == card.values[SetupField.ServiceKind] }?.name.orEmpty()
            SetupSection.TorrentIndexer -> card.values[SetupField.TorrentIndexerName].orEmpty()
        }
        return name.ifBlank { card.slot.label }
    }

    /** An open card's legend: a saved one's name as the TV has it, else its place. */
    private fun legend(card: CardView) = when {
        card.slot.section == SetupSection.Tmdb -> "TMDB"
        card.saved -> card.savedName.orEmpty().ifBlank { card.values[SetupField.ProviderHost].orEmpty() }.ifBlank { card.slot.label }
        else -> card.slot.label
    }

    private fun untested(why: Untested) = if (why == Untested.ClosingSoon) UNTESTED_CLOSING else UNTESTED_NO_TESTS

    private fun stepLine(step: SetupStep) = when (step) {
        SetupStep.Providers -> "Step 1 of 4 · Usenet providers"
        SetupStep.Indexers -> "Step 2 of 4 · Indexers"
        SetupStep.Tmdb -> "Step 3 of 4 · TMDB key (optional)"
        else -> "Step 4 of 4 · Check and send"
    }

    /** A card's result line: ✓ or ✗ and the TV's words, why it wasn't tried, or the plain http warning. */
    private fun resultLine(result: SetupResult): String = when {
        result.plainHttp -> "<p class=\"result http\">$PLAIN_HTTP</p>\n"
        result.outcome == SetupOutcome.Passed -> "<p class=\"result ok\">✓ ${escape(words(result.words))}</p>\n"
        result.outcome == SetupOutcome.Failed -> "<p class=\"result fail\">✗ ${escape(words(result.words))}</p>\n"
        else -> "<p class=\"result\">Not tested: ${escape(words(result.words))}</p>\n"
    }

    /**
     * One of the open card's inputs: what it holds, escaped, but a password or key, which stays empty with a line on
     * what is kept. No autocorrect, capitals or saved entries; the keyboard's kind and the TV's limits, nothing required.
     */
    private fun input(field: SetupField, card: CardView): String {
        val plain = "autocapitalize=\"none\" autocorrect=\"off\" spellcheck=\"false\" autocomplete=\"off\""
        val value = card.values[field].orEmpty()
        val filled = if (value.isEmpty() || field.isSecret) "" else " value=\"${escape(value)}\""
        // The line under it (what a password or key field keeps, an address's hidden parts) and why it wasn't kept,
        // tied to it for a screen reader, which otherwise hears only its label.
        val under = when {
            field.isSecret -> kept(card)
            (field == SetupField.IndexerUrl || field == SetupField.TorrentIndexerUrl) && card.hiddenParts -> HIDDEN_PARTS_KEPT
            field == SetupField.TorrentIndexerDebrid -> TORRENT_DEBRID_HELP
            else -> null
        }
        // Under a service's key, where each service the card offers keeps it: the page has no script to show one alone.
        val places = if (field == SetupField.ServiceKey) card.choices.map { it.keyPlace } else emptyList()
        val placeIds = places.indices.map { "${field.key}-w${it + 1}" }
        val described = listOfNotNull(problemId(field).takeIf { field in card.problems }) + placeIds + listOfNotNull(hintId(field).takeIf { under != null })
        val about = if (described.isEmpty()) "" else " aria-describedby=\"${described.joinToString(" ")}\""
        val hint = places.zip(placeIds).joinToString("") { (place, id) -> "<p id=\"$id\" class=\"fh\">${escape(clean(place))}</p>\n" } +
            under?.let { "<p id=\"${hintId(field)}\" class=\"fh\">$it</p>\n" }.orEmpty()
        val id = "id=\"${field.key}\" name=\"${field.key}\"$filled$about"
        val max = "maxlength=\"${field.maxLength}\""
        val label = "<label for=\"${field.key}\">${field.label}</label>\n"
        return when (field) {
            SetupField.ProviderTls -> "<label class=\"check\"><input type=\"checkbox\" $id${if (value == "on") " checked" else ""}> ${field.label}</label>\n"
            SetupField.ProviderHost -> "$label<input $id inputmode=\"url\" $max $plain placeholder=\"news.example.net\">\n"
            // Text with a number keyboard, not type=number: unchecked (novalidate), that sends "" for what it can't read,
            // which would be the default here, and ignores maxlength. As typed, the TV names what it can't take.
            SetupField.ProviderPort -> "$label<input $id inputmode=\"numeric\" $max $plain placeholder=\"563 with TLS, 119 without\">\n"
            SetupField.ProviderConnections -> "$label<input $id inputmode=\"numeric\" $max $plain placeholder=\"8\">\n"
            SetupField.ProviderPassword -> "$label<input type=\"password\" $id $max $plain $NOT_A_LOGIN>\n$hint"
            // While it is the address shown, its line says changing it gives up the parts kept on the TV.
            SetupField.IndexerUrl -> "$label<input type=\"url\" $id inputmode=\"url\" $max $plain placeholder=\"https://indexer.example.org/api\">\n$hint"
            SetupField.IndexerKey, SetupField.TmdbKey -> "$label<input $id $max $plain $NOT_A_LOGIN>\n$hint"
            SetupField.TorrentIndexerUrl -> "$label<input type=\"url\" $id inputmode=\"url\" $max $plain placeholder=\"https://indexer.example.org/api\">\n$hint"
            SetupField.ServiceKey, SetupField.TorrentIndexerKey -> "$label<input $id $max $plain $NOT_A_LOGIN>\n$hint"
            SetupField.TorrentIndexerDebrid -> "<label class=\"check\"><input type=\"checkbox\" $id${if (value == "on") " checked" else ""}> ${field.label}</label>\n$hint"
            SetupField.ServiceKind -> radios(field, card)
            SetupField.ProviderName, SetupField.ProviderUsername, SetupField.IndexerName, SetupField.TorrentIndexerName -> "$label<input $id $max $plain>\n"
        }
    }

    /**
     * A choice as radio buttons, one a service the card offers ([CardView.choices], A to Z), its own checked: a group
     * with the field's label as its name, for a screen reader. None is checked on a new card until one is chosen.
     */
    private fun radios(field: SetupField, card: CardView): String = buildString {
        val group = "${field.key}-l"
        append("<label id=\"").append(group).append("\">").append(field.label).append("</label>\n")
        append("<div role=\"radiogroup\" aria-labelledby=\"").append(group).append("\">\n")
        for (choice in card.choices) {
            append("<label class=\"check\"><input type=\"radio\" name=\"").append(field.key).append("\" value=\"").append(escape(choice.value)).append('"')
            if (card.values[field] == choice.value) append(" checked")
            append("> ").append(escape(clean(choice.name))).append("</label>\n")
        }
        append("</div>\n")
    }

    /** What an empty password or key field keeps, for under it; null for nothing. */
    private fun kept(card: CardView): String? {
        return when (card.secret) {
            SecretState.Typed -> TYPED_KEPT
            SecretState.Saved -> when (card.slot.section) {
                SetupSection.Tmdb -> TMDB_SAVED_KEPT
                SetupSection.Service -> SERVICE_SAVED_KEPT
                else -> SAVED_KEPT
            }
            SecretState.Unreadable -> when (card.slot.section) {
                SetupSection.Provider -> PASSWORD_UNREADABLE
                SetupSection.Indexer -> KEY_UNREADABLE
                SetupSection.Tmdb -> TMDB_UNREADABLE
                SetupSection.Service, SetupSection.TorrentIndexer -> KEY_UNREADABLE
            }
            SecretState.None -> null
        }
    }

    /** The ids of a field's lines: why it wasn't kept, and what is under it. */
    private fun problemId(field: SetupField) = "${field.key}-p"

    private fun hintId(field: SetupField) = "${field.key}-h"

    /**
     * A submit button: [style] its class, if any; [disabled] for Next or Send while the page isn't ready; [of], the card's
     * name as markup, makes its name for a screen reader "Remove: Old block".
     */
    private fun button(action: SetupAction, label: String, style: String = "", disabled: Boolean = false, of: String? = null) =
        "<button type=\"submit\" name=\"${SetupForm.INTENT}\" value=\"${action.value}\"" +
            (if (style.isEmpty()) "" else " class=\"$style\"") + (of?.let { " aria-label=\"$label: $it\"" } ?: "") +
            (if (disabled) " disabled" else "") + ">$label</button>"

    /** Saved or typed text as a page shows it: without controls, bidi or zero-width characters. Escaped after. */
    private fun clean(text: String): String = buildString(text.length) {
        for (c in text) {
            when {
                c in BREAKS -> append(' ')
                SetupForm.spoofs(c) -> Unit
                else -> append(c)
            }
        }
    }.replace(SPACES, " ").trim()

    private const val WIZARD_STEPS = 4

    val sent: String = message("Sent. Check your TV.", "Nothing is saved until you choose Save there. You can close this page.")
    /** After 10 minutes without use, or 10 minutes unopened. */
    val expired: String = message("This code closed after 10 minutes without use.", "Choose New code on the TV.")
    /** At the code's hard cap, however much it is used. */
    val closedAtCap: String = message("This code closed: a code works for 45 minutes at most.", "Choose New code on the TV.")
    val used: String = message("This page has been used. Check your TV.", "To send something else, open a new code on the TV.")
    val locked: String = message("This code was closed after 5 wrong tries.", "Choose New code on the TV and scan the new one.")
    val notFound: String = message("Nothing here. Check the address on your TV.", "Or scan its code again.")
    val rejected: String = message("Something you typed couldn't be sent.", "Go back, check what you typed and send again.")
    val otherDevice: String = message("This code is open on another device.", "Check your TV. If that wasn't you, choose New code there.")
    val refused: String = message("This request was refused.", "Open the address shown on your TV.")

    private fun message(title: String, line: String) = page("<h1>$title</h1>\n<p>$line</p>\n")

    private fun page(main: String) =
        "<!doctype html>\n<html lang=\"en\">\n<head>\n<meta charset=\"utf-8\">\n" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n" +
            "<title>Set up your TV</title>\n<style>$STYLE</style>\n</head>\n<body>\n<main>\n$main</main>\n</body>\n</html>\n"

    private fun sha256(text: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))
}
