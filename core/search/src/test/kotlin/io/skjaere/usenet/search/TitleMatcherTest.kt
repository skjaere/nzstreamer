package io.skjaere.usenet.search

import io.skjaere.usenet.releases.ReleaseParser
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TitleMatcherTest {

    private fun matches(release: String, name: String, year: Int? = null, aliases: List<String> = emptyList()) =
        TitleMatcher.matches(ReleaseParser.parse(release), name, year, aliases)

    @Test
    fun `scene spellings match titles with umlauts, sharp s and ligatures`() {
        listOf(
            Triple("Wattwuermer.fuer.Anfaenger.S01E01.German.720p.WEB.x264-GRP", "Wattwürmer für Anfänger", 2006),
            Triple("Wattwurmer.fur.Anfanger.S01E01.720p.WEB.x264-GRP", "Wattwürmer für Anfänger", 2006),
            Triple("Weissenwald.S01E01.German.720p.HDTV.x264-GRP", "Weißenwald", 2010),
            Triple("Das.Moewenmaedchen.2019.German.1080p.WEB.x264-GRP", "Das Möwenmädchen", 2019),
            Triple("Die.Goettinbucht.2021.German.1080p.WEB.x264-GRP", "Die Göttinbucht", 2021),
            Triple("Aeon.Harbor.2005.1080p.BluRay.x264-GRP", "Æon Harbor", 2005),
            Triple("Bolgehavn.2015.1080p.BluRay.x264-GRP", "Bølgehavn", 2015),
            Triple("Thokuvik.2015.1080p.WEB.x264-GRP", "Þokuvík", 2015),
        ).forEach { (release, name, year) -> assertTrue(matches(release, name, year), "$release should match $name") }
    }

    @Test
    fun `release names with umlauts match a scene-spelled title or alias`() {
        assertTrue(matches("Wattwürmer.für.Anfänger.S01E01.German.720p.WEB.x264-GRP", "Wattwuermer fuer Anfaenger", 2006))
        assertTrue(matches("Weißenwald.S01E01.German.720p.HDTV.x264-GRP", "Weissenwald", 2010))
        assertTrue(matches("Die.Göttinbucht.2021.German.1080p.WEB.x264-GRP", "The Goddess Bay", 2021, aliases = listOf("Die Goettinbucht")))
    }

    @Test
    fun `other titles still do not match`() {
        assertFalse(matches("Weisenwald.S01E01.German.720p.HDTV.x264-GRP", "Weißenwald", 2010))
        assertFalse(matches("Leon.Harbor.2005.1080p.BluRay.x264-GRP", "Æon Harbor", 2005))
        assertFalse(matches("Wattwuermer.fuer.Anfaenger.Der.Film.2012.German.1080p.BluRay.x264-GRP", "Wattwürmer für Anfänger", 2012))
    }

    /** L178: a by-name search's torrent with a site's tag before its name is the title it names. */
    @Test
    fun `a torrent's name with a site's tag before it matches its title`() {
        assertTrue(matches("www.Example.org - Quiet Orbit S02E05 1080p WEB H264-GRP", "Quiet Orbit"))
        assertTrue(matches("[ www.example.org ] Quiet.Orbit.2019.1080p.WEB.H264-GRP", "Quiet Orbit", 2019))
    }
}
