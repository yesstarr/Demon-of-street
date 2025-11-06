package com.ooplab.exercises_fitfuel

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.functions.ktx.functions
import com.google.firebase.ktx.Firebase

import android.widget.ImageView

private const val TAG = "LikedVideos"

class LikedVideosActivity : AppCompatActivity() {

    private lateinit var rv: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var btnMore: Button
    private lateinit var homeButton: ImageView
    private lateinit var btnOpenLeaderboard: ImageView
    private lateinit var btnMyPage: ImageView
    private lateinit var adapter: LeaderboardAdapter
    private val items = mutableListOf<PublicVideoItem>()
    private var nextToken: String? = null
    private var loading = false

    private val playerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // 재생 후 조회수/좋아요 변동 반영
        Log.d(TAG, "player returned → refresh list")
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_liked_video)

        val uid = FirebaseAuth.getInstance().currentUser?.uid
        Log.d(TAG, "onCreate: currentUser=$uid")

        rv = findViewById(R.id.rvLiked)
        progress = findViewById(R.id.progressLiked)
        btnMore = findViewById(R.id.btnLoadMore)

        // Initialize and set click listeners for bottom navigation buttons
        homeButton = findViewById(R.id.homeButton)
        btnOpenLeaderboard = findViewById(R.id.btnOpenLeaderboard)
        btnMyPage = findViewById(R.id.btnMyPage)

        homeButton.setOnClickListener { 
            val intent = Intent(this, MainScreenActivity::class.java)
            startActivity(intent)
            finish() // Finish current activity to prevent backstack issues
        }

        btnOpenLeaderboard.setOnClickListener { 
            val intent = Intent(this, LeaderboardActivity::class.java)
            startActivity(intent)
            finish() // Finish current activity
        }

        btnMyPage.setOnClickListener { 
            val intent = Intent(this, MyPageActivity::class.java)
            startActivity(intent)
            finish() // Finish current activity
        }

        rv.layoutManager = LinearLayoutManager(this)
        rv.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        rv.setHasFixedSize(true)

        adapter = LeaderboardAdapter(
            context = this,
            items = items,
            getSort = { LeaderboardAdapter.Sort.LIKES },
            onClick = { item ->
                Log.d(TAG, "item click: id=${item.id}, challengeId=${item.challengeId}")
                val it = Intent(this, VideoPlayerActivity::class.java).apply {
                    putExtra("videoUrl", item.videoUrl ?: "")
                    putExtra("publicVideoId", item.id)
                    putExtra("challengeId", item.challengeId ?: "")
                    putExtra("showChallengeButton", false)
                }
                playerLauncher.launch(it)
            },
            onLongClick = { item ->
                Log.d(TAG, "toggleLike long-press: id=${item.id}")
                Firebase.functions("asia-northeast3")
                    .getHttpsCallable("toggleLike")
                    .call(mapOf("videoId" to item.id))
                    .addOnSuccessListener { Log.d(TAG, "toggleLike success → refresh") ; refresh() }
                    .addOnFailureListener { e ->
                        Log.e(TAG, "toggleLike failed: ${e.message}", e)
                    }
                true
            }
        )
        rv.adapter = adapter

        btnMore.setOnClickListener { loadPage() }

        // 첫 로드
        refresh()
    }

    private fun refresh() {
        Log.d(TAG, "refresh()")
        items.clear()
        adapter.notifyDataSetChanged()
        nextToken = null
        updateMoreButton() // 초기 상태에서도 버튼 갱신
        loadPage()
    }

    private fun setLoading(b: Boolean) {
        loading = b
        progress.visibility = if (b) View.VISIBLE else View.GONE
        Log.d(TAG, "loading=$b")
    }

    private fun updateMoreButton() {
        btnMore.visibility = if (nextToken == null) View.GONE else View.VISIBLE
        Log.d(TAG, "updateMoreButton: nextToken=$nextToken, visible=${btnMore.visibility == View.VISIBLE}")
    }

    private fun loadPage() {
        if (loading) {
            Log.d(TAG, "loadPage: ignored (already loading)")
            return
        }
        setLoading(true)

        val payload = mutableMapOf<String, Any>("pageSize" to 10)
        nextToken?.let { payload["pageToken"] = it }
        Log.d(TAG, "call listMyLikedVideos payload=$payload")

        Firebase.functions("asia-northeast3")
            .getHttpsCallable("listMyLikedVideos")
            .call(payload)
            .addOnSuccessListener { res ->
                val data = res.data as? Map<*, *> ?: emptyMap<Any, Any>()
                val arr = data["items"] as? List<*> ?: emptyList<Any>()
                val token = data["nextPageToken"] as? String
                Log.d(TAG, "success: pageSize=${arr.size}, nextPageToken=$token")

                val more = arr.mapNotNull { raw ->
                    val m = raw as? Map<*, *> ?: return@mapNotNull null
                    PublicVideoItem(
                        id = m["id"] as? String ?: return@mapNotNull null,
                        ownerUid = m["ownerUid"] as? String,
                        ownerNickname = m["ownerNickname"] as? String,
                        challengeId = m["challengeId"] as? String,
                        title = m["title"] as? String,
                        videoUrl = m["videoUrl"] as? String,
                        scoreAvg = (m["scoreAvg"] as? Number)?.toDouble() ?: 0.0,
                        likesCount = (m["likesCount"] as? Number)?.toLong() ?: 0L,
                        viewsCount = (m["viewsCount"] as? Number)?.toLong() ?: 0L,
                        thumbUrl = m["thumbUrl"] as? String,
                        thumbPath = m["thumbPath"] as? String
                    )
                }

                val start = items.size
                items.addAll(more)
                adapter.notifyItemRangeInserted(start, more.size)
                Log.d(TAG, "items added: count=${more.size}, total=${items.size}")

                nextToken = token
                updateMoreButton()

                if (start == 0 && items.isEmpty()) {
                    Log.d(TAG, "empty first page (no liked videos)")
                    // 빈 목록이어도 화면이 까맣게만 보이지 않도록
                    rv.visibility = View.VISIBLE
                    Toast.makeText(this, "좋아요한 영상이 없습니다.", Toast.LENGTH_SHORT).show()
                }
                // 첫 페이지든 다음 페이지든 성공 시 리스트는 보여주기
                rv.visibility = View.VISIBLE
            }
            .addOnFailureListener { e ->
                val fe = e as? FirebaseFunctionsException
                val code = fe?.code?.name ?: "unknown"
                val details = fe?.details?.toString() ?: "no-details"
                Log.e(TAG, "listMyLikedVideos failed: code=$code, msg=${e.message}, details=$details", e)
                // 실패해도 화면만 까맣게 남지 않게
                rv.visibility = View.VISIBLE
            }
            .addOnCompleteListener { setLoading(false) }
    }
}
