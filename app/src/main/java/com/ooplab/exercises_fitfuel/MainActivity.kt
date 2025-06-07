package com.ooplab.exercises_fitfuel

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.media.Image
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.renderscript.*
import android.util.Log
import android.widget.MediaController
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {
    private lateinit var videoView: VideoView
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var previewView: PreviewView
    private lateinit var poseLandmarker: PoseLandmarker
    private lateinit var scoreTextView: TextView

    private lateinit var countdownText: TextView
    private lateinit var yuvToRgbConverter: YuvToRgbConverter

    private var cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private var poseTrackingEnabled = false
    private var videoReady = false

    private lateinit var referenceFrames: List<List<Float>>
    private val recentFrames = mutableListOf<List<Float>>()
    private val maxFrames = 30

<<<<<<< Updated upstream
=======
    private var videoDurationMs: Int = 0
    private val allFrameScores = mutableListOf<Float>()
    private var badCount = 0
    private var goodCount = 0
    private var perfectCount = 0
>>>>>>> Stashed changes

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewCam)
        scoreTextView = findViewById(R.id.score_text)
        countdownText = findViewById(R.id.countdown_text)
        videoView = findViewById(R.id.videoView)
        yuvToRgbConverter = YuvToRgbConverter(this)

        setupEdgeToEdge()
        initCameraExecutor()
        requestCameraPermission()
        referenceFrames = loadReferencePoseFromAssets()

        val uri = Uri.parse("android.resource://${packageName}/raw/chickenbanana")
        videoView.setVideoURI(uri)
        val mediaController = MediaController(this)
        mediaController.setAnchorView(videoView)
        videoView.setMediaController(mediaController)
        videoView.setOnPreparedListener {
            it.isLooping = true
            videoDurationMs = it.duration
            videoReady = true
            videoView.requestFocus()
        }

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
                        when {
                            score < 0.90 -> {
                                badCount++
                                scoreTextView.text = "BAD"
                                scoreTextView.setTextColor(Color.RED)
                            }
                            score < 0.96 -> {
                                goodCount++
                                scoreTextView.text = "GOOD"
                                scoreTextView.setTextColor(Color.parseColor("#FFA500"))
                            }
<<<<<<< Updated upstream


=======
>>>>>>> Stashed changes
                            else -> {
                                perfectCount++
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
        val currentPose = landmarks.flatMap { listOf(it.x(), it.y(), it.z()) }
        recentFrames.add(currentPose)
        if (recentFrames.size > maxFrames) recentFrames.removeAt(0)
        if (referenceFrames.isEmpty() || recentFrames.size < 5) return 0f
        val referenceSegment = referenceFrames.take(recentFrames.size)
        return 1f - computeDTW(recentFrames, referenceSegment)
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
        return dtw[n - 1][m - 1] / (n + m)
    }

    private fun loadReferencePoseFromAssets(): List<List<Float>> {
        val inputStream = assets.open("correct_shorts.csv")
        val reader = BufferedReader(InputStreamReader(inputStream))
        val frames = mutableListOf<MutableList<Float>>()
        var currentFrame = 0
        var frameData = mutableListOf<Float>()
        reader.readLines().drop(1).forEach { line ->
            val cols = line.split(",")
            val frame = cols[0].toInt()
            val x = cols[3].toFloat()
            val y = cols[4].toFloat()
            val z = cols[5].toFloat()
            if (frame != currentFrame) {
                frames.add(frameData)
                frameData = mutableListOf()
                currentFrame = frame
            }
            frameData.addAll(listOf(x, y, z))
        }
        if (frameData.isNotEmpty()) frames.add(frameData)
        return frames

    }

    private val cameraPermissionLauncher: ActivityResultLauncher<String> =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->

            if (granted) {
                setupCamera()
                startCountdown()
            } else {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
            }
        }

    private fun requestCameraPermission() {
        if (hasCameraPermission()) {
            previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            setupCamera()
            startCountdown()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun hasCameraPermission(): Boolean {
<<<<<<< Updated upstream
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

=======
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
>>>>>>> Stashed changes
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

        val bitmap = Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
        imageProxy.image?.let { image ->
            try {
                yuvToRgbConverter.yuvToRgb(image, bitmap)
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

<<<<<<< Updated upstream

    @Suppress("DEPRECATION")
=======
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
                    handler.postDelayed({
                        poseTrackingEnabled = true
                        videoView.start()
                        handler.postDelayed({
                            poseTrackingEnabled = false
                            videoView.pause() // Stop video sound

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
                    }, 200)
                }
            }
        }
        handler.post(runnable)
    }

>>>>>>> Stashed changes
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