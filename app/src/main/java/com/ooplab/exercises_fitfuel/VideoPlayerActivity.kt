package com.ooplab.exercises_fitfuel

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.bumptech.glide.Glide
import android.widget.ImageView
import kotlin.also
import androidx.media3.common.Player
import com.google.firebase.ktx.Firebase
import com.google.firebase.functions.ktx.functions
import android.provider.Settings

// 원본 영상을 ExoPlayer로 재생하는 화면
class VideoPlayerActivity : AppCompatActivity() {

    private lateinit var playerView: PlayerView
    private var player: ExoPlayer? = null  // ExoPlayer 인스턴스

    private var viewSent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_videoplayer)

        // PlayerView 연결
        playerView = findViewById(R.id.playerView)

        // MainScreenActivity에서 전달받은 videoUrl, thumbnailUrl 읽기
        val videoUrl = intent.getStringExtra("videoUrl") ?: return
        val challengeId = intent.getStringExtra("challengeId")
        val startChallengeButton = findViewById<Button>(R.id.start_challenge_button)

        if (videoUrl.isNullOrBlank()) {
            // [수정] URL 없으면 화면 닫기(빈 화면 방지)
            finish()
            return
        }

        // 오직 showChallengeButton=true 로 온 경우에만 보이기
        val showChallenge = intent.getBooleanExtra("showChallengeButton", false)
        startChallengeButton.visibility = if (showChallenge) View.VISIBLE else View.GONE

        if (showChallenge) {
            startChallengeButton.setOnClickListener {
                val intent = Intent(this, MainActivity::class.java).apply {
                    putExtra("challengeId", challengeId)
                    putExtra("videoUrl", videoUrl)
                }
                startActivity(intent)
            }
        } else {
            startChallengeButton.setOnClickListener(null)
        }



        // ExoPlayer 초기화
        player = ExoPlayer.Builder(this).build().also { exo ->
            playerView.player = exo

            // [수정] 리스너를 먼저 등록(재생 이벤트 놓치지 않도록)
            exo.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying && !viewSent) {
                        viewSent = true
                        val publicVideoId = intent.getStringExtra("publicVideoId")
                        if (!publicVideoId.isNullOrBlank()) {
                            val deviceId = Settings.Secure.getString(
                                contentResolver, Settings.Secure.ANDROID_ID
                            )
                            Firebase.functions("asia-northeast3")
                                .getHttpsCallable("addViewOnStart")
                                .call(mapOf("videoId" to publicVideoId, "anonId" to deviceId))
                                .addOnFailureListener { /* 로그만 처리해도 OK */ }
                        }
                    }
                }
            })

            // 미디어 설정 후 준비/재생
            exo.setMediaItem(MediaItem.fromUri(Uri.parse(videoUrl)))
            exo.prepare()
            exo.play() // 자동 재생

        }
    }


    override fun onStop() {
        super.onStop()
        // ExoPlayer 자원 해제 (메모리 누수 방지)
        player?.release()
        player = null
    }
}