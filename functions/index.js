/**
 * Import function triggers from their respective submodules:
 *
 * const {onCall} = require("firebase-functions/v2/https");
 * const {onDocumentWritten} = require("firebase-functions/v2/firestore");
 *
 * See a full list of supported triggers at https://firebase.google.com/docs/functions
 */


// For cost control, you can set the maximum number of containers that can be
// running at the same time. This helps mitigate the impact of unexpected
// traffic spikes by instead downgrading performance. This limit is a
// per-function limit. You can override the limit for each function using the
// `maxInstances` option in the function's options, e.g.
// `onRequest({ maxInstances: 5 }, (req, res) => { ... })`.
// NOTE: setGlobalOptions does not apply to functions using the v1 API. V1
// functions should each use functions.runWith({ maxInstances: 10 }) instead.
// In the v1 API, each function can only serve one request per container, so
// this will be the maximum concurrent request count.


// Create and deploy your first functions
// https://firebase.google.com/docs/functions/get-started

// exports.helloWorld = onRequest((request, response) => {
//   logger.info("Hello logs!", {structuredData: true});
//   response.send("Hello from Firebase!");

/* eslint-disable */
// Firebase Functions v2 (최신 문법 없이 호환 모드)
const admin = require("firebase-admin");
admin.initializeApp();

const db = admin.firestore();
const storage = admin.storage();

const https = require("firebase-functions/v2/https");
const core = require("firebase-functions/v2");
const { onDocumentDeleted } = require("firebase-functions/v2/firestore");

// 전역 옵션
core.setGlobalOptions({
  region: "asia-northeast3",
  maxInstances: 10,
});

// 인증 확인
function assertAuth(req) {
  if (!req.auth || !req.auth.uid) {
    throw new https.HttpsError("unauthenticated", "Login required");
  }
  return req.auth.uid;
}

/** 1) 좋아요 토글 (1인 1개 + 취소) */
exports.toggleLike = https.onCall(async (req) => {
  const uid = assertAuth(req);
  const data = req.data || {};
  const videoId = String(data && data.videoId ? data.videoId : "");
  if (!videoId) throw new https.HttpsError("invalid-argument", "videoId required");

  const likeRef = db.doc("public_videos/" + videoId + "/likes/" + uid);
  const videoRef = db.doc("public_videos/" + videoId);

  return db.runTransaction(async (tx) => {
    const vSnap = await tx.get(videoRef);
    if (!vSnap.exists) throw new https.HttpsError("not-found", "video not found");
    if (vSnap.get("isActive") !== true) {
      throw new https.HttpsError("failed-precondition", "video is not active");
    }

    const lSnap = await tx.get(likeRef);
    const liked = lSnap.exists;
    const delta = liked ? -1 : 1;

    if (liked) {
      tx.delete(likeRef);
    } else {
      tx.set(likeRef, {
        uid,                       // 쿼리용
        videoId: videoId,          // ★ 타이브레이커용(정렬/커서에 사용)
        at: admin.firestore.FieldValue.serverTimestamp(),
       }, { merge: true });

    }
    tx.update(videoRef, { likesCount: admin.firestore.FieldValue.increment(delta) });

    console.log('[toggleLike] writeLike', {
      path: likeRef.path,
      liked: !liked,
      uid,
      videoId
    });
    return { liked: !liked };
  });
});

/** 2) 조회수: 재생 시작 시 1뷰 (쿨다운 3분) */
exports.addViewOnStart = https.onCall(async (req) => {
  const data = req.data || {};
  const videoId = String(data && data.videoId ? data.videoId : "");
  if (!videoId) throw new https.HttpsError("invalid-argument", "videoId required");

  const anonId = data && data.anonId ? String(data.anonId) : "";
  const uidOrAnon = (req.auth && req.auth.uid) ? req.auth.uid : "anon:" + anonId;

  const videoRef = db.doc("public_videos/" + videoId);
  const tokenRef = db.doc("public_videos/" + videoId + "/view_tokens/" + uidOrAnon);
  const COOL_MS = 3 * 60 * 1000;

  return db.runTransaction(async (tx) => {
    const vSnap = await tx.get(videoRef);
    if (!vSnap.exists || vSnap.get("isActive") !== true) return { counted: false };

    const tSnap = await tx.get(tokenRef);
    var last = 0;
    if (tSnap.exists) {
      const lv = tSnap.get("lastViewedAt");
      if (lv && typeof lv.toMillis === "function") last = lv.toMillis();
    }

    if (Date.now() - last < COOL_MS) return { counted: false };

    tx.set(tokenRef, { lastViewedAt: admin.firestore.FieldValue.serverTimestamp() }, { merge: true });
    tx.update(videoRef, { viewsCount: admin.firestore.FieldValue.increment(1) });
    return { counted: true };
  });
});

