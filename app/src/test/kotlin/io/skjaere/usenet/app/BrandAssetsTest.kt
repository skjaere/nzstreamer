package io.skjaere.usenet.app

import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.DataInputStream
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.hypot
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The launcher icon, the TV banner and the splash's mark tools/brand/generate.py makes: an adaptive
 * icon with a monochrome layer for themed icons, PNGs for every density, the TV launcher's banner at
 * its two sizes (Play's TV quality TV-LB), and the mark the splash animates on Android 12+ and shows
 * still before.
 */
class BrandAssetsTest {
    private val res = File("src/main/res")

    private fun parse(file: File): Element {
        assertTrue(file.isFile, "$file is missing")
        return DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file).documentElement
    }

    private fun Element.child(tag: String): Element? = getElementsByTagName(tag).item(0) as Element?

    private fun Element.all(tag: String): List<Element> = getElementsByTagName(tag).let { nodes -> (0 until nodes.length).map { nodes.item(it) as Element } }

    private fun Element.android(name: String): String = getAttributeNS(ANDROID_NS, name)

    /** A path's points: the generator writes absolute moves and lines only, so they are the numbers in pairs. */
    private fun points(name: String, data: String): List<Pair<Double, Double>> {
        assertTrue(Regex("""[MLZ0-9.,\s-]+""").matches(data), "$name: $data")
        return Regex("""-?\d+(\.\d+)?""").findAll(data).map { it.value.toDouble() }.chunked(2).map { (x, y) -> x to y }.toList()
    }

    /** One property's animation in an animated vector: its target, from what to what, and when it ends. */
    private data class Animation(val target: String, val property: String, val from: Double, val to: Double, val endsAt: Long)

    /** A path's polygons, moved by [dx], [dy]: the generator's paths are one or more, each closed. */
    private fun polygons(name: String, data: String, dx: Double = 0.0, dy: Double = 0.0): List<List<Pair<Double, Double>>> =
        Regex("""M([^MZ]+)Z""").findAll(data).map { polygon -> points(name, polygon.groupValues[1]).map { (x, y) -> x + dx to y + dy } }.toList()

    /** A polygon's edges, each from one corner to the next. */
    private fun List<Pair<Double, Double>>.edges() = indices.map { this[it] to this[(it + 1) % size] }

    /** Whether (x, y) is inside the polygon, by the non-zero rule vector drawables fill by. */
    private fun List<Pair<Double, Double>>.covers(x: Double, y: Double): Boolean = edges().sumOf { (a, b) ->
        val side = (b.first - a.first) * (y - a.second) - (x - a.first) * (b.second - a.second)
        when {
            a.second <= y && b.second > y && side > 0 -> 1
            a.second > y && b.second <= y && side < 0 -> -1
            else -> 0
        }
    } != 0

    /** How far (x, y) is from the edge from [a] to [b]. */
    private fun distance(a: Pair<Double, Double>, b: Pair<Double, Double>, x: Double, y: Double): Double {
        val (dx, dy) = b.first - a.first to b.second - a.second
        val along = (((x - a.first) * dx + (y - a.second) * dy) / (dx * dx + dy * dy)).takeIf { it.isFinite() }?.coerceIn(0.0, 1.0) ?: 0.0
        return hypot(x - a.first - along * dx, y - a.second - along * dy)
    }

    /** The splash's animated mark on Android 12+: its vector, and each target's animations. */
    private fun splashMark(): Pair<Element, List<Animation>> {
        val splash = parse(File(res, "drawable-v31/splash_mark.xml"))
        assertEquals("animated-vector", splash.tagName)
        val vector = splash.child("vector") ?: throw AssertionError("the animated vector has no vector")
        val animations = splash.all("target").flatMap { target ->
            target.all("objectAnimator").map {
                assertEquals("floatType", it.android("valueType"), "${target.android("name")} ${it.android("propertyName")}")
                Animation(
                    target.android("name"), it.android("propertyName"), it.android("valueFrom").toDouble(), it.android("valueTo").toDouble(),
                    it.android("startOffset").ifEmpty { "0" }.toLong() + it.android("duration").toLong(),
                )
            }
        }
        return vector to animations
    }

    /** What [element] has for [property] when not animated: the drawable's still state. */
    private fun still(element: Element, property: String): Double =
        element.android(property).ifEmpty { if (property in setOf("fillAlpha", "strokeAlpha", "scaleX", "scaleY")) "1" else "0" }.toDouble()

    private fun Element.named(): Map<String, Element> = (all("group") + all("path")).associateBy { it.android("name") } - ""

    /** A PNG's width and height, from its IHDR chunk. */
    private fun pngSize(file: File): Pair<Int, Int> {
        assertTrue(file.isFile, "$file is missing")
        return DataInputStream(file.inputStream()).use { input ->
            input.skipBytes(16)
            input.readInt() to input.readInt()
        }
    }

    @Test
    fun `the app's icon is its own, round where the launcher asks for one`() {
        val application = parse(File("src/main/AndroidManifest.xml")).child("application")!!
        assertEquals("@mipmap/ic_launcher", application.getAttributeNS(ANDROID_NS, "icon"))
        assertEquals("@mipmap/ic_launcher_round", application.getAttributeNS(ANDROID_NS, "roundIcon"))
    }

    @Test
    fun `the adaptive icons have a background, a foreground and a monochrome layer for themed icons`() {
        listOf("ic_launcher", "ic_launcher_round").forEach { name ->
            val icon = parse(File(res, "mipmap-anydpi-v26/$name.xml"))
            assertEquals("adaptive-icon", icon.tagName)
            listOf("background", "foreground", "monochrome").forEach { layer ->
                val drawable = icon.child(layer)?.getAttributeNS(ANDROID_NS, "drawable").orEmpty()
                assertTrue(drawable.startsWith("@drawable/"), "$name has no $layer layer")
                parse(File(res, "drawable/${drawable.removePrefix("@drawable/")}.xml"))
            }
        }
    }

    /** Launchers mask the 108 dp layers to shapes that keep at least the 66 dp circle in the middle. */
    @Test
    fun `the mark stays inside the safe zone every launcher shape keeps`() {
        listOf("ic_launcher_foreground", "ic_launcher_monochrome").forEach { name ->
            val vector = parse(File(res, "drawable/$name.xml"))
            assertEquals("108", vector.getAttributeNS(ANDROID_NS, "viewportWidth"))
            assertEquals("108", vector.getAttributeNS(ANDROID_NS, "viewportHeight"))
            val paths = vector.all("path")
            assertTrue(paths.isNotEmpty(), "$name draws nothing")
            val far = paths.flatMap { points(name, it.android("pathData")) }.filter { (x, y) -> hypot(x - 54, y - 54) > 33 }
            assertEquals(emptyList(), far, "$name: points outside the 66 dp safe zone")
        }
    }

    @Test
    fun `every density has the icon, xhdpi at least 160 px`() {
        val scale = mapOf("mdpi" to 1.0, "hdpi" to 1.5, "xhdpi" to 2.0, "xxhdpi" to 3.0, "xxxhdpi" to 4.0)
        val xhdpi = pngSize(File(res, "mipmap-xhdpi/ic_launcher.png")).first
        assertTrue(xhdpi >= 160, "xhdpi icon is $xhdpi px")
        scale.forEach { (density, factor) ->
            listOf("ic_launcher", "ic_launcher_round").forEach { name ->
                val size = (xhdpi / 2 * factor).toInt()
                assertEquals(size to size, pngSize(File(res, "mipmap-$density/$name.png")), "$density $name")
            }
        }
    }

    @Test
    fun `the TV banner is 320 x 180 at xhdpi and 640 x 360 at xxxhdpi, 160 x 90 dp at every density`() {
        assertEquals(320 to 180, pngSize(File(res, "drawable-xhdpi/tv_banner.png")))
        assertEquals(640 to 360, pngSize(File(res, "drawable-xxxhdpi/tv_banner.png")))
        assertEquals(160 to 90, pngSize(File(res, "drawable-mdpi/tv_banner.png")))
        assertEquals(240 to 135, pngSize(File(res, "drawable-hdpi/tv_banner.png")))
        assertEquals(480 to 270, pngSize(File(res, "drawable-xxhdpi/tv_banner.png")))
    }

    /**
     * Android 12+ animates the splash's mark: the icon's mark cut into the triangle's front and the
     * three lines of its back end, which run in from the left into it. Every animation acts on a
     * named piece, and ends on the mark as the drawable draws it still, so it is whole wherever the
     * animation doesn't run.
     */
    @Test
    fun `the splash mark runs the mark's three lines in from the left`() {
        val (vector, animations) = splashMark()
        assertEquals("108", vector.android("viewportWidth"))
        assertEquals("108", vector.android("viewportHeight"))
        val named = vector.named()
        assertEquals(emptyList(), animations.map { it.target }.filter { it !in named }, "animations of nothing")
        listOf("line_top", "line_middle", "line_bottom").forEach { line ->
            val runs = animations.filter { it.target == line && it.property == "translateX" }
            assertEquals(1, runs.size, "$line doesn't run in")
            assertTrue(runs.single().from < 0, "$line doesn't start to the left")
            assertTrue(named.getValue(line).all("path").isNotEmpty(), "$line draws nothing")
        }
        animations.forEach { assertEquals(still(named.getValue(it.target), it.property), it.to, "${it.target} ${it.property} doesn't end as it rests") }
    }

    /**
     * As they rest, the splash's pieces cover what the launcher icon's mark covers, and nothing else:
     * the generator draws them from points of their own (the triangle's front, its lines), not from
     * the icon's outline. Compared every quarter unit, leaving out the points on or by an edge, where
     * the path data's rounding to hundredths decides.
     */
    @Test
    fun `the splash mark at rest is the icon's mark`() {
        val (vector, _) = splashMark()
        val pieces = vector.all("path").flatMap { path ->
            val groups = generateSequence(path.parentNode as? Element) { it.parentNode as? Element }.filter { it.tagName == "group" }.toList()
            polygons(path.android("name"), path.android("pathData"), groups.sumOf { still(it, "translateX") }, groups.sumOf { still(it, "translateY") })
        }
        val icon = parse(File(res, "drawable/ic_launcher_foreground.xml")).all("path").flatMap { polygons("ic_launcher_foreground", it.android("pathData")) }
        assertTrue(pieces.isNotEmpty() && icon.isNotEmpty(), "nothing to compare")
        val edges = (pieces + icon).flatMap { it.edges() }
        val steps = (0..108 * 4).map { it / 4.0 }
        val unlike = steps.flatMap { x -> steps.map { y -> x to y } }
            .filter { (x, y) -> edges.none { (a, b) -> distance(a, b, x, y) < 0.05 } }
            .filter { (x, y) -> icon.any { it.covers(x, y) } != pieces.any { it.covers(x, y) } }
        assertEquals(emptyList(), unlike.take(10), "${unlike.size} points where the splash mark at rest and the icon's mark differ")
    }

    /** The theme's windowSplashScreenAnimationDuration is splash_mark_duration; Android takes up to a second. */
    @Test
    fun `the splash mark's animation fits the duration the theme gives it, at most a second`() {
        val (_, animations) = splashMark()
        val declared = parse(File(res, "values/splash_mark.xml")).all("integer").single { it.getAttribute("name") == "splash_mark_duration" }.textContent.trim().toLong()
        assertTrue(declared <= 1_000, "splash_mark_duration is $declared ms")
        assertEquals(declared, animations.maxOf { it.endsAt })
    }

    /**
     * Android 12+ masks the splash's icon to the circle two thirds as wide as its box (72 of the 108
     * units); the mark keeps to the adaptive icon's 66 unit safe zone inside it, wherever the lines
     * are as they run in.
     */
    @Test
    fun `the splash mark stays inside the safe zone as its lines run in`() {
        val (vector, animations) = splashMark()
        val far = vector.all("path").flatMap { path ->
            val name = path.android("name").ifEmpty { "a path" }
            val groups = generateSequence(path.parentNode as? Element) { it.parentNode as? Element }.filter { it.tagName == "group" }.toList()
            // Where each group is still, as the animation starts and as it ends: it runs in a straight line between.
            fun offsets(property: String): List<Double> = groups.map { group ->
                listOf(still(group, property)) + animations.filter { it.target == group.android("name") && it.property == property }.flatMap { listOf(it.from, it.to) }
            }.fold(listOf(0.0)) { sums, values -> sums.flatMap { sum -> values.map { sum + it } } }
            val xs = offsets("translateX")
            val ys = offsets("translateY")
            points(name, path.android("pathData")).flatMap { (x, y) -> xs.flatMap { dx -> ys.map { dy -> Triple(name, x + dx, y + dy) } } }
        }.filter { (_, x, y) -> hypot(x - 54, y - 54) > 33 }
        assertEquals(emptyList(), far, "points outside the 66 unit safe zone")
    }

    /** Before Android 12 the library draws the splash's icon itself, still: the launcher icon's mark. */
    @Test
    fun `before Android 12 the splash shows the icon's mark still`() {
        val still = parse(File(res, "drawable/splash_mark.xml"))
        assertEquals("vector", still.tagName)
        val foreground = parse(File(res, "drawable/ic_launcher_foreground.xml"))
        assertEquals(foreground.all("path").map { it.android("pathData") to it.android("fillColor") }, still.all("path").map { it.android("pathData") to it.android("fillColor") })
        assertEquals("108", still.android("viewportWidth"))
    }

    private companion object {
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
