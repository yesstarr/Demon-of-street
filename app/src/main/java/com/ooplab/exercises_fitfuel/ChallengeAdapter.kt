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
// import kotlin.jvm.java // 이 import는 불필요하여 제거합니다.

class ChallengeAdapter(
    private val context: Context,
    private val challengeList: List<ChallengeMeta>,
) : RecyclerView.Adapter<ChallengeAdapter.ChallengeViewHolder>() {

    // ★ NEW: 챌린지 시작 버튼 클릭 리스너 정의 (상위 액티비티가 처리할 로직)
    var onStartChallengeClickListener: ((ChallengeMeta) -> Unit)? = null

    // ViewHolder 클래스 → item_challenge.xml의 View 참조를 저장r
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

        // 썸네일 이미지 Glide로 표시
        Glide.with(context)
            .load(item.thumbnailUrl)
            .into(holder.thumbnailImageView)

        // 썸네일 클릭 시 VideoPlayerActivity로 이동 (배속 정보와 무관하므로 기존 로직 유지)
        holder.thumbnailImageView.setOnClickListener {
            val intent = Intent(context, VideoPlayerActivity::class.java)
            intent.putExtra("videoUrl", item.videoUrl)
            intent.putExtra("thumbnailUrl", item.thumbnailUrl)
            intent.putExtra("challengeId", item.challengeId)
            intent.putExtra("showChallengeButton", true)
            context.startActivity(intent)
        }

        // "도전하기" 버튼 클릭 시 처리
        holder.startChallengeButton.setOnClickListener {
            // ★ MODIFIED: MainActivity로 직접 이동하는 대신, 외부 리스너(액티비티) 호출
            // 선택된 챌린지 정보(item)를 인자로 넘겨줍니다.
            onStartChallengeClickListener?.invoke(item)

            /*
            // [제거됨] 기존의 MainActivity로 직접 이동하는 코드는 제거됩니다.
            val intent = Intent(context, MainActivity::class.java)
            intent.putExtra("challengeId", item.challengeId)
            intent.putExtra("videoUrl", item.videoUrl)
            intent.putExtra("mode", mode)
            context.startActivity(intent)
            */
        }
    }

    // 전체 아이템 수 반환
    override fun getItemCount(): Int {
        return challengeList.size
    }
}