/** 3) 공개 등록 (히스토리 → 랭킹) */
exports.publishToRanking = https.onCall(async (req) => {
  const uid = assertAuth(req);
  const data = req.data || {};
  const playId = String(data && data.playId ? data.playId : "");
  if (!playId) throw new https.HttpsError("invalid-argument", "playId required");

  const histRef = db.doc("users/" + uid + "/history/" + playId);
  const snap = await histRef.get();
  if (!snap.exists) throw new https.HttpsError("not-found", "history not found");

  const challengeId = snap.get("challengeId") || "";
  const score = Number(snap.get("score") || 0);
  const videoPath = snap.get("videoPath") || "";
  const thumbPath = snap.get("thumbPath") || "";
  var videoUrl = snap.get("videoUrl") || "";
  var ownerNickname = "익명";
  var thumbUrl = "";
  var thumbPublicPath = "";

  try {
    const u = await db.doc("users/" + uid).get();
    const nick = (u.exists && u.get("nickname")) || "";
    if (nick) ownerNickname = nick;
  } catch (e) {}

  const now = admin.firestore.FieldValue.serverTimestamp();
  const bucketName = storage.bucket().name;

  // 기존 문서 재활성화 경로
  const existingPubId = snap.get("publicVideoId") || "";
  if (existingPubId) {
    const pubRef = db.doc("public_videos/" + existingPubId);
    const pubSnap = await pubRef.get();
    if (pubSnap.exists) {
      try {
        if (videoPath) {
          const dst = storage.bucket().file("public_videos/" + existingPubId + ".mp4");
          const existsArr = await dst.exists();
          const exists = Array.isArray(existsArr) ? existsArr[0] : existsArr;
          if (!exists) {
            await storage.bucket().file(videoPath).copy(dst);
            await dst.setMetadata({ cacheControl: "public, max-age=86400, immutable" });
          }
          videoUrl = "https://firebasestorage.googleapis.com/v0/b/" + bucketName +
                     "/o/" + encodeURIComponent("public_videos/" + existingPubId + ".mp4") + "?alt=media";
        }
      } catch (e) {
        throw new https.HttpsError("internal", "video copy failed");
      }

      try {
        if (thumbPath) {
          const dstT = storage.bucket().file("public_thumbs/" + existingPubId + ".jpg");
          const existsArrT = await dstT.exists();
          const existsT = Array.isArray(existsArrT) ? existsArrT[0] : existsArrT;
          if (!existsT) {
            await storage.bucket().file(thumbPath).copy(dstT);
            await dstT.setMetadata({ cacheControl: "public, max-age=86400, immutable" });
          }
          thumbPublicPath = "public_thumbs/" + existingPubId + ".jpg";
          thumbUrl = "https://firebasestorage.googleapis.com/v0/b/" + bucketName +
                     "/o/" + encodeURIComponent(thumbPublicPath) + "?alt=media";
        }
      } catch (e) {}

      const payload = {
        ownerUid: uid,
        ownerNickname: ownerNickname,
        challengeId: challengeId,
        title: challengeId,
        videoUrl: videoUrl,
        scoreAvg: score,
        updatedAt: now,
        isActive: true,
        playId: playId,                          // ★ ADDED: 재연결을 위한 playId 보관
      };
      if (thumbPublicPath) payload.thumbPath = thumbPublicPath;
      if (thumbUrl) payload.thumbUrl = thumbUrl;

      await pubRef.set(payload, { merge: true });

      // ★ ADDED: 히스토리 문서에 inRanking=true & publicVideoId 유지/기록
      await histRef.set({
        publicVideoId: existingPubId,
        inRanking: true,                         // ★ ADDED
      }, { merge: true });

      return { publicVideoId: existingPubId };
    }
  }

  // 신규 생성
  if (!videoPath && !videoUrl) {
    throw new https.HttpsError("failed-precondition", "no video to publish");
  }

  const pubRef = db.collection("public_videos").doc();
  const pubId = pubRef.id;

  try {
    if (videoPath) {
      const src = storage.bucket().file(videoPath);
      const dst = storage.bucket().file("public_videos/" + pubId + ".mp4");
      await src.copy(dst);
      await dst.setMetadata({ cacheControl: "public, max-age=86400, immutable" });
      videoUrl = "https://firebasestorage.googleapis.com/v0/b/" + bucketName +
                 "/o/" + encodeURIComponent("public_videos/" + pubId + ".mp4") + "?alt=media";
    }
  } catch (e) {
    throw new https.HttpsError("internal", "video copy failed");
  }

  try {
    if (thumbPath) {
      const srcT = storage.bucket().file(thumbPath);
      const dstT = storage.bucket().file("public_thumbs/" + pubId + ".jpg");
      await srcT.copy(dstT);
      await dstT.setMetadata({ cacheControl: "public, max-age=86400, immutable" });
      thumbPublicPath = "public_thumbs/" + pubId + ".jpg";
      thumbUrl = "https://firebasestorage.googleapis.com/v0/b/" + bucketName +
                 "/o/" + encodeURIComponent(thumbPublicPath) + "?alt=media";
    }
  } catch (e) {}

  const payloadNew = {
    ownerUid: uid,
    ownerNickname: ownerNickname,
    challengeId: challengeId,
    videoUrl: videoUrl,
    title: challengeId,
    scoreAvg: score,
    likesCount: 0,
    viewsCount: 0,
    createdAt: now,
    updatedAt: now,
    isActive: true,
    playId: playId,                              // ★ ADDED
  };
  if (thumbPublicPath) payloadNew.thumbPath = thumbPublicPath;
  if (thumbUrl) payloadNew.thumbUrl = thumbUrl;

  await pubRef.set(payloadNew);

  // ★ ADDED: 히스토리 문서에 publicVideoId + inRanking=true 기록
  await histRef.set({
    publicVideoId: pubId,                        // ★ CHANGED (update → set merge로 id 보장)
    inRanking: true,                             // ★ ADDED
  }, { merge: true });

  return { publicVideoId: pubId };
});

