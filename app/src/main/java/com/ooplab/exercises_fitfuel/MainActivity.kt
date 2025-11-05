package com.ooplab.exercises_fitfuel


import com.ooplab.exercises_fitfuel.PoseIdx
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import com.ooplab.exercises_fitfuel.AuthRepository
import com.ooplab.exercises_fitfuel.MainScreenActivity
import com.ooplab.exercises_fitfuel.ResultActivity

import android.media.Image
import android.renderscript.*
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import android.widget.MediaController
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import android.os.SystemClock
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.sqrt
import android.annotation.SuppressLint
import kotlin.math.pow

import android.view.View // 뷰 가시성 제어에 필요
import android.media.PlaybackParams // 영상 속도 조절에 필요 (API 23 이상

import java.io.BufferedReader
import java.io.InputStreamReader


class   MainActivity : AppCompatActivity() {
    // === currentRefEnergy()용 참조 캐시 ===
    private lateinit var refN: List<List<Float>>
    private lateinit var refEnergyPose: FloatArray
    private lateinit var refEnergyAngle: FloatArray

    // 점수 스무딩 상태
    private var smoothedScore: Float? = null

    // 어깨폭 러닝 값(지금은 리셋만 하지만 멤버는 필요)
    private var runShoulderW: Float? = null
    private lateinit var videoView: VideoView
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var previewView: PreviewView
    private lateinit var poseLandmarker: PoseLandmarker
    private lateinit var scoreTextView: TextView
    private lateinit var countdownText: TextView
    private lateinit var yuvToRgbConverter: YuvToRgbConverter
    private var rgbBitmap: Bitmap? = null
    private var isShuttingDown = false
    private var cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
    private var poseTrackingEnabled = false
    private var videoReady = false
    private var penaltyScore: Float = 100f
    private lateinit var referenceFrames: List<List<Float>>
    private val recentFrames = mutableListOf<List<Float>>()
    private val maxFrames = 30
    private var frameIndex = 0
    private var lastPoseTime: Long = System.currentTimeMillis()
    private var currentScore: Float = 100f  // 화면/평균 둘 다 이 값 기준으로 갱신
    private var videoDurationMs: Int = 0
    private val allFrameScores = mutableListOf<Float>()
    private var badCount = 0
    private var goodCount = 0
    private var perfectCount = 0
    private var mediaPlayer: android.media.MediaPlayer? = null // 추가
    private lateinit var slowMotionButton: Button //추가
    private var currentPlaybackSpeed: Float = 1.0f // 추가: 현재 재생 속도 (기본 1.0x)

    private lateinit var btnCompleteChallenge: Button

    private var playbackSpeed = 1.0f
    private var currentMode = "CHALLENGE"

    private lateinit var videoRecorder: VideoRecorder
    private var savedVideoUri: Uri? = null

    private lateinit var challengeId: String

    private var isRecording = false

    private lateinit var challengeSession: ChallengeSession
    // 정지 탐지/표시 브레이크 임계
    private val STATIC_ENERGY_GATE = 0.04f  // 예전 동작 원하면 0.08f로
    private var countdownHandler: Handler? = null
    private var countdownRunnable: Runnable? = null

    // MediaPipe Pose xyz 기대 차원(33 * 3)
    private val EXPECTED_XYZ_DIMS = 33 * 3
    // === 각도/뼈대 벡터 유틸 ===
    // === EMA smoothing 상태 ===
    private var lastSmoothedFrame: List<Float>? = null
    private val emaAlpha = 0.4f   // 0.3~0.5 사이 값 추천 (0.4면 적당히 부드럽고 반응 빠름)

    // ★ NEW(from newmainactivity): 새 알고리즘 추가 필드들 ===
    private val recentFramesN = mutableListOf<List<Float>>()                  // 정규화 포즈 버퍼
    private lateinit var referenceFramesN: List<List<Float>>                  // 정규화 레퍼런스
    private var lastBestStart: Int? = null                                    // 매칭 시작점 캐시

    // 점수 스무딩/정지 감지 관련
    // (기존 흐름 유지하되 점수 표시 안정화만 적용)
    // ★ NEW(from newmainactivity)
    private val STILL_DECAY_PER_SEC = 1.0f
    private val noDetectDecayPerSec = 2.0f
    private val reinitGapMs = 500L
    @Volatile private var hasSeenPerson = false
    @Volatile private var lastDetectMs: Long = 0L
    @Volatile private var lastScoreUpdateMs = 0L
    private val perSecondMaxUp   = 30.0f
    private val perSecondMaxDown = 2.5f
    private val scoreDeadband    = 0.8f

    private var motionEma = 0f
    private val motionAlpha = 0.2f
    private var stillSinceMs = 0L
    private val STILL_MOTION_EPS = 0.10f
    private val STILL_LOCK_MS = 400L
    // ====== 끝 ======


    // 프레임에서 (x,y,z) 가져오기
    private inline fun xyz(frame: List<Float>, i: Int): Triple<Float, Float, Float> {
        val x = frame[3*i]; val y = frame[3*i+1]; val z = frame[3*i+2]
        return Triple(x, y, z)
    }

    private data class Vec3(val x: Float, val y: Float, val z: Float)
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    private fun sub(a: Vec3, b: Vec3) = Vec3(a.x - b.x, a.y - b.y, a.z - b.z)
    private fun toVec3(t: Triple<Float, Float, Float>) = Vec3(t.first, t.second, t.third)
    private fun dot(a: Vec3, b: Vec3) = (a.x*b.x + a.y*b.y + a.z*b.z)
    private fun norm(a: Vec3) = sqrt((a.x*a.x + a.y*a.y + a.z*a.z).toDouble()).toFloat()

