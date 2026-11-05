package com.campus.sport.lan

import android.content.Context
import android.content.Intent
import android.os.Build
import java.io.File
import java.util.UUID

/**
 * The room this device belongs to.
 *
 * Every device keeps its own event log on disk. When a device hosts, it serves
 * that log over the LAN with [RoomHttpServer]; when it joins, it pushes its log
 * to someone else's host. Ranking is always recomputed locally from events, so
 * no device ever trusts a precomputed score.
 *
 * Hosting runs inside [LanRoomService] (a foreground service) so the room keeps
 * answering after the screen locks.
 */
object LanRoom {

    val DEFAULT_PORT: Int = RoomAddress.DEFAULT_PORT

    private const val PREFS = "profile"
    private const val KEY_USERNAME = "username"
    private const val KEY_DEVICE_ID = "device-id"
    private const val DEFAULT_USERNAME = "同学A"
    private const val MAX_USERNAME = 20

    @Volatile
    private var store: RoomStore? = null

    @Volatile
    private var server: RoomHttpServer? = null

    @Volatile
    private var deviceId: String = ""

    @Volatile
    private var cachedUsername: String? = null

    // ---- identity and profile ---------------------------------------------

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ensureLoaded(context: Context): RoomStore {
        store?.let { return it }
        synchronized(this) {
            store?.let { return it }
            val dir = File(context.applicationContext.filesDir, "room")
            dir.mkdirs()

            val saved = prefs(context).getString(KEY_DEVICE_ID, null)?.trim().orEmpty()
            deviceId = if (saved.isNotEmpty()) {
                saved
            } else {
                UUID.randomUUID().toString().also {
                    prefs(context).edit().putString(KEY_DEVICE_ID, it).apply()
                }
            }
            return FileRoomStore(File(dir, "store.json")).also { store = it }
        }
    }

    fun deviceId(context: Context): String {
        ensureLoaded(context)
        return deviceId
    }

    fun localStore(context: Context): RoomStore = ensureLoaded(context)

    fun profileId(context: Context): String = deviceId(context)

    /** The display name shown on the board. */
    fun username(context: Context): String {
        cachedUsername?.let { return it }
        val saved = prefs(context).getString(KEY_USERNAME, null)?.trim().orEmpty()
        val name = if (saved.isEmpty()) DEFAULT_USERNAME else saved.take(MAX_USERNAME)
        cachedUsername = name
        return name
    }

    /** Saves a display name and returns the value actually stored. */
    fun setUsername(context: Context, name: String): String {
        val clean = name.trim().take(MAX_USERNAME).ifEmpty { DEFAULT_USERNAME }
        prefs(context).edit().putString(KEY_USERNAME, clean).apply()
        cachedUsername = clean
        return clean
    }

    // ---- hosting -----------------------------------------------------------

    /** Starts the foreground service that hosts this room. */
    fun startHosting(context: Context, port: Int = DEFAULT_PORT, roomName: String = "") {
        val name = roomName.ifBlank { "${username(context)} 的房间" }
        val intent = Intent(context, LanRoomService::class.java).apply {
            putExtra(LanRoomService.EXTRA_PORT, port)
            putExtra(LanRoomService.EXTRA_ROOM_NAME, name)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun stopHosting(context: Context) {
        context.stopService(Intent(context, LanRoomService::class.java))
        stopServer()
    }

    /** Called by the service; idempotent. Returns the bound port. */
    fun startServer(context: Context, port: Int = DEFAULT_PORT): Int {
        ensureLoaded(context)
        server?.let { if (it.isRunning) return it.port() }
        synchronized(this) {
            server?.let { if (it.isRunning) return it.port() }
            val webRoot = File(context.applicationContext.filesDir, "room-web")
                .takeIf { it.isDirectory }
            val instance = RoomHttpServer(store, webRoot, port)
            // Hand out our own installer so a nearby phone can grab the app
            // without a cable or a store listing.
            runCatching { instance.setApkFile(File(context.applicationInfo.sourceDir)) }
            instance.start()
            server = instance
            return instance.port()
        }
    }

    fun stopServer() {
        synchronized(this) {
            server?.stop()
            server = null
        }
    }

    fun isHosting(): Boolean = server?.isRunning == true

    fun hostingPort(): Int? = server?.takeIf { it.isRunning }?.port()

    /** The URL to show and share: the most reachable LAN address, not loopback. */
    fun bestLanUrl(): String? {
        val port = hostingPort() ?: return null
        val address = RoomHttpServer.lanAddresses().firstOrNull() ?: return null
        return "http://$address:$port/"
    }

    /** URL that downloads this device's installed APK. */
    fun apkUrl(): String? {
        val base = bestLanUrl()?.trimEnd('/') ?: return null
        return base + RoomHttpServer.APK_PATH
    }

    fun hostingUrls(): List<String> {
        val port = hostingPort() ?: return emptyList()
        return RoomHttpServer.lanAddresses().map { "http://$it:$port/" }
    }

    // ---- records -----------------------------------------------------------

    /**
     * Records a finished session into the local log. Demo runs are rejected so
     * they can never enter a shared ranking.
     */
    fun record(
        context: Context,
        exercise: String,
        repetitions: Int,
        durationSeconds: Int,
        quality: Double,
        points: Int,
        occurredAt: Long = System.currentTimeMillis(),
        demo: Boolean = false,
    ): WorkoutEvent? {
        if (demo) return null
        val trimmed = points.coerceIn(0, 10000)
        if (trimmed == 0 && repetitions == 0 && durationSeconds == 0) return null
        val event = WorkoutEvent(
            UUID.randomUUID().toString(),
            profileId(context),
            deviceId(context),
            username(context),
            exercise,
            repetitions,
            durationSeconds,
            quality.coerceIn(0.0, 1.0),
            trimmed,
            occurredAt,
            "model-v1-jni",
            false,
        )
        ensureLoaded(context).addEvents(listOf(event))
        return event
    }

    fun leaderboard(context: Context, range: String): List<Leaderboard.Row> =
        Leaderboard.rank(ensureLoaded(context).events(), range, System.currentTimeMillis())
}
