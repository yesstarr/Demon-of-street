package com.ooplab.exercises_fitfuel

import android.content.Intent
import android.os.Bundle
import android.widget.Button
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
        val weightedTextView = findViewById<TextView>(R.id.weighted_score_text)
        val confirmButton = findViewById<Button>(R.id.confirm_button)

        // 점수 표시
        averageTextView.text = String.format("평균 점수: %.2f", averageScore)
        weightedTextView.text = String.format("가중 점수: %.2f", weightedScore)

        // 확인 버튼 클릭 시 메인 화면으로 이동
        confirmButton.setOnClickListener {
            val intent = Intent(this, MainScreenActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            finish()
        }
    }
}