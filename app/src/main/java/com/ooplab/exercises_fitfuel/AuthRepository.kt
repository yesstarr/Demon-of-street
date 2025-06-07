package com.ooplab.exercises_fitfuel

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

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
}