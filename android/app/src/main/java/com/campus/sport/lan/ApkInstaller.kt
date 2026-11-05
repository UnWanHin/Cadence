package com.campus.sport.lan

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a release APK and hands it to the system installer.
 *
 * Android will not install silently here: the user sees the normal install
 * prompt, and the first time they must allow this app to install packages.
 */
object ApkInstaller {

    private const val TIMEOUT_MS = 20_000

    /** Directory inside the cache that the FileProvider exposes. */
    fun downloadDir(context: Context): File = File(context.cacheDir, "update").apply { mkdirs() }

    /**
     * Downloads [url] into the cache. [onProgress] receives 0..100, or -1 when
     * the server sends no length.
     */
    fun download(context: Context, url: String, onProgress: (Int) -> Unit = {}): File? {
        val target = File(downloadDir(context), "cadence-update.apk")
        val partial = File(downloadDir(context), "cadence-update.apk.part")

        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", "Cadence-Android")
            }
            if (connection.responseCode != 200) return null
            val total = connection.contentLengthLong

            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } > 0) {
                        output.write(buffer, 0, read)
                        copied += read
                        if (total > 0) onProgress(((copied * 100) / total).toInt())
                    }
                }
            }
            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
            onProgress(100)
            target
        } catch (error: Exception) {
            partial.delete()
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** True when this app may start an install at all. */
    fun canInstall(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return context.packageManager.canRequestPackageInstalls()
    }

    /** Sends the user to the "install unknown apps" screen for this app. */
    fun requestInstallPermission(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /**
     * Opens the system installer for [apk]. Returns false when the intent could
     * not be started, which usually means the permission above is missing.
     */
    fun install(context: Context, apk: File): Boolean {
        if (!apk.isFile || apk.length() == 0L) return false
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        }.getOrNull() ?: return false

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** Removes leftovers once an install has been handed off. */
    fun cleanup(context: Context) {
        runCatching { downloadDir(context).listFiles()?.forEach { it.delete() } }
    }
}
