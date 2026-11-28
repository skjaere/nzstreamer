package app.nzstreamer.plugin.sportarr.bouts

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * L-B8's options.json: on by default, so a missing file is on; a file that is there but torn, unreadable or of another
 * format is off, failing closed; written whole through options.json.tmp.
 */
class CardsOptionTest {
    @TempDir
    lateinit var dir: File

    private val folder get() = File(dir, "plugin")
    private val option get() = CardsOption(folder, EmptyCoroutineContext)
    private val file get() = File(folder, CardsOption.NAME)

    @Test
    fun `a missing file, its folder too, reads as on`() = runBlocking<Unit> {
        assertTrue(option.read())
        assertFalse(folder.exists(), "reading writes nothing")
    }

    @Test
    fun `a torn, unreadable or unknown file reads as off`() = runBlocking<Unit> {
        folder.mkdirs()
        listOf("", "{", """{"format":1,"fightCards":tr""", """{"format":2,"fightCards":true}""", """{"format":1}""", """{"format":1,"fightCards":"yes"}""", "[]")
            .forEach { written ->
                file.writeText(written)
                assertFalse(option.read(), written)
            }
        file.delete()
        file.mkdirs()
        assertFalse(option.read(), "a folder where the file would be")
    }

    @Test
    fun `the switch written reads back, whole through its temporary file`() = runBlocking<Unit> {
        assertTrue(option.write(false))
        assertFalse(option.read())
        assertEquals("""{"format":1,"fightCards":false}""", file.readText())
        assertTrue(option.write(true))
        assertTrue(option.read())
        assertEquals(listOf(CardsOption.NAME), folder.list()!!.toList())
        file.writeText("""{"format":1,"fightCards":true,"later":"kept by a newer version"}""")
        assertTrue(option.read())
    }
}
