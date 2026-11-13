package io.skjaere.usenet.app.settings

import android.content.Context
import androidx.annotation.WorkerThread
import io.skjaere.usenet.app.debrid.DebridGate

// Settings › Privacy (W60, L138, L139; CsPhoneSettingsCast 3): the policy from the app's own copy, which the site's page
// holds too, its link once that page is live, and what a build set up with someone's accounts says above it. Read here,
// where a JVM test reads it.

const val PRIVACY_TITLE = "Privacy"

/**
 * The policy, in the APK: headings marked "## ", paragraphs a blank line apart (L139); its debrid parts marked
 * [DEBRID_START]…[DEBRID_END] (L211). The site's page holds it as a build with debrid hidden shows it.
 */
const val PRIVACY_ASSET = "privacy.txt"

/**
 * The marks around a debrid part of the policy (L211): the opening's words naming torrent indexers and debrid services,
 * and the part about them. A build that shows debrid shows them unmarked; one with debrid hidden leaves them out (L192),
 * as the site's page does while debrid is hidden in betas by default.
 */
const val DEBRID_START = "[debrid]"
const val DEBRID_END = "[/debrid]"

private val DEBRID_PART = Regex(Regex.escape(DEBRID_START) + "(.*?)" + Regex.escape(DEBRID_END), RegexOption.DOT_MATCHES_ALL)

/**
 * Above the policy in a build that came set up with someone's accounts (SEED_DEV_SETTINGS: a private build with preset
 * settings and debug builds; L138 as the lead reworded it, naming no one). Kept out of [PRIVACY_ASSET], so the site's page
 * stays equal to it.
 */
const val PRIVACY_SEEDED =
    "This build came set up with the Usenet provider, indexers and TMDB key of the person who gave it to you, so your " +
        "searches and what you play go to them under that person’s accounts."

/** A part of the policy as the page shows it: its [heading] (none for the opening) over its [paragraphs]. */
data class PrivacySection(val heading: String?, val paragraphs: List<String>)

/**
 * The page's first row (L138), once the site's page is live ([PRIVACY_ONLINE]): on a phone "Read it online" over the
 * address, which opens [url] in the browser; on a TV the address alone in its [title], with no [line] and nothing to open
 * (TV-WB), for the user's phone or computer.
 */
data class PrivacyLink(val title: String, val line: String?, val url: String?)

/**
 * [text] as [PRIVACY_ASSET] has it, in sections: a "## " line starts one, under that heading; the paragraphs before the
 * first heading are the opening's. A paragraph's lines are joined, so the file may wrap them. Its debrid parts show,
 * unmarked, while debrid is [shown], and are left out while it is hidden.
 */
fun privacySections(text: String, shown: Boolean = DebridGate.shown): List<PrivacySection> {
    val sections = mutableListOf<PrivacySection>()
    val policy = DEBRID_PART.replace(text.replace("\r\n", "\n")) { part -> if (shown) part.groupValues[1] else "" }
    policy.split(Regex("\n[ \t]*\n")).map { it.trim() }.filter { it.isNotEmpty() }.forEach { block ->
        if (block.startsWith(HEADING)) {
            sections += PrivacySection(block.removePrefix(HEADING).trim(), emptyList())
        } else {
            val paragraph = block.lines().joinToString(" ") { it.trim() }
            val last = sections.lastOrNull() ?: PrivacySection(null, emptyList()).also { sections += it }
            sections[sections.lastIndex] = last.copy(paragraphs = last.paragraphs + paragraph)
        }
    }
    return sections
}

private const val HEADING = "## "

/**
 * The page's sections: with [seeded] (a build set up with someone's accounts), [PRIVACY_SEEDED] first, then [policy]'s,
 * its debrid parts while debrid is [shown].
 */
fun privacyPage(policy: String, seeded: Boolean, shown: Boolean = DebridGate.shown): List<PrivacySection> =
    (if (seeded) listOf(PrivacySection(null, listOf(PRIVACY_SEEDED))) else emptyList()) + privacySections(policy, shown)

/**
 * The page's link on a phone, or on a [tv] the address in its place; none while the site's page isn't [online], in any
 * build, so the page is its top bar and then the policy.
 */
fun privacyLink(tv: Boolean, online: Boolean = PRIVACY_ONLINE): PrivacyLink? = when {
    !online -> null
    tv -> PrivacyLink("Read it at $PRIVACY_ADDRESS", null, null)
    else -> PrivacyLink("Read it online", PRIVACY_ADDRESS, "https://$PRIVACY_ADDRESS")
}

private const val PRIVACY_ADDRESS = "nzstreamer.app/privacy"

/**
 * Whether the policy is live at [PRIVACY_ADDRESS], so the page links it ([privacyLink]): false, as nothing is there yet.
 * It goes true once the page is live at the address, which the site's publish script puts up, on the
 * owner's word. PrivacyPageTest holds it false, so it never flips by mistake: a flip changes that test with it. The
 * device tests show the page both ways whatever it says (PrivacyScreen's online), and tv.PrivacyTvTest, which opens it
 * as the app does, follows it.
 */
const val PRIVACY_ONLINE = false

/** The policy as the APK carries it: a few KB, from the app's own assets, so the page reads offline. */
@WorkerThread
fun readPrivacyPolicy(context: Context): String = context.assets.open(PRIVACY_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
