package io.skjaere.usenet.app.settings

import io.skjaere.usenet.app.casting.CAST_LOOK_FOR
import io.skjaere.usenet.debrid.ServiceKind
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * L211's heading of the debrid part, which a build with debrid hidden leaves out, and so does the site's page
 * (PrivacySitePageTest).
 */
internal const val DEBRID_HEADING = "Your torrent indexers and debrid services"

/**
 * W25, L139: the privacy policy's one source, app/src/main/assets/privacy.txt, which the Privacy page shows and the site's
 * page holds. It parses as the page reads it, headings over paragraphs; its opening and its Google Cast part are the
 * approved words (CsPhoneSettingsCast, with the design's typography); it says nothing of what C2 doesn't do (subtitles, an
 * estimate, Wi-Fi speeds, the next episode); it says how Google's software is chosen and stopped, by the app's own words;
 * "On this device" names the transfer to a new phone; the beta builds' TMDB sentence is there and names nothing of
 * Discover, as the release APK carries the asset (checkNoDiscover reads every entry); "This website" is there. The site's
 * page, kept private, is checked against it by PrivacySitePageTest when given the page (item 53, L137).
 *
 * L374 (downloads, on phones and tablets): the providers' part says the app asks for the parts of what you play or
 * download, and "On this device" says downloads stay in the app's own storage, out of backups and transfers, and go with
 * an uninstall. No new recipient.
 *
 * L211, L192: the asset marks its debrid parts, the opening's words naming torrent indexers and debrid services and the
 * part about them, which says what the app sends them, what stays in an account and what the app removes from a TorBox
 * account (L255), the one service it names, in that sentence alone. The page shows them while debrid shows and leaves them
 * out while it is hidden; the site's page holds the hidden form, as debrid is hidden in betas by default, so neither the
 * part nor the opening's words.
 *
 * L574, L632 (plugins): the opening names GitHub, when plugins are used, and the services a plugin is
 * set up with; a part of its own, Plugins, just before "On this device", says when the app reads the plugin list and
 * downloads from GitHub, what GitHub sees, that a plugin runs with the app's access, and that its settings and keys stay
 * on the device; "On this device" says plugins aren't copied to a new phone. In both forms, every apostrophe the file's
 * own (’), and GitHub and plugins named nowhere else.
 */
class PrivacyTextTest {
    private val file = File("src/main/assets/privacy.txt")
    private val text by lazy { file.readText() }
    /** The policy as a build that shows debrid shows it. */
    private val sections by lazy { privacySections(text, shown = true) }

    /** The policy as a build with debrid hidden shows it, which the site's page holds (L211). */
    private val hidden by lazy { privacySections(text, shown = false) }
    private val paragraphs get() = sections.flatMap { it.paragraphs }

    private fun section(heading: String): String =
        sections.singleOrNull { it.heading == heading }?.paragraphs?.joinToString(" ") ?: fail("no section headed \"$heading\" in ${sections.map { it.heading }}")

    @Test
    fun `the asset parses as headings over paragraphs`() {
        assertTrue(file.isFile, "${file.absolutePath} is missing")
        val headings = listOf(
            null, "Your providers and indexers", "TMDB", "TheIntroDB", "Voice search", "Set up with your phone (on a TV)", "Play on TV",
            "Casting to Google Cast devices", "Plugins", "On this device", "This website",
        )
        assertEquals(headings.take(2) + DEBRID_HEADING + headings.drop(2), sections.map { it.heading })
        assertEquals(headings, hidden.map { it.heading })
        (sections + hidden).forEach { assertTrue(it.paragraphs.isNotEmpty(), "${it.heading} has no paragraph") }
        (sections + hidden).flatMap { it.paragraphs }.forEach { p ->
            assertFalse(p.isBlank() || "##" in p || "**" in p || '\n' in p || "[debrid]" in p || "[/debrid]" in p, "not a paragraph as the page shows one: $p")
        }
    }

    @Test
    fun `the opening is the approved one and names every recipient`() {
        assertEquals(listOf(OPENING), hidden.first().paragraphs)
        listOf("Usenet providers", "indexers", "TMDB", "TheIntroDB", "Google", "GitHub", "the services a plugin").forEach { assertTrue(it in OPENING, it) }
        // L211, L139: shown, it names the debrid recipients too.
        assertEquals(listOf(OPENING_SHOWN), sections.first().paragraphs)
        listOf("Usenet providers", "torrent indexers", "debrid services", "TMDB", "TheIntroDB", "Google", "GitHub").forEach { assertTrue(it in OPENING_SHOWN, it) }
    }

