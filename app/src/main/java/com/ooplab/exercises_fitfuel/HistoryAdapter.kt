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
            val status = item["status"] as? String ?: "ready" // ★ CHANGED
            // ★ CHANGED: videoUrl 없어도 바인딩 계속 진행
            val videoUrl = item["videoUrl"] as? String

            // 점수(0~1) → 0~100 정수로 표시
            val score = (item["score"] as? Number)?.toFloat() ?: 0f
            scoreText.text = "점수: ${"%.0f".format(score * 100)}"

            // 제목(사람이 읽는 이름이 있으면 사용, 없으면 challengeId로 대체)
            val title = (item["challengeName"] as? String)
                ?: (item["challengeId"] as? String ?: "Unknown")
            titleText.text = if (status == "uploading") "$title (업로드 중…)" else title   // ★ CHANGED

            // 날짜(Timestamp → yyyy.MM.dd HH:mm)
            val ts = item["playedAt"] as? Timestamp
            dateText.text = ts?.toDate()?.let {
                SimpleDateFormat("yyyy.MM.dd HH:mm", Locale.KOREA).format(it)
            } ?: ""

            // ★ CHANGED: 랭킹 배지 표시 기준을 publicVideoId → inRanking 으로 변경
            val badge = itemView.findViewById<TextView?>(R.id.badgeRanking)
            val inRanking = (item["inRanking"] as? Boolean) == true   // ★ CHANGED
            badge?.visibility = if (inRanking) View.VISIBLE else View.GONE  // ★ CHANGED


            // ★ CHANGED: 썸네일/클릭 처리 - videoUrl 유무에 따라 분기
            if (!videoUrl.isNullOrBlank()) {
                com.bumptech.glide.Glide.with(itemView).asBitmap()
                    .load(videoUrl)
                    .frame(0)              // ★ 첫 프레임
                    .into(thumbnail)
                itemView.isEnabled = true
                itemView.setOnClickListener {
                    val intent = android.content.Intent(itemView.context, VideoPlayerActivity::class.java).apply {
                        putExtra("videoUrl", videoUrl)
                        putExtra("challengeId", item["challengeId"] as? String ?: "")
                        putExtra("showChallengeButton", false)  // ★ 히스토리는 항상 숨김
                    }
                    itemView.context.startActivity(intent)
                }
            } else {
                thumbnail.setImageResource(android.R.color.darker_gray) // ★ placeholder
                itemView.isEnabled = false
                itemView.setOnClickListener(null) // ★ 클릭 비활성화
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