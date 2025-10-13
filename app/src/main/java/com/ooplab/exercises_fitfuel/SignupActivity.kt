package com.ooplab.exercises_fitfuel

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.ooplab.exercises_fitfuel.databinding.ActivitySignupBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.util.Locale

class SignupActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySignupBinding
    private val repository = AuthRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySignupBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnSignup.setOnClickListener {
            val nickname = binding.etNickname.text.toString().trim()
            val name = binding.etName.text.toString().trim()
            val email = binding.etEmail.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()

            if (nickname.length !in 2..20) {
                showToast("닉네임은 2~20자로 입력해주세요.")
                return@setOnClickListener
            }
            if (name.isEmpty() || email.isEmpty() || password.length < 6) {
                showToast("이름/이메일/비밀번호(6자 이상)를 확인해주세요.")
                return@setOnClickListener
            }

            // 1) 기존 로직대로 회원가입 (AuthRepository 내부에서 createUser 등 수행)
            repository.signUp(nickname, name, email, password) { success, message ->
                if (!success) {
                    showToast("회원가입 실패: $message")
                    return@signUp
                }

                // 2) 닉네임 토큰 선점 → users 문서 작성(merge)
                val uid = FirebaseAuth.getInstance().currentUser?.uid
                if (uid.isNullOrBlank()) {
                    showToast("회원 정보 확인 중 오류가 발생했어요.")
                    return@signUp
                }

                val db = FirebaseFirestore.getInstance()
                val norm = nickname.lowercase(Locale.ROOT)
                val nickRef = db.collection("nicknames").document(norm)

                // 닉네임 토큰 생성(동시성: 이미 있으면 실패)
                nickRef.set(
                    mapOf(
                        "ownerUid" to uid,
                        "displayName" to nickname, // 원문 보존
                        "norm" to norm,
                        "reservedAt" to FieldValue.serverTimestamp()
                    )
                ).addOnSuccessListener {
                    // users/{uid} merge (규칙: 소유 토큰 확인)
                    val userRef = db.collection("users").document(uid)
                    userRef.set(
                        mapOf(
                            "uid" to uid,
                            "name" to name,
                            "email" to email,
                            "nickname" to nickname,
                            "nicknameNorm" to norm,
                            "createdAt" to FieldValue.serverTimestamp()
                        ),
                        SetOptions.merge()
                    ).addOnSuccessListener {
                        showToast("회원가입 성공!")
                        finish()
                    }.addOnFailureListener { e ->
                        // users 쓰기 실패 시 토큰 롤백
                        nickRef.delete()
                        showToast("프로필 저장 실패: ${e.message}")
                    }
                }.addOnFailureListener {
                    // 이미 사용 중이거나 권한 문제
                    showToast("이미 사용 중인 닉네임입니다.")
                }
            }
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}