package com.ooplab.exercises_fitfuel

import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import kotlin.jvm.java

class ChallengeAdapter(
    private val context: Context,
    private val challengeList: List<ChallengeMeta>
) : RecyclerView.Adapter<ChallengeAdapter.ChallengeViewHolder>() {

    // ViewHolder 클래스 → item_challenge.xml의 View 참조를 저장
    inner class ChallengeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val thumbnailImageView: ImageView = itemView.findViewById(R.id.thumbnailImageView)
        val titleTextView: TextView = itemView.findViewById(R.id.titleTextView)
        val startChallengeButton: Button = itemView.findViewById(R.id.startChallengeButton)
    }

    // ViewHolder를 생성 (item_challenge.xml inflate)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChallengeViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_challenge, parent, false)
        return ChallengeViewHolder(view)
    }

    // ViewHolder에 데이터 바인딩 (화면에 표시될 데이터 설정)
    override fun onBindViewHolder(holder: ChallengeViewHolder, position: Int) {
        val item = challengeList[position]

        // 챌린지 제목 설정
        holder.titleTextView.text = item.title

        // 썸네일 이미지 Glide로 표시 (loadThumbnailUrl() 호출 필요 없음!)
        Glide.with(context)
            .load(item.thumbnailUrl)
            .into(holder.thumbnailImageView)

        // 썸네일 클릭 시 VideoPlayerActivity로 이동
        holder.thumbnailImageView.setOnClickListener {
            val intent = Intent(context, VideoPlayerActivity::class.java)
            intent.putExtra("videoUrl", item.videoUrl)
            intent.putExtra("thumbnailUrl", item.thumbnailUrl) // 필요하면 표시용으로 넘김
            intent.putExtra("challengeId", item.challengeId) // challengeId 추가
            intent.putExtra("showChallengeButton", true)   // ★ 챌린지에서는 버튼 표시
            context.startActivity(intent)
        }

        // "도전하기" 버튼 클릭 시 MainActivity로 이동
        holder.startChallengeButton.setOnClickListener {
            val intent = Intent(context, MainActivity::class.java)
            intent.putExtra("challengeId", item.challengeId)
            intent.putExtra("videoUrl", item.videoUrl)
            context.startActivity(intent)
        }
    }

    // 전체 아이템 수 반환
    override fun getItemCount(): Int {
        return challengeList.size
    }
}