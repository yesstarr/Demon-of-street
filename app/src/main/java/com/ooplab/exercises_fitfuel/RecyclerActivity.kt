package com.ooplab.exercises_fitfuel

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View // View import 추가
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView


class RecyclerActivity : AppCompatActivity() {

    private val repository = AuthRepository()
    private lateinit var speedRadioGroup: RadioGroup
    private lateinit var speedSelectionLabel: TextView

    // ★ NEW: 모드 상태를 저장할 변수 추가 ★
    private var isPracticeMode: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recycler)

        // 1. ★ Intent에서 모드 정보 수신 ★
        isPracticeMode = intent.getBooleanExtra("IS_PRACTICE_MODE", true)

        val recyclerView = findViewById<RecyclerView>(R.id.challengeRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        speedRadioGroup = findViewById(R.id.speedRadioGroup)
        speedSelectionLabel = findViewById(R.id.speedSelectionLabel) // TextView 초기화

        // 2. ★ 핵심 로직: 모드에 따라 배속 UI 가시성 제어 ★
        if (isPracticeMode) {
            // 연습 모드: 배속 선택 UI 보이기
            speedRadioGroup.visibility = View.VISIBLE
            speedSelectionLabel.visibility = View.VISIBLE // TextView도 보이게
            Log.d("RecyclerDebug", "Practice Mode: Speed UI Visible")
        } else {
            // 챌린지 모드: 배속 선택 UI 숨기기
            speedRadioGroup.visibility = View.GONE
            speedSelectionLabel.visibility = View.GONE // TextView도 숨기게
            Log.d("RecyclerDebug", "Challenge Mode: Speed UI Gone (Speed fixed at 1.0f)")
        }

        repository.loadChallengeMetaList(
            onSuccess = { challengeList ->
                val adapter = ChallengeAdapter(this, challengeList)

                adapter.onStartChallengeClickListener = { challengeMeta ->

                    // 1. ★ 선택된 배속 값 확인 및 설정 ★
                    val selectedSpeed = if (isPracticeMode) {
                        // 연습 모드일 때: UI에서 선택된 값 사용
                        getSelectedSpeedFromRadioGroup()
                    } else {
                        // 챌린지 모드일 때: 1.0f (정배속)로 강제 설정
                        1.0f
                    }

                    // 2. MainActivity 시작 및 모든 정보 전달
                    val intent = Intent(this, MainActivity::class.java).apply {
                        putExtra("challengeId", challengeMeta.challengeId)
                        putExtra("videoUrl", challengeMeta.videoUrl)
                        putExtra("selected_speed", selectedSpeed) // 속도 전달

                        // ★ 핵심: 모드 정보도 MainActivity에 다시 전달 ★
                        putExtra("IS_PRACTICE_MODE", isPracticeMode)
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

    // [유지] 선택된 배속 값을 읽는 유틸리티 함수
    private fun getSelectedSpeedFromRadioGroup(): Float {
        val checkedId = speedRadioGroup.checkedRadioButtonId

        if (checkedId != -1) {
            val selectedRadioButton = findViewById<RadioButton>(checkedId)
            return selectedRadioButton.tag?.toString()?.toFloatOrNull() ?: 1.0f
        }
        return 1.0f
    }
}