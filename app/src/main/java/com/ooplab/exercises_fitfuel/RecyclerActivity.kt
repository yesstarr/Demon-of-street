package com.ooplab.exercises_fitfuel

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup // RadioGroup import 추가
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class RecyclerActivity : AppCompatActivity() {

    private val repository = AuthRepository()
    // ★ NEW: RadioGroup 멤버 변수 추가 (onCreate에서 초기화)
    private lateinit var speedRadioGroup: RadioGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // R.layout.activity_recycler이 R.layout.activity_challenge_select와 동일하다고 가정
        setContentView(R.layout.activity_recycler)

        val recyclerView = findViewById<RecyclerView>(R.id.challengeRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        // ★ NEW: RadioGroup 초기화
        speedRadioGroup = findViewById(R.id.speedRadioGroup)

        repository.loadChallengeMetaList(
            onSuccess = { challengeList ->
                val adapter = ChallengeAdapter(this, challengeList)

                // ★ NEW: Adapter의 챌린지 시작 리스너 정의 (여기서 배속을 읽어 전달)
                adapter.onStartChallengeClickListener = { challengeMeta ->

                    // 1. 선택된 배속 값 확인 (RadioGroup에서 읽어옴)
                    val selectedSpeed = getSelectedSpeedFromRadioGroup()

                    // 2. MainActivity 시작 및 모든 정보 전달
                    val intent = Intent(this, MainActivity::class.java).apply {
                        putExtra("challengeId", challengeMeta.challengeId)
                        putExtra("videoUrl", challengeMeta.videoUrl)

                        // ★ 핵심: 선택된 배속 값(Float)을 인텐트에 추가
                        putExtra("selected_speed", selectedSpeed)
                    }
                    startActivity(intent)
                }

                recyclerView.adapter = adapter
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

    // ★ NEW: 선택된 배속 값을 읽는 유틸리티 함수
    private fun getSelectedSpeedFromRadioGroup(): Float {
        val checkedId = speedRadioGroup.checkedRadioButtonId

        // 기본값은 1.0x로 설정합니다.
        if (checkedId != -1) {
            val selectedRadioButton = findViewById<RadioButton>(checkedId)
            // XML에서 tag="0.25", tag="1.0" 등으로 설정한 값을 읽어옵니다.
            return selectedRadioButton.tag?.toString()?.toFloatOrNull() ?: 1.0f
        }
        return 1.0f
    }
}