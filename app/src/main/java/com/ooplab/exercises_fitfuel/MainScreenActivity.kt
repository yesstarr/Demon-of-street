package com.ooplab.exercises_fitfuel

import android.os.Bundle
import android.content.Intent
import android.widget.Button
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity

class MainScreenActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mainscreen)

        val btnChallenge = findViewById<Button>(R.id.btnChallengeMode)
        btnChallenge.setOnClickListener {
            val intent = Intent(this, RecyclerActivity::class.java)
            startActivity(intent)
        }

        val btnPractice = findViewById<Button>(R.id.btnPracticeMode)
        btnPractice.setOnClickListener {
            val intent = Intent(this, MyPageActivity::class.java)
            startActivity(intent)
        }

        val menuButton = findViewById<ImageView>(R.id.menuButton)
        menuButton.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        val settingsButton = findViewById<ImageView>(R.id.settingsButton)
        settingsButton.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }
    }
}