package com.ooplab.exercises_fitfuel

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import android.content.pm.PackageManager
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import android.content.ContentValues
import android.os.Build
import android.provider.MediaStore
import java.io.File
import android.media.MediaPlayer
import android.media.PlaybackParams
/**
 * 한 번의 챌린지 세션 실행을 관리하는 클래스
 * - 원본 영상 재생
 * - 사용자 녹화 시작/종료
 * - 점수 계산 후 업로드
 */
class ChallengeSession(
    private val activity: AppCompatActivity,
    private val videoView: VideoView,
    private val videoRecorder: VideoRecorder,
    private val challengeId: String

) {
    private val handler = Handler(Looper.getMainLooper())
    private var savedVideoUri: Uri? = null
    private var pendingScore: Float? = null
    private var recordingStarted = false
    private var videoDurationMs: Int = 0
    // private var currentPlaybackSpeed: Float = 1.0f //  추가: 현재 재생 속도
    private var fixedPlaybackSpeed: Float = 1.0f // ★ 새로운 고정 변수 사용
    private fun isActivityAlive(): Boolean =
        !activity.isFinishing && !activity.isDestroyed
    private var challengeEndRunnable: Runnable? = null
    private var challengeStartTimeMs: Long = 0L
    private var totalVideoDuration1xMs: Long = 0L // 1배속 기준 총 길이 (ms)
    private var scoreProvider: (() -> Float)? = null // 점수 계산 람다를 저장

    // 사용자가 "저장"을 선택했지만 아직 파일 finalize 콜백이 안 왔을 때 처리
    private var pendingSaveRequested: Boolean = false

    // finalize를 기다리는 동안 결과 이동을 보류하기 위한 상태 & 로딩 다이얼로그
    private var navigateAfterFinalizeScore: Float? = null
    private var waitingDialog: androidx.appcompat.app.AlertDialog? = null

    private val FINALIZE_TIMEOUT_MS = 10_000L  // 10초 대기 후 사용자 선택 유도
    private var finalizeTimeoutHandler: Handler? = null
    private var finalizeTimeoutRunnable: Runnable? = null

    private var pendingScoreForDialog: Float? = null
    private var pendingDeleteRequested: Boolean = false

    private var mediaPlayer: MediaPlayer? = null

    fun setVideoDuration(duration: Int) {
        videoDurationMs = duration
    }

    fun setMediaPlayer(mp: MediaPlayer) {
        this.mediaPlayer = mp
    }


    // ★ 추가: 영상 재생 완료 시 호출되는 루핑 처리
    fun handleVideoLooping() {
        Log.d("ChallengeSession", "handleVideoLooping 호출. 영상 재생 완료, 세션 종료 로직 실행.")

//
    }

    private fun setPlaybackSpeed(speed: Float) {
        val mp = mediaPlayer
        if (mp != null) {
            try {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                    val params = mp.playbackParams
                    params.speed = speed
                    mp.playbackParams = params
                    Log.d("VideoDebug", "Playback speed set to ${speed}x")
                } else {
                    Log.w("VideoDebug", "느리게 재생은 Android 6.0 이상에서 지원됩니다.")
                }
            } catch (e: Exception) {
                Log.e("VideoDebug", "재생 속도 설정 오류: ${e.message}")
            }
        }
    }

    fun start(averageScoreProvider: () -> Float, speed: Float) {
        if (ContextCompat.checkSelfPermission(
                activity, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e("ChallengeSession", "마이크 권한 없음 → 녹화 시작 불가")
            return
        }

        if (recordingStarted) {
            Log.w("ChallengeSession", "이미 recordingStarted=true, 중복 실행 방지")
            return
        }
        recordingStarted = true
        Log.d("ChallengeSession", "start() 진입 → 녹화 시작 준비 (고정 배속: $speed)")

        // Handler 선언 제거 (이제 클래스 멤버 'handler'를 사용합니다)
        totalVideoDuration1xMs = videoDurationMs.toLong() // 1. 1x 총 시간 저장
        scoreProvider = averageScoreProvider             // 2. 점수 계산 람다
        fixedPlaybackSpeed = speed
        setPlaybackSpeed(fixedPlaybackSpeed)


        // 1) 녹화 시작
        videoRecorder.startRecording { uri ->
            savedVideoUri = uri
            Log.d("ChallengeSession", "Finalize 도착, 저장 URI=$uri")

            // '저장 안함'이었고 finalize가 이제 왔다면 → 여기서 즉시 삭제
            if (pendingDeleteRequested) {
                Log.i("ChallengeSession", "[DEL] do scheduled delete at finalize: $uri")
                deleteLocalFile(uri)
                pendingDeleteRequested = false
                // 삭제만 하고 끝. 업로드/갤러리 반영/팝업 어떤 것도 하지 않음.
                cancelFinalizeTimeout()
                dismissWaitingDialog()
                return@startRecording
            }

            // finalize 시점에는 "사용자가 저장을 원한 경우"에만 업로드 트리거
            if (pendingSaveRequested) {
                pendingScore?.let { scoreForUpload ->
                    Log.d("ChallengeSession", "Finalize 이후 보류 저장 처리 → 업로드+갤러리")
                    exportToGallery(uri)               // 갤러리에 반영
                    uploadHistory(scoreForUpload, uri) // Firebase 업로드
                    pendingScore = null
                    pendingSaveRequested = false
                }
            }

            cancelFinalizeTimeout()

            pendingScoreForDialog?.let { score ->
                pendingScoreForDialog = null
                dismissWaitingDialog()
                showSaveDialog(score = score, videoUri = savedVideoUri)
                return@startRecording
            }

            // finalize를 기다린 뒤 이동하기로 되어 있었다면, 이제 이동
            navigateAfterFinalizeScore?.let { score ->
                Log.d("ChallengeSession", "Finalize 확인 → 결과 화면으로 이동")
                dismissWaitingDialog()
                moveToResultActivity(score, savedVideoUri)
                navigateAfterFinalizeScore = null
            }
        }

        // 2) 영상 재생 시작
        Log.d("ChallengeSession", "영상 재생 시작")
        videoView.start()
        challengeStartTimeMs = System.currentTimeMillis() // 3. 시작 시간 기록

        // 3) 영상 끝나면 정지 + 점수 계산
        handler.postDelayed({
            Log.d("ChallengeSession", "handler.postDelayed 실행 → 영상 종료 처리")
            videoView.pause()
            videoRecorder.stopRecording()
            (activity as? MainActivity)?.let { it.runOnUiThread { it.stopTracking() } }
            Log.d("ChallengeSession", "영상 일시정지 및 녹화 중지 호출")

            val averageScore = averageScoreProvider()
            Log.d("ChallengeSession", "점수 계산 완료 → averageScore=$averageScore")

            // 저장 여부 팝업 → 선택에 따라 업로드/미업로드 처리 후 결과 이동
            if (savedVideoUri != null) {
                showSaveDialog(score = averageScore, videoUri = savedVideoUri)
            } else {
                Log.d("ChallengeSession", "Finalize 미도착 → N초 대기 후 팝업")
                showWaitingDialog()
                pendingScoreForDialog = averageScore
                waitFinalizeThenShowDialog(averageScore)
            }
        },videoDurationMs.toLong())
    }

    private fun runChallengeEndLogic(averageScoreProvider: () -> Float) {
        challengeEndRunnable = null
        Log.e("ChallengeSession", "!!! TIMER EXECUTED !!! - SystemTime=${System.currentTimeMillis()}")
        videoView.pause()
        videoRecorder.stopRecording()
        Log.d("ChallengeSession", "영상 일시정지 및 녹화 중지 호출")

        val averageScore = averageScoreProvider()
        Log.d("ChallengeSession", "점수 계산 완료 → averageScore=$averageScore")

        // 저장 여부 팝업 → 선택에 따라 업로드/미업로드 처리 후 결과 이동
        if (savedVideoUri != null) {
            showSaveDialog(score = averageScore, videoUri = savedVideoUri)
        } else {
            Log.d("ChallengeSession", "Finalize 미도착 → N초 대기 후 팝업")
            showWaitingDialog()
            pendingScoreForDialog = averageScore
            waitFinalizeThenShowDialog(averageScore)
        }
    }

    // 속도 변경 시 호출되어 타이머를 취소하고 재설정
    private fun startChallengeEndTimer() {

        val speed = fixedPlaybackSpeed // ★ MODIFIED: currentPlaybackSpeed 대신 fixedPlaybackSpeed 사용 ★

        val averageScoreProvider = scoreProvider ?: return // provider 없으면 실행 불가

        // 1. 이전 러너블 취소
        challengeEndRunnable?.let {
            handler.removeCallbacks(it) // 멤버 변수 handler 사용
            Log.w("ChallengeSession", "PREV TIMER CANCELLED!") // 취소 성공 로그 추가
        }

        // 2. 경과 시간 및 남은 1x 시간 계산
        val elapsedTimeReal = System.currentTimeMillis() - challengeStartTimeMs
        // 1배속 기준으로 얼마나 시간이 흘렀는지 (실제 시간 * 현재 속도)
        val elapsed1xFloat = elapsedTimeReal * speed // ★ MODIFIED: speed 변수 사용 ★
        val remainingTime1x = (totalVideoDuration1xMs - elapsed1xFloat.toLong()).coerceAtLeast(0L) // coerceAtLeast(0L) 사용

        if (remainingTime1x <= 0L) {
            // 이미 종료되었어야 한다면 즉시 종료 로직 실행
            Log.d("ChallengeSession", "startChallengeEndTimer: 남은 시간 없음. 즉시 종료.")
            runChallengeEndLogic(averageScoreProvider)
            return
        }

        // 3. 남은 1x 시간을 새로운 속도로 나눈 실제 남은 시간 계산
        val actualRemainingDuration = (remainingTime1x.toFloat() / speed).toLong() // ★ MODIFIED: speed 변수 사용 ★

        Log.i("ChallengeSession", "!!! DIAG START (FIXED SPEED) !!!") // ★ MODIFIED
        Log.i("ChallengeSession", "현재 시스템 시간: ${System.currentTimeMillis()}")
        Log.i("ChallengeSession", "챌린지 시작 시간: $challengeStartTimeMs")
        Log.i("ChallengeSession", "실제 경과 시간: $elapsedTimeReal ms")
        Log.i("ChallengeSession", "1x 기준 총 시간: $totalVideoDuration1xMs ms")
        Log.i("ChallengeSession", "고정 속도: $speed x") // ★ MODIFIED
        Log.i("ChallengeSession", "계산된 예약 시간: $actualRemainingDuration ms") // 이 시간 후에 팝업이 떠야 함
        Log.i("ChallengeSession", "!!! DIAG END !!!")

        Log.i("ChallengeSession", "--- 타이머 설정 ---") // ★ MODIFIED: 재설정 → 설정
        Log.i("ChallengeSession", "경과된 1x 시간: ${elapsed1xFloat.toLong()} ms")
        Log.i("ChallengeSession", "남은 1x 시간: $remainingTime1x ms")
        Log.i("ChallengeSession", "고정된 재생 속도: $speed x") // ★ MODIFIED
        Log.i("ChallengeSession", "종료 예약 시간: $actualRemainingDuration ms") // ★ MODIFIED
        Log.i("ChallengeSession", "----------------------")


        // 4. 새로운 타이머 설정
        challengeEndRunnable = Runnable {
            Log.d("ChallengeSession", "handler.postDelayed 실행 → 영상 종료 처리")
            runChallengeEndLogic(averageScoreProvider)
        }

        // ★★★ 멤버 변수 handler 사용 ★★★
        handler.postDelayed(challengeEndRunnable!!, actualRemainingDuration)
    }

    // 저장 여부를 묻는 다이얼로그
    private fun showSaveDialog(score: Float, videoUri: Uri?) {
        // 영상 URI가 아직 없더라도(파일 finalize 미도착) 저장 선택시 보류 후 finalize에서 업로드
        AlertDialog.Builder(activity)
            .setTitle("히스토리에 저장할까요?")
            .setMessage("영상과 점수를 저장하면 마이페이지에서 다시 볼 수 있어요.")
            .setPositiveButton("저장") { _, _ ->
                Log.d("ChallengeSession", "사용자 선택: 저장")
                pendingSaveRequested = true
                pendingScore = score

                val uriNow = videoUri
                if (uriNow != null) {
                    // URI가 이미 있으면 즉시 업로드 후 바로 이동
                    Log.d("ChallengeSession", "URI 이미 존재 → 즉시 업로드")
                    exportToGallery(uriNow)          // 갤러리 반영
                    uploadHistory(score, uriNow)     // Firebase 업로드
                    pendingSaveRequested = false
                    pendingScore = null
                    moveToResultActivity(score, uriNow)
                } else {
                    // URI가 아직 없으면 finalize를 기다렸다가 이동
                    Log.d("ChallengeSession", "URI 아직 없음 → finalize 대기 후 업로드/이동")
                    showWaitingDialog()                           // 간단한 로딩 표시
                    navigateAfterFinalizeScore = score            //  finalize 후 이동하도록 예약

                }
            }
            .setNegativeButton("저장 안함") { _, _ ->
                Log.d("ChallengeSession", "사용자 선택: 저장 안함")
                // 저장 안 함 → 업로드 없이 결과 화면 이동
                pendingSaveRequested = false
                pendingScore = null
                val uriNow = savedVideoUri
                if (uriNow != null) {
                    Log.i("ChallengeSession", "[DEL] immediate delete request: $uriNow")
                    deleteLocalFile(uriNow)
                } else {
                    Log.i("ChallengeSession", "[DEL] schedule delete after finalize=true")
                    pendingDeleteRequested = true
                }
                moveToResultActivity(score, null)
            }
            .setCancelable(false)
            .show()
    }

    private fun moveToResultActivity(averageScore: Float, videoUri: Uri? = null) {
        Log.d("ChallengeSession", "ResultActivity 이동 → averageScore=$averageScore, videoUri=$videoUri")
        cancelFinalizeTimeout()
        val intent = Intent(activity, ResultActivity::class.java).apply {
            putExtra("averageScore", averageScore)
            videoUri?.let { putExtra("savedVideoUri", it.toString()) }
        }
        activity.runOnUiThread {
            if (!activity.isFinishing && !activity.isDestroyed) {
                activity.startActivity(intent)
                // activity.finish() // 결과 화면만 남기고 싶으면 사용
            }
        }
    }

    private fun uploadHistory(score: Float, uri: Uri) {
        Log.d("ChallengeSession", "uploadHistory() 호출 → score=$score, uri=$uri")
        HistoryRepository().saveHistoryInBackground(
            activity = activity,
            challengeId = challengeId,
            score = score,
            localVideoUri = uri
        )
    }

    // finalize를 기다리는 동안 사용자에게 알려주는 간단한 로딩 다이얼로그
    private fun showWaitingDialog() {
        if (!isActivityAlive()) {
            Log.w("ChallengeSession", "Activity not alive. Skip waiting dialog.")
            return
        }
        activity.runOnUiThread {
            if (!isActivityAlive()) return@runOnUiThread
            if (waitingDialog?.isShowing == true) return@runOnUiThread

            try {
                waitingDialog = AlertDialog.Builder(activity)
                    .setTitle("저장 준비 중")
                    .setMessage("영상 파일을 정리하고 있어요...")
                    .setCancelable(false)
                    .create()
                waitingDialog?.show()
            } catch (e: android.view.WindowManager.BadTokenException) {
                Log.w("ChallengeSession", "BadToken when showing waiting dialog: ${e.message}")
                waitingDialog = null
            }
        }
    }

    private fun dismissWaitingDialog() {
        activity.runOnUiThread {
            runCatching { waitingDialog?.dismiss() }
            waitingDialog = null
        }
    }

    // Finalize를 N초 기다렸다가, 없으면 사용자 선택 유도
    private fun waitFinalizeThenShowDialog(score: Float) {
        // 중복 예약 방지
        cancelFinalizeTimeout()

        finalizeTimeoutHandler = Handler(Looper.getMainLooper()).also { h ->
            finalizeTimeoutRunnable = Runnable {
                if (!isActivityAlive()) {
                    Log.w("ChallengeSession", "Activity dead at timeout. Skip dialogs.")
                    return@Runnable
                }

                // 타임아웃 시점에서의 URI 스냅샷
                val uriNow = savedVideoUri

                if (uriNow == null) {
                    // 아직 finalize 안 왔음 → 로딩 닫고 사용자에게 물어보기
                    dismissWaitingDialog()

                    activity.runOnUiThread {
                        if (!isActivityAlive()) return@runOnUiThread

                        try {
                            androidx.appcompat.app.AlertDialog.Builder(activity)
                                .setTitle("영상 저장이 지연돼요")
                                .setMessage("영상 파일 정리에 시간이 오래 걸리고 있어요. 결과 화면으로 먼저 이동할까요?")
                                .setPositiveButton("결과로 이동") { _, _ ->
                                    // 저장 보류 상태 초기화 후 결과 화면으로
                                    pendingSaveRequested = false
                                    pendingScore = null
                                    moveToResultActivity(score, null)
                                }
                                .setNegativeButton("계속 기다리기") { _, _ ->
                                    // 다시 대기 다이얼로그 보여주고, 타임아웃 재예약
                                    showWaitingDialog()
                                    waitFinalizeThenShowDialog(score)
                                }
                                .setCancelable(false)
                                .show()
                        } catch (e: android.view.WindowManager.BadTokenException) {
                            Log.w("ChallengeSession", "BadToken when showing timeout dialog: ${e.message}")
                        }
                    }
                } else {
                    // finalize가 이미 도착했음 → 저장/미저장 선택 다이얼로그로 진행
                    dismissWaitingDialog()
                    showSaveDialog(score, uriNow)
                }
            }.also { r ->
                h.postDelayed(r, FINALIZE_TIMEOUT_MS)
            }
        }
    }

    private fun cancelFinalizeTimeout() {
        finalizeTimeoutHandler?.removeCallbacks(finalizeTimeoutRunnable ?: return)
        finalizeTimeoutHandler = null
        finalizeTimeoutRunnable = null
    }

    // 임시파일을 갤러리에 “복사”하여 반영 (저장 선택 시)
    private fun exportToGallery(srcUri: Uri) {
        runCatching {
            val resolver = activity.contentResolver
            val name = "PC_${System.currentTimeMillis()}.mp4"
            val dest = resolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/PerfectChallenge")
                        put(MediaStore.Video.Media.IS_PENDING, 1)
                    }
                }
            ) ?: return
            resolver.openOutputStream(dest)?.use { out ->
                resolver.openInputStream(srcUri)?.use { it.copyTo(out) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val v = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                resolver.update(dest, v, null, null)
            }
            Log.d("ChallengeSession", "갤러리 반영 완료: $dest")
        }.onFailure {
            Log.w("ChallengeSession", "갤러리 반영 실패: ${it.message}")
        }
    }

    // “저장 안함” 시 임시파일 삭제
    private fun deleteLocalFile(uri: Uri) {
        try {
            val tag = "ChallengeSession"
            Log.i(tag, "[DEL] start: scheme=${uri.scheme}, uri=$uri")

            if (uri.scheme == "file") {
                val path = uri.path!!
                val f = File(path)
                val existedBefore = f.exists()
                val lenBefore = if (existedBefore) f.length() else -1L
                Log.i(tag, "[DEL] file:// before exists=$existedBefore, size=$lenBefore, path=$path")

                val ok = f.delete()

                val existedAfter = f.exists()
                Log.i(tag, "[DEL] file:// delete()=$ok, after exists=$existedAfter, path=$path")
            } else {
                val rows = activity.contentResolver.delete(uri, null, null)
                Log.i(tag, "[DEL] content:// delete rows=$rows, uri=$uri")
            }
        } catch (t: Throwable) {
            Log.w("ChallengeSession", "[DEL] deleteLocalFile error: ${t.message}", t)
        }
    }

    fun release() {
        challengeEndRunnable?.let { handler.removeCallbacks(it) }
        cancelFinalizeTimeout()
        dismissWaitingDialog()  // ← 추가
        Log.d("ChallengeSession", "ChallengeSession: All handlers and resources released.")
    }
}

