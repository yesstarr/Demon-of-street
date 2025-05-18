package com.ooplab.exercises_fitfuel

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity


class MyPageActivity : AppCompatActivity() {

    private val repository = AuthRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mypage)

        repository.getCurrentUserInfo { userInfo ->
            if (userInfo != null) {
                findViewById<TextView>(R.id.tvName).text = "이름: ${userInfo["name"]}"
                findViewById<TextView>(R.id.tvNickname).text = "닉네임: ${userInfo["nickname"]}"
                findViewById<TextView>(R.id.tvEmail).text = "이메일: ${userInfo["email"]}"
                findViewById<TextView>(R.id.tvGrade).text = "등급: ${userInfo["grade"]}"
            } else {
                showToast("사용자 정보가 없습니다.")
            }
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}