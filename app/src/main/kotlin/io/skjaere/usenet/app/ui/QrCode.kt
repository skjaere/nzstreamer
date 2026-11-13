package io.skjaere.usenet.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlin.math.floor

/** The light modules round a QR code that readers look for, a side. */
const val QR_QUIET_ZONE = 4

/**
 * [text]'s QR code, one bit a module, the quiet zone included: error correction M (an address of 55 characters fits
 * version 4, whose modules are big enough to read across a room). No character set is named: that would add an ECI,
 * which some readers get wrong, and an address is ASCII.
 */
internal fun qrModules(text: String): BitMatrix = QRCodeWriter().encode(
    text, BarcodeFormat.QR_CODE, 0, 0,
    mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to QR_QUIET_ZONE),
)

/**
 * [text] as a QR code [size] square: dark modules on a light tile, never the other way round (some readers can't read
 * light on dark), each a whole number of pixels so none blurs, the quiet zone inside the tile. TalkBack hears only
 * [description]: the address beside it is what it reads out.
 */
@Composable
fun QrCode(text: String, description: String, modifier: Modifier = Modifier, size: Dp = TvDimens.qr) {
    val modules = remember(text) { qrModules(text) }
    val dark = colors.background
    val light = colors.text
    Canvas(modifier.size(size).clearAndSetSemantics { contentDescription = description }) {
        drawRect(light)
        val count = modules.width
        val module = floor(this.size.minDimension / count).coerceAtLeast(1f)
        val left = floor((this.size.width - module * count) / 2)
        val top = floor((this.size.height - module * count) / 2)
        for (y in 0 until count) {
            // A row's dark modules side by side, as one rectangle each run.
            var x = 0
            while (x < count) {
                if (!modules[x, y]) {
                    x++
                    continue
                }
                val start = x
                while (x < count && modules[x, y]) x++
                drawRect(dark, Offset(left + start * module, top + y * module), Size((x - start) * module, module))
            }
        }
    }
}
