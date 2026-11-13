package io.skjaere.usenet.app.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The type sizes by form factor: the TV's one step under the TV design's type table. */
class TypeScaleTest {
    private fun TypeScale.roles(): Map<String, TextStyle> = mapOf(
        "hero" to hero, "screenTitle" to screenTitle, "cardTitle" to cardTitle, "section" to section, "body" to body,
        "bodyStrong" to bodyStrong, "secondary" to secondary, "small" to small, "release" to release, "tag" to tag,
        "address" to address,
    )

    private fun assertSize(style: TextStyle, size: TextUnit, lineHeight: TextUnit, role: String) {
        assertEquals(size, style.fontSize, "$role's size")
        assertEquals(lineHeight, style.lineHeight, "$role's line height")
    }

    @Test
    fun `a TV's sizes are a step under the design's`() {
        val tv = TypeScale.Tv
        assertSize(tv.hero, 26.sp, 32.sp, "hero")
        assertSize(tv.screenTitle, 22.sp, 28.sp, "screenTitle")
        assertSize(tv.cardTitle, 17.sp, 22.sp, "cardTitle")
        assertSize(tv.section, 13.sp, 18.sp, "section")
        assertSize(tv.body, 15.sp, 20.sp, "body")
        assertSize(tv.bodyStrong, 15.sp, 20.sp, "bodyStrong")
        assertSize(tv.secondary, 13.sp, 19.sp, "secondary")
        assertSize(tv.small, 12.sp, 17.sp, "small")
        assertSize(tv.release, 12.sp, 17.sp, "release")
        assertSize(tv.tag, 12.sp, 17.sp, "tag")
        assertSize(tv.address, 17.sp, 24.sp, "address")
    }

    /** The phone setup's address, typed off the TV by hand: in the release names' monospace, to tell 0 from o at 3 m. */
    @Test
    fun `the address is monospaced and larger than the release names`() {
        assertEquals(PlexMono, TypeScale.Tv.address.fontFamily)
        assertEquals(PlexMono, TypeScale.Phone.address.fontFamily)
        assertTrue(TypeScale.Tv.address.fontSize.value > TypeScale.Tv.release.fontSize.value)
    }

    /** Each role keeps its font, weight and tracking on a TV: only the sizes change. */
    @Test
    fun `a TV's roles are the phone's, no smaller`() {
        val phone = TypeScale.Phone.roles()
        TypeScale.Tv.roles().forEach { (role, tv) ->
            val p = phone.getValue(role)
            assertEquals(p.fontFamily, tv.fontFamily, "$role's font")
            assertEquals(p.fontWeight, tv.fontWeight, "$role's weight")
            assertEquals(p.letterSpacing, tv.letterSpacing, "$role's tracking")
            assertTrue(tv.fontSize.value >= p.fontSize.value, "$role is smaller on a TV: ${tv.fontSize} against ${p.fontSize}")
        }
    }

    @Test
    fun `nothing on a TV is under 12 sp`() {
        TypeScale.Tv.roles().forEach { (role, style) -> assertTrue(style.fontSize.value >= 12f, "$role is ${style.fontSize}") }
    }

    /** The phone's sizes stay as they were; hero is the movie page's title, tag the small capitals of NEW and the collection's pills. */
    @Test
    fun `a phone's sizes are unchanged`() {
        val phone = TypeScale.Phone
        assertSize(phone.hero, 24.sp, 28.sp, "hero")
        assertEquals(20.sp, phone.screenTitle.fontSize)
        assertEquals(16.sp, phone.cardTitle.fontSize)
        assertEquals(13.sp, phone.section.fontSize)
        assertEquals(0.8.sp, phone.section.letterSpacing)
        assertEquals(15.sp, phone.body.fontSize)
        assertEquals(15.sp, phone.bodyStrong.fontSize)
        assertSize(phone.secondary, 13.sp, 19.sp, "secondary")
        assertSize(phone.small, 12.sp, 17.sp, "small")
        assertSize(phone.release, 11.5.sp, 16.sp, "release")
        assertSize(phone.tag, 11.sp, 17.sp, "tag")
        assertSize(phone.address, 15.sp, 21.sp, "address")
        assertEquals(FontWeight.SemiBold, phone.tag.fontWeight)
    }

    /**
     * Text sizes come from the type scale, so a TV's are its own: a size written into a screen under 12 sp would show
     * as it is on a TV too.
     */
    @Test
    fun `no screen sets a size under 12 sp of its own`() {
        val sources = File("src/main/kotlin").walkTopDown().filter { it.extension == "kt" && it.name != "TvTokens.kt" }.toList()
        assertTrue(sources.size > 50, "the app's sources weren't found from ${File(".").absolutePath}")
        val size = Regex("""fontSize\s*=\s*([0-9.]+)\.sp""")
        val small = sources.flatMap { file ->
            file.readLines().mapIndexedNotNull { i, line ->
                size.findAll(line).firstOrNull { it.groupValues[1].toFloat() < 12f }?.let { "${file.name}:${i + 1}: ${line.trim()}" }
            }
        }
        assertEquals(emptyList(), small)
    }
}