    // 두 벡터 사이 각도의 cos(0~1) 반환(벡터가 너무 짧으면 0 반환)
    private fun cosBetween(a: Vec3, b: Vec3): Float {
        val na = norm(a); val nb = norm(b)
        if (na < 1e-6f || nb < 1e-6f) return 0f
        val c = (dot(a,b) / (na*nb)).coerceIn(-1f, 1f)
        return c
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MainActivity", "onCreate called")
        setContentView(R.layout.activity_main)
        setupEdgeToEdge()
        initCameraExecutor()

        previewView = findViewById(R.id.previewCam)
        scoreTextView = findViewById(R.id.score_text)
        countdownText = findViewById(R.id.countdown_text)

        videoView = findViewById(R.id.videoView)
        yuvToRgbConverter = YuvToRgbConverter(this)


        videoRecorder = VideoRecorder(this, this, previewView)

        val backToMenuButton: Button = findViewById(R.id.backToMenuButton)
        backToMenuButton.setOnClickListener {
            val intent = Intent(this, MainScreenActivity::class.java)
            startActivity(intent)
            finish()
        }

        // 챌린지 ID, 속도 정보 수신
        Log.d("CrashDebug", "intent: $intent")
        challengeId = intent.getStringExtra("challengeId") ?: "chicken_banana"
        val videoUrl = intent.getStringExtra("videoUrl")

        currentPlaybackSpeed = intent.getFloatExtra("selected_speed", 1.0f)

        // 모드 정보 수신 (연습/챌린지 모드)
        val isPracticeMode = intent.getBooleanExtra("IS_PRACTICE_MODE", false)
        Log.d("CrashDebug", "isPracticeMode: $isPracticeMode")

        Log.d("CrashDebug", "challengeId: $challengeId, videoUrl: $videoUrl, speed: $currentPlaybackSpeed")


        // ChallengeSession 초기화
        challengeSession = ChallengeSession(
            activity = this,
            videoView = videoView,
            videoRecorder = videoRecorder,
            challengeId = challengeId
        )

        // 권한 요청 (카메라 + 마이크)
        requestPermissions()

        // 원본 영상 준비
        videoUrl?.let {
            Log.d("VideoDebug", "영상 URL: $it")
            videoView.setVideoURI(Uri.parse(it))
            Log.d("VideoDebug", "setVideoURI 호출 완료")

            videoView.setOnPreparedListener { mp ->
                Log.d("VideoDebug", "onPrepared 호출됨! duration=${mp.duration}")
                mediaPlayer = mp // 추가
                challengeSession.setMediaPlayer(mp)

                // 1배속 여부와 관계없이 재생 속도 설정 (안정성 확보)
                try {
                    val params = mp.playbackParams
                    params.speed = currentPlaybackSpeed
                    mp.playbackParams = params
                    Log.d("VideoDebug", "재생 속도 적용 완료: $currentPlaybackSpeed")
                } catch (e: Exception) {
                    Log.e("VideoDebug", "재생 속도 적용 실패: ${e.message}")
                }

                // 필수: 영상 준비 완료 후 무조건 일시 정지 (카운트다운 시작 전 동기화)
                mp.pause()
                Log.d("VideoDebug", "영상 준비 후 일시 정지(Pause) 완료.")


                // ★★★ 핵심 수정: 연습/챌린지 모드 모두 루핑을 끕니다. ★★★
                mp.isLooping = false
                Log.d("VideoDebug", "영상 루핑 설정: 1회 재생 후 중지됨.")


                videoDurationMs = mp.duration
                videoReady = true
                Log.d("VideoDebug", "영상 준비 완료, 길이 = $videoDurationMs ms")

                // ChallengeSession에도 duration 전달
                challengeSession.setVideoDuration(videoDurationMs)


                // 챌린지 모드일 때만 (isPracticeMode가 false일 때) onCompletion 리스너 설정
                if (!isPracticeMode) {
                    videoView.setOnCompletionListener { completionMp ->
                        Log.d("VideoDebug", "챌린지 모드: 영상 재생 완료, 세션 종료 처리 시작.")
                        challengeSession.handleVideoLooping() // 챌린지 종료 처리 (점수 저장 등)
                    }
                } else {
                    // 연습 모드에서는 영상이 끝난 후 아무 작업 없이 멈춥니다.
                    videoView.setOnCompletionListener(null)
                    Log.d("VideoDebug", "연습 모드: 영상 재생 완료 시 자동 중지.")
                }
            }

            // 오류 메시지 억제 및 디버깅 로그 리스너
            videoView.setOnErrorListener { _, what, extra ->
                Log.e("VideoDebug", "영상 재생 중 오류 발생: what=$what, extra=$extra")

                Log.d("VideoDebug", "오류 발생, 시스템 기본 메시지 출력 방지 (처리됨).")
                true // 기본 처리 방지
            }
        }

        Log.d("CrashDebug", "Calling loadCsvFromFirebaseStream")
        // Firebase에서 원본 포즈 불러오기
        AuthRepository().loadCsvFromFirebaseStream(
            challengeId,

            onLoaded = { frames132 ->
                // 132차원 -> 99차원으로 통일
                Log.d("CrashDebug", "loadCsvFromFirebaseStream success")
                referenceFrames = frames132.map { stripVisibilityKeepXYZ(it) }

// ✅ 정규화 먼저
                referenceFramesN = referenceFrames
                    .mapNotNull { sanitizeXYZ(it) }
                    .map { normalizeFrameByRoot(it, useShoulderEma = false) } // ← 변경

// ✅ 그 다음 캐시 세팅
                refN = referenceFramesN
                refEnergyPose = motionEnergy(refN)
                val refAngles: List<List<Float>> = refN.map { angleFeature(it).toList() }
                refEnergyAngle = motionEnergy(refAngles)

                // 이제 준비가 되었으니 카메라 + 카운트다운 시작
                recentFrames.clear()         // 이전 동작 초기화
                recentFramesN.clear()        // NEW
                frameIndex = 0               // 채점용 인덱스 초기화
                // analyzer 준비
                val imageAnalyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build().apply {
                        setAnalyzer(cameraExecutor, ::analyzeImage)
                    }

                // 바인딩은 VideoRecorder가 preview+videoCapture(+analyzer)를 한 번에 처리
                videoRecorder.setupCamera(imageAnalyzer)

                Log.d("CrashDebug", "Calling startCountdown")
                startCountdown()
                Log.d("CrashDebug", "startCountdown finished")

                allFrameScores.clear()
                badCount = 0
                goodCount = 0
                perfectCount = 0

            },

            onError = {
                Log.e("CSV", "CSV 불러오기 실패: ${it.message}")
                finish() //실패하면 이전화면으로 돌아가기
            }
        )
    }

    // ★ NEW: 시작 시 1회만 호출되어 고정된 배속을 설정하는 함수
    private fun setInitialPlaybackSpeed(speed: Float) {
        val mp = mediaPlayer
        if (mp != null) {
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    val params = mp.playbackParams
                    params.speed = speed
                    mp.playbackParams = params
                    Log.d("VideoDebug", "Initial speed set to ${speed}x")

                    // ChallengeSession에도 초기 배속을 전달해야 합니다.
                    // (ChallengeSession.kt 수정 후 start() 호출 전에 처리)

                } else {
                    Log.w("VideoDebug", "느리게 재생은 Android 6.0 이상에서 지원됩니다. 1.0x로 재생됩니다.")
                }
            } catch (e: Exception) {
                Log.e("VideoDebug", "초기 재생 속도 설정 오류: ${e.message}")
            }
        }
    }

