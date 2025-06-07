package com.ooplab.exercises_fitfuel

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.ktx.storage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AuthRepository {

    private val auth = FirebaseAuth.getInstance()
    private val db = FirebaseFirestore.getInstance()

    // 로그인 기능
    fun login(email: String, password: String, onResult: (Boolean, String?) -> Unit) {
        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    auth.currentUser?.getIdToken(true)?.addOnCompleteListener { tokenTask ->
                        if (tokenTask.isSuccessful) {
                            onResult(true, tokenTask.result?.token)
                        } else {
                            onResult(false, tokenTask.exception?.message)
                        }
                    }
                } else {
                    onResult(false, task.exception?.message)
                }
            }
    }

    // 회원가입 기능
    fun signUp(nickname: String, name: String, email: String, password: String, onResult: (Boolean, String?) -> Unit) {
        db.collection("users")
            .whereEqualTo("nickname", nickname)
            .get()
            .addOnSuccessListener { querySnapshot ->
                if (querySnapshot.isEmpty) {
                    auth.createUserWithEmailAndPassword(email, password)
                        .addOnCompleteListener { task ->
                            if (task.isSuccessful) {
                                val userId = auth.currentUser?.uid ?: ""
                                val user = hashMapOf(
                                    "nickname" to nickname,
                                    "name" to name,
                                    "email" to email,
                                    "grade" to "브론즈"
                                )
                                db.collection("users").document(userId).set(user)
                                onResult(true, null)
                            } else {
                                onResult(false, task.exception?.message)
                            }
                        }
                } else {
                    onResult(false, "이미 사용 중인 닉네임입니다.")
                }
            }
            .addOnFailureListener { e ->
                onResult(false, e.message)
            }
    }

    //마이페이지 정보 불러오기 기능
    fun getCurrentUserInfo(onResult: (Map<String, String>?) -> Unit) {
        val uid = auth.currentUser?.uid
        if (uid != null) {
            db.collection("users").document(uid)
                .get()
                .addOnSuccessListener { document ->
                    if (document.exists()) {
                        val userInfo = mapOf(
                            "name" to document.getString("name").orEmpty(),
                            "nickname" to document.getString("nickname").orEmpty(),
                            "email" to document.getString("email").orEmpty(),
                            "grade" to document.getString("grade").orEmpty()
                        )
                        onResult(userInfo)
                    } else {
                        onResult(null)
                    }
                }
                .addOnFailureListener {
                    onResult(null)
                }
        } else {
            onResult(null)
        }
    }

    //원본 관절 좌표 csv 파일 가져오기
    fun loadCsvFromFirebaseStream(
    challengeId: String,
    onLoaded: (List<List<Float>>) -> Unit,
    onError: (Exception) -> Unit
    ) {
        val fileRef = Firebase.storage.reference.child("motion_data/$challengeId.csv")

        // Firebase Storage에서 파일 스트림 요청
        fileRef.stream
            .addOnSuccessListener { streamTask ->

                // ⚠️ 네트워크 + 파일 읽기 → 메인 스레드에서 하면 안됨 → IO 스레드로 이동
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        // 스트림에서 전체 텍스트 읽기
                        val reader = streamTask.stream.bufferedReader()
                        val csvString = reader.readText()

                        // 다시 메인 스레드로 복귀해서 콜백 호출
                        withContext(Dispatchers.Main) {
                            try {
                                //파싱 로직 위임
                                val frames = MotionCsvParser.parse(csvString)
                                onLoaded(frames)
                            } catch (e: Exception) {
                                Log.e("CSV", "파싱 오류: ${e.message}", e)
                                onError(e)
                            }
                        }

                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Log.e("CSV", "CSV 읽기 중 예외 발생: ${e.message}", e)
                            onError(e)
                        }
                    }
                }
            }
            .addOnFailureListener { exception ->
                // Firebase에서 파일 스트림 자체를 못 가져온 경우
                Log.e("CSV", "Firebase 파일 스트림 실패: ${exception.message}", exception)
                onError(exception)
            }
    }

    //해당 challengeId의 mp4 영상 downloadUrl을 가져오는 함수
    fun loadVideoUrl(
        challengeId: String,
        onSuccess: (String) -> Unit,      // 성공 시 URL 반환
        onError: (Exception) -> Unit      // 실패 시 예외 반환
    ) {
        // Storage 경로: videos/{challengeId}.mp4
        val fileRef = Firebase.storage.reference.child("challenge_video/$challengeId.mp4")

        // downloadUrl 요청
        fileRef.downloadUrl
            .addOnSuccessListener { uri ->
                // 성공 시 URL 문자열 반환
                onSuccess(uri.toString())
            }
            .addOnFailureListener { exception ->
                // 실패 시 에러 반환
                onError(exception)
            }
    }

    // Firestore에서 ChallengeMeta 리스트 가져오기
    fun loadChallengeMetaList(
        onSuccess: (List<ChallengeMeta>) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val db = FirebaseFirestore.getInstance()

        db.collection("challenge_metadata")
            .get()
            .addOnSuccessListener { querySnapshot ->
                val challengeList = querySnapshot.documents.mapNotNull { document ->
                    try {
                        val challengeId = document.getString("challengeId")?.trim() ?: return@mapNotNull null
                        val title = document.getString("title") ?: return@mapNotNull null
                        val videoUrl = document.getString("videoUrl") ?: return@mapNotNull null
                        val thumbnailUrl = document.getString("thumbnailUrl") ?: return@mapNotNull null
                        ChallengeMeta(challengeId, title, videoUrl, thumbnailUrl)
                    } catch (e: Exception) {
                        Log.e("ChallengeMetaLoad", "Error parsing document: ${e.message}")
                        null
                    }
                }

                onSuccess(challengeList)
            }
            .addOnFailureListener { e ->
                onError(e)
            }
    }
}