/** 4) 랭킹에서 내리기 (비공개 전환; 필요 시 파일 삭제) */
exports.unpublishFromRanking = https.onCall(async (req) => {
  const uid = assertAuth(req);
  const data = req.data || {};
  const publicVideoId = String(data && data.publicVideoId ? data.publicVideoId : "");
  const deleteFiles = !!(data && data.deleteFiles);
  if (!publicVideoId) throw new https.HttpsError("invalid-argument", "publicVideoId required");

  const pubRef = db.doc("public_videos/" + publicVideoId);
  const pubSnap = await pubRef.get();
  if (!pubSnap.exists) throw new https.HttpsError("not-found", "public video missing");
  if (pubSnap.get("ownerUid") !== uid) throw new https.HttpsError("permission-denied", "not owner");

  await pubRef.update({
    isActive: false,
    updatedAt: admin.firestore.FieldValue.serverTimestamp(),
  });

  // ★ ADDED: 히스토리 문서 inRanking=false 반영 (publicVideoId는 유지)
  const playId = pubSnap.get("playId") || "";    // ★ ADDED
  if (playId) {
    const histRef = db.doc(`users/${uid}/history/${playId}`);
    await histRef.set({ inRanking: false }, { merge: true }); // ★ ADDED
  }

  if (deleteFiles) {
    try { await storage.bucket().file("public_videos/" + publicVideoId + ".mp4").delete({ ignoreNotFound: true }); } catch (e) {}
    try { await storage.bucket().file("public_thumbs/" + publicVideoId + ".jpg").delete({ ignoreNotFound: true }); } catch (e) {}
  }

  return { ok: true };
});

