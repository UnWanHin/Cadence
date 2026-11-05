package com.campus.sport.lan

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * QR helpers. The payload is a plain `host:port` (or a full URL when the page is
 * hosted), so a phone can scan the code shown on another phone or a laptop and
 * join without typing anything.
 */
object QrCode {

    /** Renders [text] as a square QR bitmap of roughly [sizePx] pixels. */
    fun encode(text: String, sizePx: Int = 640): Bitmap? {
        if (text.isBlank()) return null
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
        )
        val matrix = runCatching {
            QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        }.getOrNull() ?: return null

        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                pixels[row + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    /** Delegates to [RoomAddress] so the parsing rules stay JVM-testable. */
    fun parseRoomAddress(raw: String): String? = RoomAddress.parse(raw)
}
