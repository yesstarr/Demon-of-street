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
    private var savedVideoUri: Uri? = null
    private var pendingScore: Float? = null
    private var recordingStarted = false
    private var videoDurationMs: Int = 0

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


    fun setVideoDuration(duration: Int) {
        videoDurationMs = duration
    }

    fun start(averageScoreProvider: () -> Float) {
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
        Log.d("ChallengeSession", "start() 진입 → 녹화 시작 준비")

        val handler = Handler(Looper.getMainLooper())


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
                moveToResultActivity(score)
                navigateAfterFinalizeScore = null
            }
        }

        // 2) 영상 재생 시작
        Log.d("ChallengeSession", "영상 재생 시작")
        videoView.start()

        // 3) 영상 끝나면 정지 + 점수 계산
        handler.postDelayed({
            Log.d("ChallengeSession", "handler.postDelayed 실행 → 영상 종료 처리")
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
        },videoDurationMs.toLong())
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
                    moveToResultActivity(score)
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
                moveToResultActivity(score)
            }
            .setCancelable(false)
            .show()
    }

    private fun moveToResultActivity(averageScore: Float) {
        Log.d("ChallengeSession", "ResultActivity 이동 → averageScore=$averageScore")
        val intent = Intent(activity, ResultActivity::class.java).apply {
            putExtra("averageScore", averageScore)
        }
        activity.startActivity(intent)
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
        if (waitingDialog?.isShowing == true) return
        waitingDialog = androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle("저장 준비 중")
            .setMessage("영상 파일을 정리하고 있어요...")
            .setCancelable(false)
            .create()
        waitingDialog?.show()
    }

    private fun dismissWaitingDialog() {
        runCatching { waitingDialog?.dismiss() }
        waitingDialog = null
    }

    // Finalize를 N초 기다렸다가, 없으면 사용자 선택 유도
    private fun waitFinalizeThenShowDialog(score: Float) {
        finalizeTimeoutHandler = Handler(Looper.getMainLooper()).also { h ->
            finalizeTimeoutRunnable = Runnable {
                if (savedVideoUri == null) {
                    dismissWaitingDialog()
                    AlertDialog.Builder(activity)
                        .setTitle("영상 저장이 지연돼요")
                        .setMessage("영상 파일 정리에 시간이 오래 걸리고 있어요. 결과 화면으로 먼저 이동할까요?")
                        .setPositiveButton("결과로 이동") { _, _ ->
                            pendingSaveRequested = false
                            pendingScore = null
                            moveToResultActivity(score)
                        }
                        .setNegativeButton("계속 기다리기") { _, _ ->
                            showWaitingDialog()
                            waitFinalizeThenShowDialog(score)
                        }
                        .setCancelable(false)
                        .show()
                } else {
                    dismissWaitingDialog()
                    showSaveDialog(score, savedVideoUri)
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
}

