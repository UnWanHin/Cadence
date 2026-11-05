package com.campus.sport.lan

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * The room this device belongs to.
 *
 * Every device keeps its own event log on disk. When a device hosts, it serves
 * that log over the LAN with [RoomHttpServer]; when it joins, it pushes its log
 * to someone else's host. Ranking is always recomputed locally from events, so
 * no device ever trusts a precomputed score.
 */
object LanRoom {

    const val DEFAULT_PORT = 8787

    @Volatile
    private var store: RoomStore? = null

    @Volatile
    private var server: RoomHttpServer? = null

    @Volatile
    private var deviceId: String = ""

    var username: String = "同学A"

    fun ensureLoaded(context: Context): RoomStore {
        store?.let { return it }
        synchronized(this) {
            store?.let { return it }
            val dir = File(context.filesDir, "room")
            dir.mkdirs()
            val identityFile = File(dir, "device-id")
            deviceId = if (identityFile.isFile) {
                identityFile.readText().trim()
            } else {
                UUID.randomUUID().toString().also { identityFile.writeText(it) }
            }
            return FileRoomStore(File(dir, "store.json")).also { store = it }
        }
    }

    fun localStore(context: Context): RoomStore = ensureLoaded(context)

    fun profileId(): String = deviceId

    /** Starts serving this device's room. Returns the port actually bound. */
    fun startHosting(context: Context, port: Int = DEFAULT_PORT): Int {
        ensureLoaded(context)
        server?.let { if (it.isRunning) return it.port() }
        synchronized(this) {
            server?.let { if (it.isRunning) return it.port() }
            val webRoot = File(context.filesDir, "room-web").takeIf { it.isDirectory }
            val instance = RoomHttpServer(store, webRoot, port)
            instance.start()
            server = instance
            return instance.port()
        }
    }

    fun stopHosting() {
        synchronized(this) {
            server?.stop()
            server = null
        }
    }

    fun isHosting(): Boolean = server?.isRunning == true

    fun hostingUrls(): List<String> =
        if (isHosting()) RoomHttpServer.lanAddresses().map { "http://$it:${server?.port()}/" } else emptyList()

    /**
     * Records a finished session into the local log. Demo runs are stored but
     * flagged so the ranking ignores them.
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
            profileId(),
            deviceId,
            username,
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
