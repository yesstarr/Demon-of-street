package com.ooplab.exercises_fitfuel

data class ChallengeMeta(
    val challengeId: String,    // 챌린지 고유 ID
    val title: String,          // 챌린지 제목
    val videoUrl: String,       // 원본 영상 URL
    val thumbnailUrl: String    // 썸네일 이미지 URL
)