    /** L211: the opening's words naming the debrid recipients are marked, and so is the part about them; nothing else is. */
    @Test
    fun `the opening's debrid words and the debrid part are marked, and nothing else`() {
        val marked = Regex("""\[debrid](.*?)\[/debrid]""", RegexOption.DOT_MATCHES_ALL).findAll(text).map { it.groupValues[1] }.toList()
        assertEquals(listOf(DEBRID_WORDS, "## $DEBRID_HEADING\n\n$DEBRID_PART\n\n"), marked.map { it.replace("\r\n", "\n") })
        assertEquals(4, Regex("""\[/?debrid]""").findAll(text).count(), "two pairs of marks, and no other")
        assertTrue("[debrid]$DEBRID_WORDS[/debrid]" in openingAsWritten(), "the opening's words, marked in place")
    }

    /** The asset's opening as written, its marks and all. */
    private fun openingAsWritten(): String = text.replace("\r\n", "\n").substringBefore("\n\n")

    /**
     * L211, L255 (choice 4): the part about torrent indexers and debrid services, as drafted: what is sent, what stays in an
     * account, and that the app removes from a TorBox account the torrents it added, and nothing else. TorBox is named in
     * that sentence alone; no other service is named anywhere, and the hidden form names none.
     */
    @Test
    fun `the debrid part says what is sent, what stays in an account and what is removed, naming TorBox there alone`() {
        assertEquals(listOf(DEBRID_PART), sections.single { it.heading == DEBRID_HEADING }.paragraphs)
        assertTrue(REMOVED_SENTENCES in DEBRID_PART)
        assertFalse("removes nothing there" in text, "D1's sentence, the app removing nothing")
        assertTrue("Only the hash is sent, never a torrent file, its trackers or which indexer found it." in DEBRID_PART)
        val torBox = Regex("(?i)tor\\s*box")
        assertEquals(1, torBox.findAll(REMOVED_SENTENCES).count(), "TorBox in L255's sentence")
        assertNull(torBox.find(text.replace(REMOVED_SENTENCES, ""))?.value, "TorBox outside L255's sentence")
        val others = ServiceKind.entries.filter { it != ServiceKind.TorBox }.map { it.displayName } + OTHER_SERVICES
        others.forEach { name -> assertFalse(name.lowercase() in text.lowercase(), "names $name") }
        assertTrue(hidden.none { it.heading == DEBRID_HEADING } && hidden.flatMap { it.paragraphs }.none { "debrid" in it }, "hidden, the part shows")
        assertTrue(hidden.flatMap { it.paragraphs }.none { p -> torBox in p }, "hidden, a service named")
    }

    /**
     * L421: a download asks a debrid service for its file as a play does, its torrent may stay in the account's list, and
     * the app removes the one it added a couple of minutes after the download finishes. Said in the debrid part alone, which
     * the hidden form leaves out with the rest of it.
     */
    @Test
    fun `the debrid part says a download asks a service, is kept in its list and goes once it finishes`() {
        val debrid = sections.single { it.heading == DEBRID_HEADING }.paragraphs.single()
        DOWNLOAD_PHRASES.forEach { phrase ->
            assertTrue(phrase in debrid, phrase)
            assertEquals(1, text.split(phrase).size - 1, "once: $phrase")
            assertFalse(hidden.flatMap { it.paragraphs }.any { phrase in it }, "hidden: $phrase")
        }
        listOf("for what you play asks them", "keep what you play in your", "after you stop playing (or").forEach { before ->
            assertFalse(before in text, "the words from before downloads: $before")
        }
    }

    @Test
    fun `its Google Cast part is the approved words exactly`() {
        assertEquals(CAST_PART, sections.single { it.heading == "Casting to Google Cast devices" }.paragraphs)
    }

    @Test
    fun `it says nothing of subtitles, an estimate, Wi-Fi speeds or the next episode`() {
        val unbuilt = Regex("(?i)subtitle|estimat|speed|mbps|bandwidth|next episode|up next")
        paragraphs.forEach { assertNull(unbuilt.find(it)?.value, it) }
    }

    @Test
    fun `it says how Google's software is chosen and stopped, in the app's own words`() {
        val cast = section("Casting to Google Cast devices")
        assertTrue("“$CAST_LOOK_FOR”" in cast, "the list's row")
        assertTrue("Settings › Playback › ${GoogleCastPage.TITLE}" in cast, "the setting's page")
    }

    /** L632: the first sentence ends with GitHub and a plugin's services, the "and" before Google moved to GitHub. */
    @Test
    fun `the first sentence ends with GitHub and the services a plugin is set up with, in both forms`() {
        listOf(sections, hidden).forEach { form ->
            val opening = form.first().paragraphs.single()
            assertTrue(opening.endsWith(OPENING_END), opening)
            assertFalse("and to Google" in opening, "the and before Google moves to GitHub")
        }
    }

