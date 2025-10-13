package com.ooplab.exercises_fitfuel

data class PublicVideoItem(
    val id: String,
    val ownerUid: String?,
    val challengeId: String?,
    val title: String?,
    val videoUrl: String?,
    val scoreAvg: Double,
    var likesCount: Long,
    var viewsCount: Long,
    val thumbPath: String?,
    val ownerNickname: String? = null,
    val thumbUrl: String? = null
)
