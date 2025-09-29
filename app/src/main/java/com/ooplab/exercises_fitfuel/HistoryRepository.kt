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

class HistoryRepository {
    // Firestore에서 히스토리 불러오기
    fun loadPlayHistory(
        onSuccess: (List<Map<String, Any>>) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val db = FirebaseFirestore.getInstance()

        Log.d("HistoryRepository", "loadPlayHistory 호출 → uid=$uid")

        db.collection("users").document(uid)
            .collection("history")
            .orderBy("playedAt", Query.Direction.DESCENDING)
            .get()
            .addOnSuccessListener { result ->
                val historyList = result.documents.mapNotNull { doc ->
                    doc.data?.toMutableMap()?.apply { this["playId"] = doc.id }
                }
                Log.d("HistoryRepository", "히스토리 로드 성공 → count=${historyList.size}")
                onSuccess(historyList)
            }
            .addOnFailureListener { e ->
                Log.e("HistoryRepository", "히스토리 로드 실패", e)
                onError(e)
            }

    }

    // 백그라운드 저장 (ResultActivity로 바로 이동 후 비동기 업로드)
    fun saveHistoryInBackground(
        activity: AppCompatActivity,
        challengeId: String,
        score: Float,
        localVideoUri: Uri
    ) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: run {
            Log.e("HistoryRepository", "saveHistoryInBackground: no auth uid")
            return
        }

        val storage = FirebaseStorage.getInstance().reference
        val playId = System.currentTimeMillis().toString()
        val videoRef = storage.child("user_videos/$uid/$playId.mp4")

        Log.d(
            "HistoryRepository",
            "saveHistoryInBackground 시작 → uid=$uid, playId=$playId, uri=$localVideoUri"
        )

        videoRef.putFile(localVideoUri)
            .continueWithTask { task ->
                if (!task.isSuccessful) {
                    Log.e("HistoryRepository", "Storage 업로드 실패", task.exception)
                    throw task.exception ?: Exception("Upload failed")
                }
                Log.d("HistoryRepository", "Storage 업로드 성공 → downloadUrl 요청")
                videoRef.downloadUrl
            }
            .addOnSuccessListener { downloadUrl ->
                Log.d("HistoryRepository", "downloadUrl=$downloadUrl")
                val data = hashMapOf(
                    "challengeId" to challengeId,
                    "score" to score,
                    "videoUrl" to downloadUrl.toString(),
                    "playedAt" to Timestamp.now()
                )
                Log.d("HistoryRepository", "Firestore 저장 시도 → $data")

                FirebaseFirestore.getInstance()
                    .collection("users").document(uid)
                    .collection("history").document(playId)
                    .set(data)
                    .addOnSuccessListener {
                        Log.d("HistoryRepository", "Firestore 저장 완료 → playId=$playId")
                    }
                    .addOnFailureListener { e ->
                        Log.e("HistoryRepository", "Firestore 저장 실패", e)
                    }
            }
            .addOnFailureListener { e ->
                Log.e("HistoryRepository", "downloadUrl 가져오기 실패", e)
            }
            .addOnCompleteListener {
                val tag = "HistoryRepository"
                Log.i(tag, "[DEL] onComplete, uri=$localVideoUri, scheme=${localVideoUri.scheme}")

                runCatching {
                    if (localVideoUri.scheme == "file") {
                        val path = localVideoUri.path!!
                        val f = java.io.File(path)
                        val existedBefore = f.exists()
                        val sizeBefore = if (existedBefore) f.length() else -1L
                        Log.i(tag, "[DEL] file:// before exists=$existedBefore, size=$sizeBefore, path=$path")

                        val ok = f.delete()

                        val existedAfter = f.exists()
                        Log.i(tag, "[DEL] file:// delete()=$ok, after exists=$existedAfter, path=$path")
                    } else {
                        val rows = activity.applicationContext
                            .contentResolver
                            .delete(localVideoUri, null, null)
                        Log.i(tag, "[DEL] content:// delete rows=$rows, uri=$localVideoUri")
                    }
                }.onFailure { er ->
                    Log.w(tag, "[DEL] delete failed: ${er.message}", er)
                }
            }
    }

    // 히스토리 항목 삭제 (Storage → Firestore)
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

        // Storage 참조 복원 (downloadUrl로도 참조 가능)
        val storageRef = try {
            storage.getReferenceFromUrl(videoUrl)
        } catch (e: Exception) {
            Log.w("HistoryRepository", "getReferenceFromUrl 실패: ${e.message} (Firestore만 삭제 시도)")
            null
        }

        val deleteStorageTask = storageRef?.delete()
            ?: com.google.android.gms.tasks.Tasks.forResult(null)

        deleteStorageTask
            .addOnSuccessListener { Log.d("HistoryRepository", "Storage 삭제 완료: $videoUrl") }
            .addOnFailureListener { e -> Log.w("HistoryRepository", "Storage 삭제 실패(계속 진행): ${e.message}") }
            .continueWithTask {
                db.collection("users").document(uid)
                    .collection("history").document(playId)
                    .delete()
            }
            .addOnSuccessListener {
                Log.d("HistoryRepository", "Firestore 삭제 완료: playId=$playId")
                onComplete(true, null)
            }
            .addOnFailureListener { e ->
                Log.e("HistoryRepository", "Firestore 삭제 실패", e)
                onComplete(false, e.message)
            }
    }
}

