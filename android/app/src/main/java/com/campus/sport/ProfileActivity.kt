package com.campus.sport

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.campus.sport.lan.LanRoom

/** "我的" tab: profile name and device identity. */
class ProfileActivity : AppCompatActivity() {

    private lateinit var usernameInput: EditText
    private lateinit var deviceIdText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_profile)

        usernameInput = findViewById(R.id.usernameInput)
        deviceIdText = findViewById(R.id.myDeviceId)

        LanRoom.ensureLoaded(this)
        usernameInput.setText(LanRoom.username(this))
        deviceIdText.text = getString(R.string.profile_my_id, LanRoom.deviceId(this))

        findViewById<Button>(R.id.usernameSave).setOnClickListener { saveUsername() }
        findViewById<android.widget.ImageButton>(R.id.backButton).setOnClickListener { finish() }
    }

    private fun saveUsername() {
        val saved = LanRoom.setUsername(this, usernameInput.text.toString())
        usernameInput.setText(saved)
        // Keep the name on chat messages from this profile in sync too. The
        // room is updated when the LAN screen resumes.
        LanRoom.localStore(this).renameProfile(LanRoom.profileId(this), saved)
        Toast.makeText(this, getString(R.string.profile_saved, saved), Toast.LENGTH_SHORT).show()
    }
}
