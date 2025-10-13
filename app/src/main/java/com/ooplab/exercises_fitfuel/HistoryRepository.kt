package com.ooplab.exercises_fitfuel

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.ktx.Firebase
import com.google.firebase.functions.ktx.functions
import com.google.firebase.firestore.SetOptions
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.firestore.FieldValue
import com.google.android.gms.tasks.Tasks
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.ByteArrayOutputStream

class HistoryRepository {

    // 히스토리 불러오기 (마이페이지)
    fun loadPlayHistory(
        onSuccess: (List<Map<String, Any>>) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            onError(IllegalStateException("Not signed in"))
            return
        }
        FirebaseFirestore.getInstance()
            .collection("users").document(uid)
            .collection("history")
            .orderBy("playedAt", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { result ->
                // 각 문서에 playId, publicVideoId 포함
                val list = result.documents.mapNotNull { doc ->
                    val data = doc.data?.toMutableMap() ?: return@mapNotNull null
                    data["playId"] = doc.id
                    data["publicVideoId"] = doc.getString("publicVideoId")
                    data
                }
                onSuccess(list)
            }
            .addOnFailureListener { e ->
                Log.e("HistoryRepository", "히스토리 로드 실패", e)
                onError(e)
            }
    }

    /**
     * 백그라운드 저장 (Result 화면으로 바로 이동하고 비동기 업로드/기록)
     * - Storage: user_videos/{uid}/{playId}.mp4
     * - Firestore: users/{uid}/history/{playId}
     */
    fun saveHistoryInBackground(
        activity: AppCompatActivity,
        challengeId: String,
        score: Float,
        localVideoUri: Uri
    ) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) {
            Log.e("HistoryRepository", "saveHistoryInBackground: no auth")
            return
        }

        val db = FirebaseFirestore.getInstance()
        val storage = FirebaseStorage.getInstance()
        val playId = System.currentTimeMillis().toString() // 고유 ID (timestamp)

        // ★ CHANGED: Firestore 문서 ‘선 생성’ (status=uploading)
        val histRef = db.collection("users").document(uid).collection("history").document(playId)
        val preData = hashMapOf(
            "challengeId" to challengeId,
            "score" to score.toDouble(),
            "playedAt" to FieldValue.serverTimestamp(),
            "status" to "uploading"  // ★ 업로드 진행중 배지용
        )
        histRef.set(preData, SetOptions.merge())
            .addOnFailureListener { e -> Log.w("HistoryRepository", "선기록 실패(무시 가능): ${e.message}") }


        // Storage 경로 (CF publish에서 복사할 때 사용)
        val videoPath = "user_videos/$uid/$playId.mp4"
        val videoRef = storage.reference.child(videoPath)

        // ★ CHANGED: 메타데이터 지정(일부 단말 contentType 누락 대응)
        val metadata = StorageMetadata.Builder()
            .setContentType("video/mp4")
            .build()

        // ★ CHANGED: 체이닝을 단일 Task로 구성
        val task = videoRef.putFile(localVideoUri, metadata)
            .continueWithTask { putTask ->
                if (!putTask.isSuccessful) {
                    throw putTask.exception ?: RuntimeException("Upload failed")
                }
                // 업로드 성공 → videoUrl 획득
                videoRef.downloadUrl
            }
            .continueWithTask { urlTask ->
                val downloadUrl = urlTask.result?.toString() ?: ""
                // ★ CHANGED: Firestore merge 업데이트 (status=ready, videoUrl, videoPath)
                val data = hashMapOf(
                    "videoUrl" to downloadUrl,
                    "videoPath" to videoPath,
                    "status" to "ready"
                )
                histRef.set(data, SetOptions.merge())
            }
            .addOnSuccessListener {
                Log.d("HistoryRepository", "히스토리 저장 완료(ready)")
                // 2) ★ 썸네일 생성 → 업로드 → thumbPath merge
                createAndUploadThumb(activity, localVideoUri, uid, playId) { thumbPath ->
                    if (!thumbPath.isNullOrBlank()) {
                        histRef.set(mapOf("thumbPath" to thumbPath), SetOptions.merge())
                            .addOnSuccessListener {
                                Log.d("HistoryRepository", "thumbPath 기록 완료: $thumbPath")
                            }
                            .addOnFailureListener { e ->
                                Log.w("HistoryRepository", "thumbPath 기록 실패: ${e.message}")
                            }
                    } else {
                        Log.w("HistoryRepository", "썸네일 생성/업로드 실패 또는 null")
                    }
                }
            }
            .addOnFailureListener { e ->
                // ★ 추가: Storage 에러코드/권한 문제를 바로 확인
                Log.e("HistoryRepository", "히스토리 저장 실패: ${e?.message}", e)
                Log.e("HistoryRepository", "업로드 후 URL 획득 실패: ${e?.message}", e)
            }

        // ★ CHANGED: 실패/성공 로그(백그라운드에서라도 원인 파악)
        task.addOnSuccessListener {
            Log.d("HistoryRepository", "히스토리 저장 완료(merge): $playId")
        }.addOnFailureListener { e ->
            Log.e("HistoryRepository", "히스토리 저장 실패(체인): ${e.message}", e)
            // 실패해도 UX는 유지, 사용자가 마이페이지를 열면 status=uploading 기록은 보임
        }

        // 결과 화면 즉시 이동 (기존 UX 유지)
        activity.startActivity(
            Intent(activity, ResultActivity::class.java).apply {
                putExtra("challengeId", challengeId)
                putExtra("score", score)
                putExtra("playId", playId)
            }
        )
    }

    /**
     * 히스토리 항목 삭제
     * - Storage(영상/썸네일) 삭제 후 Firestore 문서 삭제
     * - URL 기반 삭제가 실패하는 경우가 있어 경로(videoPath/ thumbPath) 우선 사용
     */
    fun deleteHistoryItem(
        activity: AppCompatActivity,
        playId: String,
        videoUrl: String,
        onComplete: (Boolean, String?) -> Unit
    ) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) { onComplete(false, "Not signed in"); return }

        val db = FirebaseFirestore.getInstance()
        val storage = FirebaseStorage.getInstance()
        val docRef = db.collection("users").document(uid)
            .collection("history").document(playId)

        docRef.get()
            .addOnSuccessListener { snap ->
                val videoPath = snap.getString("videoPath")   // 예: user_videos/{uid}/{playId}.mp4
                val thumbPath = snap.getString("thumbPath")   // 있을 수도/없을 수도

                val tasks = mutableListOf<com.google.android.gms.tasks.Task<*>>()

                // Storage 삭제 (경로 우선)
                if (!videoPath.isNullOrBlank()) {
                    tasks += storage.reference.child(videoPath).delete()
                        .addOnSuccessListener { Log.d("HistoryRepository", "Storage 삭제 완료(path): $videoPath") }
                        .addOnFailureListener { e -> Log.w("HistoryRepository", "Storage 삭제 실패(path): ${e.message}") }
                } else {
                    // 경로 없으면 URL로 시도 (서명/형식 문제로 실패할 수 있음)
                    try {
                        val refFromUrl = storage.getReferenceFromUrl(videoUrl)
                        tasks += refFromUrl.delete()
                            .addOnSuccessListener { Log.d("HistoryRepository", "Storage 삭제 완료(url)") }
                            .addOnFailureListener { e -> Log.w("HistoryRepository", "Storage 삭제 실패(url): ${e.message}") }
                    } catch (e: Exception) {
                        Log.w("HistoryRepository", "getReferenceFromUrl 실패: ${e.message}")
                    }
                }

                if (!thumbPath.isNullOrBlank()) {
                    tasks += storage.reference.child(thumbPath).delete()
                        .addOnFailureListener { /* ignore */ }
                }

                // Storage 삭제 후 Firestore 문서 삭제
                com.google.android.gms.tasks.Tasks.whenAllComplete(tasks)
                    .addOnCompleteListener {
                        docRef.delete()
                            .addOnSuccessListener {
                                Log.d("HistoryRepository", "Firestore 삭제 완료: $playId")
                                onComplete(true, null)
                            }
                            .addOnFailureListener { e ->
                                Log.e("HistoryRepository", "Firestore 삭제 실패", e)
                                onComplete(false, e.message)
                            }
                    }
            }
            .addOnFailureListener { e ->
                Log.w("HistoryRepository", "문서 조회 실패(문서만 삭제 시도): ${e.message}")
                docRef.delete()
                    .addOnSuccessListener { onComplete(true, null) }
                    .addOnFailureListener { err -> onComplete(false, err.message) }
            }
    }

    /**
     * 랭킹에 올리기 (Cloud Functions 호출)
     * - functions: publishToRanking (asia-northeast3)
     */
    fun publishToRanking(
        playId: String,
        onComplete: (ok: Boolean, publicVideoId: String?, err: String?) -> Unit
    ) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) { onComplete(false, null, "Not signed in"); return }

        Firebase.functions("asia-northeast3")
            .getHttpsCallable("publishToRanking")
            .call(mapOf("playId" to playId))
            .addOnSuccessListener { res ->
                val data = res.data as? Map<*, *>
                val pubId = data?.get("publicVideoId") as? String
                if (!pubId.isNullOrBlank()) onComplete(true, pubId, null)
                else onComplete(false, null, "No publicVideoId returned")
            }
            .addOnFailureListener { e ->
                onComplete(false, null, e.message)
            }
    }

    /**
     * 랭킹에서 내리기 (Cloud Functions 호출)
     * - functions: unpublishFromRanking (asia-northeast3)
     */
    fun unpublishFromRanking(
        playId: String,              // 호출자 시그니처 유지(내부에선 사용 안 함)
        publicVideoId: String,
        onComplete: (ok: Boolean, err: String?) -> Unit
    ) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid == null) { onComplete(false, "Not signed in"); return }

        Firebase.functions("asia-northeast3")
            .getHttpsCallable("unpublishFromRanking")
            .call(mapOf("publicVideoId" to publicVideoId, "deleteFiles" to false))
            .addOnSuccessListener {
                onComplete(true, null)
            }
            .addOnFailureListener { e ->
                onComplete(false, e.message)
            }
    }

    // ---------- 내부 유틸: 썸네일 생성/업로드 ----------
    private fun createAndUploadThumb(
        activity: AppCompatActivity,
        localVideoUri: Uri,
        uid: String,
        playId: String,
        onDone: (thumbPath: String?) -> Unit
    ) {
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(activity, localVideoUri)
            val frame: Bitmap? = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST)
            retriever.release()

            if (frame == null) { onDone(null); return }

            val baos = ByteArrayOutputStream()
            frame.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            val bytes = baos.toByteArray()

            val path = "user_thumbs/$uid/$playId.jpg"
            val ref = FirebaseStorage.getInstance().reference.child(path)
            val meta = StorageMetadata.Builder().setContentType("image/jpeg").build()

            ref.putBytes(bytes, meta)
                .addOnSuccessListener { onDone(path) }
                .addOnFailureListener { e ->
                    Log.w("HistoryRepository", "썸네일 업로드 실패: ${e.message}")
                    onDone(null)
                }
        } catch (e: Exception) {
            Log.w("HistoryRepository", "썸네일 생성 실패: ${e.message}")
            onDone(null)
        }
    }
}