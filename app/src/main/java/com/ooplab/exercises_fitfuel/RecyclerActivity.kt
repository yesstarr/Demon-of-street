package com.ooplab.exercises_fitfuel

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class RecyclerActivity : AppCompatActivity() {

    private val repository = AuthRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recycler)

        val recyclerView = findViewById<RecyclerView>(R.id.challengeRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

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