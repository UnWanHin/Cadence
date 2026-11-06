package com.campus.sport.lan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
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

    /**
     * Reads the first QR code found in an image picked from the gallery.
     *
     * Photos are usually much larger than needed, so bounds are read first and
     * the image is downsampled before decoding to keep memory use bounded.
     */
    fun decodeImage(context: Context, uri: Uri): String? {
        val resolver = context.contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_DECODE_DIMENSION)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        } ?: return null
        return try {
            decodeBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /** Decodes a QR code from an already-loaded bitmap; null when none is found. */
    fun decodeBitmap(bitmap: Bitmap): String? {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return null
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val source = RGBLuminanceSource(width, height, pixels)
        val hints = mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.CHARACTER_SET to "UTF-8",
        )
        // Screenshots in dark mode may carry a white-on-black code; try the
        // inverted image before giving up.
        return decode(BinaryBitmap(HybridBinarizer(source)), hints)
            ?: decode(BinaryBitmap(HybridBinarizer(source.invert())), hints)
    }

    private fun decode(bitmap: BinaryBitmap, hints: Map<DecodeHintType, Any>): String? =
        runCatching { MultiFormatReader().decode(bitmap, hints).text }.getOrNull()

    private fun sampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        while (width / sample > maxDimension || height / sample > maxDimension) {
            sample *= 2
        }
        return sample
    }

    /** Delegates to [RoomAddress] so the parsing rules stay JVM-testable. */
    fun parseRoomAddress(raw: String): String? = RoomAddress.parse(raw)

    private const val MAX_DECODE_DIMENSION = 2048
}
