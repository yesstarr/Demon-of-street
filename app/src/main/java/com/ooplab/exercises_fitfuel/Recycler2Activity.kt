package com.ooplab.exercises_fitfuel

import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class Recycler2Activity : AppCompatActivity() {

    private val repository = AuthRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recycler2)

        val recyclerView = findViewById<RecyclerView>(R.id.challengeRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        val mode = intent.getStringExtra("mode") ?: "CHALLENGE"

        repository.loadChallengeMetaList(
            onSuccess = { challengeList ->
                recyclerView.adapter = ChallengeAdapter(this, challengeList, mode)
            },
            onError = { e ->
                Log.e("ChallengeMetaLoad", "Failed to load challenge meta list: ${e.message}")
            }
        )

        val btnConfirm = findViewById<Button>(R.id.btnConfirm)
        btnConfirm.setOnClickListener {
            finish()
        }
    }
}