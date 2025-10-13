package com.ooplab.exercises_fitfuel

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.ktx.Firebase
import com.google.firebase.functions.ktx.functions
import androidx.activity.result.contract.ActivityResultContracts
import android.os.Handler
import android.os.Looper

class LeaderboardActivity : AppCompatActivity() {

    // ✅ 액티비티의 Sort enum 삭제하고, 어댑터의 Sort 사용
    private lateinit var rv: RecyclerView
    private lateinit var progress: ProgressBar
    private lateinit var adapter: LeaderboardAdapter
    private val db = FirebaseFirestore.getInstance()

    private var currentSort: LeaderboardAdapter.Sort = LeaderboardAdapter.Sort.LIKES
    private val items = mutableListOf<PublicVideoItem>()

    // 페이지네이션 상태
    private val pageSize = 50L
    private var lastDoc: DocumentSnapshot? = null
    private var loading = false
    private var reachedEnd = false

    // 초기화/중복 호출 방지
    private var initializingSort = false

    // [추가] onResume에서 첫 회 새로고침 방지
    private var firstResume = true

    // [추가] 플레이어 종료 시 무조건 1회 새로고침하는 런처
    private val playerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // 혹시 로딩 중 플래그가 남았어도 한 번 더 진행될 수 있게 리셋
        loading = false
        // CF가 조회수를 기록하기까지 약간의 지연을 고려해 살짝 딜레이 후 로드
        Handler(Looper.getMainLooper()).postDelayed({
            loadPage(reset = true)
        }, 150)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_leaderboard)

        rv = findViewById(R.id.rvLeaderboard)
        progress = findViewById(R.id.progress)

        rv.layoutManager = LinearLayoutManager(this)
        rv.addItemDecoration(DividerItemDecoration(this, DividerItemDecoration.VERTICAL))
        rv.setHasFixedSize(true)
        rv.itemAnimator = null // 전체 갱신 시 깜박임 억제 ★

        adapter = LeaderboardAdapter(
            context = this,
            items = items,
            getSort = { currentSort },             // ✅ 타입 일치: LeaderboardAdapter.Sort
            onClick = { item ->
                // [유지] 플레이어 화면으로 이동 (CF가 실제 조회수도 올림)
                val it = Intent(this, VideoPlayerActivity::class.java).apply {
                    putExtra("videoUrl", item.videoUrl ?: "")
                    putExtra("publicVideoId", item.id)
                    putExtra("challengeId", item.challengeId ?: "")
                    putExtra("showChallengeButton", false)  // ★ 랭킹은 항상 숨김
                }
                playerLauncher.launch(it) // ★ 플레이어 종료 직후 콜백에서 새로고침
            },
            onLongClick = { item ->
                // [수정] 좋아요 토글 성공 시: 즉시 숫자 반영 + 정렬 반영을 위해 한 번 리셋 로드
                val pos = items.indexOfFirst { it.id == item.id }
                Firebase.functions("asia-northeast3")
                    .getHttpsCallable("toggleLike")
                    .call(mapOf("videoId" to item.id))
                    .addOnSuccessListener { res ->
                        val liked = ((res.data as? Map<*, *>)?.get("liked") as? Boolean) == true
                        if (pos >= 0) {
                            val cur = items[pos]
                            val delta = if (liked) 1 else -1
                            val newLikes = (cur.likesCount + delta).coerceAtLeast(0)
                            items[pos] = cur.copy(likesCount = newLikes)
                            adapter.notifyItemChanged(pos)
                        }
                        // [추가] 순위 변동 가능 → 현재 정렬 기준으로 다시 로드(1회)
                        loadPage(reset = true)
                        Toast.makeText(this, if (liked) "좋아요 +1" else "좋아요 취소", Toast.LENGTH_SHORT).show()
                    }
                    .addOnFailureListener {
                        Toast.makeText(this, "좋아요 실패: ${it.message}", Toast.LENGTH_SHORT).show()
                    }
                true
            }
        )
        rv.adapter = adapter

        // 스크롤 끝 근처에서 다음 페이지 로딩
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0) return
                val lm = recyclerView.layoutManager as LinearLayoutManager
                val lastVisible = lm.findLastVisibleItemPosition()
                val total = items.size
                if (total == 0) return
                if (!loading && !reachedEnd && lastVisible >= total - 3) {
                    loadPage(reset = false)
                }
            }
        })

        // 라디오 버튼: 무음 초기화 → 리스너 연결 → 최초 1회 로드 (중복 호출 방지)
        val group = findViewById<RadioGroup>(R.id.groupSort)
        initializingSort = true
        findViewById<RadioButton>(R.id.rbLikes).isChecked = true
        currentSort = LeaderboardAdapter.Sort.LIKES
        initializingSort = false

        group.setOnCheckedChangeListener { _, checkedId ->
            if (initializingSort) return@setOnCheckedChangeListener
            val newSort = when (checkedId) {
                R.id.rbViews -> LeaderboardAdapter.Sort.VIEWS
                R.id.rbScore -> LeaderboardAdapter.Sort.SCORE
                else -> LeaderboardAdapter.Sort.LIKES
            }
            if (newSort == currentSort) return@setOnCheckedChangeListener // 동일 선택 무시 ★
            currentSort = newSort
            loadPage(reset = true)
        }

        // 첫 페이지 1회만 로드 ★
        loadPage(reset = true)
    }

    private fun baseQueryForSort(): Query {
        val base = db.collection("public_videos").whereEqualTo("isActive", true)
        return when (currentSort) {
            LeaderboardAdapter.Sort.LIKES ->
                base.orderBy("likesCount", Query.Direction.DESCENDING)
                    .orderBy("createdAt", Query.Direction.DESCENDING) // 동률이면 최신
            LeaderboardAdapter.Sort.VIEWS ->
                base.orderBy("viewsCount", Query.Direction.DESCENDING)
                    .orderBy("createdAt", Query.Direction.DESCENDING)
            LeaderboardAdapter.Sort.SCORE ->
                base.orderBy("scoreAvg", Query.Direction.DESCENDING)
                    .orderBy("createdAt", Query.Direction.DESCENDING)
        }
    }

    // [유지] onResume에서도 백업용으로 한 번 새로고침
    override fun onResume() {
        super.onResume()
        if (firstResume) { firstResume = false; return }
        loadPage(reset = true)
    }

    private fun showLoadingForReset(show: Boolean) {
        rv.visibility = if (show) View.INVISIBLE else View.VISIBLE
        progress.visibility = if (show) View.VISIBLE else View.GONE
    }

    private fun loadPage(reset: Boolean) {
        if (loading) return
        loading = true

        if (reset) {
            showLoadingForReset(true)
            items.clear()
            adapter.notifyDataSetChanged()
            lastDoc = null
            reachedEnd = false
            // [삭제] backfillTriedOnce = false
        }

        var q = baseQueryForSort().limit(pageSize)
        lastDoc?.let { q = q.startAfter(it) }

        q.get()
            .addOnSuccessListener { snap ->
                val docs = snap.documents
                if (docs.isEmpty()) {
                    reachedEnd = true
                } else {
                    val start = items.size
                    val more = docs.map { it.toPublicVideoItem() }
                    items.addAll(more)

                    // [유지] 첫 페이지는 전체 갱신, 이후는 증분 갱신
                    if (reset) adapter.notifyDataSetChanged()
                    else adapter.notifyItemRangeInserted(start, more.size)

                    lastDoc = docs.last()

                }
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "불러오기 실패: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            .addOnCompleteListener {
                loading = false
                if (reset) showLoadingForReset(false)
            }
    }
}

private fun DocumentSnapshot.toPublicVideoItem(): PublicVideoItem {
    return PublicVideoItem(
        id = id,
        ownerUid = getString("ownerUid"),
        ownerNickname = getString("ownerNickname"),
        challengeId = getString("challengeId"),
        title = getString("title") ?: getString("challengeId"),
        videoUrl = getString("videoUrl"),
        scoreAvg = (get("scoreAvg") as? Number)?.toDouble() ?: 0.0,
        likesCount = (get("likesCount") as? Number)?.toLong() ?: 0L,
        viewsCount = (get("viewsCount") as? Number)?.toLong() ?: 0L,
        thumbUrl = getString("thumbUrl"),
        thumbPath = getString("thumbPath")
    )
}