//    private fun setPlaybackSpeed(slowSpeed: Float) {
//        val mp = mediaPlayer
//        if (mp != null) {
//            try {
//                // Android 6.0(API 23) 이상에서만 지원되므로 버전 체크 필요
//                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
//                    // 현재 속도 확인
//                    val currentSpeed = mp.playbackParams.speed
//                    // 1.0x <-> slowSpeed 토글
//                    val newSpeed = if (currentSpeed != 1.0f) 1.0f else slowSpeed
//
//                    Log.w("VideoDebug", "SPEED_CHANGE BEFORE: currentPos=${mp.currentPosition}, isPlaying=${mp.isPlaying}")
//
//                    val params = mp.playbackParams
//                    params.speed = newSpeed
//                    mp.playbackParams = params
//
//                    Log.w("VideoDebug", "SPEED_CHANGE AFTER: newSpeed=${newSpeed}, isPlaying=${mp.isPlaying}")
//
//                    currentPlaybackSpeed = newSpeed // 속도 변수 갱신
//                    challengeSession.setCurrentPlaybackSpeed(newSpeed) // ChallengeSession에도 전달
//
//                    // ★★★ 핵심 수정: 속도 변경 후 영상을 재개해야 변경된 속도가 적용됩니다. ★★★
//                    if (!mp.isPlaying) {
//                        mp.start()
//                    }
//
//                    // 버튼 텍스트 업데이트
//                    val text = if (newSpeed == slowSpeed) "1.0x 일반 속도" else "${slowSpeed}x 느리게"
//                    slowMotionButton.text = text
//                    Toast.makeText(this, "${newSpeed}배속으로 재생합니다.", Toast.LENGTH_SHORT).show()
//                } else {
//                    Toast.makeText(this, "느리게 재생은 Android 6.0 이상에서 지원됩니다.", Toast.LENGTH_LONG).show()
//                }
//            } catch (e: Exception) {
//                Log.e("VideoDebug", "재생 속도 변경 오류: ${e.message}")
//                Toast.makeText(this, "재생 속도 변경 중 오류가 발생했습니다.", Toast.LENGTH_SHORT).show()
//            }
//        } else {
//            Toast.makeText(this, "영상을 불러오는 중입니다.", Toast.LENGTH_SHORT).show()
//        }
//    }



    //다른 액티비티에서 돌아올 때
    override fun onResume() {
        super.onResume()
        recentFrames.clear() // 실시간 사용자 프레임 초기화
        recentFramesN.clear() // ★ NEW(from newmainactivity)
        frameIndex = 0 // 기준 프레임 인덱스 초기화
    }

    private fun setupEdgeToEdge() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }


    // 132(x,y,z,vis × 33) → 99(x,y,z × 33)로 변환
    private fun stripVisibilityKeepXYZ(raw132: List<Float>): List<Float> {
        val out = ArrayList<Float>(33 * 3)
        var i = 0
        while (i + 3 < raw132.size) {
            out.add(raw132[i])     // x
            out.add(raw132[i + 1]) // y
            out.add(raw132[i + 2]) // z
            i += 4                 // visibility는 건너뜀
        }
        return out
    }

    // 런타임 프레임(99)에 NaN/Inf 방어 + 길이 검증
    private fun sanitizeXYZ(raw: List<Float>): List<Float>? {
        if (raw.size != EXPECTED_XYZ_DIMS) return null
        val out = FloatArray(EXPECTED_XYZ_DIMS)
        for (i in raw.indices) {
            val v = raw[i]
            out[i] = if (v.isFinite()) v else 0f
        }
        return out.toList()
    }

    private fun initCameraExecutor() {
        cameraExecutor = Executors.newSingleThreadExecutor()
        initializePoseLandmarker()
    }
    // === 관절 각도 특징: 각 관절의 끼인각 cos을 모아 하나의 feature 벡터(길이 고정) ===
    private fun angleFeature(frameNorm: List<Float>): FloatArray {
        // frameNorm은 normalizeFrameByRoot()를 거친 좌표여야 함
        fun v(i:Int)= toVec3(xyz(frameNorm, i))

        // 각도 계산을 위한 삼각관계(중점 j에 대해 (a-j)와 (b-j) 각도)
        fun angleCos(a:Int, j:Int, b:Int): Float {
            val ja = sub(v(a), v(j))
            val jb = sub(v(b), v(j))
            return ( (cosBetween(ja, jb) + 1f) * 0.5f ) // [-1,1]→[0,1]로 매핑(안정적)
        }

        val feats = ArrayList<Float>(16)

        // 팔꿈치(Upper-forearm)
        feats += angleCos(PoseIdx.LEFT_SHOULDER, PoseIdx.LEFT_ELBOW, PoseIdx.LEFT_WRIST)
        feats += angleCos(PoseIdx.RIGHT_SHOULDER, PoseIdx.RIGHT_ELBOW, PoseIdx.RIGHT_WRIST)

        // 어깨(목-상완) — 목 대신 양 어깨 중점 근사: (LS, RS)와 상완 벡터
        // 간단히 반대쪽 어깨와 팔꿈치로 어깨각 근사
        feats += angleCos(PoseIdx.RIGHT_SHOULDER, PoseIdx.LEFT_SHOULDER, PoseIdx.LEFT_ELBOW)
        feats += angleCos(PoseIdx.LEFT_SHOULDER, PoseIdx.RIGHT_SHOULDER, PoseIdx.RIGHT_ELBOW)

        // 무릎(Thigh-shin)
        feats += angleCos(PoseIdx.LEFT_HIP, PoseIdx.LEFT_KNEE, PoseIdx.LEFT_ANKLE)
        feats += angleCos(PoseIdx.RIGHT_HIP, PoseIdx.RIGHT_KNEE, PoseIdx.RIGHT_ANKLE)

        // 고관절(몸통-허벅지)
        feats += angleCos(PoseIdx.LEFT_SHOULDER, PoseIdx.LEFT_HIP, PoseIdx.LEFT_KNEE)
        feats += angleCos(PoseIdx.RIGHT_SHOULDER, PoseIdx.RIGHT_HIP, PoseIdx.RIGHT_KNEE)

        // 발목(발등 각도 근사: 종아리-발끝)
        feats += angleCos(PoseIdx.LEFT_KNEE, PoseIdx.LEFT_ANKLE, PoseIdx.LEFT_FOOT_INDEX)
        feats += angleCos(PoseIdx.RIGHT_KNEE, PoseIdx.RIGHT_ANKLE, PoseIdx.RIGHT_FOOT_INDEX)

        // 손목(전완-손 방향)
        feats += angleCos(PoseIdx.LEFT_ELBOW, PoseIdx.LEFT_WRIST, PoseIdx.LEFT_INDEX)
        feats += angleCos(PoseIdx.RIGHT_ELBOW, PoseIdx.RIGHT_WRIST, PoseIdx.RIGHT_INDEX)

        // 몸통 기울기(골반-어깨 라인 vs 수직): 간단히 좌우어깨-좌우엉덩이 벡터
        run {
            val pelvis = toVec3(Triple(
                (frameNorm[3*PoseIdx.LEFT_HIP] + frameNorm[3*PoseIdx.RIGHT_HIP]) * 0.5f,
                (frameNorm[3*PoseIdx.LEFT_HIP+1] + frameNorm[3*PoseIdx.RIGHT_HIP+1]) * 0.5f,
                (frameNorm[3*PoseIdx.LEFT_HIP+2] + frameNorm[3*PoseIdx.RIGHT_HIP+2]) * 0.5f
            ))
            val chest = toVec3(Triple(
                (frameNorm[3*PoseIdx.LEFT_SHOULDER] + frameNorm[3*PoseIdx.RIGHT_SHOULDER]) * 0.5f,
                (frameNorm[3*PoseIdx.LEFT_SHOULDER+1] + frameNorm[3*PoseIdx.RIGHT_SHOULDER+1]) * 0.5f,
                (frameNorm[3*PoseIdx.LEFT_SHOULDER+2] + frameNorm[3*PoseIdx.RIGHT_SHOULDER+2]) * 0.5f
            ))
            val spine = sub(chest, pelvis)
            // 수직 기준 벡터: (0,1,0) 근사 (정규화된 좌표계라 대략적 비교용)
            val up = Vec3(0f, 1f, 0f)
            feats += ( (cosBetween(spine, up) + 1f) * 0.5f )
        }

        return feats.toFloatArray()
    }

    // === 새 알고리즘: 각도 시퀀스 점수(앵커-코사인) → 0~1 ===
    // ★ CHANGED(from newmainactivity): 내부 앵커를 angle용으로 사용
    private fun angleSequenceScore(
        refNorm: List<List<Float>>,
        usrNorm: List<List<Float>>,
        tau: Int,
        window: Int = 6,
        topK: Int = 20
    ): Float {
        val refA = refNorm.map { angleFeature(it).toList() }
        val usrA = usrNorm.map { angleFeature(it).toList() }
        val eRef = motionEnergy(refNorm)
        val eUsr = motionEnergy(usrNorm)
        val energy = FloatArray(eRef.size) { i ->
            val u = if (i < eUsr.size) eUsr[i] else 0f
            0.5f * eRef[i] + 0.5f * u
        }.also { arr ->
            val m = arr.maxOrNull() ?: 1f
            if (m > 1e-6f) for (i in arr.indices) arr[i] /= m
        }
        return anchorScoreAngle(refA, usrA, tau, energy, window, topK, usrPoseN = usrNorm)
    }
    // 하이브리드 점수: 좌표 기반 + 각도 기반
    private fun scoreHybridCosine(
        userFramesRaw: List<List<Float>>,
        refFramesRaw: List<List<Float>>,
        tryMirror: Boolean = true,
        wPose: Float = 0.6f,   // 좌표 유사도 가중
        wAngle: Float = 0.4f   // 각도 유사도 가중
    ): Float {
        if (userFramesRaw.isEmpty() || refFramesRaw.isEmpty()) return 0f

        // 1) 정규화
        val refN = refFramesRaw.map { normalizeFrameByRoot(it) }
        val usrN = userFramesRaw.map { normalizeFrameByRoot(it) }
        val usrMirN = if (tryMirror) usrN.map { mirrorWithSwap(it) } else null

        // 2) 한 번만 모션에너지 계산
        val energy = motionEnergy(refN)

        fun runOne(targetN: List<List<Float>>): Float {
            // 좌표 기반으로 전역 시프트 추정
            val tau = estimateGlobalShift(refN, targetN)

            // 좌표 점수(0~1)
            val poseS = anchorScore(refN, targetN, tau, energy)

            // 각도 점수(0~1)
            val angS = angleSequenceScore(refN, targetN, tau)

            // 하이브리드(0~1)
            val mix = (wPose * poseS + wAngle * angS).coerceIn(0f, 1f)
            return mix
        }

        val s1 = runOne(usrN)
        val s2 = usrMirN?.let { runOne(it) } ?: -1f
        val best01 = maxOf(s1, s2)

        // 0~100로 매핑
        return (best01 * 100f).coerceIn(0f, 100f)
    }
    // === EMA smoothing 함수 ===
    private fun applyEMA(current: List<Float>): List<Float> {
        val prev = lastSmoothedFrame
        if (prev == null || prev.size != current.size) {
            lastSmoothedFrame = current
            return current
        }
        val smoothed = FloatArray(current.size)
        for (i in current.indices) {
            smoothed[i] = emaAlpha * current[i] + (1 - emaAlpha) * prev[i]
        }
        lastSmoothedFrame = smoothed.toList()
        return lastSmoothedFrame!!
    }
    private fun initializePoseLandmarker() {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("pose_landmarker_full.task")
            .build()


        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setResultListener { result, _ ->
                val now = SystemClock.uptimeMillis()

                // 현재 비디오 위치의 참조 모션 에너지(0~1)
                fun currentRefEnergy(): Float {
                    if (!::refN.isInitialized || refN.isEmpty()) return 0f
                    val fps   = 30f
                    val posMs = try { videoView.currentPosition } catch (_: Throwable) { 0 }
                    val idx   = (((posMs.coerceAtLeast(0) / 1000f) * fps).toInt())
                        .coerceIn(0, refN.lastIndex)
                    val ePose = if (::refEnergyPose.isInitialized) refEnergyPose.getOrElse(idx){ 0f } else 0f
                    val eAng  = if (::refEnergyAngle.isInitialized) refEnergyAngle.getOrElse(idx){ 0f } else 0f
                    return if (ePose > eAng) ePose else eAng   // maxOf(ePose, eAng)와 동일
                }

                val landmarkList = result.landmarks().firstOrNull()
                if (landmarkList != null) {
                    // 원시 점수 계산(0~100)
                    lastPoseTime = now
                    val raw = calculateScore(landmarkList)

                    // 스무딩/정지 처리
                    val (motionNow, stillLocked) = updateMotionAndStill(now)
                    val refE = currentRefEnergy()                      // ★ 추가
                    val lockOnlyWhenStatic = stillLocked && refE < STATIC_ENERGY_GATE   // ★ 추가
                    currentScore = smoothAndClampScore(raw, motionNow, now, lockOnlyWhenStatic, refE) // ★ 5번째 인자

                    allFrameScores.add(currentScore)  // ★ raw 대신 currentScore 기록
                    lastDetectMs = now
                } else {
                    // 미검출 시: refE 기반으로 자연 하강(원래 방식 유지하고 싶으면 아래 블록은 생략 가능)
                    val refE = currentRefEnergy()
                    currentScore = smoothAndClampScore(0f, 0f, now, true, refE)
                    lastScoreUpdateMs = now
                }


                // ✅ 화면 표시 & 기록(감지/미감지 모두 동일 로직!)

                runOnUiThread {
                    scoreTextView.text = "${currentScore.toInt()}"
                    when {
                        currentScore < 90f -> scoreTextView.setTextColor(Color.RED)
                        currentScore < 96f -> scoreTextView.setTextColor(Color.parseColor("#FFA500"))
                        else               -> scoreTextView.setTextColor(Color.BLUE)
                    }
                }
                // 현재 비디오 위치에 해당하는 참조 모션 에너지(0~1)


            }

            .build()

        poseLandmarker = PoseLandmarker.createFromOptions(this, options)
    }

    // ======== 새 알고리즘 핵심 ========

    // ★ NEW(from newmainactivity): 검색 윈도우 (현재 재생 위치 근방)
    private fun refSearchWindow(L: Int, margin: Int = 12): IntRange {
        if (!::referenceFramesN.isInitialized || referenceFramesN.isEmpty()) return 0..0
        val fps = 30f
        val current = try { videoView.currentPosition } catch (_: Throwable) { 0 }
        val estRefIdx = ((current.coerceAtLeast(0) / 1000f) * fps).toInt()
        val center = (estRefIdx - L).coerceAtLeast(0)
        val start = maxOf(0, center - margin)
        val end = minOf(referenceFramesN.size - L, center + margin)
        return if (start <= end) start..end else 0..0
    }

    // ★ NEW(from newmainactivity): 하이브리드(좌표+각도) + 속도 보강
    private fun scoreHybridOnNormalized(
        usrN: List<List<Float>>,
        refN: List<List<Float>>,
        tryMirror: Boolean = true,
        wPose: Float = 0.6f,
        wAngle: Float = 0.4f
    ): Float {
        val usrMirN = if (tryMirror) usrN.map { mirrorWithSwap(it) } else null
        fun runOne(targetN: List<List<Float>>): Float {
            val tau = estimateGlobalShift(refN, targetN)
            val energyPoseBoth = motionEnergyBoth(refN, targetN)
            val poseS = anchorScorePose(refN, targetN, tau, energyPoseBoth)
            val angS  = angleSequenceScore(refN, targetN, tau)
            val vPoseS = velocityScorePose(refN, targetN, tau)
            val vAngS  = velocityScoreAngle(refN, targetN, tau)
            val base   = ((wPose * poseS + wAngle * angS) / (wPose + wAngle)).coerceIn(0f, 1f)
            val wVel   = 0.20f
            return ((1f - wVel) * base + wVel * ((vPoseS + vAngS) * 0.5f)).coerceIn(0f, 1f)
        }
        val s1 = runOne(usrN)
        val s2 = usrMirN?.let { runOne(it) } ?: -1f
        return (maxOf(s1, s2) * 100f).coerceIn(0f, 100f)
    }

    // 최근 프레임 변화량(정지 판정용)
    private fun lastUserMotion(): Float {
        if (recentFramesN.size < 2) return 0f
        val a = recentFramesN.last()
        val b = recentFramesN[recentFramesN.lastIndex - 1]
        var s = 0.0
        for (i in a.indices) {
            val d = (a[i] - b[i]).toDouble()
            s += d * d
        }
        return kotlin.math.sqrt(s / a.size).toFloat()
    }

    // ★ NEW(from newmainactivity): 모션 EMA + 정지락
    private fun updateMotionAndStill(nowMs: Long): Pair<Float, Boolean> {
        val inst = lastUserMotion()
        motionEma = if (motionEma == 0f) inst else (motionAlpha * inst + (1 - motionAlpha) * motionEma)
        val isStillNow = motionEma < STILL_MOTION_EPS
        if (isStillNow) {
            if (stillSinceMs == 0L) stillSinceMs = nowMs
        } else {
            stillSinceMs = 0L
        }
        val stillLocked = stillSinceMs > 0L && (nowMs - stillSinceMs) >= STILL_LOCK_MS
        return motionEma to stillLocked
    }

    // ★ NEW(from newmainactivity): 점수 스무딩/클램프
    // 시간기반 클램프 + 데드존 + 정지 시 상승 금지
    private fun smoothAndClampScore(
        rawIn: Float,
        userMotion: Float,
        nowMs: Long,
        applyStillBrake: Boolean,
        refE: Float = 0f
    ): Float {
        val raw = rawIn.coerceIn(0f, 100f)
        if (lastScoreUpdateMs == 0L) lastScoreUpdateMs = nowMs
        val prev = smoothedScore ?: raw
        val dtSec = ((nowMs - lastScoreUpdateMs).coerceAtLeast(16L)) / 1000f

        val err = raw - prev
        val upBase = lerp(36f, 72f, refE)
        val dnBase = lerp(18f, 36f, refE)
        val upScale = ((userMotion - STILL_MOTION_EPS) / (0.35f - STILL_MOTION_EPS)).coerceIn(0f,1f)
        val upCap   = (upBase * (0.35f + 0.65f * upScale)) * dtSec
        val downCap = dnBase * dtSec

        if (applyStillBrake) {
            val naturalDown = if (err < 0f) err.coerceAtLeast(-downCap) else 0f
            val forcedDown  = -STILL_DECAY_PER_SEC * dtSec
            val out = (prev + minOf(naturalDown, forcedDown)).coerceIn(0f, 100f)
            smoothedScore = out
            lastScoreUpdateMs = nowMs
            return out
        }

        if (kotlin.math.abs(err) <= scoreDeadband) {
            smoothedScore = prev
            lastScoreUpdateMs = nowMs
            return prev
        }

        val delta = if (err > 0f) err.coerceAtMost(upCap) else err.coerceAtLeast(-downCap)
        val out = (prev + delta).coerceIn(0f, 100f)
        smoothedScore = out
        lastScoreUpdateMs = nowMs
        return out
    }
    private fun maxOf(a: Int, b: Int): Int = if (a > b) a else b
    private fun minOf(a: Int, b: Int): Int = if (a < b) a else b
    private fun minOf(a: Float, b: Float, c: Float): Float {
        return if (a < b) {
            if (a < c) a else c
        } else {
            if (b < c) b else c
        }
    }

    // === 메인 점수 계산(새 알고리즘) → 0~100 ===
    // ★ CHANGED(from newmainactivity)
    // === 메인 점수 계산(예전 알고리즘: 코사인 기반, 0~100) ===
