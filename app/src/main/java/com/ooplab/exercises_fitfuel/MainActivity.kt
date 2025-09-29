package com.ooplab.exercises_fitfuel


import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager

import android.media.Image
import android.renderscript.*
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.sqrt
import android.annotation.SuppressLint

import java.io.BufferedReader
import java.io.InputStreamReader


class   MainActivity : AppCompatActivity() {
    private lateinit var videoView: VideoView
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var previewView: PreviewView
    private lateinit var poseLandmarker: PoseLandmarker
    private lateinit var scoreTextView: TextView
    private lateinit var countdownText: TextView
    private lateinit var yuvToRgbConverter: YuvToRgbConverter

    private var cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
    private var poseTrackingEnabled = false
    private var videoReady = false

    private lateinit var referenceFrames: List<List<Float>>
    private val recentFrames = mutableListOf<List<Float>>()
    private val maxFrames = 30
    private var frameIndex = 0

    private var videoDurationMs: Int = 0
    private val allFrameScores = mutableListOf<Float>()
    private var badCount = 0
    private var goodCount = 0
    private var perfectCount = 0

    private lateinit var videoRecorder: VideoRecorder
    private var savedVideoUri: Uri? = null

    private lateinit var challengeId: String

    private var isRecording = false

    private lateinit var challengeSession: ChallengeSession


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

        // 챌린지 ID 받아오기
        Log.d("CrashDebug", "intent: $intent")
        challengeId = intent.getStringExtra("challengeId") ?: "chicken_banana"
        val videoUrl = intent.getStringExtra("videoUrl")
        Log.d("CrashDebug", "challengeId: $challengeId, videoUrl: $videoUrl")

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
                mp.isLooping = true
                videoDurationMs = mp.duration
                videoReady = true
                Log.d("VideoDebug", "영상 준비 완료, 길이 = $videoDurationMs ms")

                // ChallengeSession에도 duration 전달
                challengeSession.setVideoDuration(videoDurationMs)

