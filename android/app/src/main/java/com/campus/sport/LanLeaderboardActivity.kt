package com.campus.sport

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.campus.sport.lan.LanRoom
import com.campus.sport.lan.Leaderboard
import com.campus.sport.lan.RoomClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LAN leaderboard and room chat.
 *
 * Reads from this device's local event log and, when a room is joined, from the
 * host too. Ranking is recomputed here from events, never taken precomputed.
 */
class LanLeaderboardActivity : AppCompatActivity() {

    private lateinit var boardBox: LinearLayout
    private lateinit var chatBox: LinearLayout
    private lateinit var rangeSpinner: Spinner
    private lateinit var chatInput: EditText
    private lateinit var statusText: TextView
    private lateinit var roomInput: EditText

    private var pollJob: Job? = null
    private var remoteRoom: String? = null
    private val remoteEvents = mutableListOf<com.campus.sport.lan.WorkoutEvent>()
    private val remoteChat = mutableListOf<com.campus.sport.lan.ChatMessage>()
    private val chatDirty = AtomicBoolean(false)
    private val rangeKeys = arrayOf("day", "week", "month", "all")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_leaderboard)

        boardBox = findViewById(R.id.boardBox)
        chatBox = findViewById(R.id.chatBox)
        rangeSpinner = findViewById(R.id.rangeSpinner)
        chatInput = findViewById(R.id.chatInput)
        statusText = findViewById(R.id.roomStatus)
        roomInput = findViewById(R.id.roomAddress)

        rangeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("今日", "本周", "本月", "历史总榜"),
        )
        rangeSpinner.setSelection(1)
        rangeSpinner.onItemSelectedListener = SimpleItemSelected { refreshBoard() }

        findViewById<Button>(R.id.hostButton).setOnClickListener { toggleHosting() }
        findViewById<Button>(R.id.joinButton).setOnClickListener { joinRoom() }
        findViewById<Button>(R.id.chatSend).setOnClickListener { sendChat() }

        LanRoom.ensureLoaded(this)
        roomInput.setText(intent.getStringExtra(EXTRA_ROOM).orEmpty())
        refreshBoard()
        renderChat()
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        startPolling()
        refreshBoard()
    }

    override fun onPause() {
        super.onPause()
        pollJob?.cancel()
        pollJob = null
    }

    private fun toggleHosting() {
        if (LanRoom.isHosting()) {
            LanRoom.stopHosting()
            updateStatus()
            Toast.makeText(this, R.string.room_host_stopped, Toast.LENGTH_SHORT).show()
            return
        }
        runCatching { LanRoom.startHosting(this) }
            .onSuccess {
                updateStatus()
                Toast.makeText(this, getString(R.string.room_host_started, it), Toast.LENGTH_LONG).show()
            }
            .onFailure {
                statusText.text = getString(R.string.room_host_failed, it.message ?: "")
            }
    }

    private fun joinRoom() {
        val address = roomInput.text.toString().trim()
        if (address.isEmpty()) {
            Toast.makeText(this, R.string.room_address_required, Toast.LENGTH_SHORT).show()
            return
        }
        val normalized = if (address.startsWith("http")) address else "http://$address"
        remoteRoom = normalized.trimEnd('/')
        statusText.text = getString(R.string.room_joining, normalized)
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val client = RoomClient(remoteRoom!!)
                    if (!client.health()) return@runCatching false
                    val merged = client.pushEvents(
                        LanRoom.localStore(this@LanLeaderboardActivity).events(),
                    )
                    remoteEvents.clear()
                    remoteEvents.addAll(merged)
                    true
                }.getOrDefault(false)
            }
            statusText.text = if (ok) {
                getString(R.string.room_joined, normalized)
            } else {
                getString(R.string.room_join_failed, normalized)
            }
            refreshBoard()
            renderChat()
        }
    }

    private fun startPolling() {
        if (pollJob != null) return
        pollJob = lifecycleScope.launch {
            while (isActive) {
                pollOnce()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun pollOnce() {
        val room = remoteRoom ?: return
        val snapshot = withContext(Dispatchers.IO) {
            runCatching {
                val client = RoomClient(room)
                val events = client.pushEvents(
                    LanRoom.localStore(this@LanLeaderboardActivity).events(),
                )
                val chat = client.fetchChat()
                events to chat
            }.getOrNull()
        } ?: return

        remoteEvents.clear()
        remoteEvents.addAll(snapshot.first)
        val incoming = snapshot.second
        val known = remoteChat.map { it.messageId }.toSet()
        if (incoming.any { it.messageId !in known }) {
            remoteChat.clear()
            remoteChat.addAll(incoming)
            chatDirty.set(true)
        }
        withContext(Dispatchers.Main) {
            refreshBoard()
            if (chatDirty.compareAndSet(true, false)) renderChat()
        }
    }

    private fun allEvents(): List<com.campus.sport.lan.WorkoutEvent> {
        val local = LanRoom.localStore(this).events()
        if (remoteEvents.isEmpty()) return local
        val seen = local.map { it.eventId }.toHashSet()
        val merged = ArrayList<com.campus.sport.lan.WorkoutEvent>(local.size + remoteEvents.size)
        merged.addAll(local)
        for (event in remoteEvents) if (seen.add(event.eventId)) merged.add(event)
        return merged
    }

    private fun refreshBoard() {
        val range = rangeKeys.getOrElse(rangeSpinner.selectedItemPosition) { "week" }
        val rows: List<Leaderboard.Row> = Leaderboard.rank(allEvents(), range, System.currentTimeMillis())
        boardBox.removeAllViews()
        if (rows.isEmpty()) {
            boardBox.addView(label(getString(R.string.board_empty)))
            return
        }
        rows.forEachIndexed { index, row ->
            boardBox.addView(
                label(
                    getString(
                        R.string.board_row,
                        index + 1,
                        row.username,
                        row.points,
                        row.repetitions,
                        row.durationSeconds,
                    ),
                ),
            )
        }
    }

    private fun renderChat() {
        chatBox.removeAllViews()
        val messages = remoteChat.sortedBy { it.sentAt }
        if (messages.isEmpty()) {
            chatBox.addView(label(getString(R.string.chat_empty)))
            return
        }
        messages.takeLast(100).forEach { message ->
            chatBox.addView(label("${message.username}：${message.text}"))
        }
    }

    private fun sendChat() {
        val text = chatInput.text.toString().trim()
        if (text.isEmpty()) return
        val room = remoteRoom
        if (room == null) {
            Toast.makeText(this, R.string.chat_room_required, Toast.LENGTH_SHORT).show()
            return
        }
        chatInput.setText("")
        lifecycleScope.launch {
            val sent = withContext(Dispatchers.IO) {
                runCatching {
                    RoomClient(room).sendChat(text, LanRoom.username, LanRoom.profileId())
                }.getOrNull()
            }
            if (sent != null) {
                remoteChat.add(sent)
                renderChat()
            } else {
                Toast.makeText(this@LanLeaderboardActivity, R.string.chat_send_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun updateStatus() {
        statusText.text = if (LanRoom.isHosting()) {
            val urls = LanRoom.hostingUrls()
            if (urls.isEmpty()) {
                getString(R.string.room_hosting_no_address, LanRoom.DEFAULT_PORT)
            } else {
                getString(R.string.room_hosting, urls.first())
            }
        } else if (remoteRoom != null) {
            getString(R.string.room_joined, remoteRoom)
        } else {
            getString(R.string.room_idle)
        }
    }

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        setPadding(0, 8, 0, 8)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }

    private class SimpleItemSelected(val onSelected: () -> Unit) : android.widget.AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
            onSelected()
        }

        override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
    }

    companion object {
        const val EXTRA_ROOM = "room"
        private const val POLL_INTERVAL_MS = 2000L
    }
}
