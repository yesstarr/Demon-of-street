package com.ooplab.exercises_fitfuel

import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import java.text.SimpleDateFormat
import java.util.Locale
import com.google.firebase.Timestamp

class HistoryAdapter(
    private val activity: AppCompatActivity,
    private val historyList: List<Map<String, Any>>
) : RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder>() {

    inner class HistoryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val thumbnail: ImageView = view.findViewById(R.id.thumbnail)
        private val scoreText: TextView = view.findViewById(R.id.tvScore)
        private val titleText: TextView = view.findViewById(R.id.tvTitle)
        private val dateText: TextView = view.findViewById(R.id.tvDate)

        fun bind(item: Map<String, Any>) {
            val videoUrl = item["videoUrl"] as? String ?: return

            // 점수(0~1) → 0~100 정수로 표시
            val score = (item["score"] as? Number)?.toFloat() ?: 0f
            scoreText.text = "점수: ${"%.0f".format(score * 100)}"

            // 제목(사람이 읽는 이름이 있으면 사용, 없으면 challengeId로 대체)
            val title = (item["challengeName"] as? String)
                ?: (item["challengeId"] as? String ?: "Unknown")
            titleText.text = title

            // 날짜(Timestamp → yyyy.MM.dd HH:mm)
            val ts = item["playedAt"] as? Timestamp
            dateText.text = ts?.toDate()?.let {
                SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(it)
            } ?: ""


            // 썸네일(간단 버전: 비디오 첫 프레임 자동 로드)
            Glide.with(itemView)
                .load(videoUrl)
                .into(thumbnail)

            // 클릭 → VideoPlayerActivity로 재생
            itemView.setOnClickListener {
                val intent = Intent(itemView.context, VideoPlayerActivity::class.java).apply {
                    putExtra("videoUrl", videoUrl)
                    putExtra("challengeId", item["challengeId"] as? String ?: "")
                }
                itemView.context.startActivity(intent)
            }
        }
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_history, parent, false)
        return HistoryViewHolder(view)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        holder.bind(historyList[position])
    }

    override fun getItemCount(): Int = historyList.size
}