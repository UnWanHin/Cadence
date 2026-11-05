package com.campus.sport

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class HomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        findViewById<View>(R.id.startWorkoutCard).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        findViewById<View>(R.id.manualBanner).setOnClickListener {
            showManual()
        }
        findViewById<View>(R.id.navDiscover).setOnClickListener {
            showComingSoon()
        }
        findViewById<View>(R.id.navProfile).setOnClickListener {
            showComingSoon()
        }
    }

    private fun showManual() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.home_manual_dialog_title)
            .setMessage(R.string.home_manual_dialog_message)
            .setPositiveButton(R.string.home_manual_dialog_ok, null)
            .show()
    }

    private fun showComingSoon() {
        Toast.makeText(this, R.string.home_coming_soon, Toast.LENGTH_SHORT).show()
    }
}
