package com.ooplab.exercises_fitfuel

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.functions.ktx.functions
import com.google.firebase.ktx.Firebase

class MainScreenActivity : AppCompatActivity() {

    private lateinit var leaderboardAdapter: LeaderboardAdapter
    private val leaderboardItems = mutableListOf<PublicVideoItem>()
    private val db = FirebaseFirestore.getInstance()

    // 페이지네이션 상태(미니 섹션)
    private val pageSizeMini = 10L
    private var miniLastDoc: DocumentSnapshot? = null
    private var miniLoading = false
    private var miniReachedEnd = false

    // [추가] onResume에서 첫 회 중복 호출 방지
    private var firstResume = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mainscreen)

        findViewById<Button>(R.id.btnChallengeMode).setOnClickListener {
            val intent = Intent(this, RecyclerActivity::class.java)
            intent.putExtra("mode", "CHALLENGE")
            startActivity(intent)
        }
        findViewById<Button>(R.id.btnPracticeMode).setOnClickListener {
            val intent = Intent(this, Recycler2Activity::class.java)
            intent.putExtra("mode", "PRACTICE")
            startActivity(intent)
        }
        findViewById<ImageView>(R.id.menuButton).setOnClickListener {
            startActivity(Intent(this, MyPageActivity::class.java))
        }

        // Bottom Navigation
        findViewById<ImageView>(R.id.homeButton).setOnClickListener {
            // Current screen, do nothing
        }
        findViewById<ImageView>(R.id.btnOpenLeaderboard).setOnClickListener {
            startActivity(Intent(this, LeaderboardActivity::class.java))
        }
        findViewById<ImageView>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        

        // 랭킹 RecyclerView (미니 랭킹)
        val rv = findViewById<RecyclerView>(R.id.leaderboardRecycler)
        rv.layoutManager = LinearLayoutManager(this)
        rv.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        rv.setHasFixedSize(true)
        rv.itemAnimator = null // [유지] 깜박임 억제

        leaderboardAdapter = LeaderboardAdapter(
            context = this,
            items = leaderboardItems,
            getSort = { LeaderboardAdapter.Sort.LIKES }, // 미니 섹션에서는 정렬 UI 안씀(시그니처 맞춤)
            onClick = { item ->
                // 아이템 탭 → 재생 화면 (publicVideoId도 넘겨 조회수 카운트용)
                val it = Intent(this, VideoPlayerActivity::class.java).apply {
                    putExtra("videoUrl", item.videoUrl ?: "")
                    putExtra("publicVideoId", item.id)
                    putExtra("challengeId", item.challengeId ?: "")
                    putExtra("showChallengeButton", false)   // ★ 메인에서도 항상 숨김
                }
                startActivity(it)
            },
            onLongClick = { item ->
                // [수정] 좋아요 토글 성공 시: 즉시 숫자 반영 + 소프트 리프레시
                val pos = leaderboardItems.indexOfFirst { it.id == item.id }
                Firebase.functions("asia-northeast3")
                    .getHttpsCallable("toggleLike")
                    .call(mapOf("videoId" to item.id))
                    .addOnSuccessListener { res ->
                        val liked = ((res.data as? Map<*, *>)?.get("liked") as? Boolean) == true
                        if (pos >= 0) {
                            val delta = if (liked) 1 else -1
                            leaderboardItems[pos].likesCount =
                                (leaderboardItems[pos].likesCount + delta).coerceAtLeast(0)
                            leaderboardAdapter.notifyItemChanged(pos)
                        }
                        // [추가] 최신순 목록에서도 숫자 동기화를 위해 1회 새로고침
                        loadMiniPage(reset = true)
                        Toast.makeText(this, if (liked) "좋아요 +1" else "좋아요 취소", Toast.LENGTH_SHORT).show()
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(this, "실패: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                true
            }
        )
        rv.adapter = leaderboardAdapter

        // 스크롤 끝 근처에서 다음 페이지 로딩
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lm = recyclerView.layoutManager as LinearLayoutManager
                val lastVisible = lm.findLastVisibleItemPosition()
                val total = leaderboardItems.size
                if (total == 0) return
                if (!miniLoading && !miniReachedEnd && lastVisible >= total - 3) {
                    loadMiniPage(reset = false)
                }
            }
        })
        // 첫 페이지
        loadMiniPage(reset = true)
    }

    // [추가] 메인으로 돌아올 때마다 최신순으로 갱신(조회수/좋아요 서버 반영 반영)
    override fun onResume() {
        super.onResume()
        if (firstResume) { // onCreate 직후 중복 방지
            firstResume = false
            return
        }
        loadMiniPage(reset = true)
    }

    /** 미니 랭킹: 최신순 페이지 로드 */
    private fun loadMiniPage(reset: Boolean) {
        if (miniLoading) return
        miniLoading = true

        if (reset) {
            leaderboardItems.clear()
            leaderboardAdapter.notifyDataSetChanged()
            miniLastDoc = null
            miniReachedEnd = false
        }

        var q = db.collection("public_videos")
            .whereEqualTo("isActive", true)
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(pageSizeMini)

        miniLastDoc?.let { q = q.startAfter(it) }

        q.get()
            .addOnSuccessListener { snap ->
                val docs = snap.documents
                if (docs.isEmpty()) {
                    miniReachedEnd = true
                } else {
                    val start = leaderboardItems.size
                    val more = docs.map { it.toPublicVideoItem() }
                    leaderboardItems.addAll(more)
                    if (reset) leaderboardAdapter.notifyDataSetChanged()
                    else leaderboardAdapter.notifyItemRangeInserted(start, more.size)
                    miniLastDoc = docs.last()
                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "랭킹 로드 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            .addOnCompleteListener { miniLoading = false }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 실시간 리스너 없음 (페이지네이션은 get 기반)
    }
}

/** Firestore → 화면 아이템 변환 */
private fun DocumentSnapshot.toPublicVideoItem(): PublicVideoItem {
    return PublicVideoItem(
        id = id,
        ownerUid = getString("ownerUid"),
        // ★ 닉네임을 실제로 바인딩
        ownerNickname = getString("ownerNickname"),
        challengeId = getString("challengeId"),
        title = getString("title") ?: getString("challengeId"),
        videoUrl = getString("videoUrl"),
        scoreAvg = (get("scoreAvg") as? Number)?.toDouble() ?: 0.0,
        likesCount = (get("likesCount") as? Number)?.toLong() ?: 0L,
        viewsCount = (get("viewsCount") as? Number)?.toLong() ?: 0L,
        // ★ 썸네일 URL 우선, 없으면 경로만
        thumbUrl  = getString("thumbUrl"),
        thumbPath = getString("thumbPath")
    )
}

