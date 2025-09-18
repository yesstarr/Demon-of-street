package com.ooplab.exercises_fitfuel


import com.ooplab.exercises_fitfuel.PoseIdx
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
    // MediaPipe Pose xyz 기대 차원(33 * 3)
    private val EXPECTED_XYZ_DIMS = 33 * 3
    // === 각도/뼈대 벡터 유틸 ===
    // === EMA smoothing 상태 ===
    private var lastSmoothedFrame: List<Float>? = null
    private val emaAlpha = 0.4f   // 0.3~0.5 사이 값 추천 (0.4면 적당히 부드럽고 반응 빠름)

    // 프레임에서 (x,y,z) 가져오기
    private inline fun xyz(frame: List<Float>, i: Int): Triple<Float, Float, Float> {
        val x = frame[3*i]; val y = frame[3*i+1]; val z = frame[3*i+2]
        return Triple(x, y, z)
    }

    private data class Vec3(val x: Float, val y: Float, val z: Float)

    private fun sub(a: Vec3, b: Vec3) = Vec3(a.x - b.x, a.y - b.y, a.z - b.z)
    private fun toVec3(t: Triple<Float, Float, Float>) = Vec3(t.first, t.second, t.third)
    private fun dot(a: Vec3, b: Vec3) = (a.x*b.x + a.y*b.y + a.z*b.z)
    private fun norm(a: Vec3) = kotlin.math.sqrt((a.x*a.x + a.y*a.y + a.z*a.z).toDouble()).toFloat()

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
            onLoaded = { frames132 ->
                // ✅ 132차원 -> 99차원으로 통일
                referenceFrames = frames132.map { stripVisibilityKeepXYZ(it) }
                Log.d("CSV", "원본 포즈 ${referenceFrames.size}프레임(visibility 제거, xyz 99)")

                recentFrames.clear()
                frameIndex = 0
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
    // 각도 시퀀스 점수(0~1)
    private fun angleSequenceScore(
        refNorm: List<List<Float>>,
        usrNorm: List<List<Float>>,
        tau: Int,
        window: Int = 6,
        topK: Int = 20
    ): Float {
        // 각 프레임을 각도 특징으로 변환
        val refA = refNorm.map { angleFeature(it).toList() }
        val usrA = usrNorm.map { angleFeature(it).toList() }

        // ref 쪽 앵커는 좌표 기반과 동일(움직임 큰 곳을 앵커로 보는 개념 유지)
        val energy = motionEnergy(refNorm)
        // 각도 벡터끼리 코사인 비교를 동일 창/상위K 방식으로 수행
        return anchorScore(refA, usrA, tau, energy, window, topK)
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
                if (!poseTrackingEnabled) return@setResultListener

                val now = System.currentTimeMillis()
                val landmarkList = result.landmarks().firstOrNull()

                if (landmarkList != null) {
                    // ✅ 포즈가 잡힌 경우: 점수 계산 → currentScore 갱신
                    lastPoseTime = now
                    val score = calculateScore(landmarkList)      // 0~100 반환
                    currentScore = score

                    // 등급 카운트 업데이트
                    when {
                        currentScore < 90f -> badCount++
                        currentScore < 96f -> goodCount++
                        else ->               perfectCount++
                    }
                } else {
                    // ✅ 포즈 미검출: 지난 초만큼 3점씩 감점
                    val elapsedSec = ((now - lastPoseTime) / 1000L).toInt()
                    if (elapsedSec > 0) {
                        currentScore = (currentScore - 3f * elapsedSec).coerceAtLeast(0f)
                        lastPoseTime += elapsedSec * 1000L
                        // 미검출은 BAD로 간주하고 싶으면 아래 주석 해제
                        // badCount++
                    }
                }

                // ✅ 화면 표시 & 기록(감지/미감지 모두 동일 로직!)
                allFrameScores.add(currentScore)
                runOnUiThread {
                    scoreTextView.text = "${currentScore.toInt()}"
                    when {
                        currentScore < 90f -> scoreTextView.setTextColor(Color.RED)
                        currentScore < 96f -> scoreTextView.setTextColor(Color.parseColor("#FFA500"))
                        else               -> scoreTextView.setTextColor(Color.BLUE)
                    }
                }
            }
            .build()

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
        // 1) 현재 프레임 → (x,y,z)*33 벡터
        val currentPoseRaw = landmarks.flatMap { listOf(it.x(), it.y(), it.z()) }
        val currentPose = sanitizeXYZ(currentPoseRaw) ?: return 0f

// ✅ EMA smoothing 적용
        val smoothedPose = applyEMA(currentPose)

        recentFrames.add(smoothedPose)
        if (recentFrames.size > maxFrames) recentFrames.removeAt(0)

        // 2) 최소 버퍼 확보
        if (referenceFrames.isEmpty() || recentFrames.size < 8) return 0f

        // 3) 레퍼런스 길이 맞추기 (앞부분만 사용; 리샘플 권장)
        val refSegment = referenceFrames
            .take(recentFrames.size)
            .mapNotNull { sanitizeXYZ(it) }
        if (refSegment.isEmpty()) return 0f


        // 4) 코사인 기반 점수 계산
        return scoreHybridCosine(
            userFramesRaw = recentFrames.take(refSegment.size),
            refFramesRaw = refSegment,
            tryMirror = true,
            wPose = 0.6f,
            wAngle = 0.4f
        )
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
    private fun normalizeFrameByRoot(raw: List<Float>): List<Float> {
        fun v(i:Int)=Triple(raw[3*i], raw[3*i+1], raw[3*i+2])

        val (lx,ly,lz)=v(PoseIdx.LEFT_HIP); val (rx,ry,rz)=v(PoseIdx.RIGHT_HIP)
        val rootX=(lx+rx)/2f; val rootY=(ly+ry)/2f; val rootZ=(lz+rz)/2f

        val (lsx,lsy,lsz)=v(PoseIdx.LEFT_SHOULDER); val (rsx,rsy,rsz)=v(PoseIdx.RIGHT_SHOULDER)
        val shoulderW = kotlin.math.sqrt(
            ((lsx-rsx)*(lsx-rsx) + (lsy-rsy)*(lsy-rsy) + (lsz-rsz)*(lsz-rsz)).toDouble()
        ).toFloat().coerceAtLeast(1e-4f)

        val out = FloatArray(raw.size)
        for (i in raw.indices step 3) {
            out[i]   = (raw[i]   - rootX) / shoulderW
            out[i+1] = (raw[i+1] - rootY) / shoulderW
            out[i+2] = (raw[i+2] - rootZ) / shoulderW * 0.3f // z 축 가중치 낮춤
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

        // 반전 후 다시 정규화
        return normalizeFrameByRoot(tmp)
    }

    // 전역 시프트 추정
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
                sum += cosine(ref[t], usr[t + tau])
                cnt++
            }
            if (cnt > 0) {
                val mean = sum / cnt
                if (mean > bestScore) {
                    bestScore = mean
                    bestTau = tau
                }
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

        val ref = refFramesRaw.map { normalizeFrameByRoot(it) }
        val usr = userFramesRaw.map { normalizeFrameByRoot(it) }
        val usrMir = if (tryMirror) usr.map { mirrorWithSwap(it) } else null

        fun runOne(target: List<List<Float>>): Float {
            val tau = estimateGlobalShift(ref, target)
            val e = motionEnergy(ref)
            return anchorScore(ref, target, tau, e)
        }

        val s1 = runOne(usr)
        val s2 = usrMir?.let { runOne(it) } ?: -1f
        val s = maxOf(s1, s2)

        return (s * 100f).coerceIn(0f, 100f) // 보기 좋은 0~100 점수
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

                                val averageScore = allFrameScores.average().toFloat()/100
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