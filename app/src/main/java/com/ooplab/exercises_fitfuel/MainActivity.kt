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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("MainActivity", "onCreate called")
        setContentView(R.layout.activity_main)
        setupEdgeToEdge()
        initCameraExecutor()

        val backToMenuButton: Button = findViewById(R.id.backToMenuButton)
        backToMenuButton.setOnClickListener {
            val intent = Intent(this, MainScreenActivity::class.java)
            startActivity(intent)
            finish()
        }

        // 챌린지 ID 받아오기
        Log.d("CrashDebug", "intent: $intent")
        val challengeId = intent.getStringExtra("challengeId") ?: "chicken_banana"
        val videoUrl = intent.getStringExtra("videoUrl")
        Log.d("CrashDebug", "challengeId: $challengeId, videoUrl: $videoUrl")

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
                Log.d("CrashDebug", "Calling setupCamera")
                setupCamera()
                Log.d("CrashDebug", "Calling startCountdown")
                startCountdown()
                Log.d("CrashDebug", "setupCamera and startCountdown finished")
            },
            onError = {
                Log.e("CrashDebug", "loadCsvFromFirebaseStream error: ${it.message}")
                Log.e("CSV", "CSV 불러오기 실패: ${it.message}")
                finish() //실패하면 이전화면으로 돌아가기
            }
        )
        

        previewView = findViewById(R.id.previewCam)
        scoreTextView = findViewById(R.id.score_text)
        countdownText = findViewById(R.id.countdown_text)

        videoView = findViewById(R.id.videoView)
        yuvToRgbConverter = YuvToRgbConverter(this)

        requestCameraPermission()

        videoUrl?.let {
            Log.d("VideoDebug", "영상 URL: $it")  // URL 정상 여부 확인
            videoView.setVideoURI(Uri.parse(it))
            Log.d("VideoDebug", "setVideoURI 호출 완료")  // 👉 추가 로그

            videoView.setOnPreparedListener { mp ->
                mp.isLooping = true
                videoDurationMs = mp.duration
                videoReady = true
                Log.d("VideoDebug", "영상 준비 완료, 길이 = $videoDurationMs ms")
                videoView.requestFocus()
            }

            videoView.setOnErrorListener { mp, what, extra ->
                Log.e("VideoDebug", "영상 재생 중 오류 발생: what=$what, extra=$extra")
                false  // 시스템 기본 오류 처리 진행
            }

            
        }


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



    private val cameraPermissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && ::referenceFrames.isInitialized && referenceFrames.isNotEmpty()) {
                setupCamera()

                startCountdown()
            } else {
                Toast.makeText(this, "Camera permission required or CSV not loaded", Toast.LENGTH_SHORT).show()
            }
        }

    private fun requestCameraPermission() {
        if (!hasCameraPermission()) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
        // 권한이 이미 있더라도 여기선 아무것도 하지 않음.
        // CSV 로딩 성공 시점에서 setupCamera()와 startCountdown() 호출
    }

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

                    handler.postDelayed({
                        poseTrackingEnabled = true
                        Log.d("CrashDebug", "videoReady: $videoReady")
                        if (videoReady) {
                            videoView.start()
                            Log.d("Countdown", "영상 재생 시작")

                            handler.postDelayed({
                                poseTrackingEnabled = false
                                videoView.pause()
                                Log.d("Countdown", "영상 일시정지 → 결과 화면으로 이동")

                                val averageScore = allFrameScores.average().toFloat()
                                val total = badCount + goodCount + perfectCount
                                val weightedScore = if (total > 0) {
                                    (badCount * 1 + goodCount * 2 + perfectCount * 3).toDouble() / total
                                } else 0.0

                                val intent = Intent(this@MainActivity, ResultActivity::class.java).apply {
                                    putExtra("averageScore", averageScore)
                                    putExtra("weightedScore", weightedScore)
                                }
                                startActivity(intent)
                                finish()
                            }, videoDurationMs.toLong())

                        } else {
                            Log.e("Countdown", "영상 준비가 되지 않아서 재생할 수 없습니다!")
                            Log.e("CrashDebug", "Video not ready, cannot start.")
                        }
                    }, 200)
                }
            }
        }
        handler.post(runnable)
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

    }

    private fun setupCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().apply {
                setSurfaceProvider(previewView.surfaceProvider)
            }
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().apply {
                    setAnalyzer(cameraExecutor, ::analyzeImage)
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            } catch (e: Exception) {
                Log.e("CameraSetup", "Error binding camera use cases", e)
            }
        }, ContextCompat.getMainExecutor(this))
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