                // 카운트다운이 이미 끝났다면 즉시 실행
                if (poseTrackingEnabled) {
                    // 점수 계산 람다를 넘겨줌
                    challengeSession.start {
                        if (allFrameScores.isNotEmpty()) {
                            allFrameScores.sum() / allFrameScores.size
                        } else 0f
                    }
                }
            }

            videoView.setOnErrorListener { _, what, extra ->
                Log.e("VideoDebug", "영상 재생 중 오류 발생: what=$what, extra=$extra")
                false
            }
        }

        Log.d("CrashDebug", "Calling loadCsvFromFirebaseStream")
        // Firebase에서 원본 포즈 불러오기
        AuthRepository().loadCsvFromFirebaseStream(
            challengeId,
            onLoaded = { frames ->
                Log.d("CrashDebug", "loadCsvFromFirebaseStream success")
                referenceFrames = frames
                Log.d("CSV", "원본 포즈 ${frames.size}프레임 불러옴")
                // 이제 준비가 되었으니 카메라 + 카운트다운 시작
                recentFrames.clear()         // 이전 동작 초기화
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
                Log.e("CrashDebug", "loadCsvFromFirebaseStream error: ${it.message}")
                Log.e("CSV", "CSV 불러오기 실패: ${it.message}")
                finish() //실패하면 이전화면으로 돌아가기
            }
        )

    }

    //다른 액티비티에서 돌아올 때
    override fun onResume() {
        super.onResume()
        recentFrames.clear() // 실시간 사용자 프레임 초기화
        frameIndex = 0 // 기준 프레임 인덱스 초기화
    }

    private fun setupEdgeToEdge() {
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
    }

    private fun initCameraExecutor() {
        cameraExecutor = Executors.newSingleThreadExecutor()
        initializePoseLandmarker()
    }

    private fun initializePoseLandmarker() {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath("pose_landmarker_full.task")
            .build()

        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setResultListener { result, _ ->
                if (!poseTrackingEnabled) return@setResultListener

                result.landmarks().firstOrNull()?.let { landmarkList ->
                    val score = calculateScore(landmarkList)
                    allFrameScores.add(score)
                    runOnUiThread {
                        val scorePercentage = score * 100
                        scoreTextView.text = String.format("%d", scorePercentage.toInt())

                        when {
                            score < 0.90 -> {
                                scoreTextView.setTextColor(Color.RED)
                                badCount++
                            }
                            score < 0.96 -> {
                                scoreTextView.setTextColor(Color.parseColor("#FFA500"))
                                goodCount++
                            }
                            else -> {
                                scoreTextView.setTextColor(Color.BLUE)
                                perfectCount++
                            }
                        }
                    }
                }
            }.build()

        poseLandmarker = PoseLandmarker.createFromOptions(this, options)
    }

    // Helper functions to avoid resolution issues
    private fun maxOf(a: Int, b: Int): Int = if (a > b) a else b
    private fun minOf(a: Int, b: Int): Int = if (a < b) a else b
    private fun minOf(a: Float, b: Float, c: Float): Float {
        return if (a < b) {
            if (a < c) a else c
        } else {
            if (b < c) b else c
        }
    }

    private fun calculateScore(landmarks: List<NormalizedLandmark>): Float {
        landmarks.forEachIndexed { index, lm ->
            Log.d("UserPose", "관절 #$index → x=%.3f, y=%.3f, z=%.3f".format(lm.x(), lm.y(), lm.z()))
        }

        val currentPose = landmarks.flatMap { listOf(it.x(), it.y(), it.z()) }
        recentFrames.add(currentPose)
        if (recentFrames.size > maxFrames) recentFrames.removeAt(0)

        if (referenceFrames.isEmpty() || videoDurationMs == 0) return 0f

        val videoTime = videoView.currentPosition
        val totalRefFrames = referenceFrames.size

        val currentRefFrameIndex = (videoTime.toFloat() / videoDurationMs.toFloat() * totalRefFrames).toInt()

        val windowSize = recentFrames.size
        val startIndex = maxOf(0, currentRefFrameIndex - windowSize / 2)
        val endIndex = minOf(totalRefFrames, startIndex + windowSize)

        if (startIndex >= endIndex) return 0f

        val referenceSegment = referenceFrames.subList(startIndex, endIndex)

        if (referenceSegment.isEmpty() || recentFrames.isEmpty()) return 0f

        val dtwDistance = computeDTW(recentFrames, referenceSegment)
        val amplifiedDistance = dtwDistance * 2.5f // 증폭 계수 유지
        val score = 1f - amplifiedDistance
        return score.coerceIn(0f, 1f) // 0~1 사이로 점수 유지
    }

    private fun cosineSimilarity(a: List<Float>, b: List<Float>): Float {
        val dot = a.zip(b).sumOf { (x, y) -> (x * y).toDouble() }
        val normA = sqrt(a.sumOf { (it * it).toDouble() })
        val normB = sqrt(b.sumOf { (it * it).toDouble() })

        return (dot / (normA * normB)).toFloat().coerceIn(-1f, 1f)
    }

    private fun computeDTW(seq1: List<List<Float>>, seq2: List<List<Float>>): Float {
        val n = seq1.size
        val m = seq2.size
        if (n == 0 || m == 0) return 0f // Prevent crash on empty sequences

        val dtw = Array(n) { FloatArray(m) }

        dtw[0][0] = 1f - cosineSimilarity(seq1[0], seq2[0])

        for (i in 1 until n) {
            val cost = 1f - cosineSimilarity(seq1[i], seq2[0])
            dtw[i][0] = cost + dtw[i - 1][0]
        }

        for (j in 1 until m) {
            val cost = 1f - cosineSimilarity(seq1[0], seq2[j])
            dtw[0][j] = cost + dtw[0][j - 1]
        }

        for (i in 1 until n) {
            for (j in 1 until m) {
                val cost = 1f - cosineSimilarity(seq1[i], seq2[j])
                dtw[i][j] = cost + minOf(dtw[i - 1][j], dtw[i][j - 1], dtw[i - 1][j - 1])
            }
        }

        return dtw[n - 1][m - 1] / (n + m)
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
        var index = 0

        val handler = Handler(mainLooper)
        val runnable = object : Runnable {
            override fun run() {
                if (index < countdownValues.size) {
                    countdownText.text = countdownValues[index]
                    index++
                    handler.postDelayed(this, 1000)
                } else {
                    countdownText.visibility = TextView.GONE
                    Log.d("Countdown", "카운트다운 종료 → 영상 재생 및 포즈 트래킹 시작")

                    // 포즈 추적 켜기
                    poseTrackingEnabled = true

                    if (videoReady) {
                        challengeSession.start {
                            if (allFrameScores.isNotEmpty()) {
                                allFrameScores.sum() / allFrameScores.size
                            } else 0f
                        }
                    } else {
                        Log.w("VideoDebug", "카운트다운 끝났지만 영상 준비 중...")
                    }

                    // 세션 종료 타이밍에 맞춰 포즈 추적 끄기
                    Handler(mainLooper).postDelayed({
                        poseTrackingEnabled = false
                    }, videoDurationMs.toLong() + 200)
                }
            }
        }
        handler.post(runnable)
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun analyzeImage(imageProxy: ImageProxy) {
        if (!poseTrackingEnabled) {
            imageProxy.close()
            return
        }

        val bitmap =
            Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
        imageProxy.image?.let { image ->
            try {
                val converter = YuvToRgbConverter(this)
                converter.yuvToRgb(image, bitmap)
                val mpImage = BitmapImageBuilder(bitmap).build()
                poseLandmarker.detectAsync(mpImage, System.currentTimeMillis())
            } catch (e: Exception) {
                Log.e("YUV", "Failed to convert YUV to RGB: ${e.message}")
            }
        } ?: Log.w("YUV", "Image is null")

        imageProxy.close()

    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        poseLandmarker.close()
    }


    @Suppress("DEPRECATION")
    class YuvToRgbConverter(context: Context) {
        private val rs = RenderScript.create(context)

        fun yuvToRgb(image: Image, output: Bitmap) {
            val yuvBuffer = imageToByteArray(image)
            val inputAllocation = Allocation.createSized(rs, Element.U8(rs), yuvBuffer.size)
            val outputAllocation = Allocation.createFromBitmap(rs, output)

            inputAllocation.copyFrom(yuvBuffer)

            val script = ScriptIntrinsicYuvToRGB.create(rs, Element.U8_4(rs))
            script.setInput(inputAllocation)
            script.forEach(outputAllocation)

            outputAllocation.copyTo(output)
        }

        private fun imageToByteArray(image: Image): ByteArray {
            val planes = image.planes
            val yPlane = planes[0].buffer
            val uPlane = planes[1].buffer
            val vPlane = planes[2].buffer

            val ySize = yPlane.remaining()
            val uSize = uPlane.remaining()
            val vSize = vPlane.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)

            yPlane.get(nv21, 0, ySize)
            vPlane.get(nv21, ySize, vSize)
            uPlane.get(nv21, ySize + vSize, uSize)

            return nv21
        }
    }
}

