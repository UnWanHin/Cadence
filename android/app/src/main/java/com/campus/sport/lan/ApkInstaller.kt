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
     * Downloads [url] into the cache.
     *
     * [onProgress] receives the bytes written and the announced total (0 when
     * the server sent no length). [isCancelled] is polled between chunks so the
     * caller can abort without waiting for the whole file.
     *
     * Returns null on any failure, including a cancelled or truncated download;
     * a partial file is never left behind for the installer to open.
     */
    fun download(
        context: Context,
        url: String,
        isCancelled: () -> Boolean = { false },
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): File? {
        val target = File(downloadDir(context), "cadence-update.apk")
        val partial = File(downloadDir(context), "cadence-update.apk.part")

        var connection: HttpURLConnection? = null
        return try {
            connection = openFollowingRedirects(url) ?: return null
            if (connection.responseCode != 200) return null
            val total = connection.contentLengthLong

            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } > 0) {
                        if (isCancelled()) {
                            partial.delete()
                            return null
                        }
                        output.write(buffer, 0, read)
                        copied += read
                        onProgress(copied, total)
                    }
                    // A short read means the connection dropped mid-file; an APK
                    // cut in half installs as "解析软件包出现问题" with no clue why.
                    if (total > 0 && copied != total) {
                        partial.delete()
                        return null
                    }
                }
            }
            if (!partial.renameTo(target)) {
                partial.copyTo(target, overwrite = true)
                partial.delete()
            }
            target
        } catch (error: Exception) {
            partial.delete()
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Opens [url], following 3xx hops itself.
     *
     * The release download is a redirect to another host, and the redirect
     * response reports a content length of zero. Following it by hand is what
     * makes the real length available for a determinate progress bar.
     */
    private fun openFollowingRedirects(url: String, hops: Int = 5): HttpURLConnection? {
        var current = url
        var remaining = hops
        while (remaining-- > 0) {
            val connection = runCatching {
                (URL(current).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    setRequestProperty("User-Agent", "Cadence-Android")
                }
            }.getOrNull() ?: return null

            val code = runCatching { connection.responseCode }.getOrNull() ?: return null
            val location = connection.getHeaderField("Location")
            if (code in 300..399 && !location.isNullOrBlank()) {
                connection.disconnect()
                current = runCatching { URL(URL(current), location).toString() }.getOrNull() ?: return null
                continue
            }
            return connection
        }
        return null
    }

    /** True when this app may start an install at all. */
    fun canInstall(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true
        return context.packageManager.canRequestPackageInstalls()
    }

    /**
     * Intent for the "install unknown apps" screen for this app, or null when
     * the platform has no such switch.
     *
     * Returned rather than launched so the caller can start it through an
     * ActivityResultLauncher and resume the download on the way back.
     */
    fun installPermissionIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        )
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
