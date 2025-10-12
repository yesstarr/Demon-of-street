package com.ooplab.exercises_fitfuel

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import android.util.Log

class ResultActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_result)

        // 전달받은 점수 데이터 가져오기
        val averageScore = intent.getFloatExtra("averageScore", 0f)
        val savedVideoUriString = intent.getStringExtra("savedVideoUri")
        val savedVideoUri = savedVideoUriString?.let { Uri.parse(it) }

        // UI 요소 연결
        val averageTextView = findViewById<TextView>(R.id.average_score_text)
        val confirmButton = findViewById<Button>(R.id.confirm_button)
        val shareButton = findViewById<Button>(R.id.share_button)

        // 점수 표시
        averageTextView.text = String.format("최종 점수: %.2f", averageScore * 100)

        // 확인 버튼 클릭 시 메인 화면으로 이동
        confirmButton.setOnClickListener {
            val intent = Intent(this, MainScreenActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            finish()
        }

        // 공유 버튼 설정
        if (savedVideoUri != null) {
            shareButton.visibility = View.VISIBLE
            shareButton.setOnClickListener {
                shareVideo(savedVideoUri)
            }
        } else {
            shareButton.visibility = View.GONE
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

    private fun shareVideo(uri: Uri) {
        Log.d("ShareVideo", "Original URI: $uri")
        val shareIntent = Intent(Intent.ACTION_SEND)
        shareIntent.type = "video/mp4"

        val file = File(uri.path)
        if (!file.exists()) {
            Log.e("ShareVideo", "File does not exist at path: ${uri.path}")
            // Optionally, show a Toast to the user
            // Toast.makeText(this, "공유할 파일을 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }

        val contentUri = FileProvider.getUriForFile(this, "com.ooplab.exercises_fitfuel.provider", file)
        Log.d("ShareVideo", "Content URI: $contentUri")

        shareIntent.putExtra(Intent.EXTRA_STREAM, contentUri)
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        startActivity(Intent.createChooser(shareIntent, "영상 공유"))
    }
}