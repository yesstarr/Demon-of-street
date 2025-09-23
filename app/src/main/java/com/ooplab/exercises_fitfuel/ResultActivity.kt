package com.ooplab.exercises_fitfuel

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class ResultActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result)

        // 전달받은 점수 데이터 가져오기
        val averageScore = intent.getFloatExtra("averageScore", 0f)
        val weightedScore = intent.getDoubleExtra("weightedScore", 0.0)

        // UI 요소 연결
        val averageTextView = findViewById<TextView>(R.id.average_score_text)
        val confirmButton = findViewById<Button>(R.id.confirm_button)

        // 점수 표시
        averageTextView.text = String.format("최종 점수: %.2f", averageScore*100)

        // 확인 버튼 클릭 시 메인 화면으로 이동
        confirmButton.setOnClickListener {
            val intent = Intent(this, MainScreenActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            finish()
        }

        val menuButton = findViewById<ImageView>(R.id.menuButton)
        menuButton.setOnClickListener {
            val intent = Intent(this, MyPageActivity::class.java)
            startActivity(intent)
        }

        val settingsButton = findViewById<ImageView>(R.id.settingsButton)
        settingsButton.setOnClickListener {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
        }

        val homeButton = findViewById<ImageView>(R.id.homeButton)
        homeButton.setOnClickListener {
            val intent = Intent(this, MainScreenActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            finish()
        }
    }
}