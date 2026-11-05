package com.campus.sport

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.campus.sport.lan.ChatMessage
import com.campus.sport.lan.HostElection
import com.campus.sport.lan.LanRoom
import com.campus.sport.lan.Leaderboard
import com.campus.sport.lan.QrCode
import com.campus.sport.lan.RoomClient
import com.campus.sport.lan.RoomScanner
import com.campus.sport.lan.WorkoutEvent
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * LAN leaderboard, room chat, profile and QR join.
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
    private lateinit var usernameInput: EditText
    private lateinit var deviceIdText: TextView

    private var pollJob: Job? = null
    private var remoteRoom: String? = null
    private val remoteEvents = mutableListOf<WorkoutEvent>()
    private val remoteChat = mutableListOf<ChatMessage>()
    private val chatDirty = AtomicBoolean(false)
    private var lastCandidates: List<HostElection.Candidate> = emptyList()
    private var pollFailures = 0
    private var failoverRunning = false
    private val rangeKeys = arrayOf("day", "week", "month", "all")

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result?.contents
        if (contents.isNullOrBlank()) return@registerForActivityResult
        val address = QrCode.parseRoomAddress(contents)
        if (address == null) {
            Toast.makeText(this, R.string.room_scan_invalid, Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        roomInput.setText(address)
        joinRoom()
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_leaderboard)

        boardBox = findViewById(R.id.boardBox)
        chatBox = findViewById(R.id.chatBox)
        rangeSpinner = findViewById(R.id.rangeSpinner)
        chatInput = findViewById(R.id.chatInput)
        statusText = findViewById(R.id.roomStatus)
        roomInput = findViewById(R.id.roomAddress)
        usernameInput = findViewById(R.id.usernameInput)
        deviceIdText = findViewById(R.id.myDeviceId)

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
        findViewById<Button>(R.id.scanButton).setOnClickListener { startScan() }
        findViewById<Button>(R.id.discoverButton).setOnClickListener { discoverRooms() }
        findViewById<Button>(R.id.showQrButton).setOnClickListener { showMyQr() }
        findViewById<Button>(R.id.usernameSave).setOnClickListener { saveUsername() }

        LanRoom.ensureLoaded(this)
        usernameInput.setText(LanRoom.username(this))
        deviceIdText.text = getString(R.string.profile_my_id, LanRoom.deviceId(this))

        roomInput.setText(intent.getStringExtra(EXTRA_ROOM).orEmpty())
        refreshBoard()
        renderChat()
        updateStatus()
    }

    override fun onResume() {
        super.onResume()
        startPolling()
        refreshBoard()
        updateStatus()
    }

    override fun onPause() {
        super.onPause()
        pollJob?.cancel()
        pollJob = null
    }

    // ---- profile -----------------------------------------------------------

    private fun saveUsername() {
        val saved = LanRoom.setUsername(this, usernameInput.text.toString())
        usernameInput.setText(saved)
        Toast.makeText(this, getString(R.string.profile_saved, saved), Toast.LENGTH_SHORT).show()
        refreshBoard()
    }

    // ---- hosting and joining ------------------------------------------------

    private fun toggleHosting() {
        if (LanRoom.isHosting()) {
            LanRoom.stopHosting(this)
            // The room we were polling was our own; drop it so polling stops
            // dialling a server that no longer exists.
            if (remoteRoom?.startsWith("http://127.0.0.1") == true) {
                remoteRoom = null
                remoteEvents.clear()
                remoteChat.clear()
                roomInput.setText("")
            }
            updateStatus()
            refreshBoard()
            renderChat()
            Toast.makeText(this, R.string.room_host_stopped, Toast.LENGTH_SHORT).show()
            return
        }
        requestNotificationPermissionIfNeeded()
        runCatching { LanRoom.startHosting(this) }
        lifecycleScope.launch {
            // The service starts asynchronously; give it a moment before reporting.
            delay(700)
            if (LanRoom.isHosting()) {
                // Hosting is authoritative for this device: join it right away so
                // the board and chat work without a second manual step.
                val port = LanRoom.hostingPort() ?: LanRoom.DEFAULT_PORT
                val selfAddress = "127.0.0.1:$port"
                roomInput.setText(selfAddress)
                joinRoom()
                updateStatus()
                val url = LanRoom.hostingUrls().firstOrNull()
                Toast.makeText(
                    this@LanLeaderboardActivity,
                    if (url != null) getString(R.string.room_host_started_url, url)
                    else getString(R.string.room_host_started, LanRoom.DEFAULT_PORT),
                    Toast.LENGTH_LONG,
                ).show()
            } else {
                statusText.text = getString(R.string.room_host_failed, "")
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun joinRoom() {
        val address = roomInput.text.toString().trim()
        if (address.isEmpty()) {
            Toast.makeText(this, R.string.room_address_required, Toast.LENGTH_SHORT).show()
            return
        }
        val normalized = if (address.startsWith("http")) address else "http://$address"
        val room = normalized.trimEnd('/')
        remoteRoom = room
        pollFailures = 0
        statusText.text = getString(R.string.room_joining, room)
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val client = RoomClient(room)
                    if (!client.health()) return@runCatching false
                    val merged = client.pushEvents(LanRoom.localStore(this@LanLeaderboardActivity).events())
                    remoteEvents.clear()
                    remoteEvents.addAll(merged)
                    true
                }.getOrDefault(false)
            }
            statusText.text = if (ok) getString(R.string.room_joined, room)
            else getString(R.string.room_join_failed, room)
            refreshBoard()
            renderChat()
        }
    }

    // ---- discovery ---------------------------------------------------------

    /** Asks the network for rooms, measures them, and lets the user pick one. */
    private fun discoverRooms() {
        Toast.makeText(this, R.string.room_searching, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val candidates = collectCandidates()
            if (candidates.isEmpty()) {
                Toast.makeText(this@LanLeaderboardActivity, R.string.room_search_empty, Toast.LENGTH_LONG).show()
                return@launch
            }
            lastCandidates = candidates
            val labels = candidates.map { describe(it) }.toTypedArray()
            MaterialAlertDialogBuilder(this@LanLeaderboardActivity)
                .setTitle(getString(R.string.room_search_result, candidates.size))
                .setItems(labels) { _, which ->
                    val picked = candidates[which]
                    roomInput.setText(picked.address)
                    joinRoom()
                }
                .setPositiveButton(R.string.room_pick_fastest) { _, _ -> electAndJoin(HostElection.MODE_DELAY) }
                .setNeutralButton(R.string.room_pick_random) { _, _ -> electAndJoin(HostElection.MODE_RANDOM) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    private fun describe(candidate: HostElection.Candidate): String {
        val label = if (candidate.name.isEmpty()) candidate.address else candidate.name
        val latency = if (candidate.rttMs >= 0) " · ${candidate.rttMs}ms" else " · 无响应"
        return label + latency + "  " + candidate.address
    }

    /** Discovers rooms and measures each one on a background thread. */
    private suspend fun collectCandidates(): List<HostElection.Candidate> = withContext(Dispatchers.IO) {
        RoomScanner.scan(this@LanLeaderboardActivity).map { room ->
            val host = room.address.substringBefore(':')
            val port = room.address.substringAfter(':', "").toIntOrNull() ?: LanRoom.DEFAULT_PORT
            val latency = runCatching {
                RoomClient("http://$host:$port").latencyMs()
            }.getOrDefault(-1L)
            HostElection.Candidate(room.address, room.address, room.name, latency)
        }.sortedWith(compareBy({ it.rttMs < 0 }, { it.rttMs }, { it.address }))
    }

    /** Picks a host by the chosen rule and joins it. */
    private fun electAndJoin(mode: String) {
        val picked = HostElection.elect(lastCandidates, mode, System.currentTimeMillis())
        if (picked == null) {
            Toast.makeText(this, R.string.room_search_empty, Toast.LENGTH_LONG).show()
            return
        }
        val why = if (mode == HostElection.MODE_RANDOM) {
            getString(R.string.room_elected_random)
        } else {
            getString(R.string.room_elected_fastest)
        }
        roomInput.setText(picked.address)
        statusText.text = why + " " + picked.address
        joinRoom()
    }

    // ---- QR ----------------------------------------------------------------

    private fun startScan() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt(getString(R.string.room_scan_prompt))
            setBeepEnabled(false)
            setOrientationLocked(false)
        }
        runCatching { scanLauncher.launch(options) }
            .onFailure { Toast.makeText(this, getString(R.string.room_scan_failed, it.message ?: ""), Toast.LENGTH_LONG).show() }
    }

    private fun showMyQr() {
        val payload = shareableAddress()
        if (payload == null) {
            Toast.makeText(this, R.string.room_qr_empty, Toast.LENGTH_LONG).show()
            return
        }
        val bitmap = QrCode.encode(payload, 720)
        if (bitmap == null) {
            Toast.makeText(this, R.string.room_qr_unavailable, Toast.LENGTH_SHORT).show()
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 24)
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }
        container.addView(
            ImageView(this).apply {
                setImageBitmap(bitmap)
                adjustViewBounds = true
                layoutParams = LinearLayout.LayoutParams(dp(240), dp(240))
            },
        )
        container.addView(
            TextView(this).apply {
                text = payload
                setPadding(0, dp(16), 0, dp(4))
                textSize = 15f
                gravity = android.view.Gravity.CENTER
            },
        )
        container.addView(
            TextView(this).apply {
                text = getString(R.string.room_qr_hint)
                textSize = 12f
                gravity = android.view.Gravity.CENTER
            },
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.room_qr_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /** What another device should scan to reach this room. */
    private fun shareableAddress(): String? {
        LanRoom.hostingUrls().firstOrNull()?.let { return it }
        return remoteRoom
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * Re-runs discovery and moves the room to the next best host.
     *
     * Events live on every device, so the new host is filled by the pushes that
     * follow; nothing is lost when the old host disappears.
     */
    private fun maybeFailover() {
        if (failoverRunning) return
        val dead = remoteRoom ?: return
        failoverRunning = true
        lifecycleScope.launch {
            val candidates = runCatching { collectCandidates() }.getOrDefault(emptyList())
                .filter { "http://${'$'}{it.address}" != dead }
            val picked = HostElection.elect(candidates, HostElection.MODE_DELAY, System.currentTimeMillis())
            if (picked != null) {
                roomInput.setText(picked.address)
                statusText.text = getString(R.string.room_failover, picked.address)
                joinRoom()
            } else {
                statusText.text = getString(R.string.room_failover_none)
            }
            failoverRunning = false
        }
    }

    // ---- polling -----------------------------------------------------------

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
        }
        if (snapshot == null) {
            pollFailures += 1
            // The host went away (left, locked, crashed). Look for another one
            // instead of spinning against a dead address forever.
            if (pollFailures >= FAILURES_BEFORE_FAILOVER && !LanRoom.isHosting()) {
                maybeFailover()
            }
            return
        }
        pollFailures = 0

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

    // ---- rendering ---------------------------------------------------------

    private fun allEvents(): List<WorkoutEvent> {
        val local = LanRoom.localStore(this).events()
        if (remoteEvents.isEmpty()) return local
        val seen = local.map { it.eventId }.toHashSet()
        val merged = ArrayList<WorkoutEvent>(local.size + remoteEvents.size)
        merged.addAll(local)
        for (event in remoteEvents) if (seen.add(event.eventId)) merged.add(event)
        return merged
    }

    private fun refreshBoard() {
        val range = rangeKeys.getOrElse(rangeSpinner.selectedItemPosition) { "week" }
        val rows: List<Leaderboard.Row> = Leaderboard.rank(allEvents(), range, System.currentTimeMillis())
        // Renaming is display-only; show the current name for this device right
        // away instead of waiting for the next event. Peers pick it up from the
        // username carried by future events.
        val me = LanRoom.profileId(this)
        val myName = LanRoom.username(this)
        rows.firstOrNull { it.profileId == me }?.username = myName
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
                    RoomClient(room).sendChat(text, LanRoom.username(this@LanLeaderboardActivity), LanRoom.profileId(this@LanLeaderboardActivity))
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
        statusText.text = when {
            LanRoom.isHosting() -> {
                val url = LanRoom.hostingUrls().firstOrNull()
                if (url != null) getString(R.string.room_hosting, url)
                else getString(R.string.room_hosting_no_address, LanRoom.DEFAULT_PORT)
            }
            remoteRoom != null -> getString(R.string.room_joined, remoteRoom)
            else -> getString(R.string.room_idle)
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
        private const val FAILURES_BEFORE_FAILOVER = 3
    }
}