/** 5) 히스토리 삭제 트리거: 랭킹/스토리지까지 완전 삭제 */
exports.onHistoryDeleted = onDocumentDeleted(
  {
    region: "asia-northeast3",
    document: "users/{uid}/history/{playId}",
  },
  async (event) => {
    // ★ CHANGED: v2에서는 event.data 가 "삭제 직전"의 DocumentSnapshot
    const oldSnap = event.data;                  // ★ CHANGED
    if (!oldSnap) {
      console.log("[onHistoryDeleted] no old snapshot");  // ★ ADDED
      return;
    }
    const oldData = oldSnap.data();              // ★ CHANGED
    if (!oldData) {
      console.log("[onHistoryDeleted] old snapshot has no data"); // ★ ADDED
      return;
    }

    const uid = event.params.uid;
    const playId = event.params.playId;

    let publicVideoId = oldData.publicVideoId || "";
    const videoPath = oldData.videoPath || "";
    const thumbPath = oldData.thumbPath || "";

    console.log("[onHistoryDeleted] start cleanup", { uid, playId, publicVideoId, videoPath, thumbPath }); // ★ ADDED

    // (1) 개인 업로드 파일 삭제
    try {
      if (videoPath) {
        await storage.bucket().file(videoPath).delete({ ignoreNotFound: true });
        console.log("[onHistoryDeleted] deleted user video:", videoPath); // ★ ADDED
      }
    } catch (e) {
      console.warn("[onHistoryDeleted] delete user video failed:", videoPath, e.message); // ★ ADDED
    }
    try {
      if (thumbPath) {
        await storage.bucket().file(thumbPath).delete({ ignoreNotFound: true });
        console.log("[onHistoryDeleted] deleted user thumb:", thumbPath); // ★ ADDED
      }
    } catch (e) {
      console.warn("[onHistoryDeleted] delete user thumb failed:", thumbPath, e.message); // ★ ADDED
    }

    // (2) public 문서 id가 없으면 ownerUid + playId로 역추적  // (publish 시 playId 저장해둠)
    if (!publicVideoId) {
      try {
        const qSnap = await db.collection("public_videos")
          .where("ownerUid", "==", uid)
          .where("playId", "==", playId)
          .limit(1)
          .get();
        if (!qSnap.empty) {
          publicVideoId = qSnap.docs[0].id;
          console.log("[onHistoryDeleted] found publicVideoId by query:", publicVideoId); // ★ ADDED
        }
      } catch (e) {
        console.warn("[onHistoryDeleted] public lookup failed:", e.message); // ★ ADDED
      }
    }
    if (!publicVideoId) {
      console.log("[onHistoryDeleted] no publicVideoId; nothing more to clean"); // ★ ADDED
      return; // 랭킹에 올린 적이 없으면 종료
    }

    const pubRef = db.doc("public_videos/" + publicVideoId);
    const pubSnap = await pubRef.get();
    if (!pubSnap.exists) {
      console.log("[onHistoryDeleted] public doc already missing:", publicVideoId); // ★ ADDED
    } else {
      // (3) likes / view_tokens 서브컬렉션 정리 (batch 삭제)
      try {
        const subcols = await pubRef.listCollections();
        for (const col of subcols) {
          const snap = await col.get();
          if (!snap.empty) {
            const batch = db.batch();
            snap.docs.forEach(d => batch.delete(d.ref));
            await batch.commit();
            console.log(`[onHistoryDeleted] cleared subcollection ${col.id} count=${snap.size}`); // ★ ADDED
          }
        }
      } catch (e) {
        console.warn("[onHistoryDeleted] clear subcollections failed:", e.message); // ★ ADDED
      }

      // (4) public 파일 삭제 + public 문서 삭제
      try {
        await storage.bucket().file("public_videos/" + publicVideoId + ".mp4").delete({ ignoreNotFound: true });
        console.log("[onHistoryDeleted] deleted public video:", "public_videos/" + publicVideoId + ".mp4"); // ★ ADDED
      } catch (e) {
        console.warn("[onHistoryDeleted] delete public video failed:", e.message); // ★ ADDED
      }
      try {
        await storage.bucket().file("public_thumbs/" + publicVideoId + ".jpg").delete({ ignoreNotFound: true });
        console.log("[onHistoryDeleted] deleted public thumb:", "public_thumbs/" + publicVideoId + ".jpg"); // ★ ADDED
      } catch (e) {
        console.warn("[onHistoryDeleted] delete public thumb failed:", e.message); // ★ ADDED
      }
      try {
        await pubRef.delete();
        console.log("[onHistoryDeleted] deleted public doc:", publicVideoId); // ★ ADDED
      } catch (e) {
        console.warn("[onHistoryDeleted] delete public doc failed:", e.message); // ★ ADDED
      }
    }
  }
);