// 다른 부분 변경 금지 요청에 따라 이 함수만 교체
    private fun calculateScore(landmarks: List<NormalizedLandmark>): Float {
        // 1) 현재 프레임 99D (x,y,z × 33)
        val currentPoseRaw = landmarks.flatMap { listOf(it.x(), it.y(), it.z()) }
        val currentPose = sanitizeXYZ(currentPoseRaw) ?: return 0f

        // 2) EMA로 부드럽게 + 정규화(정지 감지 유지용 버퍼)
        val smoothedPose = applyEMA(currentPose)
        val smoothedNorm = normalizeFrameByRoot(smoothedPose, useShoulderEma = true) // ← 변경

        // 채점용/정지감지용 버퍼 갱신 (크기 제한 유지)
        recentFrames.add(smoothedPose)
        if (recentFrames.size > maxFrames) recentFrames.removeAt(0)

        recentFramesN.add(smoothedNorm)   // 정지 감지/표시 스무딩 로직과 호환
        if (recentFramesN.size > maxFrames) recentFramesN.removeAt(0)

        // 3) 기본 조건 체크
        if (!::referenceFrames.isInitialized || referenceFrames.isEmpty()) return 0f
        if (recentFrames.size < 8) return 0f

        // 4) 예전 방식: 좌표 정규화/미러/전역시프트/앵커-코사인만으로 0~100 산출
        val score = scoreCosineOnly(
            userFramesRaw = recentFrames,
            refFramesRaw  = referenceFrames,
            tryMirror     = true
        )

        // 5) 보정/난이도 가중 없이 그대로 반환(예전 동작)
        return score.coerceIn(0f, 100f)
    }

    // === 유사도/앵커/속도 유틸 (from newmainactivity) ===

    // ★ NEW: 관절 가중 코사인(99D)
    private fun weightedCosineXYZ(a: List<Float>, b: List<Float>, jointWeights: FloatArray): Float {
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (j in 0 until 33) {
            val w = jointWeights[j].toDouble()
            val ix = 3*j; val iy = ix+1; val iz = ix+2
            val ax = a[ix].toDouble(); val ay = a[iy].toDouble(); val az = a[iz].toDouble()
            val bx = b[ix].toDouble(); val by = b[iy].toDouble(); val bz = b[iz].toDouble()
            dot += w * (ax*bx + ay*by + az*bz)
            na  += w * (ax*ax + ay*ay + az*az)
            nb  += w * (bx*bx + by*by + bz*bz)
        }
        if (na < 1e-12 || nb < 1e-12) return 0f
        return (dot / (kotlin.math.sqrt(na) * kotlin.math.sqrt(nb))).toFloat().coerceIn(-1f, 1f)
    }

    // ★ NEW: 각도 가중 코사인(13D)
    private fun weightedCosineAngle(a: List<Float>, b: List<Float>, w: FloatArray): Float {
        var dot=0.0; var na=0.0; var nb=0.0
        for (i in a.indices) {
            val wi = w.getOrElse(i){1f}.toDouble()
            val ax = a[i].toDouble(); val bx = b[i].toDouble()
            dot += wi * ax * bx
            na  += wi * ax * ax
            nb  += wi * bx * bx
        }
        if (na < 1e-12 || nb < 1e-12) return 0f
        return (dot / (kotlin.math.sqrt(na) * kotlin.math.sqrt(nb))).toFloat().coerceIn(-1f, 1f)
    }

    // ★ NEW: 시퀀스 차분(속도)
    private fun diffSequence(frames: List<List<Float>>): List<List<Float>> {
        if (frames.size < 2) return emptyList()
        val out = ArrayList<List<Float>>(frames.size - 1)
        for (t in 1 until frames.size) {
            val a = frames[t]; val b = frames[t-1]
            val d = FloatArray(a.size) { i -> a[i] - b[i] }
            out += d.toList()
        }
        return out
    }

    // ★ NEW: 속도 점수(좌표)
    private fun velocityScorePose(
        refN: List<List<Float>>,
        usrN: List<List<Float>>,
        tau:Int,
        window:Int=4,
        topK:Int=20
    ): Float {
        val refV = diffSequence(refN)
        val usrV = diffSequence(usrN)
        if (refV.isEmpty() || usrV.isEmpty()) return 0f
        val energy = motionEnergyBoth(refV, usrV)
        return anchorScorePose(refV, usrV, tau, energy, window, topK)
    }

    // ★ NEW: 속도 점수(각도)
    private fun velocityScoreAngle(
        refN: List<List<Float>>,
        usrN: List<List<Float>>,
        tau:Int,
        window:Int=4,
        topK:Int=20
    ): Float {
        val refA = refN.map { angleFeature(it).toList() }
        val usrA = usrN.map { angleFeature(it).toList() }
        val refV = diffSequence(refA)
        val usrV = diffSequence(usrA)
        if (refV.isEmpty() || usrV.isEmpty()) return 0f
        val energy = motionEnergyBoth(refV, usrV)
        return anchorScoreAngle(refV, usrV, tau, energy, window, topK, usrPoseN = null)
    }


    private fun cosine(a: List<Float>, b: List<Float>): Float {
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) {
            val x = a[i].toDouble()
            val y = b[i].toDouble()
            dot += x * y
            na += x * x
            nb += y * y
        }
        if (na < 1e-12 || nb < 1e-12) return 0f
        return (dot / (kotlin.math.sqrt(na) * kotlin.math.sqrt(nb))).toFloat().coerceIn(-1f, 1f)
    }

    // 루트(골반 중심) 기준 정규화 + 어깨폭 스케일링
    // 클래스 멤버로 이미 있음:
