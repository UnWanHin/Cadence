package com.campus.sport.lan

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream

/**
 * Saves a QR image into the phone's gallery.
 *
 * Android 10 and newer need no permission at all (MediaStore owns the file);
 * older releases write to the public Pictures folder and need a storage
 * permission, which the caller must arrange before calling.
 */
object QrSaver {

    private const val ALBUM = "Cadence"

    /** True when saving needs a runtime permission on this device. */
    fun needsPermission(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    /**
     * Writes [bitmap] as a PNG. Returns a human-readable location, or null when
     * the write failed.
     */
    fun save(context: Context, bitmap: Bitmap, namePrefix: String): String? {
        val fileName = "$namePrefix-${System.currentTimeMillis()}.png"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveWithMediaStore(context, bitmap, fileName)
        } else {
            saveToPublicPictures(context, bitmap, fileName)
        }
    }

    private fun saveWithMediaStore(context: Context, bitmap: Bitmap, fileName: String): String? {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = runCatching {
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return null

        return try {
            resolver.openOutputStream(uri)?.use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) return null
            } ?: return null
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "${Environment.DIRECTORY_PICTURES}/$ALBUM/$fileName"
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun saveToPublicPictures(context: Context, bitmap: Bitmap, fileName: String): String? {
        return try {
            val directory = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                ALBUM,
            )
            if (!directory.exists() && !directory.mkdirs()) return null
            val file = File(directory, fileName)
            FileOutputStream(file).use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) return null
            }
            // Make the gallery notice it without waiting for the next scan.
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), null, null)
            file.absolutePath
        } catch (error: Exception) {
            null
        }
    }
}
