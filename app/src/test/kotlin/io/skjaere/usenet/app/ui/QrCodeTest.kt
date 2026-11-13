package io.skjaere.usenet.app.ui

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.BitMatrix
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The phone setup's QR code: what a phone's camera reads is the address, the longest address there can be fits a small
 * version (big modules, read from the sofa), and a module is at least 5 dp at the TV's size.
 */
class QrCodeTest {
    /** The longest address a setup page can have: the longest IPv4 and port, and the code's 26 characters. */
    private val longest = "http://255.255.255.255:65535/" + "z".repeat(26)

    /** [modules] as a picture [scale] pixels a module, dark on light, as the screen draws it, read by ZXing's reader. */
    private fun read(modules: BitMatrix, scale: Int = 4): String {
        val side = modules.width * scale
        val pixels = IntArray(side * side) { i ->
            if (modules[(i % side) / scale, (i / side) / scale]) 0xFF0E0F12.toInt() else 0xFFF3F1EC.toInt()
        }
        val bitmap = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels)))
        return QRCodeReader().decode(bitmap, mapOf(DecodeHintType.PURE_BARCODE to false)).text
    }

    @Test
    fun `the code reads back as the address`() {
        listOf("http://192.168.1.40:41234/k7m2q9xd0a1b2c3d4e5f6g7h8j", longest).forEach {
            assertEquals(it, read(qrModules(it)))
        }
    }

    @Test
    fun `the longest address fits version 4, at error correction M`() {
        val code = Encoder.encode(longest, ErrorCorrectionLevel.M, mapOf(EncodeHintType.MARGIN to QR_QUIET_ZONE))
        assertEquals(ErrorCorrectionLevel.M, code.ecLevel)
        assertTrue(code.version.versionNumber <= 4, "version ${code.version.versionNumber}")
    }

    /** Version 4's 33 modules and the quiet zone's 4 a side: 41 across. */
    @Test
    fun `the quiet zone is inside the code's modules`() {
        val modules = qrModules(longest)
        assertEquals(33 + 2 * QR_QUIET_ZONE, modules.width)
        assertEquals(false, (0 until modules.width).any { modules[it, 0] || modules[0, it] || modules[it, modules.width - 1] || modules[modules.width - 1, it] })
    }

    @Test
    fun `a module is at least 5 dp at the TV's size`() {
        val perModule = TvDimens.qr.value / qrModules(longest).width
        assertTrue(perModule >= 5f, "$perModule dp a module")
    }

    /** ZXing is Apache-2.0: its licence and its NOTICE ship beside the fonts' licences. */
    @Test
    fun `ZXing's licence and notice ship with the app`() {
        val licence = File("src/main/assets/licenses/zxing-LICENSE.txt")
        val notice = File("src/main/assets/licenses/zxing-NOTICE.txt")
        assertTrue(licence.isFile && "Apache License" in licence.readText() && "Version 2.0" in licence.readText(), "no ZXing licence at ${licence.absolutePath}")
        // Its NOTICE is Barcode4J's and JCommander's, which ZXing passes on.
        assertTrue(notice.isFile && "Barcode4J" in notice.readText(), "no ZXing NOTICE at ${notice.absolutePath}")
    }
}