// private var runShoulderW: Float? = null

    private val SHOULDER_EMA_ALPHA = 0.20f   // 어깨폭 EMA 알파 (레거시 근사)
    private val SHOULDER_CLAMP_RATIO = 0.35f // 프레임 간 폭 변화 허용(±35%)

    private fun normalizeFrameByRoot(
        raw: List<Float>,
        useShoulderEma: Boolean = true // 사용자 프레임: true, 레퍼런스: false
    ): List<Float> {
        fun v(i:Int)=Triple(raw[3*i], raw[3*i+1], raw[3*i+2])

        // 1) 루트(골반 중심)
        val (lx,ly,lz)=v(PoseIdx.LEFT_HIP); val (rx,ry,rz)=v(PoseIdx.RIGHT_HIP)
        val rootX=(lx+rx)/2f; val rootY=(ly+ry)/2f; val rootZ=(lz+rz)/2f

        // 2) 어깨폭 (좌우 어깨 거리)
        val (lsx,lsy,lsz)=v(PoseIdx.LEFT_SHOULDER); val (rsx,rsy,rsz)=v(PoseIdx.RIGHT_SHOULDER)
        val instW = kotlin.math.sqrt(
            ((lsx-rsx)*(lsx-rsx) + (lsy-rsy)*(lsy-rsy) + (lsz-rsz)*(lsz-rsz)).toDouble()
        ).toFloat().coerceAtLeast(1e-4f)

        val shoulderW = if (useShoulderEma) {
            val prev = runShoulderW
            // 프레임 간 급변 방지(레거시 느낌): 이전 EMA 기준 ±35%로 클램프
            val clamped = if (prev != null) {
                val lo = prev * (1f - SHOULDER_CLAMP_RATIO)
                val hi = prev * (1f + SHOULDER_CLAMP_RATIO)
                instW.coerceIn(lo, hi)
            } else instW
            val ema = prev?.let { SHOULDER_EMA_ALPHA*clamped + (1f-SHOULDER_EMA_ALPHA)*it } ?: instW
            runShoulderW = ema
            ema
        } else {
            // 레퍼런스는 프레임 독립적(EMA 없음)
            instW
        }

        // 3) 평행이동 + 어깨폭 스케일 + z 0.3배
        val out = FloatArray(raw.size)
        var i = 0
        while (i < raw.size) {
            out[i]   = (raw[i]   - rootX) / shoulderW
            out[i+1] = (raw[i+1] - rootY) / shoulderW
            out[i+2] = ((raw[i+2] - rootZ) / shoulderW) * 0.30f
            i += 3
        }
        return out.toList()
    }
    // 좌우 미러: x 반전 + 좌우 swap
    private fun mirrorWithSwap(frame: List<Float>): List<Float> {
        val n = frame.size / 3
        val tmp = frame.toMutableList()

        // x 좌표 반전
        for (i in 0 until n) tmp[3*i] = -tmp[3*i]

        // 좌/우 관절 swap
        fun swap(a:Int, b:Int) {
            for (k in 0 until 3) {
                val ia = 3*a+k; val ib = 3*b+k
                val t = tmp[ia]; tmp[ia]=tmp[ib]; tmp[ib]=t
            }
        }
        PoseIdx.LEFT_TO_RIGHT.forEach { (l, r) -> swap(l, r) }

        return tmp // ✅ 이미 정규화된 프레임이므로 재정규화 금지(특히 z*0.3 중복 방지)
    }

    // ★ CHANGED(from newmainactivity): 전역 시프트 추정 시 관절가중 코사인 사용
    private fun estimateGlobalShift(
        ref: List<List<Float>>,
        usr: List<List<Float>>,
        maxShift: Int = 12
    ): Int {
        var bestTau = 0
        var bestScore = Float.NEGATIVE_INFINITY
        for (tau in -maxShift..maxShift) {
            var sum = 0f; var cnt = 0
            val start = maxOf(0, -tau)
            val end = minOf(ref.size, usr.size - tau)
            for (t in start until end) {
                sum += weightedCosineXYZ(ref[t], usr[t + tau], PoseScoreConfig.JOINT_WEIGHTS) // ★ CHANGED
                cnt++
            }
            if (cnt > 0) {
                val mean = sum / cnt
                if (mean > bestScore) { bestScore = mean; bestTau = tau }
            }
        }
        return bestTau
    }

    // 모션 에너지
    private fun motionEnergy(frames: List<List<Float>>): FloatArray {
        val e = FloatArray(frames.size) { 0f }
        for (t in 1 until frames.size) {
            var s = 0.0
            val a = frames[t]; val b = frames[t-1]
            for (i in a.indices) {
                val d = (a[i] - b[i]).toDouble()
                s += d * d
            }
            e[t] = kotlin.math.sqrt(s).toFloat()
        }
        val max = e.maxOrNull() ?: 1f
        if (max > 1e-6) for (i in e.indices) e[i] /= max
        return e
    }

    // ★ NEW: 양쪽 모션 에너지 결합
    private fun motionEnergyBoth(ref: List<List<Float>>, usr: List<List<Float>>): FloatArray {
        val er = motionEnergy(ref)
        val eu = motionEnergy(usr)
        val out = FloatArray(er.size) { i ->
            val u = if (i < eu.size) eu[i] else 0f
            0.5f * er[i] + 0.5f * u
        }
        val m = out.maxOrNull() ?: 1f
        if (m > 1e-6f) for (i in out.indices) out[i] /= m
        return out
    }

    // ★ NEW: 프레임 품질(0~1)
    private fun frameQuality(frame: List<Float>): Float {
        if (frame.size < 99) return 1f
        fun len(i:Int, j:Int): Double {
            val dx = frame[3*i]-frame[3*j]
            val dy = frame[3*i+1]-frame[3*j+1]
            val dz = frame[3*i+2]-frame[3*j+2]
            return kotlin.math.sqrt((dx*dx+dy*dy+dz*dz).toDouble())
        }
        val l1 = len(PoseIdx.LEFT_SHOULDER, PoseIdx.LEFT_ELBOW)
        val r1 = len(PoseIdx.RIGHT_SHOULDER, PoseIdx.RIGHT_ELBOW)
        val l2 = len(PoseIdx.LEFT_ELBOW, PoseIdx.LEFT_WRIST)
        val r2 = len(PoseIdx.RIGHT_ELBOW, PoseIdx.RIGHT_WRIST)
        val armSym = 1.0 - (kotlin.math.abs(l1-r1)+kotlin.math.abs(l2-r2)) / (l1+r1+l2+r2+1e-6)

        var zVar=0.0; var mz=0.0
        for (j in 0 until 33) mz += frame[3*j+2]
        mz /= 33.0
        for (j in 0 until 33) { val dz = frame[3*j+2]-mz.toFloat(); zVar += dz*dz }
        zVar = kotlin.math.sqrt(zVar/33.0)
        val zScore = 1.0 / (1.0 + zVar)
        return (0.7*armSym + 0.3*zScore).coerceIn(0.0,1.0).toFloat()
    }

    // ★ NEW: 좌표 99D용 앵커 스코어(0~1)
    private fun anchorScorePose(
        ref: List<List<Float>>,
        usr: List<List<Float>>,
        tau: Int,
        energy: FloatArray,
        window: Int = 6,
        topK: Int = 15,
        usrPoseN: List<List<Float>>? = null
    ): Float {
        val idx = energy.indices.sortedByDescending { energy[it] }.take(topK).sorted()
        var wtot = 0f
        var wsum = 0f
        for (t in idx) {
            val ut = t + tau
            var best = -1f
            var bestK = -1
            for (dt in -window..window) {
                val k = ut + dt
                if (k in usr.indices) {
                    val c = weightedCosineXYZ(ref[t], usr[k], PoseScoreConfig.JOINT_WEIGHTS)
                    if (c > best) { best = c; bestK = k }
                }
            }
            if (best > -0.99f) {
                val w = (0.5f + 0.5f * energy[t])
                val q = usrPoseN?.getOrNull(bestK)?.let { frameQuality(it) } ?: 1f
                val bestAdj = best * (0.85f + 0.15f * q)
                wsum += w * bestAdj
                wtot += w
            }
        }
        if (wtot == 0f) return 0f
        return ((wsum / wtot).coerceIn(-1f, 1f) + 1f) * 0.5f
    }

    // ★ NEW: 각도 13D용 앵커 스코어(0~1)
    private fun anchorScoreAngle(
        ref: List<List<Float>>,
        usr: List<List<Float>>,
        tau: Int,
        energy: FloatArray,
        window: Int = 6,
        topK: Int = 20,
        usrPoseN: List<List<Float>>? = null
    ): Float {
        val idx = energy.indices.sortedByDescending { energy[it] }.take(topK).sorted()
        var wtot = 0f
        var wsum = 0f
        for (t in idx) {
            val ut = t + tau
            var best = -1f
            var bestK = -1
            for (dt in -window..window) {
                val k = ut + dt
                if (k in usr.indices) {
                    val c = weightedCosineAngle(ref[t], usr[k], PoseScoreConfig.ANGLE_WEIGHTS)
                    if (c > best) { best = c; bestK = k }
                }
            }
            if (best > -0.99f && bestK != -1) {
                val w = 0.5f + 0.5f * energy[t]
                val q = usrPoseN?.getOrNull(bestK)?.let { frameQuality(it) } ?: 1f
                val bestAdj = best * (0.85f + 0.15f * q)
                wsum += w * bestAdj
                wtot += w
            }
        }
        if (wtot == 0f) return 0f
        return ((wsum / wtot).coerceIn(-1f, 1f) + 1f) * 0.5f
    }

    // ★ NEW: 구간 난이도 가중(0.8~1.1)
    private fun segmentDifficultyWeight(refSegN: List<List<Float>>): Float {
        val e = motionEnergy(refSegN)
        val mean = e.average().toFloat()
        return (0.8f + 0.3f * mean).coerceIn(0.8f, 1.1f)
    }

    // ======== 끝: 새 알고리즘 ========

    // 앵커 매칭
    private fun anchorScore(
        ref: List<List<Float>>,
        usr: List<List<Float>>,
        tau: Int,
        energy: FloatArray,
        window: Int = 6,
        topK: Int = 20
    ): Float {
        val idx = energy.indices.sortedByDescending { energy[it] }.take(topK).sorted()
        var num = 0
        var wsum = 0f
        for (t in idx) {
            val ut = t + tau
            var best = -1f
            for (dt in -window..window) {
                val k = ut + dt
                if (k in usr.indices) {
                    val c = cosine(ref[t], usr[k])
                    if (c > best) best = c
                }
            }
            if (best > -0.99f) {
                val w = (0.5f + 0.5f * energy[t])
                wsum += w * best
                num++
            }
        }
        if (num == 0) return 0f
        return ((wsum / num).coerceIn(-1f, 1f) + 1f) * 0.5f
    }

    // 메인 점수 계산
    private fun scoreCosineOnly(
        userFramesRaw: List<List<Float>>,
        refFramesRaw: List<List<Float>>,
        tryMirror: Boolean = true
    ): Float {
        if (userFramesRaw.isEmpty() || refFramesRaw.isEmpty()) return 0f

        val ref = refFramesRaw.map { normalizeFrameByRoot(it, useShoulderEma = false) } // ← 변경
        val usr = userFramesRaw.map { normalizeFrameByRoot(it, useShoulderEma = true) } // ← 변경
        val usrMir = if (tryMirror) usr.map { mirrorWithSwap(it) } else null

        fun runOne(target: List<List<Float>>): Float {
            val tau = estimateGlobalShift(ref, target)
            val e = motionEnergy(ref)
            return anchorScore(ref, target, tau, e)
        }

        val s1 = runOne(usr)
        val s2 = usrMir?.let { runOne(it) } ?: -1f
        val s = maxOf(s1, s2)
        return (s * 100f).coerceIn(0f, 100f)
    }

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val allGranted = results.values.all { it }
            if (!allGranted) {
                Toast.makeText(this, "카메라/마이크 권한이 필요합니다", Toast.LENGTH_SHORT).show()
            }
        }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            requestPermissionsLauncher.launch(missing.toTypedArray())
        }
    }

    @SuppressLint("MissingPermission")
    private fun startCountdown() {
        Log.d("CrashDebug", "startCountdown called")
        Log.d("Countdown", "카운트다운 시작")
        countdownText.visibility = TextView.VISIBLE
        val countdownValues = listOf("3", "2", "1", "Start!")
        lastSmoothedFrame = null
        smoothedScore = null
        lastScoreUpdateMs = 0L
        runShoulderW = null
        var index = 0

        countdownHandler = Handler(Looper.getMainLooper())
        countdownRunnable = object : Runnable {
            override fun run() {
                if (index < countdownValues.size) {
                    countdownText.text = countdownValues[index]
                    index++
                    countdownHandler?.postDelayed(this, 1000)
                } else {
                    countdownText.visibility = TextView.GONE
                    Log.d("Countdown", "카운트다운 종료 → 영상 재생 및 포즈 트래킹 시작")

                    // 포즈 추적 켜기
                    poseTrackingEnabled = true

                    if (videoReady) {
                        videoView.start() // 일시 정지된 영상을 여기서 재생 시작
                        Log.d("VideoDebug", "카운트다운 종료, videoView.start() 호출.")
                    }

                    if (videoReady) {
                        // ★ MODIFIED: challengeSession.start() 호출 시 고정 배속을 전달합니다.
                        challengeSession.start(
                            averageScoreProvider = {
                                if (allFrameScores.isNotEmpty()) {
                                    (allFrameScores.sum() / allFrameScores.size) / 100f
                                } else 0f
                            },
                            speed = currentPlaybackSpeed // ★ NEW: 고정 배속 값 전달 ★
                        )
                    } else {
                        Log.w("VideoDebug", "카운트다운 끝났지만 영상 준비 중...")
                    }

                    // 세션 종료 타이밍에 맞춰 포즈 추적 끄기 (참고: 실제 세션 종료는 ChallengeSession의 타이머가 처리함)
                    Handler(mainLooper).postDelayed({
                        poseTrackingEnabled = false
                    }, videoDurationMs.toLong() + 200)
                }
            }
        }
        countdownHandler?.post(countdownRunnable!!)
    }

    @SuppressLint("UnsafeOptInUsageError")
