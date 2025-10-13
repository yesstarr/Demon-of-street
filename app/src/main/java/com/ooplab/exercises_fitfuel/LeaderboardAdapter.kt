package com.ooplab.exercises_fitfuel

import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.google.firebase.ktx.Firebase
import com.google.firebase.storage.ktx.storage

class LeaderboardAdapter(
    private val context: android.content.Context,
    private val items: List<PublicVideoItem>,
    private val getSort: () -> Sort,
    private val onClick: (PublicVideoItem) -> Unit,
    private val onLongClick: (PublicVideoItem) -> Boolean
) : RecyclerView.Adapter<LeaderboardAdapter.VH>() {

    /** 이 어댑터에서 쓰는 정렬 기준 */
    enum class Sort { LIKES, VIEWS, SCORE }

    inner class VH(v: View) : RecyclerView.ViewHolder(v) {
        private val imageThumb: ImageView = v.findViewById(R.id.imageThumb)
        private val textNickname: TextView = v.findViewById(R.id.textNickname)
        private val textChallenge: TextView = v.findViewById(R.id.textChallenge)
        private val textLikes: TextView = v.findViewById(R.id.textLikes)
        private val textViews: TextView = v.findViewById(R.id.textViews)
        private val textScore: TextView = v.findViewById(R.id.textScore)

        fun bind(item: PublicVideoItem) {
            // 텍스트들
            textNickname.text = item.ownerNickname ?: "익명"
            textChallenge.text = item.title ?: item.challengeId ?: ""

            textLikes.text = "❤ ${item.likesCount}"
            textViews.text = "▶ ${item.viewsCount}"
            textScore.text = "★ ${scoreToPoints(item.scoreAvg)}점"

            // 선택된 정렬 지표만 굵게 표시(나머지는 기본)
            val sort = getSort()
            textLikes.setTypeface(null, if (sort == Sort.LIKES) Typeface.BOLD else Typeface.NORMAL)
            textViews.setTypeface(null, if (sort == Sort.VIEWS) Typeface.BOLD else Typeface.NORMAL)
            textScore.setTypeface(null, if (sort == Sort.SCORE) Typeface.BOLD else Typeface.NORMAL)

            // 썸네일 로딩: thumbUrl 우선, 없으면 thumbPath로 downloadUrl
            val placeholderRes = android.R.color.darker_gray
            val url = item.thumbUrl
            val path = item.thumbPath

            when {
                !url.isNullOrBlank() -> {
                    Glide.with(imageThumb)
                        .load(url)
                        .placeholder(placeholderRes)
                        .error(placeholderRes)
                        .into(imageThumb)
                }
                !path.isNullOrBlank() -> {
                    Glide.with(imageThumb).load(placeholderRes).into(imageThumb)
                    Firebase.storage.reference.child(path)
                        .downloadUrl
                        .addOnSuccessListener { uri ->
                            if (imageThumb.isAttachedToWindow) {
                                Glide.with(imageThumb)
                                    .load(uri)
                                    .placeholder(placeholderRes)
                                    .error(placeholderRes)
                                    .into(imageThumb)
                            }
                        }
                        .addOnFailureListener {
                            imageThumb.setImageResource(placeholderRes)
                        }
                }
                else -> imageThumb.setImageResource(placeholderRes)
            }

            itemView.setOnClickListener { onClick(item) }
            itemView.setOnLongClickListener { onLongClick(item) }
        }

        private fun scoreToPoints(scoreAvg: Double): Int =
            kotlin.math.round(scoreAvg * 100).toInt()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.leaderboard_item, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size
}