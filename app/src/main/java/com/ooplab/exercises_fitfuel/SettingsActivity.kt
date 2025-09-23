package com.ooplab.exercises_fitfuel

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val backButton = findViewById<ImageView>(R.id.backButton)
        backButton.setOnClickListener {
            finish()
        }

        val homeButton = findViewById<ImageView>(R.id.homeButton)
        homeButton.setOnClickListener {
            val intent = Intent(this, MainScreenActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            finish()
        }

        val settingsButton = findViewById<ImageView>(R.id.settingsButton)
        settingsButton.setOnClickListener {
            // Already on settings screen, do nothing
        }
    }
}