// ★ CHANGED(from newmainactivity)
    private fun analyzeImage(imageProxy: ImageProxy) {
        try {
            // 해제 중/완료 상태면 즉시 반환
            if (!poseTrackingEnabled || isShuttingDown) {
                Log.d("PoseFlow","drop frame (enabled=$poseTrackingEnabled, shutting=$isShuttingDown)")
                imageProxy.close(); return
            }

            // 비트맵 재사용 준비
            if (rgbBitmap == null ||
                rgbBitmap!!.width  != imageProxy.width ||
                rgbBitmap!!.height != imageProxy.height) {
                rgbBitmap = Bitmap.createBitmap(
                    imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888
                )
            }
            val bmp = rgbBitmap!!

            val img = imageProxy.image
            if (img != null) {
                try {
                    // ★ CHANGED: 컨버터(필드) 재사용 + NV21 캐시 활용
                    yuvToRgbConverter.yuvToRgb(img, bmp)

                    // ★ NEW: MediaPipe 입력 이미지 + 회전 보정 옵션
                    val mpImage   = BitmapImageBuilder(bmp).build()
                    val imageOpts = ImageProcessingOptions.builder()
                        .setRotationDegrees(imageProxy.imageInfo.rotationDegrees)
                        .build()

                    // ★ CHANGED: detectAsync에 imageOpts + 단조증가 타임스탬프
                    poseLandmarker.detectAsync(
                        mpImage,
                        imageOpts,
                        SystemClock.uptimeMillis()
                    )
                } catch (e: Exception) {
                    Log.e("YUV", "convert/detect fail: ${e.message}", e)
                }
            } else {
                Log.w("YUV", "Image is null")
            }
        } finally {
            imageProxy.close()
        }
    }


    override fun onDestroy() {
        super.onDestroy()
        isShuttingDown = true
        countdownHandler?.removeCallbacksAndMessages(null)
        cameraExecutor.shutdown()
        poseLandmarker.close()
        try { yuvToRgbConverter.release() } catch (_: Throwable) {}
        rgbBitmap = null

        // ★ NEW: ChallengeSession의 리소스 정리 호출
        challengeSession.release()

        Log.d("MainActivity", "Activity and resources released.")
    }


    @Suppress("DEPRECATION")
