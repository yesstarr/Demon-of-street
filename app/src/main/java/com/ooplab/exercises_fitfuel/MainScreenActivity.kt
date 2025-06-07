package com.ooplab.exercises_fitfuel

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainScreenActivity : AppCompatActivity() {
    private val repository = AuthRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mainscreen)

        val recyclerView = findViewById<RecyclerView>(R.id.challengeRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        // 마이페이지 버튼
        val moveMyPageButton = findViewById<Button>(R.id.moveMyPageButton)
        moveMyPageButton.setOnClickListener {
            val intent = Intent(this, MyPageActivity::class.java)
            startActivity(intent)
        }

        // Firestore에서 ChallengeMeta 리스트 불러오기
        repository.loadChallengeMetaList(
            onSuccess = { challengeList ->
                recyclerView.adapter = ChallengeAdapter(this, challengeList)
            },
            onError = { e ->
                Log.e("ChallengeMetaLoad", "Failed to load challenge meta list: ${e.message}")
            }
        )
    }
}