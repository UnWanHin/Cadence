package com.campus.sport.lan

import android.content.Context
import android.net.wifi.WifiManager

/**
 * Finds rooms announced on the local network.
 *
 * Android only delivers broadcast packets to apps holding a multicast lock, so
 * the scan takes one for its duration and releases it afterwards.
 */
object RoomScanner {

    fun scan(context: Context, timeoutMs: Int = 1200): List<RoomDiscovery.Room> {
        val manager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as? WifiManager

        val lock = runCatching {
            manager?.createMulticastLock("cadence-room-scan")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()

        return try {
            RoomDiscovery.scan(RoomDiscovery.DEFAULT_PORT, timeoutMs)
        } finally {
            runCatching { if (lock?.isHeld == true) lock.release() }
        }
    }
}