// ★ CHANGED(from newmainactivity)
    class YuvToRgbConverter(context: Context) {
        private val rs = RenderScript.create(context)
        private var script: ScriptIntrinsicYuvToRGB? = null
        private var inAlloc: Allocation? = null
        private var outAlloc: Allocation? = null
        private var yuvBuf: ByteArray? = null
        private var cachedW = -1
        private var cachedH = -1

        fun release() {
            try {
                inAlloc?.destroy(); outAlloc?.destroy(); script?.destroy(); rs.destroy()
            } catch (_: Throwable) { }
            inAlloc = null; outAlloc = null; script = null; yuvBuf = null
        }

        fun yuvToRgb(image: Image, output: Bitmap) {
            val w = image.width; val h = image.height
            if (w != cachedW || h != cachedH || yuvBuf == null) {
                cachedW = w; cachedH = h
                // NV21 크기 = w*h (Y) + w*h/2 (VU)
                yuvBuf = ByteArray(w * h * 3 / 2)

                inAlloc?.destroy(); outAlloc?.destroy(); script?.destroy()
                inAlloc = Allocation.createSized(rs, Element.U8(rs), yuvBuf!!.size)
                outAlloc = Allocation.createFromBitmap(rs, output)
                script  = ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs))
            }

            fillNv21(image, yuvBuf!!)
            inAlloc!!.copyFrom(yuvBuf)
            script!!.setInput(inAlloc)
            script!!.forEach(outAlloc)
            outAlloc!!.copyTo(output)
        }

        private fun fillNv21(image: Image, out: ByteArray) {
            val w = image.width
            val h = image.height
            val yPlane = image.planes[0]
            val uPlane = image.planes[1]
            val vPlane = image.planes[2]

            val yBuf = yPlane.buffer
            val uBuf = uPlane.buffer
            val vBuf = vPlane.buffer

            val yRowStride = yPlane.rowStride
            val yPixelStride = yPlane.pixelStride
            val uRowStride = uPlane.rowStride
            val uPixelStride = uPlane.pixelStride
            val vRowStride = vPlane.rowStride
            val vPixelStride = vPlane.pixelStride

            var offset = 0

            // Y
            for (row in 0 until h) {
                var col = 0
                while (col < w) {
                    out[offset++] = yBuf.get(row * yRowStride + col * yPixelStride)
                    col++
                }
            }

            // VU (NV21)
            val chromaH = h / 2
            val chromaW = w / 2
            for (row in 0 until chromaH) {
                var col = 0
                while (col < chromaW) {
                    val v = vBuf.get(row * vRowStride + col * vPixelStride)
                    val u = uBuf.get(row * uRowStride + col * uPixelStride)
                    out[offset++] = v
                    out[offset++] = u
                    col++
                }
            }
        }
    }

}

