package com.ooplab.exercises_fitfuel

import android.util.Log
import kotlin.also
import kotlin.collections.drop
import kotlin.collections.filter
import kotlin.collections.flatMap
import kotlin.collections.groupBy
import kotlin.collections.map
import kotlin.collections.mapNotNull
import kotlin.text.isNotBlank
import kotlin.text.lines
import kotlin.text.split
import kotlin.text.toFloat
import kotlin.text.toInt
import kotlin.text.trim
import kotlin.to

object MotionCsvParser {

    fun parse(csvString: String): List<List<Float>> {
        val lines = csvString.lines()

        // 헤더만 존재하거나 내용이 비어 있을 경우 예외 처리
        if (lines.size <= 1) throw Exception("CSV 데이터가 비어 있거나 헤더만 존재함")

        // frame 번호와 각 관절 좌표를 추출하여 Pair로 구성
        val frameData = lines
            .drop(1) // 헤더 제거
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val tokens = line.split(",").map { it.trim() }
                try {
                    val frame = tokens[0].toInt()               // frame 번호
                    val x = tokens[3].toFloat()                 // x 좌표
                    val y = tokens[4].toFloat()                 // y 좌표
                    val z = tokens[5].toFloat()                 // z 좌표
                    val visibility = tokens[6].toFloat()        // 관절 인식 신뢰도
                    frame to listOf(x, y, z, visibility)       // Pair(frame, [x, y, z, v])
                } catch (e: Exception) {
                    Log.e("CSV", "줄 파싱 오류: '$line'", e)
                    null
                }
            }

        // frame 기준으로 그룹핑하여 각 프레임을 하나의 List<Float>로 구성
        return frameData
            .groupBy { it.first }
            .map { (_, landmarks) ->
                landmarks.flatMap { it.second } // 1 frame = 132개 float
            }
            .also {
                if (it.isEmpty()) throw Exception("모든 줄 파싱 실패 또는 frame 없음")
            }
    }
}