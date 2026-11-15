package io.skjaere.usenet.app.ui

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two drawn download icons (D7, DlPhoneNotification): Download, an arrow into a tray, and Downloaded, a tick over the
 * tray, as drawn for the app, in 2 dp round strokes on Material's 24 dp grid. The app's (tinted as the core set's are)
 * and the notification's small icons draw the same strokes.
 */
class DownloadIconsTest {
    private val drawables = File("src/main/res/drawable")

    @Test
    fun `the paths are the drawn strokes`() {
        assertEquals("M12 3.5v10.5M7.5 9.5 12 14l4.5-4.5M4 14.5v3.5a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3.5", DOWNLOAD_PATH)
        assertEquals("M4 14.5v3.5a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3.5M7.5 10.2l3.1 3.1 6-6.3", DOWNLOADED_PATH)
    }

    @Test
    fun `both parse, the arrow's head and the tick as lines after their moves`() {
        val download = addPathNodes(DOWNLOAD_PATH)
        val downloaded = addPathNodes(DOWNLOADED_PATH)
        // A move followed by more pairs draws lines to them, as SVG and Android's own parser read it.
        assertEquals(listOf(PathNode.MoveTo(7.5f, 9.5f), PathNode.LineTo(12f, 14f), PathNode.RelativeLineTo(4.5f, -4.5f)), download.subList(2, 5))
        assertEquals(listOf(PathNode.MoveTo(7.5f, 10.2f), PathNode.RelativeLineTo(3.1f, 3.1f), PathNode.RelativeLineTo(6f, -6.3f)), downloaded.takeLast(3))
        // The tray is the same in both.
        assertEquals(download.drop(5), downloaded.dropLast(3))
    }

    @Test
    fun `both stay on the 24 dp grid, a stroke's half width in from its edge`() {
        listOf(DOWNLOAD_PATH, DOWNLOADED_PATH).forEach { path ->
            val far = ends(addPathNodes(path)).filter { (x, y) -> x !in 1f..23f || y !in 1f..23f }
            assertEquals(emptyList(), far, path)
        }
    }

    @Test
    fun `the app's icons are 24 dp on a 24 dp viewport, in 2 dp round strokes that their tint colours, as the core set's`() {
        listOf(DownloadIcon to DOWNLOAD_PATH, DownloadedIcon to DOWNLOADED_PATH).forEach { (icon, path) ->
            assertEquals(24.dp, icon.defaultWidth, icon.name)
            assertEquals(24.dp, icon.defaultHeight, icon.name)
            assertEquals(24f, icon.viewportWidth, icon.name)
            assertEquals(24f, icon.viewportHeight, icon.name)
            assertEquals(BlendMode.SrcIn, icon.tintBlendMode, icon.name)
            val drawn = paths(icon)
            assertEquals(1, drawn.size, icon.name)
            val stroke = drawn.single()
            assertEquals(addPathNodes(path), stroke.pathData, icon.name)
            assertNull(stroke.fill, icon.name)
            assertEquals(SolidColor(Color.Black), stroke.stroke, icon.name)
            assertEquals(2f, stroke.strokeLineWidth, icon.name)
            assertEquals(StrokeCap.Round, stroke.strokeLineCap, icon.name)
            assertEquals(StrokeJoin.Round, stroke.strokeLineJoin, icon.name)
        }
    }

    @Test
    fun `the notification's small icons draw the same strokes, white for Android to tint, on a 24 dp viewport`() {
        listOf("ic_stat_download" to DOWNLOAD_PATH, "ic_stat_downloaded" to DOWNLOADED_PATH).forEach { (name, path) ->
            val file = File(drawables, "$name.xml")
            assertTrue(file.isFile, "$file is missing")
            val vector = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file).documentElement
            assertEquals("vector", vector.tagName, name)
            assertEquals("24dp", vector.android("width"), name)
            assertEquals("24dp", vector.android("height"), name)
            assertEquals("24", vector.android("viewportWidth"), name)
            assertEquals("24", vector.android("viewportHeight"), name)
            val strokes = vector.getElementsByTagName("path").let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }
            assertEquals(1, strokes.size, name)
            val stroke = strokes.single()
            assertEquals(path, stroke.android("pathData"), name)
            assertEquals("2", stroke.android("strokeWidth"), name)
            assertEquals("round", stroke.android("strokeLineCap"), name)
            assertEquals("round", stroke.android("strokeLineJoin"), name)
            assertEquals("#FFFFFFFF", stroke.android("strokeColor"), name)
            assertEquals("#00000000", stroke.android("fillColor"), name)
        }
    }

    private fun Element.android(name: String): String = getAttributeNS(ANDROID_NS, name)

    private fun paths(icon: ImageVector): List<VectorPath> = icon.root.toList().map { it as VectorPath }

    /** Where each of [nodes]' strokes goes, as absolute points: the kinds of node the two paths use. */
    private fun ends(nodes: List<PathNode>): List<Pair<Float, Float>> {
        var x = 0f
        var y = 0f
        return nodes.map { node ->
            when (node) {
                is PathNode.MoveTo -> { x = node.x; y = node.y }
                is PathNode.LineTo -> { x = node.x; y = node.y }
                is PathNode.RelativeLineTo -> { x += node.dx; y += node.dy }
                is PathNode.RelativeVerticalTo -> y += node.dy
                is PathNode.RelativeHorizontalTo -> x += node.dx
                is PathNode.RelativeArcTo -> { x += node.arcStartDx; y += node.arcStartDy }
                else -> throw AssertionError("a node the icons don't use: $node")
            }
            x to y
        }
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
