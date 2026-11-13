package io.skjaere.usenet.app.settings

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * W60, L112, L138 (CsPhoneSettingsCast 1): the Settings hub's groups and rows, in order. On a phone, Playback ends with TVs
 * and Google Cast; on a TV, Set up with your phone and Phones come first, and never Google Cast, even if asked for (L110).
 * About, with Privacy, on both, its line saying what leaves the device it is on; under it the footnote. Debrid services
 * and Torrent indexers follow Indexers in Sources on both (DbPhoneSources, DbTvSources 1), only while debrid shows (L192).
 */
class SettingsScreenRowsTest {
    @Test
    fun `a phone's hub, with TVs and Google Cast in Playback and About last`() {
        assertEquals(
            listOf(
                HubGroup("Sources", listOf(HubRow.Providers, HubRow.Indexers, HubRow.DebridServices, HubRow.TorrentIndexers, HubRow.Metadata)),
                HubGroup("Playback", listOf(HubRow.Playback, HubRow.Cache, HubRow.ThisDevice, HubRow.Tvs, HubRow.GoogleCast)),
                HubGroup("App", listOf(HubRow.Logs)),
                HubGroup("About", listOf(HubRow.Privacy)),
            ),
            settingsHub(television = false, optional = setOf(HubRow.Tvs, HubRow.GoogleCast, HubRow.DebridServices, HubRow.TorrentIndexers), shown = true),
        )
        assertEquals("What leaves this phone, and to whom", privacyRowLine(television = false))
    }

    @Test
    fun `a TV's hub, with its phone's rows first, About last and no Google Cast`() {
        assertEquals(
            listOf(
                HubGroup(null, listOf(HubRow.PhoneSetup, HubRow.Phones)),
                HubGroup("Sources", listOf(HubRow.Providers, HubRow.Indexers, HubRow.DebridServices, HubRow.TorrentIndexers, HubRow.Metadata)),
                HubGroup("Playback", listOf(HubRow.Playback, HubRow.Cache, HubRow.ThisDevice)),
                HubGroup("App", listOf(HubRow.Logs)),
                HubGroup("About", listOf(HubRow.Privacy)),
            ),
            settingsHub(television = true, optional = setOf(HubRow.PhoneSetup, HubRow.Phones, HubRow.GoogleCast, HubRow.DebridServices, HubRow.TorrentIndexers), shown = true),
        )
        assertEquals("What leaves this TV, and to whom", privacyRowLine(television = true))
    }

    @Test
    fun `a row the caller can't open isn't there`() {
        val rows = settingsHub(television = false, optional = emptySet(), shown = true).flatMap { it.rows }
        assertEquals(listOf(HubRow.Providers, HubRow.Indexers, HubRow.Metadata, HubRow.Playback, HubRow.Cache, HubRow.ThisDevice, HubRow.Logs, HubRow.Privacy), rows)
    }

    @Test
    fun `the rows' titles and the footnote`() {
        assertEquals(
            listOf(
                "Set up with your phone", "Phones", "Usenet providers", "Indexers", "Debrid services", "Torrent indexers", "Metadata", "Quality and languages", "Cache",
                "This device", "TVs",
                "Google Cast", "Logs", "Privacy",
            ),
            HubRow.entries.map { it.title },
        )
        assertEquals("Backup is still to come.", HUB_FOOTNOTE)
    }

    /** L209 (DbTvSignIn 5): Set up with your phone's line names what its page takes, the debrid cards while debrid shows. */
    @Test
    fun `the phone setup row's line names a debrid service and torrent indexers while debrid shows`() {
        assertEquals("Add or change providers, indexers, a debrid service, torrent indexers and the TMDB key from your phone", phoneSetupHubLine(shown = true))
        assertEquals("Add or change providers, indexers and the TMDB key from your phone", phoneSetupHubLine(shown = false))
        assertEquals(PHONE_SETUP_HUB_ROW, phoneSetupHubLine(shown = false), "today's while hidden")
    }

    /** L192: hidden, no Debrid services or Torrent indexers row, on a phone or a TV, whoever asks for them. */
    @Test
    fun `the debrid rows are absent while debrid is hidden`() {
        listOf(false, true).forEach { television ->
            val rows = settingsHub(television, optional = setOf(HubRow.DebridServices, HubRow.TorrentIndexers, HubRow.PhoneSetup), shown = false).flatMap { it.rows }
            assertEquals(false, HubRow.TorrentIndexers in rows, "television $television")
            assertEquals(false, HubRow.DebridServices in rows, "television $television")
            val sources = settingsHub(television, setOf(HubRow.DebridServices, HubRow.TorrentIndexers), shown = false).single { it.heading == "Sources" }
            assertEquals(listOf(HubRow.Providers, HubRow.Indexers, HubRow.Metadata), sources.rows)
        }
    }
}
