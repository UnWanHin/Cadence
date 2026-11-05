package com.campus.sport.lan

import java.net.HttpURLConnection
import java.net.URL

/**
 * Asks GitHub for the newest release.
 *
 * The redirect on /releases/latest is tried first because the REST API is rate
 * limited to 60 anonymous requests per hour per IP, and a whole campus usually
 * shares one. The API is only a fallback for picking up a non-standard asset
 * name.
 */
object UpdateClient {

    private const val TIMEOUT_MS = 8000

    enum class Outcome { UPDATE, UP_TO_DATE, NO_RELEASE, OFFLINE }

    data class Result(
        val outcome: Outcome,
        val current: String,
        val release: UpdateChecker.Release? = null,
        val message: String = "",
    )

    fun check(currentVersion: String, repo: String = UpdateChecker.DEFAULT_REPO): Result {
        val tag = runCatching { tagFromRedirect(repo) }.getOrNull()
        if (!tag.isNullOrEmpty()) {
            val asset = runCatching { assetFromApi(repo) }.getOrNull()
            val release = asset?.takeIf { it.tag == tag && it.hasApk() }
                ?: UpdateChecker.Release(
                    tag,
                    "",
                    UpdateChecker.assetUrl(repo, tag, UpdateChecker.ASSET_NAME),
                    UpdateChecker.ASSET_NAME,
                    0,
                )
            return decide(currentVersion, release)
        }

        // No redirect: either there is no release yet, or we are offline.
        val fromApi = runCatching { assetFromApi(repo) }.getOrNull()
        if (fromApi != null) return decide(currentVersion, fromApi)
        return Result(Outcome.OFFLINE, currentVersion, null, "无法连接 GitHub")
    }

    private fun decide(current: String, release: UpdateChecker.Release): Result {
        return if (UpdateChecker.isNewer(current, release.tag)) {
            Result(Outcome.UPDATE, current, release)
        } else {
            Result(Outcome.UP_TO_DATE, current, release)
        }
    }

    /** Follows nothing: the Location header itself carries the tag. */
    private fun tagFromRedirect(repo: String): String {
        val connection = (URL("https://github.com/$repo/releases/latest").openConnection()
                as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Cadence-Android")
        }
        try {
            val code = connection.responseCode
            if (code !in 300..399) return ""
            return UpdateChecker.tagFromRedirect(connection.getHeaderField("Location"))
        } finally {
            connection.disconnect()
        }
    }

    /** Optional: only used to pick up an asset whose name differs. */
    private fun assetFromApi(repo: String): UpdateChecker.Release? {
        val connection = (URL("https://api.github.com/repos/$repo/releases/latest").openConnection()
                as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Cadence-Android")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        return try {
            if (connection.responseCode != 200) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            UpdateChecker.parse(body)
        } finally {
            connection.disconnect()
        }
    }
}