    /** L632: Plugins, just before On this device, holds the two paragraphs word for word, in both forms, with ’. */
    @Test
    fun `the Plugins part holds both paragraphs word for word, just before On this device, in both forms`() {
        listOf(sections, hidden).forEach { form ->
            val headings = form.map { it.heading }
            assertEquals(headings.indexOf("On this device") - 1, headings.indexOf("Plugins"), headings.toString())
            assertEquals(headings.indexOf("Casting to Google Cast devices") + 1, headings.indexOf("Plugins"), headings.toString())
            assertEquals(PLUGINS_PART, form.single { it.heading == "Plugins" }.paragraphs)
        }
        assertTrue("GitHub’s privacy statement applies." in PLUGINS_PART[0])
        assertTrue("A plugin’s own settings and keys stay on this device." in PLUGINS_PART[1])
        assertFalse(PLUGINS_PART.any { '\'' in it }, "the file's apostrophes are ’")
    }

    /** L632: GitHub and plugins are named in the opening, the Plugins part and On this device alone; nothing else moved. */
    @Test
    fun `GitHub and plugins are named in the opening, the Plugins part and On this device alone`() {
        listOf(sections, hidden).forEach { form ->
            form.forEach { section ->
                val named = section.paragraphs.any { "GitHub" in it || Regex("(?i)plugin").containsMatchIn(it) }
                assertEquals(section.heading in setOf(null, "Plugins", "On this device"), named, "${section.heading}")
            }
        }
    }

    @Test
    fun `On this device names the transfer to a new phone`() {
        val device = section("On this device")
        assertTrue("transfer to a new phone" in device, device)
        assertTrue("never your passwords, keys or TV pairings" in device, device)
        // L632: plugins aren't copied to a new phone either.
        assertTrue("never your passwords, keys or TV pairings, or plugins." in device, device)
    }

    /** L374: a download's parts come from the same providers as a play's, so their part says both, and names no one new. */
    @Test
    fun `the providers' part says the app asks for the parts of what you play or download`() {
        val providers = section("Your providers and indexers")
        assertTrue("asks your Usenet providers for the parts of what you play or download." in providers, providers)
        assertFalse("for the parts of what you play." in text, "the words from before downloads")
        assertEquals(listOf(OPENING), hidden.first().paragraphs, "the opening, and so its recipients, as before")
    }

    /** L374, L350: downloads stay on the device, out of Android's backups and its transfer to a new phone, and an uninstall deletes them. */
    @Test
    fun `On this device says downloads stay, aren't backed up or copied to a new phone, and go with an uninstall`() {
        val device = section("On this device")
        assertTrue(DOWNLOADS_STAY in device, device)
        // Said where the transfer is, after what it may copy.
        assertTrue(device.indexOf("never your passwords, keys or TV pairings") < device.indexOf(DOWNLOADS_STAY), device)
        assertEquals(device, hidden.single { it.heading == "On this device" }.paragraphs.joinToString(" "), "the same in the hidden form the site holds")
    }

    @Test
    fun `the beta builds' TMDB sentence is there and names nothing of Discover`() {
        assertTrue("In the beta builds, the lists of titles to browse are asked of TMDB" in section("TMDB"))
        DISCOVER_MARKERS.forEach { assertFalse(it in text, "Discover's marker \"$it\"") }
        assertNull(Regex("(?i)discover").find(text)?.value, "the word Discover")
    }

    @Test
    fun `This website is there`() {
        assertTrue(section("This website").startsWith("nzstreamer.app is served by Cloudflare"))
    }