// === 내가 좋아요한 공개 영상 10개씩 페이지네이션 ===
// 요청: { pageSize?: number=10, pageToken?: string }
// 응답: { items: PublicVideoItem[], nextPageToken: string|null }
exports.listMyLikedVideos = https.onCall(async (req) => {
  try {
    const uid = assertAuth(req);
    const data = req.data || {};
    const size = Math.min(Math.max(Number(data.pageSize) || 10, 1), 50);
    const pageToken = typeof data.pageToken === 'string' ? data.pageToken : null;

    // likes 컬렉션그룹에서 내 uid 문서만, at 있는 것만 최신순
    // 인덱스: uid(ASC), at(DESC), videoId(ASC) 와 1:1로 맞춤
    let q = db
      .collectionGroup('likes')
      .where('uid', '==', uid)
      .orderBy('at', 'desc')                           // 1차 정렬
      .orderBy(admin.firestore.FieldPath.documentId()) // 2차 정렬(문서ID)
      .limit(size);


    // 커서는 정렬 필드 개수(2)에 맞춰 (at, videoId) 값 튜플로 지정
    if (pageToken) {
        const cursor = await db.doc(pageToken).get();     // likes 문서 스냅샷
        if (cursor.exists && cursor.get('at') != null) {
          q = q.startAfter(cursor.get('at'), cursor.id);  // (at, 문서ID)
        }
    }

    const likeSnap = await q.get();
    if (likeSnap.empty) return { items: [], nextPageToken: null };

    // 상위 상위가 public_videos/{videoId}
    const pvRefs = likeSnap.docs.map(d => d.ref.parent.parent).filter(Boolean);
    const pvDocs = await db.getAll(...pvRefs);

    const docMap = new Map(pvDocs.map(doc => [doc.id, doc]));
    const items = likeSnap.docs
      .map(d => d.ref.parent.parent)
      .filter(Boolean)
      .map(ref => docMap.get(ref.id))
      .filter(doc => doc && doc.exists) // 존재만 확인
      .map(doc => {
        const v = doc.data() || {};
        return {
          id: doc.id,
          ownerUid: v.ownerUid || null,
          ownerNickname: v.ownerNickname || null,
          challengeId: v.challengeId || null,
          title: v.title || v.challengeId || null,
          videoUrl: v.videoUrl || null,
          scoreAvg: Number(v.scoreAvg || 0),
          likesCount: Number(v.likesCount || 0),
          viewsCount: Number(v.viewsCount || 0),
          thumbUrl: v.thumbUrl || null,
          thumbPath: v.thumbPath || null,
          createdAt: v.createdAt && v.createdAt.toMillis ? v.createdAt.toMillis() : null,
          isActive: v.isActive === true, // 내려만 줌(클라이언트에서 표시용)
        };
      });

    const last = likeSnap.docs[likeSnap.docs.length - 1];
    return { items, nextPageToken: last ? last.ref.path : null };
  } catch (e) {
    console.error('listMyLikedVideos failed:', e);
    if (e instanceof https.HttpsError) throw e;
    const msg = (e && e.message) ? e.message : 'unknown';
    throw new https.HttpsError('failed-precondition', msg);
  }
});

// === 디버그: 내 likes 컬렉션그룹 조회 결과를 직빵으로 확인 ===
exports.debugMyLikes = https.onCall(async (req) => {
  const uid = assertAuth(req);

  const data = req && req.data ? req.data : {};
  const pageSizeRaw = (typeof data.pageSize === 'number' || typeof data.pageSize === 'string') ? Number(data.pageSize) : 10;
  const size = Math.min(Math.max(pageSizeRaw || 10, 1), 50);

  // listMyLikedVideos와 동일한 쿼리 스펙(현재: at desc + videoId asc)
  const q = db.collectionGroup('likes')
    .where('uid', '==', uid)
    .orderBy('at', 'desc')
    .orderBy('videoId', 'asc')
    .limit(size);

  const snap = await q.get();
  const rows = [];
  for (let i = 0; i < snap.docs.length; i++) {
    const d = snap.docs[i];
    const obj = d.data() || {};
    let atMillis = null;
    if (obj.at && typeof obj.at.toMillis === 'function') {
      try { atMillis = obj.at.toMillis(); } catch (e) { atMillis = null; }
    }
    rows.push({
      path: d.ref.path,                  // ex) public_videos/{videoId}/likes/{uid}
      uid: obj.uid || null,
      videoId: obj.videoId || null,
      atMillis: atMillis
    });
  }

  return { count: rows.length, rows: rows };
});

// === 디버그: 특정 like 문서 path를 그대로 읽어서 확인 ===
// 요청 예: { path: "public_videos/VIDEO123/likes/UID123" }
exports.debugOneLikePath = https.onCall(async (req) => {
  assertAuth(req);

  const data = req && req.data ? req.data : {};
  const path = (typeof data.path === 'string') ? data.path : '';

  if (!(path.indexOf('public_videos/') === 0) || path.indexOf('/likes/') === -1) {
    throw new https.HttpsError('invalid-argument', 'invalid path');
  }

  const doc = await db.doc(path).get();
  const result = { exists: doc.exists, path: doc.ref.path, data: null };
  if (doc.exists) {
    result.data = doc.data();
  }
  return result;
});
