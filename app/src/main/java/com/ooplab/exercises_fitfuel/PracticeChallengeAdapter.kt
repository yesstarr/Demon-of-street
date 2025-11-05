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

class PracticeChallengeAdapter(
    private val context: Context,
    private val challengeList: List<ChallengeMeta>,
    private val onItemClick: (ChallengeMeta) -> Unit // Lambda for item clicks
) : RecyclerView.Adapter<PracticeChallengeAdapter.PracticeChallengeViewHolder>() {

    inner class PracticeChallengeViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val thumbnailImageView: ImageView = itemView.findViewById(R.id.thumbnailImageView)
        val titleTextView: TextView = itemView.findViewById(R.id.titleTextView)
        val startChallengeButton: Button = itemView.findViewById(R.id.startChallengeButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PracticeChallengeViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_challenge, parent, false)
        return PracticeChallengeViewHolder(view)
    }

    override fun onBindViewHolder(holder: PracticeChallengeAdapter.PracticeChallengeViewHolder, position: Int) {
        val item = challengeList[position]

        holder.titleTextView.text = item.title

        Glide.with(context)
            .load(item.thumbnailUrl)
            .into(holder.thumbnailImageView)

        // Call the onItemClick lambda when thumbnail is clicked
        holder.thumbnailImageView.setOnClickListener {
            onItemClick(item)
        }

        // Call the onItemClick lambda when startChallengeButton is clicked
        holder.startChallengeButton.setOnClickListener {
            onItemClick(item)
        }
    }

    override fun getItemCount(): Int {
        return challengeList.size
    }
}