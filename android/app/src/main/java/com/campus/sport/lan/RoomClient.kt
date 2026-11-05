package com.campus.sport.lan

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * Talks to a room hosted by another device (or by the desktop Node service).
 *
 * The wire format is the same one ui/ calls, so a phone and a browser can join
 * the same room in either direction.
 */
class RoomClient(baseUrl: String) {

    private val base = baseUrl.trimEnd('/')

    fun health(): Boolean = runCatching {
        request("GET", "/api/v1/health")
        true
    }.getOrDefault(false)

    /** What the room says about itself: protocol dialect and stored events. */
    data class Health(val protocol: Int, val events: Int)

    fun healthInfo(): Health? {
        val body = runCatching { request("GET", "/api/v1/health") }.getOrNull() ?: return null
        val map = runCatching { RoomJson.readObject(body) }.getOrNull() ?: return null
        val protocol = (WorkoutEvent.num(map["protocol"], 0.0)).toInt()
        val events = (WorkoutEvent.num(map["events"], 0.0)).toInt()
        return Health(protocol, events)
    }

    /** Round-trip time of a health probe, or -1 when the room is unreachable. */
    fun latencyMs(): Long {
        val start = System.nanoTime()
        val reachable = runCatching { request("GET", "/api/v1/health") }.isSuccess
        if (!reachable) return -1
        return (System.nanoTime() - start) / 1_000_000
    }

    fun fetchEvents(): List<WorkoutEvent> {
        val payload = RoomJson.readObject(request("GET", "/api/v1/events"))
        val raw = payload["events"] as? List<*> ?: return emptyList()
        return raw.mapNotNull { item ->
            @Suppress("UNCHECKED_CAST")
            WorkoutEvent.fromMap(item as? Map<String, Any?>)
        }
    }

    /** Pushes local events; returns the room's full event list afterwards. */
    fun pushEvents(events: List<WorkoutEvent>): List<WorkoutEvent> {
        if (events.isEmpty()) return fetchEvents()
        val body = linkedMapOf<String, Any?>("events" to events.map { it.toMap() })
        val payload = RoomJson.readObject(
            request("POST", "/api/v1/events", RoomJson.write(body))
        )
        val raw = payload["events"] as? List<*> ?: return emptyList()
        return raw.mapNotNull { item ->
            @Suppress("UNCHECKED_CAST")
            WorkoutEvent.fromMap(item as? Map<String, Any?>)
        }
    }

    fun fetchChat(): List<ChatMessage> {
        val payload = RoomJson.readObject(request("GET", "/api/v1/chat"))
        val raw = payload["messages"] as? List<*> ?: return emptyList()
        return raw.mapNotNull { item ->
            @Suppress("UNCHECKED_CAST")
            ChatMessage.fromMap(item as? Map<String, Any?>, "remote-${item.hashCode()}")
        }
    }

    fun sendChat(text: String, username: String, profileId: String): ChatMessage? {
        val body = linkedMapOf<String, Any?>(
            "username" to username,
            "profileId" to profileId,
            "text" to text,
            "sentAt" to System.currentTimeMillis(),
        )
        val payload = RoomJson.readObject(request("POST", "/api/v1/chat", RoomJson.write(body)))
        return ChatMessage.fromMap(payload, "sent-${System.currentTimeMillis()}")
    }

    private fun request(method: String, path: String, body: String? = null): String {
        val connection = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 4000
            readTimeout = 6000
            setRequestProperty("Accept", "application/json")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
            }
        }
        try {
            val stream = if (connection.responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val text = stream?.let {
                BufferedReader(InputStreamReader(it, StandardCharsets.UTF_8)).use { reader -> reader.readText() }
            } ?: ""
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode}: $text")
            }
            return text
        } finally {
            connection.disconnect()
        }
    }
}