    private companion object {
        /** CsPhoneSettingsCast's opening, as approved: a build with debrid hidden shows it, and the site's page holds it. */
        const val OPENING =
            "NZStreamer has no analytics, no ads and no server of its own. What leaves this phone goes to your own Usenet " +
                "providers and indexers, to TMDB with your own key, to TheIntroDB while its switch is on, to Google while its " +
                "Cast software runs, and to GitHub when you use plugins, and to the services a plugin you install is set up with."

        /** L632's end of the first sentence: the "and" before Google moves to GitHub. */
        const val OPENING_END =
            "to TheIntroDB while its switch is on, to Google while its Cast software runs, and to GitHub when you use plugins, " +
                "and to the services a plugin you install is set up with."

        /** L632's Plugins part, word for word, with the file's ’. A plugin's own sentences come with that plugin. */
        val PLUGINS_PART = listOf(
            "The app reads the list of plugins from GitHub when you open Settings › Plugins, and once a day while a plugin is " +
                "installed. Installing or updating a plugin downloads it from GitHub. GitHub sees your internet address, the time " +
                "and what is downloaded; the app sends it nothing else, and names no device or version. With no plugin installed " +
                "and Plugins never opened, the app never contacts GitHub. GitHub’s privacy statement applies.",
            "A plugin runs inside the app, with the same access as the app, so the app runs only plugins signed with its own " +
                "key. A plugin you install talks to the services you set it up with, such as your own server. A plugin’s own " +
                "settings and keys stay on this device.",
        )

        /** L374's sentence in "On this device". */
        const val DOWNLOADS_STAY =
            "Downloads stay on this phone or tablet, in NZStreamer’s own storage: they aren’t backed up or copied to a new phone, " +
                "and uninstalling the app deletes them."

        /** The opening's words naming the debrid recipients (L211), marked in the asset. */
        const val DEBRID_WORDS = "to your own torrent indexers and debrid services, "

        /** The opening while debrid shows (L211). */
        const val OPENING_SHOWN =
            "NZStreamer has no analytics, no ads and no server of its own. What leaves this phone goes to your own Usenet " +
                "providers and indexers, to your own torrent indexers and debrid services, to TMDB with your own key, to " +
                "TheIntroDB while its switch is on, to Google while its Cast software runs, and to GitHub when you use plugins, " +
                "and to the services a plugin you install is set up with."

        /**
         * L255's two sentences (choice 4): what a service keeps, and what the app removes from TorBox; with L421's words for a
         * download's torrent, which stays in the account while it runs.
         */
        const val REMOVED_SENTENCES =
            "Some services keep what you play or download in your account’s list, as their own sites do. From a TorBox account " +
                "the app removes the torrents it added, a couple of minutes after you stop playing, or after a download finishes " +
                "(or the next time the app starts), unless you turn off Remove after playing; it removes nothing else."

        /** L211's part, as drafted (read again before the site's page goes live), with L255's sentences and L421's words. */
        const val DEBRID_PART =
            "The app sends your searches to the torrent indexers you add. It sends your debrid services the info hashes of " +
                "the torrents they find, to ask which they have cached, and for what you play or download asks them for the " +
                "file and a link to it. $REMOVED_SENTENCES Only the hash is sent, never a torrent file, its trackers or which " +
                "indexer found it. It comes with none set up. Their own terms apply."

        /** L421's three phrases in the debrid part: a download asks a service as a play does, and its torrent goes after it. */
        val DOWNLOAD_PHRASES = listOf(
            "for what you play or download asks them for the file",
            "Some services keep what you play or download in your account’s list",
            "a couple of minutes after you stop playing, or after a download finishes (or the next time the app starts)",
        )

        /** Services the app doesn't sign in to, which the policy never names either (W69: Real-Debrid never). */
        val OTHER_SERVICES = listOf("Real-Debrid", "RealDebrid", "AllDebrid", "Debrid-Link", "Offcloud", "put.io", "EasyDebrid")

        /** CsPhoneSettingsCast's Google Cast part (W25 revised, L139), as approved. */
        val CAST_PART = listOf(
            "NZStreamer is Google Cast-enabled. To find and control Chromecasts and TVs with Google Cast, it uses Google’s " +
                "Cast software from Google Play services. That software starts only after you choose “Look for Cast devices” in " +
                "the app, and from then on each time you open the list of devices to play on. While it runs, it keeps records " +
                "on your phone about finding Cast devices and about cast sessions, about your phone (such as its model and " +
                "Android version) and about this app, and sends them to Google LLC, also while the app is closed. They reach " +
                "Google from your internet address. Google says the records hold no identifiers that point to you and are kept " +
                "for a short time. Neither you nor we can stop what it sends while it runs, or have it deleted. You can stop it " +
                "starting again in Settings › Playback › Google Cast; that takes effect the next time the app starts, and " +
                "records already kept on your phone may still be sent. Google’s privacy policy applies: " +
                "policies.google.com/privacy.",
            "While you cast, your phone sends the video to the Cast device over your home network, unencrypted, at a " +
                "one-time address. Other devices on your network can see that address and the title. Once the Cast device asks " +
                "for the video, the phone sends it to no other device. The title and poster go to the Cast device, which " +
                "fetches the poster from TMDB. To choose releases the device can play, the app reads the device’s model from " +
                "your network and keeps what it learns on your phone. Google’s software on the Cast device may report its own " +
                "use to Google. Other phones on your network may show what is playing and offer controls.",
        )

        /**
         * What checkNoDiscover fails the release APK on in any entry, this asset's among them: tools/feature-check's
         * Discover.forbidden (Markers.kt), its classes' packages, its canary, TMDB's list paths and JustWatch's name.
         */
        val DISCOVER_MARKERS = listOf(
            "io/skjaere/usenet/discover/", "io/skjaere/usenet/app/discover/", "nzs.discover.canary",
            "trending/", "discover/movie", "discover/tv", "movie/popular", "movie/top_rated", "movie/now_playing", "movie/upcoming",
            "tv/popular", "tv/top_rated", "tv/airing_today", "tv/on_the_air", "watch/providers", "genre/movie/list", "genre/tv/list",
            "JustWatch",
        )
    }
}
