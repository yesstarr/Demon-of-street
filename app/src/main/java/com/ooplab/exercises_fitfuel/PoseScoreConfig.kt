package com.ooplab.exercises_fitfuel

import kotlin.math.pow

/**
 * 채점 가중치/보정 설정
 * - JOINT_WEIGHTS, ANGLE_WEIGHTS: 가중치
 * - GAMMA (>1): 중·하위 점수만 더 깎는 비선형 보정 (100은 유지)
 * - SCORE_SCALE: 선형 스케일(전 구간 동일 비율). 필요 없으면 1.0f로.
 */
object PoseScoreConfig {

    // 관절 가중(33개)
    val JOINT_WEIGHTS: FloatArray = FloatArray(33) { 1f }.apply {
        this[PoseIdx.LEFT_SHOULDER] = 1.3f; this[PoseIdx.RIGHT_SHOULDER] = 1.3f
        this[PoseIdx.LEFT_ELBOW] = 1.2f;    this[PoseIdx.RIGHT_ELBOW] = 1.2f
        this[PoseIdx.LEFT_WRIST] = 1.1f;    this[PoseIdx.RIGHT_WRIST] = 1.1f
        this[PoseIdx.LEFT_HIP] = 1.2f;      this[PoseIdx.RIGHT_HIP] = 1.2f
        this[PoseIdx.LEFT_KNEE] = 1.2f;     this[PoseIdx.RIGHT_KNEE] = 1.2f
        this[PoseIdx.LEFT_ANKLE] = 1.1f;    this[PoseIdx.RIGHT_ANKLE] = 1.1f
    }

    // 각도 가중(13개)
    val ANGLE_WEIGHTS: FloatArray = floatArrayOf(
        1.2f, 1.2f, 1.1f, 1.1f, 1.2f, 1.2f,
        1.1f, 1.1f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f
    )

    /** 선형 스케일 (전 구간 동일 비율). 필요 없으면 1.0f */
    var SCORE_SCALE: Float = 1.0f

    /** 비선형 감마 보정: 1보다 크면 전체적으로 내려가되 100은 유지됨 */
    var GAMMA: Double = 3.10

    /** 입력: 0..1  →  출력: 0..1  (GAMMA/선형 스케일 적용) */
    fun calibrateScore01(x: Float): Float {
        val clamped = x.coerceIn(0f, 1f)
        val curved  = clamped.toDouble().pow(GAMMA).toFloat()
        val scaled  = (curved * SCORE_SCALE).coerceIn(0f, 1f)
        return scaled
    }


    /** 입력: 0..100 →  출력: 0..100 */
    fun applyTo100(score0to100: Float): Float {
        val s01 = (score0to100 / 100f).coerceIn(0f, 1f)
        return 100f * calibrateScore01(s01)
    }
}