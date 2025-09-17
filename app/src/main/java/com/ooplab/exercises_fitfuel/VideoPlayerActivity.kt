package com.ooplab.exercises_fitfuel

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.bumptech.glide.Glide
import android.widget.ImageView
import kotlin.also

// 원본 영상을 ExoPlayer로 재생하는 화면
class VideoPlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null  // ExoPlayer 인스턴스

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_videoplayer)

        // PlayerView 연결
        playerView = findViewById(R.id.playerView)

        // MainScreenActivity에서 전달받은 videoUrl, thumbnailUrl 읽기
        val videoUrl = intent.getStringExtra("videoUrl") ?: return
        val thumbnailUrl = intent.getStringExtra("thumbnailUrl")
        val challengeId = intent.getStringExtra("challengeId")

        // ExoPlayer 초기화 및 세팅
        player = ExoPlayer.Builder(this).build().also { exoPlayer ->
            playerView.player = exoPlayer

            // MediaItem 생성 (영상 URL로 설정)
            val mediaItem = MediaItem.fromUri(Uri.parse(videoUrl))

            // 플레이어에 MediaItem 설정 후 재생 시작
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.play()
        }

        val startChallengeButton = findViewById<Button>(R.id.start_challenge_button)
        startChallengeButton.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.putExtra("challengeId", challengeId)
            intent.putExtra("videoUrl", videoUrl)
            startActivity(intent)
        }
    }

    override fun onStop() {
        super.onStop()
        // ExoPlayer 자원 해제 (메모리 누수 방지)
        player?.release()
        player = null
    }
}