package com.ooplab.exercises_fitfuel

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.media.Image
import android.os.Bundle
import android.os.Handler
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var previewView: PreviewView
    private lateinit var poseLandmarker: PoseLandmarker
    private lateinit var scoreTextView: TextView
    private lateinit var countdownText: TextView
    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var poseTrackingEnabled = false

    private val recentFrames = mutableListOf<List<Float>>()
    private val maxFrames = 30

    private lateinit var referenceFrames: List<List<Float>>
    private var frameIndex = 0

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
        val challengeId = intent.getStringExtra("challengeId") ?: "chicken_banana"
        Log.d("CSV", "넘어온 challengeId = $challengeId")
        Log.d("MainActivity", "loadCsvFromFirebaseStream 호출 시작")

        // Firebase에서 원본 포즈 불러오기
        AuthRepository().loadCsvFromFirebaseStream(
            challengeId,
            onLoaded = { frames ->
                referenceFrames = frames
                Log.d("CSV", "원본 포즈 ${frames.size}프레임 불러옴")
                // 이제 준비가 되었으니 카메라 + 카운트다운 시작
                recentFrames.clear()         // 이전 동작 초기화
                frameIndex = 0               // 채점용 인덱스 초기화
                setupCamera()
                startCountdown()
            },
            onError = {
                Log.e("CSV", "CSV 불러오기 실패: ${it.message}")
                finish() //실패하면 이전화면으로 돌아가기
            }
        )

        previewView = findViewById(R.id.previewCam)
        scoreTextView = findViewById(R.id.score_text)
        countdownText = findViewById(R.id.countdown_text)

        requestCameraPermission()


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
                    runOnUiThread {
                        when {
                            score < 0.82 -> {
                                scoreTextView.text = "BAD"
                                scoreTextView.setTextColor(Color.RED)
                            }
                            score < 0.88 -> {
                                scoreTextView.text = "GOOD"
                                scoreTextView.setTextColor(Color.parseColor("#FFA500")) // 주황색
                            }
                            else -> {
                                scoreTextView.text = "PERFECT"
                                scoreTextView.setTextColor(Color.BLUE)
                            }
                        }
                    }
                }
            }.build()

        poseLandmarker = PoseLandmarker.createFromOptions(this, options)
    }

    private fun calculateScore(landmarks: List<NormalizedLandmark>): Float {
        landmarks.forEachIndexed { index, lm ->
            Log.d("UserPose", "관절 #$index → x=%.3f, y=%.3f, z=%.3f".format(lm.x(), lm.y(), lm.z()))
        }

        val currentPose = landmarks.flatMap { listOf(it.x(), it.y(), it.z()) }
        recentFrames.add(currentPose)
        if (recentFrames.size > maxFrames) recentFrames.removeAt(0)

        if (referenceFrames.isEmpty() || recentFrames.size < 5) return 0f

        val referenceSegment = referenceFrames.take(recentFrames.size)
        return 1f - computeDTW(recentFrames, referenceSegment) // 유사도 점수는 낮을수록 가까움
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
        val dtw = Array(n) { FloatArray(m) { Float.POSITIVE_INFINITY } }
        dtw[0][0] = 1f - cosineSimilarity(seq1[0], seq2[0])

        for (i in 0 until n) {
            for (j in 0 until m) {
                val cost = 1f - cosineSimilarity(seq1[i], seq2[j])
                val minPrev = listOfNotNull(
                    dtw.getOrNull(i - 1)?.getOrNull(j),
                    dtw.getOrNull(i)?.getOrNull(j - 1),
                    dtw.getOrNull(i - 1)?.getOrNull(j - 1)
                ).minOrNull() ?: 0f
                dtw[i][j] = cost + minPrev
            }
        }
        return dtw[n - 1][m - 1] / (n + m) // normalize
    }



    private val cameraPermissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && referenceFrames.isNotEmpty()) {
                setupCamera()
                startCountdown()
            } else {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
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
                    poseTrackingEnabled = true
                }
            }
        }
        handler.post(runnable)
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun setupCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().apply { setSurfaceProvider(previewView.surfaceProvider) }
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().apply { setAnalyzer(cameraExecutor, ::analyzeImage) }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer)
            } catch (e: Exception) {
                Log.e("CameraSetup", "Error binding camera use cases", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @OptIn(ExperimentalGetImage::class)
    private fun analyzeImage(imageProxy: ImageProxy) {
        if (!poseTrackingEnabled) {
            imageProxy.close()
            return
        }

        val mediaImage = imageProxy.image
        if (mediaImage != null && imageProxy.format == ImageFormat.YUV_420_888) {
            val bitmap = yuvToRgb(mediaImage, imageProxy)
            val mpImage: MPImage = if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
                val matrix = Matrix().apply {
                    postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())
                    postScale(-1f, 1f, bitmap.width.toFloat(), bitmap.height.toFloat())
                }
                BitmapImageBuilder(Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)).build()
            } else {
                BitmapImageBuilder(bitmap).build()
            }
            poseLandmarker.detectAsync(mpImage, imageProxy.imageInfo.timestamp)
        }
        imageProxy.close()
    }

    private fun yuvToRgb(image: Image, imageProxy: ImageProxy): Bitmap {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 100, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        poseLandmarker.close()
    }
}