package com.campus.sport

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
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
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.campus.sport.lan.AppShareCode
import com.campus.sport.lan.ChatMessage
import com.campus.sport.lan.ChatNames
import com.campus.sport.lan.HostElection
import com.campus.sport.lan.LanRoom
import com.campus.sport.lan.Leaderboard
import com.campus.sport.lan.QrCode
import com.campus.sport.lan.Protocol
import com.campus.sport.lan.QrSaver
import com.campus.sport.lan.RoomClient
import com.campus.sport.lan.ApkInstaller
import com.campus.sport.lan.RoomScanner
import com.campus.sport.lan.UpdateChecker
import com.campus.sport.lan.UpdatePolicy
import com.campus.sport.lan.UpdateClient
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

    private var pollJob: Job? = null
    private var remoteRoom: String? = null
    private val remoteEvents = mutableListOf<WorkoutEvent>()
    private val remoteChat = mutableListOf<ChatMessage>()
    private val chatDirty = AtomicBoolean(false)
    private var lastCandidates: List<HostElection.Candidate> = emptyList()
    private var pollFailures = 0
    private var failoverRunning = false
    private var remoteProtocol = 0
    private val rangeKeys = arrayOf("day", "week", "month", "all")

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        handleScannedContents(result?.contents)
    }

    private val albumScanLauncher =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) decodeAlbumQr(uri)
        }

    private fun handleScannedContents(contents: String?) {
        if (contents.isNullOrBlank()) return
        if (AppShareCode.isShareCode(contents)) {
            onAppShareScanned(contents)
            return
        }
        val address = QrCode.parseRoomAddress(contents)
        if (address == null) {
            Toast.makeText(this, R.string.room_scan_invalid, Toast.LENGTH_LONG).show()
            return
        }
        roomInput.setText(address)
        joinRoom()
    }

    /** A scanned "install this app" code: offer the download and the room in one place. */
    private fun onAppShareScanned(payload: String) {
        val apkUrl = AppShareCode.apkUrlOf(payload)
        val room = AppShareCode.roomOf(payload)
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.share_scanned_title)
            .setMessage(getString(R.string.share_scanned_message, apkUrl))

        if (room != null) {
            builder.setPositiveButton(R.string.share_scanned_join) { _, _ ->
                roomInput.setText(room)
                joinRoom()
            }
            builder.setNeutralButton(R.string.share_scanned_download) { _, _ -> openUrl(apkUrl) }
        } else {
            builder.setPositiveButton(R.string.share_scanned_download) { _, _ -> openUrl(apkUrl) }
        }
        builder.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { Toast.makeText(this, getString(R.string.share_open_failed, url), Toast.LENGTH_LONG).show() }
    }

    /** Gallery write needs a permission below Android 10 only. */
    private var pendingQr: Bitmap? = null
    private val storagePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val bitmap = pendingQr
        pendingQr = null
        if (granted && bitmap != null) saveQr(bitmap) else
            Toast.makeText(this, R.string.room_qr_save_denied, Toast.LENGTH_LONG).show()
    }

    private fun saveQr(bitmap: Bitmap) {
        if (QrSaver.needsPermission() && !hasStoragePermission()) {
            pendingQr = bitmap
            storagePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        val location = QrSaver.save(this, bitmap, "cadence-qr")
        val text = if (location != null) getString(R.string.room_qr_saved, location)
        else getString(R.string.room_qr_save_failed)
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    private fun hasStoragePermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

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
        findViewById<Button>(R.id.albumScanButton).setOnClickListener { startAlbumScan() }
        findViewById<Button>(R.id.discoverButton).setOnClickListener { discoverRooms() }
        findViewById<Button>(R.id.shareAppButton).setOnClickListener { shareApp() }
        findViewById<Button>(R.id.checkUpdateButton).setOnClickListener { checkForUpdate() }
        findViewById<Button>(R.id.showQrButton).setOnClickListener { showMyQr() }

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
        renderChat()
        updateStatus()
        publishProfileName()
        autoCheckUpdate()
        offerNearbyRoom()
    }

    override fun onPause() {
        super.onPause()
        pollJob?.cancel()
        pollJob = null
    }

    // ---- profile -----------------------------------------------------------

    /**
     * Tells the room the name currently saved in "我的". The nickname is edited
     * there, so this screen republishes it whenever it comes back to the front.
     */
    private fun publishProfileName() {
        val room = remoteRoom ?: return
        val username = LanRoom.username(this)
        val profileId = LanRoom.profileId(this)
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { RoomClient(room).updateProfile(username, profileId) }
            }
        }
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
                // Poll our own server over loopback: it keeps working even if the
                // Wi-Fi address changes. The address shown and shared is the LAN
                // one, because 127.0.0.1 is useless to anyone else.
                joinAddress("127.0.0.1:$port")
                val url = LanRoom.bestLanUrl() ?: LanRoom.hostingUrls().firstOrNull()
                roomInput.setText(url?.removePrefix("http://")?.trimEnd('/') ?: "127.0.0.1:$port")
                updateStatus()
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
        joinAddress(address)
    }

    /** Joins [address]; the transport may differ from the address shown. */
    private fun joinAddress(address: String) {
        val normalized = if (address.startsWith("http")) address else "http://$address"
        val room = normalized.trimEnd('/')
        remoteRoom = room
        pollFailures = 0
        statusText.text = getString(R.string.room_joining, room)
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val client = RoomClient(room)
                    val health = client.healthInfo() ?: return@runCatching false
                    // A peer speaking an unreadable dialect would silently skew
                    // the board, so refuse early instead of ranking nonsense.
                    if (health.protocol > 0 && !Protocol.canRead(health.protocol)) {
                        return@runCatching false
                    }
                    remoteProtocol = health.protocol
                    val merged = client.pushEvents(LanRoom.localStore(this@LanLeaderboardActivity).events())
                    remoteEvents.clear()
                    remoteEvents.addAll(merged)
                    true
                }.getOrDefault(false)
            }
            if (!ok) {
                statusText.text = getString(R.string.room_join_failed, room)
            } else {
                updateStatus()
                announceProtocolMismatch()
                publishProfileName()
            }
            refreshBoard()
            renderChat()
        }
    }

    /**
     * On open, look for a room and offer to join.
     *
     * This is what closes the loop after installing from a shared QR: the app is
     * opened for the first time, sees the room that shared it, and asks.
     */
    private fun offerNearbyRoom() {
        if (LanRoom.isHosting() || remoteRoom != null || offeredThisLaunch) return
        offeredThisLaunch = true
        lifecycleScope.launch {
            delay(700)
            val candidates = runCatching { collectCandidates() }.getOrDefault(emptyList())
            val best = HostElection.elect(candidates, HostElection.MODE_DELAY, System.currentTimeMillis())
                ?: return@launch
            lastCandidates = candidates
            val label = best.name.ifEmpty { best.address }
            MaterialAlertDialogBuilder(this@LanLeaderboardActivity)
                .setTitle(R.string.room_qr_auto_join_title)
                .setMessage(getString(R.string.room_qr_auto_join_message, "$label（${best.address}）"))
                .setPositiveButton(R.string.room_qr_auto_join_yes) { _, _ ->
                    roomInput.setText(best.address)
                    joinRoom()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
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

    // ---- update ------------------------------------------------------------

    /**
     * Checks for a release on open, at most every few hours.
     *
     * A newly published version shows up as a question ("要现在更新吗?") together
     * with its release notes, which double as the announcement.
     */
    private fun autoCheckUpdate() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_UPDATE_CHECK, 0)
        val now = System.currentTimeMillis()
        if (!UpdatePolicy.shouldCheck(last, now)) return
        prefs.edit().putLong(KEY_LAST_UPDATE_CHECK, now).apply()

        val current = currentVersionName()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { UpdateClient.check(current) }
            if (result.outcome != UpdateClient.Outcome.UPDATE) return@launch
            val release = result.release ?: return@launch
            MaterialAlertDialogBuilder(this@LanLeaderboardActivity)
                .setTitle(getString(R.string.update_available_title, release.tag))
                .setMessage(
                    getString(
                        R.string.update_auto_message,
                        current,
                        release.tag,
                        UpdatePolicy.notesPreview(release.notes)
                            .ifBlank { getString(R.string.update_no_notes) },
                    ),
                )
                .setPositiveButton(R.string.update_now) { _, _ -> downloadUpdate(release) }
                .setNegativeButton(R.string.update_later, null)
                .show()
        }
    }

    private fun currentVersionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
    }.getOrDefault("?")

    // ---- announce ----------------------------------------------------------

    /** Shows a note when the room speaks a different dialect than this build. */
    private fun announceProtocolMismatch() {
        val remote = remoteProtocol
        if (remote <= 0) return
        val message = when {
            Protocol.isNewerThanLocal(remote) -> getString(R.string.compat_peer_newer, remote)
            Protocol.isOlderButReadable(remote) -> getString(R.string.compat_peer_older, remote)
            else -> return
        }
        statusText.text = message
    }

    /** Asks GitHub for a newer release and offers to install it. */
    private fun checkForUpdate() {
        val current = currentVersionName()
        Toast.makeText(this, getString(R.string.update_checking, current), Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { UpdateClient.check(current) }
            when (result.outcome) {
                UpdateClient.Outcome.UPDATE -> showUpdateDialog(result)
                UpdateClient.Outcome.UP_TO_DATE ->
                    MaterialAlertDialogBuilder(this@LanLeaderboardActivity)
                        .setTitle(R.string.update_none_title)
                        .setMessage(getString(R.string.update_none_message, current, result.release?.tag ?: "—"))
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                UpdateClient.Outcome.OFFLINE -> Toast.makeText(
                    this@LanLeaderboardActivity,
                    R.string.update_offline,
                    Toast.LENGTH_LONG,
                ).show()
                UpdateClient.Outcome.NO_RELEASE -> Toast.makeText(
                    this@LanLeaderboardActivity,
                    R.string.update_no_release,
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun showUpdateDialog(result: UpdateClient.Result) {
        val release = result.release ?: return
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.update_available_title, release.tag))
            .setMessage(
                getString(
                    R.string.update_available_message,
                    result.current,
                    release.tag,
                    release.notes.take(600).ifBlank { getString(R.string.update_no_notes) },
                ),
            )
            .setPositiveButton(R.string.update_download) { _, _ -> downloadUpdate(release) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun downloadUpdate(release: UpdateChecker.Release) {
        if (!release.hasApk()) {
            Toast.makeText(this, R.string.update_no_apk, Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                ApkInstaller.download(this@LanLeaderboardActivity, release.apkUrl)
            }
            if (file == null) {
                Toast.makeText(this@LanLeaderboardActivity, R.string.update_download_failed, Toast.LENGTH_LONG).show()
                return@launch
            }
            if (!ApkInstaller.canInstall(this@LanLeaderboardActivity)) {
                Toast.makeText(this@LanLeaderboardActivity, R.string.update_need_permission, Toast.LENGTH_LONG).show()
                ApkInstaller.requestInstallPermission(this@LanLeaderboardActivity)
                return@launch
            }
            if (!ApkInstaller.install(this@LanLeaderboardActivity, file)) {
                Toast.makeText(this@LanLeaderboardActivity, R.string.update_install_failed, Toast.LENGTH_LONG).show()
            }
        }
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

    /** Opens the system photo picker so an existing screenshot can be scanned. */
    private fun startAlbumScan() {
        runCatching {
            albumScanLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
        }.onFailure {
            Toast.makeText(this, getString(R.string.room_scan_failed, it.message ?: ""), Toast.LENGTH_LONG).show()
        }
    }

    private fun decodeAlbumQr(uri: Uri) {
        lifecycleScope.launch {
            val decoded = withContext(Dispatchers.IO) {
                runCatching { QrCode.decodeImage(this@LanLeaderboardActivity, uri) }
            }
            decoded.onSuccess { contents ->
                if (contents.isNullOrBlank()) {
                    Toast.makeText(this@LanLeaderboardActivity, R.string.room_scan_no_code, Toast.LENGTH_LONG).show()
                } else {
                    handleScannedContents(contents)
                }
            }.onFailure {
                Toast.makeText(
                    this@LanLeaderboardActivity,
                    getString(R.string.room_scan_image_failed, it.message ?: ""),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun showMyQr() {
        val payload = shareableAddress() ?: run {
            Toast.makeText(this, R.string.room_qr_empty, Toast.LENGTH_LONG).show()
            return
        }
        showQrDialog(R.string.room_qr_title, payload, R.string.room_qr_hint)
    }

    /**
     * Shares this device's own APK.
     *
     * The code points at the file on this device's room server, so the receiver
     * needs no cable and no store; the room address rides along in the fragment
     * so the app can offer to join straight after installing.
     */
    private fun shareApp() {
        if (!LanRoom.isHosting()) {
            Toast.makeText(this, R.string.share_need_hosting, Toast.LENGTH_LONG).show()
            return
        }
        val apkUrl = LanRoom.apkUrl() ?: run {
            Toast.makeText(this, R.string.share_no_network, Toast.LENGTH_LONG).show()
            return
        }
        val room = LanRoom.bestLanUrl()?.removePrefix("http://")?.trimEnd('/')
        val payload = AppShareCode.build(apkUrl, room)
        showQrDialog(R.string.share_qr_title, payload, R.string.share_qr_hint)
    }

    /** Shows a QR with a button that writes it into the gallery. */
    private fun showQrDialog(titleRes: Int, payload: String, hintRes: Int) {
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
                text = getString(hintRes)
                textSize = 12f
                gravity = android.view.Gravity.CENTER
            },
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(titleRes)
            .setView(container)
            .setPositiveButton(R.string.room_qr_save) { _, _ -> saveQr(bitmap) }
            .setNegativeButton(android.R.string.cancel, null)
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
        // A rename rewrites existing messages, so changes are not limited to
        // new message IDs. Compare names too or history would stay stale.
        if (chatChanged(incoming)) {
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

    private fun chatChanged(incoming: List<ChatMessage>): Boolean {
        if (incoming.size != remoteChat.size) return true
        val known = remoteChat.associateBy { it.messageId }
        return incoming.any { message ->
            val previous = known[message.messageId] ?: return@any true
            previous.username != message.username ||
                previous.text != message.text ||
                previous.sentAt != message.sentAt
        }
    }

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
        // Renaming is display-only. Chat already resolves the current name; the
        // board has to use the same mapping or a person who renamed would show
        // one name in chat and another in the ranking until they trained again.
        val names = ChatNames.resolve(
            remoteChat,
            allEvents(),
            LanRoom.profileId(this),
            LanRoom.username(this),
        )
        rows.forEach { row ->
            names[row.profileId]?.let { row.username = it }
        }
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
        val names = ChatNames.resolve(
            messages,
            allEvents(),
            LanRoom.profileId(this),
            LanRoom.username(this),
        )
        messages.takeLast(100).forEach { message ->
            val name = names[message.profileId].orEmpty().ifEmpty { message.username }
            chatBox.addView(label("$name：${message.text}"))
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
                val url = LanRoom.bestLanUrl() ?: LanRoom.hostingUrls().firstOrNull()
                if (url != null) getString(R.string.room_hosting, url)
                else getString(R.string.room_hosting_no_address, LanRoom.hostingPort() ?: LanRoom.DEFAULT_PORT)
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
        private const val PREFS = "profile"
        private const val KEY_LAST_UPDATE_CHECK = "last-update-check"
        private var offeredThisLaunch = false
    }
}