// ★ NEW(from newmainactivity): 채점 가중/보정 설정 (top-level object)
object PoseScoreConfig {
    // 관절 가중(33개)
    val JOINT_WEIGHTS: FloatArray = FloatArray(33) { 1f }.apply {
        this[PoseIdx.LEFT_SHOULDER] = 1.3f; this[PoseIdx.RIGHT_SHOULDER] = 1.3f
        this[PoseIdx.LEFT_ELBOW] = 1.2f;    this[PoseIdx.RIGHT_ELBOW] = 1.2f
        this[PoseIdx.LEFT_WRIST] = 1.1f;    this[PoseIdx.RIGHT_WRIST] = 1.1f
        this[PoseIdx.LEFT_HIP] = 1.2f;      this[PoseIdx.RIGHT_HIP] = 1.2f
        this[PoseIdx.LEFT_KNEE] = 1.2f;     this[PoseIdx.RIGHT_KNEE] = 1.2f
        this[PoseIdx.LEFT_ANKLE] = 1.1f;    this[PoseIdx.RIGHT_ANKLE] = 1.1f
    }
    // 각도 가중(13개)
    val ANGLE_WEIGHTS: FloatArray = floatArrayOf(
        1.2f, 1.2f, 1.1f, 1.1f, 1.2f, 1.2f, 1.1f, 1.1f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f
    )
    // 0~1 보정 커브
    fun calibrateScore(x: Float): Float {
        val g = 0.9
        val v = x.coerceIn(0f, 1f).toDouble()
        return v.pow(g).toFloat()   // ← 확장함수 형태로 호출
    }
}

