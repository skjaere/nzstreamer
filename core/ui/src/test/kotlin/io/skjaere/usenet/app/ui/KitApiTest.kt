package io.skjaere.usenet.app.ui

import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * L613, L614: what plugins draw with is a promise, its dump (api/ui.api) the record of it. No type in it is a data class:
 * a data class's copy takes every property, so adding one (a colour, a type style) would change copy's signature, a
 * removal in the dump and a major, where an addition is a minor.
 */
class KitApiTest {
    @Test
    fun `no type in the kit's dump is a data class`() {
        val dump = File("api/ui.api")
        assertTrue(dump.isFile, "the kit's dump is read from the module's api/ui.api")
        var owner = ""
        val dataClasses = dump.readLines().mapNotNull { line ->
            when {
                line.endsWith(" {") -> null.also { owner = line.substringAfter("class ").substringBefore(' ') }
                DATA_MEMBER.containsMatchIn(line) -> owner
                else -> null
            }
        }.distinct()
        assertEquals(emptyList(), dataClasses)
    }

    /** The value holders that were data classes stay equal by what they hold: AppTheme makes its colours anew each time. */
    @Test
    fun `the kit's colours, type scale and focus style are equal by every value they hold`() {
        fun held(type: Class<*>) = type.declaredFields.count { !Modifier.isStatic(it.modifiers) }
        assertEquals(held(AppColors::class.java), AppColors().values.size, "every colour counts")
        assertEquals(held(TypeScale::class.java), TypeScale.Tv.values.size, "every style counts")
        assertEquals(held(FocusStyle::class.java), FocusStyle.Tv.values.size, "every part counts")
        assertEquals(AppColors(), AppColors())
        assertEquals(AppColors().hashCode(), AppColors().hashCode())
        assertNotEquals(AppColors(), AppColors(accent = Color(0xFF00FF00)))
        assertNotEquals(TypeScale.Phone, TypeScale.Tv)
        assertNotEquals(FocusStyle.Phone, FocusStyle.Tv)
    }

    private companion object {
        // A data class's copy (its name mangled where it takes an inline class) and its componentN.
        val DATA_MEMBER = Regex("""^\t.*fun (copy|component\d+)[-$ ]""")
    }
}
