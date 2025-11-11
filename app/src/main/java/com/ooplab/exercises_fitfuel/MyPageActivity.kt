package com.ooplab.exercises_fitfuel

import android.content.Intent
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GestureDetectorCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.GridLayoutManager
import android.view.View



class MyPageActivity : AppCompatActivity() {

    private val repository = AuthRepository()
    private val historyRepo = HistoryRepository()

    private lateinit var recycler: RecyclerView
    private var items: MutableList<Map<String, Any>> = mutableListOf()
    private var adapter: HistoryAdapter? = null

    private lateinit var btnOpenLeaderboard: ImageView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mypage)

        repository.getCurrentUserInfo { userInfo ->
            if (userInfo != null) {
                findViewById<TextView>(R.id.tvName).text = "이름: ${userInfo["name"]}"
                findViewById<TextView>(R.id.tvNickname).text = "닉네임: ${userInfo["nickname"]}"
                findViewById<TextView>(R.id.tvEmail).text = "이메일: ${userInfo["email"]}"
                
            } else showToast("사용자 정보가 없습니다.")
        }

        // 히스토리 RecyclerView 초기화
        recycler = findViewById(R.id.recyclerViewHistory)
        recycler.layoutManager = GridLayoutManager(this, 2)
        recycler.isNestedScrollingEnabled = false

        // 단일 탭으로 재생(히스토리 → 도전하기 버튼 숨김)
        attachTapToRecycler()

        // 길게 누르기 제스처로 삭제 다이얼로그 실행
        attachLongPressToRecycler()

        // 첫 로드
        reloadHistory()

        // 로그아웃/뒤로 가기
        findViewById<Button>(R.id.logoutButton).setOnClickListener {
            repository.logout()
            val intent = Intent(this, LoginActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            startActivity(intent)
            finish()
        }

        findViewById<ImageView>(R.id.backButton)?.setOnClickListener { finish() }

        

        

        val homeButton = findViewById<ImageView>(R.id.homeButton)
        homeButton.setOnClickListener {
            val intent = Intent(this, MainScreenActivity::class.java)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            finish()
        }

        val btnMyPage = findViewById<ImageView>(R.id.btnMyPage)
        btnMyPage.setOnClickListener {
            // Already on MyPage, do nothing or show a toast
            Toast.makeText(this, "이미 마이페이지입니다.", Toast.LENGTH_SHORT).show()
        }

        btnOpenLeaderboard = findViewById(R.id.btnOpenLeaderboard)
        btnOpenLeaderboard.setOnClickListener {
            val intent = Intent(this, LeaderboardActivity::class.java)
            startActivity(intent)
        }

        // ★ ADDED: 좋아요한 영상 버튼 → LikedVideosActivity 이동
        findViewById<Button>(R.id.btnLikedVideos).setOnClickListener {
            startActivity(Intent(this, LikedVideosActivity::class.java))
        }

    }

    // ★ CHANGED: 결과 화면 다녀와도 갱신되도록 onResume에서 재로딩
    override fun onResume() {
        super.onResume()
        reloadHistory()
    }

    // Firestore에서 새로 로드해 UI 갱신
    private fun reloadHistory() {
        historyRepo.loadPlayHistory(
            onSuccess = { list ->
                items = list.toMutableList()
                if (adapter == null) {
                    adapter = HistoryAdapter(this, items)
                    recycler.adapter = adapter
                } else {
                    // update() 대신 어댑터를 새로 생성해서 교체
                    adapter = HistoryAdapter(this, items)
                    recycler.adapter = adapter
                }
                if (list.isEmpty()) showToast("아직 저장된 도전 영상이 없어요 ")
            },
            onError = { e -> showToast("히스토리 로드 실패: ${e.message}") }
        )
    }

    // 썸네일 길게 누르면 삭제/랭킹 메뉴
    private fun attachLongPressToRecycler() {
        val detector = GestureDetectorCompat(
            this,
            object : GestureDetector.SimpleOnGestureListener() {

                override fun onLongPress(e: MotionEvent) {
                    // android.view.View 로 고정
                    val child: View? = recycler.findChildViewUnder(e.x, e.y)
                    val pos = if (child != null) recycler.getChildAdapterPosition(child)
                    else RecyclerView.NO_POSITION
                    if (pos == RecyclerView.NO_POSITION || pos >= items.size) return

                    val item = items[pos]
                    val playId = item["playId"] as? String
                    val videoUrl = item["videoUrl"] as? String
                    val publicVideoId = item["publicVideoId"] as? String // ★ NEW
                    if (playId.isNullOrBlank() || videoUrl.isNullOrBlank()) return

                    // ★ CHANGED: inRanking 플래그로 메뉴 토글
                    val inRanking = (item["inRanking"] as? Boolean) == true // ★ CHANGED
                    val options = if (inRanking)
                        arrayOf("삭제", "랭킹에서 내리기")
                    else
                        arrayOf("삭제", "랭킹에 올리기")

                    AlertDialog.Builder(this@MyPageActivity)
                        .setTitle("메뉴")
                        .setItems(options) { _, which ->
                            when (options[which]) {
                                "삭제" -> {
                                    // ★ ADDED: 삭제 재확인 다이얼로그
                                    val msg = if (inRanking) {
                                        "이 영상은 랭킹에도 등록되어 있어요.\n정말 삭제할까요?"
                                    } else {
                                        "정말 삭제할까요?"
                                    }

                                    AlertDialog.Builder(this@MyPageActivity)
                                        .setTitle("삭제 확인")
                                        .setMessage(msg)
                                        .setPositiveButton("삭제") { _, _ ->
                                            // ★ CHANGED: 실제 삭제는 확인 후 수행
                                            historyRepo.deleteHistoryItem(
                                                activity = this@MyPageActivity,
                                                playId = playId,
                                                videoUrl = videoUrl
                                            ) { ok, err ->
                                                if (ok) {
                                                    showToast("삭제했어요")
                                                    reloadHistory()
                                                } else {
                                                    showToast("삭제 실패: $err")
                                                }
                                            }
                                        }
                                        .setNegativeButton("취소", null)
                                        .show()
                                }
                                "랭킹에 올리기" -> {
                                    historyRepo.publishToRanking(
                                        playId = playId
                                    ) { ok, vid, err ->
                                        if (ok) {
                                            showToast("랭킹에 등록했어요!")
                                            reloadHistory() // inRanking/publicVideoId 반영
                                        } else showToast("등록 실패: $err")
                                    }
                                }
                                "랭킹에서 내리기" -> {
                                    // ★ CHANGED: 안전 가드 (publicVideoId 없으면 오류 메시지)
                                    if (publicVideoId.isNullOrBlank()) {
                                        showToast("이미 랭킹에서 내려간 항목이에요.")
                                        reloadHistory()
                                        return@setItems
                                    }
                                    historyRepo.unpublishFromRanking(
                                        playId = playId,
                                        publicVideoId = publicVideoId
                                    ) { ok, err ->
                                        if (ok) {
                                            showToast("랭킹에서 내렸어요")
                                            reloadHistory()
                                        } else showToast("실패: $err")
                                    }
                                }
                            }
                        }
                        .show()
                }

                override fun onDown(e: MotionEvent): Boolean = true
            }
        )

        recycler.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                detector.onTouchEvent(e)
                return false
            }
            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) { /* no-op */ }
            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) { }
        })
    }

    private fun attachTapToRecycler() {
        val detector = GestureDetectorCompat(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    val child: View? = recycler.findChildViewUnder(e.x, e.y)
                    val pos = if (child != null) recycler.getChildAdapterPosition(child)
                    else RecyclerView.NO_POSITION
                    if (pos == RecyclerView.NO_POSITION || pos >= items.size) return false

                    val item = items[pos]
                    val videoUrl = item["videoUrl"] as? String ?: return false
                    val challengeId = item["challengeId"] as? String ?: "history"

                    val intent = Intent(this@MyPageActivity, VideoPlayerActivity::class.java).apply {
                        putExtra("videoUrl", videoUrl)
                        putExtra("challengeId", challengeId)
                        putExtra("showChallengeButton", false)
                    }
                    startActivity(intent)
                    return true
                }

                override fun onDown(e: MotionEvent): Boolean = true
            }
        )

        recycler.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                detector.onTouchEvent(e)
                return false
            }
            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) { /* no-op */ }
            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) { }
        })
    }


    private fun showToast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}